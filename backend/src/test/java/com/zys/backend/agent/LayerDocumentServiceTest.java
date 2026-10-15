package com.zys.backend.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.AgentAssetService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LayerDocumentServiceTest {
    private LayerDocumentService service;
    private PictureEditSession session;
    private JSONObject document;
    private JSONObject layer;
    private byte[] original;

    @BeforeEach
    void setUp() throws Exception {
        service = new LayerDocumentService();
        AgentAssetService assets = mock(AgentAssetService.class);
        ReflectionTestUtils.setField(service, "assetService", assets);
        session = new PictureEditSession();
        session.setId(9L);
        PictureAgentAsset asset = new PictureAgentAsset();
        asset.setId(10L);
        asset.setWidth(80);
        asset.setHeight(40);
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 80; x++)
            image.setRGB(x, y, x < 40 ? Color.RED.getRGB() : Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        original = output.toByteArray();
        when(assets.getInSession(9L, 10L)).thenReturn(asset);
        when(assets.loadBytes(asset)).thenReturn(original);
        layer = service.imageLayer("base", "底图", asset, 0, 0, 80, 40, true);
        document = new JSONObject().set("width", 80).set("height", 40)
                .set("layers", new JSONArray().put(layer));
    }

    private BufferedImage render() throws Exception {
        session.setDocumentJson(document.toString());
        return ImageIO.read(new ByteArrayInputStream(service.currentCanvasBytes(session)));
    }

    @Test
    void unchangedCanvasPreservesOriginalBytes() {
        session.setDocumentJson(document.toString());
        assertArrayEquals(original, service.currentCanvasBytes(session));
    }

    @Test
    void cropUsesCanvasSizeAndOffsetInsteadOfOriginalAsset() throws Exception {
        document.set("width", 40);
        layer.set("x", -40);
        BufferedImage actual = render();
        assertEquals(40, actual.getWidth());
        assertEquals(Color.BLUE.getRGB(), actual.getRGB(20, 20));
    }

    @Test
    void opacityIsIncludedInInputForSubsequentTools() throws Exception {
        layer.set("opacity", 0.5);
        Color pixel = new Color(render().getRGB(20, 20));
        assertEquals(255, pixel.getRed());
        assertEquals(0, pixel.getGreen());
        int alpha = render().getRGB(20, 20) >>> 24;
        assertTrue(alpha >= 127 && alpha <= 128);
    }

    @Test
    void resizedLayerDoesNotReuseOriginalAsset() throws Exception {
        layer.set("width", 40);
        assertEquals(0, render().getRGB(60, 20) >>> 24);
    }

    @Test
    void multilineTextPreservesBlankLinesAndWindowsNewlines() throws Exception {
        JSONObject text = new JSONObject().set("kind", "text").set("text", "A\r\n\r\nB")
                .set("x", 5).set("y", 2).set("fontSize", 12).set("fill", "#000000");
        document.set("height", 80).set("layers", new JSONArray().put(text));
        BufferedImage actual = render();
        int lowerLinePixels = 0;
        for (int y = 30; y < 47; y++) for (int x = 5; x < 25; x++)
            if ((actual.getRGB(x, y) >>> 24) > 0) lowerLinePixels++;
        assertTrue(lowerLinePixels > 0, "The third line must be rendered below the blank second line");
    }
}
