package com.zys.backend.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.zys.backend.agent.service.LayerDocumentService;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.agent.tool.ToolRegistry;
import com.zys.backend.agent.tool.ToolRunContext;
import com.zys.backend.agent.tool.impl.DocumentTools;
import com.zys.backend.agent.tool.impl.TextLayerTools;
import com.zys.backend.model.entity.PictureEditSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TextLayerToolsTest {
    private ToolRegistry registry;
    private PictureEditSession session;
    private LayerDocumentService documents;

    @BeforeEach
    void setUp() {
        documents = new LayerDocumentService();
        TextLayerTools text = new TextLayerTools();
        DocumentTools edits = new DocumentTools();
        ReflectionTestUtils.setField(text, "documentService", documents);
        ReflectionTestUtils.setField(edits, "documentService", documents);
        registry = new ToolRegistry();
        ReflectionTestUtils.setField(registry, "tools", Arrays.asList(text, edits));
        registry.init();
        JSONObject base = new JSONObject().set("id", "base").set("kind", "image").set("visible", true);
        session = new PictureEditSession();
        session.setDocumentJson(new JSONObject().set("width", 640).set("height", 480)
                .set("layers", new JSONArray().put(base)).toString());
    }

    private JSONObject execute(String tool, Map<String, Object> params) throws Exception {
        Map<String, Object> normalized = registry.validateParams(tool, params);
        return (JSONObject) registry.specOf(tool).getHandler().handle(new ToolRunContext().setSession(session), normalized).get("document");
    }

    private Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    @Test
    void addsEditableMultilineLayerAndKeepsTheBackingImageAndStoredSnapshot() throws Exception {
        String before = session.getDocumentJson();
        JSONObject result = execute("add_text_layer", params("text", "中文标题\n第二行", "name", "标题"));
        JSONObject text = result.getJSONArray("layers").getJSONObject(1);
        assertEquals("text", text.getStr("kind"));
        assertEquals("标题", text.getStr("name"));
        assertTrue(text.getStr("id").startsWith("text-"));
        assertEquals(2 * 32 * 1.2, text.getDouble("height"), 0.001);
        assertTrue(text.getDouble("width") > 0);
        assertFalse(text.getBool("locked"));
        assertEquals("image", result.getJSONArray("layers").getJSONObject(0).getStr("kind"));
        assertEquals(before, session.getDocumentJson(), "Changes are only persisted by the normal history executor");
    }

    @Test
    void textEditingRecomputesBoundsAndRejectsImageTargets() throws Exception {
        JSONObject created = execute("add_text_layer", params("text", "A"));
        session.setDocumentJson(created.toString());
        JSONObject before = created.getJSONArray("layers").getJSONObject(1);
        JSONObject result = execute("set_layer_text", params("text", "更长的中文标题\r\n第二行", "fontSize", 40));
        JSONObject after = result.getJSONArray("layers").getJSONObject(1);
        assertTrue(after.getDouble("width") > before.getDouble("width"));
        assertEquals(96, after.getDouble("height"), 0.001);
        assertThrows(AgentToolException.class, () -> execute("set_layer_text", params("layerId", "base", "text", "不能改图像层")));
        assertEquals("image", documents.parseDocument(session).getJSONArray("layers").getJSONObject(0).getStr("kind"));
    }
}
