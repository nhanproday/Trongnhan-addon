package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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

public class AutoTotemVip extends Module {
    private static AutoTotemVip activeInstance;
    private static boolean priorityLock;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("So tick cho giua moi lan swap sang offhand")
        .defaultValue(3)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> yieldMeteorOffhand = sgGeneral.add(
        new BoolSetting.Builder()
            .name("yield-meteor-offhand")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> totemAntiDie = sgGeneral.add(
        new BoolSetting.Builder()
            .name("totem-anti-die")
            .description("Tat menu va ngung ngoi de gan totem khan cap.")
            .defaultValue(true)
            .build()
    );

    private int cooldown;
    private boolean swapQueued;
    private boolean urgentPop;

    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;
    private boolean neutralArmed;

    private int lastTotemSlot = -1;
    private int awaitVerifyTicks = -1;
    private boolean wasSneaking = false;

    public AutoTotemVip() {
        super(
            TrongNhanAddon.CATEGORY,
            "auto-totem-vip",
            "Automatically equips a totem directly from inventory into the offhand."
        );
    }

    public static boolean isPriorityLocked() {
        return priorityLock;
    }

    public static boolean isInstanceActive() {
        return activeInstance != null && activeInstance.isActive();
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        resetPendingState();
        super.onActivate();
    }

    @Override
    public void onDeactivate() {
        if (activeInstance == this) activeInstance = null;
        priorityLock = false;
        resetPendingState();
        super.onDeactivate();
    }

    @EventHandler(priority = -10000)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (!neutralArmed) return;
        ensureNeutralInput();
    }

    @EventHandler(priority = 10000)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (!neutralArmed || neutralInput == null) return;

        boolean safe = hasSafeInventoryClickInput()
            && mc.player == neutralPlayer
            && mc.player != null
            && mc.player.input == neutralInput;

        restoreNeutralInput();
        neutralPlayer = null;
        neutralArmed = false;

        if (safe) {
            attemptDirectSwap();
        }
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

        if (totemAntiDie.get() && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            if (mc.currentScreen != null) {
                if (mc.currentScreen instanceof HandledScreen) {
                    mc.player.closeHandledScreen();
                }
                mc.setScreen(null);
            }
            if (mc.player.isSneaking()) {
                wasSneaking = true;
                mc.player.setSneaking(false);
            }
        }
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

        if (yieldMeteorOffhand.get() && isMeteorOffhandActive()) {
            priorityLock = false;
            return;
        }

        if (awaitVerifyTicks >= 0) {
            if (awaitVerifyTicks > 0) {
                awaitVerifyTicks--;
                priorityLock = true;
                return;
            }

            awaitVerifyTicks = -1;

            if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                priorityLock = false;
                swapQueued = false;
                urgentPop = false;
                cooldown = delay.get();
                if (wasSneaking) {
                    mc.player.setSneaking(true);
                    wasSneaking = false;
                }
                return;
            }

            priorityLock = false;
            urgentPop = true;
            cooldown = 0;
        }

        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            urgentPop = false;
            swapQueued = false;
            neutralArmed = false;
            priorityLock = false;
            restoreNeutralInput();
            if (wasSneaking) {
                mc.player.setSneaking(true);
                wasSneaking = false;
            }
            return;
        }

        if (swapQueued) {
            swapQueued = false;
            return;
        }

        if (cooldown > 0 && !urgentPop) {
            cooldown--;
            return;
        }

        if (urgentPop) cooldown = 0;

        if (neutralArmed) return;

        int totemSlot = findTotemSlot();
        if (totemSlot < 0) {
            lastTotemSlot = -1;
            priorityLock = false;
            return;
        }

        lastTotemSlot = totemSlot;

        if (hasSafeInventoryClickInput() || (urgentPop && totemAntiDie.get())) {
            attemptDirectSwap();
            return;
        }

        neutralArmed = true;
        priorityLock = true;
        ensureNeutralInput();
    }

    private void attemptDirectSwap() {
        if (mc.player == null || mc.interactionManager == null) return;

        int totemSlot = lastTotemSlot >= 0 ? lastTotemSlot : findTotemSlot();
        if (totemSlot < 0) return;

        if (!swapTotemDirectlyToOffhand(totemSlot)) return;

        swapQueued = true;
        urgentPop = false;
        priorityLock = true;
        awaitVerifyTicks = 3;
        cooldown = delay.get();
    }

    private boolean swapTotemDirectlyToOffhand(int inventoryIndex) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (inventoryIndex < 0 || inventoryIndex > 35) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory() || source.getIndex() != inventoryIndex || !source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
        if (handler.getCursorStack() != null && !handler.getCursorStack().isEmpty()) return false;

        mc.interactionManager.clickSlot(handler.syncId, handlerSlot, 40, SlotActionType.SWAP, mc.player);
        return true;
    }

    private int findTotemSlot() {
        if (mc.player == null) return -1;
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inventory.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private void ensureNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.hasVehicle()) {
            neutralArmed = false;
            return;
        }
        if (neutralInput != null && player.input == neutralInput) return;
        neutralInput = new NeutralInput(player.input);
        player.input = neutralInput;
        neutralPlayer = player;
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
        return playerInput == null || !(playerInput.forward() || playerInput.backward() || playerInput.left() || playerInput.right());
    }

    private static int resolvePlayerHandlerSlot(ScreenHandler handler, int playerInventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventoryIndex) return i;
        }
        return -1;
    }

    private static boolean isMeteorOffhandActive() {
        Module offhand = meteordevelopment.meteorclient.systems.modules.Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.Offhand.class);
        return offhand != null && offhand.isActive();
    }

    private void resetPendingState() {
        cooldown = 0;
        swapQueued = false;
        urgentPop = false;
        neutralArmed = false;
        neutralPlayer = null;
        lastTotemSlot = -1;
        awaitVerifyTicks = -1;
        wasSneaking = false;
        restoreNeutralInput();
    }

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
            if (!neutralArmed || mc.player == null || mc.player.input != this) return;
            PlayerInput neutral = new PlayerInput(false, false, false, false, false, original.sneak(), false);
            setState(neutral, Vec2f.ZERO);
            mc.player.setSprinting(false);
        }
        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }
}
