package com.zys.backend.agent.tool.impl;

import com.zys.backend.agent.service.LayerDocumentService;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 用确定性排字覆盖完整的文案区，图片模型不负责商品文字。 */
public final class MarketingPosterComposer {
    private MarketingPosterComposer() { }

    public static byte[] compose(byte[] background, String caption) {
        BufferedImage image = AgentImageOps.read(background);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int start = (int) Math.round(image.getWidth() * .54);
            int padding = Math.max(12, image.getWidth() / 30);
            int available = image.getWidth() - start - 2 * padding;
            graphics.setColor(new Color(248, 241, 229));
            graphics.fillRect(start, 0, image.getWidth() - start, image.getHeight());
            if (caption == null || caption.trim().isEmpty()) return LayerDocumentService.toPng(image);
            int fontSize = Math.max(12, Math.min(image.getWidth(), image.getHeight()) / 16);
            List<String> lines;
            FontMetrics metrics;
            do {
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
                metrics = graphics.getFontMetrics();
                lines = wrap(caption, metrics, available);
                if (lines.size() * metrics.getHeight() <= image.getHeight() - 2 * padding || fontSize <= 12) break;
                fontSize--;
            } while (true);
            int y = Math.max(padding, (image.getHeight() - lines.size() * metrics.getHeight()) / 2) + metrics.getAscent();
            graphics.setColor(new Color(71, 43, 25));
            for (String line : lines) {
                graphics.drawString(line, start + padding, y);
                y += metrics.getHeight();
            }
        } finally { graphics.dispose(); }
        return LayerDocumentService.toPng(image);
    }

    private static List<String> wrap(String caption, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : caption.split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            for (int point : paragraph.codePoints().toArray()) {
                String character = new String(Character.toChars(point));
                if (line.length() > 0 && metrics.stringWidth(line.toString() + character) > width) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(character);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
