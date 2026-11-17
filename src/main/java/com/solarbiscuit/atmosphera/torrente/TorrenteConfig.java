package com.solarbiscuit.atmosphera.torrente;

import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

/** {@code config/Atmosphera/torrente-client.toml} and the Torrente group in Video Settings > Atmosphera. */
public final class TorrenteConfig {
    /**
     * 0.5.0: Unbound became ATMOSPHERA, Aurora CELESTIAL, Reimagined VANILLA_PLUS; the old Vanilla Plus became VANILLA
     * (vanilla water outright: Torrente's water shading, waves, caustics and underwater fog stay off) and Glimmer was
     * replaced by LAGOON. Saved values are converted once ({@link Torrente}).
     */
    public enum Style { ATMOSPHERA, LAGOON, CELESTIAL, VANILLA_PLUS, VANILLA, CUSTOM }

    public enum Quality { LOW, HIGH }

    /** AUTO: Bathymetry's depth tint when it is installed, otherwise Torrente's own. */
    public enum DepthMode { AUTO, TORRENTE, BATHYMETRY, OFF }

    public enum DebugChannel { OFF, TINT, LIGHT, LUMA, THICKNESS, KEEP }

    public static AtmosConfig.FeatureSwitch shading;
    public static AtmosOption<Style> style;
    /** 0.5.0: marks a file whose style value uses the 0.5.0 names (written by Forge; see Torrente.convertStyleNames). */
    public static AtmosOption<Integer> styleNames;
    public static AtmosOption<Quality> quality;
    public static AtmosOption<DepthMode> depthMode;
    public static AtmosOption<Boolean> refraction;
    public static AtmosOption<Boolean> foam;
    public static AtmosOption<Boolean> lodWater;
    public static AtmosOption<DebugChannel> debugChannel;
    public static AtmosConfig.FeatureSwitch waves;
    public static AtmosOption<Double> waveHeight;
    public static AtmosOption<Boolean> windAligned;
    public static AtmosOption<Double> crestGlow;
    public static AtmosOption<Boolean> rainRipples;
    public static AtmosOption<Boolean> mirror;
    public static AtmosOption<Boolean> ripples;
    public static AtmosOption<Boolean> waterline;
    public static AtmosOption<Boolean> causticDispersion;
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
        AtmosConfig config = AtmosConfig.create(Torrente.MOD_ID, "Torrente");
        config.section("water", "Water", "Water surface: colour, depth, reflection and glints.");
        shading = config.featureSwitch("shading", true, "Water shading",
                "Styled water colour that deepens with depth, sky reflection, sun glints, ripples and shore foam.");
        style = config.choice("style", Style.ATMOSPHERA, ChangeTier.LIVE, "Water style",
                "ATMOSPHERA: clear blue (RatPack). LAGOON: very clear water, bright blues and turquoise shallows, lively "
                        + "caustics, and a colour that follows the sky (sunsets, overcast, night). CELESTIAL: turquoise "
                        + "shallows, blue-violet depths (after Aurora Fantasy). VANILLA_PLUS: vanilla's texture with light "
                        + "effects. VANILLA: vanilla water; Torrente's water shading, waves, caustics and underwater fog stay "
                        + "off. CUSTOM: the values in the Custom style group.");
        config.fileOnly(true);
        styleNames = config.slider("style_names", 2, 1, 2, 1, ChangeTier.LIVE, "Style names version",
                "Torrente 0.5.0 renamed the water styles; 2 marks this file as using the new names. Leave it as it is.");
        config.fileOnly(false);
        quality = config.choice("quality", Quality.HIGH, ChangeTier.LIVE, "Water quality",
                "LOW: fewer ripple octaves and one caustic layer. HIGH: full detail.");
        depthMode = config.choice("depth_mode", DepthMode.AUTO, ChangeTier.LIVE, "Depth colour",
                "Who darkens deep water. AUTO: Bathymetry when it is installed, otherwise Torrente. "
                        + "BATHYMETRY: keep Bathymetry's tint, Torrente only sets opacity. OFF: no depth effect.");
        config.group("surface", "Surface");
        refraction = config.toggle("refraction", true, ChangeTier.LIVE, "Refraction",
                "Bends what lies under the water with the ripples (one extra screen copy).");
        foam = config.toggle("foam", true, ChangeTier.LIVE, "Shore foam",
                "A soft white edge where water meets blocks.");
        config.group("water", "Water");
        lodWater = config.toggle("lod_water", true, ChangeTier.LIVE, "Water on Voxy LODs",
                "Distant Voxy/NeoVoxy LOD water gets the same shading as the water nearby (colour, depth, reflection, "
                        + "glints, waves), so there is no visible ring where the vanilla render distance ends. "
                        + "Needs AtmospheraLib's Voxy LOD shading; no shader pack.");
        config.group("surface", "Surface");
        crestGlow = config.percent("crest_glow", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Crest glow",
                "Looking towards a low sun, light shines through the tops of the waves in the water's colour (0% = off). "
                        + "Needs water waves on.");
        rainRipples = config.toggle("rain_ripples", true, ChangeTier.LIVE, "Rain ripples",
                "Rings spread on water open to the sky while it rains.");
        mirror = config.toggle("mirror", true, ChangeTier.LIVE, "Reflections of the scene",
                "Water near you mirrors the land, trees and buildings around it (planar reflections; needs OpenGL 4.3). "
                        + "Half resolution on MEDIUM quality, full on HIGH, off on LOW. Elsewhere water reflects the sky.");
        ripples = config.toggle("ripples", true, ChangeTier.LIVE, "Ripples from movement",
                "Players, mobs, boats and items moving through the water send rings across it (within 16 blocks).");
        config.groupAtEnd("debug", "Debug");
        debugChannel = config.choice("debug_channel", DebugChannel.OFF, ChangeTier.LIVE, "Debug: show input",
                "Diagnostics: paints one input of the water shading instead of the water, on near and Voxy LOD water "
                        + "alike, so the two can be compared at the LOD transition (NeoVoxy /voxy debug handoff compare). "
                        + "TINT: biome/Bathymetry tint. LIGHT: lightmap colour. LUMA: texture brightness. THICKNESS: "
                        + "water thickness along the view ray (white = 32 blocks). KEEP: share of what lies behind.");

