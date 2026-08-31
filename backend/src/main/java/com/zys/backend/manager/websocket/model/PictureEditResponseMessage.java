package com.zys.backend.manager.websocket.model;

import com.zys.backend.model.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 图片编辑响应消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PictureEditResponseMessage {

    /**
     * 消息类型，例如 "INFO", "ERROR", "ENTER_EDIT", "EXIT_EDIT", "EDIT_ACTION"
     */
    private String type;

    /**
     * 信息
     */
    private String message;

    /**
     * 执行的编辑动作
     */
    private String editAction;

    /**
     * 编辑动作附带的数据
     */
    private Map<String, Object> editData;


    /**
     * 用户信息
     */
    private UserVO user;

    /**
     * 当前打开工作台的观看用户列表
     */
    private List<UserVO> viewers;

    /**
     * 当前获得编辑权的用户
     */
    private UserVO editingUser;
}