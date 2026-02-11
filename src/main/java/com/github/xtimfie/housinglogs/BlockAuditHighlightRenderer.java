package com.github.xtimfie.housinglogs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class BlockAuditHighlightRenderer {

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        java.util.List<BlockAuditManager.AreaSnapshot> areas = BlockAuditManager.getHighlightAreasSnapshot();
        if (areas == null || areas.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getRenderManager() == null) return;

        for (BlockAuditManager.AreaSnapshot area : areas) {
            if (area == null || area.min == null || area.max == null) continue;

            // Inclusive block selection -> convert to block-space AABB with +1 on max
            AxisAlignedBB bb = new AxisAlignedBB(
                    area.min.getX(), area.min.getY(), area.min.getZ(),
                    area.max.getX() + 1.0, area.max.getY() + 1.0, area.max.getZ() + 1.0
            ).offset(-mc.getRenderManager().viewerPosX, -mc.getRenderManager().viewerPosY, -mc.getRenderManager().viewerPosZ);

            drawBoundingBox(bb, area.colorRgba);
        }
    }

    private void drawBoundingBox(AxisAlignedBB bb, int colorRgba) {
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        float r = ((colorRgba >> 24) & 0xFF) / 255f;
        float g = ((colorRgba >> 16) & 0xFF) / 255f;
        float b = ((colorRgba >> 8) & 0xFF) / 255f;
        float a = (colorRgba & 0xFF) / 255f;
        GlStateManager.color(r, g, b, a);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);

        addBoxVertices(wr, bb);

        tess.draw();

        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void addBoxVertices(WorldRenderer wr, AxisAlignedBB bb) {
        double x1 = bb.minX, y1 = bb.minY, z1 = bb.minZ;
        double x2 = bb.maxX, y2 = bb.maxY, z2 = bb.maxZ;

        // Bottom
        wr.pos(x1, y1, z1).endVertex(); wr.pos(x2, y1, z1).endVertex();
        wr.pos(x2, y1, z1).endVertex(); wr.pos(x2, y1, z2).endVertex();
        wr.pos(x2, y1, z2).endVertex(); wr.pos(x1, y1, z2).endVertex();
        wr.pos(x1, y1, z2).endVertex(); wr.pos(x1, y1, z1).endVertex();

        // Top
        wr.pos(x1, y2, z1).endVertex(); wr.pos(x2, y2, z1).endVertex();
        wr.pos(x2, y2, z1).endVertex(); wr.pos(x2, y2, z2).endVertex();
        wr.pos(x2, y2, z2).endVertex(); wr.pos(x1, y2, z2).endVertex();
        wr.pos(x1, y2, z2).endVertex(); wr.pos(x1, y2, z1).endVertex();

        // Sides
        wr.pos(x1, y1, z1).endVertex(); wr.pos(x1, y2, z1).endVertex();
        wr.pos(x2, y1, z1).endVertex(); wr.pos(x2, y2, z1).endVertex();
        wr.pos(x2, y1, z2).endVertex(); wr.pos(x2, y2, z2).endVertex();
        wr.pos(x1, y1, z2).endVertex(); wr.pos(x1, y2, z2).endVertex();
    }
}
