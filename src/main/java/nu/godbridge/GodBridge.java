package nu.godbridge;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// Hypixel/Watchdog and most anticheats flag this (rotation snap + fixed pitch + injected mouse button), use it on your own server or singleplayer
// If your autoclicker ignores injected input (low-level hook filters LLMHF_INJECTED), it will not react to the held XButton
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int   XBUTTON   = 4;      // java.awt button index: 4 = XButton1 (back), 5 = XButton2 (forward)
    static final float PITCH     = 75.6f;  // FILL: tune if blocks don't land on the edge
    static final float SMOOTH    = 0.35f;  // 1.0 = instant snap
    static final float STRAFE    = -1f;    // -1 = S+D, +1 = S+A
    static final int   JUMP_MIN  = 7;      // many godbridgers jump on the 7th block, 8 is harder to time
    static final int   JUMP_MAX  = 7;      // inclusive, set 8 for a 7-8 spread
    static final int   JUMP_HOLD = 2;      // ticks
    static final int   PAUSE     = 1;      // ticks the XButton is released at each jump (1 tick = 50 ms, guides say 20-60 ms)

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random();

    private KeyBinding holdKey;   // rebind in Options > Controls > GodBridge
    private Robot robot;
    private boolean held;

    private boolean active;
    private float lockYaw;
    private double lastX, lastZ, travelled;
    private int target, jumpTicks, pauseTicks;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        holdKey = new KeyBinding("Godbridge (hold)", Keyboard.KEY_Z, "GodBridge");
        ClientRegistry.registerKeyBinding(holdKey);
        try {
            robot = new Robot();
        } catch (Throwable t) {
            System.err.println("[GodBridge] Robot unavailable: " + t);
        }
        // never leave the mouse button stuck down
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                hold(false);
            }
        }));
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        EntityPlayerSP p = mc.thePlayer;
        if (p != null && !(p.movementInput instanceof AutoInput)) p.movementInput = new AutoInput(mc.gameSettings);
        boolean want = p != null && mc.theWorld != null && mc.currentScreen == null && holdKey.isKeyDown();

        if (want && !active) start(p);
        else if (!want && active) active = false;
        if (!active) {
            hold(false);
            return;
        }

        // aim: nearest diagonal yaw, fixed pitch, smoothed
        p.rotationYaw += MathHelper.wrapAngleTo180_float(lockYaw - p.rotationYaw) * SMOOTH;
        p.rotationPitch += (PITCH - p.rotationPitch) * SMOOTH;

        // jump every 7-8 blocks walked
        if (jumpTicks > 0) jumpTicks--;
        travelled += Math.hypot(p.posX - lastX, p.posZ - lastZ);
        lastX = p.posX;
        lastZ = p.posZ;
        if (travelled >= target) {
            jumpTicks = JUMP_HOLD;
            pauseTicks = PAUSE;
            travelled = 0;
            target = nextTarget();
            // aim reset: back on the exact angles for the landing
            p.rotationYaw += MathHelper.wrapAngleTo180_float(lockYaw - p.rotationYaw);
            p.rotationPitch = PITCH;
        }

        // god bridge cannot run past ~8 blocks without a reset: release clicks for ONE tick at the jump, then place mid-air
        hold(pauseTicks <= 0);
        if (pauseTicks > 0) pauseTicks--;
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
        pauseTicks = 0;
        target = nextTarget();
    }

    private synchronized void hold(boolean down) {
        if (robot == null || held == down) return;
        try {
            int mask = InputEvent.getMaskForButton(XBUTTON);
            if (down) robot.mousePress(mask);
            else robot.mouseRelease(mask);
            held = down;
        } catch (Throwable t) {
            System.err.println("[GodBridge] XButton" + XBUTTON + " failed: " + t);
            robot = null;
        }
    }

    private int nextTarget() {
        return JUMP_MIN + rnd.nextInt(JUMP_MAX - JUMP_MIN + 1);
    }
}
