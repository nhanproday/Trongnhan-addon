package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class MainhandModule extends Module {

    public enum Stage { Idle, Step1_FindHotbar, Step2_Open, Step3_OffhandSwap, Step4_Close }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInv = settings.createGroup("Inventory Replenish");

    public final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Khoảng chờ (ticks) giữa các thao tác.")
        .defaultValue(2).min(0).sliderMax(20).build());

    public final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-mode").description("Bật: swap ngầm qua packet. Tắt: mở balo click chuột.")
        .defaultValue(true).build());
        
    public final Setting<Integer> targetSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot").description("Ô hotbar ưu tiên chứa totem (1-9).")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private int delayTimer = 0;
    private Stage stage = Stage.Idle;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Auto totem (PtAddon logic) + Elytra Mainhand Save.");
    }

    @Override
    public void onActivate() {
        stage = Stage.Idle;
        delayTimer = 0;
    }

    @Override
    public void onDeactivate() {
        if (mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.currentScreen instanceof InventoryScreen && !silentMode.get()) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        boolean offhandHasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean isElytraFlying = mc.player.isGliding();
        int currentSlot = mc.player.getInventory().getSelectedSlot();

        if (isElytraFlying && !offhandHasTotem && !mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            for (int i = 0; i <= 8; i++) {
                if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                    mc.player.getInventory().setSelectedSlot(i);
                    try {
                        java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
                        m.setAccessible(true);
                        m.invoke(mc.interactionManager);
                    } catch (Exception ignored) {}
                    delayTimer = actionDelay.get();
                    return;
                }
            }
        }

        int preferredSlot = targetSlot.get() - 1;

        if (!offhandHasTotem) {
            stage = Stage.Step1_FindHotbar;
        } else if (!mc.player.getInventory().getStack(preferredSlot).isOf(Items.TOTEM_OF_UNDYING)) {
            stage = Stage.Step1_FindHotbar; 
        } else {
            stage = Stage.Idle;
        }

        switch (stage) {
            case Step1_FindHotbar -> {
                int hotbarTotem = -1;
                for (int i = 0; i <= 8; i++) {
                    if (i == currentSlot) continue;
                    if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                        hotbarTotem = i;
                        break;
                    }
                }

                if (!offhandHasTotem && hotbarTotem != -1) {
                    executeKeybindSwap(hotbarTotem);
                    delayTimer = actionDelay.get();
                    stage = Stage.Idle;
                } else {
                    int invTotem = findTotemInInventoryOnly();
                    if (invTotem != -1) {
                        stage = Stage.Step2_Open;
                        delayTimer = actionDelay.get();
                    } else {
                        stage = Stage.Idle;
                    }
                }
            }
            case Step2_Open -> {
                if (!silentMode.get()) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                stage = Stage.Step3_OffhandSwap;
                delayTimer = actionDelay.get();
            }
            case Step3_OffhandSwap -> {
                int invTotem = findTotemInInventoryOnly();
                if (invTotem != -1) {
                    int targetIdx = targetSlot.get() - 1;
                    
                    if (silentMode.get()) {
                        ScreenHandler handler = mc.player.currentScreenHandler;
                        mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                            handler.syncId, handler.getRevision(), invTotem, targetIdx, SlotActionType.SWAP, 
                            mc.player.getInventory().getStack(invTotem), new net.minecraft.screen.slot.SlotActionType[0]
                        ));
                    } else {
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, invTotem, targetIdx, SlotActionType.SWAP, mc.player);
                    }
                }
                stage = Stage.Step4_Close;
                delayTimer = actionDelay.get();
            }
            case Step4_Close -> {
                if (!silentMode.get() && mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
                stage = Stage.Idle;
                delayTimer = actionDelay.get();
            }
        }
    }

    private int findTotemInInventoryOnly() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private void executeKeybindSwap(int hotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, 36 + hotbarSlot, 40, SlotActionType.SWAP, mc.player);
    }
}
