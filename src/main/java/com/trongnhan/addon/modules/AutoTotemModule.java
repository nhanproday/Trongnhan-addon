package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

public class AutoTotemModule extends Module {
    private static AutoTotemModule activeInstance;

    private final SettingGroup sgOffhand = settings.createGroup("Offhand");
    private final SettingGroup sgMainhand = settings.createGroup("Mainhand");

    private final Setting<Integer> delay = sgOffhand.add(new IntSetting.Builder()
        .name("offhand-delay").defaultValue(0).min(0).sliderMax(20).build());

    private final Setting<Boolean> yieldMeteorOffhand = sgOffhand.add(new BoolSetting.Builder()
        .name("yield-meteor-offhand").defaultValue(true).build());

    private final Setting<Boolean> mainhandEnabled = sgMainhand.add(new BoolSetting.Builder()
        .name("mainhand-enabled").defaultValue(true).build());

    private final Setting<Double> mainhandHealth = sgMainhand.add(new DoubleSetting.Builder()
        .name("mainhand-health").defaultValue(10.0).min(1.0).sliderMax(20.0)
        .visible(mainhandEnabled::get).build());

    private final Setting<Integer> mainhandSlotMin = sgMainhand.add(new IntSetting.Builder()
        .name("mainhand-slot-min").defaultValue(1).min(1).sliderMax(9)
        .visible(mainhandEnabled::get).build());

    private final Setting<Integer> mainhandSlotMax = sgMainhand.add(new IntSetting.Builder()
        .name("mainhand-slot-max").defaultValue(5).min(1).sliderMax(9)
        .visible(mainhandEnabled::get).build());

    private final Setting<Integer> mainhandRefillDelay = sgMainhand.add(new IntSetting.Builder()
        .name("mainhand-refill-delay").description("Seconds each empty hotbar slot must wait before refilling from inventory.")
        .defaultValue(1).min(0).sliderMax(10)
        .visible(mainhandEnabled::get).build());

    private static final int HOTBAR_FIRST = 0;
    private static final int HOTBAR_LAST = 8;
    private static final int INV_FIRST = 9;
    private static final int INV_LAST = 35;

    private int cooldown;
    private SwapPhase swapPhase = SwapPhase.IDLE;

    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;

    private boolean swapQueued;
    private boolean urgentPop;

    private int lastMainhandSlot = -1;

    private final long[] slotRefillAtMs = new long[9];
    private final boolean[] slotHadTotem = new boolean[9];

    public AutoTotemModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem",
            "Offhand + mainhand totem swap.");
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
        for (int i = 0; i < 9; i++) {
            slotRefillAtMs[i] = 0L;
            slotHadTotem[i] = false;
        }
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
        for (int i = 0; i < 9; i++) {
            slotRefillAtMs[i] = 0L;
            slotHadTotem[i] = false;
        }
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

        int min = Math.min(mainhandSlotMin.get(), mainhandSlotMax.get()) - 1;
        int max = Math.max(mainhandSlotMin.get(), mainhandSlotMax.get()) - 1;

        PlayerInventory inv = mc.player.getInventory();
        long now = System.currentTimeMillis();
        long delayMs = (long) mainhandRefillDelay.get() * 1000L;

        updateSlotTimers(inv, min, max, now, delayMs);

        if (mc.player.getHealth() > mainhandHealth.get().floatValue()) return;
        if (mc.player.isUsingItem()) return;
        if (mc.options.useKey.isPressed()) return;

        // Try refill all expired empty slots first
        for (int i = min; i <= max; i++) {
            if (!inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING) && slotRefillAtMs[i] != 0L && now >= slotRefillAtMs[i]) {
                if (refillFromInventory(i)) {
                    slotRefillAtMs[i] = 0L;
                    slotHadTotem[i] = true;
                }
            }
        }

        int currentSel = inv.getSelectedSlot();

        if (currentSel < min || currentSel > max) {
            int target = findLowestTotemSlotInRange(inv, min, max);
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

        int nextSlot = findNextTotemSlotInRange(inv, currentSel, min, max);
        if (nextSlot >= 0) {
            selectHotbarSlot(nextSlot);
            lastMainhandSlot = nextSlot;
            return;
        }

        if (refillFromInventory(currentSel)) {
            selectHotbarSlot(currentSel);
            lastMainhandSlot = currentSel;
            slotRefillAtMs[currentSel] = 0L;
            slotHadTotem[currentSel] = true;
        } else {
            int anyTotem = findLowestTotemSlotInRange(inv, min, max);
            if (anyTotem >= 0) {
                selectHotbarSlot(anyTotem);
                lastMainhandSlot = anyTotem;
            }
        }
    }

    private void updateSlotTimers(PlayerInventory inv, int min, int max, long now, long delayMs) {
        for (int i = min; i <= max; i++) {
            boolean hasTotem = inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING);
            if (hasTotem) {
                slotHadTotem[i] = true;
                slotRefillAtMs[i] = 0L;
            } else {
                if (slotHadTotem[i] && slotRefillAtMs[i] == 0L) {
                    slotRefillAtMs[i] = now + delayMs;
                }
                slotHadTotem[i] = false;
            }
        }
    }

    private int findLowestTotemSlotInRange(PlayerInventory inv, int min, int max) {
        for (int i = min; i <= max; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findNextTotemSlotInRange(PlayerInventory inv, int current, int min, int max) {
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

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int srcHandler = resolvePlayerHandlerSlot(handler, srcInv);
        if (srcHandler < 0 || srcHandler >= handler.slots.size()) return false;

        Slot source = handler.getSlot(srcHandler);
        if (source.inventory != mc.player.getInventory()) return false;
        if (source.getIndex() != srcInv) return false;
        if (!source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        mc.interactionManager.clickSlot(
            handler.syncId,
            srcHandler,
            targetHotbarSlot,
            SlotActionType.SWAP,
            mc.player);
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

    private void selectHotbarSlot(int hotbarIndex) {
        mc.player.getInventory().setSelectedSlot(hotbarIndex);
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarIndex));
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
