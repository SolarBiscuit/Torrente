package com.solarbiscuit.atmosphera.torrente.style;

import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;

import java.util.EnumMap;
import java.util.Map;

/** The loaded presets and the style the config selects. Read on the render thread (frame uniforms, fog events). */
public final class Styles {
    /** Shader values of torrente_Mode.x. */
    public static final int DEPTH_OFF = 0;
    public static final int DEPTH_TORRENTE = 1;
    public static final int DEPTH_BATHYMETRY = 2;

    private static volatile Map<TorrenteConfig.Style, WaterStyle> presets = new EnumMap<>(TorrenteConfig.Style.class);
    private static WaterStyle custom;
    /** The custom-style option values {@link #custom} was built from (0.1.10: rebuilt only when one changes). */
    private static double[] customValues;
    private static com.solarbiscuit.atmosphera.lib.options.AtmosOption<?>[] customOptions;

    private Styles() {
    }

    static void publish(Map<TorrenteConfig.Style, WaterStyle> loaded) {
        presets = loaded;
    }

    /**
     * The selected style. CUSTOM is rebuilt from the config only when one of its option values changed (0.1.10: it
     * was rebuilt every frame, a record and three arrays each time); the check reads the cached option values and
     * allocates nothing. {@code frame} is unused since 0.1.10 and kept for callers.
     */
    public static WaterStyle active(long frame) {
        TorrenteConfig.Style selected = TorrenteConfig.style.get();
        if (selected == TorrenteConfig.Style.CUSTOM) {
            if (customChanged() || custom == null) {
                custom = WaterStyle.fromConfig();
            }
            return custom;
        }
        return presets.getOrDefault(selected, WaterStyle.FALLBACK);
    }

    /** Render thread: compares the custom-style options with the values last used, and remembers the new ones. */
    private static boolean customChanged() {
        if (customOptions == null) {
            customOptions = new com.solarbiscuit.atmosphera.lib.options.AtmosOption<?>[]{
                    TorrenteConfig.colorR, TorrenteConfig.colorG, TorrenteConfig.colorB, TorrenteConfig.biomeTint,
                    TorrenteConfig.opacity, TorrenteConfig.absorbR, TorrenteConfig.absorbG, TorrenteConfig.absorbB,
                    TorrenteConfig.reflection, TorrenteConfig.specular, TorrenteConfig.waveNormals,
                    TorrenteConfig.texture, TorrenteConfig.refractionStrength, TorrenteConfig.foamStrength,
                    TorrenteConfig.causticsStyle, TorrenteConfig.causticScale, TorrenteConfig.causticFade,
                    TorrenteConfig.fogR, TorrenteConfig.fogG, TorrenteConfig.fogB, TorrenteConfig.fogMix,
                    TorrenteConfig.fogDistance};
            customValues = new double[customOptions.length];
            java.util.Arrays.fill(customValues, Double.NaN);
        }
        boolean changed = false;
        for (int i = 0; i < customOptions.length; i++) {
            double value = ((Number) customOptions[i].get()).doubleValue();
            if (value != customValues[i]) {
                customValues[i] = value;
                changed = true;
            }
        }
        return changed;
    }

    public static int depthMode() {
        return switch (TorrenteConfig.depthMode.get()) {
            case OFF -> DEPTH_OFF;
            case TORRENTE -> DEPTH_TORRENTE;
            case BATHYMETRY -> DEPTH_BATHYMETRY;
            case AUTO -> AtmosCompat.isLoaded("bathymetry") ? DEPTH_BATHYMETRY : DEPTH_TORRENTE;
        };
    }
}
