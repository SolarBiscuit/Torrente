package com.solarbiscuit.atmosphera.torrente.style;

import com.solarbiscuit.atmosphera.torrente.Torrente;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

/**
 * Loads the preset styles from {@code assets/torrente/styles/<style>.json} at every resource reload; a resource pack
 * replaces a preset by shipping the same path. Keys a file leaves out keep {@link WaterStyle#FALLBACK}.
 * See {@code atmosphera.json} for the format (named {@code unbound.json} before 0.5.0). VANILLA and CUSTOM have no file.
 */
public final class StyleLoader extends SimplePreparableReloadListener<Map<TorrenteConfig.Style, WaterStyle>> {
    @Override
    protected Map<TorrenteConfig.Style, WaterStyle> prepare(ResourceManager manager, ProfilerFiller profiler) {
        // 0.5.1: AtmospheraLib's preset loader (0.11.0).
        return com.solarbiscuit.atmosphera.lib.util.JsonPresets.load(manager, Torrente.MOD_ID, "styles",
                TorrenteConfig.Style.class,
                style -> style != TorrenteConfig.Style.CUSTOM && style != TorrenteConfig.Style.VANILLA,
                json -> WaterStyle.fromJson(json, WaterStyle.FALLBACK), Torrente.LOGGER, "Water style");
    }

    @Override
    protected void apply(Map<TorrenteConfig.Style, WaterStyle> styles, ResourceManager manager, ProfilerFiller profiler) {
        Styles.publish(styles);
        Torrente.LOGGER.info("Loaded {} water styles.", styles.size());
    }
}
