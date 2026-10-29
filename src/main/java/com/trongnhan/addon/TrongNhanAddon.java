package com.trongnhan.addon;

import com.mojang.logging.LogUtils;
import com.trongnhan.addon.modules.AutoShopModule;
import com.trongnhan.addon.modules.AutoTotemModule;
import com.trongnhan.addon.modules.MainhandModule;
import com.trongnhan.addon.modules.VelocityModule;
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
        
        // Nạp 4 module vào
        Modules.get().add(new AutoTotemModule());
        Modules.get().add(new MainhandModule());
        Modules.get().add(new AutoShopModule());
        Modules.get().add(new VelocityModule());
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
