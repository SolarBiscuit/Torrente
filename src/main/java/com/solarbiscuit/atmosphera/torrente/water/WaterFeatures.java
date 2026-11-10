package com.solarbiscuit.atmosphera.torrente.water;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.mesh.BitField;
import com.solarbiscuit.atmosphera.lib.mesh.BlockMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.FluidMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.MeshHooks;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import com.solarbiscuit.atmosphera.torrente.Torrente;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import com.solarbiscuit.atmosphera.torrente.style.Styles;
import com.solarbiscuit.atmosphera.torrente.style.WaterStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Torrente's terrain features and their data:
 * <ul>
 *   <li>material bit {@code torrente:surface} on every water fluid quad (water shading, waves);</li>
 *   <li>material bit {@code torrente:submerged} on blocks under or beside water (caustics);</li>
 *   <li>nine AtmosFrame slots ({@link FrameUniforms}) with the active style, wind and weather, written once per
 *   frame on the render thread.</li>
 * </ul>
 * The underwater fog feature has no terrain part; see {@link UnderwaterFog}.
 */
public final class WaterFeatures {
    /** Swell amplitude per unit of wave energy, and the hard cap (blocks). */
    private static final float SWELL_HEIGHT = 0.035f;
    private static final float SWELL_CAP = 0.1f;

    private static BitField surface;
    private static BitField submerged;
    private static AtmosFeature waves;
    private static WaterStyle frameStyle = WaterStyle.FALLBACK;
    private static final WindVector WIND = new WindVector();

    private WaterFeatures() {
    }

