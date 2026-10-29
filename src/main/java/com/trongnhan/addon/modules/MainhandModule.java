package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import com.trongnhan.addon.utils.InvUtil;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayDeque;
import java.util.Iterator;

public class MainhandModule extends Module {

    public enum RefillMode { Immediate, AfterPops }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPops = settings.createGroup("Pops");
    private final SettingGroup sgGapple = settings.createGroup("Gapple");
    private final SettingGroup sgThreat = settings.createGroup("Threat");

    public final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot").description("Hotbar slot (1-9) to keep the totem in.")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay (ms) before refill.")
        .defaultValue(0).min(0).max(2000).sliderMin(0).sliderMax(2000).build());

    public final Setting<RefillMode> refillMode = sgGeneral.add(new EnumSetting.Builder<RefillMode>()
        .name("refill-mode").defaultValue(RefillMode.Immediate).build());

    public final Setting<Integer> popsBeforeRefill = sgPops.add(new IntSetting.Builder()
        .name("pops-before-refill").defaultValue(4).min(1).max(9).sliderMin(1).sliderMax(9)
        .visible(() -> refillMode.get() == RefillMode.AfterPops).build());

    public final Setting<Boolean> useGapple = sgGapple.add(new BoolSetting.Builder()
        .name("use-gapple").defaultValue(true).build());

    public final Setting<Boolean> lethalOverride = sgGapple.add(new BoolSetting.Builder()
        .name("lethal-override").defaultValue(false).visible(useGapple::get).build());

    public final Setting<Double> health = sgThreat.add(new DoubleSetting.Builder()
        .name("health").defaultValue(16).min(0).max(36).sliderMin(0).sliderMax(36).build());

    public final Setting<Boolean> elytraCheck = sgThreat.add(new BoolSetting.Builder()
        .name("elytra-check").defaultValue(true).build());

    public final Setting<Double> fallDistance = sgThreat.add(new DoubleSetting.Builder()
        .name("fall-distance").defaultValue(20).min(0).max(80).sliderMin(0).sliderMax(80).build());

    public final Setting<Boolean> antiMace = sgThreat.add(new BoolSetting.Builder()
        .name("anti-mace").defaultValue(false).build());

    public final Setting<Double> maceRange = sgThreat.add(new DoubleSetting.Builder()
        .name("mace-range").defaultValue(12).min(0).max(24).sliderMin(0).sliderMax(24)
        .visible(antiMace::get).build());

    private static final int POP_WINDOW_UPDATES = 8;
    private static final int MAX_POP_CANDIDATES = 32;

    private long pendingRefillTime = 0L;
    private final ArrayDeque<PopCandidate> popCandidates = new ArrayDeque<>();
    private final boolean[] observedTotems = new boolean[9];
    private int updateSequence;
    private int confirmedPopDebt;
    private int refillSource = -1;
    private int refillTarget = -1;
    private int refillAttemptUpdate;

    private RefillMode observedMode;
    private Object observedPlayer;
    private Object observedWorld;

    private boolean eatingHotbar = false;
    private int previousHotbarSlot = -1;
    private boolean eatingInventory = false;
    private int swappedInvSlot = -1;
    private int swappedHotbarSlot = -1;

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final class PopCandidate {
        final int slot; final int createdUpdate;
        PopCandidate(int s, int u) { slot = s; createdUpdate = u; }
    }

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand",
            "Auto totem + gapple mainhand manager.");
    }

    @Override
    public void onActivate() {
        pendingRefillTime = 0L;
        resetAfterPops("activate");
        observedMode = refillMode.get();
    }

    @Override
    public void onDeactivate() {
        if (eatingHotbar && mc.player != null && previousHotbarSlot != -1) {
            mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
            syncSelected();
        }
        if (eatingInventory && mc.player != null && swappedInvSlot != -1 && swappedHotbarSlot != -1) {
            InvUtil.swap(swappedInvSlot, swappedHotbarSlot);
        }
        eatingHotbar = false; previousHotbarSlot = -1;
        eatingInventory = false; swappedInvSlot = -1; swappedHotbarSlot = -1;
        pendingRefillTime = 0L;
        resetAfterPops("deactivate");
        observedMode = null; observedPlayer = null; observedWorld = null;
    }

    private void syncSelected() {
        if (mc.interactionManager != null) mc.interactionManager.syncSelectedSlot();
    }

    private void resetAfterPops(String reason) {
        if (!popCandidates.isEmpty() || confirmedPopDebt != 0 || pendingRefillTime != 0L || refillSource != -1) {
            TrongNhanAddon.LOG.info("[Mainhand] RESET reason={}", reason);
        }
        popCandidates.clear();
        confirmedPopDebt = 0;
        pendingRefillTime = 0L;
        refillSource = -1; refillTarget = -1;
        updateSequence = 0;
        for (int i = 0; i < observedTotems.length; i++) observedTotems[i] = false;
    }

    private void observeModeAndSession() {
        RefillMode mode = refillMode.get();
        if (observedMode == null || observedMode != mode) {
            resetAfterPops("mode_change"); observedMode = mode;
        }
        if (mc.player == null || mc.world == null || mc.player.isDead()) {
            resetAfterPops("invalid_session");
            observedPlayer = mc.player; observedWorld = mc.world; return;
        }
        if (observedPlayer != mc.player || observedWorld != mc.world) {
            resetAfterPops("session_change");
            observedPlayer = mc.player; observedWorld = mc.world;
        }
    }

    private boolean afterPops() { return refillMode.get() == RefillMode.AfterPops; }

    @EventHandler
    private void onTick(TickEvent.Post e) {
        observeModeAndSession();
        if (afterPops() && mc.player != null && mc.world != null && !mc.player.isDead()) observeAfterPops();
        updateMainhand();
    }

    private void observeAfterPops() {
        updateSequence++;
        boolean[] current = new boolean[9];
        for (int i = 0; i < current.length; i++) current[i] = InvUtil.isTotem(mc.player.getInventory().getStack(i));
        Iterator<PopCandidate> it = popCandidates.iterator();
        while (it.hasNext()) {
            PopCandidate c = it.next();
            if (updateSequence - c.createdUpdate > POP_WINDOW_UPDATES) {
                it.remove();
                TrongNhanAddon.LOG.info("[Mainhand] POP_REJECTED reason=timeout slot={}", c.slot + 1);
            } else if (observedTotems[c.slot] && !current[c.slot]) {
                it.remove(); confirmedPopDebt++;
                TrongNhanAddon.LOG.info("[Mainhand] POP_CONFIRMED slot={} debt={}", c.slot + 1, confirmedPopDebt);
                observedTotems[c.slot] = false;
            }
        }
        System.arraycopy(current, 0, observedTotems, 0, current.length);
    }

    public void notifyPop() {
        if (!isActive() || afterPops()) return;
        int target = slot.get() - 1;
        if (delay.get() <= 0) refill(target);
        else pendingRefillTime = System.currentTimeMillis() + delay.get();
    }

    private void refill(int targetHotbarSlot) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.getInventory().getStack(targetHotbarSlot).isOf(Items.TOTEM_OF_UNDYING)) return;
        int totemSlot = findRefillTotem(targetHotbarSlot);
        if (totemSlot != -1) InvUtil.swap(totemSlot, targetHotbarSlot);
    }

    private int findRefillTotem(int target) {
        int inv = InvUtil.find(Items.TOTEM_OF_UNDYING, InvUtil.INVENTORY_START, InvUtil.INVENTORY_END);
        if (inv != -1) return inv;
        for (int i = InvUtil.HOTBAR_START; i <= InvUtil.HOTBAR_END; i++) {
            if (i == target) continue;
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findGappleHotbar() {
        int egap = InvUtil.find(Items.ENCHANTED_GOLDEN_APPLE, InvUtil.HOTBAR_START, InvUtil.HOTBAR_END);
        if (egap != -1) return egap;
        return InvUtil.find(Items.GOLDEN_APPLE, InvUtil.HOTBAR_START, InvUtil.HOTBAR_END);
    }

    private int findGappleInventory() {
        int egap = InvUtil.find(Items.ENCHANTED_GOLDEN_APPLE, InvUtil.INVENTORY_START, InvUtil.INVENTORY_END);
        if (egap != -1) return egap;
        return InvUtil.find(Items.GOLDEN_APPLE, InvUtil.INVENTORY_START, InvUtil.INVENTORY_END);
    }

    private void updateAfterPopsRefill(int targetSlot, long now) {
        int threshold = popsBeforeRefill.get();
        if (refillSource != -1) {
            boolean targetHas = mc.player.getInventory().getStack(refillTarget).isOf(Items.TOTEM_OF_UNDYING);
            boolean sourceLost = !mc.player.getInventory().getStack(refillSource).isOf(Items.TOTEM_OF_UNDYING);
            if (targetHas && sourceLost) {
                confirmedPopDebt = Math.max(0, confirmedPopDebt - threshold);
                TrongNhanAddon.LOG.info("[Mainhand] REFILL_SUCCESS debt={}", confirmedPopDebt);
                refillSource = -1; refillTarget = -1; pendingRefillTime = 0L; return;
            }
            if (updateSequence - refillAttemptUpdate <= POP_WINDOW_UPDATES) return;
            refillSource = -1; refillTarget = -1; pendingRefillTime = 0L; return;
        }
        if (confirmedPopDebt < threshold) { pendingRefillTime = 0L; return; }
        if (mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING)) { pendingRefillTime = 0L; return; }
        if (pendingRefillTime == 0L) pendingRefillTime = now + Math.max(1L, delay.get());
        if (now < pendingRefillTime) return;
        int source = InvUtil.find(Items.TOTEM_OF_UNDYING, InvUtil.INVENTORY_START, InvUtil.INVENTORY_END);
        if (source == -1) return;
        refillSource = source; refillTarget = targetSlot; refillAttemptUpdate = updateSequence;
        InvUtil.swap(source, targetSlot);
    }

    private void updateMainhand() {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;

        boolean isUsing = mc.options.useKey.isPressed() || mc.player.isUsingItem();

        if (eatingHotbar) {
            if (!isUsing) {
                if (previousHotbarSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
                    syncSelected();
                }
                eatingHotbar = false; previousHotbarSlot = -1;
            }
            return;
        }
        if (eatingInventory) {
            if (!isUsing) {
                if (swappedInvSlot != -1 && swappedHotbarSlot != -1) InvUtil.swap(swappedInvSlot, swappedHotbarSlot);
                eatingInventory = false; swappedInvSlot = -1; swappedHotbarSlot = -1;
            }
            return;
        }

        boolean canGapple = !needsTotem() || (lethalOverride.get() && !hasEnemyInVisualRange());

        if (useGapple.get() && mc.options.useKey.isPressed()
                && mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) && canGapple) {
            int hb = findGappleHotbar();
            if (hb != -1) {
                previousHotbarSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(hb); syncSelected();
                eatingHotbar = true; return;
            }
            int inv = findGappleInventory();
            if (inv != -1) {
                swappedInvSlot = inv;
                swappedHotbarSlot = mc.player.getInventory().getSelectedSlot();
                InvUtil.swap(inv, swappedHotbarSlot);
                eatingInventory = true; return;
            }
        }

        int targetSlot = slot.get() - 1;
        long now = System.currentTimeMillis();
        if (afterPops()) updateAfterPopsRefill(targetSlot, now);
        else if (pendingRefillTime > 0L) {
            if (now >= pendingRefillTime) { pendingRefillTime = 0L; refill(targetSlot); }
        } else {
            boolean need = !mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING);
            if (need) {
                if (delay.get() <= 0) refill(targetSlot);
                else pendingRefillTime = now + delay.get();
            }
        }

        if (needsTotem()) {
            int selected = mc.player.getInventory().getSelectedSlot();
            boolean slotHas = mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING);
            if (selected != targetSlot || !slotHas) {
                if (slotHas) {
                    mc.player.getInventory().setSelectedSlot(targetSlot); syncSelected();
                } else {
                    int any = InvUtil.find(Items.TOTEM_OF_UNDYING, InvUtil.HOTBAR_START, InvUtil.HOTBAR_END);
                    if (any != -1 && selected != any) {
                        mc.player.getInventory().setSelectedSlot(any); syncSelected();
                    }
                }
            }
        }
    }

    public boolean shouldLockSlot() {
        if (!isActive() || mc.player == null) return false;
        if (eatingHotbar || eatingInventory) return false;
        if (InvUtil.find(Items.TOTEM_OF_UNDYING) == -1) return false;
        return needsTotem();
    }

    private boolean needsTotem() {
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get()) return true;
        if (mc.player.fallDistance > fallDistance.get()) return true;
        if (elytraCheck.get() && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return true;
        if (antiMace.get() && mc.world != null) {
            double r2 = MathHelper.square(maceRange.get().floatValue());
            return mc.world.getPlayers().stream().anyMatch(p -> {
                if (p == mc.player || p.isDead()) return false;
                if (p.squaredDistanceTo(mc.player) > r2) return false;
                if (p.fallDistance < 1.5) return false;
                return p.getMainHandStack().isOf(Items.MACE);
            });
        }
        return false;
    }

    private boolean hasEnemyInVisualRange() {
        if (mc.world == null || mc.player == null) return false;
        return mc.world.getPlayers().stream().anyMatch(p -> p != mc.player && !p.isDead());
    }
}
