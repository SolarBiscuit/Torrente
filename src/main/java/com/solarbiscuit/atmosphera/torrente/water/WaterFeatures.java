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
 *   <li>six AtmosFrame slots with the active style, written once per frame on the render thread.</li>
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
        FrameUniforms.vec4("torrente_Lod", "x: shade Voxy LOD water (lod_water option), y: debug channel",
                out -> {
                    out[0] = TorrenteConfig.lodWater.get() ? 1f : 0f;
                    out[1] = TorrenteConfig.debugChannel.get().ordinal();
                });
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
