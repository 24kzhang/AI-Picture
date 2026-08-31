package com.zys.backend.model.dto.user;


import lombok.Data;

/**
 * 更新用户请求
 */
@Data
public class UserUpdateRequest {
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
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色
     */
    private String userRole;


}
