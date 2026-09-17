package com.zys.backend.agent.tool.impl;

import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.tool.AgentToolException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Java2D 像素处理：缩放、letterbox、调色、按 mask 合成。
 */
public final class AgentImageOps {

    private AgentImageOps() {
    }

    public static BufferedImage read(byte[] data) {
        if (data == null || data.length == 0) {
            throw new AgentToolException("图片内容为空");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                throw new AgentToolException("图片解码失败");
            }
            return image;
        } catch (AgentToolException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentToolException("图片解码失败", e);
        }
    }

    public static byte[] writePng(BufferedImage image) {
        return LayerDocumentService.toPng(image);
    }

    /**
     * 完整放入目标画幅（等比缩放 + 白边），不裁切内容
     */
    public static byte[] letterbox(byte[] data, int width, int height) {
        BufferedImage source = read(data);
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        double scale = Math.min((double) width / source.getWidth(), (double) height / source.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        graphics.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            ImageIO.write(target, "jpg", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new AgentToolException("图片编码失败", e);
        }
    }

    /**
     * 等比缩放到目标最长边
     */
    public static BufferedImage resize(BufferedImage source, int maxEdge) {
        int max = Math.max(source.getWidth(), source.getHeight());
        if (max <= maxEdge) {
            return source;
        }
        double scale = (double) maxEdge / max;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    /**
     * 本地调色：亮度/对比度/饱和度/色温，参数 -1..1
     */
    public static byte[] adjust(byte[] data, Map<String, Object> params) {
        BufferedImage source = read(data);
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        double brightness = doubleOf(params.get("brightness"));
        double contrast = doubleOf(params.get("contrast"));
        double saturation = doubleOf(params.get("saturation"));
        double temperature = doubleOf(params.get("temperature"));
        double tint = doubleOf(params.get("tint"));
        double highlights = doubleOf(params.get("highlights"));
        double shadows = doubleOf(params.get("shadows"));
        double contrastFactor = (1.0 + contrast) * (1.0 + contrast);

        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            source.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x];
                int alpha = (rgb >> 24) & 0xff;
                double r = (rgb >> 16) & 0xff;
                double g = (rgb >> 8) & 0xff;
                double b = rgb & 0xff;
                // 亮度
                r += brightness * 64;
                g += brightness * 64;
                b += brightness * 64;
                // 对比度（围绕中灰）
                r = (r - 128) * contrastFactor + 128;
                g = (g - 128) * contrastFactor + 128;
                b = (b - 128) * contrastFactor + 128;
                // 高光/阴影（按亮度加权）
                double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                double highlightWeight = Math.max(0, Math.min(1, (luma - 0.5) * 2));
                double shadowWeight = Math.max(0, Math.min(1, (0.5 - luma) * 2));
                double lightDelta = highlights * 48 * highlightWeight + shadows * 48 * shadowWeight;
                r += lightDelta;
                g += lightDelta;
                b += lightDelta;
                // 色温（冷暖）与色调（绿品）
                r += temperature * 32;
                b -= temperature * 32;
                g += tint * 32;
                // 饱和度
                double gray = 0.299 * r + 0.587 * g + 0.114 * b;
                double satFactor = 1.0 + saturation;
                r = gray + (r - gray) * satFactor;
                g = gray + (g - gray) * satFactor;
                b = gray + (b - gray) * satFactor;
                row[x] = (alpha << 24)
                        | (clamp(r) << 16)
                        | (clamp(g) << 8)
                        | clamp(b);
            }
            target.setRGB(0, y, width, 1, row, 0, width);
        }
        return writePng(target);
    }

    /**
     * 按 mask（白色区域生效）把 edited 合成回 source
     */
    public static byte[] applyMasked(byte[] sourceData, byte[] editedData, byte[] maskData) {
        BufferedImage source = read(sourceData);
        BufferedImage edited = read(editedData);
        BufferedImage mask = read(maskData);
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();

        BufferedImage scaledMask = scaleTo(mask, width, height);
        BufferedImage scaledEdited = scaleTo(edited, width, height);

        int[] sourceRow = new int[width];
        int[] editedRow = new int[width];
        int[] maskRow = new int[width];
        for (int y = 0; y < height; y++) {
            target.getRGB(0, y, width, 1, sourceRow, 0, width);
            scaledEdited.getRGB(0, y, width, 1, editedRow, 0, width);
            scaledMask.getRGB(0, y, width, 1, maskRow, 0, width);
            for (int x = 0; x < width; x++) {
                int m = maskRow[x];
                int maskAlpha = (m >> 24) & 0xff;
                int maskLuma = ((m >> 16) & 0xff) * 299 / 1000
                        + ((m >> 8) & 0xff) * 587 / 1000
                        + (m & 0xff) * 114 / 1000;
                // 白底黑字或黑底白字 mask 都支持：取“非黑程度”与 alpha 的较小者
                int strength = Math.min(maskAlpha, maskLuma);
                if (strength <= 8) {
                    continue;
                }
                double ratio = strength / 255.0;
                int sr = (sourceRow[x] >> 16) & 0xff;
                int sg = (sourceRow[x] >> 8) & 0xff;
                int sb = sourceRow[x] & 0xff;
                int er = (editedRow[x] >> 16) & 0xff;
                int eg = (editedRow[x] >> 8) & 0xff;
                int eb = editedRow[x] & 0xff;
                int r = (int) Math.round(sr + (er - sr) * ratio);
                int g = (int) Math.round(sg + (eg - sg) * ratio);
                int b = (int) Math.round(sb + (eb - sb) * ratio);
                sourceRow[x] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
            target.setRGB(0, y, width, 1, sourceRow, 0, width);
        }
        return writePng(target);
    }

    /**
     * 以 mask 白色区域为界抠出 alpha 通道（无选区时按整层处理由调用方兜底）
     */
    public static byte[] alphaMaskOf(byte[] maskData, int width, int height) {
        BufferedImage mask = scaleTo(read(maskData), width, height);
        return writePng(mask);
    }

    private static BufferedImage scaleTo(BufferedImage image, int width, int height) {
        if (image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static double doubleOf(Object value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
