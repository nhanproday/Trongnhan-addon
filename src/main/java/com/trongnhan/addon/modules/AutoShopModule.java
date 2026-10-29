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
