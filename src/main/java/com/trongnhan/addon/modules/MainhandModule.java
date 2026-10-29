package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.class_1657;
import net.minecraft.class_1661;
import net.minecraft.class_1703;
import net.minecraft.class_1713;
import net.minecraft.class_1735;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_310;
import net.minecraft.class_437;
import net.minecraft.class_490;
import net.minecraft.class_746;
import org.lwjgl.glfw.GLFW;

public class MainhandModule extends Module {

    private enum Stage {
        IDLE,
        TAP,
        OPEN,
        OFFHAND,
        HOTBAR,
        CLOSE
    }

    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INV_START = 9;
    private static final int MAIN_INV_END = 36;
    private static final int OFFHAND_CONTAINER_SLOT = 45;
    private static final int HOTBAR_CONTAINER_START = 36;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> targetHotbarSlot = sgGeneral.add(
        new IntSetting.Builder()
            .name("hotbar-slot")
            .description("Slot hotbar (1-9) can refill totem")
            .defaultValue(1)
            .min(1)
            .max(9)
            .build()
    );

    private final Setting<Integer> stepDelay = sgGeneral.add(
        new IntSetting.Builder()
            .name("step-delay")
            .description("Delay giua cac buoc, don vi tick")
            .defaultValue(2)
            .min(1)
            .max(20)
            .build()
    );

    private final Setting<Boolean> silent = sgGeneral.add(
        new BoolSetting.Builder()
            .name("silent")
            .description("Bat: gui packet thang, khong hien inv. Tat: mo inv binh thuong")
            .defaultValue(true)
            .build()
    );

