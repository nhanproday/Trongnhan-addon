package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.lang.reflect.Method;
import java.util.Optional;

public class VelocityModule extends Module {
    public enum Mode { Normal, Walls, Cancel, Grim }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAntiPush = settings.createGroup("AntiPush");

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("Mode").description("The method used to modify knockback.")
        .defaultValue(Mode.Cancel).build());

    public final Setting<Integer> horizontal = sgGeneral.add(new IntSetting.Builder()
        .name("Horizontal").description("Horizontal knockback received.")
        .defaultValue(0).min(0).max(100)
        .visible(() -> mode.get() == Mode.Normal || mode.get() == Mode.Walls).build());

    public final Setting<Integer> vertical = sgGeneral.add(new IntSetting.Builder()
        .name("Vertical").description("Vertical knockback received.")
        .defaultValue(0).min(0).max(100)
        .visible(() -> mode.get() == Mode.Normal || mode.get() == Mode.Walls).build());

    public final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("OnlyOnGround").description("Only reduces knockback while phased AND on ground.")
        .defaultValue(false).visible(() -> mode.get() == Mode.Walls).build());

    public final Setting<Boolean> explosions = sgGeneral.add(new BoolSetting.Builder()
        .name("Explosions").description("Modifies explosion knockback.")
        .defaultValue(true).build());

    public final Setting<Boolean> pause = sgGeneral.add(new BoolSetting.Builder()
        .name("Pause").description("Pauses velocity after rubberband.")
        .defaultValue(true).visible(() -> mode.get() == Mode.Cancel || mode.get() == Mode.Grim).build());

    public final Setting<Boolean> onlyPhased = sgGeneral.add(new BoolSetting.Builder()
        .name("OnlyPhased").description("Only reduces knockback while phased.")
        .defaultValue(false).visible(() -> mode.get() == Mode.Grim).build());

    public final Setting<Boolean> onlyWhenHeadCovered = sgGeneral.add(new BoolSetting.Builder()
        .name("OnlyCoveredHead").description("Only reduces knockback when head is covered.")
        .defaultValue(false).visible(() -> mode.get() == Mode.Grim).build());

    public final Setting<Double> fallDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("FallDistance").description("Pause velocity if falling more than this distance.")
        .defaultValue(10.0).min(0.0).max(50.0).build());

    public final Setting<Boolean> conceal = sgGeneral.add(new BoolSetting.Builder()
        .name("Conceal").description("Ignores fake 0-0-0 velocity packets after setback.")
        .defaultValue(true).build());

    public final Setting<Boolean> antiPush = sgAntiPush.add(new BoolSetting.Builder()
        .name("Entities").description("Prevents entities from pushing you.")
        .defaultValue(true).build());

    public final Setting<Boolean> antiLiquidPush = sgAntiPush.add(new BoolSetting.Builder()
        .name("Liquids").description("Prevents liquids from pushing you.")
        .defaultValue(false).build());

    public final Setting<Boolean> antiBlockPush = sgAntiPush.add(new BoolSetting.Builder()
        .name("Blocks").description("Prevents blocks from pushing you.")
        .defaultValue(true).build());

    public final Setting<Boolean> antiFishingRod = sgAntiPush.add(new BoolSetting.Builder()
        .name("FishingRods").description("Prevents fishing rods from pushing you.")
        .defaultValue(false).build());

    public final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("ChatNotify").description("Notifies in chat.")
        .defaultValue(true).build());

    public final Setting<Boolean> drawn = sgGeneral.add(new BoolSetting.Builder()
        .name("Drawn").description("Draws HUD.")
        .defaultValue(true).build());

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private boolean pendingConcealment = false;
    private boolean pendingVelocity = false;
    private long lastSetbackTime = 0;

    public VelocityModule() {
        super(TrongNhanAddon.CATEGORY, "velocity", "Modifies the amount of knockback that you receive.");
    }

    @Override
    public void onActivate() {
        pendingVelocity = false;
        pendingConcealment = false;
        lastSetbackTime = 0;
    }

    @Override
    public void onDeactivate() {
        if (pendingVelocity && mode.get() == Mode.Grim) {
            sendRotationFix();
        }
        pendingVelocity = false;
        pendingConcealment = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (pendingVelocity && mode.get() == Mode.Grim) {
            sendRotationFix();
        }
        pendingVelocity = false;
        pendingConcealment = false;
    }

    private void sendRotationFix() {
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        float f2 = MathHelper.clamp(pitch + f, -90.0f, 90.0f);
        
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, f2, mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (fallDistance.get() > 0.0 && mc.player.fallDistance >= fallDistance.get()) return;

        if (event.packet instanceof PlayerPositionLookS2CPacket && conceal.get()) {
            pendingConcealment = true;
        }

        if (event.packet instanceof EntityVelocityUpdateS2CPacket packet) {
            if (packet.getEntityId() != mc.player.getId()) return;

            Vec3d movement = new Vec3d(packet.getVelocity().x, packet.getVelocity().y, packet.getVelocity().z);
            
            if (pendingConcealment && movement.x == 0.0 && movement.y == 0.0 && movement.z == 0.0) {
                pendingConcealment = false;
                return;
            }

            switch (mode.get()) {
                case Normal -> scaleVelocity(event, movement);
                case Walls -> {
                    if (!isPhased(mc.player) || (onlyOnGround.get() && !mc.player.isOnGround())) return;
                    scaleVelocity(event, movement);
                }
                case Cancel -> {
                    if (pause.get() && System.currentTimeMillis() - lastSetbackTime < 100L) return;
                    event.cancel();
                }
                case Grim -> {
                    if (pause.get() && System.currentTimeMillis() - lastSetbackTime < 100L) return;
                    if (onlyPhased.get() && !isPhased(mc.player)) return;
                    if (onlyWhenHeadCovered.get()) {
                        BlockPos target = mc.player.getBlockPos().up(2);
                        if (mc.player.isCrawling()) target = mc.player.getBlockPos().up(1);
                        if (mc.world.getBlockState(target).isAir()) return;
                    }
                    scaleVelocity(event, movement);
                    pendingVelocity = true;
                }
            }
        }

        if (event.packet instanceof ExplosionS2CPacket packet && explosions.get()) {
            try {
                Method getKnockback = packet.getClass().getMethod("getPlayerKnockback");
                Optional<Vec3d> opt = (Optional<Vec3d>) getKnockback.invoke(packet);
                Vec3d knockback = opt.orElse(Vec3d.ZERO);
                
                switch (mode.get()) {
                    case Normal -> scaleExplosion(packet, knockback);
                    case Walls -> {
                        if (isPhased(mc.player)) scaleExplosion(packet, knockback);
                    }
                    case Cancel -> {
                        if (pause.get() && System.currentTimeMillis() - lastSetbackTime < 100L) return;
                        event.cancel();
                    }
                    case Grim -> {
                        if (pause.get() && System.currentTimeMillis() - lastSetbackTime < 100L) return;
                        if (onlyPhased.get() && !isPhased(mc.player)) return;
                        if (onlyWhenHeadCovered.get()) {
                            BlockPos target = mc.player.getBlockPos().up(2);
                            if (mc.player.isCrawling()) target = mc.player.getBlockPos().up(1);
                            if (mc.world.getBlockState(target).isAir()) return;
                        }
                        scaleExplosion(packet, knockback);
                        pendingVelocity = true;
                    }
                }

                if (event.isCancelled()) {
                    mc.execute(() -> {
                        try {
                            Method getX = packet.getClass().getMethod("getX");
                            Method getY = packet.getClass().getMethod("getY");
                            Method getZ = packet.getClass().getMethod("getZ");
                            Method getSound = packet.getClass().getMethod("getExplosionSound");

                            double x = (double) getX.invoke(packet);
                            double y = (double) getY.invoke(packet);
                            double z = (double) getZ.invoke(packet);
                            RegistryEntry<SoundEvent> soundEntry = (RegistryEntry<SoundEvent>) getSound.invoke(packet);

                            mc.world.playSound(
                                x, y, z,
                                soundEntry.value(), SoundCategory.BLOCKS,
                                4.0F, (1.0F + (mc.world.random.nextFloat() - mc.world.random.nextFloat()) * 0.2F) * 0.7F, false
                            );
                            
                            mc.world.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1.0, 0.0, 0.0);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
        }

        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            lastSetbackTime = System.currentTimeMillis();
        }
    }

    private void scaleVelocity(PacketEvent.Receive event, Vec3d target) {
        Vec3d velocity = mc.player.getVelocity();
        double x = (target.x - velocity.x) * (horizontal.get() / 100.0) + velocity.x;
        double y = (target.y - velocity.y) * (vertical.get() / 100.0) + velocity.y;
        double z = (target.z - velocity.z) * (horizontal.get() / 100.0) + velocity.z;
        
        event.cancel();
        mc.player.setVelocity(x, y, z);
    }

    private void scaleExplosion(ExplosionS2CPacket packet, Vec3d knockback) {
        if (knockback == Vec3d.ZERO) return;
        
        double x = knockback.x * (horizontal.get() / 100.0);
        double y = knockback.y * (vertical.get() / 100.0);
        double z = knockback.z * (horizontal.get() / 100.0);
        
        mc.player.addVelocity(x, y, z);
    }

    private boolean isPhased(Entity entity) {
        if (entity == null || mc.world == null) return false;
        
        Box box = entity.getBoundingBox();
        int minX = MathHelper.floor(box.minX);
        int maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.minY);
        int maxY = MathHelper.ceil(box.maxY);
        int minZ = MathHelper.floor(box.minZ);
        int maxZ = MathHelper.ceil(box.maxZ);
        
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    VoxelShape shape = mc.world.getBlockState(pos).getCollisionShape(mc.world, pos);
                    if (!shape.isEmpty() && shape.getBoundingBox().offset(pos).intersects(box)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String getInfoString() {
        if (mode.get() == Mode.Cancel) return "0%, 0%";
        if (mode.get() == Mode.Grim) return "Grim";
        return horizontal.get() + "%, " + vertical.get() + "%";
    }
}
