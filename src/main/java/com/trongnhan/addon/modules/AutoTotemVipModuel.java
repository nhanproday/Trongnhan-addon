package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class AutoTotemVipModule extends Module {
    public static AutoTotemVipModule activeInstance;

    public enum TargetHand { Both, Offhand, Mainhand }
    public enum MainhandMode { Health, Always }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMainhand = settings.createGroup("Main Hand");
    private final SettingGroup sgInv = settings.createGroup("Inventory Replenish");
    private final SettingGroup sgGrim = settings.createGroup("Grim Safety");
    private final SettingGroup sgAlert = settings.createGroup("Alerts");

    public final Setting<TargetHand> targetHand = sgGeneral.add(new EnumSetting.Builder<TargetHand>()
        .name("target-hand").defaultValue(TargetHand.Both).build());

    public final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").defaultValue(0).min(0).sliderMax(5).build());

    public final Setting<MainhandMode> mainhandMode = sgMainhand.add(new EnumSetting.Builder<MainhandMode>()
        .name("mainhand-mode").defaultValue(MainhandMode.Health)
        .visible(() -> targetHand.get() != TargetHand.Offhand).build());

    public final Setting<Double> mainhandHealth = sgMainhand.add(new DoubleSetting.Builder()
        .name("mainhand-health").defaultValue(10.0).min(1.0).sliderMax(36.0)
        .visible(() -> targetHand.get() != TargetHand.Offhand && mainhandMode.get() == MainhandMode.Health).build());

    public final Setting<Boolean> hotbarSwitch = sgMainhand.add(new BoolSetting.Builder()
        .name("hotbar-switch").defaultValue(true)
        .visible(() -> targetHand.get() != TargetHand.Offhand).build());

    public final Setting<Boolean> silentMode = sgInv.add(new BoolSetting.Builder()
        .name("silent-mode").defaultValue(true).build());

    public final Setting<Boolean> grimSafe = sgGrim.add(new BoolSetting.Builder()
        .name("grim-safe").defaultValue(true).build());

    public final Setting<Boolean> yieldMeteorOffhand = sgGrim.add(new BoolSetting.Builder()
        .name("yield-meteor-offhand").defaultValue(true).build());

    public final Setting<Boolean> lowAlert = sgAlert.add(new BoolSetting.Builder()
        .name("low-alert").defaultValue(true).build());

    public final Setting<Integer> lowThreshold = sgAlert.add(new IntSetting.Builder()
        .name("low-threshold").defaultValue(5).min(1).sliderMax(15).visible(lowAlert::get).build());

    public final Setting<Boolean> alertSound = sgAlert.add(new BoolSetting.Builder()
        .name("alert-sound").defaultValue(true).visible(lowAlert::get).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private int delayTimer;
    private Stage stage = Stage.Idle;
    private int lastInventoryTotemCount = -1;
    private int pendingSlot = -1;
    private int pendingButton = -1;

    private enum SwapPhase { Idle, NeutralArmed, NeutralApplied, NeutralReady }
    private SwapPhase swapPhase = SwapPhase.Idle;
    private boolean mioPending;
    private boolean mioDirectAllowed;
    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;

    public AutoTotemVipModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem-vip", "Tu dong lap Totem vao tay phu hoac tay chinh voi Grim bypass.");
    }

    public static void tryQueuedDirectSwapAfterClientTickEnd() {
        if (activeInstance != null && activeInstance.isActive()) {
            activeInstance.tryQueuedMioSwap();
        }
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        delayTimer = 0;
        stage = Stage.Idle;
        swapPhase = SwapPhase.Idle;
        mioPending = false;
        mioDirectAllowed = false;
        lastInventoryTotemCount = -1;
        pendingSlot = -1;
        pendingButton = -1;
        restoreNeutralInput();
    }

    @Override
    public void onDeactivate() {
        if (activeInstance == this) activeInstance = null;
        if (stage != Stage.Idle && mc.currentScreen instanceof InventoryScreen) {
            mc.player.closeHandledScreen();
        }
        stage = Stage.Idle;
        swapPhase = SwapPhase.Idle;
        mioPending = false;
        mioDirectAllowed = false;
        pendingSlot = -1;
        pendingButton = -1;
        restoreNeutralInput();
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        int total = InvUtils.find(Items.TOTEM_OF_UNDYING).count();
        return String.valueOf(total);
    }

    @EventHandler(priority = -10000)
    private void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (grimSafe.get() && swapPhase == SwapPhase.NeutralArmed) {
            ensureNeutralInput();
        }
    }

    @EventHandler(priority = 10000)
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (!grimSafe.get() || swapPhase != SwapPhase.NeutralApplied) return;

        boolean safe = hasSafeInventoryClickInput()
            && mc.player == neutralPlayer
            && mc.player != null
            && mc.player.input == neutralInput;
        restoreNeutralInput();
        neutralPlayer = null;
        swapPhase = safe ? SwapPhase.NeutralReady : SwapPhase.Idle;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != 35) return;
        if (mc.world == null || mc.player == null) return;
        if (packet.getEntity(mc.world) != mc.player) return;

        if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;
        if (!isActive() || (yieldMeteorOffhand.get() && isMeteorOffhandActive())) return;

        mioPending = true;
        mioDirectAllowed = true;
        if (grimSafe.get()) {
            if (swapPhase == SwapPhase.Idle) {
                swapPhase = SwapPhase.NeutralArmed;
            }
            if (swapPhase == SwapPhase.NeutralReady) {
                tryQueuedMioSwap();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (yieldMeteorOffhand.get() && isMeteorOffhandActive()) return;

        int currentInvTotems = countInventoryTotemsOnly();

        if (lowAlert.get() && lastInventoryTotemCount != -1 && currentInvTotems < lastInventoryTotemCount) {
            if (currentInvTotems <= lowThreshold.get()) {
                ChatUtils.sendMsg(Text.literal("§fCANH BAO: Kho do chi con §e" + currentInvTotems + " §fTotem!"));
                if (alertSound.get()) {
                    mc.player.playSound(SoundEvents.ENTITY_IRON_GOLEM_HURT, 1.0F, 0.6F);
                }
            }
        }
        lastInventoryTotemCount = currentInvTotems;

        if (grimSafe.get()) {
            if (swapPhase == SwapPhase.NeutralArmed || swapPhase == SwapPhase.NeutralApplied) return;
            if (swapPhase == SwapPhase.NeutralReady) {
                swapPhase = SwapPhase.Idle;
                mioDirectAllowed = false;
                handleIdle();
                return;
            }
        }

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        switch (stage) {
            case Idle -> handleIdle();
            case Step2_Open -> handleOpen();
            case Step3_Swap -> handleSwap();
            case Step4_Close -> handleClose();
        }
    }

    private boolean tryQueuedMioSwap() {
        if (!mioPending || !mioDirectAllowed) return false;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (mc.player.isCreative() || mc.player.isSpectator()) return false;
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            mioPending = false;
            mioDirectAllowed = false;
            swapPhase = SwapPhase.Idle;
            return false;
        }

        int totemSlot = findTotemInInventoryOnly();
        if (totemSlot < 0) return false;

        swapPhase = SwapPhase.Idle;
        mioDirectAllowed = false;
        executeKeybindSwap(totemSlot, 40);
        delayTimer = actionDelay.get();
        mioPending = false;
        return true;
    }

    private void handleIdle() {
        if (mc.player == null) return;

        if (shouldRefillOffhand()) {
            if (grimSafe.get() && !hasSafeInventoryClickInput() && swapPhase == SwapPhase.Idle) {
                swapPhase = SwapPhase.NeutralArmed;
                return;
            }

            int hotbarTotem = findHotbarTotem(true);
            if (hotbarTotem == -1) hotbarTotem = findHotbarTotem(false);

            if (hotbarTotem != -1) {
                executeKeybindSwap(36 + hotbarTotem, 40);
                delayTimer = actionDelay.get();
                return;
            }

            int invTotem = findTotemInInventoryOnly();
            if (invTotem != -1) {
                pendingSlot = invTotem;
                pendingButton = 40;
                transitionToOpen();
                return;
            }
        }

        if (shouldRefillMainhand()) {
            int currentSelected = mc.player.getInventory().getSelectedSlot();
            int hotbarTotem = findHotbarTotemForMainhand();

            if (hotbarTotem != -1) {
                if (hotbarSwitch.get()) {
                    InvUtils.swap(hotbarTotem, false);
                    delayTimer = actionDelay.get();
                    return;
                } else {
                    executeKeybindSwap(36 + hotbarTotem, currentSelected);
                    delayTimer = actionDelay.get();
                    return;
                }
            }

            int invTotem = findTotemInInventoryOnly();
            if (invTotem != -1) {
                pendingSlot = invTotem;
                pendingButton = currentSelected;
                transitionToOpen();
            }
        }
    }

    private boolean shouldRefillOffhand() {
        if (targetHand.get() == TargetHand.Mainhand) return false;
        return !mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private boolean shouldRefillMainhand() {
        if (targetHand.get() == TargetHand.Offhand) return false;
        if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        if (mainhandMode.get() == MainhandMode.Always) return true;

        float totalHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        return totalHp <= mainhandHealth.get();
    }

    private void transitionToOpen() {
        stage = Stage.Step2_Open;
        if (actionDelay.get() == 0 && silentMode.get()) {
            handleOpen();
        } else {
            delayTimer = actionDelay.get();
        }
    }

    private void handleOpen() {
        if (silentMode.get()) {
            stage = Stage.Step3_Swap;
            if (actionDelay.get() == 0) {
                handleSwap();
            } else {
                delayTimer = actionDelay.get();
            }
        } else {
            if (mc.currentScreen == null) {
                mc.setScreen(new InventoryScreen(mc.player));
                stage = Stage.Step3_Swap;
                delayTimer = actionDelay.get();
            } else if (mc.currentScreen instanceof InventoryScreen) {
                stage = Stage.Step3_Swap;
                if (actionDelay.get() == 0) handleSwap();
                else delayTimer = actionDelay.get();
            } else {
                stage = Stage.Idle;
                pendingSlot = -1;
                pendingButton = -1;
            }
        }
    }

    private void handleSwap() {
        if (pendingSlot != -1 && pendingButton != -1) {
            if (!silentMode.get()) simulateMouseOverSlot(pendingSlot);
            executeKeybindSwap(pendingSlot, pendingButton);
        }

        stage = Stage.Step4_Close;
        if (actionDelay.get() == 0 && silentMode.get()) handleClose();
        else delayTimer = actionDelay.get();
    }

    private void handleClose() {
        if (silentMode.get()) {
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(0));
        } else {
            if (mc.currentScreen instanceof InventoryScreen) mc.player.closeHandledScreen();
        }
        pendingSlot = -1;
        pendingButton = -1;
        stage = Stage.Idle;
        delayTimer = actionDelay.get();
    }

    private void simulateMouseOverSlot(int slotId) {
        Screen screen = mc.currentScreen;
        if (screen instanceof InventoryScreen inv) {
            Slot slot = inv.getScreenHandler().getSlot(slotId);
            if (slot != null) {
                int guiLeft = (inv.width - 176) / 2;
                int guiTop = (inv.height - 166) / 2;
                double mouseX = guiLeft + slot.x + 10;
                double mouseY = guiTop + slot.y + 10;
                long handle = mc.getWindow().getHandle();
                double scale = (double) mc.getWindow().getWidth() / (double) inv.width;
                GLFW.glfwSetCursorPos(handle, mouseX * scale, mouseY * scale);
            }
        }
    }

    private void executeKeybindSwap(int slotId, int button) {
        int syncId = silentMode.get() ? mc.player.playerScreenHandler.syncId : 
            (mc.currentScreen instanceof InventoryScreen inv ? inv.getScreenHandler().syncId : 0);

        mc.interactionManager.clickSlot(syncId, slotId, button, SlotActionType.SWAP, mc.player);
    }

    private int countInventoryTotemsOnly() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 9; i < 36; ++i) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) count++;
        }
        return count;
    }

    private int findHotbarTotem(boolean excludeCurrentSelected) {
        if (mc.player == null) return -1;
        int current = mc.player.getInventory().getSelectedSlot();
        int fallback = -1;

        for (int i = 0; i < 9; ++i) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                if (i != current) return i;
                fallback = i;
            }
        }
        return excludeCurrentSelected ? -1 : fallback;
    }

    private int findHotbarTotemForMainhand() {
        if (mc.player == null) return -1;
        int current = mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; ++i) {
            if (i != current && mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int findTotemInInventoryOnly() {
        if (mc.player == null || mc.player.playerScreenHandler == null) return -1;
        for (Slot slot : mc.player.playerScreenHandler.slots) {
            if (slot.index >= 9 && slot.index <= 35 && slot.getStack().isOf(Items.TOTEM_OF_UNDYING)) return slot.index;
        }
        return -1;
    }

    private boolean hasSafeInventoryClickInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.isSprinting()) return false;
        if (player.input == null) return true;

        Vec2f movement = player.input.getMovementInput();
        if (movement != null && movement.lengthSquared() > 1.0E-5f) return false;

        return !(player.input.pressingForward || player.input.pressingBack || player.input.pressingLeft || player.input.pressingRight);
    }

    private void ensureNeutralInput() {
        ClientPlayerEntity player = mc.player;
        if (player == null || player.getVehicle() != null) return;
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

    private static boolean isMeteorOffhandActive() {
        Module offhand = Modules.get().get(meteordevelopment.meteorclient.systems.modules.combat.AutoTotem.class);
        return offhand != null && offhand.isActive();
    }

    private final class NeutralInput extends net.minecraft.client.input.Input {
        final net.minecraft.client.input.Input delegate;

        NeutralInput(net.minecraft.client.input.Input delegate) {
            this.delegate = delegate;
            this.pressingForward = delegate.pressingForward;
            this.pressingBack = delegate.pressingBack;
            this.pressingLeft = delegate.pressingLeft;
            this.pressingRight = delegate.pressingRight;
            this.movementSideways = delegate.movementSideways;
            this.movementForward = delegate.movementForward;
            this.jumping = delegate.jumping;
            this.sneaking = delegate.sneaking;
        }

        @Override
        public void tick() {
            delegate.tick();
            this.pressingForward = delegate.pressingForward;
            this.pressingBack = delegate.pressingBack;
            this.pressingLeft = delegate.pressingLeft;
            this.pressingRight = delegate.pressingRight;
            this.movementSideways = delegate.movementSideways;
            this.movementForward = delegate.movementForward;
            this.jumping = delegate.jumping;
            this.sneaking = delegate.sneaking;

            if (swapPhase != SwapPhase.NeutralArmed || mc.player == null || mc.player.input != this) return;

            this.pressingForward = false;
            this.pressingBack = false;
            this.pressingLeft = false;
            this.pressingRight = false;
            this.movementSideways = 0;
            this.movementForward = 0;
            this.jumping = false;
            mc.player.setSprinting(false);
            neutralPlayer = mc.player;
            swapPhase = SwapPhase.NeutralApplied;
        }
    }

    private enum Stage { Idle, Step2_Open, Step3_Swap, Step4_Close }
}