    private int delayTimer;
    private Stage stage = Stage.IDLE;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand",
            "Tu refill totem offhand + hotbar");
    }

    @Override
    public void onActivate() {
        delayTimer = 0;
        stage = Stage.IDLE;
    }

    @Override
    public void onDeactivate() {
        class_310 mc = class_310.method_1551();
        if (mc.field_1724 != null && mc.field_1755 instanceof class_490) {
            mc.field_1724.method_1507(null);
        }
        stage = Stage.IDLE;
        delayTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        class_310 mc = class_310.method_1551();
        class_746 player = mc.field_1724;
        if (player == null) return;

        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        switch (stage) {
            case IDLE    -> tickIdle(player, mc.field_1755);
            case TAP     -> tickTap(player);
            case OPEN    -> tickOpen(player, mc.field_1755);
            case OFFHAND -> tickOffhand(player, mc.field_1755);
            case HOTBAR  -> tickHotbar();
            case CLOSE   -> tickClose(player);
        }
    }

    private void tickIdle(class_746 player, class_437 screen) {
        if (screen != null) return;
        if (countAllTotems(player) <= 0) return;

        boolean offhandEmpty = !isTotem(player.method_6079());
        boolean hotbarNeedsRefill = findHotbarTotem(player) < 0;
        if (!offhandEmpty && !hotbarNeedsRefill) return;

        simulatePhysicalTap(targetHotbarSlot.get() - 1);
        stage = Stage.TAP;
        delayTimer = stepDelay.get();
    }

    private void tickTap(class_746 player) {
        if (!hasSpareTotem(player)) {
            stage = Stage.CLOSE;
            return;
        }
        stage = Stage.OPEN;
        delayTimer = stepDelay.get();
    }

    private void tickOpen(class_746 player, class_437 screen) {
        if (!silent.get()) {
            if (!(screen instanceof class_490)) {
                player.method_1507(screen);
            }
        }
        stage = Stage.OFFHAND;
        delayTimer = stepDelay.get();
    }

    private void tickOffhand(class_746 player, class_437 screen) {
        if (!silent.get() && !(screen instanceof class_490)) {
            stage = Stage.IDLE;
            return;
        }
        boolean offhandEmpty = !isTotem(player.method_6079());
        boolean hotbarNeedsRefill = findHotbarTotem(player) < 0;
        replenish(player, offhandEmpty, hotbarNeedsRefill);
        stage = Stage.HOTBAR;
        delayTimer = stepDelay.get();
    }

    private void tickHotbar() {
        stage = Stage.CLOSE;
        delayTimer = stepDelay.get();
    }

    private void tickClose(class_746 player) {
        if (!silent.get()) {
            player.method_1507(null);
        }
        stage = Stage.IDLE;
        delayTimer = stepDelay.get();
    }

    private static boolean isTotem(class_1799 stack) {
        return stack != null && stack.method_31574(class_1802.field_8288);
    }

    private int countAllTotems(class_746 player) {
        class_1661 inv = player.method_31548();
        int count = 0;
        for (int i = 0; i < inv.method_5438(); i++) {
            class_1799 stack = inv.method_5438(i);
            if (isTotem(stack)) count += stack.method_7947();
        }
        return count;
    }

    private int findHotbarTotem(class_746 player) {
        class_1661 inv = player.method_31548();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (isTotem(inv.method_5438(i))) return i;
        }
        return -1;
    }

    private int findTotemInInventoryOnly(class_746 player) {
        class_1661 inv = player.method_31548();
        for (int i = MAIN_INV_START; i < MAIN_INV_END; i++) {
            if (isTotem(inv.method_5438(i))) return i;
        }
        return -1;
    }

    private boolean hasSpareTotem(class_746 player) {
        return findTotemInInventoryOnly(player) >= 0 || findHotbarTotem(player) >= 0;
    }

    private boolean hotbarSlotHasTotem(class_746 player, int hotbarIdx) {
        return isTotem(player.method_31548().method_5438(hotbarIdx));
    }

    private void simulatePhysicalTap(int slot) {
        class_310 mc = class_310.method_1551();
        class_746 player = mc.field_1724;
        if (player == null || player.field_7498 == null) return;
        int syncId = player.field_7498.field_7763;
        player.field_7498.method_2906(syncId, slot, 40, class_1713.field_7791, player);
    }

    private void simulateMouseOverSlot(int slot) {
        class_310 mc = class_310.method_1551();
        if (!(mc.field_1755 instanceof class_490 inv) || inv.field_7763 == null) return;
        class_1735 slotObj = inv.field_7763.method_7611(slot);
        if (slotObj == null) return;
        double scale = mc.method_22683().method_4490();
        double mouseX = (inv.field_1755 + slotObj.field_7873) * scale;
        double mouseY = (inv.field_1756 + slotObj.field_7872) * scale;
        GLFW.glfwSetCursorPos(mc.method_22683().method_4480(), mouseX, mouseY);
    }

    private void executeKeybindSwap(int fromSlot, int toSlot) {
        class_310 mc = class_310.method_1551();
        class_746 player = mc.field_1724;
        if (player == null || player.field_7498 == null) return;

        class_1703 handler;
        if (silent.get()) {
            handler = player.field_7498;
        } else {
            class_437 screen = mc.field_1755;
            if (!(screen instanceof class_490 inv) || inv.field_7763 == null) return;
            handler = inv.field_7763;
        }

        int syncId = handler.field_7763;
        player.field_7498.method_2906(syncId, fromSlot, 0, class_1713.field_7791, player);
        player.field_7498.method_2906(syncId, toSlot, 0, class_1713.field_7791, player);
    }

    private void replenish(class_746 player, boolean offhandEmpty, boolean hotbarNeedsRefill) {
        if (offhandEmpty) {
            int src = findTotemInInventoryOnly(player);
            if (src >= 0) executeKeybindSwap(src, OFFHAND_CONTAINER_SLOT);
        }
        if (hotbarNeedsRefill) {
            int targetIdx = targetHotbarSlot.get() - 1;
            int src = findTotemInInventoryOnly(player);
            if (src >= 0 && !hotbarSlotHasTotem(player, targetIdx)) {
                executeKeybindSwap(src, HOTBAR_CONTAINER_START + targetIdx);
            }
        }
    }
}
