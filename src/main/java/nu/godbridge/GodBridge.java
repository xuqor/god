package nu.godbridge;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// Hypixel/Watchdog and most anticheats flag this (rotation snap + fixed pitch + 15 CPS), use it on your own server or singleplayer
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int   KEY       = Keyboard.KEY_Z;
    static final float PITCH     = 75.6f;  // FILL: tune if blocks don't land on the edge
    static final float SMOOTH    = 0.35f;  // 1.0 = instant snap
    static final float CPS       = 15f;    // max placements per second (tick-capped at 20)
    static final float STRAFE    = -1f;    // -1 = S+D, +1 = S+A
    static final int   JUMP_MIN  = 6;
    static final int   JUMP_MAX  = 8;      // inclusive
    static final int   JUMP_HOLD = 2;      // ticks

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random();

    private boolean active;
    private float lockYaw;
    private double lastX, lastZ, travelled, clickAcc;
    private int target, jumpTicks;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        EntityPlayerSP p = mc.thePlayer;
        if (p != null && !(p.movementInput instanceof AutoInput)) p.movementInput = new AutoInput(mc.gameSettings);
        boolean want = p != null && mc.theWorld != null && mc.currentScreen == null && Keyboard.isKeyDown(KEY);

        if (want && !active) start(p);
        else if (!want && active) active = false;
        if (!active) return;

        // aim: nearest diagonal yaw, fixed pitch, smoothed
        p.rotationYaw += MathHelper.wrapAngleTo180_float(lockYaw - p.rotationYaw) * SMOOTH;
        p.rotationPitch += (PITCH - p.rotationPitch) * SMOOTH;

        // jump every 6-8 blocks walked
        if (jumpTicks > 0) jumpTicks--;
        travelled += Math.hypot(p.posX - lastX, p.posZ - lastZ);
        lastX = p.posX;
        lastZ = p.posZ;
        if (travelled >= target) {
            jumpTicks = JUMP_HOLD;
            travelled = 0;
            target = nextTarget();
        }

        // place only on a free spot below feet level
        clickAcc += CPS / 20.0;
        if (clickAcc >= 1.0) {
            MovingObjectPosition h = placeTarget(p);
            if (h != null) {
                place(p, h);
                clickAcc -= 1.0;
            } else {
                clickAcc = 1.0;
            }
        }
    }

    // own MovementInput on the player: vanilla update first, then override while active
    private class AutoInput extends MovementInputFromOptions {
        AutoInput(GameSettings gs) {
            super(gs);
        }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();
            if (!active) return;
            moveForward = -1f;
            moveStrafe = STRAFE;
            if (jumpTicks > 0) jump = true;
        }
    }

    private void start(EntityPlayerSP p) {
        active = true;
        lockYaw = Math.round((p.rotationYaw - 45f) / 90f) * 90f + 45f;
        lastX = p.posX;
        lastZ = p.posZ;
        travelled = 0;
        jumpTicks = 0;
        clickAcc = 1.0;
        target = nextTarget();
    }

    private MovingObjectPosition placeTarget(EntityPlayerSP p) {
        ItemStack st = p.getHeldItem();
        if (st == null || !(st.getItem() instanceof ItemBlock)) return null;
        MovingObjectPosition h = mc.objectMouseOver;
        if (h == null || h.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        BlockPos pos = h.getBlockPos().offset(h.sideHit);
        int feet = MathHelper.floor_double(p.posY);
        if (pos.getY() > feet - 1 || pos.getY() < feet - 2) return null;
        if (!mc.theWorld.isAirBlock(pos)) return null;
        return h;
    }

    // same call vanilla rightClickMouse makes, no reflection
    private void place(EntityPlayerSP p, MovingObjectPosition h) {
        ItemStack st = p.getHeldItem();
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, st, h.getBlockPos(), h.sideHit, h.hitVec)) {
            p.swingItem();
        }
    }

    private int nextTarget() {
        return JUMP_MIN + rnd.nextInt(JUMP_MAX - JUMP_MIN + 1);
    }
}
