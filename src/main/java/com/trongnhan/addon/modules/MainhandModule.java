package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayDeque;

public class MainhandModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("Slot").description("The hotbar slot (1-9) to keep a totem in.")
        .defaultValue(1).min(1).max(9).build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("Delay").description("Delay (ms) before refilling a totem to hotbar.")
        .defaultValue(0).min(0).max(2000).build());

    public final Setting<String> refillMode = sgGeneral.add(new EnumSetting.Builder<String>()
        .name("RefillMode").description("When to refill the configured hotbar slot.")
        .defaultValue("Immediate").values("Immediate", "After Pops").build());

    public final Setting<Integer> popsBeforeRefill = sgGeneral.add(new IntSetting.Builder()
        .name("PopsBeforeRefill").description("Confirmed main-hand Totem pops per inventory refill.")
        .defaultValue(4).min(1).max(9).visible(() -> refillMode.get().equals("After Pops")).build());

    public final Setting<Boolean> useGapple = sgGeneral.add(new BoolSetting.Builder()
        .name("UseGapple").description("Switches to golden apple.")
        .defaultValue(true).build());

    public final Setting<Boolean> lethalOverride = sgGeneral.add(new BoolSetting.Builder()
        .name("LethalOverride").description("Overrides necessity for a totem.")
        .defaultValue(false).visible(useGapple::get).build());

    public final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("Health").description("The health at which a totem will be prioritized.")
        .defaultValue(16).min(0).max(36).build());

    public final Setting<Boolean> elytraCheck = sgGeneral.add(new BoolSetting.Builder()
        .name("ElytraCheck").defaultValue(true).build());

    public final Setting<Double> fallDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("FallDistance").defaultValue(20).min(0).max(80).build());

    public final Setting<Boolean> antiMace = sgGeneral.add(new BoolSetting.Builder()
        .name("AntiMace").defaultValue(false).build());

    public final Setting<Double> maceRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("MaceRange").defaultValue(12).min(0).max(24).visible(antiMace::get).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private long pendingRefillTime = 0L;
    private static final int POP_WINDOW_UPDATES = 8;
    private final ArrayDeque<PopCandidate> popCandidates = new ArrayDeque<>();
    private final boolean[] observedTotems = new boolean[9];
    private int updateSequence;
    private int confirmedPopDebt;
    private int refillSource = -1;
    private int refillTarget = -1;
    private int refillAttemptUpdate;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Automatically manages totems and golden apples in your mainhand.");
    }

    @Override
    public void onActivate() {
        pendingRefillTime = 0L;
        resetAfterPops();
    }

    private boolean afterPops() {
        return refillMode.get().equals("After Pops");
    }

    private void resetAfterPops() {
        popCandidates.clear();
        confirmedPopDebt = 0;
        pendingRefillTime = 0L;
        refillSource = -1;
        refillTarget = -1;
        updateSequence = 0;
        for (int i = 0; i < observedTotems.length; i++) observedTotems[i] = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (afterPops()) {
            observeAfterPops();
        }
        updateMainhand();
    }

    private void observeAfterPops() {
        updateSequence++;
        boolean[] current = new boolean[9];
        for (int i = 0; i < current.length; i++) {
            current[i] = mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING);
        }
        popCandidates.removeIf(c -> updateSequence - c.createdUpdate > POP_WINDOW_UPDATES);
        
        for (PopCandidate c : popCandidates) {
            if (observedTotems[c.slot] && !current[c.slot]) {
                confirmedPopDebt++;
                observedTotems[c.slot] = false;
            }
        }
        System.arraycopy(current, 0, observedTotems, 0, current.length);
    }

    private void updateMainhand() {
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return;

        int targetSlot = slot.get() - 1;
        long now = System.currentTimeMillis();
        
        if (afterPops()) {
            if (confirmedPopDebt >= popsBeforeRefill.get()) {
                if (pendingRefillTime == 0L) pendingRefillTime = now + delay.get();
                if (now >= pendingRefillTime) {
                    int src = findTotem();
                    if (src != -1) {
                        int containerSrc = src < 9 ? 36 + src : src;
                        int containerTarget = 36 + targetSlot;
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSrc, 0, SlotActionType.PICKUP, mc.player);
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerTarget, 0, SlotActionType.PICKUP, mc.player);
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSrc, 0, SlotActionType.PICKUP, mc.player);
                        confirmedPopDebt -= popsBeforeRefill.get();
                        pendingRefillTime = 0L;
                    }
                }
            }
        } else {
            if (!mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING)) {
                if (pendingRefillTime == 0L) pendingRefillTime = now + delay.get();
                if (now >= pendingRefillTime) {
                    int src = findTotem();
                    if (src != -1) {
                        int containerSrc = src < 9 ? 36 + src : src;
                        int containerTarget = 36 + targetSlot;
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSrc, 0, SlotActionType.PICKUP, mc.player);
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerTarget, 0, SlotActionType.PICKUP, mc.player);
                        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSrc, 0, SlotActionType.PICKUP, mc.player);
                        pendingRefillTime = 0L;
                    }
                }
            }
        }
    }

    private int findTotem() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private static final class PopCandidate {
        final int slot; final int createdUpdate;
        PopCandidate(int s, int u) { slot = s; createdUpdate = u; }
    }
}
