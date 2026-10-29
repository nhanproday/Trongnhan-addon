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
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class MainhandModule extends Module {

    private enum Stage {
        IDLE, OPEN, SWAP_OFFHAND, SWAP_HOTBAR, CLOSE
    }

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INV_START = 9;
    private static final int MAIN_INV_END = 36;
    private static final int OFFHAND_CONTAINER_SLOT = 45;
    private static final int HOTBAR_CONTAINER_START = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> targetHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Slot hotbar (1-9) can refill totem")
        .defaultValue(1).min(1).max(9).build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay (ticks) giua cac buoc")
        .defaultValue(2).min(1).max(20).build());

    public final Setting<Boolean> silent = sgGeneral.add(new BoolSetting.Builder()
        .name("silent")
        .description("Bat: packet ngam. Tat: mo balo")
        .defaultValue(true).build());

    private int delayTimer = 0;
    private Stage stage = Stage.IDLE;
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private ItemStack originalMainhand = ItemStack.EMPTY;
    private boolean isHoldingTotemMainhand = false;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Auto totem + Elytra save.");
    }

    @Override
    public void onActivate() {
        delayTimer = 0;
        stage = Stage.IDLE;
        originalMainhand = ItemStack.EMPTY;
        isHoldingTotemMainhand = false;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
        }
        stage = Stage.IDLE;
        delayTimer = 0;
        originalMainhand = ItemStack.EMPTY;
        isHoldingTotemMainhand = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        boolean offhandHasTotem = isTotem(player.getOffHandStack());
        boolean isElytraFlying = player.isGliding() || player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        int currentSlot = player.getInventory().getSelectedSlot();
        boolean mainhandIsTotem = isTotem(player.getMainHandStack());

        // LOGIC ELYTRA: Giữ mạng bằng mainhand
        if (isElytraFlying && !offhandHasTotem) {
            if (!isHoldingTotemMainhand && !mainhandIsTotem) {
                originalMainhand = player.getMainHandStack().copy();
                isHoldingTotemMainhand = true;
            }
            if (!mainhandIsTotem) {
                for (int i = 0; i < HOTBAR_SIZE; i++) {
                    if (i == currentSlot) continue;
                    if (isTotem(player.getInventory().getStack(i))) {
                        player.getInventory().setSelectedSlot(i);
                        syncSelected();
                        delayTimer = delay.get();
                        return; // Nghỉ 1 nhịp để tránh spam
                    }
                }
            }
        } else if (offhandHasTotem && isHoldingTotemMainhand && isElytraFlying) {
            if (mainhandIsTotem && !originalMainhand.isEmpty()) {
                for (int i = 0; i < HOTBAR_SIZE; i++) {
                    if (i != currentSlot && ItemStack.areItemsEqual(player.getInventory().getStack(i), originalMainhand)) {
                        player.getInventory().setSelectedSlot(i);
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

        // STATE MACHINE
        switch (stage) {
            case IDLE -> {
                if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen && !silent.get())) return;
                
                boolean needOffhand = !offhandHasTotem;
                boolean needHotbar = !hotbarSlotHasTotem(player, targetHotbarSlot.get() - 1);
                
                if (needOffhand || needHotbar) {
                    if (findTotemInInventoryOnly(player) != -1) {
                        stage = Stage.OPEN;
                    }
                }
            }
            case OPEN -> {
                if (!silent.get()) {
                    if (!(mc.currentScreen instanceof InventoryScreen)) {
                        mc.setScreen(new InventoryScreen(player));
                        delayTimer = delay.get();
                    } else {
                        stage = Stage.SWAP_OFFHAND;
                    }
                } else {
                    stage = Stage.SWAP_OFFHAND;
                }
            }
            case SWAP_OFFHAND -> {
                if (!offhandHasTotem) {
                    int src = findTotemInInventoryOnly(player);
                    if (src != -1) {
                        doSwap(src, OFFHAND_CONTAINER_SLOT);
                    }
                }
                stage = Stage.SWAP_HOTBAR;
                delayTimer = delay.get();
            }
            case SWAP_HOTBAR -> {
                boolean needHotbar = !hotbarSlotHasTotem(player, targetHotbarSlot.get() - 1);
                if (needHotbar) {
                    int src = findTotemInInventoryOnly(player);
                    if (src != -1) {
                        doSwap(src, HOTBAR_CONTAINER_START + targetHotbarSlot.get() - 1);
                    }
                }
                stage = Stage.CLOSE;
                delayTimer = delay.get();
            }
            case CLOSE -> {
                if (!silent.get() && mc.currentScreen instanceof InventoryScreen) {
                    player.closeHandledScreen();
                }
                stage = Stage.IDLE;
                delayTimer = delay.get();
            }
        }
    }

    private void doSwap(int fromInvSlot, int toContainerSlot) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        int syncId = player.currentScreenHandler.syncId;
        int fromContainer = fromInvSlot < 9 ? 36 + fromInvSlot : fromInvSlot;

        mc.interactionManager.clickSlot(syncId, fromContainer, 0, SlotActionType.PICKUP, player);
        mc.interactionManager.clickSlot(syncId, toContainerSlot, 0, SlotActionType.PICKUP, player);
        mc.interactionManager.clickSlot(syncId, fromContainer, 0, SlotActionType.PICKUP, player);
    }

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }

    private static boolean isTotem(ItemStack stack) {
        return stack != null && stack.isOf(Items.TOTEM_OF_UNDYING);
    }

    private int findTotemInInventoryOnly(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = MAIN_INV_START; i < MAIN_INV_END; i++) {
            if (isTotem(inv.getStack(i))) return i;
        }
        return -1;
    }

    private boolean hotbarSlotHasTotem(ClientPlayerEntity player, int hotbarIdx) {
        return isTotem(player.getInventory().getStack(hotbarIdx));
    }
}
