package com.solarbiscuit.atmosphera.torrente.water;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.luminance;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import com.solarbiscuit.atmosphera.torrente.style.Styles;
import com.solarbiscuit.atmosphera.torrente.style.WaterStyle;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * The style's fog while the camera is in water, through Forge's fog events. They fire inside
 * {@code FogRenderer.setupColor/setupFog}, before the TAIL mixins of Atmospherics and NeoVoxy, so those still see
 * (and NeoVoxy still captures) the final values. Bathymetry's fog-distance mixin scales the distance as usual.
 */
public final class UnderwaterFog {
    static AtmosFeature feature;

    private UnderwaterFog() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(UnderwaterFog::onFog);
        forgeBus.addListener(UnderwaterFog::onColor);
    }

    private static boolean applies(FogType type) {
        return type == FogType.WATER && feature != null && feature.isActive();
    }

    /** Distance: vanilla's (water vision, swamps) times the style and the option. Cancelled so Forge applies it. */
    private static void onFog(ViewportEvent.RenderFog event) {
        if (!applies(event.getType())) {
            return;
        }
        WaterStyle style = Styles.active(AtmosClient.frame());
        float scale = style.fogDistance() * TorrenteConfig.visibility.get().floatValue();
        event.setFarPlaneDistance(event.getFarPlaneDistance() * scale);
        event.setCanceled(true);
    }

    /** Colour: towards the style colour, at the brightness vanilla computed (night, depth and effects carry over). */
    private static void onColor(ViewportEvent.ComputeFogColor event) {
        Camera camera = event.getCamera();
        if (!applies(camera.getFluidInCamera()) || !(camera.getEntity().level() instanceof ClientLevel level)) {
            return;
        }
        WaterStyle style = Styles.active(AtmosClient.frame());
        if (style.fogMix() <= 0f) {
            return;
        }
        // 0.5.2: the camera's own block position (it allocated a BlockPos every frame under water).
        int base = level.getBiome(camera.getBlockPosition()).value().getWaterFogColor();
        float baseLum = luminance(((base >> 16) & 255) / 255f, ((base >> 8) & 255) / 255f, (base & 255) / 255f);
        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();
        float brightness = Math.min(1.5f, luminance(r, g, b) / Math.max(baseLum, 0.02f));
        float[] target = style.fogColor();
        float mix = style.fogMix();
        event.setRed(r + (target[0] * brightness - r) * mix);
        event.setGreen(g + (target[1] * brightness - g) * mix);
        event.setBlue(b + (target[2] * brightness - b) * mix);
    }
}
