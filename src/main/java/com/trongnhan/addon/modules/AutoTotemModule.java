package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
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
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.glfw.GLFW;

public class AutoTotemVipModule extends Module {
    public static AutoTotemVipModule activeInstance;

    public enum MainhandMode { Health, Always }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMainhand = settings.createGroup("Main Hand");
    private final SettingGroup sgInv = settings.createGroup("Inventory Replenish");
    private final SettingGroup sgGrim = settings.createGroup("Grim Safety");
    private final SettingGroup sgAlert = settings.createGroup("Alerts");

    public final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").defaultValue(0).min(0).sliderMax(5).build());

    public final Setting<MainhandMode> mainhandMode = sgMainhand.add(new EnumSetting.Builder<MainhandMode>()
        .name("mainhand-mode").defaultValue(MainhandMode.Health).build());

    public final Setting<Double> mainhandHealth = sgMainhand.add(new DoubleSetting.Builder()
        .name("mainhand-health").defaultValue(10.0).min(1.0).sliderMax(36.0)
        .visible(() -> mainhandMode.get() == MainhandMode.Health).build());

    public final Setting<Boolean> silentMode = sgInv.add(new BoolSetting.Builder()
        .name("silent-mode").defaultValue(true).build());

    public final Setting<Boolean> grimSafe = sgGrim.add(new BoolSetting.Builder()
        .name("grim-safe").defaultValue(true).build());

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
    private ClientPlayerEntity neutralPlayer;
    private NeutralInput neutralInput;

    public AutoTotemVipModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem-vip", "Auto totem VIP (Mainhand only, Grim bypass).");
    }

    @Override
    public void onActivate() {
        activeInstance = this;
        delayTimer = 0;
        stage = Stage.Idle;
        swapPhase = SwapPhase.Idle;
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
        pendingSlot = -1;
        pendingButton = -1;
        restoreNeutralInput();
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        return String.valueOf(InvUtils.find(Items.TOTEM_OF_UNDYING).count());
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
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

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

    private void handleIdle() {
        if (mc.player == null) return;

        // CHỈ XỬ LÝ MAINHAND
        if (shouldRefillMainhand()) {
            if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return;

            // Nếu tay đang cầm đéo phải Totem, tìm Totem ở Hotbar
            int hotbarTotem = findHotbarTotemForMainhand();
            if (hotbarTotem != -1) {
                mc.player.getInventory().setSelectedSlot(hotbarTotem);
                syncSelected();
                delayTimer = actionDelay.get();
                return;
            }

            // Nếu Hotbar không có, tìm trong Balo và kéo lên Hotbar
            int invTotem = findTotemInInventoryOnly();
            if (invTotem != -1) {
                if (grimSafe.get() && !hasSafeInventoryClickInput() && swapPhase == SwapPhase.Idle) {
                    swapPhase = SwapPhase.NeutralArmed;
                    return;
                }

                int currentSelected = mc.player.getInventory().getSelectedSlot();
                if (swapTotemDirectly(invTotem, currentSelected)) {
                    delayTimer = actionDelay.get();
                }
            }
        }
    }

    private boolean shouldRefillMainhand() {
        if (mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) return false;

        if (mainhandMode.get() == MainhandMode.Always) return true;

        float totalHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        return totalHp <= mainhandHealth.get();
    }

    private void handleOpen() {
        if (silentMode.get()) {
            stage = Stage.Step3_Swap;
            if (actionDelay.get() == 0) handleSwap();
            else delayTimer = actionDelay.get();
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

    // Hàm kéo chuẩn của Klang (AutoTotem XX)
    private boolean swapTotemDirectly(int inventoryIndex, int targetHotbarSlot) {
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

        mc.interactionManager.clickSlot(handler.syncId, handlerSlot, targetHotbarSlot, SlotActionType.SWAP, mc.player);
        return true;
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

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }

    private int countInventoryTotemsOnly() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 9; i < 36; ++i) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) count++;
        }
        return count;
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
        if (mc.player == null) return -1;
        ScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null) return -1;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() >= 9 && slot.getIndex() <= 35 && slot.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
                return slot.getIndex();
            }
        }
        return -1;
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

            if (swapPhase != SwapPhase.NeutralArmed || mc.player == null || mc.player.input != this) return;

            PlayerInput neutral = new PlayerInput(false, false, false, false, false, original.sneak(), false);
            setState(neutral, Vec2f.ZERO);
            mc.player.setSprinting(false);
            neutralPlayer = mc.player;
            swapPhase = SwapPhase.NeutralApplied;
        }

        private void setState(PlayerInput input, Vec2f movement) {
            playerInput = input == null ? PlayerInput.DEFAULT : input;
            movementVector = movement == null ? Vec2f.ZERO : movement;
        }
    }

    private enum Stage { Idle, Step2_Open, Step3_Swap, Step4_Close }
}
