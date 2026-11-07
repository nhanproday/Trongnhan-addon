package com.trongnhan.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class AntiGhostHelper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static long lastGhostSync = 0;

    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(new AntiGhostHelper());
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        
        if (event.packet instanceof InventoryS2CPacket || event.packet instanceof ScreenHandlerSlotUpdateS2CPacket) {
            if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                long now = System.currentTimeMillis();
                // Giới hạn 1 giây 1 lần để tránh bị kick vì spam packet
                if (now - lastGhostSync > 1000) {
                    lastGhostSync = now;
                    mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ORIGIN,
                        Direction.DOWN
                    ));
                }
            }
        }
    }
}
