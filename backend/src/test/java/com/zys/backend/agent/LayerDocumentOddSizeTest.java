package com.zys.backend.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.AgentAssetService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.tool.impl.AgentImageOps;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LayerDocumentOddSizeTest {
    @Test void oddSizedLayersHaveNoHalfPixelOffsetOrUnselectedPixelBleed() {
        LayerDocumentService documents = new LayerDocumentService();
        AgentAssetService assets = mock(AgentAssetService.class);
        ReflectionTestUtils.setField(documents, "assetService", assets);
        PictureEditSession session = new PictureEditSession(); session.setId(1L);
        PictureAgentAsset asset = new PictureAgentAsset(); asset.setId(2L);
        BufferedImage pixels = new BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) pixels.setRGB(x,y,0xff102030+x*4096+y*256);
        AtomicReference<byte[]> bytes = new AtomicReference<>(LayerDocumentService.toPng(pixels));
        when(assets.getInSession(1L,2L)).thenReturn(asset);
        when(assets.loadBytes(asset)).thenAnswer(i -> bytes.get());
        JSONObject layer = new JSONObject().set("kind","image").set("assetId","2").set("width",7).set("height",5);
        JSONObject document = new JSONObject().set("width",7).set("height",5).set("layers",new JSONArray().put(layer));
        BufferedImage before = AgentImageOps.read(documents.flatten(session,document));
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) assertEquals(pixels.getRGB(x,y),before.getRGB(x,y));
        pixels.setRGB(3,2,0xffcc2211);bytes.set(LayerDocumentService.toPng(pixels));
        BufferedImage after = AgentImageOps.read(documents.flatten(session,document));
        int changed = 0;
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) if (before.getRGB(x,y)!=after.getRGB(x,y)) {
            changed++;assertEquals(3,x);assertEquals(2,y);
        }
        assertEquals(1,changed);
    }
}
