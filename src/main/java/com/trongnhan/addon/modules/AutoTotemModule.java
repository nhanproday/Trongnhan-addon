package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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

public class AutoTotemVipModule extends Module {
    private static AutoTotemVipModule activeInstance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> minSlot = sgGeneral.add(new IntSetting.Builder()
        .name("min-slot").description("Slot hotbar nho nhat (1-9).")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Integer> maxSlot = sgGeneral.add(new IntSetting.Builder()
        .name("max-slot").description("Slot hotbar lon nhat (1-9).")
        .defaultValue(3).min(1).max(9).sliderMin(1).sliderMax(9).build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("So tick cho giua moi lan swap.")
        .defaultValue(0).min(0).sliderMax(20).build());

    private int cooldown;
    private SwapPhase swapPhase = SwapPhase.IDLE;
    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;
    private boolean swapQueued;
    private boolean urgentPop;
    private int currentTargetSlot;

    public AutoTotemVipModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem-vip", "Automatically equips totems to hotbar slots with Grim bypass.");
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        cooldown = 0;
        swapPhase = SwapPhase.IDLE;
        swapQueued = false;
        urgentPop = false;
        neutralPlayer = null;
        currentTargetSlot = minSlot.get() - 1;
        restoreNeutralInput();
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

        swapPhase = safe ? SwapPhase.NEUTRAL_READY : SwapPhase.IDLE;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != 35) return;
        if (mc.world == null || mc.player == null) return;
        if (packet.getEntity(mc.world) != mc.player) return;
        if (!isActive()) return;

        urgentPop = true;
        swapQueued = false;
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            resetPendingState();
            return;
        }

        int min = minSlot.get() - 1;
        int max = maxSlot.get() - 1;
        if (min > max) { int temp = min; min = max; max = temp; }
        if (currentTargetSlot < min || currentTargetSlot > max) currentTargetSlot = min;

        // 1. Logic kiểm tra xem slot mục tiêu hiện tại có Totem chưa
        if (mc.player.getInventory().getStack(currentTargetSlot).isOf(Items.TOTEM_OF_UNDYING)) {
            // Nếu đã có, kiểm tra xem còn slot nào trong dải bị thiếu không
            boolean allFull = true;
            for (int i = min; i <= max; i++) {
                if (!mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                    currentTargetSlot = i;
                    allFull = false;
                    break;
                }
            }
            if (allFull) {
                resetPendingState();
                return;
            }
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
            if (swapTotemDirectlyToHotbar(totemIndex, currentTargetSlot)) {
                swapQueued = true;
                urgentPop = false;
                cooldown = delay.get();

                // Tự động nhảy sang slot tiếp theo trong dải
                if (currentTargetSlot < max) {
                    currentTargetSlot++;
                } else {
                    currentTargetSlot = min;
                }
            }
            swapPhase = SwapPhase.IDLE;
            return;
        }

        if (swapPhase == SwapPhase.NEUTRAL_ARMED || swapPhase == SwapPhase.NEUTRAL_APPLIED) return;

        swapPhase = SwapPhase.NEUTRAL_ARMED;
    }

    private boolean swapTotemDirectlyToHotbar(int inventoryIndex, int targetHotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (inventoryIndex < 0 || inventoryIndex > 35) return false;
        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory()) return false;
        if (source.getIndex() != inventoryIndex) return false;
        if (!source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        // Dùng SWAP với button là index của slot hotbar (0-8)
        mc.interactionManager.clickSlot(
            handler.syncId,
            handlerSlot,
            targetHotbarSlot,
            SlotActionType.SWAP,
            mc.player
        );
        return true;
    }

    private int findTotemInInventoryOnly() {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i <= 35; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
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
