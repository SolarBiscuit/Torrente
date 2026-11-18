package com.solarbiscuit.atmosphera.torrente.water;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.client.DevFlags;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.gpu.PostProgram;
import com.solarbiscuit.atmosphera.lib.gpu.Textures;
import com.solarbiscuit.atmosphera.lib.world.WaterColumn;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.torrente.Torrente;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Interactive ripples, 0.4.0 "Contact": players, mobs, boats and items moving through the water surface send rings
 * across it. A wave-equation grid of 128x128 cells, 4 cells per block (32x32 blocks), centred on the camera:
 * {@code next = (sum of the 4 neighbours) / 2 - previous}, damped, the stable update the ideas review checked. It runs
 * as a ping-pong of two RGBA16F render targets in a fragment pass (GL 3.3, no compute) at the {@code BEFORE_TRANSLUCENT}
 * stage, 20 steps a second whatever the frame rate: rings travel about 3.5 blocks a second and fade within a few.
 * <p>
 * Texels: r the height now, g the height a step ago, ba the height's slope along x and z, which the water shader adds
 * to its normal with one fetch. When the camera crosses a cell the grid scrolls (the update reads the old state
 * shifted), so rings stay where they were made. Impulses ("splats") come from the client tick: up to 16 entities within
 * 16 blocks whose box crosses the water surface and that move, by their horizontal speed, or a bigger one where
 * something falls in or rises out (0.5.3: the nearest first, and a breach also throws spray). Rings show from above
 * and from under the water (0.5.3). Not on Voxy LOD water.
 */
public final class Ripples {
    static final int SIZE = 128;
    private static final int PER_BLOCK = 4;
    private static final float STEP = 1f / 20f;
    private static final int MAX_SPLATS = 16;

    private static AtmosFeature feature;
    /** 0.5.1: the shared post-program holder (lib 0.11.0). {@link #failed}: the grid's framebuffers. */
    private static final PostProgram PROGRAM = new PostProgram(Torrente.LOGGER, "Torrente ripples", "Torrente ripples",
            () -> GlslPrograms.FULLSCREEN_VERTEX, () -> "#version 330 core\n" + Ripples.STEP_GLSL,
            "u_Prev", "u_Shift", "u_SplatCount", "u_Splat");
    private static boolean failed;
    private static final int[] TEX = new int[2];
    private static final int[] FBO = new int[2];
    private static int current;
    private static boolean fresh = true;
    private static double accumulated;
    // Grid origin, in cells (world x * 4), of the state in TEX[current], and of the next step.
    private static int originX;
    private static int originZ;
    private static long drawnFrame = -1;

    // Splats from the client tick, consumed by the next step: world x, z, radius (blocks), strength.
    private static final float[] SPLAT = new float[MAX_SPLATS * 4];
    private static final float[] SPLAT_CELLS = new float[MAX_SPLATS * 4];
    private static int splats;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private Ripples() {
    }

