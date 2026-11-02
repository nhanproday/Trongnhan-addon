package com.trongnhan.addon.modules;

import com.trongnhan.addon.TrongNhanAddon;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent.Post;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Receive;
import meteordevelopment.meteorclient.events.packets.PacketEvent.Sent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Pre;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.EnumSetting.Builder;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public final class SpeedMinePlusPlus extends TrongNhanAddonModule implements GrimCombatCoordinator.Participant {
   private static final int MINE_PRIORITY = 200;
   private static final int ACK_TIMEOUT_TICKS = 60;
   private static final int FADE_TICKS = 10;
   private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
   private static final long START_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(300L);
   private static final int MAX_RENDER_PULSES = 8;
   private static final MethodHandle SEQUENCED_PACKET_SENDER = findSequencedPacketSender();
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgRender = this.settings.createGroup("ESP");
   private final Setting<SpeedMinePlusPlus.AutoSwapMode> autoSwap = this.sgGeneral
      .add(
         ((Builder)((Builder)((Builder)new Builder().name("auto-swap")).description("Silent uses the fastest suitable hotbar tool on the server."))
               .defaultValue(SpeedMinePlusPlus.AutoSwapMode.Silent))
            .build()
      );
   private final Setting<Boolean> rebreak = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                     .name("rebreak"))
                  .description("Automatically mines the target again after the server replaces it."))
               .defaultValue(true))
            .build()
      );
   private final Setting<Boolean> instantRebreak = this.sgGeneral
      .add(
         ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                        .name("instant-rebreak"))
                     .description("Sends STOP immediately when the server replaces the target. This can flag FastBreak."))
                  .defaultValue(true))
               .visible(this.rebreak::get))
            .build()
      );
   private final Setting<Boolean> esp = this.sgRender
      .add(
         ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                     .name("esp"))
                  .description("Renders the active mining target."))
               .defaultValue(true))
            .build()
      );
   private final Setting<ShapeMode> shapeMode = this.sgRender
      .add(
         ((Builder)((Builder)((Builder)((Builder)new Builder().name("shape-mode")).description("Controls which parts of the target box are rendered."))
                  .defaultValue(ShapeMode.Both))
               .visible(this.esp::get))
            .build()
      );
   private final Setting<Boolean> fade = this.sgRender
      .add(
         ((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)((meteordevelopment.meteorclient.settings.BoolSetting.Builder)new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                        .name("fade"))
                     .description("Fades completed target boxes instead of removing them immediately."))
                  .defaultValue(true))
               .visible(this.esp::get))
            .build()
      );
   private final Setting<Integer> fadeTime = this.sgRender
      .add(
         ((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)((meteordevelopment.meteorclient.settings.IntSetting.Builder)new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                        .name("fade-time"))
                     .description("Fade duration in ticks."))
                  .defaultValue(10))
               .range(1, 40)
               .sliderRange(1, 20)
               .visible(() -> (Boolean)this.esp.get() && (Boolean)this.fade.get()))
            .build()
      );
   private final Setting<SettingColor> sideColor = this.sgRender
      .add(
         ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                     .name("side-color"))
                  .description("Target box side color."))
               .defaultValue(new SettingColor(189, 153, 255, 32))
               .visible(() -> (Boolean)this.esp.get() && this.shapeMode.get() != ShapeMode.Lines))
            .build()
      );
   private final Setting<SettingColor> lineColor = this.sgRender
      .add(
         ((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)((meteordevelopment.meteorclient.settings.ColorSetting.Builder)new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                     .name("line-color"))
                  .description("Target box outline color."))
               .defaultValue(new SettingColor(189, 153, 255, 193))
               .visible(() -> (Boolean)this.esp.get() && this.shapeMode.get() != ShapeMode.Sides))
            .build()
      );
   private final GrimLookGate lookGate = new GrimLookGate();
   private BlockPos targetPos;
   private Direction targetFace;
   private BlockPos abortPos;
   private SpeedMinePlusPlus.Phase phase = SpeedMinePlusPlus.Phase.Idle;
   private int clientTick;
   private int actionTick;
   private boolean serverAirConfirmed;
   private long mineReadyAtNanos;
   private long nextStartAtNanos;
   private final ArrayDeque<SpeedMinePlusPlus.RenderPulse> renderPulses = new ArrayDeque<>();
   private SpeedMinePlusPlus.RotationIntent queuedRotation;
   private SpeedMinePlusPlus.MiningRotationInput rotationInput;
   private float sentYaw;
   private float sentPitch;
   private boolean sentLookKnown;

   public SpeedMinePlusPlus() {
      super(TrongNhanAddon.PVP_CATEGORY, "speed-mine++", "Automatic Grim-safe packet mining with silent tool and look control.");
   }

   @Override
   public void onActivate() {
      this.resetState();
      GrimCombatCoordinator.activate(this);
      super.onActivate();
   }

   @Override
   public void onDeactivate() {
      this.restoreRotationInput();
      this.abortOpenBreak();
      GrimCombatCoordinator.deactivate(this);
      this.resetState();
      super.onDeactivate();
   }

   @TrongNhanHandler(
      priority = 500
   )
   private void onStartBreakingBlock(StartBreakingBlockEvent event) {
      if (this.canTarget(event.blockPos)) {
         event.cancel();
         BlockPos requested = event.blockPos.toImmutable();
         Direction requestedFace = event.direction == null ? this.choosePlausibleFace(requested) : event.direction;
         if (requested.equals(this.targetPos)) {
            if (this.phase == SpeedMinePlusPlus.Phase.Armed && this.serverAirConfirmed && !this.mc.world.getBlockState(requested).isAir()) {
               this.armReplacement(requested);
            }
         } else {
            this.queueAbortIfOpen();
            this.addFade(this.targetPos);
            this.targetPos = requested;
            this.targetFace = requestedFace == null ? Direction.DOWN : requestedFace;
            this.phase = SpeedMinePlusPlus.Phase.NeedsStart;
            this.actionTick = this.clientTick;
            this.serverAirConfirmed = false;
            this.mineReadyAtNanos = 0L;
            this.lookGate.invalidate();
            this.queuedRotation = null;
         }
      }
   }

   @TrongNhanHandler(
      priority = 100
   )
   private void onTickPre(Pre event) {
      this.clientTick++;
      if (this.mc.player != null && this.mc.world != null && this.mc.getNetworkHandler() != null) {
         this.pruneRenderPulses();
         if (this.targetPos != null) {
            if ((Boolean)this.rebreak.get() || this.phase != SpeedMinePlusPlus.Phase.Armed && this.phase != SpeedMinePlusPlus.Phase.InstantRebreak) {
               if (!(Boolean)this.instantRebreak.get() && this.phase == SpeedMinePlusPlus.Phase.InstantRebreak) {
                  this.phase = SpeedMinePlusPlus.Phase.NeedsStart;
               }

               if (this.autoSwap.get() == SpeedMinePlusPlus.AutoSwapMode.None && GrimCombatCoordinator.hasSilentSlot(this)) {
                  GrimCombatCoordinator.restoreSilent(this);
               }

               if (!this.canKeepTarget(this.targetPos)) {
                  this.clearTarget(true);
               } else {
                  if (this.phase == SpeedMinePlusPlus.Phase.AwaitingAir && this.clientTick - this.actionTick > 60) {
                     this.clearTarget(false);
                  }
               }
            } else {
               this.clearTarget(false);
            }
         }
      } else {
         this.resetState();
      }
   }

   @TrongNhanHandler(
      priority = 100
   )
   private void onPlayerTickMovement(PlayerTickMovementEvent event) {
      this.applyQueuedRotationInput();
   }

   @TrongNhanHandler(
      priority = -200
   )
   private void onSendMovementPacketsPost(Post event) {
      this.restoreRotationInput();
      this.lookGate.onMovementPacketsPost();
   }

   @TrongNhanHandler(
      priority = -1000
   )
   private void onPacketSent(Sent event) {
      if (event.packet instanceof PlayerMoveC2SPacket packet && packet.changesLook()) {
         this.sentYaw = packet.getYaw(this.sentYaw);
         this.sentPitch = packet.getPitch(this.sentPitch);
         this.sentLookKnown = true;
         if (this.rotationInput != null && this.rotationInput.applied) {
            GrimCombatCoordinator.observeOwnedLook(this, this.sentYaw, this.sentPitch);
         }
      }
   }

   @TrongNhanHandler(
      priority = -1000
   )
   private void onPacketReceive(Receive event) {
      if (event.packet instanceof BlockUpdateS2CPacket update) {
         this.observeServerBlock(update.getPos(), update.getState());
      } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket update) {
         update.visitUpdates(this::observeServerBlock);
      }
   }

   @TrongNhanHandler
   private void onRender3D(Render3DEvent event) {
      if ((Boolean)this.esp.get() && this.mc.world != null) {
         BlockPos active = this.targetPos;
         if (active != null && !this.mc.world.getBlockState(active).isAir()) {
            this.drawBox(event, active, 1.0);
         }

         if ((Boolean)this.fade.get()) {
            for (SpeedMinePlusPlus.RenderPulse pulse : this.renderPulses) {
               double alpha = this.fadeAlpha(pulse, event.tickDelta);
               if (!(alpha <= 0.0)) {
                  double progress = 1.0 - alpha;
                  this.drawBox(event, new Box(pulse.pos).expand(progress * 0.12), alpha);
               }
            }
         }
      }
   }

   @Override
   public GrimCombatCoordinator.Intent prepareCombatIntent() {
      this.restoreRotationInput();
      this.queuedRotation = null;
      if (this.mc.player == null || this.mc.world == null || this.mc.interactionManager == null || this.mc.getNetworkHandler() == null) {
         return null;
      } else if (this.abortPos != null) {
         BlockPos plannedPos = this.abortPos;
         return this.intent(() -> this.runAbort(plannedPos));
      } else if (this.targetPos != null && !this.isPaused()) {
         BlockPos plannedPos = this.targetPos;
         BlockState state = this.mc.world.getBlockState(plannedPos);
         if (state.isAir()) {
            return null;
         } else if (state.getHardness(this.mc.world, plannedPos) < 0.0F) {
            this.clearTarget(false);
            return null;
         } else {
            Direction plannedFace = this.choosePlausibleFace(plannedPos);
            if (plannedFace == null) {
               plannedFace = this.targetFace;
            }

            if (plannedFace == null) {
               plannedFace = Direction.DOWN;
            }

            if (this.phase == SpeedMinePlusPlus.Phase.NeedsStart) {
               if (System.nanoTime() < this.nextStartAtNanos) {
                  return null;
               } else {
                  Direction finalFace = plannedFace;
                  return this.intent(() -> this.runStart(plannedPos, finalFace));
               }
            } else if (this.phase == SpeedMinePlusPlus.Phase.InstantRebreak) {
               Direction finalFace = plannedFace;
               return this.intent(() -> this.runInstantRebreak(plannedPos, finalFace));
            } else {
               return this.phase == SpeedMinePlusPlus.Phase.Mining && System.nanoTime() >= this.mineReadyAtNanos
                  ? this.intent(() -> this.runFinish(plannedPos, plannedFace))
                  : null;
            }
         }
      } else {
         GrimCombatCoordinator.restoreSilent(this);
         this.lookGate.invalidate();
         return null;
      }
   }

   @Override
   public void suspendForItemUse() {
      GrimCombatCoordinator.restoreSilent(this);
      this.restoreRotationInput();
      this.queuedRotation = null;
      this.lookGate.invalidate();
   }

   @Override
   public int combatPriority() {
      return 200;
   }

   private GrimCombatCoordinator.Intent intent(Runnable action) {
      return new GrimCombatCoordinator.Intent() {
         @Override
         public GrimCombatCoordinator.Participant owner() {
            return SpeedMinePlusPlus.this;
         }

         @Override
         public void execute() {
            action.run();
         }
      };
   }

   private void runStart(BlockPos plannedPos, Direction plannedFace) {
      if (this.phase == SpeedMinePlusPlus.Phase.NeedsStart
         && plannedPos.equals(this.targetPos)
         && this.canKeepTarget(plannedPos)
         && System.nanoTime() >= this.nextStartAtNanos) {
         BlockState state = this.mc.world.getBlockState(plannedPos);
         if (!state.isAir() && !(state.getHardness(this.mc.world, plannedPos) < 0.0F)) {
            if (this.holdAndGateBlock(plannedPos, plannedFace)) {
               if (GrimCombatCoordinator.claimAction(this)) {
                  int slot = this.toolSlot(state);
                  double delta = BlockUtils.getBreakDelta(slot, state);
                  if (!(delta <= 0.0)
                     && Double.isFinite(delta)
                     && this.sendMiningAction(
                        Action.START_DESTROY_BLOCK, plannedPos, plannedFace, slot, this.autoSwap.get() == SpeedMinePlusPlus.AutoSwapMode.Silent && delta < 1.0
                     )) {
                     this.targetFace = plannedFace;
                     this.actionTick = this.clientTick;
                     this.serverAirConfirmed = false;
                     if (delta >= 1.0) {
                        this.mineReadyAtNanos = 0L;
                        this.phase = SpeedMinePlusPlus.Phase.AwaitingAir;
                     } else {
                        this.mineReadyAtNanos = System.nanoTime() + miningDurationNanos(delta);
                        this.phase = SpeedMinePlusPlus.Phase.Mining;
                     }
                  }
               }
            }
         }
      }
   }

   private void runFinish(BlockPos plannedPos, Direction plannedFace) {
      if (this.phase == SpeedMinePlusPlus.Phase.Mining
         && plannedPos.equals(this.targetPos)
         && this.canKeepTarget(plannedPos)
         && System.nanoTime() >= this.mineReadyAtNanos) {
         BlockState state = this.mc.world.getBlockState(plannedPos);
         if (!state.isAir() && !(state.getHardness(this.mc.world, plannedPos) < 0.0F)) {
            int slot = this.toolSlot(state);
            double delta = BlockUtils.getBreakDelta(slot, state);
            if (!(delta <= 0.0) && Double.isFinite(delta)) {
               if (this.holdAndGateBlock(plannedPos, plannedFace)) {
                  if (GrimCombatCoordinator.claimAction(this)) {
                     if (this.sendMiningAction(Action.STOP_DESTROY_BLOCK, plannedPos, plannedFace, slot, false)) {
                        long now = System.nanoTime();
                        this.nextStartAtNanos = Math.max(this.nextStartAtNanos, now + START_INTERVAL_NANOS);
                        this.targetFace = plannedFace;
                        this.serverAirConfirmed = false;
                        this.mineReadyAtNanos = 0L;
                        this.actionTick = this.clientTick;
                        this.phase = SpeedMinePlusPlus.Phase.AwaitingAir;
                     }
                  }
               }
            }
         }
      }
   }

   private void runInstantRebreak(BlockPos plannedPos, Direction plannedFace) {
      if (this.phase == SpeedMinePlusPlus.Phase.InstantRebreak && plannedPos.equals(this.targetPos) && this.canKeepTarget(plannedPos)) {
         BlockState state = this.mc.world.getBlockState(plannedPos);
         if (!state.isAir() && !(state.getHardness(this.mc.world, plannedPos) < 0.0F)) {
            int slot = this.toolSlot(state);
            if (GrimCombatCoordinator.selectSilent(this, slot)) {
               if (this.holdAndGateBlock(plannedPos, plannedFace)) {
                  if (GrimCombatCoordinator.claimAction(this)) {
                     if (this.sendMiningAction(Action.STOP_DESTROY_BLOCK, plannedPos, plannedFace, slot, false)) {
                        this.targetFace = plannedFace;
                        this.serverAirConfirmed = false;
                        this.mineReadyAtNanos = 0L;
                        this.actionTick = this.clientTick;
                        this.phase = SpeedMinePlusPlus.Phase.AwaitingAir;
                     }
                  }
               }
            }
         }
      }
   }

   private void runAbort(BlockPos plannedPos) {
      if (this.abortPos != null && this.abortPos.equals(plannedPos) && GrimCombatCoordinator.claimAction(this)) {
         this.mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(Action.ABORT_DESTROY_BLOCK, plannedPos, Direction.DOWN));
         GrimCombatCoordinator.restoreSilent(this);
         if (plannedPos.equals(this.abortPos)) {
            this.abortPos = null;
         }
      }
   }

   private boolean sendMiningAction(Action action, BlockPos pos, Direction face, int slot, boolean keepSilentSlot) {
      if (!GrimCombatCoordinator.selectSilent(this, slot)) {
         return false;
      } else {
         boolean sent = false;

         boolean var7;
         try {
            this.mc.player.swingHand(Hand.MAIN_HAND);
            sent = this.sendSequencedAction(action, pos, face);
            var7 = sent;
         } finally {
            if (!sent || !keepSilentSlot) {
               GrimCombatCoordinator.restoreSilent(this);
            }
         }

         return var7;
      }
   }

   private boolean sendSequencedAction(Action action, BlockPos pos, Direction face) {
      if (SEQUENCED_PACKET_SENDER == null) {
         return false;
      } else {
         SequencedPacketCreator creator = sequence -> new PlayerActionC2SPacket(action, pos, face == null ? Direction.DOWN : face, sequence);

         try {
            SEQUENCED_PACKET_SENDER.invoke(
               (ClientPlayerInteractionManager)this.mc.interactionManager, (ClientWorld)this.mc.world, (SequencedPacketCreator)creator
            );
            return true;
         } catch (Throwable var6) {
            return false;
         }
      }
   }

   private void observeServerBlock(BlockPos pos, BlockState state) {
      if (this.targetPos != null && pos != null && this.targetPos.equals(pos)) {
         if (state.isAir()) {
            GrimCombatCoordinator.restoreSilent(this);
            this.serverAirConfirmed = true;
            this.mineReadyAtNanos = 0L;
            this.addFade(pos);
            this.lookGate.invalidate();
            this.queuedRotation = null;
            if (!(Boolean)this.rebreak.get()) {
               this.clearTarget(false);
            } else {
               this.phase = SpeedMinePlusPlus.Phase.Armed;
            }
         } else if (this.serverAirConfirmed && this.phase == SpeedMinePlusPlus.Phase.Armed) {
            if (state.getHardness(this.mc.world, pos) < 0.0F) {
               this.clearTarget(false);
            } else {
               this.armReplacement(pos);
            }
         }
      }
   }

   private void armReplacement(BlockPos pos) {
      this.serverAirConfirmed = false;
      this.mineReadyAtNanos = 0L;
      this.actionTick = this.clientTick;
      Direction plausible = this.choosePlausibleFace(pos);
      if (plausible != null) {
         this.targetFace = plausible;
      }

      this.phase = this.instantRebreak.get() ? SpeedMinePlusPlus.Phase.InstantRebreak : SpeedMinePlusPlus.Phase.NeedsStart;
      this.lookGate.invalidate();
      this.queuedRotation = null;
   }

   private int toolSlot(BlockState state) {
      int selected = this.mc.player.getInventory().getSelectedSlot();
      if (this.autoSwap.get() == SpeedMinePlusPlus.AutoSwapMode.None) {
         return selected;
      } else {
         FindItemResult fastest = InvUtils.findFastestTool(state);
         return fastest.found() && fastest.slot() >= 0 && fastest.slot() < 9 ? fastest.slot() : selected;
      }
   }

   private boolean canTarget(BlockPos pos) {
      return this.mc.player != null
         && this.mc.world != null
         && this.mc.interactionManager != null
         && this.mc.getNetworkHandler() != null
         && !this.mc.player.isCreative()
         && !this.mc.player.isSpectator()
         && BlockUtils.canBreak(pos, this.mc.world.getBlockState(pos))
         && this.canKeepTarget(pos);
   }

   private boolean canKeepTarget(BlockPos pos) {
      return this.mc.player != null && this.mc.world != null && pos != null && this.mc.player.canInteractWithBlockAt(pos, 0.0);
   }

   private boolean isPaused() {
      return this.mc.currentScreen != null || this.mc.player.isUsingItem() || this.mc.options.useKey.isPressed();
   }

   private Direction choosePlausibleFace(BlockPos pos) {
      return this.mc.player == null ? this.targetFace : CrystalAuraPlusPlus.choosePlausibleFace(pos, this.mc.player.getEyePos());
   }

   private boolean holdAndGateBlock(BlockPos pos, Direction face) {
      if (this.sentLookHitsBlock(pos)) {
         this.holdSentLook();
         this.lookGate.invalidate();
         return true;
      } else {
         this.queueRotation(pos);
         BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), face, pos, false);
         if (!this.lookGate.readyFor(hit)) {
            return false;
         } else {
            boolean valid = this.sentLookHitsBlock(pos);
            if (!valid) {
               this.lookGate.invalidate();
            }

            return valid;
         }
      }
   }

   private boolean sentLookHitsBlock(BlockPos pos) {
      return this.sentLookKnown
         && this.canKeepTarget(pos)
         && CrystalAuraPlusPlus.rayIntersectsBox(
            this.mc.player.getEyePos(), this.sentYaw, this.sentPitch, this.mc.player.getBlockInteractionRange(), new Box(pos)
         );
   }

   private void queueRotation(BlockPos pos) {
      Vec3d direction = Vec3d.ofCenter(pos).subtract(this.mc.player.getEyePos());
      double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
      float yaw = (float)(Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
      float pitch = (float)(-Math.toDegrees(Math.atan2(direction.y, horizontal)));
      this.queuedRotation = new SpeedMinePlusPlus.RotationIntent(yaw, pitch);
   }

   private void holdSentLook() {
      this.queuedRotation = new SpeedMinePlusPlus.RotationIntent(this.sentYaw, this.sentPitch);
   }

   private void applyQueuedRotationInput() {
      SpeedMinePlusPlus.RotationIntent intent = this.queuedRotation;
      this.queuedRotation = null;
      ClientPlayerEntity player = this.mc.player;
      if (intent != null && player != null && !player.hasVehicle() && player.input != null) {
         this.restoreRotationInput();
         this.rotationInput = new SpeedMinePlusPlus.MiningRotationInput(player.input, player, intent);
         player.input = this.rotationInput;
      }
   }

   private void restoreRotationInput() {
      SpeedMinePlusPlus.MiningRotationInput input = this.rotationInput;
      if (input != null) {
         input.restorePlayerRotation();
         if (this.mc.player == input.player && this.mc.player.input == input) {
            this.mc.player.input = input.delegate;
         }

         this.rotationInput = null;
      }
   }

   private void queueAbortIfOpen() {
      if (this.targetPos != null && this.phase == SpeedMinePlusPlus.Phase.Mining) {
         this.abortPos = this.targetPos;
      }
   }

   private void clearTarget(boolean abortOpen) {
      if (abortOpen) {
         this.queueAbortIfOpen();
      }

      this.addFade(this.targetPos);
      GrimCombatCoordinator.restoreSilent(this);
      this.restoreRotationInput();
      this.targetPos = null;
      this.targetFace = null;
      this.phase = SpeedMinePlusPlus.Phase.Idle;
      this.actionTick = 0;
      this.serverAirConfirmed = false;
      this.mineReadyAtNanos = 0L;
      this.queuedRotation = null;
      this.lookGate.invalidate();
   }

   private void abortOpenBreak() {
      if (this.targetPos != null && this.phase == SpeedMinePlusPlus.Phase.Mining && this.mc.getNetworkHandler() != null) {
         this.mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(Action.ABORT_DESTROY_BLOCK, this.targetPos, Direction.DOWN));
         GrimCombatCoordinator.restoreSilent(this);
      }
   }

   private void drawBox(Render3DEvent event, BlockPos pos, double alpha) {
      this.drawBox(event, new Box(pos), alpha);
   }

   private void drawBox(Render3DEvent event, Box box, double alpha) {
      event.renderer
         .box(box, colorWithAlpha((Color)this.sideColor.get(), alpha), colorWithAlpha((Color)this.lineColor.get(), alpha), (ShapeMode)this.shapeMode.get(), 0);
   }

   private void addFade(BlockPos pos) {
      if ((Boolean)this.fade.get() && pos != null) {
         SpeedMinePlusPlus.RenderPulse latest = this.renderPulses.peekLast();
         if (latest == null || latest.startedTick != this.clientTick || !latest.pos.equals(pos)) {
            while (this.renderPulses.size() >= 8) {
               this.renderPulses.removeFirst();
            }

            this.renderPulses.addLast(new SpeedMinePlusPlus.RenderPulse(pos.toImmutable(), this.clientTick));
         }
      }
   }

   private void pruneRenderPulses() {
      if (!(Boolean)this.fade.get()) {
         this.renderPulses.clear();
      } else {
         this.renderPulses.removeIf(pulse -> this.clientTick - pulse.startedTick > (Integer)this.fadeTime.get());
      }
   }

   private double fadeAlpha(SpeedMinePlusPlus.RenderPulse pulse, float tickDelta) {
      double age = Math.max(0.0, (double)(this.clientTick - pulse.startedTick + tickDelta));
      return MathHelper.clamp(1.0 - age / ((Integer)this.fadeTime.get()).intValue(), 0.0, 1.0);
   }

   private static Color colorWithAlpha(Color color, double alpha) {
      return alpha >= 0.999 ? color : new Color(color.r, color.g, color.b, MathHelper.clamp((int)Math.round(color.a * alpha), 0, 255));
   }

   static long miningDurationNanos(double breakDelta) {
      if (breakDelta > 0.0 && Double.isFinite(breakDelta)) {
         double ticks = Math.ceil(1.0 / breakDelta) + 1.0;
         return ticks >= 9.223372E18F / TICK_NANOS ? Long.MAX_VALUE : Math.max(TICK_NANOS, (long)ticks * TICK_NANOS);
      } else {
         return Long.MAX_VALUE;
      }
   }

   private void resetState() {
      this.restoreRotationInput();
      this.targetPos = null;
      this.targetFace = null;
      this.abortPos = null;
      this.phase = SpeedMinePlusPlus.Phase.Idle;
      this.clientTick = 0;
      this.actionTick = 0;
      this.serverAirConfirmed = false;
      this.mineReadyAtNanos = 0L;
      this.nextStartAtNanos = 0L;
      this.renderPulses.clear();
      this.queuedRotation = null;
      this.sentYaw = 0.0F;
      this.sentPitch = 0.0F;
      this.sentLookKnown = false;
      this.lookGate.invalidate();
   }

   private static MethodHandle findSequencedPacketSender() {
      for (Method method : ClientPlayerInteractionManager.class.getDeclaredMethods()) {
         Class<?>[] parameters = method.getParameterTypes();
         if (method.getReturnType() == void.class
            && parameters.length == 2
            && parameters[0] == ClientWorld.class
            && parameters[1] == SequencedPacketCreator.class) {
            try {
               method.setAccessible(true);
               return MethodHandles.lookup().unreflect(method);
            } catch (RuntimeException | IllegalAccessException var6) {
               return null;
            }
         }
      }

      return null;
   }

   private static Vec2f movementFor(PlayerInput input) {
      float sideways = (input.left() ? 1.0F : 0.0F) - (input.right() ? 1.0F : 0.0F);
      float forward = (input.forward() ? 1.0F : 0.0F) - (input.backward() ? 1.0F : 0.0F);
      return new Vec2f(sideways, forward).normalize();
   }

   private static enum AutoSwapMode {
      None,
      Silent;
   }

   private final class MiningRotationInput extends Input {
      private final Input delegate;
      private final ClientPlayerEntity player;
      private final SpeedMinePlusPlus.RotationIntent intent;
      private boolean applied;
      private float restoreYaw;
      private float restorePitch;

      private MiningRotationInput(Input delegate, ClientPlayerEntity player, SpeedMinePlusPlus.RotationIntent intent) {
         this.delegate = delegate;
         this.player = player;
         this.intent = intent;
         this.setState(delegate.playerInput, delegate.getMovementInput());
      }

      public void tick() {
         this.delegate.tick();
         PlayerInput original = this.delegate.playerInput == null ? PlayerInput.DEFAULT : this.delegate.playerInput;
         Vec2f originalMovement = this.delegate.getMovementInput();
         this.setState(original, originalMovement);
         if (SpeedMinePlusPlus.this.mc.player == this.player && SpeedMinePlusPlus.this.mc.player.input == this) {
            this.restoreYaw = this.player.getYaw();
            this.restorePitch = this.player.getPitch();
            PlayerInput corrected = CrystalAuraPlusPlus.remapPlayerInput(original, originalMovement, this.restoreYaw, this.intent.yaw);
            this.setState(corrected, SpeedMinePlusPlus.movementFor(corrected));
            this.player.setYaw(this.intent.yaw);
            this.player.setPitch(this.intent.pitch);
            this.applied = true;
         }
      }

      private void restorePlayerRotation() {
         if (this.applied && SpeedMinePlusPlus.this.mc.player == this.player) {
            this.player.setYaw(this.restoreYaw);
            this.player.setPitch(this.restorePitch);
            this.applied = false;
         }
      }

      private void setState(PlayerInput input, Vec2f movement) {
         this.playerInput = input == null ? PlayerInput.DEFAULT : input;
         this.movementVector = movement == null ? Vec2f.ZERO : movement;
      }
   }

   private static enum Phase {
      Idle,
      NeedsStart,
      Mining,
      AwaitingAir,
      Armed,
      InstantRebreak;
   }

   private record RenderPulse(BlockPos pos, int startedTick) {
   }

   private record RotationIntent(float yaw, float pitch) {
   }
}
