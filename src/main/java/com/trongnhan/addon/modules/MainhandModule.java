package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class MainhandModule extends Module {

    private enum Stage {
        Idle, Step1_Tap, Step2_Open, Step3_Offhand, Step4_Hotbar, Step5_Close
    }

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INV_START = 9;
    private static final int MAIN_INV_END = 36;
    private static final int OFFHAND_CONTAINER_SLOT = 45;
    private static final int HOTBAR_CONTAINER_START = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> targetHotbarSlot = sgGeneral.add(
        new IntSetting.Builder()
            .name("hotbar-slot")
            .description("Slot hotbar (1-9) cần refill totem")
            .defaultValue(1)
            .min(1)
            .max(9)
            .build()
    );

    public final Setting<Integer> delay = sgGeneral.add(
        new IntSetting.Builder()
            .name("delay")
            .description("Delay (ticks) giữa các bước.")
            .defaultValue(2)
            .min(0)
            .sliderMax(20)
            .build()
    );

    public final Setting<Boolean> silentMode = sgGeneral.add(
        new BoolSetting.Builder()
            .name("silent-mode")
            .description("Bật: ẩn balo. Tắt: mở balo.")
            .defaultValue(true)
            .build()
    );

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

        switch (stage) {
            case Idle -> tickIdle(mc.player, mc.currentScreen);
            case Step1_Tap -> tickTap(mc.player);
            case Step2_Open -> tickOpen(mc.player, mc.currentScreen);
            case Step3_Offhand -> tickOffhand(mc.player, mc.currentScreen);
            case Step4_Hotbar -> tickHotbar();
            case Step5_Close -> tickClose(mc.player);
        }
    }

    private void tickIdle(ClientPlayerEntity player, Screen screen) {
        if (screen != null) return;
        if (countAllTotems(player) <= 0) return;

        boolean offhandEmpty = !isTotem(player.getOffHandStack());
        boolean hotbarNeedsRefill = findHotbarTotem(player) < 0;
        if (!offhandEmpty && !hotbarNeedsRefill) return;

        simulatePhysicalTap(targetHotbarSlot.get() - 1);
        stage = Stage.Step1_Tap;
        delayTimer = delay.get();
    }

    private void tickTap(ClientPlayerEntity player) {
        if (!hasSpareTotem(player)) {
            stage = Stage.Step5_Close;
            return;
        }
        stage = Stage.Step2_Open;
        delayTimer = delay.get();
    }

    private void tickOpen(ClientPlayerEntity player, Screen screen) {
        if (!silentMode.get()) {
            if (!(screen instanceof InventoryScreen)) {
                mc.setScreen(new InventoryScreen(player));
            }
        }
        stage = Stage.Step3_Offhand;
        delayTimer = delay.get();
    }

    private void tickOffhand(ClientPlayerEntity player, Screen screen) {
        if (!silentMode.get() && !(screen instanceof InventoryScreen)) {
            stage = Stage.Idle;
            return;
        }
        boolean offhandEmpty = !isTotem(player.getOffHandStack());
        boolean hotbarNeedsRefill = findHotbarTotem(player) < 0;
        replenish(player, offhandEmpty, hotbarNeedsRefill);
        stage = Stage.Step4_Hotbar;
        delayTimer = delay.get();
    }

    private void tickHotbar() {
        stage = Stage.Step5_Close;
        delayTimer = delay.get();
    }

    private void tickClose(ClientPlayerEntity player) {
        if (!silentMode.get()) {
            player.closeHandledScreen();
        }
        stage = Stage.Idle;
        delayTimer = delay.get();
    }

    private static boolean isTotem(ItemStack stack) {
        return stack != null && stack.isOf(Items.TOTEM_OF_UNDYING);
    }

    private int countAllTotems(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (isTotem(stack)) count += stack.getCount();
        }
        return count;
    }

    private int findHotbarTotem(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (isTotem(inv.getStack(i))) return i;
        }
        return -1;
    }

    private int findTotemInInventoryOnly(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = MAIN_INV_START; i < MAIN_INV_END; i++) {
            if (isTotem(inv.getStack(i))) return i;
        }
        return -1;
    }

    private boolean hasSpareTotem(ClientPlayerEntity player) {
        return findTotemInInventoryOnly(player) >= 0 || findHotbarTotem(player) >= 0;
    }

    private boolean hotbarSlotHasTotem(ClientPlayerEntity player, int hotbarIdx) {
        return isTotem(player.getInventory().getStack(hotbarIdx));
    }

    private void simulatePhysicalTap(int slot) {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.currentScreenHandler == null) return;
        int syncId = player.currentScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, slot, 40, SlotActionType.SWAP, player);
    }

    private void simulateMouseOverSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen inv)) return;
        Slot slot = inv.getScreenHandler().getSlot(slotId);
        if (slot == null) return;
        
        try {
            Field xField = HandledScreen.class.getDeclaredField("x");
            xField.setAccessible(true);
            int screenX = xField.getInt(inv);
            
            Field yField = HandledScreen.class.getDeclaredField("y");
            yField.setAccessible(true);
            int screenY = yField.getInt(inv);

            double scale = mc.getWindow().getScaleFactor();
            double mouseX = (screenX + slot.x) * scale;
            double mouseY = (screenY + slot.y) * scale;
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), mouseX, mouseY);
        } catch (Exception ignored) {}
    }

    private void executeKeybindSwap(int fromSlot, int toSlot) {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.currentScreenHandler == null) return;

        ScreenHandler handler;
        if (silentMode.get()) {
            handler = player.currentScreenHandler;
        } else {
            if (!(mc.currentScreen instanceof InventoryScreen inv)) return;
            handler = inv.getScreenHandler();
        }

        int syncId = handler.syncId;
        mc.interactionManager.clickSlot(syncId, fromSlot, 0, SlotActionType.PICKUP, player);
        mc.interactionManager.clickSlot(syncId, toSlot, 0, SlotActionType.PICKUP, player);
        mc.interactionManager.clickSlot(syncId, fromSlot, 0, SlotActionType.PICKUP, player);
    }

    private void replenish(ClientPlayerEntity player, boolean offhandEmpty, boolean hotbarNeedsRefill) {
        if (offhandEmpty) {
            int src = findTotemInInventoryOnly(player);
            if (src >= 0) executeKeybindSwap(src, OFFHAND_CONTAINER_SLOT);
        }
        if (hotbarNeedsRefill) {
            int targetIdx = targetHotbarSlot.get() - 1;
            int src = findTotemInInventoryOnly(player);
            if (src >= 0 && !hotbarSlotHasTotem(player, targetIdx)) {
                executeKeybindSwap(src, HOTBAR_CONTAINER_START + targetIdx);
            }
        }
    }
}
