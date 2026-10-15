package com.zys.backend.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.zys.backend.agent.provider.AgentImageProviderRouter;
import com.zys.backend.agent.provider.EditImageRequest;
import com.zys.backend.agent.service.AgentAssetService;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.service.SelectionService;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.impl.AgentImageOps;
import com.zys.backend.agent.tool.impl.RegionTools;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.entity.PictureToolRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegionLayerScopeTest {
    private RegionTools tool;
    private AgentAssetService assets;
    private AgentImageProviderRouter provider;
    private SelectionService selections;
    private PictureEditSession session;
    private JSONObject document;
    private PictureAgentAsset mask;
    private Map<String,Object> params;

    @BeforeEach
    void setUp() {
        tool = new RegionTools(); assets = mock(AgentAssetService.class);
        provider = mock(AgentImageProviderRouter.class); selections = mock(SelectionService.class);
        LayerDocumentService documents = new LayerDocumentService();
        ReflectionTestUtils.setField(documents, "assetService", assets);
        ReflectionTestUtils.setField(tool, "documentService", documents);
        ReflectionTestUtils.setField(tool, "assetService", assets);
        ReflectionTestUtils.setField(tool, "imageProvider", provider);
        ReflectionTestUtils.setField(tool, "selectionService", selections);
        session = new PictureEditSession(); session.setId(9L); session.setPictureId(100L); session.setUserId(2L); session.setRevision(1);
        PictureAgentAsset source = new PictureAgentAsset(); source.setId(10L); source.setWidth(10); source.setHeight(10);
        mask = new PictureAgentAsset(); mask.setId(11L);
        PictureAgentAsset output = new PictureAgentAsset(); output.setId(20L); output.setUrl("https://example.test/output.png");
        when(assets.getInSession(9L, 10L)).thenReturn(source); when(assets.getInSession(9L, 11L)).thenReturn(mask);
        when(assets.loadBytes(source)).thenReturn(png(10, 10, Color.RED));
        when(provider.edit(any(), any())).thenReturn(Collections.singletonList(png(4, 4, Color.BLUE)));
        when(assets.createFromBytes(eq(9L), eq(100L), eq(2L), anyString(), anyString(), eq("png"), any())).thenReturn(output);
        JSONObject base = documents.imageLayer("base", "底图", source, 20, 30, 10, 10, false)
                .set("rotation", 90).set("scaleX", 2).set("scaleY", 2);
        JSONObject text = documents.textLayer("title", "标题", "保留文字图层", 12, "#000000");
        document = new JSONObject().set("width", 100).set("height", 100).set("layers", new JSONArray().put(base).put(text));
        params = new LinkedHashMap<>(); params.put("layerId", "base"); params.put("maskAssetId", "11"); params.put("revision", 1); params.put("prompt", "变蓝");
    }

    private byte[] png(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); g.setColor(color); g.fillRect(0, 0, width, height); g.dispose();
        return AgentImageOps.writePng(image);
    }

    private byte[] selection(int x, int y, int width, int height) {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); g.setColor(Color.WHITE); g.fillRect(x, y, width, height); g.dispose();
        return AgentImageOps.writePng(image);
    }

    private Map<String,Object> execute() throws Exception {
        session.setDocumentJson(document.toString());
        PictureToolRun run = new PictureToolRun(); run.setTool("replace_region");
        return tool.specs().get(1).getHandler().handle(new ToolRunContext().setSession(session).setToolRun(run), params);
    }

    @Test
    void selectedLayerUsesInverseCoordinatesAndPreservesAllOtherLayersAndPixels() throws Exception {
        when(assets.loadBytes(mask)).thenReturn(selection(25, 25, 10, 10));
        String before = document.toString();
        Map<String,Object> result = execute();
        JSONObject after = (JSONObject) result.get("document");
        assertEquals(2, after.getJSONArray("layers").size());
        assertEquals(document.getJSONArray("layers").getJSONObject(1).toString(), after.getJSONArray("layers").getJSONObject(1).toString());
        JSONObject edited = after.getJSONArray("layers").getJSONObject(0);
        assertEquals("20", edited.getStr("assetId"));
        assertEquals(90, edited.getInt("rotation")); assertEquals(2, edited.getDouble("scaleX"), 0.001);
        assertFalse(result.containsKey("adoptAssetId"));
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(assets).createFromBytes(eq(9L), eq(100L), eq(2L), anyString(), eq("replace_region"), eq("png"), bytes.capture());
        BufferedImage output = AgentImageOps.read(bytes.getValue());
        assertEquals(10, output.getWidth()); assertEquals(10, output.getHeight());
        assertEquals(Color.BLUE.getRGB(), output.getRGB(2, 2));
        assertEquals(Color.RED.getRGB(), output.getRGB(7, 2));
        assertEquals(Color.RED.getRGB(), output.getRGB(2, 7));
        ArgumentCaptor<EditImageRequest> request = ArgumentCaptor.forClass(EditImageRequest.class);
        verify(provider).edit(request.capture(), any());
        assertEquals(10, AgentImageOps.read(request.getValue().getImage()).getWidth());
        assertEquals(before, session.getDocumentJson());
    }

    @Test
    void nonoverlappingSelectionFailsBeforeModelSubmissionAndKeepsSelection() {
        when(assets.loadBytes(mask)).thenReturn(selection(80, 80, 10, 10));
        assertThrows(AgentToolException.class, this::execute);
        verifyNoInteractions(provider);
        verify(selections, never()).clear(anyLong());
        verify(assets, never()).createFromBytes(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
    }
}
