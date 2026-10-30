package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Random;

public class AutoTotemModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAlert = settings.createGroup("Alerts");

    public final Setting<Integer> targetHotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot").description("Ô hotbar (1-9) ưu tiên chứa totem.")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health").description("Ngưỡng máu tự động cầm totem.")
        .defaultValue(16.0).min(0.0).max(36.0).sliderMin(0.0).sliderMax(36.0).build());

    public final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay (ticks) giữa các thao tác.")
        .defaultValue(2).min(0).sliderMax(20).build());

    public final Setting<Boolean> silentMode = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-mode").description("Bật: packet ngầm. Tắt: mở balo.")
        .defaultValue(true).build());

    public final Setting<Boolean> lowAlert = sgAlert.add(new BoolSetting.Builder()
        .name("low-alert").description("Cảnh báo khi hết totem trong balo.")
        .defaultValue(true).build());

    public final Setting<Boolean> alertSound = sgAlert.add(new BoolSetting.Builder()
        .name("alert-sound").description("Phát âm thanh cảnh báo.")
        .defaultValue(true).visible(lowAlert::get).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private int delayTimer = 0;
    private int lastOffhandCount = -1;
    private int popCounter = 0;
    private int randomTarget = 3;
    private final Random random = new Random();

    public AutoTotemModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem--", "Auto totem (Health, Hotbar, Balo).");
    }

    @Override
    public void onActivate() {
        delayTimer = 0;
        lastOffhandCount = -1;
        popCounter = 0;
        randomTarget = 2 + random.nextInt(3);
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.currentScreen instanceof InventoryScreen && !silentMode.get()) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        // 1. Logic máu thấp -> Tự cầm totem (Chỉ đổi slot)
        boolean needsTotem = mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get();
        if (needsTotem) {
            boolean holdingTotem = mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (!holdingTotem) {
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                        mc.player.getInventory().setSelectedSlot(i);
                        syncSelected();
                        delayTimer = actionDelay.get();
                        return;
                    }
                }
            }
        }

        // Đếm số totem offhand bị mất (pop)
        int currentOffhandCount = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) ? mc.player.getOffHandStack().getCount() : 0;
        if (lastOffhandCount != -1 && currentOffhandCount < lastOffhandCount) {
            popCounter += (lastOffhandCount - currentOffhandCount);
        }
        lastOffhandCount = currentOffhandCount;

        // 2. Logic Offhand trống -> Lấy totem TỪ BALO bù vào
        if (currentOffhandCount == 0) {
            int invTotem = findTotemInInventoryOnly();
            if (invTotem != -1) {
                if (!silentMode.get() && !(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                    delayTimer = actionDelay.get();
                    return;
                }
                int fromC = invTotem < 9 ? 36 + invTotem : invTotem;
                executePickupSwap(fromC, 45); // 45 là slot offhand
                delayTimer = actionDelay.get();
                return;
            }
        }

        // 3. Logic bù vào Slot Hotbar chỉ định nếu bị mất
        int targetIdx = targetHotbarSlot.get() - 1;
        boolean hotbarHasTotem = mc.player.getInventory().getStack(targetIdx).isOf(Items.TOTEM_OF_UNDYING);
        
        if (!hotbarHasTotem) {
            if (popCounter >= randomTarget) { // Đủ số lần random mới bù
                int invTotem = findTotemInInventoryOnly();
                if (invTotem != -1) {
                    if (!silentMode.get() && !(mc.currentScreen instanceof InventoryScreen)) {
                        mc.setScreen(new InventoryScreen(mc.player));
                        delayTimer = actionDelay.get();
                        return;
                    }
                    
                    int fromC = invTotem < 9 ? 36 + invTotem : invTotem;
                    int toC = 36 + targetIdx;
                    
                    executePickupSwap(fromC, toC);
                    
                    popCounter = 0;
                    randomTarget = 2 + random.nextInt(3);
                    
                    if (!silentMode.get() && mc.currentScreen instanceof InventoryScreen) {
                        mc.player.closeHandledScreen();
                    }
                    delayTimer = actionDelay.get();
                    return;
                } else {
                    if (lowAlert.get() && lastOffhandCount > 0) {
                        ChatUtils.sendMsg(Text.literal("§cHết sạch Totem trong balo!"));
                        if (alertSound.get()) mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
                    }
                }
            }
        }
    }

    private int findTotemInInventoryOnly() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private void executePickupSwap(int fromC, int toC) {
        if (mc.player == null || mc.interactionManager == null) return;
        short syncId = (short) mc.player.currentScreenHandler.syncId;
        
        if (silentMode.get()) {
            short rev = (short) mc.player.currentScreenHandler.getRevision();
            short shortFromC = (short) fromC;
            short shortToC = (short) toC;
            byte button = (byte) 0;
            
            mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                syncId, rev, shortFromC, button, SlotActionType.PICKUP,
                mc.player.currentScreenHandler.getCursorStack(), new net.minecraft.screen.slot.SlotActionType[0]
            ));
            mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                syncId, (short)(rev + 1), shortToC, button, SlotActionType.PICKUP,
                mc.player.currentScreenHandler.getCursorStack(), new net.minecraft.screen.slot.SlotActionType[0]
            ));
            mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                syncId, (short)(rev + 2), shortFromC, button, SlotActionType.PICKUP,
                mc.player.currentScreenHandler.getCursorStack(), new net.minecraft.screen.slot.SlotActionType[0]
            ));
        } else {
            mc.interactionManager.clickSlot(syncId, fromC, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, toC, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, fromC, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }
}
