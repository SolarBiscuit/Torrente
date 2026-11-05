package com.solarbiscuit.atmosphera.torrente;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

/** {@code config/SolConfig/atmosphera/torrente-client.toml} and the Torrente group in Video Settings > Atmosphera. */
public final class TorrenteConfig {
    public enum Style { UNBOUND, GLIMMER, REIMAGINED, VANILLA_PLUS, CUSTOM }

    public enum Quality { LOW, HIGH }

    /** AUTO: Bathymetry's depth tint when it is installed, otherwise Torrente's own. */
    public enum DepthMode { AUTO, TORRENTE, BATHYMETRY, OFF }

    public enum DebugChannel { OFF, TINT, LIGHT, LUMA, THICKNESS, KEEP }

    public static AtmosConfig.FeatureSwitch shading;
    public static AtmosOption<Style> style;
    public static AtmosOption<Quality> quality;
    public static AtmosOption<DepthMode> depthMode;
    public static AtmosOption<Boolean> refraction;
    public static AtmosOption<Boolean> foam;
    public static AtmosOption<Boolean> lodWater;
    public static AtmosOption<DebugChannel> debugChannel;
    public static AtmosConfig.FeatureSwitch waves;
    public static AtmosOption<Double> waveHeight;
    public static AtmosConfig.FeatureSwitch caustics;
    public static AtmosOption<Double> causticStrength;
    /** causticStrength slider value to shader strength: 50% on the slider = 0.2, the old 20%. */
    public static final float CAUSTIC_SCALE = 0.4f;
    public static AtmosConfig.FeatureSwitch underwaterFog;
    public static AtmosOption<Double> visibility;

    // Custom style (used while style = CUSTOM); same meaning as the keys in assets/torrente/styles/*.json.
    public static AtmosOption<Integer> colorR;
    public static AtmosOption<Integer> colorG;
    public static AtmosOption<Integer> colorB;
    public static AtmosOption<Double> biomeTint;
    public static AtmosOption<Double> opacity;
    public static AtmosOption<Double> absorbR;
    public static AtmosOption<Double> absorbG;
    public static AtmosOption<Double> absorbB;
    public static AtmosOption<Double> reflection;
    public static AtmosOption<Double> specular;
    public static AtmosOption<Double> waveNormals;
    public static AtmosOption<Double> texture;
    public static AtmosOption<Double> refractionStrength;
    public static AtmosOption<Double> foamStrength;
    public static AtmosOption<Double> causticsStyle;
    public static AtmosOption<Double> causticScale;
    public static AtmosOption<Integer> causticFade;
    public static AtmosOption<Integer> fogR;
    public static AtmosOption<Integer> fogG;
    public static AtmosOption<Integer> fogB;
    public static AtmosOption<Double> fogMix;
    public static AtmosOption<Double> fogDistance;

    private TorrenteConfig() {
    }

