package nu.godbridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// Hypixel/Watchdog and most anticheats flag this (rotation snap + fixed pitch), use it on your own server or singleplayer
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final int   KEY       = Keyboard.KEY_Z;
    static final float PITCH     = 75.6f;  // FILL: tune if blocks don't land on the edge
    static final float SMOOTH    = 0.35f;  // 1.0 = instant snap
    static final float CPS       = 15f;    // block placements per second (tick-capped at 20)
    static final int   JUMP_MIN  = 6;
    static final int   JUMP_MAX  = 8;      // inclusive
    static final int   JUMP_HOLD = 2;      // ticks

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rnd = new Random();

    private boolean active;
    private float lockYaw;
    private double lastX, lastZ, travelled;
    private int target, jumpTicks;
    private double clickAcc;
    private Method rightClick;
    private Field delayTimer;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        rightClick = ReflectionHelper.findMethod(Minecraft.class, mc, new String[]{"rightClickMouse", "func_147121_ag"});
        delayTimer = ReflectionHelper.findField(Minecraft.class, "rightClickDelayTimer", "field_71467_ac");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        EntityPlayerSP p = mc.thePlayer;
        boolean want = p != null && mc.currentScreen == null && Keyboard.isKeyDown(KEY);

        if (want && !active) start(p);
        else if (!want && active) stop();
        if (!active) return;

        // aim: nearest diagonal yaw, fixed pitch, smoothed
        p.rotationYaw += MathHelper.wrapAngleTo180_float(lockYaw - p.rotationYaw) * SMOOTH;
        p.rotationPitch += (PITCH - p.rotationPitch) * SMOOTH;

        // distance walked since last jump
        travelled += Math.hypot(p.posX - lastX, p.posZ - lastZ);
        lastX = p.posX;
        lastZ = p.posZ;

        if (travelled >= target) {
            jumpTicks = JUMP_HOLD;
            travelled = 0;
            target = nextTarget();
        }

        GameSettings gs = mc.gameSettings;
        set(gs.keyBindBack, true);
        set(gs.keyBindRight, true);   // S+D, swap to keyBindLeft for S+A
        set(gs.keyBindJump, jumpTicks > 0);

        // own click loop, vanilla hold-RMB is capped at 5/s by rightClickDelayTimer
        clickAcc += CPS / 20.0;
        while (clickAcc >= 1.0) {
            clickAcc -= 1.0;
            click();
        }
        if (jumpTicks > 0) jumpTicks--;
    }

    private void start(EntityPlayerSP p) {
        active = true;
        lockYaw = Math.round((p.rotationYaw - 45f) / 90f) * 90f + 45f;
        lastX = p.posX;
        lastZ = p.posZ;
        travelled = 0;
        jumpTicks = 0;
        clickAcc = 0;
        target = nextTarget();
    }

    private void stop() {
        active = false;
        GameSettings gs = mc.gameSettings;
        set(gs.keyBindBack, false);
        set(gs.keyBindRight, false);
        set(gs.keyBindJump, false);
    }

    private void click() {
        try {
            delayTimer.setInt(mc, 0);
            rightClick.invoke(mc);
        } catch (Exception ignored) {
        }
    }

    private int nextTarget() {
        return JUMP_MIN + rnd.nextInt(JUMP_MAX - JUMP_MIN + 1);
    }

    private static void set(KeyBinding kb, boolean down) {
        KeyBinding.setKeyBindState(kb.getKeyCode(), down);
    }
}
