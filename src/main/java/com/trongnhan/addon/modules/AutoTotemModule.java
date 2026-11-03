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

    private final Setting<Double> healthThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Khi mau <= gia tri nay thi bat dau kich hoat he thong mainhand.")
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
        .description("So tick cho giua moi lan thuc hien thao tac.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> yieldMeteorOffhand = sgGeneral.add(
        new BoolSetting.Builder()
            .name("yield-meteor-offhand")
            .description("Tu dong dung khi Meteor Offhand module dang active.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> minSlot = sgSlots.add(new IntSetting.Builder()
        .name("min-slot")
        .description("Slot hotbar 1 (chinh).")
        .defaultValue(1)
        .min(1)
        .max(9)
        .sliderMin(1)
        .sliderMax(9)
        .build()
    );

    private final Setting<Integer> maxSlot = sgSlots.add(new IntSetting.Builder()
        .name("max-slot")
        .description("Slot hotbar 2 (du phong).")
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
            "Offhand va Mainhand deu keo totem truc tiep tu balo an toan chong ban."
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

        int totemIndex = findTotemInInventoryOnly();
        if (totemIndex < 0) return;

        if (swapPhase == SwapPhase.NEUTRAL_READY) {
            if (swapTotemDirectlyToSlot(totemIndex, 40)) {
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

        int slot1 = minSlot.get() - 1;
        int slot2 = maxSlot.get() - 1;

        if (slot1 < 0 || slot1 > 8 || slot2 < 0 || slot2 > 8) return;
        if (slot1 == slot2) slot2 = Math.min(slot1 + 1, 8);

        boolean has1 = inv.getStack(slot1).isOf(Items.TOTEM_OF_UNDYING);
        boolean has2 = inv.getStack(slot2).isOf(Items.TOTEM_OF_UNDYING);

        if (!has1 && !has2) {
            int totemInvIndex = findTotemInInventoryOnly();
            if (totemInvIndex < 0) return;

            if (swapPhase == SwapPhase.NEUTRAL_READY) {
                if (swapTotemDirectlyToSlot(totemInvIndex, slot1)) {
                    inv.setSelectedSlot(slot1);
                    waitTicks = delay.get();
                }
                swapPhase = SwapPhase.IDLE;
                return;
            }

            if (swapPhase != SwapPhase.NEUTRAL_ARMED && swapPhase != SwapPhase.NEUTRAL_APPLIED) {
                swapPhase = SwapPhase.NEUTRAL_ARMED;
            }
            return;
        }

        if (has1) {
            if (inv.getSelectedSlot() != slot1) {
                inv.setSelectedSlot(slot1);
                waitTicks = delay.get();
            }
        } else if (has2) {
            if (inv.getSelectedSlot() != slot2) {
                inv.setSelectedSlot(slot2);
                waitTicks = delay.get();
            }

            int totemInvIndex = findTotemInInventoryOnly();
            if (totemInvIndex >= 0) {
                if (swapPhase == SwapPhase.NEUTRAL_READY) {
                    swapTotemDirectlyToSlot(totemInvIndex, slot1);
                    swapPhase = SwapPhase.IDLE;
                    waitTicks = delay.get();
                } else if (swapPhase != SwapPhase.NEUTRAL_ARMED && swapPhase != SwapPhase.NEUTRAL_APPLIED) {
                    swapPhase = SwapPhase.NEUTRAL_ARMED;
                }
            }
        }
    }

    private boolean swapTotemDirectlyToSlot(int inventoryIndex, int targetSlotId) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (inventoryIndex < 9 || inventoryIndex > 35) return false;

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
            targetSlotId,
            SlotActionType.SWAP,
            mc.player
        );

        return true;
    }

    private int findTotemInInventoryOnly() {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
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