    static void register() {
        AtmosConfig config = AtmosConfig.create(Torrente.MOD_ID, "Torrente (water)");
        config.section("water", "Water surface: colour, depth, reflection and glints.");
        shading = config.featureSwitch("shading", true, "Water shading",
                "Styled water colour that deepens with depth, sky reflection, sun glints, ripples and shore foam.");
        style = config.choice("style", Style.UNBOUND, ChangeTier.LIVE, "Water style",
                "UNBOUND: clear blue (RatPack). GLIMMER: physically based absorption. REIMAGINED: vanilla texture "
                        + "with light effects. VANILLA_PLUS: depth opacity and fog only. CUSTOM: the [custom] values.");
        quality = config.choice("quality", Quality.HIGH, ChangeTier.LIVE, "Water quality",
                "LOW: fewer ripple octaves and one caustic layer. HIGH: full detail.");
        depthMode = config.choice("depth_mode", DepthMode.AUTO, ChangeTier.LIVE, "Depth colour",
                "Who darkens deep water. AUTO: Bathymetry when it is installed, otherwise Torrente. "
                        + "BATHYMETRY: keep Bathymetry's tint, Torrente only sets opacity. OFF: no depth effect.");
        refraction = config.toggle("refraction", true, ChangeTier.LIVE, "Refraction",
                "Bends what lies under the water with the ripples (one extra screen copy).");
        foam = config.toggle("foam", true, ChangeTier.LIVE, "Shore foam",
                "A soft white edge where water meets blocks.");
        lodWater = config.toggle("lod_water", true, ChangeTier.LIVE, "Water on Voxy LODs",
                "Distant Voxy/NeoVoxy LOD water gets the same shading as the water nearby (colour, depth, reflection, "
                        + "glints, waves), so there is no visible ring where the vanilla render distance ends. "
                        + "Needs AtmospheraLib's Voxy LOD shading; no shader pack.");
        debugChannel = config.choice("debug_channel", DebugChannel.OFF, ChangeTier.LIVE, "Debug: show input",
                "Diagnostics: paints one input of the water shading instead of the water, on near and Voxy LOD water "
                        + "alike, so the two can be compared at the LOD transition (NeoVoxy /voxy debug handoff compare). "
                        + "TINT: biome/Bathymetry tint. LIGHT: lightmap colour. LUMA: texture brightness. THICKNESS: "
                        + "water thickness along the view ray (white = 32 blocks). KEEP: share of what lies behind.");

        config.section("waves", "Waves: the water surface rises and falls with the wind.");
        waves = config.featureSwitch("enabled", true, "Water waves",
                "Water surfaces move up and down, more in wind and rain.");
        waveHeight = config.percent("height", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Wave height",
                "How high the waves are. Always capped at 0.1 blocks.");

        config.section("caustics", "Caustics: moving light patterns on blocks under water.");
        caustics = config.featureSwitch("enabled", true, "Caustics",
                "Light patterns on the floor and walls of shallow sunlit water.");
        // New key (was strength, 0-200%): 50% here is the old 20%, the look players settled on (2026-11-02).
        causticStrength = config.percent("intensity", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Caustic strength",
                "How bright the caustic pattern is. 50% is the default; 100% is twice as bright.");

        config.section("underwater", "Underwater view.");
        underwaterFog = config.featureSwitch("fog", true, "Underwater fog",
                "Style colour and visibility of the fog while your head is under water.");
        visibility = config.percent("visibility", 1.0, 0.25, 3.0, ChangeTier.LIVE, "Underwater visibility",
                "How far you can see under water, on top of the style's own value.");

        config.pageSection("custom", "Custom style", "Water style used while water.style = CUSTOM.");
        colorR = config.slider("color_r", 40, 0, 255, 1, ChangeTier.LIVE, "Water red", "Deep water colour, red.");
        colorG = config.slider("color_g", 110, 0, 255, 1, ChangeTier.LIVE, "Water green", "Deep water colour, green.");
        colorB = config.slider("color_b", 190, 0, 255, 1, ChangeTier.LIVE, "Water blue", "Deep water colour, blue.");
        biomeTint = config.percent("biome_tint", 0.6, 0.0, 1.0, ChangeTier.LIVE, "Biome colour",
                "How much the biome's water colour (swamps, warm oceans) steers the water colour.");
        opacity = config.percent("opacity", 0.35, 0.0, 1.0, ChangeTier.LIVE, "Opacity",
                "Opacity of very shallow water.");
        config.group(); // absorption
        absorbR = config.percent("absorption_r", 0.25, 0.0, 1.0, ChangeTier.LIVE, "Absorb red",
                "Absorption of red light per block of water (Beer-Lambert coefficient).");
        absorbG = config.percent("absorption_g", 0.09, 0.0, 1.0, ChangeTier.LIVE, "Absorb green",
                "Absorption of green light per block of water (Beer-Lambert coefficient).");
        absorbB = config.percent("absorption_b", 0.06, 0.0, 1.0, ChangeTier.LIVE, "Absorb blue",
                "Absorption of blue light per block of water (Beer-Lambert coefficient).");
        config.group(); // surface
        reflection = config.percent("reflection", 1.0, 0.0, 1.0, ChangeTier.LIVE, "Reflection",
                "Sky reflection strength.");
        specular = config.percent("specular", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Sun glints",
                "Sun and moon glint strength.");
        waveNormals = config.percent("wave_normals", 0.6, 0.0, 2.0, ChangeTier.LIVE, "Ripples",
                "Ripple strength in reflections and refraction.");
        texture = config.percent("texture", 0.2, 0.0, 1.0, ChangeTier.LIVE, "Texture",
                "How much of the water texture's pattern stays visible.");
        refractionStrength = config.percent("refraction", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Refraction strength",
                "How strongly ripples bend what lies under the water.");
        foamStrength = config.percent("foam", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Foam strength", "Shore foam strength.");
        config.group(); // caustics
        causticsStyle = config.percent("caustics", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Style caustics",
                "Caustic strength of the style.");
        causticScale = config.percent("caustic_scale", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Caustic size",
                "Caustic cells per block (higher = smaller cells).");
        causticFade = config.slider("caustic_fade", 10, 2, 15, 1, ChangeTier.LIVE, "Caustic depth",
                "Depth in blocks at which caustics have faded out.");
        config.group(); // underwater fog
        fogR = config.slider("fog_r", 20, 0, 255, 1, ChangeTier.LIVE, "Fog red", "Underwater fog colour, red.");
        fogG = config.slider("fog_g", 70, 0, 255, 1, ChangeTier.LIVE, "Fog green", "Underwater fog colour, green.");
        fogB = config.slider("fog_b", 130, 0, 255, 1, ChangeTier.LIVE, "Fog blue", "Underwater fog colour, blue.");
        fogMix = config.percent("fog_mix", 0.7, 0.0, 1.0, ChangeTier.LIVE, "Fog colour mix",
                "How much the fog colour above replaces the biome's water fog colour.");
        fogDistance = config.percent("fog_distance", 1.2, 0.25, 3.0, ChangeTier.LIVE, "Fog distance",
                "Underwater visibility of the style, relative to vanilla.");
        config.register();
    }
}
