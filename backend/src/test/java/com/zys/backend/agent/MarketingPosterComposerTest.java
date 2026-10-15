package com.zys.backend.agent;

import com.zys.backend.agent.tool.impl.AgentImageOps;
import com.zys.backend.agent.tool.impl.MarketingPosterComposer;
import com.zys.backend.agent.service.LayerDocumentService;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketingPosterComposerTest {
    @Test void preservesProductAreaAndDeterministicallyReplacesOnlyTheCopyPanel() {
        BufferedImage source = new BufferedImage(640, 640, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) source.setRGB(x, y, 0xff000000 | x * 97 + y * 53);
        byte[] bytes = LayerDocumentService.toPng(source);
        byte[] rendered = MarketingPosterComposer.compose(bytes, "每日现磨\n唤醒好状态");
        assertTrue(Arrays.equals(rendered, MarketingPosterComposer.compose(bytes, "每日现磨\n唤醒好状态")));
        BufferedImage output = AgentImageOps.read(rendered);
        for (int y = 0; y < 640; y++) for (int x = 0; x < 345; x++) assertEquals(source.getRGB(x,y), output.getRGB(x,y));
        BufferedImage blank = AgentImageOps.read(MarketingPosterComposer.compose(bytes, ""));
        int letters = 0;
        for (int y = 0; y < 640; y++) for (int x = 346; x < 640; x++) if (output.getRGB(x,y) != blank.getRGB(x,y)) letters++;
        assertTrue(letters > 100, "Chinese copy must be rendered into the reserved panel");
        assertEquals(blank.getRGB(639,0), output.getRGB(639,0));
    }
}
