package com.zys.backend.model.dto.user;


import com.zys.backend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询用户请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UserQueryRequest extends PageRequest {
    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户账号
     */
    private String userAccount;


    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色
     */
    private String userRole;


}
