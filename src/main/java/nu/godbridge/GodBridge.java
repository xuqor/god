package nu.godbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// Telly: sprint-jump forward, turn back in the air, fill the bridge from its end up to the cell under your feet, turn forward before landing, jump again.
// Never builds ahead of you. Every block is placed through a real ray from your eye along the rotation the server already has.
// Snapping camera mid-air is still visible to rotation checks, use it on your own server or singleplayer.
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int    BACK   = 4;     // lane cells scanned behind the cell under your (predicted) feet
    static final double PRED   = 2.0;   // ticks of momentum used to find the cell you will stand over
    static final double REACH  = 4.4;   // stay under the 4.5 block placement ray
    static final double LAND_H = 0.7;   // feet height above the bridge at which the camera turns forward again
    static final float  LOOK_BACK_PITCH = 75f;

    private final Minecraft mc = Minecraft.getMinecraft();

    private KeyBinding key;   // rebind in Options > Controls > GodBridge
    private boolean active;
    private int ax, az, bridgeY;
    private float laneYaw;

    // block aimed last tick, placed this tick through a fresh ray (the rotation packet for it is already out)
    private BlockPos pCell;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        key = new KeyBinding("Telly bridge (hold)", Keyboard.KEY_C, "GodBridge");
        ClientRegistry.registerKeyBinding(key);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        EntityPlayerSP p = mc.thePlayer;
        if (p != null && !(p.movementInput instanceof TellyInput)) p.movementInput = new TellyInput(mc.gameSettings);
        boolean want = p != null && mc.theWorld != null && mc.currentScreen == null && key.isKeyDown();

        if (want && !active) start(p);
        active = want;
        if (!active || !holdingBlock(p)) {
            pCell = null;
            return;
        }

        // about to land on solid bridge: camera forward, sprint on, no placing
        BlockPos under = new BlockPos(MathHelper.floor_double(p.posX), bridgeY, MathHelper.floor_double(p.posZ));
        boolean landing = !p.onGround && p.motionY < 0 && p.posY - (bridgeY + 1) <= LAND_H && !mc.theWorld.isAirBlock(under);
        if (p.onGround || landing) {
            pCell = null;
            p.rotationYaw += MathHelper.wrapAngleTo180_float(laneYaw - p.rotationYaw);
            p.setSprinting(true);
            return;
        }

        // airborne: place what was aimed last tick, then aim at the next block, camera looking back
        if (pCell != null) {
            tryPlace(p);
            pCell = null;
        }
        if (findTarget(p)) return;
        p.rotationYaw += MathHelper.wrapAngleTo180_float(laneYaw + 180f - p.rotationYaw);
        p.rotationPitch = LOOK_BACK_PITCH;
    }

    // forced movement toward the lane whatever the camera yaw is: solve vanilla moveFlying for (forward, strafe)
    private class TellyInput extends MovementInputFromOptions {
        TellyInput(GameSettings gs) {
            super(gs);
        }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();
            if (!active) return;
            EntityPlayerSP p = mc.thePlayer;
            if (p == null || !holdingBlock(p)) return;
            double r = Math.toRadians(p.rotationYaw), s = Math.sin(r), c = Math.cos(r);
            moveForward = (float) (-ax * s + az * c);
            moveStrafe = (float) (ax * c + az * s);
            sneak = false;
            if (p.onGround) jump = true;
        }
    }

    private boolean holdingBlock(EntityPlayerSP p) {
        ItemStack st = p == null ? null : p.getHeldItem();
        return st != null && st.getItem() instanceof ItemBlock;
    }

    // lane = cardinal direction you face, bridge level = the block row you stand on
    private void start(EntityPlayerSP p) {
        int f = MathHelper.floor_double(p.rotationYaw / 90.0 + 0.5) & 3; // 0 S, 1 W, 2 N, 3 E, same order as EnumFacing.getHorizontal
        EnumFacing d = EnumFacing.getHorizontal(f);
        ax = d.getFrontOffsetX();
        az = d.getFrontOffsetZ();
        laneYaw = f * 90f;
        bridgeY = MathHelper.floor_double(p.posY) - 1;
        pCell = null;
    }

    // first free lane cell at or behind the cell under the predicted feet that touches a solid block, scanning from the back
    // so the bridge grows from its end. Rotates the camera onto that block's face for the next tick.
    private boolean findTarget(EntityPlayerSP p) {
        int cx = MathHelper.floor_double(p.posX + p.motionX * PRED);
        int cz = MathHelper.floor_double(p.posZ + p.motionZ * PRED);
        // eye position next tick (air friction 0.91, gravity 0.08 then 0.98)
        double ex = p.posX + p.motionX * 0.91;
        double ey = p.posY + (p.motionY - 0.08) * 0.98 + p.getEyeHeight();
        double ez = p.posZ + p.motionZ * 0.91;
        EnumFacing[] order = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

        for (int k = -BACK; k <= 0; k++) {
            BlockPos bp = new BlockPos(cx + ax * k, bridgeY, cz + az * k);
            if (!mc.theWorld.isAirBlock(bp)) continue;
            for (EnumFacing f : order) {
                BlockPos nb = bp.offset(f);
                if (mc.theWorld.isAirBlock(nb) || mc.theWorld.getBlockState(nb).getBlock().getMaterial().isLiquid()) continue;
                EnumFacing face = f.getOpposite(); // face of the neighbour that touches bp
                double hx = nb.getX() + 0.5 + face.getFrontOffsetX() * 0.5;
                double hy = nb.getY() + 0.5 + face.getFrontOffsetY() * 0.5;
                double hz = nb.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5;
                double dx = hx - ex, dy = hy - ey, dz = hz - ez;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > REACH) continue;

                float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
                float pitch = (float) -(Math.atan2(dy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
                p.rotationYaw += MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw);
                p.rotationPitch = MathHelper.clamp_float(pitch, -90f, 90f);
                pCell = bp;
                return true;
            }
        }
        return false;
    }

    // place only if the real ray from the eye, along the current rotation, lands on a face that fills pCell
    private void tryPlace(EntityPlayerSP p) {
        ItemStack st = p.getHeldItem();
        if (st == null || !mc.theWorld.isAirBlock(pCell)) return;
        Vec3 eye = p.getPositionEyes(1.0F);
        Vec3 look = p.getLook(1.0F);
        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
        MovingObjectPosition h = mc.theWorld.rayTraceBlocks(eye, end);
        if (h == null || h.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        if (!h.getBlockPos().offset(h.sideHit).equals(pCell)) return;
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, st, h.getBlockPos(), h.sideHit, h.hitVec)) {
            p.swingItem();
        }
    }
}
