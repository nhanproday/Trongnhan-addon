package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.Iterator;

public class AutoTotemModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> slotStart = sgGeneral.add(new IntSetting.Builder()
        .name("slot-start").description("Hotbar slot start (1-9) to keep totem.")
        .defaultValue(3).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Integer> slotEnd = sgGeneral.add(new IntSetting.Builder()
        .name("slot-end").description("Hotbar slot end (1-9) to keep totem.")
        .defaultValue(5).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Delay (ms) before refilling a totem.")
        .defaultValue(350).min(0).max(2000).sliderMax(2000).build());

    public enum RefillMode { Immediate, AfterPops }

    public final Setting<RefillMode> refillMode = sgGeneral.add(new EnumSetting.Builder<RefillMode>()
        .name("refill-mode").description("When to refill.")
        .defaultValue(RefillMode.AfterPops).build());

    public final Setting<Integer> popsBeforeRefill = sgGeneral.add(new IntSetting.Builder()
        .name("pops-before-refill").description("Confirmed pops per inventory refill.")
        .defaultValue(7).min(1).max(9).visible(() -> refillMode.get() == RefillMode.AfterPops).build());

    public final Setting<Boolean> useGapple = sgGeneral.add(new BoolSetting.Builder()
        .name("use-gapple").description("Switches to golden apple.")
        .defaultValue(true).build());

    public final Setting<Boolean> lethalOverride = sgGeneral.add(new BoolSetting.Builder()
        .name("lethal-override").description("Allows eating gapple regardless of health if no enemies near.")
        .defaultValue(false).visible(useGapple::get).build());

    public final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health").description("The health at which a totem will be prioritized.")
        .defaultValue(36.0).min(0.0).max(36.0).sliderMax(36.0).build());

    public final Setting<Double> fallDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-distance").description("The fall distance at which totem is prioritized.")
        .defaultValue(21.28).min(0.0).max(80.0).sliderMax(80.0).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private long pendingRefillTime = 0L;
    private static final int POP_WINDOW_UPDATES = 8;
    private static final int MAX_POP_CANDIDATES = 32;
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

    public AutoTotemModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem", "Automatically manages totems in hotbar (Anti-Cheat Bypass).");
    }

    @Override
    public void onActivate() {
        pendingRefillTime = 0L;
        resetAfterPops("enable");
        observedMode = refillMode.get();
    }

    private static final class PopCandidate {
        private final int slot;
        private final int createdUpdate;
        private PopCandidate(int slot, int createdUpdate) {
            this.slot = slot;
            this.createdUpdate = createdUpdate;
        }
    }

    private boolean afterPops() {
        return refillMode.get() == RefillMode.AfterPops;
    }

    private void resetAfterPops(String reason) {
        popCandidates.clear();
        confirmedPopDebt = 0;
        pendingRefillTime = 0L;
        refillSource = -1;
        refillTarget = -1;
        updateSequence = 0;
        for (int i = 0; i < observedTotems.length; i++) observedTotems[i] = false;
    }

    private void observeModeAndSession() {
        RefillMode mode = refillMode.get();
        if (observedMode == null || observedMode != mode) {
            resetAfterPops("mode_change");
            observedMode = mode;
        }
        if (mc.player == null || mc.world == null || mc.player.isDead()) {
            resetAfterPops("invalid_session");
            observedPlayer = mc.player;
            observedWorld = mc.world;
            return;
        }
        if (observedPlayer != mc.player || observedWorld != mc.world) {
            resetAfterPops("session_change");
            observedPlayer = mc.player;
            observedWorld = mc.world;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        observeModeAndSession();

        if (afterPops()) {
            observeAfterPops();
        }

        updateMainhand();
    }

    private int getEmptyTargetSlot() {
        int start = slotStart.get() - 1;
        int end = slotEnd.get() - 1;
        
        for (int i = start; i <= end; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        for (int i = start; i <= end; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty() || !mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i; 
            }
        }
        return start;
    }

    private void refill(int targetHotbarSlot) {
        if (mc.player == null || mc.world == null) return;
        
        Screen currentScreen = mc.currentScreen;
        if (currentScreen instanceof HandledScreen && !(currentScreen instanceof InventoryScreen) && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;

        if (mc.player.getInventory().getStack(targetHotbarSlot).isOf(Items.TOTEM_OF_UNDYING)) return;
        
        int totemSlot = findRefillTotem(targetHotbarSlot);
        if (totemSlot != -1) {
            // DÙNG CLICKSWAP THAY VÌ INVUTILS ĐỂ BYPASS ANTICHEAT
            clickSwap(totemSlot, targetHotbarSlot);
        }
    }

    private void observeAfterPops() {
        updateSequence++;
        boolean[] current = new boolean[9];
        for (int i = 0; i < current.length; i++) {
            current[i] = mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING);
        }
        Iterator<PopCandidate> iterator = popCandidates.iterator();
        while (iterator.hasNext()) {
            PopCandidate candidate = iterator.next();
            if (updateSequence - candidate.createdUpdate > POP_WINDOW_UPDATES) {
                iterator.remove();
            } else if (observedTotems[candidate.slot] && !current[candidate.slot]) {
                iterator.remove();
                confirmedPopDebt++;
                observedTotems[candidate.slot] = false;
            }
        }
        System.arraycopy(current, 0, observedTotems, 0, current.length);
    }

    private void updateAfterPopsRefill(int targetSlot, long now) {
        int threshold = popsBeforeRefill.get();
        if (refillSource != -1) {
            boolean targetHasTotem = mc.player.getInventory().getStack(refillTarget).isOf(Items.TOTEM_OF_UNDYING);
            boolean sourceLostTotem = !mc.player.getInventory().getStack(refillSource).isOf(Items.TOTEM_OF_UNDYING);
            if (targetHasTotem && sourceLostTotem) {
                confirmedPopDebt = Math.max(0, confirmedPopDebt - threshold);
                refillSource = -1;
                refillTarget = -1;
                pendingRefillTime = 0L;
                return;
            }
            if (updateSequence - refillAttemptUpdate <= POP_WINDOW_UPDATES) return;
            refillSource = -1;
            refillTarget = -1;
            pendingRefillTime = 0L;
            return;
        }
        if (confirmedPopDebt < threshold) {
            pendingRefillTime = 0L;
            return;
        }
        if (mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING)) {
            pendingRefillTime = 0L;
            return;
        }
        if (pendingRefillTime == 0L) {
            pendingRefillTime = now + Math.max(1L, delay.get());
        }
        if (now < pendingRefillTime) return;
        int sourceSlot = findTotemInInventoryOnly();
        if (sourceSlot == -1) return;
        
        refillSource = sourceSlot;
        refillTarget = targetSlot;
        refillAttemptUpdate = updateSequence;
        clickSwap(sourceSlot, targetSlot);
    }

    private void updateMainhand() {
        if (mc.player == null || mc.world == null) return;
        
        Screen currentScreen = mc.currentScreen;
        boolean isContainerScreen = currentScreen instanceof HandledScreen && !(currentScreen instanceof InventoryScreen);
        if (isContainerScreen && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;

        boolean isUsing = mc.options.useKey.isPressed() || mc.player.isUsingItem();
        if (eatingHotbar) {
            if (!isUsing) {
                if (previousHotbarSlot != -1) {
                    mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
                    syncSelected();
                }
                eatingHotbar = false;
                previousHotbarSlot = -1;
            }
            return;
        }
        if (eatingInventory) {
            if (!isUsing) {
                if (swappedInvSlot != -1 && swappedHotbarSlot != -1) {
                    clickSwap(swappedInvSlot, swappedHotbarSlot);
                }
                eatingInventory = false;
                swappedInvSlot = -1;
                swappedHotbarSlot = -1;
            }
            return;
        }
        
        boolean canGapple = !needsTotem() || (lethalOverride.get() && !hasEnemyInVisualRange());
        if (useGapple.get() && mc.options.useKey.isPressed() && mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) && canGapple) {
            int hotbarGapple = findGappleHotbar();
            if (hotbarGapple != -1) {
                previousHotbarSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(hotbarGapple);
                syncSelected();
                eatingHotbar = true;
                return;
            }
            int invGapple = findGappleInventory();
            if (invGapple != -1) {
                swappedInvSlot = invGapple;
                swappedHotbarSlot = mc.player.getInventory().getSelectedSlot();
                clickSwap(invGapple, swappedHotbarSlot);
                eatingInventory = true;
                return;
            }
        }
        
        int targetSlot = getEmptyTargetSlot();
        long now = System.currentTimeMillis();
        
        if (afterPops()) {
            updateAfterPopsRefill(targetSlot, now);
        } else if (pendingRefillTime > 0L) {
            if (now >= pendingRefillTime) {
                pendingRefillTime = 0L;
                refill(targetSlot);
            }
        } else {
            boolean needRefill = !mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING);
            if (needRefill) {
                long d = delay.get();
                if (d <= 0L) {
                    refill(targetSlot);
                } else {
                    pendingRefillTime = now + d;
                }
            }
        }
        
        if (needsTotem()) {
            int currentSelected = mc.player.getInventory().getSelectedSlot();
            boolean slotHasTotem = mc.player.getInventory().getStack(targetSlot).isOf(Items.TOTEM_OF_UNDYING);
            
            if (currentSelected != targetSlot || !slotHasTotem) {
                if (slotHasTotem) {
                    mc.player.getInventory().setSelectedSlot(targetSlot);
                    syncSelected();
                } else {
                    int anyHotbarTotem = findTotemInInventoryOnly();
                    if (anyHotbarTotem != -1 && currentSelected != anyHotbarTotem) {
                        mc.player.getInventory().setSelectedSlot(anyHotbarTotem);
                        syncSelected();
                    }
                }
            }
        }
    }

    private int findRefillTotem(int target) {
        int invTotem = findTotemInInventoryOnly();
        if (invTotem != -1) return invTotem;
        
        int start = slotStart.get() - 1;
        int end = slotEnd.get() - 1;
        
        for (int i = start; i <= end; ++i) {
            if (i == target || !mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) continue;
            return i;
        }
        return -1;
    }

    private int findTotemInInventoryOnly() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findGappleHotbar() {
        for (int i = 0; i <= 8; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENCHANTED_GOLDEN_APPLE) || mc.player.getInventory().getStack(i).isOf(Items.GOLDEN_APPLE)) return i;
        }
        return -1;
    }

    private int findGappleInventory() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENCHANTED_GOLDEN_APPLE) || mc.player.getInventory().getStack(i).isOf(Items.GOLDEN_APPLE)) return i;
        }
        return -1;
    }

    private boolean needsTotem() {
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get()) return true;
        return mc.player.fallDistance > fallDistance.get().floatValue();
    }

    private boolean hasEnemyInVisualRange() {
        if (mc.world == null || mc.player == null) return false;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && !player.isDead()) {
                return true;
            }
        }
        return false;
    }

    /**
     * HÀM CỐT LÕI: SWAP SILENT BẰNG PACKET CLICKSWAP (CHUẨN GRIM/NCP)
     * Khác hoàn toàn với InvUtils.swap (hay bị flag)
     */
    private void clickSwap(int invSlot, int targetHotbarSlot) {
        if (mc.interactionManager == null || mc.player == null) return;
        
        // Map từ Inventory Slot sang Screen Handler Slot
        // Inventory 0-8 (Hotbar) -> Container 36-44
        // Inventory 9-35 (Balo) -> Container 9-35
        int screenSource = invSlot < 9 ? invSlot + 36 : invSlot;
        
        // Target hotbar slot (0-8) -> Container 36-44
        int screenTarget = targetHotbarSlot + 36;
        
        int syncId = mc.player.currentScreenHandler.syncId;
        
        // Bước 1: Lấy item từ nguồn (Pickup)
        mc.interactionManager.clickSlot(syncId, screenSource, 0, SlotActionType.PICKUP, mc.player);
        // Bước 2: Đặt item vào đích (Pickup)
        mc.interactionManager.clickSlot(syncId, screenTarget, 0, SlotActionType.PICKUP, mc.player);
    }

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDeactivate() {
        if (eatingHotbar && mc.player != null && previousHotbarSlot != -1) {
            mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
            syncSelected();
        }
        if (eatingInventory && mc.player != null && swappedInvSlot != -1 && swappedHotbarSlot != -1) {
            clickSwap(swappedInvSlot, swappedHotbarSlot);
        }
        eatingHotbar = false;
        previousHotbarSlot = -1;
        eatingInventory = false;
        swappedInvSlot = -1;
        swappedHotbarSlot = -1;
        pendingRefillTime = 0L;
        resetAfterPops("disable");
        observedMode = null;
        observedPlayer = null;
        observedWorld = null;
    }

    @Override
    public String getInfoString() {
        return "Slot " + slotStart.get() + "-" + slotEnd.get();
    }
}
