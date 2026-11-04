package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AutoTotemModule extends Module {

    private final SettingGroup sgTotem = settings.createGroup("Totem");
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> totemAmount = sgTotem.add(new IntSetting.Builder()
        .name("totem-amount").defaultValue(1).min(1).sliderMax(10).build());

    public final Setting<Boolean> belowThreshold = sgTotem.add(new BoolSetting.Builder()
        .name("below-threshold").defaultValue(false).build());

    public final Setting<Integer> totemThreshold = sgTotem.add(new IntSetting.Builder()
        .name("totem-threshold").defaultValue(3).min(1).sliderMax(36).build());

    public final Setting<Integer> crystalStacks = sgTotem.add(new IntSetting.Builder()
        .name("crystal-stacks").defaultValue(1).min(1).sliderMax(10).build());

    public final Setting<Boolean> crystalBelowThreshold = sgTotem.add(new BoolSetting.Builder()
        .name("crystal-below-threshold").defaultValue(false).build());

    public final Setting<Integer> crystalThreshold = sgTotem.add(new IntSetting.Builder()
        .name("crystal-threshold").defaultValue(200).min(100).sliderRange(100, 500).build());

    public final Setting<Integer> expStacks = sgTotem.add(new IntSetting.Builder()
        .name("exp-stacks").defaultValue(1).min(1).sliderMax(10).build());

    public final Setting<Boolean> expBelowThreshold = sgTotem.add(new BoolSetting.Builder()
        .name("exp-below-threshold").defaultValue(false).build());

    public final Setting<Integer> expThreshold = sgTotem.add(new IntSetting.Builder()
        .name("exp-threshold").defaultValue(200).min(100).sliderRange(100, 500).build());

    public final Setting<Integer> obsidianStacks = sgTotem.add(new IntSetting.Builder()
        .name("obsidian-stacks").defaultValue(1).min(1).sliderMax(10).build());

    public final Setting<Boolean> obsidianBelowThreshold = sgTotem.add(new BoolSetting.Builder()
        .name("obsidian-below-threshold").defaultValue(false).build());

    public final Setting<Integer> obsidianThreshold = sgTotem.add(new IntSetting.Builder()
        .name("obsidian-threshold").defaultValue(200).min(100).sliderRange(100, 500).build());

    public final Setting<Integer> enderPearlStacks = sgTotem.add(new IntSetting.Builder()
        .name("enderpearl-stacks").defaultValue(1).min(1).sliderMax(10).build());

    public final Setting<Boolean> enderPearlBelowThreshold = sgTotem.add(new BoolSetting.Builder()
        .name("enderpearl-below-threshold").defaultValue(false).build());

    public final Setting<Integer> enderPearlThreshold = sgTotem.add(new IntSetting.Builder()
        .name("enderpearl-threshold").defaultValue(16).min(1).sliderMax(64).build());

    public final Setting<Boolean> inventoryTotem = sgInventory.add(new BoolSetting.Builder()
        .name("inventory-totem").defaultValue(true).build());

    public final Setting<Integer> keepAmount = sgInventory.add(new IntSetting.Builder()
        .name("keep-amount").defaultValue(3).min(0).sliderMax(10)
        .visible(inventoryTotem::get).build());

    public final Setting<Integer> hotbarSlotFirst = sgInventory.add(new IntSetting.Builder()
        .name("hotbar-slot-first").defaultValue(1).min(1).sliderMax(9).build());

    public final Setting<Integer> hotbarSlotLast = sgInventory.add(new IntSetting.Builder()
        .name("hotbar-slot-last").defaultValue(1).min(1).sliderMax(9).build());

    public final Setting<Boolean> mainhandTotem = sgInventory.add(new BoolSetting.Builder()
        .name("mainhand-totem").defaultValue(false).build());

    public final Setting<Boolean> disableOnDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-death").defaultValue(false).build());

    public final Setting<Boolean> disableOnLog = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-log").defaultValue(false).build());

    public final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay").defaultValue(0).min(0).sliderMax(20).build());

    public final Setting<Boolean> silent = sgGeneral.add(new BoolSetting.Builder()
        .name("silent").defaultValue(true).build());

    public final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").defaultValue(false).build());

    private static final int SLOT_TOTEM = 13;
    private static final int SLOT_CRYSTAL = 10;
    private static final int SLOT_EXP = 16;
    private static final int SLOT_OBSIDIAN = 9;
    private static final int SLOT_ENDERPEARL = 14;
    private static final int SLOT_SET11 = 16;
    private static final int SLOT_EP_SET11 = 15;
    private static final int SLOT_CONFIRM = 23;

    private static final Set<String> ALLOWED_HOSTS = Set.of("java.kingmc.vn", "kingmc.vn");
    private static final long LOCK_MS = 10000L;
    private static final long HOTBAR_CHECK_COOLDOWN_MS = 2000L;
    private static final long MAINHAND_SELECT_WINDOW_MS = 3000L;

    private int retryCount;
    private int staleContainerId = -1;
    private ShopState state = ShopState.IDLE;
    private final Deque<BuyOrder> queue = new ConcurrentLinkedDeque<>();
    private boolean preemptFlag;
    private BuyOrder currentOrder;
    private int actionTicks;
    private int waitTicks;
    private boolean set11Done;
    private long lastConfirmTime;
    private int lastStateId = -1;
    private int preClickItemCount;
    private static long globalLockUntil = 0L;
    private boolean lastWasTotemOrder;
    private ClientWorld lastWorldForLock;
    private long lockUntil;
    private final Map<ShopItem, Long> lastBuyAttempt = new HashMap<>();
    private String lastErrorMessage;

    private long lastHotbarCheckMs;
    private volatile boolean popPending;
    private volatile long mainhandSelectDeadlineMs;

    private static final int HOTBAR_FIRST = 0;
    private static final int HOTBAR_LAST = 8;
    private static final int INV_FIRST = 9;
    private static final int INV_LAST = 35;

    public AutoTotemModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem-kingmc",
            "Buys totems with AutoShop on every pop instead of swapping them out of the inventory (KingMC).");
    }

    @Override
    public void onActivate() { resetAll(); }

    @Override
    public void onDeactivate() {
        resetAll();
        if (mc.player != null) closeContainer();
    }

    private void resetAll() {
        retryCount = 0;
        state = ShopState.IDLE;
        queue.clear();
        currentOrder = null;
        preemptFlag = false;
        lastConfirmTime = 0;
        set11Done = false;
        lastWasTotemOrder = false;
        actionTicks = 0;
        waitTicks = 0;
        lastHotbarCheckMs = 0L;
        mainhandSelectDeadlineMs = 0L;
        popPending = false;
        lastErrorMessage = null;
    }

    @EventHandler(priority = 100000)
    private void onPacketReceive(PacketEvent.Receive e) {
        if (e.packet instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35 && mc.world != null && mc.player != null
                && packet.getEntity(mc.world) == mc.player) {
                if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    mainhandSelectDeadlineMs = System.currentTimeMillis();
                }
                popPending = true;
            }
            return;
        }
        if (!debug.get()) return;
        Packet<?> p = e.packet;
        if (p instanceof GameMessageS2CPacket chat) {
            log("server msg: " + chat.content().getString());
        } else if (p instanceof OverlayMessageS2CPacket ab) {
            log("actionbar: " + ab.getMessage().getString());
        } else if (p instanceof OpenScreenS2CPacket os) {
            log("open screen id=" + os.getSyncId() + " title=" + os.getName().getString());
        } else if (p instanceof CloseScreenS2CPacket cc) {
            log("server closed window " + cc.getSyncId());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.isAlive()) return;

        long now = System.currentTimeMillis();
        hotbarMaintenance();
        handleShopState(now);
    }

    private void hotbarMaintenance() {
        restoreMainhandAfterPop();
        keepMainhandTotem();
        keepHotbarTotemSelected();

        if (mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            throwExcessInventoryTotems();
            if (!isRunning()) makeRoomInHotbarSlots();
        }

        if (hotbarTotemCount() <= 2) {
            if (findItemSlot(Items.TOTEM_OF_UNDYING, HOTBAR_FIRST, HOTBAR_LAST) != -1) {
                if (!popPending) {
                    long now = System.currentTimeMillis();
                    if (now - lastHotbarCheckMs < HOTBAR_CHECK_COOLDOWN_MS) return;
                }
                if (popPending || !isInOrder()) {
                    String msg = popPending
                        ? "pop, hotbar totems=" + hotbarTotemCount()
                        : "hotbar totems=" + hotbarTotemCount();
                    notifyBuyTotem(msg);
                }
            }
        }
    }

    private void restoreMainhandAfterPop() {
        if (mainhandSelectDeadlineMs == 0L) return;
        long now = System.currentTimeMillis();
        if (now - mainhandSelectDeadlineMs > MAINHAND_SELECT_WINDOW_MS) {
            mainhandSelectDeadlineMs = 0L;
            return;
        }
        PlayerInventory inv = mc.player.getInventory();
        int selected = inv.selectedSlot;
        ItemStack cur = inv.getStack(selected);
        if (cur.isOf(Items.TOTEM_OF_UNDYING)) return;
        if (!cur.isEmpty()) { mainhandSelectDeadlineMs = 0L; return; }
        int found = findItemSlot(Items.TOTEM_OF_UNDYING, HOTBAR_FIRST, HOTBAR_LAST);
        if (found != -1) {
            selectHotbarSlot(found);
            mainhandSelectDeadlineMs = 0L;
        }
    }

    private void keepMainhandTotem() {
        if (!mainhandTotem.get()) return;
        if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        int found = findItemSlot(Items.TOTEM_OF_UNDYING, HOTBAR_FIRST, HOTBAR_LAST);
        if (found != -1) selectHotbarSlot(found);
    }

    private void keepHotbarTotemSelected() {
        if (!shouldManageHotbar()) return;
        if (mc.options.useKey.isPressed() || mc.player.isUsingItem()) return;

        int selected = mc.player.getInventory().selectedSlot;
        int first = hotbarSlotFirst.get() - 1;
        int last = hotbarSlotLast.get() - 1;

        if (mc.player.getInventory().getStack(selected).isOf(Items.TOTEM_OF_UNDYING)
            && selected >= first && selected <= last) return;

        int found = findItemSlot(Items.TOTEM_OF_UNDYING, first, last);
        if (found == -1) {
            if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
            if (selected >= first && selected <= last) return;
            found = first;
        }
        selectHotbarSlot(found);
    }

    private void throwExcessInventoryTotems() {
        if (!inventoryTotem.get()) return;
        int keep = keepAmount.get();
        int seen = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = INV_FIRST; i <= INV_LAST; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                seen++;
                if (seen > keep) {
                    int containerSlot = toHandlerSlot(i);
                    if (containerSlot >= 0 && mc.interactionManager != null) {
                        mc.interactionManager.clickSlot(
                            mc.player.currentScreenHandler.syncId,
                            containerSlot, 1, SlotActionType.THROW, mc.player);
                    }
                }
            }
        }
    }

    private void makeRoomInHotbarSlots() {
        int start = hotbarSlotFirst.get() - 1;
        int end = hotbarSlotLast.get() - 1;
        if (start > end) return;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = start; i <= end; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty() || st.isOf(Items.TOTEM_OF_UNDYING)) continue;
            int emptyInvSlot = findEmptySlot(INV_FIRST, INV_LAST);
            if (emptyInvSlot == -1) return;
            swapSlots(emptyInvSlot, i);
        }
    }

    private void notifyBuyTotem(String reason) {
        lastHotbarCheckMs = System.currentTimeMillis();
        popPending = false;

        double used;
        try { used = invUsedFraction(); } catch (Throwable t) { used = 0.0; }

        if (Math.abs(used - 1.0) > 0.05) {
            int syncId = mc.player.playerScreenHandler.syncId;
            for (Slot slot : mc.player.playerScreenHandler.slots) {
                if (slot != null && slot.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
                    mc.interactionManager.clickSlot(syncId, slot.id, 1, SlotActionType.THROW, mc.player);
                }
            }
        } else {
            if (debug.get()) log("buy: " + reason);
            buyTotemNow();
        }
    }

    private void handleShopState(long now) {
        if (now < globalLockUntil) {
            ShopItem want = decideNextItem();
            if (want != null) warn(want, "AutoShop is locked for a few seconds");
            return;
        }

        if (state == ShopState.IDLE) handleIdle();
        else handleActive(now);
    }

    private void handleIdle() {
        ShopItem want = decideNextItem();
        if (want == null) { lastErrorMessage = null; return; }
        if (!mc.player.isAlive()) { warn(want, "you are dead"); return; }
        if (!isScreenSafeToBuy()) {
            Screen s = mc.currentScreen;
            warn(want, "a screen is open (" + (s != null ? s.getClass().getSimpleName() : "?") + "), close it");
            return;
        }

        if (tryAutoBuy(belowThreshold, totemThreshold, Items.TOTEM_OF_UNDYING, ShopItem.Totem, totemAmount.get())) return;
        if (tryAutoBuy(crystalBelowThreshold, crystalThreshold, Items.END_CRYSTAL, ShopItem.Crystal, crystalStacks.get())) return;
        if (tryAutoBuy(expBelowThreshold, expThreshold, Items.EXPERIENCE_BOTTLE, ShopItem.Exp, expStacks.get())) return;
        if (tryAutoBuy(obsidianBelowThreshold, obsidianThreshold, Items.OBSIDIAN, ShopItem.Obsidian, obsidianStacks.get())) return;
        tryAutoBuy(enderPearlBelowThreshold, enderPearlThreshold, Items.ENDER_PEARL, ShopItem.EnderPearl, enderPearlStacks.get());
    }

    private ShopItem decideNextItem() {
        if (belowThreshold.get() && countItem(Items.TOTEM_OF_UNDYING) < totemThreshold.get()) return ShopItem.Totem;
        if (crystalBelowThreshold.get() && countItem(Items.END_CRYSTAL) < crystalThreshold.get()) return ShopItem.Crystal;
        if (expBelowThreshold.get() && countItem(Items.EXPERIENCE_BOTTLE) < expThreshold.get()) return ShopItem.Exp;
        if (obsidianBelowThreshold.get() && countItem(Items.OBSIDIAN) < obsidianThreshold.get()) return ShopItem.Obsidian;
        if (enderPearlBelowThreshold.get() && countItem(Items.ENDER_PEARL) < enderPearlThreshold.get()) return ShopItem.EnderPearl;
        return null;
    }

    private boolean tryAutoBuy(Setting<Boolean> toggle, Setting<Integer> threshold, Item item, ShopItem shop, int amount) {
        long now = System.currentTimeMillis();
        if (now < globalLockUntil) return false;
        if (!toggle.get()) return false;
        if (countItem(item) >= threshold.get()) return false;
        if (!isOnKingMC()) { warn(shop, "only works when joined through java.kingmc.vn or kingmc.vn"); return false; }
        if (!shopOpenPossible()) { warn(shop, "in hub/login, or /shop didn't open in this world"); return false; }
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            warn(shop, "an item is on your cursor (dropping it first)");
            if (mc.interactionManager != null)
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, -999, 0, SlotActionType.PICKUP, mc.player);
            return false;
        }
        if (!hasFreeOrPartialSlot(item)) { warn(shop, "inventory full (no free slot)"); return false; }

        long last = lastBuyAttempt.getOrDefault(shop, 0L);
        if (now - last < 2000L) return false;
        lastBuyAttempt.put(shop, now);
        lastErrorMessage = null;
        enqueue(shop, amount);
        return true;
    }

    public synchronized void enqueue(ShopItem item, int amount) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (!isOnKingMC()) return;
        if (state != ShopState.IDLE) return;
        if (amount <= 0) return;

        info("Buying " + item.name().toLowerCase() + "...");
        if (item == ShopItem.Totem) lastWasTotemOrder = true;

        queue.add(new BuyOrder(item, amount));
        log("enqueue " + item + " x" + amount + " (totems now=" + countItem(Items.TOTEM_OF_UNDYING) + ")");

        currentOrder = queue.poll();
        actionTicks = 0;
        waitTicks = 0;
        set11Done = false;
        retryCount = 0;
        staleContainerId = currentContainerId();
        sendShopCommand();
        state = ShopState.WAIT_SHOP;
    }

    public synchronized void buyTotemNow() {
        if (!shopOpenPossible()) return;

        if (state == ShopState.IDLE) { enqueue(ShopItem.Totem, 3); return; }
        if (currentOrder != null && currentOrder.item == ShopItem.Totem) {
            currentOrder.remaining = Math.min(currentOrder.remaining + 1, 5);
            log("buyTotemNow: running totem order -> remaining=" + currentOrder.remaining);
            return;
        }
        BuyOrder first = queue.peekFirst();
        if (first != null && first.item == ShopItem.Totem) {
            first.remaining = Math.min(first.remaining + 1, 5);
            log("buyTotemNow: queued totem order -> remaining=" + first.remaining);
            return;
        }

        info("Buying totem...");
        lastWasTotemOrder = true;
        BuyOrder order = new BuyOrder(ShopItem.Totem, 3);
        if (state != ShopState.WAIT_SHOP && state != ShopState.CLICK_ITEM) {
            queue.addFirst(order);
            preemptFlag = true;
            log("buyTotemNow: preempting " + (currentOrder != null ? currentOrder.item : null));
        } else {
            if (currentOrder != null) queue.addFirst(currentOrder);
            currentOrder = order;
            set11Done = false;
        }
    }

    private void handleActive(long now) {
        if (state == ShopState.WAIT_SHOP) {
            if (!shopWindowChanged()) {
                if (++waitTicks < 10) return;
                waitTicks = 0;
                boolean sameWorld = (mc.world == lastWorldForLock);
                retryCount++;
                log("shop not open after 10 ticks, retry #" + retryCount + " (open window=" + currentContainerId() + ", stale=" + staleContainerId + ")");
                if (retryCount > (sameWorld ? 5 : 1)) {
                    if (!sameWorld) {
                        lastWorldForLock = mc.world;
                        lockUntil = now + 30000L;
                    }
                    abort("Shop GUI never opened after 5 tries (server refused /shop? e.g. combat tag, check chat).");
                    return;
                }
                closeContainer();
                staleContainerId = currentContainerId();
                sendShopCommand();
                return;
            }
            log("shop window open id=" + currentContainerId());
            state = ShopState.CLICK_ITEM;
            waitTicks = 0;
            actionTicks = 0;
            if (actionDelay.get() > 0) return;
        }

        if (state == ShopState.WAIT_PAGE) {
            if (!shopWindowChanged()) {
                if (++waitTicks < 10) return;
                waitTicks = 0;
                retryCount++;
                log("item page not open after 10 ticks, retry #" + retryCount + " (open window=" + currentContainerId() + ", stale=" + staleContainerId + ")");
                if (retryCount > 5) {
                    mc.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
                    abort("Item page never opened after 5 tries.");
                    return;
                }
                mc.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
                closeContainer();
                staleContainerId = currentContainerId();
                sendShopCommand();
                state = ShopState.WAIT_SHOP;
                return;
            }
            log("item page open id=" + currentContainerId());
            state = ShopState.DECIDE;
            waitTicks = 0;
            actionTicks = 0;
            if (actionDelay.get() > 0) return;
        }

        if (state != ShopState.DECIDE) {
            if (actionTicks < actionDelay.get()) { actionTicks++; return; }
        }
        actionTicks = 0;

        boolean containerOpen = mc.player.currentScreenHandler != mc.player.playerScreenHandler;
        if (state != ShopState.WAIT_SHOP && state != ShopState.WAIT_PAGE && !containerOpen) {
            abort("Shop GUI closed unexpectedly.");
            return;
        }

        if (preemptFlag && (state == ShopState.SET_11 || state == ShopState.CONFIRM)) {
            state = ShopState.DECIDE;
        }

        ShopState prev = state;
        switch (state) {
            case CLICK_ITEM -> handleClickItem();
            case DECIDE -> handleDecide(now);
            case SET_11 -> handleSet11();
            case CONFIRM -> handleConfirm(now);
            default -> {}
        }

        if (prev == ShopState.DECIDE && actionDelay.get() == 0
            && (state == ShopState.CONFIRM || state == ShopState.SET_11)) {
            handleShopState(System.currentTimeMillis());
        }
    }

    private void handleClickItem() {
        if (currentOrder == null) { state = ShopState.DECIDE; return; }
        int slot = switch (currentOrder.item) {
            case Totem -> SLOT_TOTEM;
            case Crystal -> SLOT_CRYSTAL;
            case Exp -> SLOT_EXP;
            case Obsidian -> SLOT_OBSIDIAN;
            case EnderPearl -> SLOT_ENDERPEARL;
        };
        if (mc.player.currentScreenHandler.getSlot(slot).getStack().isEmpty()) {
            if (++waitTicks < 10) return;
        }
        staleContainerId = currentContainerId();
        clickSlot(slot);
        waitTicks = 0;
        state = ShopState.WAIT_PAGE;
    }

    private void handleDecide(long now) {
        if (preemptFlag) {
            preemptFlag = false;
            if (currentOrder != null && currentOrder.remaining > 0) {
                BuyOrder polled = queue.pollFirst();
                if (currentOrder != null) queue.addFirst(currentOrder);
                if (polled != null) queue.addFirst(polled);
                currentOrder = null;
            }
        }

        if (currentOrder != null && lastConfirmTime != 0) {
            if (now - lastConfirmTime < 500L) {
                if (currentOrder.remaining <= 0 && countItem(itemOf(currentOrder.item)) <= preClickItemCount) return;
                if (currentOrder.remaining > 0 && mc.player.currentScreenHandler.getRevision() == lastStateId) return;
            }
        }

        if (currentOrder == null || currentOrder.remaining <= 0) {
            if (!queue.isEmpty()) {
                currentOrder = queue.poll();
                lastConfirmTime = 0;
                set11Done = false;
                retryCount = 0;
                waitTicks = 0;
                staleContainerId = currentContainerId();
                sendShopCommand();
                state = ShopState.WAIT_SHOP;
                return;
            }
            finish();
            return;
        }

        if (!hasFreeOrPartialSlot(itemOf(currentOrder.item))) { abort("Inventory full."); return; }

        if (currentOrder.item == ShopItem.Totem) state = ShopState.CONFIRM;
        else if (!set11Done) state = ShopState.SET_11;
        else state = ShopState.CONFIRM;
    }

    private void handleSet11() {
        int slot = (currentOrder != null && currentOrder.item == ShopItem.EnderPearl) ? SLOT_EP_SET11 : SLOT_SET11;
        clickSlot(slot);
        set11Done = true;
        state = ShopState.CONFIRM;
    }

    private void handleConfirm(long now) {
        if (mc.player.currentScreenHandler.getSlot(SLOT_CONFIRM).getStack().isEmpty()) {
            if (++waitTicks < 10) return;
        }
        waitTicks = 0;
        if (currentOrder != null) preClickItemCount = countItem(itemOf(currentOrder.item));
        clickSlot(SLOT_CONFIRM);
        lastConfirmTime = now;
        lastStateId = mc.player.currentScreenHandler.getRevision();
        if (currentOrder != null) {
            if (currentOrder.item == ShopItem.Totem) lastWasTotemOrder = true;
            currentOrder.remaining--;
        }
        state = ShopState.DECIDE;
    }

    private void finish() {
        int totems = mc.player != null ? countItem(Items.TOTEM_OF_UNDYING) : -1;
        log("finish (totems now=" + totems + ", " + (System.currentTimeMillis() - lastConfirmTime) + "ms after last CONFIRM)");
        lastConfirmTime = 0;
        boolean wasTotem = lastWasTotemOrder;
        lastWasTotemOrder = false;
        preemptFlag = false;
        state = ShopState.IDLE;
        queue.clear();
        currentOrder = null;
        if (mc.player != null) closeContainer();

        if (wasTotem && invNotFull()) {
            if (Math.random() < 0.5) {
                throwAllTotemsInInventory();
                globalLockUntil = System.currentTimeMillis() + LOCK_MS;
            }
        }
    }

    private void abort(String reason) {
        log("ABORT: " + reason);
        lastConfirmTime = 0;
        lastWasTotemOrder = false;
        preemptFlag = false;
        state = ShopState.IDLE;
        queue.clear();
        currentOrder = null;
        if (mc.player != null) closeContainer();
    }

    public boolean isRunning() { return state != ShopState.IDLE; }
    public boolean isInOrder() { return state != ShopState.IDLE || !queue.isEmpty(); }

    private boolean isOnKingMC() {
        if (mc.isInSingleplayer() || mc.getCurrentServerEntry() == null) return false;
        String host = ServerAddress.parse(mc.getCurrentServerEntry().address).getHost().toLowerCase(Locale.ROOT);
        return ALLOWED_HOSTS.contains(host);
    }

    private boolean shopPossible() {
        if (!isOnKingMC()) return false;
        if (lastWorldForLock == mc.world && System.currentTimeMillis() < lockUntil) return false;
        return true;
    }

    private boolean shopOpenPossible() {
        if (!shopPossible()) return false;
        if (mc.world == null) return false;
        Scoreboard sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return false;
        for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
            Team team = sb.getScoreHolderTeam(entry.owner());
            String line = Team.decorateName(team, entry.name()).getString();
            if (line.contains("/dk ") || line.contains("/dn ")) return true;
        }
        return false;
    }

    private boolean shouldManageHotbar() {
        if (!isActive() || mc.player == null) return false;
        return !isRunning();
    }

    private boolean isScreenSafeToBuy() {
        Screen s = mc.currentScreen;
        if (s == null) return true;
        if (silent.get() && !(s instanceof HandledScreen)) return true;
        return false;
    }

    private int currentContainerId() {
        return mc.player.currentScreenHandler != mc.player.playerScreenHandler ? mc.player.currentScreenHandler.syncId : -1;
    }

    private boolean shopWindowChanged() {
        int id = currentContainerId();
        return id != -1 && id != staleContainerId;
    }

    private void sendShopCommand() {
        if (mc.getNetworkHandler() != null) {
            log("send /shop open gear");
            mc.getNetworkHandler().sendChatCommand("shop open gear");
        }
    }

    private void closeContainer() {
        if (mc.player == null) return;
        Screen s = mc.currentScreen;
        if (s != null && !(s instanceof HandledScreen)) {
            ClientPlayNetworkHandler conn = mc.getNetworkHandler();
            if (conn != null) conn.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
            mc.player.currentScreenHandler = mc.player.playerScreenHandler;
        } else {
            mc.player.closeHandledScreen();
        }
    }

    private void clickSlot(int slot) {
        if (debug.get()) {
            ScreenHandler m = mc.player.currentScreenHandler;
            String name = slot < m.slots.size() ? m.getSlot(slot).getStack().getName().getString() : "?";
            log("click slot " + slot + " [" + name + "] window=" + m.syncId + " stateId=" + m.getRevision());
        }
        if (mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private int countItem(Item item) {
        int n = 0;
        if (mc.player.getOffHandStack().isOf(item)) n += mc.player.getOffHandStack().getCount();
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i <= 35; i++) if (inv.getStack(i).isOf(item)) n += inv.getStack(i).getCount();
        return n;
    }

    private boolean hasFreeOrPartialSlot(Item item) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i <= 35; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) return true;
            if (st.isOf(item)) {
                int cap = st.getMaxCount() == 64 ? 55 : (st.getMaxCount() == 16 ? 11 : st.getMaxCount());
                if (st.getCount() < cap) return true;
            }
        }
        return false;
    }

    private boolean invNotFull() {
        try { return invUsedFraction() <= 0.95; }
        catch (Throwable t) { return true; }
    }

    private double invUsedFraction() {
        PlayerInventory inv = mc.player.getInventory();
        int used = 0;
        for (int i = 0; i <= 35; i++) if (!inv.getStack(i).isEmpty()) used++;
        return (double) used / 36.0;
    }

    private void throwAllTotemsInInventory() {
        if (mc.player == null || mc.interactionManager == null) return;
        int id = mc.player.playerScreenHandler.syncId;
        for (Slot s : mc.player.playerScreenHandler.slots) {
            if (s != null && s.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
                mc.interactionManager.clickSlot(id, s.id, 1, SlotActionType.THROW, mc.player);
            }
        }
    }

    private static Item itemOf(ShopItem item) {
        return switch (item) {
            case Totem -> Items.TOTEM_OF_UNDYING;
            case Crystal -> Items.END_CRYSTAL;
            case Exp -> Items.EXPERIENCE_BOTTLE;
            case Obsidian -> Items.OBSIDIAN;
            case EnderPearl -> Items.ENDER_PEARL;
        };
    }

    private int hotbarTotemCount() {
        int count = 0;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = HOTBAR_FIRST; i <= HOTBAR_LAST; i++) if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) count++;
        return count;
    }

    private int findItemSlot(Item item, int from, int to) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = from; i <= to; i++) if (inv.getStack(i).isOf(item)) return i;
        return -1;
    }

    private int findEmptySlot(int from, int to) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = from; i <= to; i++) if (inv.getStack(i).isEmpty()) return i;
        return -1;
    }

    private int toHandlerSlot(int inventoryIndex) {
        ScreenHandler menu = mc.player.currentScreenHandler;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.inventory == mc.player.getInventory() && s.getIndex() == inventoryIndex) return i;
        }
        return -1;
    }

    private void selectHotbarSlot(int hotbarIndex) {
        mc.player.getInventory().selectedSlot = hotbarIndex;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarIndex));
        }
    }

    private void swapSlots(int inventoryA, int inventoryB) {
        if (mc.interactionManager == null) return;
        int id = mc.player.currentScreenHandler.syncId;
        int a = toHandlerSlot(inventoryA);
        int b = toHandlerSlot(inventoryB);
        if (a < 0 || b < 0) return;
        mc.interactionManager.clickSlot(id, a, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(id, b, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(id, a, 0, SlotActionType.PICKUP, mc.player);
    }

    private void log(String msg) {
        if (debug.get()) {
            long now = System.currentTimeMillis() % 100000L;
            TrongNhanAddon.LOG.info("[AutoTotemModule] " + now + " [" + state + "] " + msg);
        }
    }

    private void warn(ShopItem item, String msg) {
        String m = "Can't buy " + item.name().toLowerCase() + ": " + msg;
        if (!m.equals(lastErrorMessage)) lastErrorMessage = m;
    }

    @Override
    public String getInfoString() {
        if (currentOrder != null) {
            String extra = queue.isEmpty() ? "" : " (+" + queue.size() + ")";
            return currentOrder.item.name() + " " + currentOrder.remaining + extra;
        }
        return null;
    }

    public static class BuyOrder {
        public final ShopItem item;
        public int remaining;
        public BuyOrder(ShopItem item, int remaining) { this.item = item; this.remaining = remaining; }
    }

    public enum ShopItem { Totem, Crystal, Exp, Obsidian, EnderPearl }

    private enum ShopState { IDLE, WAIT_SHOP, CLICK_ITEM, WAIT_PAGE, DECIDE, SET_11, CONFIRM }
}
