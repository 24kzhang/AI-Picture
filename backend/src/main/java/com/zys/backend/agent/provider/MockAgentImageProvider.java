package com.zys.backend.agent.provider;

import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ProgressReporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock Provider：未配置 AI Key 或显式指定 mock 时使用。
 * 结果 = 原图 + 半透明色罩 + MOCK 水印，用于流程联调与验收，不产生真实 AI 效果。
 */
@Slf4j
@Component("mockAgentImageProvider")
public class MockAgentImageProvider implements AgentImageProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public List<byte[]> edit(EditImageRequest request, ProgressReporter reporter) {
        BufferedImage source = read(request.getImage());
        int count = Math.max(1, Math.min(4, request.getCount()));
        List<byte[]> results = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (reporter != null) {
                reporter.report(40 + 40 * index / count, "模拟生成中（" + (index + 1) + "/" + count + "）");
            }
            sleep(400);
            int tint = (request.getPrompt() == null ? 0 : request.getPrompt().hashCode()) + index * 37;
            results.add(tint(source, tint, index));
        }
        if (reporter != null) {
            reporter.report(85, "模拟完成");
        }
        return results;
    }

    @Override
    public byte[] upscale(byte[] image, int scale, ProgressReporter reporter) {
        BufferedImage source = read(image);
        int factor = scale < 2 ? 2 : Math.min(scale, 4);
        if (reporter != null) {
            reporter.report(50, "模拟超分");
        }
        sleep(300);
        int width = source.getWidth() * factor;
        int height = source.getHeight() * factor;
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        if (reporter != null) {
            reporter.report(90, "模拟超分完成");
        }
        return write(target);
    }

    private byte[] tint(BufferedImage source, int seed, int index) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, null);
        Color[] palette = {
                new Color(255, 244, 214), new Color(214, 233, 255),
                new Color(255, 219, 232), new Color(220, 255, 226)
        };
        Color tint = palette[Math.abs(seed) % palette.length];
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
        graphics.setColor(tint);
        graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(new Color(0, 0, 0, 120));
        int fontSize = Math.max(18, target.getWidth() / 18);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        String label = "MOCK-" + (index + 1);
        int textWidth = graphics.getFontMetrics().stringWidth(label);
        graphics.drawString(label, target.getWidth() - textWidth - fontSize / 2, target.getHeight() - fontSize);
        graphics.dispose();
        return write(target);
    }

    private BufferedImage read(byte[] data) {
        if (data == null || data.length == 0) {
            throw new AgentToolException("缺少输入图片");
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

    private byte[] write(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new AgentToolException("图片编码失败", e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
