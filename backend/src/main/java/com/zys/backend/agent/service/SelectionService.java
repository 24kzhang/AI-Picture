package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.tool.AgentToolException;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.model.entity.PictureAgentAsset;
import com.zys.backend.model.entity.PictureEditSession;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 选区维护：选区只限定“改哪里”，图层限定“改谁”。
 * 画布 revision 变更后旧选区自动失效。
 */
@Slf4j
@Service
public class SelectionService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AgentAssetService assetService;

    /**
     * 上传 mask 图片生成 mask 资产（prepare 阶段，不直接激活选区）
     */
    public PictureAgentAsset prepareMask(PictureEditSession session, byte[] maskBytes, String format) {
        if (maskBytes == null || maskBytes.length == 0) {
            throw new AgentToolException("选区蒙版内容为空");
        }
        return assetService.createFromBytes(session.getId(), session.getPictureId(), session.getUserId(),
                AgentAssetKindEnum.MASK.getValue(), "selection", StrUtil.blankToDefault(format, "png"), maskBytes);
    }

    /**
     * 保存选区
     */
    public void put(PictureEditSession session, Long maskAssetId, List<Map<String, Object>> markers) {
        JSONObject value = new JSONObject()
                .set("revision", session.getRevision())
                .set("maskAssetId", maskAssetId)
                .set("markers", markers == null ? new JSONArray() : JSONUtil.parseArray(markers))
                .set("updatedAt", System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(key(session.getId()), value.toString(),
                AgentConstant.SELECTION_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 读取选区，不存在返回 null
     */
    public JSONObject get(Long sessionId) {
        String value = stringRedisTemplate.opsForValue().get(key(sessionId));
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(value);
        } catch (Exception e) {
            log.warn("选区值解析失败，sessionId={}", sessionId);
            return null;
        }
    }

    /**
     * 清除选区
     */
    public void clear(Long sessionId) {
        stringRedisTemplate.delete(key(sessionId));
    }

    /**
     * 取当前会话仍有效的选区；revision 不匹配视为失效并清除
     */
    public JSONObject getValidSelection(PictureEditSession session) {
        JSONObject selection = get(session.getId());
        if (selection == null) {
            return null;
        }
        Integer bound = selection.getInt("revision");
        if (bound == null || !bound.equals(session.getRevision())) {
            clear(session.getId());
            return null;
        }
        return selection;
    }

    /**
     * 读取有效选区对应的 mask 资产，可能返回 null
     */
    public PictureAgentAsset getValidMaskAsset(PictureEditSession session) {
        JSONObject selection = getValidSelection(session);
        if (selection == null) {
            return null;
        }
        Long maskAssetId = selection.getLong("maskAssetId");
        if (maskAssetId == null) {
            return null;
        }
        try {
            return assetService.getInSession(session.getId(), maskAssetId);
        } catch (Exception e) {
            log.warn("选区 mask 资产缺失，sessionId={}，maskAssetId={}", session.getId(), maskAssetId);
            clear(session.getId());
            return null;
        }
    }

    /**
     * 工具执行期 revision 校验：参数中带了 revision 且与当前会话不一致时报错
     */
    public void checkRevision(PictureEditSession session, Map<String, Object> params) {
        Object revision = params.get("revision");
        if (revision == null) {
            return;
        }
        int expected = Integer.parseInt(String.valueOf(revision));
        if (session.getRevision() == null || session.getRevision() != expected) {
            throw new AgentToolException("选区已过期，请重新选择");
        }
    }

    public static String key(Long sessionId) {
        return AgentConstant.SELECTION_KEY_PREFIX + sessionId;
    }
}
