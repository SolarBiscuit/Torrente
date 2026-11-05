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
        TorrenteConfig.register();
        WaterFeatures.register();
        UnderwaterFog.register(MinecraftForge.EVENT_BUS);
        modBus.addListener((RegisterClientReloadListenersEvent e) -> e.registerReloadListener(new StyleLoader()));
    }
}
