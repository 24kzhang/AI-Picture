package com.zys.backend.agent.service;

import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * Agent 接口手动鉴权：路径变量无法被注解式空间鉴权解析，
 * 统一在此按 picture → space → 权限列表校验。
 *
 * <p>规则：查看需要 picture:view；创建会话、发消息、执行工具、提交版本需要 picture:edit；
 * 空间 viewer 只能看；管理员可查看和恢复版本，但提交仍需走乐观锁。</p>
 */
@Service
public class AgentAuthService {

    @Resource
    private UserService userService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;

    /**
     * 校验图片查看权限
     */
    public void requireView(User loginUser, Picture picture) {
        requirePermission(loginUser, picture, SpaceUserPermissionConstant.PICTURE_VIEW);
    }

    /**
     * 校验图片编辑权限
     */
    public void requireEdit(User loginUser, Picture picture) {
        requirePermission(loginUser, picture, SpaceUserPermissionConstant.PICTURE_EDIT);
    }

    private void requirePermission(User loginUser, Picture picture, String permission) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (picture == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        if (userService.isAdmin(loginUser)) {
            // 管理员可查看和恢复版本；提交仍由乐观锁把关
            return;
        }
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            // 公共图库：仅本人可编辑，其他人只读
            if (SpaceUserPermissionConstant.PICTURE_VIEW.equals(permission)) {
                return;
            }
            if (!loginUser.getId().equals(picture.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有该图片的编辑权限");
            }
            return;
        }
        Space space = spaceService.getById(spaceId);
        List<String> permissions = spaceUserAuthManager.getPermissionList(space, loginUser);
        if (permissions == null || !permissions.contains(permission)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    SpaceUserPermissionConstant.PICTURE_EDIT.equals(permission)
                            ? "没有该图片的编辑权限" : "没有该图片的查看权限");
        }
    }
}
