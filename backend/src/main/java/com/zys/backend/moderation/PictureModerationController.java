package com.zys.backend.moderation;

import com.zys.backend.annotation.AuthCheck;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.constant.UserConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.Map;

@RestController
@RequestMapping("/picture")
public class PictureModerationController {
    @Resource private JdbcTemplate jdbc;
    @PostMapping("/ai-review")
    @AuthCheck(mustRole=UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> request(@RequestBody Map<String,String> input){
        long id;try{id=Long.parseLong(input.get("id"));}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR);}
        int updated=jdbc.update("UPDATE picture SET reviewStatus=0,reviewerId=0,reviewTime=NOW(),reviewMessage='等待AI审核' WHERE id=? AND isDelete=0",id);
        if(updated==0)throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(true);
    }
}
