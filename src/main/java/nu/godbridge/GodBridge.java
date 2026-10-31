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
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// Snapping rotations mid-air is exactly what Watchdog/Grim style checks flag, use it on your own server or singleplayer
// Real telly: sprint-jump forward, turn back in the air and place under/behind you, turn forward again before landing, jump again.
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int    LEAD   = 3;     // cells built ahead of the player's cell
    static final int    BACK   = 4;     // cells scanned behind it (stay inside server reach)
    static final double LAND_H = 0.7;   // feet height above the bridge at which the camera turns forward again
    static final float  LOOK_BACK_PITCH = 75f;

    private final Minecraft mc = Minecraft.getMinecraft();

    private KeyBinding key;   // rebind in Options > Controls > GodBridge
    private boolean active;
    private int ax, az, bridgeY;
    private float laneYaw;

    // block aimed last tick, placed this tick (rotation packet is already out by then, like a real click)
    private BlockPos pCell, pNb;
    private EnumFacing pFace;
    private Vec3 pHit;

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

        boolean landing = !p.onGround && p.motionY < 0 && p.posY - (bridgeY + 1) <= LAND_H;
        if (p.onGround || landing) {
            // ground / about to land: camera forward, sprint on, no placing
            pCell = null;
            p.rotationYaw += MathHelper.wrapAngleTo180_float(laneYaw - p.rotationYaw);
            p.setSprinting(true);
            return;
        }

        // airborne: place what was aimed last tick, then aim at the next block, camera looking back
        if (pCell != null) {
            ItemStack st = p.getHeldItem();
            if (st != null && mc.theWorld.isAirBlock(pCell) && !mc.theWorld.isAirBlock(pNb)
                    && mc.playerController.onPlayerRightClick(p, mc.theWorld, st, pNb, pFace, pHit)) {
                p.swingItem();
            }
            pCell = null;
        }
        if (findTarget()) {
            aimAt(p, pHit);
        } else {
            p.rotationYaw += MathHelper.wrapAngleTo180_float(laneYaw + 180f - p.rotationYaw);
            p.rotationPitch = LOOK_BACK_PITCH;
        }
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

    // first free lane cell (scanning from behind) that touches a solid block, so the bridge grows from its end
    private boolean findTarget() {
        EntityPlayerSP p = mc.thePlayer;
        int cx = MathHelper.floor_double(p.posX), cz = MathHelper.floor_double(p.posZ);
        EnumFacing[] order = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
        for (int k = -BACK; k <= LEAD; k++) {
            BlockPos bp = new BlockPos(cx + ax * k, bridgeY, cz + az * k);
            if (!mc.theWorld.isAirBlock(bp)) continue;
            for (EnumFacing f : order) {
                BlockPos nb = bp.offset(f);
                if (mc.theWorld.isAirBlock(nb) || mc.theWorld.getBlockState(nb).getBlock().getMaterial().isLiquid()) continue;
                EnumFacing face = f.getOpposite(); // face of the neighbour that touches bp
                pCell = bp;
                pNb = nb;
                pFace = face;
                pHit = new Vec3(nb.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                                nb.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                                nb.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
                return true;
            }
        }
        return false;
    }

    private void aimAt(EntityPlayerSP p, Vec3 h) {
        double dx = h.xCoord - p.posX, dy = h.yCoord - (p.posY + p.getEyeHeight()), dz = h.zCoord - p.posZ;
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
        p.rotationYaw += MathHelper.wrapAngleTo180_float(yaw - p.rotationYaw);
        p.rotationPitch = MathHelper.clamp_float(pitch, -90f, 90f);
    }
}
