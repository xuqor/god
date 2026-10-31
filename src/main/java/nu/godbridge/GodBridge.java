package nu.godbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

// SafeWalk: at the edge of a block the mod presses sneak for you (real sneak, real packets), so the vanilla edge clamp keeps you on the block.
// Press Z to switch it on or off (rebind in Options > Controls > SafeWalk).
@Mod(modid = "godbridge", version = "1.0", acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GodBridge {
    static final boolean HOLD = false;   // true: active only while the key is held
    static final double  LOOK = 0.2;     // blocks of extra lookahead along your input, on top of current momentum

    private final Minecraft mc = Minecraft.getMinecraft();
    private KeyBinding key;
    private boolean on;

    @EventHandler
    public void init(FMLInitializationEvent e) {
        key = new KeyBinding("SafeWalk (toggle)", Keyboard.KEY_Z, "SafeWalk");
        ClientRegistry.registerKeyBinding(key);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) return;

        if (!(p.movementInput instanceof SafeInput)) p.movementInput = new SafeInput(mc.gameSettings);

        if (HOLD) {
            on = mc.currentScreen == null && key.isKeyDown();
        } else {
            while (key.isPressed()) {
                on = !on;
                p.addChatMessage(new ChatComponentText("[SafeWalk] " + (on ? "ON" : "OFF")));
            }
        }
    }

    // vanilla input first, then add sneak when the next step would leave the floor
    private class SafeInput extends MovementInputFromOptions {
        SafeInput(GameSettings gs) {
            super(gs);
        }

        @Override
        public void updatePlayerMoveState() {
            super.updatePlayerMoveState();
            if (!on || sneak) return;
            EntityPlayerSP p = mc.thePlayer;
            if (p == null || mc.theWorld == null || !p.onGround) return;

            // direction of your input in the world (vanilla moveFlying)
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

            // same support test vanilla sneak uses: the box one step ahead, shifted down a block, hits nothing -> edge
            boolean floor = !mc.theWorld.getCollidingBoundingBoxes(p,
                    p.getEntityBoundingBox().offset(p.motionX + dx, -1.0, p.motionZ + dz)).isEmpty();
            if (!floor) {
                sneak = true;
                moveForward *= 0.3f; // vanilla sneak slowdown
                moveStrafe *= 0.3f;
            }
        }
    }
}
