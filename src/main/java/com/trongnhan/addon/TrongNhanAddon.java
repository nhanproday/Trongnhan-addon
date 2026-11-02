package com.trongnhan.addon;

import com.mojang.logging.LogUtils;
import com.trongnhan.addon.modules.AutoTotemModule;
import com.trongnhan.addon.modules.SpeedMine; // Thêm import cho SpeedMine
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
        
        // Đăng ký các module
        Modules.get().add(new AutoTotemModule());
        Modules.get().add(new SpeedMinePlusPlus()); // Thêm dòng này để đăng ký SpeedMine
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
