package com.solarbiscuit.atmosphera.torrente.water;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lib.gpu.SceneTextures;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.world.ColumnField;
import com.solarbiscuit.atmosphera.lib.gpu.PostProgram;
import com.solarbiscuit.atmosphera.lib.gpu.Textures;
import com.solarbiscuit.atmosphera.torrente.Torrente;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Planar reflections, 0.3.0 "Mirror" (screen-space planar reflections, SSPR). Most water near the camera lies at one
 * height, so the opaque scene is reflected across that one horizontal plane in two full-screen fragment passes at the
 * lib's {@code BEFORE_TRANSLUCENT} stage, after the scene copies and before the water is drawn:
 * <ol>
 *   <li><b>Scatter:</b> every pixel above the plane mirrors its point across it, projects it, and writes its own screen
 *   position into the texel it lands on with {@code imageAtomicMin} (an R32UI image). The smallest key, the source
 *   lowest on the screen, wins: of several points that land on one texel, the one nearest the water line hides the
 *   others in a true mirror.</li>
 *   <li><b>Resolve:</b> each texel fetches the scene colour at its source (holes of one texel take a neighbour's), and
 *   fades out sources near the screen's edges, so things leaving the screen fade instead of popping.</li>
 * </ol>
 * The water shader reads the result at its own screen position, bent by the ripples, and uses it in place of the sky
 * reflection where it has data; elsewhere (holes, other water heights, LODs) the sky view stays. Fragment passes only
 * (the suite's design rule against compute stands), but image load/store needs GL 4.3 here ({@code requiresGl}).
 * Half the screen's width and height on MEDIUM quality, full size on HIGH, off on LOW.
 */
public final class Mirror {
    private static AtmosFeature feature;
    /** 0.5.1: the shared post-program holders (lib 0.11.0). {@link #failed}: the targets. */
    private static final PostProgram SCATTER_PROGRAM = new PostProgram(Torrente.LOGGER, "Torrente mirror scatter",
            "Torrente reflections", () -> GlslPrograms.FULLSCREEN_VERTEX_460, () -> Mirror.SCATTER,
            "u_Depth", "u_InvViewProj", "u_ViewProj", "u_Plane");
    private static final PostProgram RESOLVE_PROGRAM = new PostProgram(Torrente.LOGGER, "Torrente mirror resolve",
            "Torrente reflections", () -> GlslPrograms.FULLSCREEN_VERTEX_460, () -> Mirror.RESOLVE, "u_Scene", "u_Size");
    private static boolean failed;
    private static int keys;
    private static int keyFbo;
    private static int colour;
    private static int colourFbo;
    private static int width;
    private static int height;
    /** True when this frame's water may read the result (the pass ran last frame or this one). */
    private static long drawnFrame = -1;

    // The plane: world y of the water surface, chosen from the column field and held steady.
    private static float plane = Float.NaN;
    private static float candidate = Float.NaN;
    private static int candidateFrames;
    private static int planeCountdown;
    private static final Object COLUMN_OWNER = new Object();
    private static final float[] TOPS = new float[289];
    private static final int[] COUNTS = new int[289];

    private Mirror() {
    }

    static void register(AtmosFeature shading) {
        feature = AtmosFeature.builder(Torrente.MOD_ID, "mirror")
                .provides(Capability.WATER_SHADING)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.SCENE_DEPTH, AtmosFeature.Uses.SCENE_COLOR,
                        AtmosFeature.Uses.WORLD_PASS)
                .requiresGl(4, 3)
                .enabledWhen(() -> TorrenteConfig.shading.enabled().get() && WaterFeatures.notVanilla() && TorrenteConfig.mirror.get()
                        && TorrenteConfig.quality.get() != TorrenteConfig.Quality.LOW)
                .register();
        PostPasses.register(feature, PostPasses.Stage.BEFORE_TRANSLUCENT, 10, "torrente_mirror", Mirror::render);
        TerrainSamplers.request(feature, "torrente_MirrorTex", GL11C.GL_TEXTURE_2D, TerrainSamplers.Stage.FRAGMENT, 40,
                () -> colour);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_TRANSLUCENT, 11,
                "uniform sampler2D torrente_MirrorTex;\n");
        com.solarbiscuit.atmosphera.lib.client.AtmosClient.onLeaveLevel(Mirror::leaveLevel);
        FrameUniforms.vec4("torrente_Mirror", "x: plane height relative to the camera, y: 1 while reflections are drawn, "
                + "z: strength", Mirror::writeSlot);
    }

    /** The macro the water snippet tests: defined while the mirror feature is compiled in. */
    static String macro() {
        return feature.macro();
    }

    /** Frame slot, written before the BEFORE_TRANSLUCENT stage runs: also where the plane is chosen. */
    private static void writeSlot(float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        out[2] = 0f;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || feature == null || !feature.isActive()) {
            plane = Float.NaN;
            return;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        ColumnField.use(COLUMN_OWNER, level, 256);
        if (--planeCountdown <= 0) {
            planeCountdown = 4;
            choosePlane(camera);
        }
        if (Float.isNaN(plane) || mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER
                || camera.y < plane + 0.05) {
            return;
        }
        out[0] = (float) (plane - camera.y);
        out[1] = com.solarbiscuit.atmosphera.lib.client.AtmosClient.frame() - drawnFrame <= 1 ? 1f : 0f;
        out[2] = 1f;
    }

    /**
     * The most common water top among the columns within 32 blocks (every 4th column), held until another height has
     * won for 10 choices in a row (about 40 frames), so the plane never flickers between two pools.
     */
    private static void choosePlane(Vec3 camera) {
        if (!ColumnField.ready()) {
            plane = Float.NaN;
            return;
        }
        int cx = (int) Math.floor(camera.x);
        int cz = (int) Math.floor(camera.z);
        int n = 0;
        for (int dz = -32; dz <= 32; dz += 4) {
            for (int dx = -32; dx <= 32; dx += 4) {
                int raw = ColumnField.waterRaw(cx + dx, cz + dz);
                if (raw == 0) {
                    continue;
                }
                float top = ColumnField.top(raw);
                int i = 0;
                while (i < n && Math.abs(TOPS[i] - top) > 0.01f) {
                    i++;
                }
                if (i == n) {
                    TOPS[n] = top;
                    COUNTS[n] = 0;
                    n++;
                }
                COUNTS[i]++;
            }
        }
        float best = Float.NaN;
        int bestCount = 3;
        for (int i = 0; i < n; i++) {
            if (COUNTS[i] > bestCount) {
                bestCount = COUNTS[i];
                best = TOPS[i];
            }
        }
        if (Float.isNaN(best)) {
            plane = Float.NaN;
            candidateFrames = 0;
            return;
        }
        if (Float.isNaN(plane)) {
            plane = best;
        } else if (Math.abs(best - plane) > 0.01f) {
            if (Float.isNaN(candidate) || Math.abs(best - candidate) > 0.01f) {
                candidate = best;
                candidateFrames = 0;
            }
            if (++candidateFrames >= 10) {
                plane = best;
                candidateFrames = 0;
            }
        } else {
            candidateFrames = 0;
        }
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (failed || SCATTER_PROGRAM.failed() || RESOLVE_PROGRAM.failed() || Float.isNaN(plane) || mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER) {
            return;
        }
        Vec3 camera = ctx.camera();
        float planeRel = (float) (plane - camera.y);
        if (planeRel > -0.05f) {
            return;
        }
        int depth = SceneTextures.depthTexture();
        int scene = SceneTextures.colorTexture();
        RenderTargets.Scene target = RenderTargets.scene();
        if (depth == 0 || scene == 0 || !target.usable()) {
            return;
        }
        if (!SCATTER_PROGRAM.ready() || !RESOLVE_PROGRAM.ready()) {
            return;
        }
        int divisor = TorrenteConfig.quality.get() == TorrenteConfig.Quality.HIGH ? 1 : 2;
        if (!ensureTargets(Math.max(1, target.width() / divisor), Math.max(1, target.height() / divisor))) {
            return;
        }
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glViewport(0, 0, width, height);

            // 1. Clear the keys to "none", then scatter.
            GpuTimers.begin("torrente_mirror_scatter");
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, keyFbo);
            GL30C.glClearBufferuiv(GL11C.GL_COLOR, 0, stack.ints(-1, -1, -1, -1));
            GL11C.glColorMask(false, false, false, false);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depth);
            GL42C.glBindImageTexture(0, keys, 0, false, 0, GL42C.GL_READ_WRITE, GL30C.GL_R32UI);
            org.lwjgl.opengl.GL20C.glUseProgram(SCATTER_PROGRAM.id());
            org.lwjgl.opengl.GL20C.glUniform1i(SCATTER_PROGRAM.u(0), 12);
            org.lwjgl.opengl.GL20C.glUniformMatrix4fv(SCATTER_PROGRAM.u(1), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
            org.lwjgl.opengl.GL20C.glUniformMatrix4fv(SCATTER_PROGRAM.u(2), false, ctx.viewProjection().get(stack.mallocFloat(16)));
            org.lwjgl.opengl.GL20C.glUniform4f(SCATTER_PROGRAM.u(3), planeRel, width, height, 0f);
            GlslPrograms.drawFullscreen();
            GL11C.glColorMask(true, true, true, true);
            GpuTimers.end("torrente_mirror_scatter");

            // 2. Resolve into the colour target the water reads.
            GpuTimers.begin("torrente_mirror_resolve");
            GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, colourFbo);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene);
            GL42C.glBindImageTexture(0, keys, 0, false, 0, GL42C.GL_READ_ONLY, GL30C.GL_R32UI);
            org.lwjgl.opengl.GL20C.glUseProgram(RESOLVE_PROGRAM.id());
            org.lwjgl.opengl.GL20C.glUniform1i(RESOLVE_PROGRAM.u(0), 13);
            org.lwjgl.opengl.GL20C.glUniform4f(RESOLVE_PROGRAM.u(1), width, height, 0f, 0f);
            GlslPrograms.drawFullscreen();
            GpuTimers.end("torrente_mirror_resolve");
            drawnFrame = com.solarbiscuit.atmosphera.lib.client.AtmosClient.frame();
        } finally {
            GL42C.glBindImageTexture(0, 0, 0, false, 0, GL42C.GL_READ_ONLY, GL30C.GL_R32UI);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            saved.restore();
            // The scene copies live on units 12 and 13 for the terrain programs: put them back.
            SceneTextures.bind();
        }
    }

    private static boolean ensureTargets(int w, int h) {
        if (keys != 0 && w == width && h == height) {
            return true;
        }
        releaseTargets();
        width = w;
        height = h;
        keys = Textures.create2D("Torrente mirror keys", w, h, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT,
                GL11C.GL_NEAREST, GL11C.GL_REPEAT);
        colour = Textures.create2D("Torrente mirror", w, h, GL11C.GL_RGBA8, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE,
                GL11C.GL_LINEAR, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
        keyFbo = Textures.framebuffer("Torrente mirror keys", keys);
        colourFbo = Textures.framebuffer("Torrente mirror", colour);
        if (keyFbo == 0 || colourFbo == 0) {
            Torrente.LOGGER.error("[Atmosphera] Torrente reflections are off: a framebuffer is incomplete");
            failed = true;
            releaseTargets();
            return false;
        }
        return true;
    }




    private static void releaseTargets() {
        for (int fbo : new int[] {keyFbo, colourFbo}) {
            if (fbo != 0) {
                GpuResources.deleteFramebuffer(fbo);
            }
        }
        for (int tex : new int[] {keys, colour}) {
            if (tex != 0) {
                GpuResources.deleteTexture(tex);
            }
        }
        keyFbo = colourFbo = keys = colour = 0;
        width = height = 0;
    }

    /**
     * 0.5.2: render tick. Frees the two screen-size targets (8 bytes a pixel: about 66 MB at 4K on HIGH) while the
     * reflections are off (option, LOW quality, VANILLA style, shading off) or no world is shown; they were kept until
     * a shader reload.
     */
    static void update() {
        if (keys != 0 && (feature == null || !feature.isActive() || Minecraft.getInstance().level == null)) {
            releaseTargets();
        }
    }

    /**
     * 0.5.2: leaving a level (main thread). The plane is chosen anew in the next one; before, the old world's height
     * was used for up to four frames. CPU state only.
     */
    private static void leaveLevel() {
        plane = Float.NaN;
        candidate = Float.NaN;
        candidateFrames = 0;
        planeCountdown = 0;
        drawnFrame = -1;
    }

    private static void releasePrograms() {
        SCATTER_PROGRAM.release();
        RESOLVE_PROGRAM.release();
    }

    /** Frees programs and targets (the lib's shader reload); the next frame rebuilds them. */
    public static void release() {
        releasePrograms();
        releaseTargets();
        failed = false;
        drawnFrame = -1;
    }

    static final String SCATTER = """
            #version 460 core
            layout(r32ui, binding = 0) uniform coherent uimage2D u_Keys;
            uniform sampler2D u_Depth;
            uniform mat4 u_InvViewProj;
            uniform mat4 u_ViewProj;
            uniform vec4 u_Plane;   // x plane height relative to the camera, yz target size
            in vec2 v_Uv;

            void main() {
                ivec2 size = ivec2(u_Plane.yz);
                ivec2 src = ivec2(gl_FragCoord.xy);
                vec2 uv = (vec2(src) + 0.5) / vec2(size);
                float depth = textureLod(u_Depth, uv, 0.0).r;
                if (depth >= 1.0) {
                    return;
                }
                vec4 rel = u_InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec3 p = rel.xyz / rel.w;
                // Only what stands above the water (and not the shore pixel at the water line itself).
                if (p.y < u_Plane.x + 0.05) {
                    return;
                }
                vec4 clip = u_ViewProj * vec4(p.x, 2.0 * u_Plane.x - p.y, p.z, 1.0);
                if (clip.w <= 1e-4) {
                    return;
                }
                vec2 t = clip.xy / clip.w * 0.5 + 0.5;
                if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(1.0)))) {
                    return;
                }
                // The source lowest on the screen wins (key: its row, then its column).
                imageAtomicMin(u_Keys, ivec2(t * vec2(size)), (uint(src.y) << 16) | uint(src.x));
            }
            """;

    static final String RESOLVE = """
            #version 460 core
            layout(r32ui, binding = 0) uniform readonly uimage2D u_Keys;
            uniform sampler2D u_Scene;
            uniform vec4 u_Size;    // xy target size
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                ivec2 size = ivec2(u_Size.xy);
                ivec2 p = ivec2(gl_FragCoord.xy);
                uint key = imageLoad(u_Keys, p).r;
                if (key == 0xFFFFFFFFu) {
                    // A hole of one texel: take a direct neighbour's source.
                    uint n = imageLoad(u_Keys, clamp(p + ivec2(0, 1), ivec2(0), size - 1)).r;
                    n = min(n, imageLoad(u_Keys, clamp(p - ivec2(0, 1), ivec2(0), size - 1)).r);
                    n = min(n, imageLoad(u_Keys, clamp(p + ivec2(1, 0), ivec2(0), size - 1)).r);
                    n = min(n, imageLoad(u_Keys, clamp(p - ivec2(1, 0), ivec2(0), size - 1)).r);
                    key = n;
                }
                if (key == 0xFFFFFFFFu) {
                    fragColor = vec4(0.0);
                    return;
                }
                vec2 src = (vec2(float(key & 0xFFFFu), float(key >> 16)) + 0.5) / u_Size.xy;
                // Sources near the left, right and top edges of the screen fade out instead of popping.
                float fade = smoothstep(0.0, 0.08, src.x) * smoothstep(0.0, 0.08, 1.0 - src.x)
                        * smoothstep(0.0, 0.08, 1.0 - src.y);
                fragColor = vec4(textureLod(u_Scene, src, 0.0).rgb, fade);
            }
            """;
}
