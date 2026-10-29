package com.trongnhan.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

public final class InvUtil {
    public static final int HOTBAR_START = 0;
    public static final int HOTBAR_END = 8;
    public static final int INVENTORY_START = 9;
    public static final int INVENTORY_END = 35;

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private InvUtil() {}

    public static int find(Item item) {
        return find(item, HOTBAR_START, INVENTORY_END);
    }

    public static int find(Item item, int start, int end) {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = start; i <= end; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    public static void swap(int fromSlot, int toSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        int syncId = mc.player.currentScreenHandler.syncId;
        int fromContainer = toContainerSlot(fromSlot);
        int toContainer   = toContainerSlot(toSlot);
        if (fromContainer < 0 || toContainer < 0) return;

        mc.interactionManager.clickSlot(syncId, fromContainer, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, toContainer,   0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, fromContainer, 0, SlotActionType.PICKUP, mc.player);
    }

    private static int toContainerSlot(int invIndex) {
        if (invIndex < 0 || invIndex > 35) return -1;
        if (invIndex <= 8) return 36 + invIndex;
        return invIndex;
    }

    public static boolean isTotem(ItemStack stack) {
        return stack != null && stack.isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);
    }
}
