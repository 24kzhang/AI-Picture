package com.zys.backend.agent.tool.impl;

import cn.hutool.json.JSONObject;
import com.zys.backend.agent.tool.AgentToolException;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;

/** Projects the canvas selection into the original pixel coordinates of one transformed layer. */
public final class LayerMaskProjection {
    private LayerMaskProjection() {}

    public static byte[] project(byte[] maskBytes, JSONObject document, JSONObject layer, int width, int height) {
        BufferedImage mask = AgentImageOps.read(maskBytes);
        double displayedWidth = layer.getDouble("width", (double) width);
        double displayedHeight = layer.getDouble("height", (double) height);
        AffineTransform sourceToCanvas = new AffineTransform();
        sourceToCanvas.translate(layer.getDouble("x", 0.0) + displayedWidth / 2,
                layer.getDouble("y", 0.0) + displayedHeight / 2);
        sourceToCanvas.rotate(Math.toRadians(layer.getDouble("rotation", 0.0)));
        sourceToCanvas.scale(layer.getDouble("scaleX", 1.0), layer.getDouble("scaleY", 1.0));
        sourceToCanvas.translate(-displayedWidth / 2, -displayedHeight / 2);
        sourceToCanvas.scale(displayedWidth / width, displayedHeight / height);
        BufferedImage projected = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = projected.createGraphics();
        try {
            AffineTransform canvasToSource = sourceToCanvas.createInverse();
            canvasToSource.scale((double) document.getInt("width", width) / mask.getWidth(),
                    (double) document.getInt("height", height) / mask.getHeight());
            graphics.drawImage(mask, canvasToSource, null);
        } catch (NoninvertibleTransformException error) {
            throw new AgentToolException("目标图层的缩放无效，无法映射选区");
        } finally { graphics.dispose(); }
        boolean overlaps = false;
        for (int y = 0; y < height && !overlaps; y++) for (int x = 0; x < width; x++) {
            int pixel = projected.getRGB(x, y);
            int alpha = pixel >>> 24;
            int light = (((pixel >>> 16) & 255) * 299 + ((pixel >>> 8) & 255) * 587 + (pixel & 255) * 114) / 1000;
            if (Math.min(alpha, light) > 8) { overlaps = true; break; }
        }
        if (!overlaps) throw new AgentToolException("选区与指定图层没有重叠，请重新选择");
        return AgentImageOps.writePng(projected);
    }
}