    public static void register() {
        surface = VertexBits.material(Torrente.MOD_ID, "surface", 1);
        submerged = VertexBits.material(Torrente.MOD_ID, "submerged", 1);
        registerSlots();

        AtmosFeature shading = AtmosFeature.builder(Torrente.MOD_ID, "water_shading")
                .provides(Capability.WATER_SHADING)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS, AtmosFeature.Uses.SCENE_DEPTH)
                .switchedBy(TorrenteConfig.shading)
                .register();
        // Compiled into the shading snippet (its macro gates the refraction branch); it adds the colour copy.
        AtmosFeature refraction = AtmosFeature.builder(Torrente.MOD_ID, "refraction")
                .provides(Capability.WATER_SHADING)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.SCENE_DEPTH, AtmosFeature.Uses.SCENE_COLOR)
                .switchedBy(TorrenteConfig.shading)
                .enabledWhen(() -> TorrenteConfig.shading.enabled().get() && TorrenteConfig.refraction.get())
                .register();
        waves = AtmosFeature.builder(Torrente.MOD_ID, "waves")
                .provides(Capability.WATER_WAVES)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS)
                .switchedBy(TorrenteConfig.waves)
                .register();
        AtmosFeature caustics = AtmosFeature.builder(Torrente.MOD_ID, "caustics")
                .provides(Capability.CAUSTICS)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS, AtmosFeature.Uses.FACE_NORMAL)
                .switchedBy(TorrenteConfig.caustics)
                .register();
        causticsFeature = caustics;
        // 0.2.1: exact water depth from the lib's column field (lib 0.5.0); kept running while caustics are active.
        com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader.requireInTerrain(caustics);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.TickEvent.RenderTickEvent e) -> {
                    if (e.phase == net.minecraftforge.event.TickEvent.Phase.START) {
                        keepColumnField();
                    }
                });
        UnderwaterFog.feature = AtmosFeature.builder(Torrente.MOD_ID, "underwater_fog")
                .provides(Capability.UNDERWATER_FOG)
                .uses(AtmosFeature.Uses.OTHER)
                .switchedBy(TorrenteConfig.underwaterFog)
                .register();

        FluidMeshHook waterSurface = surfaceHook();
        MeshHooks.registerFluid(shading, waterSurface);
        MeshHooks.registerFluid(waves, waterSurface);
        MeshHooks.registerBlock(caustics, submergedHook());

        for (AtmosFeature feature : new AtmosFeature[]{shading, waves, caustics}) {
            ShaderSnippets.register(feature, ShaderHook.VERT_DECL, ShaderSnippets.PASS_ALL, 10, WaterGlsl.VERT_DECL);
            ShaderSnippets.register(feature, ShaderHook.VERT_POST_LIGHT, ShaderSnippets.PASS_ALL, 10, WaterGlsl.VERT_POST_LIGHT);
            ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, 10, WaterGlsl.FRAG_DECL);
        }
        ShaderSnippets.register(shading, ShaderHook.FRAG_PRE_FOG, ShaderSnippets.PASS_TRANSLUCENT, 10,
                WaterGlsl.surface(surface.glslMacro(), refraction.macro()));
        ShaderSnippets.register(waves, ShaderHook.VERT_POSITION, ShaderSnippets.PASS_TRANSLUCENT, 10,
                WaterGlsl.waves(surface.glslMacro()));
        ShaderSnippets.register(caustics, ShaderHook.FRAG_PRE_FOG, ShaderSnippets.PASS_SOLID | ShaderSnippets.PASS_CUTOUT, 10,
                WaterGlsl.caustics(submerged.glslMacro()));

        // Voxy LOD terrain (AtmospheraLib compat.voxy): the same surface shading and swell on distant water
        for (AtmosFeature feature : new AtmosFeature[]{shading, waves}) {
            ShaderSnippets.register(feature, ShaderHook.LOD_DECL, ShaderSnippets.PASS_ALL, 10, WaterGlsl.LOD_DECL);
        }
        ShaderSnippets.register(shading, ShaderHook.LOD_FRAGMENT, ShaderSnippets.PASS_TRANSLUCENT, 10,
                WaterGlsl.lodSurface(surface.glslMacro(), refraction.macro()));
        ShaderSnippets.register(waves, ShaderHook.LOD_VERTEX, ShaderSnippets.PASS_TRANSLUCENT, 10,
                WaterGlsl.lodWaves(surface.glslMacro()));
    }

    /** Water fluid quads (sources, flows, waterlogged blocks): one tag test per fluid block. Same bit on Voxy LODs. */
    private static FluidMeshHook surfaceHook() {
        int bit = surface.set(0, 1);
        return new FluidMeshHook() {
            @Override
            public int materialBits(FluidState fluid, BlockPos pos, BlockAndTintGetter level) {
                return fluid.is(FluidTags.WATER) ? bit : 0;
            }

            @Override
            public int lodMaterialBits(FluidState fluid) {
                return fluid.is(FluidTags.WATER) ? bit : 0;
            }
        };
    }

    /**
     * Blocks that can show caustics: the block holds water, or water is right above, or a horizontal neighbour is
     * water while the block above is not air (dry shore tops stay out, trench walls are in). At most six slice reads,
     * through the lib's {@link MeshHooks#blockAt} (Xenon's int-coordinate WorldSlice read, no BlockPos, no
     * ThreadLocal: this runs for every meshed block; the fast path moved into the lib in 0.1.10).
     */
    private static BlockMeshHook submergedHook() {
        int bit = submerged.set(0, 1);
        return new BlockMeshHook() {
            @Override
            public int materialBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
                if (isWater(state.getFluidState())) {
                    return bit;
                }
                int x = pos.getX();
                int y = pos.getY();
                int z = pos.getZ();
                BlockState above = MeshHooks.blockAt(level, x, y + 1, z);
                if (isWater(above.getFluidState())) {
                    return bit;
                }
                return !above.isAir() && (isWater(MeshHooks.blockAt(level, x + 1, y, z).getFluidState())
                        || isWater(MeshHooks.blockAt(level, x - 1, y, z).getFluidState())
                        || isWater(MeshHooks.blockAt(level, x, y, z + 1).getFluidState())
                        || isWater(MeshHooks.blockAt(level, x, y, z - 1).getFluidState())) ? bit : 0;
            }
        };
    }

    private static boolean isWater(FluidState fluid) {
        return !fluid.isEmpty() && fluid.is(FluidTags.WATER);
    }

    private static void registerSlots() {
        // Written in this order every frame; the first one picks the frame's style.
        FrameUniforms.vec4("torrente_Color", "rgb: water colour, a: shallow opacity", out -> {
            frameStyle = Styles.active(AtmosClient.frame());
            WaterStyle s = frameStyle;
            out[0] = s.waterColor()[0];
            out[1] = s.waterColor()[1];
            out[2] = s.waterColor()[2];
            out[3] = s.opacity();
        });
        FrameUniforms.vec4("torrente_Absorb", "rgb: absorption per block, a: biome tint share", out -> {
            WaterStyle s = frameStyle;
            out[0] = s.absorption()[0];
            out[1] = s.absorption()[1];
            out[2] = s.absorption()[2];
            out[3] = s.biomeTint();
        });
        FrameUniforms.vec4("torrente_Surface", "x: reflection, y: specular, z: ripples, w: texture", out -> {
            WaterStyle s = frameStyle;
            out[0] = s.reflection();
            out[1] = s.specular();
            out[2] = s.waveNormals();
            out[3] = s.texture();
        });
        FrameUniforms.vec4("torrente_Waves", "x: swell height, y: foam, z: refraction, w: ripple octaves",
                WaterFeatures::writeWaves);
        FrameUniforms.vec4("torrente_Caustics", "x: strength, y: cells per block, z: fade depth, w: layers", out -> {
            WaterStyle s = frameStyle;
            out[0] = s.caustics() * TorrenteConfig.causticStrength.get().floatValue() * TorrenteConfig.CAUSTIC_SCALE;
            out[1] = s.causticScale();
            out[2] = s.causticFade();
            out[3] = TorrenteConfig.quality.get() == TorrenteConfig.Quality.HIGH ? 2f : 1f;
        });
        FrameUniforms.vec4("torrente_Mode", "x: depth mode (0 off, 1 Torrente, 2 Bathymetry)",
                out -> out[0] = Styles.depthMode());
        FrameUniforms.vec4("torrente_Swell", "xyz: amplitude weights of the three swell directions (downwind ones higher, sum of 0.5/0.3/0.2 kept)",
                WaterFeatures::writeSwell);
        FrameUniforms.vec4("torrente_Light", "x: crest glow, y: rain ring strength (0 when dry), z: caustic dispersion (HIGH)",
                WaterFeatures::writeLight);
        FrameUniforms.vec4("torrente_Lod", "x: shade Voxy LOD water (lod_water option), y: debug channel",
                out -> {
                    out[0] = TorrenteConfig.lodWater.get() ? 1f : 0f;
                    out[1] = TorrenteConfig.debugChannel.get().ordinal();
                });
    }

    private static AtmosFeature causticsFeature;
    private static final Object COLUMN_OWNER = new Object();
    /** The smallest window (256 blocks): caustics fade out within the vanilla render distance anyway. */
    private static final int COLUMN_SIZE = 256;

    /** Render tick: keeps the column field (water tops) running while caustics are drawn. */
    private static void keepColumnField() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && causticsFeature != null && causticsFeature.isActive()) {
            com.solarbiscuit.atmosphera.lib.world.ColumnField.use(COLUMN_OWNER, level, COLUMN_SIZE);
        }
    }

    private static final float[][] SWELL_DIRS = {{0.8f, 0.6f}, {-0.36f, 0.933f}, {0.95f, -0.312f}};
    private static final float[] SWELL_AMP = {0.5f, 0.3f, 0.2f};
    private static final float[] AXES = new float[4];

    /**
     * Wind-aligned swell (0.2.0): each fixed direction's share of running downwind, from the lib's axis weights
     * (FrameUniforms.axisWeights, the same per-frame weights Gale uses), lifts its amplitude and lowers the others.
     * The weighted sum stays 1, so wind direction only moves height between directions. Phases are untouched.
     */
    private static void writeSwell(float[] out) {
        out[0] = out[1] = out[2] = 1f;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !TorrenteConfig.windAligned.get()) {
            return;
        }
        Wind.global(level, Minecraft.getInstance().getFrameTime(), WIND);
        FrameUniforms.axisWeights(WIND.dirX, WIND.dirZ, AXES);
        float align = Math.min(1f, WIND.strength * 1.5f);
        float sum = 0f;
        for (int i = 0; i < 3; i++) {
            float dx = SWELL_DIRS[i][0];
            float dz = SWELL_DIRS[i][1];
            float px = Math.max(dx, 0f), nx = Math.max(-dx, 0f), pz = Math.max(dz, 0f), nz = Math.max(-dz, 0f);
            float down = AXES[0] * px * px + AXES[1] * nx * nx + AXES[2] * pz * pz + AXES[3] * nz * nz;
            out[i] = (1f - align) + align * (0.3f + 2.1f * down);
            sum += SWELL_AMP[i] * out[i];
        }
        float k = 1f / Math.max(sum, 1e-3f);
        out[0] *= k;
        out[1] *= k;
        out[2] *= k;
    }

    private static void writeLight(float[] out) {
        out[0] = TorrenteConfig.crestGlow.get().floatValue();
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && TorrenteConfig.rainRipples.get()) {
            float rain = level.getRainLevel(Minecraft.getInstance().getFrameTime());
            if (rain > 0.01f && rainsOnNearbyWater(level,
                    Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition())) {
                out[1] = rain;
            }
        }
        out[2] = TorrenteConfig.causticDispersion.get() && TorrenteConfig.quality.get() == TorrenteConfig.Quality.HIGH ? 1f : 0f;
    }

    /** Columns tested for rain: the camera's and four 16 blocks away (rings are drawn within 40 blocks). */
    private static final int[] RAIN_PROBES = {0, 0, 16, 0, -16, 0, 0, 16, 0, -16};
    private static final BlockPos.MutableBlockPos RAIN_POS = new BlockPos.MutableBlockPos();

    /**
     * Rain, not snow, on the water near the camera (0.2.4; it was tested at the camera itself, so a camera on a snowy
     * peak or in a snowy biome beside a rainy one turned the rings off everywhere). The column field gives each probe's
     * water surface: rain at any of them turns the rings on, snow at all of them keeps them off. Without field data
     * (field off, or no water under the probes) each probe is tested at the camera's height, or sea level if lower.
     * Render thread, no allocation.
     */
    private static boolean rainsOnNearbyWater(ClientLevel level, BlockPos camera) {
        boolean water = false;
        if (com.solarbiscuit.atmosphera.lib.world.ColumnField.ready()) {
            for (int i = 0; i < RAIN_PROBES.length; i += 2) {
                int x = camera.getX() + RAIN_PROBES[i];
                int z = camera.getZ() + RAIN_PROBES[i + 1];
                int raw = com.solarbiscuit.atmosphera.lib.world.ColumnField.waterRaw(x, z);
                if (raw != 0) {
                    water = true;
                    int y = (int) Math.floor(com.solarbiscuit.atmosphera.lib.world.ColumnField.top(raw) - 0.05f);
                    if (rainsAt(level, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        if (water) {
            return false;
        }
        int y = Math.min(camera.getY(), level.getSeaLevel() - 1);
        for (int i = 0; i < RAIN_PROBES.length; i += 2) {
            if (rainsAt(level, camera.getX() + RAIN_PROBES[i], y, camera.getZ() + RAIN_PROBES[i + 1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean rainsAt(ClientLevel level, int x, int y, int z) {
        RAIN_POS.set(x, y, z);
        return level.getBiome(RAIN_POS).value().getPrecipitationAt(RAIN_POS)
                == net.minecraft.world.level.biome.Biome.Precipitation.RAIN;
    }

    private static void writeWaves(float[] out) {
        WaterStyle s = frameStyle;
        out[0] = 0f;
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && waves.isActive()) {
            float partial = Minecraft.getInstance().getFrameTime();
            Wind.global(level, partial, WIND);
            float energy = 0.6f + 0.8f * WIND.strength + 0.5f * level.getRainLevel(partial)
                    + 0.4f * level.getThunderLevel(partial);
            out[0] = Math.min(SWELL_CAP, SWELL_HEIGHT * energy * TorrenteConfig.waveHeight.get().floatValue());
        }
        out[1] = TorrenteConfig.foam.get() ? s.foam() : 0f;
        out[2] = s.refraction();
        out[3] = TorrenteConfig.quality.get() == TorrenteConfig.Quality.HIGH ? 6f : 3f;
    }
}
