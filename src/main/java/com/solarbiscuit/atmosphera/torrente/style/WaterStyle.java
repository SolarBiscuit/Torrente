package com.solarbiscuit.atmosphera.torrente.style;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;

/**
 * One water look. Every value is a live uniform (no recompile, no remesh). Colours are 0..1 sRGB, as Minecraft
 * shades. The JSON keys in {@code assets/torrente/styles/*.json} are the field names in snake_case; missing keys keep
 * the {@link #FALLBACK} value.
 *
 * @param waterColor   colour of deep water in full daylight (the in-scatter colour)
 * @param biomeTint    0..1, how much the biome water colour steers {@code waterColor}
 * @param opacity      opacity of very shallow water
 * @param absorption   Beer-Lambert coefficient per block, per channel
 * @param reflection   Fresnel sky reflection strength
 * @param specular     sun and moon glint strength
 * @param waveNormals  ripple strength in shading and refraction
 * @param texture      0..1, how much of the water texture's pattern stays visible
 * @param refraction   screen-space bend strength (1.0 = about 2 % of the screen at full ripple)
 * @param foam         shore foam strength
 * @param caustics     caustic strength
 * @param causticScale caustic cells per block
 * @param causticFade  depth in blocks at which caustics are gone
 * @param fogColor     underwater fog colour
 * @param fogMix       0..1, how much {@code fogColor} replaces the biome's water fog colour
 * @param fogDistance  underwater visibility relative to vanilla
 * @param shallowColor     0.5.0: the colour shallow water takes (turquoise in Lagoon)
 * @param shallowMix       0.5.0: 0..1, how much shallow water takes {@code shallowColor} (0: none)
 * @param skyBlend         0.5.0: 0..1, how much the water's colour follows the sky and fog colour (sunsets, overcast, night)
 * @param causticSharpness 0.5.0: caustic edge sharpness (1: the soft edges of 0.4)
 * @param causticTint      0.5.0: caustic light colour (white: none)
 */
