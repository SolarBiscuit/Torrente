package com.solarbiscuit.atmosphera.torrente;

import com.solarbiscuit.atmosphera.torrente.style.StyleLoader;
import com.solarbiscuit.atmosphera.torrente.water.UnderwaterFog;
import com.solarbiscuit.atmosphera.torrente.water.WaterFeatures;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Torrente: water for the Atmosphera suite. Everything goes through the AtmospheraLib API: the config and options
 * tab, five features (surface shading, refraction, waves, caustics, underwater fog), their vertex bits and snippets,
 * and data-driven water styles. Client-only; on a dedicated server it does nothing.
 */
@Mod(Torrente.MOD_ID)
public final class Torrente {
    public static final String MOD_ID = "torrente";
    public static final Logger LOGGER = LoggerFactory.getLogger("Torrente");

    public Torrente() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("Torrente is client-only; inactive on this dedicated server.");
            return;
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        convertStyleNames();
        TorrenteConfig.register();
        WaterFeatures.register();
        UnderwaterFog.register(MinecraftForge.EVENT_BUS);
        // 0.3.0: /atmosphera reload shaders (and the reload key) rebuilds the reflection, ripple and waterline passes.
        com.solarbiscuit.atmosphera.lib.options.AtmosReload.register(MOD_ID, () -> {
            com.solarbiscuit.atmosphera.torrente.water.Mirror.release();
            com.solarbiscuit.atmosphera.torrente.water.Ripples.release();
            com.solarbiscuit.atmosphera.torrente.water.Waterline.release();
        });
        modBus.addListener((RegisterClientReloadListenersEvent e) -> e.registerReloadListener(new StyleLoader()));
    }

    /**
     * 0.5.0 renamed the water styles. A file written before 0.5.0 (no {@code style_names} key) has its style converted
     * once, all names at the same time, before Forge reads it: VANILLA_PLUS to VANILLA, REIMAGINED to VANILLA_PLUS,
     * AURORA to CELESTIAL, UNBOUND to ATMOSPHERA and GLIMMER to LAGOON. Forge then adds {@code style_names = 2}.
     */
    private static void convertStyleNames() {
        java.util.Map<String, String> names = java.util.Map.of("VANILLA_PLUS", "VANILLA", "REIMAGINED", "VANILLA_PLUS",
                "AURORA", "CELESTIAL", "UNBOUND", "ATMOSPHERA", "GLIMMER", "LAGOON");
        // 0.5.1: AtmospheraLib's one-time rewrite (0.11.0).
        com.solarbiscuit.atmosphera.lib.options.ConfigMigration.Change change =
                com.solarbiscuit.atmosphera.lib.options.ConfigMigration.rewrite("torrente-client.toml", "style_names", null,
                        java.util.regex.Pattern.compile("(?m)^(\\s*style\\s*=\\s*\")([A-Za-z_]+)(\")"),
                        old -> names.get(old.toUpperCase(java.util.Locale.ROOT)), LOGGER, "water style");
        if (change != null) {
            LOGGER.info("Torrente 0.5.0 renamed the water styles: your style {} is now {}.", change.before(), change.after());
        }
    }
}
