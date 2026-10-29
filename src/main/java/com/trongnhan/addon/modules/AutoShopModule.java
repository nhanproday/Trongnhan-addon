package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AutoShopModule extends Module {
    private final SettingGroup sgTotem = settings.createGroup("Totem");
    private final SettingGroup sgCrystal = settings.createGroup("Crystal");
    private final SettingGroup sgExp = settings.createGroup("Exp");
    private final SettingGroup sgObsidian = settings.createGroup("Obsidian");
    private final SettingGroup sgPearl = settings.createGroup("EnderPearl");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> totemBelowThreshold = sgTotem.add(new BoolSetting.Builder()
        .name("TotemBelowThreshold").description("Automatically buy Totems after the configured number are lost.")
        .defaultValue(false).build());
    public final Setting<Integer> totemAmount = sgTotem.add(new IntSetting.Builder()
        .name("TotemAmount").description("How many totems to buy.")
        .defaultValue(1).min(1).max(10).build());
    public final Setting<Integer> totemLostCount = sgTotem.add(new IntSetting.Builder()
        .name("TotemLostCount").description("Tracked Totems lost before one automatic purchase.")
        .defaultValue(2).min(1).max(64).build());
    public final Setting<Boolean> crystalBelowThreshold = sgCrystal.add(new BoolSetting.Builder()
        .name("CrystalBelowThreshold").description("Buy one Crystal order when stock reaches the threshold.")
        .defaultValue(false).build());
    public final Setting<Integer> crystalStacks = sgCrystal.add(new IntSetting.Builder()
        .name("CrystalStacks").description("How many stacks (64) of end crystals to buy.")
        .defaultValue(1).min(1).max(10).build());
    public final Setting<Integer> crystalThreshold = sgCrystal.add(new IntSetting.Builder()
        .name("CrystalThreshold").description("Crystal stack threshold.")
        .defaultValue(1).min(0).max(20).build());
    public final Setting<Boolean> expBelowThreshold = sgExp.add(new BoolSetting.Builder()
        .name("ExpBelowThreshold").description("Buy one Exp order when stock reaches the threshold.")
        .defaultValue(false).build());
    public final Setting<Integer> expStacks = sgExp.add(new IntSetting.Builder()
        .name("ExpStacks").description("How many stacks (64) of exp bottles to buy.")
        .defaultValue(1).min(1).max(10).build());
    public final Setting<Integer> expThreshold = sgExp.add(new IntSetting.Builder()
        .name("ExpThreshold").description("Exp stack threshold.")
        .defaultValue(1).min(0).max(20).build());
    public final Setting<Boolean> obsidianBelowThreshold = sgObsidian.add(new BoolSetting.Builder()
        .name("ObsidianBelowThreshold").description("Buy one Obsidian order when stock reaches the threshold.")
        .defaultValue(false).build());
    public final Setting<Integer> obsidianStacks = sgObsidian.add(new IntSetting.Builder()
        .name("ObsidianStacks").description("How many stacks (64) of obsidian to buy.")
        .defaultValue(1).min(1).max(10).build());
    public final Setting<Integer> obsidianThreshold = sgObsidian.add(new IntSetting.Builder()
        .name("ObsidianThreshold").description("Obsidian stack threshold.")
        .defaultValue(1).min(0).max(20).build());
    public final Setting<Boolean> enderPearlBelowThreshold = sgPearl.add(new BoolSetting.Builder()
        .name("EnderPearlBelowThreshold").description("Buy one EnderPearl order when stock reaches the threshold.")
        .defaultValue(false).build());
    public final Setting<Integer> enderPearlStacks = sgPearl.add(new IntSetting.Builder()
        .name("EnderPearlStacks").description("How many stacks (64) of ender pearls to buy.")
        .defaultValue(1).min(1).max(10).build());
    public final Setting<Integer> enderPearlThreshold = sgPearl.add(new IntSetting.Builder()
        .name("EnderPearlThreshold").description("EnderPearl stack threshold.")
        .defaultValue(1).min(0).max(20).build());
    public final Setting<Boolean> silent = sgGeneral.add(new BoolSetting.Builder()
        .name("Silent").description("Suppress expected real shop screens before presentation.")
        .defaultValue(false).build());
    public final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("ActionDelay").description("Ticks between each shop GUI click.")
        .defaultValue(4).min(0).max(20).build());

    private static final int SLOT_ITEM_TOTEM = 13;
    private static final int SLOT_ITEM_CRYSTAL = 10;
    private static final int SLOT_ITEM_EXP = 16;
    private static final int SLOT_ITEM_OBSIDIAN = 9;
    private static final int SLOT_ITEM_ENDERPEARL = 14;
    private static final int SLOT_SET_1 = 15;
    private static final int SLOT_SET_11 = 16;
    private static final int SLOT_CONFIRM = 23;
    private static final int STEP_TIMEOUT_TICKS = 100;

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private State state = State.IDLE;
    private final Queue<PurchaseOrder> orderQueue = new ConcurrentLinkedQueue<>();
    private PurchaseOrder currentOrder = null;
    private int ticks = 0;
    private int timeoutTicks = 0;
    private boolean quantitySet = false;
    private final AutoControl[] autoControls = {new AutoControl(), new AutoControl(), new AutoControl(), new AutoControl(), new AutoControl()};
    private boolean totemTrackingInitialized;
    private boolean totemContainerResyncPending;
    private boolean totemZeroFailsafePending;
    private boolean totemZeroFailsafeUsed;
    private boolean totemOrderRecovered;
    private String totemEpisodeTriggerReason = "LOSS";
    private int baselineTotems;
    private int previousObservedTotems;
    private Object lastWorld;
    private Object lastPlayer;
    private long generation;
    private long expectedGeneration;
    private ResponseReason expectedReason = ResponseReason.NONE;
    private long expectationExpiresAt;
    private boolean realShopScreenIntercepted;
    private boolean silentSession;

    public AutoShopModule() {
        super(TrongNhanAddon.CATEGORY, "auto-shop", "Automates the server /shop GUI to buy items.");
    }

    @Override
    public void onActivate() {
        resetAutoControls();
        clearTotemTracking();
    }

    @Override
    public void onDeactivate() {
        resetAutoControls();
        clearTotemTracking();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (lastWorld != mc.world || lastPlayer != mc.player) {
            if (lastWorld != null && state != State.IDLE) abort("Player changed world/session");
            lastWorld = mc.world;
            lastPlayer = mc.player;
            generation++;
            clearSilentState();
            resetAutoControls();
            clearTotemTracking();
        }

        if (mc.player.isDead() || mc.player.getHealth() <= 0.0f) {
            if (isTotemAutoOrder()) {
                abort("Player dead during automatic Totem order");
                clearTotemTracking();
            }
            return;
        }

        observeTotemStock();
        tryStartAutomaticOrder();

        if (state == State.IDLE) return;

        expireExpectedResponse();
        if (ticks < actionDelay.get()) {
            ticks++;
            return;
        }
        ticks = 0;

        Screen currentScreen = mc.currentScreen;
        boolean screenOpen = currentScreen instanceof AbstractInventoryScreen && !(currentScreen instanceof InventoryScreen);
        if (silent.get() && silentSession) screenOpen = screenOpen || mc.player.currentScreenHandler != mc.player.playerScreenHandler;

        if (state != State.WAIT_SHOP && !screenOpen) {
            abort("Shop GUI closed unexpectedly.");
            return;
        }

        switch (state) {
            case WAIT_SHOP -> {
                if (screenOpen || (silent.get() && realShopScreenIntercepted)) {
                    realShopScreenIntercepted = false;
                    state = State.CLICK_ITEM;
                    timeoutTicks = 0;
                } else {
                    timeoutTicks++;
                    if (timeoutTicks % 20 == 0) sendShopCommand();
                    if (timeoutTicks > STEP_TIMEOUT_TICKS) abort("Shop GUI never opened.");
                }
            }
            case CLICK_ITEM -> {
                if (currentOrder == null) {
                    state = State.DECIDE;
                    return;
                }
                int slot = switch (currentOrder.mode) {
                    case Totem -> SLOT_ITEM_TOTEM;
                    case Crystal -> SLOT_ITEM_CRYSTAL;
                    case Exp -> SLOT_ITEM_EXP;
                    case Obsidian -> SLOT_ITEM_OBSIDIAN;
                    case EnderPearl -> SLOT_ITEM_ENDERPEARL;
                };
                if (silent.get() && silentSession) armExpectedResponse(ResponseReason.ITEM_SELECTION);
                click(slot);
                state = State.DECIDE;
            }
            case DECIDE -> {
                if (currentOrder == null || currentOrder.remaining <= 0) {
                    if (!orderQueue.isEmpty()) {
                        if (isTotemAutoOrder()) {
                            markAutoServiced();
                            totemOrderRecovered = false;
                        }
                        currentOrder = orderQueue.poll();
                        beginOrder();
                        return;
                    }
                    finish();
                    return;
                }
                if (currentOrder.mode == ShopMode.Totem) {
                    state = State.CONFIRM;
                } else if (!quantitySet) {
                    state = State.SET_11;
                } else {
                    state = State.CONFIRM;
                }
            }
            case SET_11 -> {
                int slot = currentOrder != null && currentOrder.mode == ShopMode.EnderPearl ? SLOT_SET_1 : SLOT_SET_11;
                if (silent.get() && silentSession) armExpectedResponse(ResponseReason.QUANTITY_SELECTION);
                click(slot);
                quantitySet = true;
                state = State.CONFIRM;
            }
            case CONFIRM -> {
                click(SLOT_CONFIRM);
                if (currentOrder != null) currentOrder.remaining--;
                state = State.DECIDE;
            }
        }
    }

    private void beginOrder() {
        generation++;
        clearSilentState();
        lastWorld = mc.world;
        lastPlayer = mc.player;
        ticks = 0;
        timeoutTicks = 0;
        quantitySet = false;
        sendShopCommand();
        state = State.WAIT_SHOP;
    }

    private void sendShopCommand() {
        if (mc.getNetworkHandler() != null) {
            armExpectedResponse(ResponseReason.SHOP_COMMAND);
            mc.getNetworkHandler().sendChatCommand("shop open gear");
        }
    }

    private void armExpectedResponse(ResponseReason reason) {
        if (!silent.get() || currentOrder == null) return;
        expectedReason = reason;
        expectedGeneration = generation;
        expectationExpiresAt = System.nanoTime() + 2_000_000_000L;
    }

    private void expireExpectedResponse() {
        if (expectedReason != ResponseReason.NONE && System.nanoTime() - expectationExpiresAt >= 0) {
            expectedReason = ResponseReason.NONE;
        }
    }

    private void clearSilentState() {
        expectedReason = ResponseReason.NONE;
        expectedGeneration = 0;
        realShopScreenIntercepted = false;
        silentSession = false;
    }

    private boolean isTotemAutoOrder() {
        return currentOrder != null && currentOrder.mode == ShopMode.Totem && currentOrder.origin != OrderOrigin.MANUAL;
    }

    private void resetAutoControls() {
        for (AutoControl control : autoControls) control.reset();
    }

    private AutoControl totemAutoControl() { return autoControls[ShopMode.Totem.ordinal()]; }

    private void clearTotemTracking() {
        totemTrackingInitialized = false;
        totemContainerResyncPending = false;
        totemZeroFailsafePending = false;
        totemZeroFailsafeUsed = false;
        totemOrderRecovered = false;
        totemEpisodeTriggerReason = "LOSS";
        baselineTotems = 0;
        previousObservedTotems = 0;
        totemAutoControl().reset();
    }

    private void resetTotemBaseline(int stock, String reason) {
        baselineTotems = stock;
        previousObservedTotems = stock;
        totemTrackingInitialized = true;
        totemContainerResyncPending = false;
        totemZeroFailsafePending = false;
        totemZeroFailsafeUsed = false;
        totemEpisodeTriggerReason = "LOSS";
        AutoControl control = totemAutoControl();
        control.reset();
        control.wasEnabled = true;
    }

    private boolean isUnrelatedInventorySession() {
        Screen screen = mc.currentScreen;
        boolean ownedShop = state != State.IDLE && currentOrder != null;
        if (screen instanceof InventoryScreen) return true;
        if (screen instanceof AbstractInventoryScreen) return !ownedShop;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            return !(ownedShop && silent.get() && (silentSession || expectedReason != ResponseReason.NONE));
        }
        return false;
    }

    private void observeTotemStock() {
        AutoControl control = totemAutoControl();
        if (!totemBelowThreshold.get()) {
            if (totemTrackingInitialized || control.wasEnabled) clearTotemTracking();
            return;
        }
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || mc.player.isDead() || mc.player.getHealth() <= 0.0f) {
            if (totemTrackingInitialized) clearTotemTracking();
            return;
        }
        int stock = stockCount(Items.TOTEM_OF_UNDYING);
        if (isUnrelatedInventorySession()) {
            totemContainerResyncPending = true;
            return;
        }
        if (totemContainerResyncPending) {
            resetTotemBaseline(stock, "CONTAINER_RESYNC");
            return;
        }
        if (!totemTrackingInitialized) {
            resetTotemBaseline(stock, "ENABLE/SESSION");
            return;
        }
        if (stock > previousObservedTotems) {
            if (isTotemAutoOrder()) totemOrderRecovered = true;
            resetTotemBaseline(stock, "STOCK_INCREASE");
            return;
        }
        if (stock < previousObservedTotems) {
            if (previousObservedTotems > 0 && stock == 0 && baselineTotems > 0 && !totemZeroFailsafeUsed) {
                totemZeroFailsafePending = true;
            }
        }
        previousObservedTotems = stock;
    }

    private void markAutoFailure() {
        if (currentOrder == null || currentOrder.origin == OrderOrigin.MANUAL) return;
        if (isTotemAutoOrder() && (!totemBelowThreshold.get() || totemOrderRecovered)) return;
        AutoControl control = autoControls[currentOrder.mode.ordinal()];
        if (currentOrder.origin == OrderOrigin.AUTO_PRIMARY) {
            control.phase = AutoPhase.RETRY_COOLDOWN;
            control.retryAtNanos = System.nanoTime() + 5_000_000_000L;
        } else {
            control.phase = AutoPhase.EXHAUSTED;
        }
    }

    private void markAutoServiced() {
        if (currentOrder == null || currentOrder.origin == OrderOrigin.MANUAL) return;
        if (isTotemAutoOrder() && (!totemBelowThreshold.get() || totemOrderRecovered)) return;
        AutoControl control = autoControls[currentOrder.mode.ordinal()];
        control.phase = AutoPhase.SERVICED;
        int stock = stockCount(itemFor(currentOrder.mode));
        control.peakStock = Math.max(control.startStock, stock);
    }

    private static Item itemFor(ShopMode mode) {
        return switch (mode) {
            case Totem -> Items.TOTEM_OF_UNDYING;
            case Crystal -> Items.END_CRYSTAL;
            case Exp -> Items.EXPERIENCE_BOTTLE;
            case Obsidian -> Items.OBSIDIAN;
            case EnderPearl -> Items.ENDER_PEARL;
        };
    }

    private int stockCount(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private boolean tryStartAutomaticTotemOrder() {
        if (!totemBelowThreshold.get() || !totemTrackingInitialized || totemContainerResyncPending || mc.player == null || mc.world == null || mc.getNetworkHandler() == null || mc.player.isDead() || mc.player.getHealth() <= 0.0f) return false;
        AutoControl control = totemAutoControl();
        if (state != State.IDLE || !orderQueue.isEmpty()) return false;
        if (control.phase != AutoPhase.READY && control.phase != AutoPhase.RETRY_COOLDOWN) return false;
        int stock = previousObservedTotems;
        int lost = Math.max(0, baselineTotems - stock);
        int configuredLoss = totemLostCount.get();
        boolean lossTrigger = lost >= configuredLoss;
        boolean zeroTrigger = baselineTotems > 0 && stock == 0 && totemZeroFailsafePending && !totemZeroFailsafeUsed;
        if ((control.phase != AutoPhase.RETRY_COOLDOWN && !lossTrigger && !zeroTrigger) || !autoStartSafe(Items.TOTEM_OF_UNDYING)) return false;
        if (control.phase == AutoPhase.RETRY_COOLDOWN && System.nanoTime() - control.retryAtNanos < 0) return false;
        OrderOrigin origin = control.phase == AutoPhase.READY ? OrderOrigin.AUTO_PRIMARY : OrderOrigin.AUTO_RETRY;
        if (origin == OrderOrigin.AUTO_PRIMARY) control.startStock = stock;
        control.phase = origin == OrderOrigin.AUTO_PRIMARY ? AutoPhase.RUNNING_PRIMARY : AutoPhase.RUNNING_RETRY;
        control.peakStock = Math.max(control.peakStock, stock);
        totemOrderRecovered = false;
        if (origin == OrderOrigin.AUTO_PRIMARY) {
            totemEpisodeTriggerReason = lossTrigger ? "LOSS" : "ZERO_FAILSAFE";
            if (zeroTrigger && !lossTrigger) {
                totemZeroFailsafePending = false;
                totemZeroFailsafeUsed = true;
            }
        }
        enqueuePurchaseOrder(new PurchaseOrder(ShopMode.Totem, totemAmount.get(), origin));
        return true;
    }

    private void tryStartAutomaticOrder() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        for (ShopMode mode : ShopMode.values()) {
            if (mode == ShopMode.Totem) {
                if (tryStartAutomaticTotemOrder()) return;
                continue;
            }
            AutoControl control = autoControls[mode.ordinal()];
            boolean enabled = autoEnabled(mode);
            if (!enabled) {
                control.wasEnabled = false;
                continue;
            }
            if (!control.wasEnabled) {
                control.reset();
                control.wasEnabled = true;
            }
            int stock = stockCount(itemFor(mode));
            int threshold = thresholdCount(mode, itemFor(mode));
            if (control.phase == AutoPhase.SERVICED || control.phase == AutoPhase.EXHAUSTED) {
                control.peakStock = Math.max(control.peakStock, stock);
                if (stock > threshold || (control.peakStock > control.startStock && stock < control.peakStock && stock <= threshold)) {
                    control.phase = AutoPhase.READY;
                }
            } else if (control.phase == AutoPhase.RETRY_COOLDOWN) {
                if (stock > control.startStock) {
                    control.phase = AutoPhase.SERVICED;
                    control.peakStock = stock;
                } else if (stock > threshold) {
                    control.phase = AutoPhase.READY;
                }
            }
            if (state != State.IDLE || !orderQueue.isEmpty()) continue;
            if (control.phase != AutoPhase.READY && control.phase != AutoPhase.RETRY_COOLDOWN) continue;
            if (stock > threshold || !autoStartSafe(itemFor(mode))) continue;
            if (control.phase == AutoPhase.RETRY_COOLDOWN && System.nanoTime() - control.retryAtNanos < 0) continue;
            OrderOrigin origin = control.phase == AutoPhase.READY ? OrderOrigin.AUTO_PRIMARY : OrderOrigin.AUTO_RETRY;
            if (origin == OrderOrigin.AUTO_PRIMARY) control.startStock = stock;
            control.phase = origin == OrderOrigin.AUTO_PRIMARY ? AutoPhase.RUNNING_PRIMARY : AutoPhase.RUNNING_RETRY;
            control.peakStock = Math.max(control.peakStock, stock);
            enqueuePurchaseOrder(new PurchaseOrder(mode, manualAmount(mode), origin));
            return;
        }
    }

    private boolean autoStartSafe(Item item) {
        if (mc.currentScreen != null || mc.player.currentScreenHandler != mc.player.playerScreenHandler) return false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || (stack.getItem() == item && stack.getCount() < stack.getMaxCount())) return true;
        }
        return false;
    }

    private boolean autoEnabled(ShopMode mode) {
        return switch (mode) {
            case Totem -> totemBelowThreshold.get();
            case Crystal -> crystalBelowThreshold.get();
            case Exp -> expBelowThreshold.get();
            case Obsidian -> obsidianBelowThreshold.get();
            case EnderPearl -> enderPearlBelowThreshold.get();
        };
    }

    private int thresholdCount(ShopMode mode, Item item) {
        int threshold = switch (mode) {
            case Totem -> throw new IllegalArgumentException("Totem automatic buying uses loss tracking");
            case Crystal -> crystalThreshold.get();
            case Exp -> expThreshold.get();
            case Obsidian -> obsidianThreshold.get();
            case EnderPearl -> enderPearlThreshold.get();
        };
        return mode == ShopMode.Totem ? threshold : threshold * item.getDefaultStack().getMaxCount();
    }

    private int manualAmount(ShopMode mode) {
        return switch (mode) {
            case Totem -> totemAmount.get();
            case Crystal -> crystalStacks.get();
            case Exp -> expStacks.get();
            case Obsidian -> obsidianStacks.get();
            case EnderPearl -> enderPearlStacks.get();
        };
    }

    public synchronized void enqueueOrder(ShopMode shopMode, int amount) {
        enqueuePurchaseOrder(new PurchaseOrder(shopMode, amount));
    }

    private synchronized void enqueuePurchaseOrder(PurchaseOrder order) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (order.remaining <= 0) return;
        orderQueue.add(order);
        if (state == State.IDLE) {
            currentOrder = orderQueue.poll();
            beginOrder();
        }
    }

    private void finish() {
        markAutoServiced();
        totemOrderRecovered = false;
        state = State.IDLE;
        orderQueue.clear();
        currentOrder = null;
        generation++;
        clearSilentState();
        if (mc.player != null) mc.player.closeHandledScreen();
        ChatUtils.sendMsg(Text.literal("AutoShop: Done."));
    }

    private void abort(String reason) {
        markAutoFailure();
        totemOrderRecovered = false;
        state = State.IDLE;
        orderQueue.clear();
        currentOrder = null;
        generation++;
        clearSilentState();
        if (mc.player != null) mc.player.closeHandledScreen();
        ChatUtils.sendMsg(Text.literal("AutoShop: Aborted - " + reason));
    }

    private void click(int slot) {
        if (mc.interactionManager != null) {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    @Override
    public String getInfoString() {
        if (currentOrder != null) {
            return currentOrder.mode.name() + " " + currentOrder.remaining + (orderQueue.isEmpty() ? "" : " (+" + orderQueue.size() + ")");
        }
        return "";
    }

    private enum State { IDLE, WAIT_SHOP, CLICK_ITEM, DECIDE, SET_11, CONFIRM }
    private enum ResponseReason { NONE, SHOP_COMMAND, ITEM_SELECTION, QUANTITY_SELECTION }
    private enum OrderOrigin { MANUAL, AUTO_PRIMARY, AUTO_RETRY }
    private enum AutoPhase { READY, RUNNING_PRIMARY, RETRY_COOLDOWN, RUNNING_RETRY, SERVICED, EXHAUSTED }

    private static final class AutoControl {
        private AutoPhase phase = AutoPhase.READY;
        private boolean wasEnabled;
        private int startStock;
        private int peakStock;
        private long retryAtNanos;
        private void reset() {
            phase = AutoPhase.READY;
            wasEnabled = false;
            startStock = 0;
            peakStock = 0;
            retryAtNanos = 0;
        }
    }

    public static class PurchaseOrder {
        public final ShopMode mode;
        public int remaining;
        private final OrderOrigin origin;
        public PurchaseOrder(ShopMode mode, int remaining) { this(mode, remaining, OrderOrigin.MANUAL); }
        private PurchaseOrder(ShopMode mode, int remaining, OrderOrigin origin) {
            this.mode = mode; this.remaining = remaining; this.origin = origin;
        }
    }

    public enum ShopMode { Totem, Crystal, Exp, Obsidian, EnderPearl }
}
