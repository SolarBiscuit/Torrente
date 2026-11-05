package com.solarbiscuit.atmosphera.torrente.style;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.solarbiscuit.atmosphera.torrente.Torrente;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the preset styles from {@code assets/torrente/styles/<style>.json} at every resource reload; a resource pack
 * replaces a preset by shipping the same path. Keys a file leaves out keep {@link WaterStyle#FALLBACK}.
 * See {@code unbound.json} for the format.
 */
public final class StyleLoader extends SimplePreparableReloadListener<Map<TorrenteConfig.Style, WaterStyle>> {
    @Override
    protected Map<TorrenteConfig.Style, WaterStyle> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<TorrenteConfig.Style, WaterStyle> styles = new EnumMap<>(TorrenteConfig.Style.class);
        for (TorrenteConfig.Style style : TorrenteConfig.Style.values()) {
            if (style == TorrenteConfig.Style.CUSTOM) {
                continue;
            }
            String name = style.name().toLowerCase(Locale.ROOT);
            ResourceLocation id = new ResourceLocation(Torrente.MOD_ID, "styles/" + name + ".json");
            Optional<Resource> resource = manager.getResource(id);
            if (resource.isEmpty()) {
                Torrente.LOGGER.warn("Water style {} is missing; using the fallback style.", id);
                continue;
            }
            try (Reader reader = resource.get().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                styles.put(style, WaterStyle.fromJson(json.getAsJsonObject(), WaterStyle.FALLBACK));
            } catch (Exception e) {
                Torrente.LOGGER.warn("Water style {} could not be read ({}); using the fallback style.", id, e.toString());
            }
        }
        return styles;
    }

    @Override
    protected void apply(Map<TorrenteConfig.Style, WaterStyle> styles, ResourceManager manager, ProfilerFiller profiler) {
        Styles.publish(styles);
        Torrente.LOGGER.info("Loaded {} water styles.", styles.size());
    }
}
