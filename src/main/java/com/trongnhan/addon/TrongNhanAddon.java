package com.trongnhan.addon;

import com.mojang.logging.LogUtils;
import com.trongnhan.addon.modules.AutoTotemModule;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class TrongNhanAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("TrongNhan");

    @Override
    public void onInitialize() {
        LOG.info("[TrongNhan] init");
        Modules.get().add(new AutoTotemModule());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.trongnhan.addon";
    }
}
