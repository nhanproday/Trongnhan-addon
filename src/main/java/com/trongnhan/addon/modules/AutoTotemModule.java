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
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

public class AutoTotem extends Module {
    private static AutoTotem activeInstance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("So tick cho giua moi lan swap sang offhand")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> yieldMeteorOffhand = sgGeneral.add(
        new BoolSetting.Builder()
            .name("yield-meteor-offhand")
            .description("Tu dong dung khi Meteor Offhand module dang active")
            .defaultValue(true)
            .build()
    );

    private int cooldown;
    private SwapPhase swapPhase = SwapPhase.IDLE;

    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;

    private boolean swapQueued;
    private boolean urgentPop;

    public AutoTotem() {
        super(
            KlangTemplate.PVP_CATEGORY,
            "auto-totem",
            "Automatically equips a totem directly from inventory into the offhand."
        );
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        cooldown = 0;
        swapPhase = SwapPhase.IDLE;
        swapQueued = false;
        urgentPop = false;
        neutralPlayer = null;

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

        restoreNeutralInput();
        super.onDeactivate();
    }

    @EventHandler(priority = -10000)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (swapPhase == SwapPhase.NEUTRAL_ARMED) {
            ensureNeutralInput();
        }
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

        swapPhase = safe
            ? SwapPhase.NEUTRAL_READY
            : SwapPhase.IDLE;
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

        int totemIndex = findTotemSlot();
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

        if (swapPhase == SwapPhase.NEUTRAL_ARMED
            || swapPhase == SwapPhase.NEUTRAL_APPLIED) {
            return;
        }

        swapPhase = SwapPhase.NEUTRAL_ARMED;
    }

    private boolean swapTotemDirectlyToOffhand(int inventoryIndex) {
        if (mc.player == null || mc.interactionManager == null) {
            return false;
        }

        if (inventoryIndex < 0 || inventoryIndex > 35) {
            return false;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);

        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) {
            return false;
        }

        Slot source = handler.getSlot(handlerSlot);

        if (source.inventory != mc.player.getInventory()) {
            return false;
        }

        if (source.getIndex() != inventoryIndex) {
            return false;
        }

        if (!source.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return false;
        }

        mc.interactionManager.clickSlot(
            handler.syncId,
            handlerSlot,
            40,
            SlotActionType.SWAP,
            mc.player
        );

        return true;
    }

    private int findTotemSlot() {
        if (mc.player == null) return -1;

        PlayerInventory inventory = mc.player.getInventory();

        for (int i = 0; i < 36; i++) {
            if (inventory.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }

        return -1;
    }

    private void ensureNeutralInput() {
        ClientPlayerEntity player = mc.player;

        if (player == null || player.hasVehicle()) return;

        if (neutralInput != null && player.input == neutralInput) {
            return;
        }

        neutralInput = new NeutralInput(player.input);
        player.input = neutralInput;
    }

    private void restoreNeutralInput() {
        ClientPlayerEntity player = mc.player;

        if (player != null
            && neutralInput != null
            && player.input == neutralInput) {

            player.input = neutralInput.delegate;
        }

        neutralInput = null;
    }

    private boolean hasSafeInventoryClickInput() {
        ClientPlayerEntity player = mc.player;

        if (player == null || player.isSprinting()) {
            return false;
        }

        Input input = player.input;
        if (input == null) return true;

        Vec2f movement = input.getMovementInput();

        if (movement != null
            && movement.lengthSquared() > 1.0E-5f) {
            return false;
        }

        PlayerInput playerInput = input.playerInput;

        return playerInput == null
            || !(playerInput.forward()
                || playerInput.backward()
                || playerInput.left()
                || playerInput.right());
    }

    private static int resolvePlayerHandlerSlot(
        ScreenHandler handler,
        int playerInventoryIndex
    ) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);

            if (slot.inventory instanceof PlayerInventory
                && slot.getIndex() == playerInventoryIndex) {
                return i;
            }
        }

        return -1;
    }

    private static boolean isMeteorOffhandActive() {
        Module offhand =
            meteordevelopment.meteorclient.systems.modules.Modules.get().get(
                meteordevelopment.meteorclient.systems.modules.combat.Offhand.class
            );

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

    private enum SwapPhase {
        IDLE,
        NEUTRAL_ARMED,
        NEUTRAL_APPLIED,
        NEUTRAL_READY
    }

    private final class NeutralInput extends Input {
        private final Input delegate;

        private NeutralInput(Input delegate) {
            this.delegate = delegate;

            setState(
                delegate.playerInput,
                delegate.getMovementInput()
            );
        }

        @Override
        public void tick() {
            delegate.tick();

            PlayerInput original = delegate.playerInput == null
                ? PlayerInput.DEFAULT
                : delegate.playerInput;

            setState(original, delegate.getMovementInput());

            if (swapPhase != SwapPhase.NEUTRAL_ARMED
                || mc.player == null
                || mc.player.input != this) {
                return;
            }

            PlayerInput neutral = new PlayerInput(
                false,
                false,
                false,
                false,
                false,
                original.sneak(),
                false
            );

            setState(neutral, Vec2f.ZERO);

            mc.player.setSprinting(false);
            neutralPlayer = mc.player;
            swapPhase = SwapPhase.NEUTRAL_APPLIED;
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null
                ? PlayerInput.DEFAULT
                : input;

            movementVector = movement == null
                ? Vec2f.ZERO
                : movement;
        }
    }
}