public record WaterStyle(float[] waterColor, float biomeTint, float opacity, float[] absorption, float reflection,
                         float specular, float waveNormals, float texture, float refraction, float foam,
                         float caustics, float causticScale, float causticFade, float[] fogColor, float fogMix,
                         float fogDistance, float[] shallowColor, float shallowMix, float skyBlend,
                         float causticSharpness, float[] causticTint) {

    /** Neutral clear blue, used for any value a style file leaves out or gets wrong. */
    public static final WaterStyle FALLBACK = new WaterStyle(rgb(0x2E6EB8), 0.6f, 0.35f,
            new float[]{0.25f, 0.09f, 0.06f}, 1f, 1f, 0.6f, 0.2f, 1f, 0.5f, 1f, 1f, 10f, rgb(0x14467F), 0.7f, 1.2f,
            rgb(0x2E6EB8), 0f, 0f, 1f, new float[]{1f, 1f, 1f});

    /**
     * 0.5.0: the VANILLA style's values. Its water features are off, so only what other mods read matters: no
     * absorption (Lumis then uses its own underwater colour), no fog colour, vanilla's visibility.
     */
    public static final WaterStyle NEUTRAL = new WaterStyle(rgb(0x3F76E4), 1f, 0.6f, new float[]{0f, 0f, 0f}, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 1f, 10f, rgb(0x3F76E4), 0f, 1f, rgb(0x3F76E4), 0f, 0f, 1f, new float[]{1f, 1f, 1f});

    public static WaterStyle fromJson(JsonObject json, WaterStyle base) {
        return new WaterStyle(
                color(json, "water_color", base.waterColor),
                num(json, "biome_tint", base.biomeTint, 0f, 1f),
                num(json, "opacity", base.opacity, 0f, 1f),
                vec3(json, "absorption", base.absorption),
                num(json, "reflection", base.reflection, 0f, 1f),
                num(json, "specular", base.specular, 0f, 4f),
                num(json, "wave_normals", base.waveNormals, 0f, 4f),
                num(json, "texture", base.texture, 0f, 1f),
                num(json, "refraction", base.refraction, 0f, 4f),
                num(json, "foam", base.foam, 0f, 1f),
                num(json, "caustics", base.caustics, 0f, 4f),
                num(json, "caustic_scale", base.causticScale, 0.1f, 4f),
                num(json, "caustic_fade", base.causticFade, 1f, 15f),
                color(json, "fog_color", base.fogColor),
                num(json, "fog_mix", base.fogMix, 0f, 1f),
                num(json, "fog_distance", base.fogDistance, 0.1f, 4f),
                color(json, "shallow_color", base.shallowColor),
                num(json, "shallow_mix", base.shallowMix, 0f, 1f),
                num(json, "sky_blend", base.skyBlend, 0f, 1f),
                num(json, "caustic_sharpness", base.causticSharpness, 0.25f, 4f),
                color(json, "caustic_tint", base.causticTint));
    }

    /** The CUSTOM style, from the {@code [custom]} TOML section. */
    public static WaterStyle fromConfig() {
        return new WaterStyle(
                new float[]{TorrenteConfig.colorR.get() / 255f, TorrenteConfig.colorG.get() / 255f, TorrenteConfig.colorB.get() / 255f},
                TorrenteConfig.biomeTint.get().floatValue(),
                TorrenteConfig.opacity.get().floatValue(),
                new float[]{TorrenteConfig.absorbR.get().floatValue(), TorrenteConfig.absorbG.get().floatValue(),
                        TorrenteConfig.absorbB.get().floatValue()},
                TorrenteConfig.reflection.get().floatValue(),
                TorrenteConfig.specular.get().floatValue(),
                TorrenteConfig.waveNormals.get().floatValue(),
                TorrenteConfig.texture.get().floatValue(),
                TorrenteConfig.refractionStrength.get().floatValue(),
                TorrenteConfig.foamStrength.get().floatValue(),
                TorrenteConfig.causticsStyle.get().floatValue(),
                TorrenteConfig.causticScale.get().floatValue(),
                TorrenteConfig.causticFade.get(),
                new float[]{TorrenteConfig.fogR.get() / 255f, TorrenteConfig.fogG.get() / 255f, TorrenteConfig.fogB.get() / 255f},
                TorrenteConfig.fogMix.get().floatValue(),
                TorrenteConfig.fogDistance.get().floatValue(),
                FALLBACK.shallowColor, FALLBACK.shallowMix, FALLBACK.skyBlend, FALLBACK.causticSharpness,
                FALLBACK.causticTint);
    }

    private static float[] rgb(int hex) {
        return com.solarbiscuit.atmosphera.lib.util.Colors.toFloats(hex);
    }

    // 0.5.1: the readers are AtmospheraLib's (0.11.0); colours also take "#RGB" and "0xRRGGBB" now.
    private static float num(JsonObject json, String key, float fallback, float min, float max) {
        return com.solarbiscuit.atmosphera.lib.util.JsonPresets.f(json, key, fallback, min, max);
    }

    private static float[] color(JsonObject json, String key, float[] fallback) {
        return com.solarbiscuit.atmosphera.lib.util.JsonPresets.color(json, key, fallback);
    }

    /** {@code [r, g, b]}. */
    private static float[] vec3(JsonObject json, String key, float[] fallback) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            return fallback;
        }
        JsonArray a = e.getAsJsonArray();
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            if (!a.get(i).isJsonPrimitive() || !a.get(i).getAsJsonPrimitive().isNumber()) {
                return fallback;
            }
            // 0.5.2: a non-finite number (the lenient parser takes NaN and Infinity) keeps the fallback; Math.max
            // and Math.min pass NaN through.
            float v = a.get(i).getAsFloat();
            if (!Float.isFinite(v)) {
                return fallback;
            }
            out[i] = Math.max(0f, Math.min(4f, v));
        }
        return out;
    }
}
