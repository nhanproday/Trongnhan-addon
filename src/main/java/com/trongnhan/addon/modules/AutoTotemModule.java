package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

import java.util.Locale;
import java.util.Set;

public class AutoTotemModule extends Module {
    private static AutoTotemModule activeInstance;

    private final SettingGroup sgOffhand = settings.createGroup("Offhand");
    private final SettingGroup sgMainhand = settings.createGroup("Mainhand");
    private final SettingGroup sgAutoBuy = settings.createGroup("Auto Buy");

    private final Setting<Integer> delay = sgOffhand.add(new IntSetting.Builder()
        .name("offhand-delay").defaultValue(0).min(0).sliderMax(20).build());

    private final Setting<Boolean> yieldMeteorOffhand = sgOffhand.add(new BoolSetting.Builder()
        .name("yield-meteor-offhand").defaultValue(true).build());

    private final Setting<Boolean> mainhandEnabled = sgMainhand.add(new BoolSetting.Builder()
        .name("mainhand-enabled").defaultValue(true).build());

    private final Setting<Integer> mainhandSlotMin = sgMainhand.add(new IntSetting.Builder()
        .name("mainhand-slot-min").defaultValue(1).min(1).sliderMax(9)
        .visible(mainhandEnabled::get).build());

    private final Setting<Integer> mainhandSlotMax = sgMainhand.add(new IntSetting.Builder()
        .name("mainhand-slot-max").defaultValue(3).min(1).sliderMax(9)
        .visible(mainhandEnabled::get).build());

    private final Setting<Boolean> autoBuyEnabled = sgAutoBuy.add(new BoolSetting.Builder()
        .name("auto-buy-enabled").defaultValue(true).build());

    private final Setting<Boolean> autoBuySilent = sgAutoBuy.add(new BoolSetting.Builder()
        .name("auto-buy-silent").defaultValue(true)
        .visible(autoBuyEnabled::get).build());

    private final Setting<Integer> autoBuyDelay = sgAutoBuy.add(new IntSetting.Builder()
        .name("auto-buy-delay").defaultValue(0).min(0).sliderMax(20)
        .visible(() -> autoBuyEnabled.get() && autoBuySilent.get()).build());

    private final Setting<Integer> autoBuyMin = sgAutoBuy.add(new IntSetting.Builder()
        .name("auto-buy-min").defaultValue(3).min(1).sliderMax(36)
        .visible(autoBuyEnabled::get).build());

    private final Setting<Integer> autoBuyMax = sgAutoBuy.add(new IntSetting.Builder()
        .name("auto-buy-max").defaultValue(10).min(1).sliderMax(36)
        .visible(autoBuyEnabled::get).build());

    private final Setting<Integer> autoBuyAmount = sgAutoBuy.add(new IntSetting.Builder()
        .name("auto-buy-amount").defaultValue(1).min(1).sliderMax(10)
        .visible(autoBuyEnabled::get).build());

