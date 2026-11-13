package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Random;

public class MainhandModule extends Module {
    public enum RefillMode { Immediate }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSlots = settings.createGroup("Slots");
    private final SettingGroup sgHeal = settings.createGroup("Heal");
    private final SettingGroup sgExtras = settings.createGroup("Extras");

    public final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay")
        .defaultValue(3000).min(0).sliderMax(5000)
        .build());

    public final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay")
        .defaultValue(4000).min(0).sliderMax(5000)
        .build());

    public final Setting<Integer> switchDelay = sgGeneral.add(new IntSetting.Builder()
        .name("switch-delay")
        .defaultValue(1000).min(0).sliderMax(5000)
        .build());

    public final Setting<RefillMode> refillMode = sgGeneral.add(new EnumSetting.Builder<RefillMode>()
        .name("refill-mode")
        .defaultValue(RefillMode.Immediate)
        .build());

    public final Setting<Integer> slot1 = sgSlots.add(new IntSetting.Builder()
        .name("slot-1")
        .defaultValue(1).min(1).max(9).sliderMin(1).sliderMax(9)
        .build());

    public final Setting<Integer> slot2 = sgSlots.add(new IntSetting.Builder()
        .name("slot-2")
        .defaultValue(2).min(1).max(9).sliderMin(1).sliderMax(9)
        .build());

    public final Setting<Boolean> heal = sgHeal.add(new BoolSetting.Builder()
        .name("heal")
        .defaultValue(true)
        .build());

    public final Setting<Double> health = sgHeal.add(new DoubleSetting.Builder()
        .name("health")
        .defaultValue(16.0).min(0.0).max(36.0).sliderMax(36.0)
        .visible(heal::get)
        .build());

    public final Setting<Boolean> useGapple = sgExtras.add(new BoolSetting.Builder()
        .name("use-gapple")
        .defaultValue(true)
        .build());

    public final Setting<Boolean> rightClickEat = sgExtras.add(new BoolSetting.Builder()
        .name("right-click-eat")
        .defaultValue(true)
        .build());

    public final Setting<Boolean> chatNotify = sgExtras.add(new BoolSetting.Builder()
        .name("chat-notify")
        .defaultValue(true)
        .build());

    public final Setting<Boolean> drawn = sgExtras.add(new BoolSetting.Builder()
        .name("drawn")
        .defaultValue(true)
        .build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    
    private final long[] slotCooldowns = new long[9];
    private final Random random = new Random();
    private boolean eatingHotbar = false;
    private int previousHotbarSlot = -1;
    private int awaitVerifyTicks = -1;
    private long lastSwitchTime = 0;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Auto Totem (Mainhand) + Heal & Extras.");
    }

    @Override
    public void onActivate() {
        for (int i = 0; i < 9; i++) slotCooldowns[i] = 0;
        eatingHotbar = false;
        previousHotbarSlot = -1;
        awaitVerifyTicks = -1;
        lastSwitchTime = 0;
    }

    @Override
    public void onDeactivate() {
        if (eatingHotbar && mc.player != null && previousHotbarSlot != -1) {
            mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
            syncSelected();
        }
        eatingHotbar = false;
        previousHotbarSlot = -1;
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        return String.valueOf(countInventoryTotemsOnly());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.player.isCreative() || mc.player.isSpectator()) return;

        if (awaitVerifyTicks >= 0) {
            if (awaitVerifyTicks > 0) {
                awaitVerifyTicks--;
                return;
            }
            awaitVerifyTicks = -1;
        }

        long now = System.currentTimeMillis();

        if (useGapple.get() && mc.options.useKey.isPressed() && rightClickEat.get()) {
            int gapSlot = findGappleHotbar();
            if (gapSlot != -1 && mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                if (!eatingHotbar) {
                    previousHotbarSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(gapSlot);
                    syncSelected();
                    eatingHotbar = true;
                    return;
                }
            }
        } else if (eatingHotbar) {
            if (previousHotbarSlot != -1) {
                mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
                syncSelected();
            }
            eatingHotbar = false;
            previousHotbarSlot = -1;
        }

        if (eatingHotbar) return;

        boolean need = needsTotem();
        if (!need) return;

        int targetA = slot1.get() - 1;
        int targetB = slot2.get() - 1;

        boolean aHas = mc.player.getInventory().getStack(targetA).isOf(Items.TOTEM_OF_UNDYING);
        boolean bHas = mc.player.getInventory().getStack(targetB).isOf(Items.TOTEM_OF_UNDYING);

        if (aHas || bHas) {
            int selected = mc.player.getInventory().getSelectedSlot();
            int targetSlot = aHas ? targetA : targetB;
            if (selected != targetSlot) {
                if (now - lastSwitchTime >= switchDelay.get()) {
                    mc.player.getInventory().setSelectedSlot(targetSlot);
                    syncSelected();
                    lastSwitchTime = now;
                }
            }
            return;
        }

        boolean anyHotbarTotem = false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                anyHotbarTotem = true;
                if (mc.player.getInventory().getSelectedSlot() != i) {
                    if (now - lastSwitchTime >= switchDelay.get()) {
                        mc.player.getInventory().setSelectedSlot(i);
                        syncSelected();
                        lastSwitchTime = now;
                    }
                }
                break;
            }
        }

        if (anyHotbarTotem) return;

        int invTotem = findTotemInInventoryOnly();
        if (invTotem == -1) {
            slotCooldowns[targetA] = 0;
            slotCooldowns[targetB] = 0;
            return;
        }

        if (slotCooldowns[targetA] == 0) {
            slotCooldowns[targetA] = now + getRandomDelay();
        }
        if (slotCooldowns[targetB] == 0) {
            slotCooldowns[targetB] = now + getRandomDelay();
        }

        if (now >= slotCooldowns[targetA]) {
            if (swapTotemDirectly(invTotem, targetA)) {
                slotCooldowns[targetA] = 0;
                awaitVerifyTicks = 3;
                if (now - lastSwitchTime >= switchDelay.get()) {
                    mc.player.getInventory().setSelectedSlot(targetA);
                    syncSelected();
                    lastSwitchTime = now;
                }
                return;
            }
        }
        
        if (now >= slotCooldowns[targetB]) {
            if (swapTotemDirectly(invTotem, targetB)) {
                slotCooldowns[targetB] = 0;
                awaitVerifyTicks = 3;
                if (now - lastSwitchTime >= switchDelay.get()) {
                    mc.player.getInventory().setSelectedSlot(targetB);
                    syncSelected();
                    lastSwitchTime = now;
                }
                return;
            }
        }
    }

    private long getRandomDelay() {
        int min = Math.min(minDelay.get(), maxDelay.get());
        int max = Math.max(minDelay.get(), maxDelay.get());
        if (min == max) return min;
        return min + random.nextInt(max - min + 1);
    }

    private boolean needsTotem() {
        if (heal.get()) {
            float totalHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (totalHp <= health.get()) return true;
        }
        return false;
    }

    private boolean swapTotemDirectly(int inventoryIndex, int targetHotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int handlerSlot = resolvePlayerHandlerSlot(handler, inventoryIndex);
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) return false;

        Slot source = handler.getSlot(handlerSlot);
        if (source.inventory != mc.player.getInventory() || source.getIndex() != inventoryIndex || !source.getStack().isOf(Items.TOTEM_OF_UNDYING)) return false;
        
        if (handler.getCursorStack() != null && !handler.getCursorStack().isEmpty()) return false;

        mc.interactionManager.clickSlot(handler.syncId, handlerSlot, targetHotbarSlot, SlotActionType.SWAP, mc.player);
        return true;
    }

    private void syncSelected() {
        try {
            java.lang.reflect.Method m = net.minecraft.client.network.ClientPlayerInteractionManager.class.getDeclaredMethod("syncSelectedSlot");
            m.setAccessible(true);
            m.invoke(mc.interactionManager);
        } catch (Exception ignored) {}
    }

    private int findGappleHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENCHANTED_GOLDEN_APPLE) || mc.player.getInventory().getStack(i).isOf(Items.GOLDEN_APPLE)) return i;
        }
        return -1;
    }

    private int findTotemInInventoryOnly() {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private int countInventoryTotemsOnly() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 9; i < 36; ++i) {
            if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) count++;
        }
        return count;
    }

    private static int resolvePlayerHandlerSlot(ScreenHandler handler, int playerInventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == playerInventoryIndex) return i;
        }
        return -1;
    }
}
