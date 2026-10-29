package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;

public class AutoTotemModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<String> item = sgGeneral.add(new EnumSetting.Builder<String>()
        .name("Item").description("The item that will be placed in your offhand slot.")
        .defaultValue("Totem").values("Totem", "Crystal", "Gapple").build());

    public final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("Health").description("The health at which a totem will be prioritized.")
        .defaultValue(16).min(0).max(36)
        .visible(() -> !item.get().equals("Totem")).build());

    public final Setting<Boolean> elytraCheck = sgGeneral.add(new BoolSetting.Builder()
        .name("ElytraCheck").description("Prioritizes a totem whenever you're wearing an elytra.")
        .defaultValue(false).build());

    public final Setting<Double> fallDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("FallDistance").description("The fall distance at which the module will prioritize a totem.")
        .defaultValue(20.43).min(0).max(80).build());

    public final Setting<Boolean> useGapple = sgGeneral.add(new BoolSetting.Builder()
        .name("UseGapple").description("Switches to a golden apple in your offhand based on main hand items.")
        .defaultValue(true).build());

    public final Setting<Boolean> gappleSword = sgGeneral.add(new BoolSetting.Builder()
        .name("Sword").description("Switches to a gapple when holding a sword or axe.")
        .defaultValue(true).visible(useGapple::get).build());

    public final Setting<Boolean> gapplePickaxe = sgGeneral.add(new BoolSetting.Builder()
        .name("Pickaxe").description("Switches to a gapple when holding a pickaxe.")
        .defaultValue(true).visible(useGapple::get).build());

    public final Setting<Boolean> gappleTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("Totem").description("Switches to a gapple when holding a totem.")
        .defaultValue(true).visible(useGapple::get).build());

    public final Setting<Boolean> lethalOverride = sgGeneral.add(new BoolSetting.Builder()
        .name("LethalOverride").description("Overrides any necessity for a totem when gappling.")
        .defaultValue(true).visible(useGapple::get).build());

    public final Setting<Boolean> noTotemGap = sgGeneral.add(new BoolSetting.Builder()
        .name("NoTotemGap").description("Swap your gap into offhand when no totems left.")
        .defaultValue(false).build());

    public final Setting<Boolean> version117 = sgGeneral.add(new BoolSetting.Builder()
        .name("1.17+").description("Uses the SWAP inventory action.")
        .defaultValue(true).build());

    public final Setting<Boolean> smartMine = sgGeneral.add(new BoolSetting.Builder()
        .name("SmartMine").description("Switches to a crystal whenever you start mining.")
        .defaultValue(false).visible(() -> item.get().equals("Crystal")).build());

    public final Setting<Boolean> antiMace = sgGeneral.add(new BoolSetting.Builder()
        .name("AntiMace").description("Switches to a totem if a player is trying to smash attack you.")
        .defaultValue(false).build());

    public final Setting<Double> maceRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("MaceRange").description("The distance at which an enemy has to be in with a mace.")
        .defaultValue(12.0).min(0).max(24).visible(antiMace::get).build());

    public final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("Debug").description("Logs why you died.")
        .defaultValue(false).build());

    public final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("ChatNotify").description("Notifies in chat.")
        .defaultValue(true).build());

    public final Setting<Boolean> drawn = sgGeneral.add(new BoolSetting.Builder()
        .name("Drawn").description("Draws HUD.")
        .defaultValue(true).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private int totemCount = 0;
    private int gappleStagedSlot = -1;

    public AutoTotemModule() {
        super(TrongNhanAddon.CATEGORY, "auto-totem", "Automatically puts a specified item in your offhand slot.");
    }

    @Override
    public void onActivate() {
        gappleStagedSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        totemCount = countItem(Items.TOTEM_OF_UNDYING);
        updateTotem();
    }

    private void updateTotem() {
        if (mc.player == null || mc.world == null) return;
        
        boolean isInventoryOpen = mc.currentScreen instanceof InventoryScreen;
        if (mc.currentScreen != null && !isInventoryOpen) return;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;

        Item targetItem = getItem();
        if (targetItem == null) return;

        if (mc.player.getOffHandStack().getItem() == targetItem) return;

        int slot = -1;
        if (targetItem == Items.TOTEM_OF_UNDYING && gappleStagedSlot != -1) {
            if (mc.player.getInventory().getStack(gappleStagedSlot).getItem() == Items.TOTEM_OF_UNDYING) {
                slot = gappleStagedSlot;
            } else {
                slot = findInventory(Items.TOTEM_OF_UNDYING);
            }
            gappleStagedSlot = -1;
        } else if (targetItem == Items.ENCHANTED_GOLDEN_APPLE || targetItem == Items.GOLDEN_APPLE) {
            slot = findHotbar(targetItem);
            if (slot == -1) slot = findInventory(targetItem);
            if (slot != -1) gappleStagedSlot = slot;
        } else {
            slot = findInventory(targetItem);
            if (slot == -1) slot = findHotbar(targetItem);
        }

        if (slot == -1) return;

        if (!version117.get() && mc.player.isUsingItem()) {
            mc.interactionManager.stopUsingItem(mc.player);
        }

        int containerSlot = slot < 9 ? 36 + slot : slot;
        if (version117.get()) {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSlot, 40, SlotActionType.SWAP, mc.player);
        } else {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, 45, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    private Item getItem() {
        boolean hasTotem = hasItem(Items.TOTEM_OF_UNDYING);
        Item gappleItem = hasItem(Items.ENCHANTED_GOLDEN_APPLE) ? Items.ENCHANTED_GOLDEN_APPLE : (hasItem(Items.GOLDEN_APPLE) ? Items.GOLDEN_APPLE : null);
        boolean hasGapple = gappleItem != null;

        boolean holdingSword = mc.player.getMainHandStack().isIn(ItemTags.SWORDS) || mc.player.getMainHandStack().getItem() instanceof AxeItem;
        boolean holdingPickaxe = mc.player.getMainHandStack().isIn(ItemTags.PICKAXES);
        boolean holdingTotem = mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);

        boolean mainHandMatches = gappleSword.get() && holdingSword || gapplePickaxe.get() && holdingPickaxe || gappleTotem.get() && holdingTotem;

        if (useGapple.get() && mc.options.useKey.isPressed() && mainHandMatches && hasGapple && (lethalOverride.get() || !needsTotem() || !hasTotem)) {
            return gappleItem;
        }

        if (needsTotem()) {
            if (hasTotem) return Items.TOTEM_OF_UNDYING;
            if (noTotemGap.get() && hasGapple) return gappleItem;
        }

        switch (item.get()) {
            case "Crystal": if (hasItem(Items.END_CRYSTAL)) return Items.END_CRYSTAL; break;
            case "Gapple": if (hasGapple) return gappleItem; break;
            default: if (hasTotem) return Items.TOTEM_OF_UNDYING; break;
        }

        if (hasTotem) return Items.TOTEM_OF_UNDYING;
        if (noTotemGap.get() && hasGapple) return gappleItem;
        return mc.player.getOffHandStack().getItem();
    }

    private boolean needsTotem() {
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() <= health.get()) return true;
        if (mc.player.fallDistance > fallDistance.get()) return true;
        if (elytraCheck.get() && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return true;
        
        if (antiMace.get() && mc.world != null) {
            double r2 = MathHelper.square(maceRange.get().floatValue());
            return mc.world.getPlayers().stream().anyMatch(p -> p != mc.player && p.distanceTo(mc.player) <= r2 && p.fallDistance >= 1.5 && p.getMainHandStack().isOf(Items.MACE));
        }
        return false;
    }

    private boolean hasItem(Item item) {
        return findInventory(item) != -1 || findHotbar(item) != -1 || mc.player.getOffHandStack().getItem() == item;
    }

    private int findInventory(Item item) {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    private int findHotbar(Item item) {
        for (int i = 0; i <= 8; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) count += mc.player.getInventory().getStack(i).getCount();
        }
        return count;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(totemCount);
    }
}