    static void register() {
        feature = AtmosFeature.builder(Torrente.MOD_ID, "ripples")
                .provides(Capability.WATER_SHADING)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.WORLD_PASS)
                .enabledWhen(() -> TorrenteConfig.shading.enabled().get() && WaterFeatures.notVanilla() && TorrenteConfig.ripples.get())
                .register();
        PostPasses.register(feature, PostPasses.Stage.BEFORE_TRANSLUCENT, 0, "torrente_ripples", Ripples::render);
        TerrainSamplers.request(feature, "torrente_RippleTex", GL11C.GL_TEXTURE_2D, TerrainSamplers.Stage.FRAGMENT, 39,
                () -> TEX[current]);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_TRANSLUCENT, 11,
                "uniform sampler2D torrente_RippleTex;\n");
        AtmosClient.onLeaveLevel(Ripples::leaveLevel);
        FrameUniforms.vec4("torrente_Ripple", "xy: the grid's corner relative to the camera (blocks), z: strength (0 off)",
                Ripples::writeSlot);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.TickEvent.ClientTickEvent e) -> {
                    if (e.phase == net.minecraftforge.event.TickEvent.Phase.END) {
                        collect();
                    }
                });
    }

    static String macro() {
        return feature.macro();
    }

    private static void writeSlot(float[] out) {
        out[0] = out[1] = out[2] = out[3] = 0f;
        if (feature == null || !feature.isActive() || AtmosClient.frame() - drawnFrame > 1) {
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        out[0] = (float) (originX / (double) PER_BLOCK - camera.x);
        out[1] = (float) (originZ / (double) PER_BLOCK - camera.z);
        out[2] = 0.6f;
    }

    /** Client tick: entities moving through the water surface near the camera. */
    private static void collect() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || feature == null || !feature.isActive() || mc.isPaused()) {
            return;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Entity cameraEntity = mc.getCameraEntity();
        // 0.4.2: added to what the last step has not used yet (a tick without a step lost its splashes).
        // 0.5.3: the free slots go to the nearest entities (the camera's own first), not the first ones listed.
        int room = MAX_SPLATS - splats;
        int candidates = 0;
        int farthest = -1;
        boolean breachParticles = TorrenteConfig.rippleBreachParticles.get();
        for (Entity entity : level.entitiesForRendering()) {
            if (room <= 0) {
                break;
            }
            double dx = entity.getX() - camera.x;
            double dz = entity.getZ() - camera.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > 16.0 * 16.0 || entity.isSpectator()) {
                continue;
            }
            if (entity == cameraEntity) {
                d2 = -1.0;
            } else if (candidates == room && d2 >= CAND_D2[farthest]) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            double vx = entity.getX() - entity.xo;
            double vz = entity.getZ() - entity.zo;
            double vy = entity.getY() - entity.yo;
            // 0.4.2: crossing the surface since the last tick counts too (a falling item passes it within one tick).
            double lowest = Math.min(box.minY, box.minY - vy);
            double highest = Math.max(box.maxY, box.maxY - vy);
            // 0.5.3: the surface anywhere along the box's swept height. Feet more than a block under it (an elder
            // guardian, a drowned, a player upright in deep water) found only water above and were skipped.
            float surface = WaterColumn.surfaceAcross(level, Mth.floor(entity.getX()), Mth.floor(lowest),
                    Mth.floor(highest), Mth.floor(entity.getZ()), POS);
            if (Float.isNaN(surface) || lowest > surface + 0.05 || highest < surface - 0.05) {
                continue;
            }
            double speed = Math.sqrt(vx * vx + vz * vz);
            float strength = (float) Math.min(speed / 0.3, 1.0) * 0.35f;
            int kind = KIND_MOVE;
            float radius = (float) Math.max(0.3, Math.min(1.5, box.getXsize() * 0.6));
            if (vy < -0.15) {
                // Falling in: a bigger ring.
                float fall = (float) Math.min(-vy / 0.6, 1.0) * 0.8f;
                if (fall > strength) {
                    strength = fall;
                    kind = KIND_FALL;
                }
            } else if (vy > 0.12) {
                // 0.5.3: rising through the surface (a fish or a dolphin coming up, a player jumping out).
                float rise = (float) Math.min(vy / 0.5, 1.0) * 0.6f;
                if (rise > strength) {
                    strength = rise;
                    kind = KIND_RISE;
                }
                if (box.minY - vy < surface && box.minY > surface) {
                    // Breach: the whole box left the water this tick. A wider ring and a burst of spray.
                    kind = KIND_BREACH;
                    radius = Math.min(2.0f, radius * 1.3f);
                    if (breachParticles) {
                        breachSplash(level, entity, box, surface);
                    }
                }
            }
            if (strength < 0.03f) {
                continue;
            }
            int slot;
            if (candidates < room) {
                slot = candidates++;
            } else {
                slot = farthest;
            }
            CAND[slot * 4] = (float) entity.getX();
            CAND[slot * 4 + 1] = (float) entity.getZ();
            CAND[slot * 4 + 2] = radius;
            CAND[slot * 4 + 3] = strength;
            CAND_D2[slot] = d2;
            CAND_KIND[slot] = kind;
            CAND_ENTITY[slot] = entity;
            if (candidates == room) {
                farthest = 0;
                for (int i = 1; i < candidates; i++) {
                    if (CAND_D2[i] > CAND_D2[farthest]) {
                        farthest = i;
                    }
                }
            }
        }
        int n = splats;
        System.arraycopy(CAND, 0, SPLAT, n * 4, candidates * 4);
        if (DevFlags.RIPPLE_LOG) {
            for (int i = 0; i < candidates; i++) {
                LOG_COUNTS.computeIfAbsent(CAND_ENTITY[i].getType(), t -> new int[KINDS])[CAND_KIND[i]]++;
            }
            if (++logTicks >= 20) {
                logTicks = 0;
                logCounts();
            }
        }
        java.util.Arrays.fill(CAND_ENTITY, 0, candidates, null);
        n += candidates;
        if (n > splats && !loggedSplash) {
            loggedSplash = true;
            Torrente.LOGGER.info("Ripples: first splash at {} {} (strength {})", SPLAT[splats * 4], SPLAT[splats * 4 + 1],
                    SPLAT[splats * 4 + 3]);
        }
        splats = n;
    }

    /** 0.5.3: spray where something breaks out of the water (vanilla's splash only plays where things fall in). */
    private static void breachSplash(ClientLevel level, Entity entity, AABB box, float surface) {
        RandomSource random = level.random;
        double width = box.getXsize();
        int count = Math.min(12, 4 + (int) (width * 6.0));
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() * 2.0 - 1.0) * width * 0.7;
            double oz = (random.nextDouble() * 2.0 - 1.0) * width * 0.7;
            level.addParticle(ParticleTypes.SPLASH, entity.getX() + ox, surface + 0.05, entity.getZ() + oz,
                    ox * 0.1, 0.1 + random.nextDouble() * 0.15, oz * 0.1);
        }
    }

    private static void logCounts() {
        if (LOG_COUNTS.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder("Ripples: last 1 s:");
        LOG_COUNTS.forEach((type, counts) -> line.append(' ').append(EntityType.getKey(type).getPath())
                .append(" move=").append(counts[KIND_MOVE]).append(" fall=").append(counts[KIND_FALL])
                .append(" rise=").append(counts[KIND_RISE]).append(" breach=").append(counts[KIND_BREACH]).append(';'));
        Torrente.LOGGER.info(line.toString());
        LOG_COUNTS.clear();
    }

    private static boolean loggedSplash;
    private static final int KIND_MOVE = 0;
    private static final int KIND_FALL = 1;
    private static final int KIND_RISE = 2;
    private static final int KIND_BREACH = 3;
    private static final int KINDS = 4;
    // 0.5.3: this tick's nearest candidates (fixed arrays, nothing allocated per tick).
    private static final float[] CAND = new float[MAX_SPLATS * 4];
    private static final double[] CAND_D2 = new double[MAX_SPLATS];
    private static final int[] CAND_KIND = new int[MAX_SPLATS];
    private static final Entity[] CAND_ENTITY = new Entity[MAX_SPLATS];
    // Dev flag ripple_log only.
    private static final java.util.Map<EntityType<?>, int[]> LOG_COUNTS = new java.util.LinkedHashMap<>();
    private static int logTicks;

    /**
     * 0.5.2: render tick. Frees the grid while ripples are off or no world is shown; it was kept until a shader reload.
     * The next frame with ripples on starts a still grid.
     */
    static void update() {
        if (TEX[0] != 0 && (feature == null || !feature.isActive() || Minecraft.getInstance().level == null)) {
            releaseTargets();
        }
    }

    /**
     * 0.5.2: leaving a level (main thread). The next level starts a still grid, and splashes the last step did not use
     * are dropped; before, the old world's rings and splashes carried into the new one where its coordinates overlap.
     * CPU state only: at shutdown the library has freed the textures already.
     */
    private static void leaveLevel() {
        splats = 0;
        accumulated = 0.0;
        fresh = true;
        drawnFrame = -1;
    }


    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (failed || PROGRAM.failed() || mc.level == null) {
            return;
        }
        if (!PROGRAM.ready()) {
            return;
        }
        if (TEX[0] == 0 && !createTargets()) {
            return;
        }
        accumulated = Math.min(accumulated + AtmosClock.deltaSeconds(), STEP * 2.0);
        Vec3 camera = ctx.camera();
        int wantX = (int) Math.floor(camera.x * PER_BLOCK) - SIZE / 2;
        int wantZ = (int) Math.floor(camera.z * PER_BLOCK) - SIZE / 2;
        if (fresh) {
            originX = wantX;
            originZ = wantZ;
        }
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GpuTimers.begin("torrente_ripples_step");
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glViewport(0, 0, SIZE, SIZE);
            GL20C.glUseProgram(PROGRAM.id());
            GL20C.glUniform1i(PROGRAM.u(0), 14);
            int steps = 0;
            while ((accumulated >= STEP || fresh) && steps < 2) {
                accumulated = Math.max(0.0, accumulated - STEP);
                int shiftX = fresh ? 0 : wantX - originX;
                int shiftZ = fresh ? 0 : wantZ - originZ;
                int n = steps == 0 ? splats : 0;
                for (int i = 0; i < n; i++) {
                    SPLAT_CELLS[i * 4] = SPLAT[i * 4] * PER_BLOCK - wantX;
                    SPLAT_CELLS[i * 4 + 1] = SPLAT[i * 4 + 1] * PER_BLOCK - wantZ;
                    SPLAT_CELLS[i * 4 + 2] = SPLAT[i * 4 + 2] * PER_BLOCK;
                    SPLAT_CELLS[i * 4 + 3] = SPLAT[i * 4 + 3];
                }
                int next = 1 - current;
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, FBO[next]);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, TEX[current]);
                GL20C.glUniform4f(PROGRAM.u(1), shiftX, shiftZ, fresh ? 1f : 0f, 0.975f);
                GL20C.glUniform1i(PROGRAM.u(2), n);
                if (n > 0) {
                    GL20C.glUniform4fv(PROGRAM.u(3), stack.mallocFloat(n * 4).put(SPLAT_CELLS, 0, n * 4).flip());
                }
                GlslPrograms.drawFullscreen();
                current = next;
                originX = wantX;
                originZ = wantZ;
                fresh = false;
                steps++;
            }
            if (steps > 0) {
                splats = 0;
            }
            GpuTimers.end("torrente_ripples_step");
            drawnFrame = AtmosClient.frame();
        } finally {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            saved.restore();
        }
    }

    private static boolean createTargets() {
        for (int i = 0; i < 2; i++) {
            TEX[i] = Textures.create2D("Torrente ripples " + i, SIZE, SIZE, GL30C.GL_RGBA16F, GL11C.GL_RGBA, GL11C.GL_FLOAT,
                    GL11C.GL_LINEAR, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
            FBO[i] = Textures.framebuffer("Torrente ripples " + i, TEX[i]);
            if (FBO[i] == 0) {
                Torrente.LOGGER.error("[Atmosphera] Torrente ripples are off: a framebuffer is incomplete");
                failed = true;
            }
        }
        fresh = true;
        if (failed) {
            releaseTargets();
        }
        return !failed;
    }


    private static void releaseTargets() {
        for (int i = 0; i < 2; i++) {
            if (FBO[i] != 0) {
                GpuResources.deleteFramebuffer(FBO[i]);
            }
            if (TEX[i] != 0) {
                GpuResources.deleteTexture(TEX[i]);
            }
            FBO[i] = TEX[i] = 0;
        }
        current = 0;
        fresh = true;
    }

    /** Frees the program and the grid (the lib's shader reload); the next frame starts a still grid. */
    public static void release() {
        PROGRAM.release();
        releaseTargets();
        failed = false;
        drawnFrame = -1;
    }

    static final String STEP_GLSL = """
            uniform sampler2D u_Prev;
            uniform vec4 u_Shift;      // xy cells the grid moved since the last step, z 1 = start still, w damping
            uniform int u_SplatCount;
            uniform vec4 u_Splat[16];  // x, z in cells of the new grid, radius in cells, strength
            in vec2 v_Uv;
            out vec4 fragColor;

            const int N = 128;

            // The previous state at a cell of the new grid (zero where it scrolled in).
            vec2 rip_prev(ivec2 c) {
                ivec2 q = c + ivec2(u_Shift.xy);
                if (u_Shift.z > 0.5 || any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, ivec2(N)))) {
                    return vec2(0.0);
                }
                return texelFetch(u_Prev, q, 0).rg;
            }

            void main() {
                ivec2 c = ivec2(gl_FragCoord.xy);
                vec2 here = rip_prev(c);
                float e = rip_prev(c + ivec2(1, 0)).r;
                float w = rip_prev(c - ivec2(1, 0)).r;
                float n = rip_prev(c + ivec2(0, 1)).r;
                float s = rip_prev(c - ivec2(0, 1)).r;
                float next = ((e + w + n + s) * 0.5 - here.g) * u_Shift.w;
                for (int i = 0; i < u_SplatCount; i++) {
                    vec2 d = vec2(c) + 0.5 - u_Splat[i].xy;
                    next += u_Splat[i].w * exp(-dot(d, d) / max(u_Splat[i].z * u_Splat[i].z, 0.25));
                }
                // The edge of the grid absorbs instead of reflecting rings back.
                vec2 edge = min(vec2(c), vec2(N - 1) - vec2(c));
                next *= smoothstep(0.0, 6.0, min(edge.x, edge.y));
                fragColor = vec4(clamp(next, -1.0, 1.0), here.r, (e - w) * 0.5, (n - s) * 0.5);
            }
            """;
}
