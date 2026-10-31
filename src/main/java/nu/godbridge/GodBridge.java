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

// Telly: sprint-jump forward, turn back in the air, fill the bridge from its end up to the cell you will land on, turn forward before landing, jump again.
// The player entity (movement, packets, F5 model) uses the mod's rotation, your first-person camera is a separate free look.
// Rotations are rate-limited, eased, jittered and snapped to your mouse GCD; placements are ray-checked. No anticheat is guaranteed to stay quiet, use it where you can afford a flag.
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int    BACK       = 5;      // lane cells scanned behind the target cell
    static final double PRED       = 2.0;    // ticks of momentum used to find the cell you will stand over
    static final double REACH      = 4.4;    // stay under the 4.5 block placement ray
    static final double LAND_H     = 1.0;    // feet height above the bridge below which the turn forward may start
    static final float  BACK_PITCH = 75f;    // pitch while nothing is placeable
    static final float  YAW_MAX    = 140f;   // max yaw step per tick (randomised +-20%)
    static final float  PITCH_MAX  = 40f;    // max pitch step per tick
    static final float  EASE_MIN   = 0.80f;  // share of the remaining angle covered per tick
    static final float  EASE_MAX   = 0.95f;
    static final float  SKIP       = 0.08f;  // chance to skip a ready placement (not when landing is at risk)
    static final double AIM_SPREAD = 0.15;   // random aim offset inside the face (blocks, +-), only points whose ray really hits are used
    static final int    ROT_DELAY  = 1;      // 0..N ticks after take-off before the camera turns back (human-ish), never when landing is at risk

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random();

    private KeyBinding key;       // rebind in Options > Controls > GodBridge
    private KeyBinding legitKey;  // unbound by default, toggles the anticheat-friendly rotation profile in game
    private boolean wasWant, silent;
    private boolean legit;        // false: exact aim every tick (reliable). true: eased, limited, jittered, GCD-snapped rotations
    private int ax, az, bridgeY, airTicks, rotDelay;
    private float laneYaw;

    // free first-person camera, and the rotation the entity (= the server) has
    private float camYaw, camPitch, setYaw, setPitch;
    private float gYaw, gPitch;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        key = new KeyBinding("Telly bridge (hold)", Keyboard.KEY_C, "GodBridge");
        ClientRegistry.registerKeyBinding(key);
        legitKey = new KeyBinding("Legit rotations (toggle)", Keyboard.KEY_NONE, "GodBridge");
        ClientRegistry.registerKeyBinding(legitKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            silent = false;
            wasWant = false;
            return;
        }

        if (e.phase == TickEvent.Phase.START) {
            if (silent) captureMouse(p); // mouse moved the entity between ticks: move that into the free camera instead
            return;
        }

        while (legitKey.isPressed()) {
            legit = !legit;
            p.addChatMessage(new ChatComponentText("[Telly] legit rotations: " + (legit ? "ON" : "OFF")));
        }
        if (!(p.movementInput instanceof TellyInput)) p.movementInput = new TellyInput(mc.gameSettings);
        boolean want = mc.currentScreen == null && key.isKeyDown();
        if (want && !wasWant) start(p);
        wasWant = want;

        boolean running = want && holdingBlock(p);
        if (running && !silent) beginSilent(p);
        if (!running) {
            if (silent) endSilent(p);
            return;
        }

        captureMouse(p);
        float fwdPitch = MathHelper.clamp_float(camPitch, 0f, 80f);

        if (p.onGround) {
            airTicks = 0;
            rotDelay = legit ? rnd.nextInt(ROT_DELAY + 1) : 0;
        } else {
            airTicks++;
        }

        // where the feet will be: predicted cell, and (while falling) the cell the fall ends in
        int cx = MathHelper.floor_double(p.posX + p.motionX * PRED);
        int cz = MathHelper.floor_double(p.posZ + p.motionZ * PRED);
        boolean descending = !p.onGround && p.motionY < 0;
        BlockPos land = descending ? landingCell(p) : null;
        boolean landSolid = land != null && !mc.theWorld.isAirBlock(land);
        double above = p.posY - (bridgeY + 1);
        boolean landing = descending && above <= LAND_H && landSolid;
        boolean urgent = descending && !landSolid && above <= 1.6; // the cell we will land on is still empty: no delays, no skips
        int mx = cx, mz = cz;
        if (land != null && ax * land.getX() + az * land.getZ() > ax * cx + az * cz) {
            if (ax != 0) mx = land.getX();
            else mz = land.getZ();
        }

        if (p.onGround || landing) {
            // ground / about to land on solid bridge: look forward, no placing
            rotateToward(laneYaw, fwdPitch, false);
            if (p.onGround && Math.abs(MathHelper.wrapAngleTo180_float(setYaw - laneYaw)) < 25f) p.setSprinting(true);
        } else if (airTicks <= rotDelay && !urgent) {
            rotateToward(laneYaw, fwdPitch, false);
        } else {
            // airborne: click what the rotation the server already has points at, then aim at the next block
            tryPlace(p, mx, mz, urgent);
            if (findTarget(p, mx, mz, urgent)) rotateToward(gYaw, gPitch, urgent);
            else rotateToward(laneYaw + 180f, BACK_PITCH, urgent);
        }
        apply(p);
    }

    // forced movement toward the lane whatever the entity yaw is: solve vanilla moveFlying for (forward, strafe)
    private class TellyInput extends MovementInputFromOptions {
        TellyInput(GameSettings gs) {
            super(gs);
        }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();
            if (!silent) return;
            EntityPlayerSP p = mc.thePlayer;
            if (p == null) return;
            double r = Math.toRadians(p.rotationYaw), s = Math.sin(r), c = Math.cos(r);
            moveForward = (float) (-ax * s + az * c);
            moveStrafe = (float) (ax * c + az * s);
            sneak = false;
            if (p.onGround) jump = true;
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

    // lane = cardinal direction you face, bridge level = the block row you stand on
    private void start(EntityPlayerSP p) {
        int f = MathHelper.floor_double(p.rotationYaw / 90.0 + 0.5) & 3; // 0 S, 1 W, 2 N, 3 E, same order as EnumFacing.getHorizontal
        EnumFacing d = EnumFacing.getHorizontal(f);
        ax = d.getFrontOffsetX();
        az = d.getFrontOffsetZ();
        laneYaw = f * 90f;
        bridgeY = MathHelper.floor_double(p.posY) - 1;
    }

    private void beginSilent(EntityPlayerSP p) {
        camYaw = setYaw = p.rotationYaw;
        camPitch = setPitch = p.rotationPitch;
        airTicks = 0;
        silent = true;
    }

    private void endSilent(EntityPlayerSP p) {
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

    private void rotateToward(float yaw, float pitch, boolean fast) {
        float g = gcd();
        setYaw += quant(MathHelper.wrapAngleTo180_float(yaw - setYaw), YAW_MAX, g, fast);
        setPitch = MathHelper.clamp_float(setPitch + quant(pitch - setPitch, PITCH_MAX, g, fast), -90f, 90f);
    }

    private float quant(float d, float max, float g, boolean fast) {
        if (!legit) return d; // exact: land on the aim every tick, a missed face costs a block
        float ease = fast ? 1f : EASE_MIN + rnd.nextFloat() * (EASE_MAX - EASE_MIN);
        float lim = max * (fast ? 2f : 0.8f + rnd.nextFloat() * 0.4f);
        float want = MathHelper.clamp_float(d * ease, -lim, lim);
        return Math.round(want / g) * g;
    }

    // where the feet will meet the bridge level, from a ballistic walk of the current motion
    private BlockPos landingCell(EntityPlayerSP p) {
        double x = p.posX, y = p.posY, z = p.posZ, vx = p.motionX, vy = p.motionY, vz = p.motionZ;
        double top = bridgeY + 1;
        for (int i = 0; i < 40 && y > top; i++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.91;
            vz *= 0.91;
            vy = (vy - 0.08) * 0.98;
        }
        return new BlockPos(MathHelper.floor_double(x), bridgeY, MathHelper.floor_double(z));
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
    // grows from its end. Sets gYaw/gPitch to a jittered point on that block's face, preferring points whose ray really hits it.
    private boolean findTarget(EntityPlayerSP p, int mx, int mz, boolean urgent) {
        // eye position next tick (air friction 0.91, gravity 0.08 then 0.98)
        double ex = p.posX + p.motionX * 0.91;
        double ey = p.posY + (p.motionY - 0.08) * 0.98 + p.getEyeHeight();
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
                    double sp = (legit && a < 4 && !urgent) ? AIM_SPREAD : 0; // the last attempt (and urgent) aims at the centre
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

    // click only if the real ray along the rotation the server already has hits a face that fills a lane cell
    private void tryPlace(EntityPlayerSP p, int mx, int mz, boolean urgent) {
        if (legit && !urgent && rnd.nextFloat() < SKIP) return;
        ItemStack st = p.getHeldItem();
        if (st == null) return;
        MovingObjectPosition h = ray(p.posX, p.posY + p.getEyeHeight(), p.posZ, setYaw, setPitch);
        if (h == null) return;
        BlockPos cell = h.getBlockPos().offset(h.sideHit);
        if (!mc.theWorld.isAirBlock(cell) || !isLaneCell(cell, mx, mz)) return;
        if (mc.playerController.onPlayerRightClick(p, mc.theWorld, st, h.getBlockPos(), h.sideHit, h.hitVec)) {
            p.swingItem();
        }
    }
}
