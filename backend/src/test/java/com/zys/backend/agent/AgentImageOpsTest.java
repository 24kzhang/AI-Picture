package com.zys.backend.agent;

import com.zys.backend.agent.tool.impl.AgentImageOps;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentImageOpsTest {
    @Test
    void preservesExactRgbaPixelsOutsideMask() {
        BufferedImage source = image(0xfea38080);
        BufferedImage edited = image(0xff00ff00);
        BufferedImage mask = image(0xff000000);
        BufferedImage result = AgentImageOps.read(AgentImageOps.applyMasked(
                AgentImageOps.writePng(source), AgentImageOps.writePng(edited), AgentImageOps.writePng(mask)));
        assertEquals(source.getRGB(0, 0), result.getRGB(0, 0));
    }

    @Test
    void retainsEditedAlphaInsideMask() {
        BufferedImage result = AgentImageOps.read(AgentImageOps.applyMasked(
                AgentImageOps.writePng(image(0xfea38080)),
                AgentImageOps.writePng(image(0x8000ff00)),
                AgentImageOps.writePng(image(0xffffffff))));
        assertEquals(128, result.getRGB(0, 0) >>> 24);
    }

    private BufferedImage image(int rgba) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, rgba);
        return image;
    }
}
