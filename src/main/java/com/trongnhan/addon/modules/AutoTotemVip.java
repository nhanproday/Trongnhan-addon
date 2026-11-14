package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.*;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.listeners.IListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec2f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class AutoTotemVip extends Module {
    private static AutoTotemVip activeInstance;
    private static boolean priorityLock;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> swapDelay = sgGeneral.add(new IntSetting.Builder()
        .name("swap-delay")
        .defaultValue(100).min(0).sliderMax(1000).build());

    public final Setting<Integer> verifyTicks = sgGeneral.add(new IntSetting.Builder()
        .name("verify-ticks")
        .defaultValue(3).min(1).sliderMax(20).build());

    public final Setting<Boolean> yieldMeteorOffhand = sgGeneral.add(
        new BoolSetting.Builder()
            .name("yield-meteor-offhand")
            .defaultValue(true).build());

    public final Setting<Boolean> totemAntiDie = sgGeneral.add(
        new BoolSetting.Builder()
            .name("totem-anti-die")
            .defaultValue(true).build());

    private long lastSwapTime = 0;
    private boolean swapQueued;
    private boolean urgentPop;

    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;
    private boolean neutralArmed;

    private int lastTotemSlot = -1;
    private int awaitVerifyTicks = -1;
    private boolean wasSneaking = false;

    public AutoTotemVip() {
        super(TrongNhanAddon.CATEGORY, "auto-totem-vip", "Automatically equips a totem into the offhand.");
    }

    public static boolean isPriorityLocked() { return priorityLock; }
    public static boolean isInstanceActive() { return activeInstance != null && activeInstance.isActive(); }

    @Override
    public void onActivate() {
        activeInstance = this;
        GrimCombatCoordinator.install();
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
        boolean safe = hasSafeInventoryClickInput() && mc.player == neutralPlayer && mc.player != null && mc.player.input == neutralInput;
        restoreNeutralInput();
        neutralPlayer = null;
        neutralArmed = false;
        if (safe) attemptDirectSwap();
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
        lastSwapTime = 0;
        priorityLock = true;

        if (totemAntiDie.get() && !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            if (mc.currentScreen != null) {
                if (mc.currentScreen instanceof HandledScreen) mc.player.closeHandledScreen();
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
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.player.isCreative() || mc.player.isSpectator()) {
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
                lastSwapTime = System.currentTimeMillis() + swapDelay.get();
                if (wasSneaking) { mc.player.setSneaking(true); wasSneaking = false; }
                restoreNeutralInput();
                return;
            }
            priorityLock = true;
            urgentPop = true;
            lastSwapTime = 0;
            restoreNeutralInput();
        }
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            urgentPop = false;
            swapQueued = false;
            neutralArmed = false;
            priorityLock = false;
            restoreNeutralInput();
            if (wasSneaking) { mc.player.setSneaking(true); wasSneaking = false; }
            return;
        }
        if (swapQueued) {
            swapQueued = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (lastSwapTime > 0 && now < lastSwapTime && !urgentPop) {
            restoreNeutralInput();
            return;
        }
        if (urgentPop) lastSwapTime = 0;
        if (neutralArmed) return;
        int totemSlot = findTotemSlot();
        if (totemSlot < 0) {
            lastTotemSlot = -1;
            priorityLock = false;
            restoreNeutralInput();
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
        awaitVerifyTicks = verifyTicks.get();
        lastSwapTime = System.currentTimeMillis() + swapDelay.get();
        restoreNeutralInput();
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
        if (player == null || player.hasVehicle()) { neutralArmed = false; return; }
        if (neutralInput != null && player.input == neutralInput) return;
        neutralInput = new NeutralInput(player.input);
        player.input = neutralInput;
        neutralPlayer = player;
    }

    private void restoreNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player != null && neutralInput != null && player.input == neutralInput) player.input = neutralInput.delegate;
        neutralInput = null;
        neutralPlayer = null;
        neutralArmed = false;
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
        Module offhand = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.Offhand.class);
        return offhand != null && offhand.isActive();
    }

    private void resetPendingState() {
        lastSwapTime = 0;
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

    public static final class GrimCombatCoordinator {
        private static final int FIRST_PRIORITY = 1_000_000;
        private static final int LAST_PRIORITY = -1_000_000;
        private static final int EVENT_TICK_PRE_EARLY = 0;
        private static final int EVENT_TICK_PRE_LATE = 1;
        private static final int EVENT_PACKET_SEND = 2;
        private static final int EVENT_PACKET_SENT = 3;
        private static final int EVENT_PACKET_RECEIVE = 4;
        private static final int EVENT_MOVEMENT_POST = 5;
        private static final int EVENT_ATTACK = 6;
        private static final int EVENT_USE = 7;
        private static final int EVENT_BREAK = 8;
        private static final int EVENT_JOIN = 9;
        private static final int EVENT_LEFT = 10;

        private static final GrimCombatCoordinator INSTANCE = new GrimCombatCoordinator();
        private final MinecraftClient mc = MinecraftClient.getInstance();
        private final List<Participant> activeParticipants = new ArrayList<>(2);
        private final IListener[] listeners = {
            new CoordinatorListener(TickEvent.Pre.class, FIRST_PRIORITY, EVENT_TICK_PRE_EARLY),
            new CoordinatorListener(TickEvent.Pre.class, LAST_PRIORITY, EVENT_TICK_PRE_LATE),
            new CoordinatorListener(PacketEvent.Send.class, FIRST_PRIORITY, EVENT_PACKET_SEND),
            new CoordinatorListener(PacketEvent.Sent.class, LAST_PRIORITY, EVENT_PACKET_SENT),
            new CoordinatorListener(PacketEvent.Receive.class, LAST_PRIORITY, EVENT_PACKET_RECEIVE),
            new CoordinatorListener(SendMovementPacketsEvent.Post.class, LAST_PRIORITY, EVENT_MOVEMENT_POST),
            new CoordinatorListener(DoAttackEvent.class, FIRST_PRIORITY, EVENT_ATTACK),
            new CoordinatorListener(DoItemUseEvent.class, FIRST_PRIORITY, EVENT_USE),
            new CoordinatorListener(StartBreakingBlockEvent.class, FIRST_PRIORITY, EVENT_BREAK),
            new CoordinatorListener(GameJoinedEvent.class, FIRST_PRIORITY, EVENT_JOIN),
            new CoordinatorListener(GameLeftEvent.class, FIRST_PRIORITY, EVENT_LEFT)
        };

        private boolean installed;
        private Participant preferredParticipant;
        private Participant executingParticipant;
        private Participant silentSlotOwner;
        private Participant lookOwner;
        private boolean managedSlotSend;
        private boolean ownsSilentSlot;
        private boolean pendingSlotRestore;
        private boolean pendingLookRestore;
        private boolean allowPendingItemUse;
        private boolean actionTakenThisEpoch;
        private boolean externalEpochReserved;
        private boolean packetWindowClean;
        private volatile boolean emergencyOffhandCorrectionPending;
        private volatile long emergencyOffhandCorrectionToken;
        private PlayerActionC2SPacket managedOffhandSwapPacket;
        private boolean managedOffhandSwapSent;
        private int emergencyRestoreSlot = -1;
        private boolean forceNextAction;
        private int serverSlot = -1;
        private int lastSentSlot = -1;
        private float serverYaw;
        private float serverPitch;
        private boolean serverLookKnown;
        private boolean externalPlacementActive;
        private boolean externalLookRestorePending;
        private float externalRestoreYaw;
        private float externalRestorePitch;

        private GrimCombatCoordinator() {}
        public static void install() { INSTANCE.installListeners(); }

        private void installListeners() {
            if (installed) return;
            installed = true;
            initializeConnectionState();
            for (IListener listener : listeners) MeteorClient.EVENT_BUS.subscribe(listener);
        }

        private void initializeConnectionState() {
            resetConnectionState();
            initializeSlotIfUnknown();
            if (mc.player != null) {
                serverYaw = mc.player.getYaw();
                serverPitch = mc.player.getPitch();
                serverLookKnown = true;
            }
        }

        private void initializeSlotIfUnknown() {
            if (serverSlot >= 0 || mc.player == null) return;
            serverSlot = mc.player.getInventory().getSelectedSlot();
            lastSentSlot = serverSlot;
        }

        private void resetConnectionState() {
            preferredParticipant = highestPriorityParticipant();
            executingParticipant = null;
            silentSlotOwner = null;
            lookOwner = null;
            managedSlotSend = false;
            ownsSilentSlot = false;
            pendingSlotRestore = false;
            pendingLookRestore = false;
            allowPendingItemUse = false;
            actionTakenThisEpoch = false;
            externalEpochReserved = false;
            packetWindowClean = false;
            emergencyOffhandCorrectionPending = false;
            emergencyOffhandCorrectionToken = 0L;
            managedOffhandSwapPacket = null;
            managedOffhandSwapSent = false;
            forceNextAction = false;
            emergencyRestoreSlot = -1;
            externalPlacementActive = false;
            externalLookRestorePending = false;
            externalRestoreYaw = 0.0f;
            externalRestorePitch = 0.0f;
            serverSlot = -1;
            lastSentSlot = -1;
            serverYaw = 0.0f;
            serverPitch = 0.0f;
            serverLookKnown = false;
        }

        private Participant highestPriorityParticipant() {
            Participant best = null;
            for (Participant participant : activeParticipants) {
                if (best == null || participant.combatPriority() > best.combatPriority()) best = participant;
            }
            return best;
        }

        private void activateParticipant(Participant participant) {
            if (participant == null || activeParticipants.contains(participant)) return;
            activeParticipants.add(participant);
            if (preferredParticipant == null || participant.combatPriority() > preferredParticipant.combatPriority()) preferredParticipant = participant;
            initializeSlotIfUnknown();
        }

        private void deactivateParticipant(Participant participant) {
            if (participant == null) return;
            activeParticipants.remove(participant);
            participant.suspendForItemUse();
            if (silentSlotOwner == participant && ownsSilentSlot) pendingSlotRestore = true;
            if (lookOwner == participant && serverLookKnown && mc.player != null && !sameLook(serverYaw, serverPitch, mc.player.getYaw(), mc.player.getPitch())) pendingLookRestore = true;
            if (preferredParticipant == participant) preferredParticipant = highestPriorityParticipant();
            if (executingParticipant == participant) executingParticipant = null;
            finishCleanupIfReady();
        }

        private boolean executeExternalPlacementInternal(int slot, boolean restoreSlot, float yaw, float pitch, BooleanSupplier interaction) {
            if (slot < 0 || slot > 8 || mc.player == null || mc.world == null || mc.getNetworkHandler() == null
                || externalPlacementActive || externalLookRestorePending || pendingSlotRestore
                || pendingLookRestore || ownsSilentSlot || lookOwner != null
                || emergencyOffhandCorrectionPending || emergencyRestoreSlot >= 0
                || executingParticipant != null || actionTakenThisEpoch || externalEpochReserved) return false;

            initializeSlotIfUnknown();
            if (!serverLookKnown) { serverYaw = mc.player.getYaw(); serverPitch = mc.player.getPitch(); serverLookKnown = true; }
            final int originalSlot = serverSlot;
            final float originalYaw = serverYaw;
            final float originalPitch = serverPitch;
            if (originalSlot < 0 || originalSlot > 8) return false;
            externalPlacementActive = true;
            externalEpochReserved = true;
            boolean interacted = false;
            boolean lookChanged = false;
            boolean slotChanged = false;
            try {
                if (serverSlot != slot) { if (!sendManagedSlot(slot)) return false; slotChanged = true; }
                if (!sameLook(serverYaw, serverPitch, yaw, pitch)) { if (!sendExternalLook(yaw, pitch)) return false; lookChanged = true; }
                interacted = interaction.getAsBoolean();
                return interacted;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                return false;
            } finally {
                if (lookChanged) {
                    if (!sendExternalLook(originalYaw, originalPitch)) {
                        externalLookRestorePending = true;
                        externalRestoreYaw = originalYaw;
                        externalRestorePitch = originalPitch;
                    }
                }
                if (restoreSlot && slotChanged) { if (!sendManagedSlot(originalSlot)) emergencyRestoreSlot = originalSlot; }
                externalPlacementActive = false;
                if (!interacted && !lookChanged && !slotChanged) externalEpochReserved = false;
            }
        }

        private boolean sendExternalLook(float yaw, float pitch) {
            if (mc.player == null || mc.getNetworkHandler() == null) return false;
            PlayerMoveC2SPacket.LookAndOnGround packet = new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision);
            mc.getNetworkHandler().sendPacket(packet);
            return serverLookKnown && sameLook(serverYaw, serverPitch, yaw, pitch);
        }

        private void retryExternalLookRestore() {
            if (!externalLookRestorePending || mc.player == null || mc.getNetworkHandler() == null) return;
            if (sendExternalLook(externalRestoreYaw, externalRestorePitch)) {
                externalLookRestorePending = false;
                finishCleanupIfReady();
            }
        }

        private void onTickPreEarly() {
            if (mc.player == null || mc.getNetworkHandler() == null) { resetConnectionState(); return; }
            initializeSlotIfUnknown();
            retryExternalLookRestore();
            if (emergencyRestoreSlot >= 0) {
                int restoreSlot = emergencyRestoreSlot;
                if (serverSlot == restoreSlot || sendManagedSlot(restoreSlot)) emergencyRestoreSlot = -1;
            }
            if (pendingSlotRestore) restoreOwnedSilentSlot();
            if (pendingLookRestore && serverLookKnown && sameLook(serverYaw, serverPitch, mc.player.getYaw(), mc.player.getPitch())) {
                pendingLookRestore = false;
                lookOwner = null;
            }
            finishCleanupIfReady();
        }

        private void onTickPreLate() {
            if (cleanupInteractionBlocked() || externalPlacementActive || externalLookRestorePending
                || actionTakenThisEpoch || externalEpochReserved || mc.player == null
                || mc.world == null || activeParticipants.isEmpty()) return;

            List<Intent> intents = new ArrayList<>(activeParticipants.size());
            for (Participant participant : List.copyOf(activeParticipants)) {
                if (participantInteractionBlocked(participant)) continue;
                try {
                    Intent intent = participant.prepareCombatIntent();
                    if (intent != null && intent.owner() == participant) intents.add(intent);
                } catch (Throwable throwable) { throwable.printStackTrace(); }
            }

            if (intents.isEmpty()) return;
            Intent selected = null;
            if (preferredParticipant != null) {
                for (Intent intent : intents) {
                    if (intent.owner() == preferredParticipant) { selected = intent; break; }
                }
            }

            if (selected == null) {
                selected = intents.getFirst();
                for (Intent intent : intents) {
                    if (intent.owner().combatPriority() > selected.owner().combatPriority()) selected = intent;
                }
            }

            executingParticipant = selected.owner();
            try { selected.execute(); }
            catch (Throwable throwable) { throwable.printStackTrace(); }
            finally { executingParticipant = null; }
        }

        private boolean selectSilentSlot(Participant participant, int slot) {
            if (executingParticipant != participant || !activeParticipants.contains(participant)
                || slot < 0 || slot > 8 || mc.player == null || mc.getNetworkHandler() == null) return false;
            initializeSlotIfUnknown();
            int visibleSlot = mc.player.getInventory().getSelectedSlot();
            if (serverSlot != slot && !sendManagedSlot(slot)) return false;
            silentSlotOwner = participant;
            ownsSilentSlot = slot != visibleSlot;
            return serverSlot == slot;
        }

        private void restoreSilentSlot(Participant participant) {
            if (silentSlotOwner != participant || !ownsSilentSlot) return;
            restoreOwnedSilentSlot();
        }

        private void restoreOwnedSilentSlot() {
            if (!ownsSilentSlot || mc.player == null || mc.getNetworkHandler() == null) return;
            int visibleSlot = mc.player.getInventory().getSelectedSlot();
            if (serverSlot == visibleSlot || sendManagedSlot(visibleSlot)) {
                ownsSilentSlot = false;
                silentSlotOwner = null;
                pendingSlotRestore = false;
                finishCleanupIfReady();
            }
        }

        private void observeParticipantLook(Participant participant, float yaw, float pitch) {
            if (!activeParticipants.contains(participant)) return;
            serverYaw = yaw;
            serverPitch = pitch;
            serverLookKnown = true;
            lookOwner = participant;
        }

        private void onPacketSend(PacketEvent.Send event) {
            if ((emergencyOffhandCorrectionPending || emergencyRestoreSlot >= 0)
                && isEmergencyBlockedPacket(event.packet) && !emergencyCombatPacketAllowed()) { event.cancel(); return; }
            if (event.packet instanceof PlayerInteractItemC2SPacket && allowPendingItemUse) { allowPendingItemUse = false; return; }
            if (!isTargetedInteraction(event.packet)) return;
            if (executingParticipant == null) {
                if (cleanupInteractionBlocked()) event.cancel();
                return;
            }
            boolean blocked = participantInteractionBlocked(executingParticipant);
            if (blocked || actionTakenThisEpoch) {
                if (forceNextAction) forceNextAction = false;
                else event.cancel();
            }
        }

        private void onPacketSent(PacketEvent.Sent event) {
            if (event.packet == managedOffhandSwapPacket) { managedOffhandSwapSent = true; return; }
            if (event.packet instanceof UpdateSelectedSlotC2SPacket packet) {
                serverSlot = packet.getSelectedSlot();
                lastSentSlot = packet.getSelectedSlot();
                if (!managedSlotSend) {
                    ownsSilentSlot = false;
                    silentSlotOwner = null;
                    pendingSlotRestore = false;
                    finishCleanupIfReady();
                }
                return;
            }
            if (event.packet instanceof ClientTickEndC2SPacket) {
                actionTakenThisEpoch = false;
                externalEpochReserved = false;
                packetWindowClean = true;
                return;
            }
            if (isPacketOrderConflict(event.packet)) packetWindowClean = false;
            if (event.packet instanceof PlayerInteractEntityC2SPacket
                || event.packet instanceof PlayerInteractBlockC2SPacket
                || event.packet instanceof PlayerInteractItemC2SPacket
                || isTargetedPlayerAction(event.packet)) {
                if (executingParticipant != null) markActionSent();
                else externalEpochReserved = true;
                return;
            }
            if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;
            packetWindowClean = false;
            if (packet.changesLook()) {
                serverYaw = packet.getYaw(serverYaw);
                serverPitch = packet.getPitch(serverPitch);
                serverLookKnown = true;
            }
        }

        private void markActionSent() {
            actionTakenThisEpoch = true;
            Participant actionOwner = executingParticipant;
            if (actionOwner != null) preferredParticipant = nextParticipant(actionOwner);
        }

        private void onPacketReceive(PacketEvent.Receive event) {
            if (!(event.packet instanceof UpdateSelectedSlotS2CPacket packet)) return;
            int slot = packet.slot();
            mc.execute(() -> observeServerSlot(slot));
        }

        private void observeServerSlot(int slot) {
            serverSlot = slot;
            if (slot == emergencyRestoreSlot) emergencyRestoreSlot = -1;
            ownsSilentSlot = false;
            silentSlotOwner = null;
            pendingSlotRestore = false;
            finishCleanupIfReady();
        }

        private void onMovementPacketsPost() {
            if (!pendingLookRestore || mc.player == null || !serverLookKnown) return;
            if (!sameLook(serverYaw, serverPitch, mc.player.getYaw(), mc.player.getPitch())) return;
            pendingLookRestore = false;
            lookOwner = null;
            allowPendingItemUse = false;
            finishCleanupIfReady();
        }

        private void cancelAttack(DoAttackEvent event) {
            if (interactionBlocked()) { event.cancel(); return; }
            if (executingParticipant != null && actionTakenThisEpoch) event.cancel();
        }

        private void cancelUse(DoItemUseEvent event) {
            if (interactionBlocked()) { event.cancel(); return; }
            if (executingParticipant != null && actionTakenThisEpoch) { event.cancel(); return; }
            if (activeParticipants.isEmpty()) return;
            boolean lookMismatch = lookDiffersFromCamera();
            if (!ownsSilentSlot && !lookMismatch) return;
            for (Participant participant : List.copyOf(activeParticipants)) participant.suspendForItemUse();
            if (ownsSilentSlot) {
                pendingSlotRestore = true;
                if (lookMismatch) pendingLookRestore = true;
                event.cancel();
                return;
            }
            pendingLookRestore = true;
            allowPendingItemUse = true;
        }

        private void cancelBreak(StartBreakingBlockEvent event) {
            if (interactionBlocked()) { event.cancel(); return; }
            if (executingParticipant != null && actionTakenThisEpoch) event.cancel();
        }

        private boolean sendManagedSlot(int slot) {
            if (slot < 0 || slot > 8 || mc.getNetworkHandler() == null) return false;
            if (serverSlot == slot) return true;
            if (lastSentSlot == slot) {
                int bridge = bridgeSlot(slot);
                if (!sendOneSlot(bridge) || serverSlot != bridge) return false;
            }
            return sendOneSlot(slot) && serverSlot == slot;
        }

        private boolean sendOneSlot(int slot) {
            if (slot < 0 || slot > 8 || slot == lastSentSlot || mc.getNetworkHandler() == null) return false;
            managedSlotSend = true;
            try { mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot)); }
            finally { managedSlotSend = false; }
            return serverSlot == slot;
        }

        private boolean claimCleanExternalInventoryEpochInternal() {
            if (!packetWindowClean || externalEpochReserved || externalPlacementActive || !externalActionAvailable()) return false;
            externalEpochReserved = true;
            return true;
        }

        private boolean externalActionAvailable() {
            return !actionTakenThisEpoch && !interactionBlocked() && executingParticipant == null && !externalPlacementActive;
        }

        private boolean interactionBlocked() {
            return pendingSlotRestore || pendingLookRestore || emergencyOffhandCorrectionPending
                || emergencyRestoreSlot >= 0 || externalPlacementActive || externalLookRestorePending;
        }

        private boolean cleanupInteractionBlocked() {
            return pendingSlotRestore || pendingLookRestore || emergencyRestoreSlot >= 0 || externalLookRestorePending;
        }

        private boolean participantInteractionBlocked(Participant participant) {
            return cleanupInteractionBlocked() || externalPlacementActive
                || (emergencyOffhandCorrectionPending && (participant == null || !participant.canFollowEmergencyOffhandSwap()));
        }

        private boolean emergencyCombatPacketAllowed() {
            return emergencyOffhandCorrectionPending && emergencyRestoreSlot < 0
                && executingParticipant != null && executingParticipant.canFollowEmergencyOffhandSwap();
        }

        private boolean lookDiffersFromCamera() {
            return mc.player != null && serverLookKnown
                && !sameLook(serverYaw, serverPitch, mc.player.getYaw(), mc.player.getPitch());
        }

        private void finishCleanupIfReady() {
            if (pendingSlotRestore || pendingLookRestore || externalLookRestorePending) return;
            if (!ownsSilentSlot) silentSlotOwner = null;
            if (lookOwner != null && !activeParticipants.contains(lookOwner)) lookOwner = null;
        }

        static int bridgeSlot(int desiredSlot) { return (desiredSlot + 1) % 9; }

        static boolean sameLook(float firstYaw, float firstPitch, float secondYaw, float secondPitch) {
            return Float.compare(firstYaw, secondYaw) == 0 && Float.compare(firstPitch, secondPitch) == 0;
        }

        private static boolean isTargetedInteraction(Object packet) {
            return packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket
                || isTargetedPlayerAction(packet);
        }

        private static boolean isTargetedPlayerAction(Object packet) {
            if (!(packet instanceof PlayerActionC2SPacket action)) return false;
            return action.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
                || action.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
                || action.getAction() == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK
                || action.getAction() == PlayerActionC2SPacket.Action.STAB
                || action.getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND;
        }

        private static boolean isEmergencyBlockedPacket(Object packet) {
            return packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket
                || packet instanceof PlayerActionC2SPacket
                || packet instanceof PickItemFromBlockC2SPacket
                || packet instanceof PickItemFromEntityC2SPacket
                || packet instanceof HandSwingC2SPacket;
        }

        private static boolean isPacketOrderConflict(Object packet) {
            return packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket
                || packet instanceof PlayerActionC2SPacket
                || packet instanceof ClickSlotC2SPacket
                || packet instanceof PickItemFromBlockC2SPacket
                || packet instanceof PickItemFromEntityC2SPacket
                || packet instanceof ClientCommandC2SPacket
                || packet instanceof ClientStatusC2SPacket;
        }

        private void dispatch(int eventKind, Object event) {
            switch (eventKind) {
                case EVENT_TICK_PRE_EARLY -> onTickPreEarly();
                case EVENT_TICK_PRE_LATE -> onTickPreLate();
                case EVENT_PACKET_SEND -> onPacketSend((PacketEvent.Send) event);
                case EVENT_PACKET_SENT -> onPacketSent((PacketEvent.Sent) event);
                case EVENT_PACKET_RECEIVE -> onPacketReceive((PacketEvent.Receive) event);
                case EVENT_MOVEMENT_POST -> onMovementPacketsPost();
                case EVENT_ATTACK -> cancelAttack((DoAttackEvent) event);
                case EVENT_USE -> cancelUse((DoItemUseEvent) event);
                case EVENT_BREAK -> cancelBreak((StartBreakingBlockEvent) event);
                case EVENT_JOIN -> initializeConnectionState();
                case EVENT_LEFT -> resetConnectionState();
                default -> throw new IllegalStateException("Unknown combat coordinator event " + eventKind);
            }
        }

        public interface Participant {
            Intent prepareCombatIntent();
            void suspendForItemUse();
            default boolean canFollowEmergencyOffhandSwap() { return false; }
            default int combatPriority() { return 0; }
        }

        public interface Intent {
            Participant owner();
            void execute();
        }

        private final class CoordinatorListener implements IListener {
            private final Class<?> target;
            private final int priority;
            private final int eventKind;
            private CoordinatorListener(Class<?> target, int priority, int eventKind) {
                this.target = target;
                this.priority = priority;
                this.eventKind = eventKind;
            }
            @Override public void call(Object event) { dispatch(eventKind, event); }
            @Override public Class<?> getTarget() { return target; }
            @Override public int getPriority() { return priority; }
            @Override public boolean isStatic() { return false; }
        }
    }
}
