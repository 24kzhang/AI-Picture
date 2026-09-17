package com.zys.backend.agent;

import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.SpaceUser;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.enums.SpaceTypeEnum;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.SpaceUserService;
import com.zys.backend.service.UserService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent 编辑入口的显式图片权限判定。
 * Agent 接口的 pictureId 多在路径变量中，无法依赖 Sa-Token 注解从请求体解析，
 * 因此按 StpInterfaceImpl 同样的规则显式计算权限列表。
 */
@Component
public class AgentPicturePermissionChecker {

    @Resource
    private UserService userService;

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    @Resource
    private PictureService pictureService;

    @Resource
    private SpaceUserAuthManager spaceUserAuthManager;

    /**
     * 计算用户对图片的权限列表（规则与 StpInterfaceImpl 保持一致）
     */
    public List<String> permissionsOf(User loginUser, Picture picture) {
        List<String> adminPermissions = spaceUserAuthManager.getPermissionsByRole("admin");
        Long userId = loginUser.getId();
        Long spaceId = picture.getSpaceId();
        // 公共图库：仅本人或管理员可编辑，其余仅可查看
        if (spaceId == null) {
            if (picture.getUserId().equals(userId) || userService.isAdmin(loginUser)) {
                return adminPermissions;
            }
            return Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW);
        }
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "未找到空间信息");
        if (space.getSpaceType() == SpaceTypeEnum.PRIVATE.getValue()) {
            // 私有空间：仅属主或管理员
            if (space.getUserId().equals(userId) || userService.isAdmin(loginUser)) {
                return adminPermissions;
            }
            return new ArrayList<>();
        }
        // 团队空间：按成员角色
        SpaceUser spaceUser = spaceUserService.lambdaQuery()
                .eq(SpaceUser::getSpaceId, spaceId)
                .eq(SpaceUser::getUserId, userId)
                .one();
        if (spaceUser == null) {
            return new ArrayList<>();
        }
        return spaceUserAuthManager.getPermissionsByRole(spaceUser.getSpaceRole());
    }

    /**
     * 校验登录用户对图片拥有编辑权限
     */
    public Picture checkPictureEditable(User loginUser, Long pictureId) {
        Picture picture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        List<String> permissions = permissionsOf(loginUser, picture);
        if (!permissions.contains(SpaceUserPermissionConstant.PICTURE_EDIT)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无图片编辑权限");
        }
        return picture;
    }
}
