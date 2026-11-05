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
 */
public record WaterStyle(float[] waterColor, float biomeTint, float opacity, float[] absorption, float reflection,
                         float specular, float waveNormals, float texture, float refraction, float foam,
                         float caustics, float causticScale, float causticFade, float[] fogColor, float fogMix,
                         float fogDistance) {

    /** Neutral clear blue, used for any value a style file leaves out or gets wrong. */
    public static final WaterStyle FALLBACK = new WaterStyle(rgb(0x2E6EB8), 0.6f, 0.35f,
            new float[]{0.25f, 0.09f, 0.06f}, 1f, 1f, 0.6f, 0.2f, 1f, 0.5f, 1f, 1f, 10f, rgb(0x14467F), 0.7f, 1.2f);

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
                num(json, "fog_distance", base.fogDistance, 0.1f, 4f));
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
                TorrenteConfig.fogDistance.get().floatValue());
    }

    private static float[] rgb(int hex) {
        return new float[]{((hex >> 16) & 255) / 255f, ((hex >> 8) & 255) / 255f, (hex & 255) / 255f};
    }

    private static float num(JsonObject json, String key, float fallback, float min, float max) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return Math.max(min, Math.min(max, e.getAsFloat()));
    }

    /** {@code "#RRGGBB"}. */
    private static float[] color(JsonObject json, String key, float[] fallback) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String s = e.getAsString().trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            return s.length() == 6 ? rgb(Integer.parseInt(s, 16)) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
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
            out[i] = Math.max(0f, Math.min(4f, a.get(i).getAsFloat()));
        }
        return out;
    }
}
