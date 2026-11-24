package com.slwold.jeirecipetree;

import com.slwold.jeirecipetree.client.ConfigHygiene;
import com.slwold.jeirecipetree.client.ConfigScreenRegistration;
import com.slwold.jeirecipetree.client.JERTLog;
import com.slwold.jeirecipetree.client.JeiCapabilities;
import com.slwold.jeirecipetree.client.StorageDrivers;
import com.slwold.jeirecipetree.client.StorageDriverRegistry;
import com.slwold.jeirecipetree.client.RtsStorageDriver;
import com.slwold.jeirecipetree.compat.ae2.Ae2StorageDriver;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;

import java.util.Locale;

@Mod(value = JERT.MODID, dist = Dist.CLIENT)
public final class JERT {
    public static final String MODID = "jeirecipetree";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile String developerState = "pending";

    public JERT() {

        repairStaleConfig();
        JERTConfig.register(ModLoadingContext.get().getActiveContainer());

        ConfigScreenRegistration.register(ModLoadingContext.get().getActiveContainer());

        StorageDrivers.registerStandard();
        if (ModList.get().isLoaded("ae2")) {
            Ae2StorageDriver.register();
        }

        if (ModList.get().isLoaded("rtsbuilding")) {
            StorageDriverRegistry.register(RtsStorageDriver.INSTANCE);
        }
        StorageDrivers.registerFallback();
        logStartupSummary();

        JeiCapabilities.logSummary();
        warnAboutBookmarkConflicts();
    }

    private static volatile String configHygieneSummary = "not-run";

    public static String configHygieneSummary() {
        return configHygieneSummary;
    }

    private static void repairStaleConfig() {
        try {
            ConfigHygiene.Summary summary =
                ConfigHygiene.apply(JERTConfig.KNOWN_KEYS, JERTConfig.DEFAULT_BLOCKS);
            configHygieneSummary = summary.toString();

            JERTLog.summary("config hygiene: {} | file={} | removed={} added={}",
                summary.detail(), summary.file(), summary.removed(), summary.added());
        } catch (Throwable error) {

            configHygieneSummary = "failed: " + error;
            JERTLog.warn("config hygiene failed (framework correction will handle it): {}", error);
        }
    }

    private static void logStartupSummary() {
        LOGGER.info(startupSummary());
    }

    public static String startupSummary() {
        return String.format(Locale.ROOT, "JEI Recipe Tree %s | MC %s | JEI %s | optional=[%s] | dev=%s",
            modVersion(MODID), minecraftVersion(), modVersion("jei"), optionalMods(), developerState);
    }

    public static void markDeveloperMode(boolean enabled) {
        developerState = String.valueOf(enabled);
    }

    private static String modVersion(String modId) {
        try {
            var file = FMLLoader.getLoadingModList().getModFileById(modId);
            return file == null ? "absent" : file.versionString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String minecraftVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String optionalMods() {
        StringBuilder present = new StringBuilder();
        for (String modId : new String[] {
            "ae2", "refinedstorage", "sophisticatedstorage", "create",
            "sophisticateditemactions", "modernui"
        }) {
            if (!ModList.get().isLoaded(modId)) {
                continue;
            }
            if (!present.isEmpty()) {
                present.append(',');
            }
            present.append(modId);
        }
        return present.toString();
    }

    private static void warnAboutBookmarkConflicts() {
        warnIfLoaded("jei_enhancements",
            "JEI-Enhancements detected: it also reworks the bookmark sidebar (paging/grouping/count). "
                + "The tree rows now live in a separate panel, so they usually coexist; if the panel "
                + "overlaps the page buttons, reduce the sidebar width or the bookmark row count.");
        warnIfLoaded("jei_plus_plus",
            "jei_plus_plus detected: it overlaps heavily with the recipe tree; do not install both.");
    }

    private static void warnIfLoaded(String modId, String message) {
        try {
            if (FMLLoader.getLoadingModList().getModFileById(modId) != null) {
                LOGGER.warn(message);
            }
        } catch (Throwable ignored) {

        }
    }
}
