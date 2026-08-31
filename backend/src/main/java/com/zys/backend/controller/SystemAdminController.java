package com.zys.backend.controller;

import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.service.SystemAdminService;
import com.zys.backend.service.PictureDedupService;
import com.zys.backend.service.SystemSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 本机独立管理控制台接口。
 *
 * <p>该接口不依赖业务账号登录，供本机管理页直接维护运行配置与数据。</p>
 */
@RestController
@RequestMapping("/local-admin")
public class SystemAdminController {

    @Resource
    private SystemAdminService systemAdminService;

    @Resource
    private SystemSettingsService systemSettingsService;

    @Resource
    private PictureDedupService pictureDedupService;

    @GetMapping("/overview")
    public BaseResponse<Map<String, Object>> overview() {
        return ResultUtils.success(systemAdminService.overview());
    }

    @GetMapping("/database/tables")
    public BaseResponse<List<Map<String, Object>>> listTables() {
        return ResultUtils.success(systemAdminService.listTables());
    }

    @GetMapping("/database/rows")
    public BaseResponse<Map<String, Object>> getRows(
            @RequestParam String table,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResultUtils.success(systemAdminService.getRows(table, page, pageSize));
    }

    @PostMapping("/database/row/insert")
    public BaseResponse<Integer> insertRow(@RequestBody Map<String, Object> request) {
        return ResultUtils.success(systemAdminService.insertRow(
                requireTable(request),
                mapValue(request, "values")
        ));
    }

    @PostMapping("/database/row/update")
    public BaseResponse<Integer> updateRow(@RequestBody Map<String, Object> request) {
        return ResultUtils.success(systemAdminService.updateRow(
                requireTable(request),
                mapValue(request, "primaryKey"),
                mapValue(request, "values")
        ));
    }

    @PostMapping("/database/row/delete")
    public BaseResponse<Integer> deleteRow(@RequestBody Map<String, Object> request) {
        return ResultUtils.success(systemAdminService.deleteRow(
                requireTable(request),
                mapValue(request, "primaryKey")
        ));
    }

    @GetMapping("/redis/keys")
    public BaseResponse<Map<String, Object>> listRedisKeys(
            @RequestParam(defaultValue = "*") String pattern,
            @RequestParam(defaultValue = "100") int limit) {
        return ResultUtils.success(systemAdminService.listRedisKeys(pattern, limit));
    }

    @PostMapping("/redis/key/save")
    public BaseResponse<Map<String, Object>> saveRedisKey(
            @RequestBody Map<String, Object> request) {
        String key = stringValue(request.get("key"));
        String value = request.get("value") == null ? "" : String.valueOf(request.get("value"));
        Long ttl = request.get("ttl") == null
                ? null
                : Long.valueOf(String.valueOf(request.get("ttl")));
        return ResultUtils.success(systemAdminService.saveRedisString(key, value, ttl));
    }

    @PostMapping("/redis/key/delete")
    public BaseResponse<Boolean> deleteRedisKey(@RequestBody Map<String, Object> request) {
        return ResultUtils.success(
                systemAdminService.deleteRedisKey(stringValue(request.get("key"))));
    }

    @GetMapping("/pictures/duplicates/preview")
    public BaseResponse<Map<String, Object>> previewPictureDuplicates() {
        return ResultUtils.success(pictureDedupService.preview());
    }

    @PostMapping("/pictures/duplicates/execute")
    public BaseResponse<Map<String, Object>> deduplicatePictures() {
        return ResultUtils.success(pictureDedupService.execute());
    }

    @PostMapping("/settings")
    public BaseResponse<Map<String, Object>> updateSettings(
            @RequestBody Map<String, String> request) {
        return ResultUtils.success(systemSettingsService.update(request));
    }

    private String requireTable(Map<String, Object> request) {
        String table = stringValue(request.get("table"));
        if (table.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据库表不能为空");
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (!(value instanceof Map)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, key + " 格式错误");
        }
        return (Map<String, Object>) value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
