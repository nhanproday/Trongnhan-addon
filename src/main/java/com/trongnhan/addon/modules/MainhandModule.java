package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class MainhandModule extends Module {

    public enum Stage { Idle, Step1_Tap, Step2_Open, Step3_Offhand, Step4_Hotbar }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> targetHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot").description("Slot hotbar (1-9) cần refill totem")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay (ticks) giữa các bước.")
        .defaultValue(2).min(0).sliderMax(20).build());

    public final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-mode").description("Bật: ẩn balo. Tắt: mở balo.")
        .defaultValue(true).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private int delayTimer = 0;
    private Stage stage = Stage.Idle;

    private ItemStack originalMainhand = ItemStack.EMPTY;
    private boolean isHoldingTotemMainhand = false;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Tự refill totem offhand + hotbar — 5 bước, bù totem khi bay.");
    }

    @Override
    public void onActivate() {
        stage = Stage.Idle;
        delayTimer = 0;
        originalMainhand = ItemStack.EMPTY;
        isHoldingTotemMainhand = false;
    }

    @Override
    public void onDeactivate() {
        if (mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
        originalMainhand = ItemStack.EMPTY;
        isHoldingTotemMainhand = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Nếu đang mở balo (do chính nó mở) thì đéo làm gì tiếp
        if (mc.currentScreen instanceof InventoryScreen && !silentMode.get()) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        boolean offhandHasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean isElytraFlying = mc.player.isGliding();
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        boolean mainhandIsTotem = mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);

        // 1. LOGIC KHI BAY: Mất totem tay trái -> Cầm totem tay phải
        if (isElytraFlying && !offhandHasTotem) {
            if (!isHoldingTotemMainhand) {
                if (!mainhandIsTotem) {
                    originalMainhand = mc.player.getMainHandStack().copy();
                    isHoldingTotemMainhand = true;
                }
            }

            if (!mainhandIsTotem) {
                for (int i = 0; i <= 8; i++) {
                    if (i == currentSlot) continue;
                    if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                        mc.player.getInventory().setSelectedSlot(i);
                        syncSelected();
                        delayTimer = delay.get();
                        return;
                    }
                }
            }
        } else if (offhandHasTotem && isHoldingTotemMainhand && isElytraFlying) {
            // TRẢ LẠI ĐỒ KHI TAY TRÁI ĐÃ AN TOÀN
            if (mainhandIsTotem && !originalMainhand.isEmpty()) {
                for (int i = 0; i <= 8; i++) {
                    if (i != currentSlot && ItemStack.areItemsEqual(mc.player.getInventory().getStack(i), originalMainhand)) {
                        mc.player.getInventory().setSelectedSlot(i);
                        syncSelected();
                        originalMainhand = ItemStack.EMPTY;
                        isHoldingTotemMainhand = false;
                        break;
                    }
                }
            } else {
                originalMainhand = ItemStack.EMPTY;
                isHoldingTotemMainhand = false;
            }
        } else if (!isElytraFlying) {
            originalMainhand = ItemStack.EMPTY;
            isHoldingTotemMainhand = false;
        }

        int totalTotems = countAllTotems();
        if (totalTotems == 0) {
            stage = Stage.Idle;
            return;
        }

        int targetIdx = targetHotbarSlot.get() - 1;
        boolean hotbarNeedsRefill = !mc.player.getInventory().getStack(targetIdx).isOf(Items.TOTEM_OF_UNDYING);
        boolean mainhandNeedsRefill = !mainhandIsTotem && isElytraFlying; 

        if (!offhandHasTotem || hotbarNeedsRefill || mainhandNeedsRefill) {
            if (stage == Stage.Idle) {
                stage = Stage.Step1_Tap;
            }
        } else {
            stage = Stage.Idle;
        }

        switch (stage) {
            case Step1_Tap -> {
                int hotbarTotem = findHotbarTotem();
                if (!offhandHasTotem && hotbarTotem != -1) {
                    executeKeybindSwap(hotbarTotem, 40);
                    delayTimer = delay.get();
                    stage = Stage.Idle;
                } else {
                    stage = Stage.Step2_Open;
                    delayTimer = delay.get();
                }
            }
            case Step2_Open -> {
                if (!silentMode.get()) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                stage = Stage.Step3_Offhand;
                delayTimer = delay.get();
            }
            case Step3_Offhand -> {
                if (!offhandHasTotem) {
                    int invTotem = findTotemInInventoryOnly();
                    if (invTotem != -1) {
                        if (silentMode.get()) {
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, invTotem, 40, SlotActionType.SWAP, mc.player);
                        } else {
                            simulateMouseOverSlot(invTotem);
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, invTotem, 40, SlotActionType.SWAP, mc.player);
                        }
                    }
                }
                stage = Stage.Step4_Hotbar;
                delayTimer = delay.get();
            }
            case Step4_Hotbar -> {
                if (hotbarNeedsRefill || (mainhandNeedsRefill && !offhandHasTotem)) {
                    int invTotem = findTotemInInventoryOnly();
                    if (invTotem != -1) {
                        if (silentMode.get()) {
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, invTotem, targetIdx, SlotActionType.SWAP, mc.player);
                        } else {
                            simulateMouseOverSlot(invTotem);
                            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, invTotem, targetIdx, SlotActionType.SWAP, mc.player);
                        }
                    }
                }
                // Đóng balo ngay lập tức nếu không dùng silent
                if (!silentMode.get() && mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
                stage = Stage.Idle;
                delayTimer = delay.get();
            }
        }
    }

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }

    private int countAllTotems() {
        int count = 0;
        for (int i = 0; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) count++;
        }
        return count;
    }

    private int findHotbarTotem() {
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i <= 8; i++) {
            if (i == currentSlot) continue;
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findTotemInInventoryOnly() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private void simulateMouseOverSlot(int slotId) {
        if (mc.currentScreen instanceof InventoryScreen invScreen) {
            try {
                Slot slot = invScreen.getScreenHandler().slots.get(slotId);
                Field xField = HandledScreen.class.getDeclaredField("x");
                xField.setAccessible(true);
                int screenX = xField.getInt(invScreen);
                Field yField = HandledScreen.class.getDeclaredField("y");
                yField.setAccessible(true);
                int screenY = yField.getInt(invScreen);

                double guiScale = mc.getWindow().getScaleFactor();
                double mouseX = (screenX + slot.x + 8) * guiScale;
                double mouseY = (screenY + slot.y + 8) * guiScale;
                GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), mouseX, mouseY);
            } catch (Exception ignored) {}
        }
    }

    private void executeKeybindSwap(int hotbarSlot, int offhandContainerSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, 36 + hotbarSlot, offhandContainerSlot, SlotActionType.SWAP, mc.player);
    }
}
