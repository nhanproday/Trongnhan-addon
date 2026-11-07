package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class MainhandModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMainhand = settings.createGroup("Mainhand");
    private final SettingGroup sgAlert = settings.createGroup("Alerts");

    public final Setting<Boolean> mainhandMode = sgMainhand.add(new BoolSetting.Builder()
        .name("mainhand-mode")
        .description("Tu dong keo Totem xuong Hotbar.")
        .defaultValue(true)
        .build());

    public final Setting<Integer> minSlot = sgMainhand.add(new IntSetting.Builder()
        .name("min-slot")
        .description("Slot hotbar nho nhat (1-9).")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9)
        .visible(mainhandMode::get).build());

    public final Setting<Integer> maxSlot = sgMainhand.add(new IntSetting.Builder()
        .name("max-slot")
        .description("Slot hotbar lon nhat (1-9).")
        .defaultValue(3).min(1).max(9).sliderMin(1).sliderMax(9)
        .visible(mainhandMode::get).build());

    public final Setting<Integer> actionDelay = sgMainhand.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay (ms) truoc khi keo Totem xuong Hotbar.")
        .defaultValue(40).min(0).sliderMax(500)
        .visible(mainhandMode::get).build());

    public final Setting<Boolean> antiGhost = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-ghost")
        .description("Chong ao ma totem.")
        .defaultValue(true).build());

    public final Setting<Boolean> lowAlert = sgAlert.add(new BoolSetting.Builder()
        .name("low-alert")
        .defaultValue(true).build());

    public final Setting<Integer> lowThreshold = sgAlert.add(new IntSetting.Builder()
        .name("low-threshold").defaultValue(5).min(1).sliderMax(15).visible(lowAlert::get).build());

    public final Setting<Boolean> alertSound = sgAlert.add(new BoolSetting.Builder()
        .name("alert-sound").defaultValue(true).visible(lowAlert::get).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private long lastActionTime = 0;
    private long lastGhostSync = 0;
    private int lastInventoryTotemCount = -1;
    private int currentTargetSlot = -1;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Keeps Totem in hotbar (Min-Max slots).");
    }

    @Override
    public void onActivate() {
        lastActionTime = System.currentTimeMillis();
        currentTargetSlot = Math.min(minSlot.get(), maxSlot.get()) - 1;
        lastInventoryTotemCount = -1;
    }

    @Override
    public void onDeactivate() {
        currentTargetSlot = -1;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        return String.valueOf(countInventoryTotemsOnly());
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (antiGhost.get() && mc.player != null && mc.getNetworkHandler() != null) {
            if (event.packet instanceof InventoryS2CPacket || event.packet instanceof ScreenHandlerSlotUpdateS2CPacket) {
                if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    long now = System.currentTimeMillis();
                    if (now - lastGhostSync > 1000) {
                        lastGhostSync = now;
                        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN
                        ));
                    }
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.player.isCreative() || mc.player.isSpectator()) return;

        int currentInvTotems = countInventoryTotemsOnly();
        if (lowAlert.get() && lastInventoryTotemCount != -1 && currentInvTotems < lastInventoryTotemCount) {
            if (currentInvTotems <= lowThreshold.get()) {
                ChatUtils.sendMsg(Text.literal("§fCANH BAO: Kho do chi con §e" + currentInvTotems + " §fTotem!"));
                if (alertSound.get()) mc.player.playSound(SoundEvents.ENTITY_IRON_GOLEM_HURT, 1.0F, 0.6F);
            }
        }
        lastInventoryTotemCount = currentInvTotems;

        if (!mainhandMode.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastActionTime < actionDelay.get()) return;

        int min = Math.min(minSlot.get(), maxSlot.get()) - 1;
        int max = Math.max(minSlot.get(), maxSlot.get()) - 1;

        if (currentTargetSlot < min || currentTargetSlot > max) currentTargetSlot = min;

        boolean slotHasTotem = mc.player.getInventory().getStack(currentTargetSlot).isOf(Items.TOTEM_OF_UNDYING);

        if (!slotHasTotem) {
            int invTotem = findTotemInInventoryOnly();
            if (invTotem != -1) {
                if (swapTotemDirectly(invTotem, currentTargetSlot)) {
                    lastActionTime = now;
                    if (currentTargetSlot < max) currentTargetSlot++;
                    else currentTargetSlot = min;
                }
            }
        } else {
            int selectedSlot = mc.player.getInventory().getSelectedSlot();
            if (!mc.player.getInventory().getStack(selectedSlot).isOf(Items.TOTEM_OF_UNDYING)) {
                boolean found = false;
                for (int i = min; i <= max; i++) {
                    if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                        mc.player.getInventory().setSelectedSlot(i);
                        syncSelected();
                        lastActionTime = now;
                        found = true;
                        break;
                    }
                }
                if (!found && mc.player.getInventory().getStack(min).isOf(Items.TOTEM_OF_UNDYING)) {
                    mc.player.getInventory().setSelectedSlot(min);
                    syncSelected();
                    lastActionTime = now;
                }
            }
        }
    }

    private boolean swapTotemDirectly(int inventoryIndex, int targetHotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory() || source.getIndex() != inventoryIndex || !source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        mc.interactionManager.clickSlot(handler.syncId, handlerSlot, targetHotbarSlot, SlotActionType.SWAP, mc.player);
        return true;
    }

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }

    private int findTotemInInventoryOnly() {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int countInventoryTotemsOnly() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 9; i < 36; ++i) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) count++;
        }
        return count;
    }

    private static int resolvePlayerHandlerSlot(ScreenHandler handler, int playerInventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventoryIndex) return i;
        }
        return -1;
    }
}
