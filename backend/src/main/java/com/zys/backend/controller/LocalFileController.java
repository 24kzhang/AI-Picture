package com.zys.backend.controller;

import com.zys.backend.common.BaseResponse;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.LocalFileManager;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.PictureService;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 受控读取旧本地图片，替代整个目录的静态映射。 */
@RestController
public class LocalFileController {
    @Resource private LocalFileManager localFileManager;
    @Resource private PictureService pictureService;
    @Resource private SpaceService spaceService;
    @Resource private SpaceUserAuthManager permissions;
    @Resource private UserService userService;

    @GetMapping("/files/**")
    public ResponseEntity<ByteArrayResource> get(HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        String path = request.getRequestURI().replaceFirst("^/+(?:api/)?files/", "");
        Path file = localFileManager.resolveLocalPathByRelative(path);
        if (file == null || !Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        String url = localFileManager.publicUrl(path);
        Picture picture = pictureService.lambdaQuery()
                .eq(Picture::getUrl, url).or().eq(Picture::getThumbnailUrl, url).one();
        if (picture == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        Space space = picture.getSpaceId() == null ? null : spaceService.getById(picture.getSpaceId());
        List<String> allowed = permissions.getPermissionList(space, user);
        if (!allowed.contains(SpaceUserPermissionConstant.PICTURE_VIEW)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "没有图片查看权限");
        }
        try {
            String contentType = Files.probeContentType(file);
            MediaType mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                    .contentType(mediaType).body(new ByteArrayResource(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取图片失败");
        }
    }
}
