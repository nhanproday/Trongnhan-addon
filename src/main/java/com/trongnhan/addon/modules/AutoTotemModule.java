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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
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

public class AutoTotemModule extends Module {
    private static AutoTotemModule activeInstance;
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSlots = settings.createGroup("Slots");

    private final Setting<Double> healthThreshold = sgGeneral.add(new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
        .name("health-threshold")
        .description("Khi mau (HP, 2 HP = 1 tim) <= gia tri nay thi chuyen totem len hotbar.")
        .defaultValue(6.0)
        .min(1.0)
        .max(20.0)
        .sliderMin(1.0)
        .sliderMax(20.0)
        .decimalPlaces(1)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("So tick cho giua moi lan swap")
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

    private final Setting<Integer> minSlot = sgSlots.add(new IntSetting.Builder()
        .name("min-slot")
        .description("Slot hotbar thap nhat (1-9) dung de cam totem.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderMin(1)
        .sliderMax(9)
        .build()
    );

    private final Setting<Integer> maxSlot = sgSlots.add(new IntSetting.Builder()
        .name("max-slot")
        .description("Slot hotbar cao nhat (1-9) dung de cam totem.")
        .defaultValue(2)
        .min(1)
        .max(9)
        .sliderMin(1)
        .sliderMax(9)
        .build()
    );

    private int cooldown;
    private int waitTicks = 0;
    private SwapPhase swapPhase = SwapPhase.IDLE;

    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;

    private boolean swapQueued;
    private boolean urgentPop;

    public AutoTotemModule() {
        super(
            TrongNhanAddon.CATEGORY,
            "auto-totem",
            "Anti-ban offhand swap combined with main-hand backup hotbar totem pull."
        );
        activeInstance = this;
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
        resetPendingState();
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

        handleOffhandLogic();
        handleMainHandLogic();
    }

    private void handleOffhandLogic() {
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

    private void handleMainHandLogic() {
        ClientPlayerEntity player = mc.player;
        PlayerInventory inv = player.getInventory();

        float health = player.getHealth() + player.getAbsorptionAmount();
        if (health > healthThreshold.get().floatValue()) {
            waitTicks = 0;
            return;
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        int min = minSlot.get() - 1;
        int max = maxSlot.get() - 1;

        if (max <= min) {
            max = Math.min(min + 1, 8);
        }

        boolean minHasTotem = inv.getStack(min).isOf(Items.TOTEM_OF_UNDYING);
        boolean maxHasTotem = inv.getStack(max).isOf(Items.TOTEM_OF_UNDYING);

        if (minHasTotem) return;

        if (maxHasTotem) {
            if (player.getInventory().getSelectedSlot() == min) {
                selectHotbarSlot(max);
            }
            return;
        }

        int totemInvIndex = findMainHandTotemInventory();
        if (totemInvIndex < 0) return;

        boolean moved = moveTotemToHotbarSlot(totemInvIndex, min);
        if (!moved) {
            moved = moveTotemToHotbarSlot(totemInvIndex, max);
        }

        if (moved) {
            if (inv.getStack(min).isOf(Items.TOTEM_OF_UNDYING)) {
                selectHotbarSlot(min);
            } else if (inv.getStack(max).isOf(Items.TOTEM_OF_UNDYING)) {
                selectHotbarSlot(max);
            }
            waitTicks = delay.get();
        }
    }

    private boolean swapTotemDirectlyToOffhand(int inventoryIndex) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (inventoryIndex < 0 || inventoryIndex > 35) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory()) return false;
        if (source.getIndex() != inventoryIndex) return false;
        if (!source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        mc.interactionManager.clickSlot(
            handler.syncId,
            handlerSlot,
            40,
            SlotActionType.SWAP,
            mc.player
        );

        return true;
    }

    private boolean moveTotemToHotbarSlot(int inventoryIndex, int hotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory()) return false;
        if (source.getIndex() != inventoryIndex) return false;
        if (!source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        mc.interactionManager.clickSlot(
            handler.syncId,
            handlerSlot,
            hotbarSlot,
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

    private int findMainHandTotemInventory() {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (i == minSlot.get() - 1 || i == maxSlot.get() - 1) continue;
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private void selectHotbarSlot(int slot) {
        if (mc.player == null) return;
        mc.player.getInventory().setSelectedSlot(slot);
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
        return playerInput == null || !(playerInput.forward() || playerInput.backward() || playerInput.left() || playerInput.right());
    }

    private static int resolvePlayerHandlerSlot(ScreenHandler handler, int playerInventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventoryIndex) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMeteorOffhandActive() {
        Module offhand = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.Offhand.class);
        return offhand != null && offhand.isActive();
    }

    private void resetPendingState() {
        cooldown = 0;
        waitTicks = 0;
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
            setState(delegate.playerInput, delegate.getMovementInput());
        }

        @Override
        public void tick() {
            delegate.tick();

            PlayerInput original = delegate.playerInput == null ? PlayerInput.DEFAULT : delegate.playerInput;
            setState(original, delegate.getMovementInput());

            if (swapPhase != SwapPhase.NEUTRAL_ARMED || mc.player == null || mc.player.input != this) {
                return;
            }

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
