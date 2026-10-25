package com.zys.backend.controller;

import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.vo.PictureVO;
import com.zys.backend.service.PictureGenerationService;
import com.zys.backend.service.UserService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;

@RestController @RequestMapping("/picture/generations")
public class PictureGenerationController {
    @Resource private PictureGenerationService generation;
    @Resource private UserService users;
    @Data public static class GenerateRequest {private Long spaceId;private String prompt;private Integer width;private Integer height;private String idempotencyKey;}
    @Data public static class SaveRequest {private String name;}
    @PostMapping public BaseResponse<Map<String,Object>> create(@RequestBody GenerateRequest body,HttpServletRequest request){
        if(body==null)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(generation.create(users.getLoginUser(request),body.getSpaceId(),body.getPrompt(),body.getWidth(),body.getHeight(),body.getIdempotencyKey()));
    }
    @GetMapping("/{id}") public BaseResponse<Map<String,Object>> get(@PathVariable String id,HttpServletRequest request){return ResultUtils.success(generation.snapshot(id,users.getLoginUser(request)));}
    @GetMapping public BaseResponse<List<Map<String,Object>>> recent(@RequestParam(required=false) Long spaceId,HttpServletRequest request){return ResultUtils.success(generation.recent(spaceId,users.getLoginUser(request)));}
    @PostMapping("/{id}/transfer") public BaseResponse<Map<String,Object>> transfer(@PathVariable String id,HttpServletRequest request){return ResultUtils.success(generation.retryTransfer(id,users.getLoginUser(request)));}
    @PostMapping("/{id}/save") public BaseResponse<PictureVO> save(@PathVariable String id,@RequestBody(required=false) SaveRequest body,HttpServletRequest request){return ResultUtils.success(generation.save(id,body==null?null:body.getName(),users.getLoginUser(request)));}
}