    private final Setting<Boolean> debugLog = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("debug").defaultValue(false).build());

    private static final Set<String> ALLOWED_HOSTS = Set.of("java.kingmc.vn", "kingmc.vn");

    private int cooldown;
    private SwapPhase swapPhase = SwapPhase.IDLE;
    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;
    private boolean swapQueued;
    private boolean urgentPop;

    private int lastMainhandSlot = -1;
    private boolean mainhandWaitNextPop;

    private ShopState shopState = ShopState.IDLE;
    private int shopRetry;
    private int shopStaleContainerId = -1;
    private int shopWaitTicks;
    private int shopActionTicks;
    private long shopLastConfirmMs;
    private int shopLastStateId = -1;

    private static final int SHOP_SLOT_TOTEM = 13;
    private static final int SHOP_SLOT_CONFIRM = 23;
    private static final int HOTBAR_FIRST = 0;
    private static final int HOTBAR_LAST = 8;
    private static final int INV_FIRST = 9;
    private static final int INV_LAST = 35;

    public AutoTotemModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem",
            "Offhand + mainhand totem + auto buy totem (KingMC).");
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        cooldown = 0;
        swapPhase = SwapPhase.IDLE;
        swapQueued = false;
        urgentPop = false;
        neutralPlayer = null;
        lastMainhandSlot = -1;
        mainhandWaitNextPop = false;
        shopState = ShopState.IDLE;
        shopRetry = 0;
        shopStaleContainerId = -1;
        shopWaitTicks = 0;
        shopActionTicks = 0;
        shopLastConfirmMs = 0L;
        shopLastStateId = -1;
        restoreNeutralInput();
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        if (activeInstance == this) activeInstance = null;
        cooldown = 0;
        swapPhase = SwapPhase.IDLE;
        swapQueued = false;
        urgentPop = false;
        neutralPlayer = null;
        lastMainhandSlot = -1;
        mainhandWaitNextPop = false;
        shopState = ShopState.IDLE;
        restoreNeutralInput();
        super.onDeactivate();
    }

    @EventHandler(priority = -10000)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (swapPhase == SwapPhase.NEUTRAL_ARMED) ensureNeutralInput();
    }

    @EventHandler(priority = 10000)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (swapPhase != SwapPhase.NEUTRAL_APPLIED) return;
        boolean safe = hasSafeInventoryClickInput()
            && mc.player == neutralPlayer
            && mc.player != null
            && mc.player.input == neutralInput;
        restoreNeutralInput();
        neutralPlayer = null;
        swapPhase = safe ? SwapPhase.NEUTRAL_READY : SwapPhase.IDLE;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != 35) return;
        if (mc.world == null || mc.player == null) return;
        if (packet.getEntity(mc.world) != mc.player) return;
        if (!isActive()) return;

        if (yieldMeteorOffhand.get() && isMeteorOffhandActive()) return;

        urgentPop = true;
        swapQueued = false;
        cooldown = 0;

        handleMainhandPop();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null
            || mc.world == null
            || mc.interactionManager == null
            || mc.player.isCreative()
            || mc.player.isSpectator()) {
            resetPendingState();
            return;
        }

        if (yieldMeteorOffhand.get() && isMeteorOffhandActive()) return;

        tickOffhand();
        tickMainhand();
        tickAutoBuy();
    }

    private void tickOffhand() {
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            urgentPop = false;
            swapQueued = false;
            swapPhase = SwapPhase.IDLE;
            return;
        }

        if (cooldown > 0 && !urgentPop) {
            cooldown--;
            return;
        }

        if (urgentPop) cooldown = 0;
        if (swapQueued) return;

        int totemIndex = findTotemInInventory();
        if (totemIndex < 0) return;

        if (swapPhase == SwapPhase.NEUTRAL_READY) {
            if (swapTotemDirectlyToOffhand(totemIndex)) {
                swapQueued = true;
                urgentPop = false;
                cooldown = delay.get();
            }
            swapPhase = SwapPhase.IDLE;
            return;
        }

        if (swapPhase == SwapPhase.NEUTRAL_ARMED || swapPhase == SwapPhase.NEUTRAL_APPLIED) return;

        swapPhase = SwapPhase.NEUTRAL_ARMED;
    }

    private void tickMainhand() {
        if (!mainhandEnabled.get()) return;
        if (mc.player.isUsingItem()) return;
        if (mc.options.useKey.isPressed()) return;

        int min = Math.min(mainhandSlotMin.get(), mainhandSlotMax.get()) - 1;
        int max = Math.max(mainhandSlotMin.get(), mainhandSlotMax.get()) - 1;

        int currentSel = mc.player.getInventory().getSelectedSlot();
        PlayerInventory inv = mc.player.getInventory();

        if (currentSel < min || currentSel > max) {
            int target = findLowestTotemSlotInRange(min, max);
            if (target >= 0) {
                selectHotbarSlot(target);
                lastMainhandSlot = target;
            }
            return;
        }

        if (inv.getStack(currentSel).isOf(Items.TOTEM_OF_UNDYING)) {
            lastMainhandSlot = currentSel;
            return;
        }

        if (mainhandWaitNextPop) {
            int nextSlot = findNextTotemSlotInRange(currentSel, min, max);
            if (nextSlot >= 0) {
                selectHotbarSlot(nextSlot);
                lastMainhandSlot = nextSlot;
                mainhandWaitNextPop = false;
                return;
            }
        }

        int refillTarget = (lastMainhandSlot >= min && lastMainhandSlot <= max)
            ? lastMainhandSlot : min;
        if (refillFromInventory(refillTarget)) {
            selectHotbarSlot(refillTarget);
            lastMainhandSlot = refillTarget;
        } else {
            int anyTotem = findLowestTotemSlotInRange(min, max);
            if (anyTotem >= 0) {
                selectHotbarSlot(anyTotem);
                lastMainhandSlot = anyTotem;
            }
        }
        mainhandWaitNextPop = false;
    }

    private void handleMainhandPop() {
        if (!mainhandEnabled.get()) return;
        int min = Math.min(mainhandSlotMin.get(), mainhandSlotMax.get()) - 1;
        int max = Math.max(mainhandSlotMin.get(), mainhandSlotMax.get()) - 1;
        int currentSel = mc.player.getInventory().getSelectedSlot();
        if (currentSel < min || currentSel > max) return;

        PlayerInventory inv = mc.player.getInventory();
        boolean stillHasTotem = inv.getStack(currentSel).isOf(Items.TOTEM_OF_UNDYING);

        int nextSlot = findNextTotemSlotInRange(currentSel, min, max);

        if (nextSlot >= 0 && !stillHasTotem) {
            mainhandWaitNextPop = true;
        } else if (nextSlot < 0) {
            mainhandWaitNextPop = false;
        }
    }

    private int findLowestTotemSlotInRange(int min, int max) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = min; i <= max; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findNextTotemSlotInRange(int current, int min, int max) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = current + 1; i <= max; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        for (int i = min; i < current; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private boolean refillFromInventory(int targetHotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        int srcInv = findTotemInInventory();
        if (srcInv < 0) return false;

        int dstHandler = toHandlerSlot(targetHotbarSlot);
        int srcHandler = toHandlerSlot(srcInv);
        if (dstHandler < 0 || srcHandler < 0) return false;

        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            srcHandler, dstHandler,
            SlotActionType.SWAP, mc.player);
        return true;
    }

    private int findTotemInInventory() {
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = INV_FIRST; i <= INV_LAST; i++) {
            if (inventory.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private boolean swapTotemDirectlyToOffhand(int inventoryIndex) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (inventoryIndex < INV_FIRST || inventoryIndex > INV_LAST) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory()) return false;
        if (source.getIndex() != inventoryIndex) return false;
        if (!source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        mc.interactionManager.clickSlot(handler.syncId, handlerSlot, 40, SlotActionType.SWAP, mc.player);
        return true;
    }

    private void tickAutoBuy() {
        if (!autoBuyEnabled.get()) return;
        if (!isOnKingMC()) return;
        if (mc.player.currentScreenHandler.getCursorStack().isEmpty() == false) {
            if (mc.interactionManager != null)
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, -999, 0, SlotActionType.PICKUP, mc.player);
            return;
        }

        int total = countTotems();
        int min = autoBuyMin.get();
        int max = autoBuyMax.get();

        if (total > max) {
            throwExcessTotems(max);
            return;
        }

        if (shopState == ShopState.IDLE) {
            if (total < min && shopPossible()) startShopBuy();
        } else {
            handleShop();
        }
    }

    private void startShopBuy() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        shopRetry = 0;
        shopStaleContainerId = currentContainerId();
        shopWaitTicks = 0;
        shopActionTicks = 0;
        shopLastConfirmMs = 0L;
        shopLastStateId = -1;
        sendShopCommand();
        shopState = ShopState.WAIT_SHOP;
    }

    private void handleShop() {
        long now = System.currentTimeMillis();

        if (shopState == ShopState.WAIT_SHOP) {
            if (!shopWindowChanged()) {
                if (++shopWaitTicks < 10) return;
                shopWaitTicks = 0;
                shopRetry++;
                if (shopRetry > 5) {
                    abortShop("shop never opened");
                    return;
                }
                closeContainer();
                shopStaleContainerId = currentContainerId();
                sendShopCommand();
                return;
            }
            shopState = ShopState.CLICK_ITEM;
            shopWaitTicks = 0;
            shopActionTicks = 0;
            if (shopActionTicks < 2) return;
        }

        if (shopState == ShopState.WAIT_PAGE) {
            if (!shopWindowChanged()) {
                if (++shopWaitTicks < 10) return;
                shopWaitTicks = 0;
                shopRetry++;
                if (shopRetry > 5) { abortShop("page never opened"); return; }
                mc.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
                closeContainer();
                shopStaleContainerId = currentContainerId();
                sendShopCommand();
                shopState = ShopState.WAIT_SHOP;
                return;
            }
            shopState = ShopState.CONFIRM;
            shopWaitTicks = 0;
            shopActionTicks = 0;
        }

        if (shopState == ShopState.CLICK_ITEM) {
            if (shopActionTicks < autoBuyDelay.get()) { shopActionTicks++; return; }
            shopActionTicks = 0;
            if (mc.player.currentScreenHandler.getSlot(SHOP_SLOT_TOTEM).getStack().isEmpty()) {
                if (++shopWaitTicks < 10) return;
            }
            shopStaleContainerId = currentContainerId();
            clickSlot(SHOP_SLOT_TOTEM);
            shopWaitTicks = 0;
            shopState = ShopState.WAIT_PAGE;
            return;
        }

        if (shopState == ShopState.CONFIRM) {
            if (shopActionTicks < autoBuyDelay.get()) { shopActionTicks++; return; }
            shopActionTicks = 0;
            if (shopLastConfirmMs != 0 && now - shopLastConfirmMs < 500L) {
                if (mc.player.currentScreenHandler.getRevision() == shopLastStateId) return;
            }
            if (mc.player.currentScreenHandler.getSlot(SHOP_SLOT_CONFIRM).getStack().isEmpty()) {
                if (++shopWaitTicks < 10) return;
            }
            shopWaitTicks = 0;
            clickSlot(SHOP_SLOT_CONFIRM);
            shopLastConfirmMs = now;
            shopLastStateId = mc.player.currentScreenHandler.getRevision();

            int total = countTotems();
            int max = autoBuyMax.get();
            int min = autoBuyMin.get();
            if (total >= max || total >= min) {
                finishShop();
            } else {
                shopState = ShopState.WAIT_SHOP;
                shopStaleContainerId = currentContainerId();
                sendShopCommand();
            }
        }
    }

    private void finishShop() {
        shopState = ShopState.IDLE;
        if (mc.player != null) closeContainer();
    }

    private void abortShop(String reason) {
        if (debugLog.get()) TrongNhanAddon.LOG.info("[AutoTotem] ABORT: " + reason);
        shopState = ShopState.IDLE;
        if (mc.player != null) closeContainer();
    }

    private int countTotems() {
        int n = 0;
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) n += mc.player.getOffHandStack().getCount();
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i <= 35; i++) if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) n += inv.getStack(i).getCount();
        return n;
    }

    private void throwExcessTotems(int max) {
        if (mc.player == null || mc.interactionManager == null) return;
        int total = countTotems();
        if (total <= max) return;

        int toThrow = total - max;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = INV_LAST; i >= INV_FIRST && toThrow > 0; i--) {
            ItemStack st = inv.getStack(i);
            if (st.isOf(Items.TOTEM_OF_UNDYING)) {
                int slot = toHandlerSlot(i);
                if (slot >= 0) {
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 1, SlotActionType.THROW, mc.player);
                    toThrow -= st.getCount();
                }
            }
        }
    }

    private boolean isOnKingMC() {
        if (mc.isInSingleplayer() || mc.getCurrentServerEntry() == null) return false;
        String host = ServerAddress.parse(mc.getCurrentServerEntry().address).getAddress().toLowerCase(Locale.ROOT);
        return ALLOWED_HOSTS.contains(host);
    }

    private boolean shopPossible() {
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

    private int currentContainerId() {
        return mc.player.currentScreenHandler != mc.player.playerScreenHandler ? mc.player.currentScreenHandler.syncId : -1;
    }

    private boolean shopWindowChanged() {
        int id = currentContainerId();
        return id != -1 && id != shopStaleContainerId;
    }

    private void sendShopCommand() {
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand("shop open gear");
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
        if (mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private void selectHotbarSlot(int hotbarIndex) {
        mc.player.getInventory().setSelectedSlot(hotbarIndex);
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarIndex));
    }

    private int toHandlerSlot(int inventoryIndex) {
        ScreenHandler menu = mc.player.currentScreenHandler;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.inventory == mc.player.getInventory() && s.getIndex() == inventoryIndex) return i;
        }
        return -1;
    }

    private void ensureNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.hasVehicle()) return;
        if (neutralInput != null && player.input == neutralInput) return;
        neutralInput = new NeutralInput(player.input);
        player.input = neutralInput;
    }

    private void restoreNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player != null && neutralInput != null && player.input == neutralInput) {
            player.input = neutralInput.delegate;
        }
        neutralInput = null;
    }

    private boolean hasSafeInventoryClickInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.isSprinting()) return false;
        Input input = player.input;
        if (input == null) return true;
        Vec2f movement = input.getMovementInput();
        if (movement != null && movement.lengthSquared() > 1.0E-5f) return false;
        PlayerInput playerInput = input.playerInput;
        return playerInput == null
            || !(playerInput.forward() || playerInput.backward() || playerInput.left() || playerInput.right());
    }

    private static int resolvePlayerHandlerSlot(ScreenHandler handler, int playerInventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventoryIndex) return i;
        }
        return -1;
    }

    private static boolean isMeteorOffhandActive() {
        Module offhand = meteordevelopment.meteorclient.systems.modules.Modules.get()
            .get(meteordevelopment.meteorclient.systems.modules.combat.Offhand.class);
        return offhand != null && offhand.isActive();
    }

    private void resetPendingState() {
        cooldown = 0;
        swapPhase = SwapPhase.IDLE;
        swapQueued = false;
        urgentPop = false;
        neutralPlayer = null;
        restoreNeutralInput();
    }

    private enum SwapPhase { IDLE, NEUTRAL_ARMED, NEUTRAL_APPLIED, NEUTRAL_READY }
    private enum ShopState { IDLE, WAIT_SHOP, CLICK_ITEM, WAIT_PAGE, CONFIRM }

    private final class NeutralInput extends Input {
        private final Input delegate;
        private NeutralInput(Input delegate) {
            this.delegate = delegate;
            setState(delegate.playerInput, delegate.getMovementInput());
        }
        @Override
        public void tick() {
            delegate.tick();
            PlayerInput original = delegate.playerInput == null ? PlayerInput.DEFAULT : delegate.playerInput;
            setState(original, delegate.getMovementInput());
            if (swapPhase != SwapPhase.NEUTRAL_ARMED || mc.player == null || mc.player.input != this) return;
            PlayerInput neutral = new PlayerInput(false, false, false, false, false, original.sneak(), false);
            setState(neutral, Vec2f.ZERO);
            mc.player.setSprinting(false);
            neutralPlayer = mc.player;
            swapPhase = SwapPhase.NEUTRAL_APPLIED;
        }
        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }
}
