package nu.godbridge;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// SafeWalk (Z toggle): presses a real sneak at block edges.
// Ninja / Duo / Triple bridge (hold, unbound by default, bind in Options > Controls > SafeWalk): walk back along the lane, place on the end block's front face.
// Sneak only for the first block of every group: 1 block (ninja), 2 (duo), 3 (triple). Between sneaks you walk and the mod stops you where the floor ends.
// The entity rotation (movement, packets, F5 model) is the mod's, your first-person camera is a separate free look.
// Anticheats can still see the rotations, use it where you can afford a flag.
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int    BACK       = 5;      // lane cells scanned behind the target cell
    static final double PRED       = 2.0;    // ticks of momentum used to find the cell you will stand over
    static final double REACH      = 4.4;    // stay under the 4.5 block placement ray
    static final double LOOK       = 0.2;    // edge lookahead (blocks) along your input, on top of momentum
    static final float  BACK_PITCH = 75f;    // pitch while nothing is placeable
    static final float  YAW_MAX    = 140f;   // legit mode: max yaw step per tick (+-20%)
    static final float  PITCH_MAX  = 40f;    // legit mode: max pitch step per tick
    static final float  EASE_MIN   = 0.80f;  // legit mode: share of the remaining angle covered per tick
    static final float  EASE_MAX   = 0.95f;
    static final float  SKIP       = 0.08f;  // legit mode: chance to skip a ready click
    static final double AIM_SPREAD = 0.15;   // legit mode: random aim offset inside the face (blocks, +-)

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random();

    private KeyBinding safeKey, ninjaKey, duoKey, tripleKey, legitKey;
    private boolean safeOn;
    private boolean legit;     // false: exact aim every tick. true: eased, limited, jittered, GCD-snapped rotations

    private int n;             // bridge group size: 0 off, 1 ninja, 2 duo, 3 triple
    private boolean silent;
    private int ax, az, bridgeY, latCell, placed;
    private double latC;
    private float laneYaw;

    // free first-person camera, and the rotation the entity (= the server) has
    private float camYaw, camPitch, setYaw, setPitch;
    private float gYaw, gPitch;

    // the click goes out at the START of the next tick (before its movement packet, like vanilla),
    // judged against the rotation and position of the last movement packet the server got
    private float sentYaw, sentPitch;
    private boolean placeNext;
    private int plMx, plMz;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        safeKey = new KeyBinding("SafeWalk (toggle)", Keyboard.KEY_Z, "SafeWalk");
        ninjaKey = new KeyBinding("Ninja bridge (hold)", Keyboard.KEY_NONE, "SafeWalk");
        duoKey = new KeyBinding("Duo ninja bridge (hold)", Keyboard.KEY_NONE, "SafeWalk");
        tripleKey = new KeyBinding("Triple ninja bridge (hold)", Keyboard.KEY_NONE, "SafeWalk");
        legitKey = new KeyBinding("Legit rotations (toggle)", Keyboard.KEY_NONE, "SafeWalk");
        ClientRegistry.registerKeyBinding(safeKey);
        ClientRegistry.registerKeyBinding(ninjaKey);
        ClientRegistry.registerKeyBinding(duoKey);
        ClientRegistry.registerKeyBinding(tripleKey);
        ClientRegistry.registerKeyBinding(legitKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            silent = false;
            n = 0;
            return;
        }

        if (e.phase == TickEvent.Phase.START) {
            if (silent) {
                if (placeNext) {
                    placeNext = false;
                    if (tryPlace(p, plMx, plMz)) placed++;
                }
                captureMouse(p); // mouse moved the entity between ticks: move that into the free camera instead
            }
            return;
        }

        if (!(p.movementInput instanceof BridgeInput)) p.movementInput = new BridgeInput(mc.gameSettings);

        while (safeKey.isPressed()) {
            safeOn = !safeOn;
            p.addChatMessage(new ChatComponentText("[SafeWalk] " + (safeOn ? "ON" : "OFF")));
        }
        while (legitKey.isPressed()) {
            legit = !legit;
            p.addChatMessage(new ChatComponentText("[Bridge] legit rotations: " + (legit ? "ON" : "OFF")));
        }

        int want = 0;
        if (mc.currentScreen == null) {
            want = ninjaKey.isKeyDown() ? 1 : duoKey.isKeyDown() ? 2 : tripleKey.isKeyDown() ? 3 : 0;
        }
        if (want > 0 && n == 0) start(p);
        n = want;

        boolean running = n > 0 && holdingBlock(p);
        if (running && !silent) beginSilent(p);
        if (!running) {
            if (silent) endSilent(p);
            return;
        }

        captureMouse(p);
        int cx = MathHelper.floor_double(p.posX + p.motionX * PRED);
        int cz = MathHelper.floor_double(p.posZ + p.motionZ * PRED);
        if (ax != 0) cz = latCell; // the bridge stays in the column it started in
        else cx = latCell;

        sentYaw = setYaw; // this is what the movement packet of this tick carried
        sentPitch = setPitch;
        p.setSprinting(false);
        placeNext = true;
        plMx = cx;
        plMz = cz;
        if (findTarget(p, cx, cz)) rotateToward(gYaw, gPitch);
        else rotateToward(laneYaw + 180f, BACK_PITCH);
        apply(p);
    }

    private class BridgeInput extends MovementInputFromOptions {
        BridgeInput(GameSettings gs) {
            super(gs);
        }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();
            EntityPlayerSP p = mc.thePlayer;
            if (p == null || mc.theWorld == null) return;
            if (silent) bridge(p);
            else if (safeOn && !sneak) safeWalk(p);
        }

        // SafeWalk: press sneak when the next step would leave the floor
        private void safeWalk(EntityPlayerSP p) {
            if (!p.onGround) return;
            double r = Math.toRadians(p.rotationYaw), s = Math.sin(r), c = Math.cos(r);
            double dx = moveStrafe * c - moveForward * s;
            double dz = moveForward * c + moveStrafe * s;
            double l = Math.hypot(dx, dz);
            if (l > 1e-4) {
                dx = dx / l * LOOK;
                dz = dz / l * LOOK;
            } else {
                dx = 0;
                dz = 0;
            }
            if (!supported(p, p.motionX + dx, p.motionZ + dz, 1.0)) {
                sneak = true;
                moveForward *= 0.3f; // vanilla sneak slowdown
                moveStrafe *= 0.3f;
            }
        }

        // forced walk along the lane whatever the entity yaw is: solve vanilla moveFlying for (forward, strafe)
        private void bridge(EntityPlayerSP p) {
            double r = Math.toRadians(p.rotationYaw), s = Math.sin(r), c = Math.cos(r);
            double off = (ax != 0 ? p.posZ : p.posX) - latC;   // offset from the column centre
            double ux = ax != 0 ? 0 : 1, uz = ax != 0 ? 1 : 0; // lateral unit vector
            double lat = Math.max(-0.5, Math.min(0.5, -off * 3.0));
            double nrm = Math.sqrt(1 + lat * lat);
            double dx = (ax + lat * ux) / nrm;
            double dz = (az + lat * uz) / nrm;
            moveForward = (float) (-dx * s + dz * c);
            moveStrafe = (float) (dx * c + dz * s);

            if (placed % n == 0) {
                // first block of the group: real sneak at the edge, vanilla slowdown and safewalk
                moveForward *= 0.3f;
                moveStrafe *= 0.3f;
                sneak = true;
            } else {
                // rest of the group: plain walking, but stop where the floor ends
                sneak = false;
                if (p.onGround && !supported(p, dx * LOOK + p.motionX, dz * LOOK + p.motionZ, 1.0)) {
                    moveForward = 0f;
                    moveStrafe = 0f;
                    p.motionX = 0;
                    p.motionZ = 0;
                }
            }
        }
    }

    // first person looks through the free camera, F5 keeps vanilla (follows the entity, so you see the head turn)
    @SubscribeEvent
    public void cam(EntityViewRenderEvent.CameraSetup e) {
        EntityPlayerSP p = mc.thePlayer;
        if (!silent || p == null || mc.gameSettings.thirdPersonView != 0) return;
        e.yaw = camYaw + (p.rotationYaw - setYaw) + 180f;
        e.pitch = camPitch + (p.rotationPitch - setPitch);
    }

    private boolean holdingBlock(EntityPlayerSP p) {
        ItemStack st = p.getHeldItem();
        return st != null && st.getItem() instanceof ItemBlock;
    }

    // lane = cardinal direction you face, bridge level = the block row you stand on, column = the one you stand in
    private void start(EntityPlayerSP p) {
        int f = MathHelper.floor_double(p.rotationYaw / 90.0 + 0.5) & 3; // 0 S, 1 W, 2 N, 3 E, same order as EnumFacing.getHorizontal
        EnumFacing d = EnumFacing.getHorizontal(f);
        ax = d.getFrontOffsetX();
        az = d.getFrontOffsetZ();
        laneYaw = f * 90f;
        bridgeY = MathHelper.floor_double(p.posY) - 1;
        latCell = ax != 0 ? MathHelper.floor_double(p.posZ) : MathHelper.floor_double(p.posX);
        latC = latCell + 0.5;
        placed = 0;
    }

    private void beginSilent(EntityPlayerSP p) {
        camYaw = setYaw = p.rotationYaw;
        camPitch = setPitch = p.rotationPitch;
        placeNext = false;
        silent = true;
    }

    private void endSilent(EntityPlayerSP p) {
        placeNext = false;
        captureMouse(p);
        p.rotationYaw = p.prevRotationYaw = camYaw;
        p.rotationPitch = p.prevRotationPitch = MathHelper.clamp_float(camPitch, -90f, 90f);
        p.rotationYawHead = camYaw;
        silent = false;
    }

    // whatever the mouse added to the entity since we last set it goes to the free camera, the entity goes back to the mod's rotation
    private void captureMouse(EntityPlayerSP p) {
        camYaw += p.rotationYaw - setYaw;
        camPitch = MathHelper.clamp_float(camPitch + (p.rotationPitch - setPitch), -90f, 90f);
        p.rotationYaw = setYaw;
        p.rotationPitch = setPitch;
    }

    private void apply(EntityPlayerSP p) {
        p.rotationYaw = setYaw;
        p.rotationPitch = setPitch;
        p.rotationYawHead = setYaw;
    }

    // degrees per mouse count for the current sensitivity (what vanilla rotations are multiples of)
    private float gcd() {
        float f = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        return f * f * f * 8f * 0.15f;
    }

    private void rotateToward(float yaw, float pitch) {
        float g = gcd();
        setYaw += quant(MathHelper.wrapAngleTo180_float(yaw - setYaw), YAW_MAX, g);
        setPitch = MathHelper.clamp_float(setPitch + quant(pitch - setPitch, PITCH_MAX, g), -90f, 90f);
    }

    private float quant(float d, float max, float g) {
        if (!legit) return d; // exact: land on the aim every tick
        float ease = EASE_MIN + rnd.nextFloat() * (EASE_MAX - EASE_MIN);
        float lim = max * (0.8f + rnd.nextFloat() * 0.4f);
        float want = MathHelper.clamp_float(d * ease, -lim, lim);
        return Math.round(want / g) * g;
    }

    // true if the player's box, shifted by (dx, -drop, dz), still collides with something (same test vanilla sneak uses at edges)
    private boolean supported(EntityPlayerSP p, double dx, double dz, double drop) {
        return !mc.theWorld.getCollidingBoundingBoxes(p, p.getEntityBoundingBox().offset(dx, -drop, dz)).isEmpty();
    }

    private boolean isLaneCell(BlockPos bp, int mx, int mz) {
        if (bp.getY() != bridgeY) return false;
        for (int k = -BACK; k <= 0; k++) {
            if (bp.getX() == mx + ax * k && bp.getZ() == mz + az * k) return true;
        }
        return false;
    }

    private Vec3 lookVec(float yaw, float pitch) {
        float cp = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sp = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cy = -MathHelper.cos(-pitch * 0.017453292F);
        float sy = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(sp * cy, sy, cp * cy);
    }

    private MovingObjectPosition ray(double ex, double ey, double ez, float yaw, float pitch) {
        Vec3 look = lookVec(yaw, pitch);
        Vec3 eye = new Vec3(ex, ey, ez);
        Vec3 end = eye.addVector(look.xCoord * 4.5, look.yCoord * 4.5, look.zCoord * 4.5);
        MovingObjectPosition h = mc.theWorld.rayTraceBlocks(eye, end);
        return h != null && h.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? h : null;
    }

    // first free lane cell at or behind the target cell that touches a solid block, scanning from the back so the bridge
    // grows from its end. Sets gYaw/gPitch to a point on that block's face whose ray really hits it.
    private boolean findTarget(EntityPlayerSP p, int mx, int mz) {
        // eye position next tick
        double ex = p.posX + p.motionX * 0.91;
        double ey = p.posY + p.motionY + p.getEyeHeight();
        double ez = p.posZ + p.motionZ * 0.91;
        EnumFacing[] order = {EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
        boolean haveFallback = false;
        float fbYaw = 0, fbPitch = 0;

        for (int k = -BACK; k <= 0; k++) {
            BlockPos bp = new BlockPos(mx + ax * k, bridgeY, mz + az * k);
            if (!mc.theWorld.isAirBlock(bp)) continue;
            for (EnumFacing f : order) {
                BlockPos nb = bp.offset(f);
                if (mc.theWorld.isAirBlock(nb) || mc.theWorld.getBlockState(nb).getBlock().getMaterial().isLiquid()) continue;
                EnumFacing face = f.getOpposite(); // face of the neighbour that touches bp
                for (int a = 0; a < 5; a++) {
                    double sp = (legit && a < 4) ? AIM_SPREAD : 0; // the last attempt aims at the centre
                    double hx = nb.getX() + 0.5 + face.getFrontOffsetX() * 0.5 + (face.getFrontOffsetX() == 0 ? (rnd.nextDouble() * 2 - 1) * sp : 0);
                    double hy = nb.getY() + 0.5 + face.getFrontOffsetY() * 0.5 + (face.getFrontOffsetY() == 0 ? (rnd.nextDouble() * 2 - 1) * sp : 0);
                    double hz = nb.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5 + (face.getFrontOffsetZ() == 0 ? (rnd.nextDouble() * 2 - 1) * sp : 0);
                    double dx = hx - ex, dy = hy - ey, dz = hz - ez;
                    if (Math.sqrt(dx * dx + dy * dy + dz * dz) > REACH) break;
                    float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
                    float pitch = (float) -(Math.atan2(dy, Math.hypot(dx, dz)) * 180.0 / Math.PI);
                    MovingObjectPosition h = ray(ex, ey, ez, yaw, pitch);
                    if (h != null && h.getBlockPos().offset(h.sideHit).equals(bp)) {
                        gYaw = yaw;
                        gPitch = pitch;
                        return true;
                    }
                    if (!haveFallback && a == 4) {
                        haveFallback = true;
                        fbYaw = yaw;
                        fbPitch = pitch;
                    }
                }
            }
        }
        if (haveFallback) {
            gYaw = fbYaw;
            gPitch = fbPitch;
            return true;
        }
        return false;
    }

    // click only if the real ray along the last sent rotation hits a face that fills a lane cell
    private boolean tryPlace(EntityPlayerSP p, int mx, int mz) {
        if (legit && rnd.nextFloat() < SKIP) return false;
        ItemStack st = p.getHeldItem();
        if (st == null) return false;
        MovingObjectPosition h = ray(p.posX, p.posY + p.getEyeHeight(), p.posZ, sentYaw, sentPitch);
        if (h == null) return false;
        BlockPos cell = h.getBlockPos().offset(h.sideHit);
        if (!mc.theWorld.isAirBlock(cell) || !isLaneCell(cell, mx, mz)) return false;
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, st, h.getBlockPos(), h.sideHit, h.hitVec)) {
            p.swingItem();
            return true;
        }
        return false;
    }
}
