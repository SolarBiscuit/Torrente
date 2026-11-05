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
    private static long customFrame = -1;

    private Styles() {
    }

    static void publish(Map<TorrenteConfig.Style, WaterStyle> loaded) {
        presets = loaded;
    }

    /** The selected style. CUSTOM is rebuilt from the config at most once per {@code frame}. */
    public static WaterStyle active(long frame) {
        TorrenteConfig.Style selected = TorrenteConfig.style.get();
        if (selected == TorrenteConfig.Style.CUSTOM) {
            if (custom == null || frame != customFrame) {
                custom = WaterStyle.fromConfig();
                customFrame = frame;
            }
            return custom;
        }
        return presets.getOrDefault(selected, WaterStyle.FALLBACK);
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
