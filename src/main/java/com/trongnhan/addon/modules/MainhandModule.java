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

public class MainhandModule extends Module {
    public enum RefillMode { Immediate }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHeal = settings.createGroup("Heal");
    private final SettingGroup sgExtras = settings.createGroup("Extras");

    public final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("slot")
        .defaultValue(5).min(1).max(9).sliderMin(1).sliderMax(9)
        .build());

    public final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .defaultValue(500).min(0).sliderMax(2000)
        .build());

    public final Setting<RefillMode> refillMode = sgGeneral.add(new EnumSetting.Builder<RefillMode>()
        .name("refill-mode")
        .defaultValue(RefillMode.Immediate)
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
    
    private long lastActionTime = 0;
    private int lastInventoryTotemCount = -1;
    private int lastTargetSlot = -1;
    private boolean eatingHotbar = false;
    private int previousHotbarSlot = -1;

    public MainhandModule() {
        super(TrongNhanAddon.CATEGORY, "mainhand", "Auto Totem (Mainhand) + Heal & Extras.");
    }

    @Override
    public void onActivate() {
        lastActionTime = System.currentTimeMillis();
        lastInventoryTotemCount = -1;
        lastTargetSlot = -1;
        eatingHotbar = false;
        previousHotbarSlot = -1;
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

        int currentInvTotems = countInventoryTotemsOnly();
        if (chatNotify.get() && lastInventoryTotemCount != -1 && currentInvTotems < lastInventoryTotemCount) {
            if (currentInvTotems <= 5) {
                ChatUtils.sendMsg(Text.literal("§fCANH BAO: Kho do chi con §e" + currentInvTotems + " §fTotem!"));
                if (chatNotify.get()) mc.player.playSound(SoundEvents.ENTITY_IRON_GOLEM_HURT, 1.0F, 0.6F);
            }
        }
        lastInventoryTotemCount = currentInvTotems;

        long now = System.currentTimeMillis();
        if (now - lastActionTime < delay.get()) return;

        int target = slot.get() - 1;

        if (lastTargetSlot != target) {
            lastTargetSlot = target;
            lastActionTime = now;
        }

        if (useGapple.get() && mc.options.useKey.isPressed() && rightClickEat.get()) {
            int gapSlot = findGappleHotbar();
            if (gapSlot != -1 && mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                if (!eatingHotbar) {
                    previousHotbarSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(gapSlot);
                    syncSelected();
                    eatingHotbar = true;
                    lastActionTime = now;
                    return;
                }
            }
        } else if (eatingHotbar) {
            if (previousHotbarSlot != -1) {
                mc.player.getInventory().setSelectedSlot(previousHotbarSlot);
                syncSelected();
                lastActionTime = now;
            }
            eatingHotbar = false;
            previousHotbarSlot = -1;
        }

        if (eatingHotbar) return;

        if (needsTotem()) {
            int hotbarTotem = findHotbarTotemForMainhand();
            if (hotbarTotem != -1 && hotbarTotem != target) {
                swapTotemDirectly(36 + hotbarTotem, target);
                lastActionTime = now;
            }
        }

        boolean slotHasTotem = mc.player.getInventory().getStack(target).isOf(Items.TOTEM_OF_UNDYING);

        if (!slotHasTotem) {
            int invTotem = findTotemInInventoryOnly();
            if (invTotem != -1) {
                if (swapTotemDirectly(invTotem, target)) {
                    lastActionTime = now;
                    mc.player.getInventory().setSelectedSlot(target);
                    syncSelected();
                    return;
                }
            }
        } else {
            int selectedSlot = mc.player.getInventory().getSelectedSlot();
            if (selectedSlot != target && !eatingHotbar) {
                if (needsTotem()) {
                    mc.player.getInventory().setSelectedSlot(target);
                    syncSelected();
                    lastActionTime = now;
                }
            }
        }
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
        if (source.inventory != mc.player.getInventory() || source.getIndex() != inventoryIndex) return false;

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

    private int findHotbarTotemForMainhand() {
        if (mc.player == null) return -1;
        int current = mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; ++i) {
            if (i != current && mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
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
