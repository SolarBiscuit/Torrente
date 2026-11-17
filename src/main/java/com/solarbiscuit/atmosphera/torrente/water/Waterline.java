package com.solarbiscuit.atmosphera.torrente.water;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.gpu.PostProgram;
import com.solarbiscuit.atmosphera.lib.world.WaterColumn;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.torrente.Torrente;
import com.solarbiscuit.atmosphera.torrente.TorrenteConfig;
import com.solarbiscuit.atmosphera.torrente.style.Styles;
import com.solarbiscuit.atmosphera.torrente.style.WaterStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;

/**
 * The waterline, 0.4.0 "Contact": with the eye right at the water surface the near plane cuts it, so part of the
 * screen is under water and part above. Vanilla switches the whole view at once. This pass tints the part whose
 * near-plane point lies under the surface with the style's underwater fog colour and draws a soft bright line where
 * the plane cuts it, worked out per pixel from the camera (no stencil). It runs only while the eye is within a few
 * hundredths of a block of the surface at its column, so it costs nothing the rest of the time.
 */
public final class Waterline {
    private static final float BAND = 0.12f;
    /** 0.5.1: the shared post-program holder (lib 0.11.0). */
    private static final PostProgram PROGRAM = new PostProgram(Torrente.LOGGER, "Torrente waterline", "Torrente waterline",
            () -> GlslPrograms.FULLSCREEN_VERTEX, () -> "#version 330 core\n" + Waterline.LINE, "u_InvViewProj", "u_Surface", "u_Fog");
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private Waterline() {
    }

    static void register(AtmosFeature underwater) {
        PostPasses.register(underwater, PostPasses.Stage.AFTER_WEATHER, 20, "torrente_waterline", Waterline::render);
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || PROGRAM.failed() || !TorrenteConfig.waterline.get()) {
            return;
        }
        Vec3 camera = ctx.camera();
        float surface = WaterColumn.surfaceNear(level, net.minecraft.util.Mth.floor(camera.x),
                net.minecraft.util.Mth.floor(camera.y), net.minecraft.util.Mth.floor(camera.z), POS);
        if (Float.isNaN(surface) || Math.abs(camera.y - surface) > BAND) {
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable() || !PROGRAM.ready()) {
            return;
        }
        WaterStyle style = Styles.active(AtmosClient.frame());
        float[] fog = style.fogColor();
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            GL20C.glUseProgram(PROGRAM.id());
            GL20C.glUniformMatrix4fv(PROGRAM.u(0), false, ctx.invViewProjection().get(stack.mallocFloat(16)));
            // y: 1 with the eye under water, where vanilla already tints the view: then only the line is drawn.
            boolean under = mc.gameRenderer.getMainCamera().getFluidInCamera()
                    == net.minecraft.world.level.material.FogType.WATER;
            GL20C.glUniform4f(PROGRAM.u(1), (float) (surface - camera.y), under ? 1f : 0f, 0f, 0f);
            GL20C.glUniform4f(PROGRAM.u(2), fog[0], fog[1], fog[2], 0.35f);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(false);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
            GlslPrograms.drawFullscreen();
            GL11C.glDisable(GL11C.GL_BLEND);
        } finally {
            saved.restore();
        }
    }



    /** Frees the program (the lib's shader reload). */
    public static void release() {
        PROGRAM.release();
    }

    static final String LINE = """
            uniform mat4 u_InvViewProj;
            uniform vec4 u_Surface;  // x surface height relative to the camera, y 1 with the eye under water
            uniform vec4 u_Fog;      // rgb underwater fog colour, a its share
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                // This pixel's point on the near plane, relative to the camera, against the surface.
                vec4 near = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, -1.0, 1.0);
                float h = near.y / near.w - u_Surface.x;
                // In pixels from the line, so the line keeps its width whatever the near plane's distance.
                float px = h / max(fwidth(h), 1e-7);
                float under = (1.0 - smoothstep(-0.5, 0.5, px)) * (1.0 - u_Surface.y);
                float line = 1.0 - smoothstep(0.5, 2.0, abs(px));
                vec3 col = mix(u_Fog.rgb, vec3(0.92, 0.97, 1.0), line);
                fragColor = vec4(col, max(under * u_Fog.a, line * 0.6));
            }
            """;
}
