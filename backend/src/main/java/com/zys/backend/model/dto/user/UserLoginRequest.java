package com.zys.backend.model.dto.user;

import lombok.Data;

@Data
//public class UserRegisterRequest implements Serializable {
public class UserLoginRequest {

//    private static final long serialVersionUID = 8735650154179439661L;
    /**
     * 用户名
     */
    private String userAccount;

    /**
     * 密码
     */
    private String userPassword;


}
