package com.slwold.jeirecipetree;

import com.slwold.jeirecipetree.client.JERTLog;
import com.slwold.jeirecipetree.client.SafeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

@EventBusSubscriber(modid = JERT.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class JERTConfigReadiness {

    private JERTConfigReadiness() {
    }

    @SubscribeEvent
    static void onClientConfigLoaded(ModConfigEvent.Loading event) {
        if (!isOurClientConfig(event.getConfig())) {
            return;
        }
        boolean developerMode = SafeConfig.developerMode();
        JERT.markDeveloperMode(developerMode);
        JERTLog.summary("JERT config ready | developerMode={}", developerMode);
    }

    @SubscribeEvent
    static void onClientConfigReloaded(ModConfigEvent.Reloading event) {
        if (!isOurClientConfig(event.getConfig())) {
            return;
        }
        JERT.markDeveloperMode(SafeConfig.developerMode());
        JERTLog.debug("config reloaded | developerMode={}", SafeConfig.developerMode());
    }

    private static boolean isOurClientConfig(ModConfig config) {
        return config != null
            && JERT.MODID.equals(config.getModId())
            && config.getType() == ModConfig.Type.CLIENT;
    }
}
