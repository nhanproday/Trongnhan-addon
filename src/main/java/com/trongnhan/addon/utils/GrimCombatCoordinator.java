package com.trongnhan.addon.utils;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.DoAttackEvent;
import meteordevelopment.meteorclient.events.entity.player.DoItemUseEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.listeners.IListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class GrimCombatCoordinator {
    private static final int FIRST_PRIORITY = 1_000_000;
    private static final int LAST_PRIORITY = -1_000_000;

    private static final int EVENT_TICK_PRE_EARLY = 0;
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
    private int serverSlot = -1;
    private int lastSentSlot = -1;
    private float serverYaw;
    private float serverPitch;
    private boolean serverLookKnown;

    private GrimCombatCoordinator() {}

    public static void install() { INSTANCE.installListeners(); }

    public static boolean executeExternalPlacement(int slot, boolean restoreSlot, float yaw, float pitch, BooleanSupplier interaction) {
        if (interaction == null) return false;
        return INSTANCE.executeExternalPlacementInternal(slot, restoreSlot, yaw, pitch, interaction);
    }

    public static boolean claimCleanExternalInventoryEpoch() { return INSTANCE.claimCleanExternalInventoryEpochInternal(); }
    public static void releaseCleanExternalInventoryEpoch() { INSTANCE.externalEpochReserved = false; }

    public static boolean swapHotbarItemToOffhand(int hotbarSlot, long token) {
        return INSTANCE.swapHotbarItemToOffhandInternal(hotbarSlot, token);
    }

    public static void finishEmergencyOffhandCorrection(long token) {
        if (token <= 0L || INSTANCE.emergencyOffhandCorrectionToken != token) return;
        INSTANCE.emergencyOffhandCorrectionPending = false;
        INSTANCE.emergencyOffhandCorrectionToken = 0L;
    }

    public static boolean emergencyOffhandCorrectionPending() {
        return INSTANCE.emergencyOffhandCorrectionPending || INSTANCE.emergencyRestoreSlot >= 0;
    }

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
        emergencyRestoreSlot = -1;
        serverSlot = -1;
        lastSentSlot = -1;
        serverYaw = 0.0f;
        serverPitch = 0.0f;
        serverLookKnown = false;
    }

    private boolean executeExternalPlacementInternal(int slot, boolean restoreSlot, float yaw, float pitch, BooleanSupplier interaction) {
        return false;
    }

    private boolean claimCleanExternalInventoryEpochInternal() {
        if (!packetWindowClean || externalEpochReserved || !externalActionAvailable()) return false;
        externalEpochReserved = true;
        return true;
    }

    private boolean externalActionAvailable() {
        return !actionTakenThisEpoch && !interactionBlocked();
    }

    private boolean interactionBlocked() {
        return pendingSlotRestore || pendingLookRestore || emergencyOffhandCorrectionPending || emergencyRestoreSlot >= 0;
    }

    private boolean swapHotbarItemToOffhandInternal(int hotbarSlot, long token) {
        return false;
    }

    private void onTickPreEarly() {
        if (mc.player == null || mc.getNetworkHandler() == null) { resetConnectionState(); return; }
        initializeSlotIfUnknown();
        if (pendingSlotRestore) restoreOwnedSilentSlot();
        if (pendingLookRestore && serverLookKnown && sameLook(serverYaw, serverPitch, mc.player.getYaw(), mc.player.getPitch())) {
            pendingLookRestore = false;
            lookOwner = null;
        }
    }

    private void restoreOwnedSilentSlot() {
        if (!ownsSilentSlot || mc.player == null || mc.getNetworkHandler() == null) return;
        int visibleSlot = mc.player.getInventory().getSelectedSlot();
        if (serverSlot == visibleSlot || sendManagedSlot(visibleSlot)) {
            ownsSilentSlot = false;
            silentSlotOwner = null;
            pendingSlotRestore = false;
        }
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

    private void onPacketSend(PacketEvent.Send event) {
        if ((emergencyOffhandCorrectionPending || emergencyRestoreSlot >= 0)
            && isEmergencyBlockedPacket(event.packet)) { event.cancel(); return; }
        if (event.packet instanceof PlayerInteractItemC2SPacket && allowPendingItemUse) { allowPendingItemUse = false; return; }
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
        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;
        packetWindowClean = false;
        if (packet.changesLook()) {
            serverYaw = packet.getYaw(serverYaw);
            serverPitch = packet.getPitch(serverPitch);
            serverLookKnown = true;
        }
    }

    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof UpdateSelectedSlotS2CPacket packet)) return;
        int slot = packet.slot();
        mc.execute(() -> {
            serverSlot = slot;
            if (slot == emergencyRestoreSlot) emergencyRestoreSlot = -1;
            ownsSilentSlot = false;
            silentSlotOwner = null;
            pendingSlotRestore = false;
        });
    }

    private void onMovementPacketsPost() {
        if (!pendingLookRestore || mc.player == null || !serverLookKnown) return;
        if (!sameLook(serverYaw, serverPitch, mc.player.getYaw(), mc.player.getPitch())) return;
        pendingLookRestore = false;
        lookOwner = null;
        allowPendingItemUse = false;
    }

    private void cancelAttack(DoAttackEvent event) {
        if (interactionBlocked()) event.cancel();
    }

    private void cancelUse(DoItemUseEvent event) {
        if (interactionBlocked()) { event.cancel(); return; }
    }

    private void cancelBreak(StartBreakingBlockEvent event) {
        if (interactionBlocked()) event.cancel();
    }

    static int bridgeSlot(int desiredSlot) { return (desiredSlot + 1) % 9; }

    static boolean sameLook(float firstYaw, float firstPitch, float secondYaw, float secondPitch) {
        return Float.compare(firstYaw, secondYaw) == 0 && Float.compare(firstPitch, secondPitch) == 0;
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
            case EVENT_PACKET_SEND -> onPacketSend((PacketEvent.Send) event);
            case EVENT_PACKET_SENT -> onPacketSent((PacketEvent.Sent) event);
            case EVENT_PACKET_RECEIVE -> onPacketReceive((PacketEvent.Receive) event);
            case EVENT_MOVEMENT_POST -> onMovementPacketsPost();
            case EVENT_ATTACK -> cancelAttack((DoAttackEvent) event);
            case EVENT_USE -> cancelUse((DoItemUseEvent) event);
            case EVENT_BREAK -> cancelBreak((StartBreakingBlockEvent) event);
            case EVENT_JOIN -> initializeConnectionState();
            case EVENT_LEFT -> resetConnectionState();
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
