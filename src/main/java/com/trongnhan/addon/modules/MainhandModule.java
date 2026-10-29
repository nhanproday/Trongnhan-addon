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
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

public class MainhandModule extends Module {

    private enum Stage {
        IDLE, TAP, OPEN, OFFHAND, HOTBAR, CLOSE
    }

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INV_START = 9;
    private static final int MAIN_INV_END = 36;
    private static final int OFFHAND_CONTAINER_SLOT = 45;
    private static final int HOTBAR_CONTAINER_START = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> targetHotbarSlot = sgGeneral.add(
        new IntSetting.Builder()
            .name("hotbar-slot")
            .description("Slot hotbar (1-9) can refill totem")
            .defaultValue(1)
            .min(1)
            .max(9)
            .build()
    );

    private final Setting<Integer> stepDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("step-delay")
            .description("Delay giua cac buoc, don vi tick")
            .defaultValue(2)
            .min(1)
            .max(20)
            .build()
    );

    private final Setting<Boolean> silent = sgGeneral.add(
        new BoolSetting.Builder()
            .name("silent")
            .description("Bat: gui packet thang, khong hien inv. Tat: mo inv binh thuong")
            .defaultValue(true)
            .build()
    );

    private int delayTimer;
    private Stage stage = Stage.IDLE;
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Tu refill totem offhand + hotbar");
    }

    @Override
    public void onActivate() {
        delayTimer = 0;
        stage = Stage.IDLE;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
        }
        stage = Stage.IDLE;
        delayTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        switch (stage) {
            case IDLE    -> tickIdle(player, mc.currentScreen);
            case TAP     -> tickTap(player);
            case OPEN    -> tickOpen(player, mc.currentScreen);
            case OFFHAND -> tickOffhand(player, mc.currentScreen);
            case HOTBAR  -> tickHotbar();
            case CLOSE   -> tickClose(player);
        }
    }

    private void tickIdle(ClientPlayerEntity player, Screen screen) {
        if (screen != null) return;
        if (countAllTotems(player) <= 0) return;

        boolean offhandEmpty = !isTotem(player.getOffHandStack());
        boolean hotbarNeedsRefill = findHotbarTotem(player) < 0;
        if (!offhandEmpty && !hotbarNeedsRefill) return;

        simulatePhysicalTap(targetHotbarSlot.get() - 1);
        stage = Stage.TAP;
        delayTimer = stepDelay.get();
    }

    private void tickTap(ClientPlayerEntity player) {
        if (!hasSpareTotem(player)) {
            stage = Stage.CLOSE;
            return;
        }
        stage = Stage.OPEN;
        delayTimer = stepDelay.get();
    }

    private void tickOpen(ClientPlayerEntity player, Screen screen) {
        if (!silent.get()) {
            if (!(screen instanceof InventoryScreen)) {
                player.closeHandledScreen(); // Đảm bảo màn hình cũ đóng
                mc.setScreen(new InventoryScreen(player));
            }
        }
        stage = Stage.OFFHAND;
        delayTimer = stepDelay.get();
    }

    private void tickOffhand(ClientPlayerEntity player, Screen screen) {
        if (!silent.get() && !(screen instanceof InventoryScreen)) {
            stage = Stage.IDLE;
            return;
        }
        boolean offhandEmpty = !isTotem(player.getOffHandStack());
        boolean hotbarNeedsRefill = findHotbarTotem(player) < 0;
        replenish(player, offhandEmpty, hotbarNeedsRefill);
        stage = Stage.HOTBAR;
        delayTimer = stepDelay.get();
    }

    private void tickHotbar() {
        stage = Stage.CLOSE;
        delayTimer = stepDelay.get();
    }

    private void tickClose(ClientPlayerEntity player) {
        if (!silent.get()) {
            player.closeHandledScreen();
        }
        stage = Stage.IDLE;
        delayTimer = stepDelay.get();
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
            java.lang.reflect.Field xField = net.minecraft.client.gui.screen.ingame.HandledScreen.class.getDeclaredField("x");
            xField.setAccessible(true);
            int screenX = xField.getInt(inv);
            
            java.lang.reflect.Field yField = net.minecraft.client.gui.screen.ingame.HandledScreen.class.getDeclaredField("y");
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
        if (silent.get()) {
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
