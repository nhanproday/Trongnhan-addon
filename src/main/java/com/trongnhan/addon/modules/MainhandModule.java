package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

public class MainhandModule extends Module {

    public enum Stage { Idle, Step1_Tap, Step2_Open, Step3_Offhand, Step4_Hotbar, Step5_Close }

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

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Tự refill totem offhand + hotbar — 5 bước, dừng an toàn khi hết totem");
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

        int totalTotems = countAllTotems();
        if (totalTotems == 0) {
            stage = Stage.Idle;
            return;
        }

        boolean offhandEmpty = !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        int targetIdx = targetHotbarSlot.get() - 1;
        boolean hotbarNeedsRefill = !mc.player.getInventory().getStack(targetIdx).isOf(Items.TOTEM_OF_UNDYING);

        if (!offhandEmpty && !hotbarNeedsRefill) {
            stage = Stage.Idle;
            return;
        }

        if (stage == Stage.Idle) {
            stage = Stage.Step1_Tap;
        }

        switch (stage) {
            case Step1_Tap -> {
                int hotbarTotem = findHotbarTotem();
                if (offhandEmpty && hotbarTotem != -1) {
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
                if (offhandEmpty) {
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
                if (hotbarNeedsRefill) {
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
                stage = Stage.Step5_Close;
                delayTimer = delay.get();
            }
            case Step5_Close -> {
                if (!silentMode.get() && mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
                stage = Stage.Idle;
                delayTimer = delay.get();
            }
        }
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
            Slot slot = invScreen.getScreenHandler().slots.get(slotId);
            double guiScale = mc.getWindow().getScaleFactor();
            double mouseX = (invScreen.x + slot.x + 8) * guiScale;
            double mouseY = (invScreen.y + slot.y + 8) * guiScale;
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), mouseX, mouseY);
        }
    }

    private void executeKeybindSwap(int hotbarSlot, int offhandContainerSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, 36 + hotbarSlot, offhandContainerSlot, SlotActionType.SWAP, mc.player);
    }
}