        config.section("waves", "Waves", "Waves: the water surface rises and falls with the wind.");
        waves = config.featureSwitch("enabled", true, "Water waves",
                "Water surfaces move up and down, more in wind and rain.");
        waveHeight = config.percent("height", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Wave height",
                "How high the waves are. Always capped at 0.1 blocks.");
        windAligned = config.toggle("wind_aligned", true, ChangeTier.LIVE, "Waves follow the wind",
                "The swell running downwind grows and the others calm down, so the main waves line up with the wind.");

        config.section("caustics", "Caustics", "Caustics: moving light patterns on blocks under water.");
        caustics = config.featureSwitch("enabled", true, "Caustics",
                "Light patterns on the floor and walls of shallow sunlit water.");
        // New key (was strength, 0-200%): 50% here is the old 20%, the look players settled on (2026-11-02).
        causticStrength = config.percent("intensity", 0.5, 0.0, 1.0, ChangeTier.LIVE, "Caustic strength",
                "How bright the caustic pattern is. 50% is the default; 100% is twice as bright.");
        causticDispersion = config.toggle("dispersion", false, ChangeTier.LIVE, "Caustic dispersion",
                "HIGH quality only: rainbow edges on caustics, stronger in deeper water. Costs about 2-3 times the "
                        + "caustic work on lit floors.");

        config.section("underwater", "Underwater", "Underwater view.");
        underwaterFog = config.featureSwitch("fog", true, "Underwater fog",
                "Style colour and visibility of the fog while your head is under water.");
        visibility = config.percent("visibility", 1.0, 0.25, 3.0, ChangeTier.LIVE, "Underwater visibility",
                "How far you can see under water, on top of the style's own value.");
        waterline = config.toggle("waterline", true, ChangeTier.LIVE, "Waterline",
                "With your eyes right at the surface, the view splits at the water line: tinted below, clear above.");

        // 0.2.6: on the main page (was a page of its own). 0.5.0: a dropdown group with a heading per part; the TOML
        // section and keys are unchanged.
        config.section("custom", "Custom style", "Custom style: the water style used while water.style = CUSTOM.");
        config.subheading("colour", "Colour");
        colorR = config.slider("color_r", 40, 0, 255, 1, ChangeTier.LIVE, "Water red", "Deep water colour, red.");
        colorG = config.slider("color_g", 110, 0, 255, 1, ChangeTier.LIVE, "Water green", "Deep water colour, green.");
        colorB = config.slider("color_b", 190, 0, 255, 1, ChangeTier.LIVE, "Water blue", "Deep water colour, blue.");
        biomeTint = config.percent("biome_tint", 0.6, 0.0, 1.0, ChangeTier.LIVE, "Biome colour",
                "How much the biome's water colour (swamps, warm oceans) steers the water colour.");
        opacity = config.percent("opacity", 0.35, 0.0, 1.0, ChangeTier.LIVE, "Opacity",
                "Opacity of very shallow water.");
        config.subheading("absorption", "Absorption");
        absorbR = config.percent("absorption_r", 0.25, 0.0, 1.0, ChangeTier.LIVE, "Absorb red",
                "Absorption of red light per block of water (Beer-Lambert coefficient).");
        absorbG = config.percent("absorption_g", 0.09, 0.0, 1.0, ChangeTier.LIVE, "Absorb green",
                "Absorption of green light per block of water (Beer-Lambert coefficient).");
        absorbB = config.percent("absorption_b", 0.06, 0.0, 1.0, ChangeTier.LIVE, "Absorb blue",
                "Absorption of blue light per block of water (Beer-Lambert coefficient).");
        config.subheading("surface", "Surface");
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
        config.subheading("caustics", "Caustics");
        causticsStyle = config.percent("caustics", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Style caustics",
                "Caustic strength of the style.");
        causticScale = config.percent("caustic_scale", 1.0, 0.25, 2.0, ChangeTier.LIVE, "Caustic size",
                "Caustic cells per block (higher = smaller cells).");
        causticFade = config.slider("caustic_fade", 10, 2, 15, 1, ChangeTier.LIVE, "Caustic depth",
                "Depth in blocks at which caustics have faded out.");
        config.subheading("fog", "Underwater fog");
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
