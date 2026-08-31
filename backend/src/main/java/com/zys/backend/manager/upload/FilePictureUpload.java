package com.zys.backend.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 浏览器文件上传。
 */
@Service
public class FilePictureUpload extends PictureUploadTemplate {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpeg", "png", "jpg");

    @Override
    protected void validPicture(Object inputSource) {
        MultipartFile multipartFile = (MultipartFile) inputSource;
        ThrowUtils.throwIf(multipartFile == null || multipartFile.isEmpty(),
                ErrorCode.PARAMS_ERROR, "文件不能为空");
        ThrowUtils.throwIf(multipartFile.getSize() > MAX_FILE_SIZE,
                ErrorCode.PARAMS_ERROR, "文件大小不能超过 10MB");
        String originalFilename = multipartFile.getOriginalFilename();
        ThrowUtils.throwIf(StrUtil.isBlank(originalFilename), ErrorCode.PARAMS_ERROR, "文件名不能为空");
        String suffix = FileUtil.getSuffix(originalFilename).toLowerCase(Locale.ROOT);
        ThrowUtils.throwIf(!ALLOW_FORMAT_LIST.contains(suffix),
                ErrorCode.PARAMS_ERROR, "仅支持 JPG、JPEG 和 PNG 图片");
        String contentType = multipartFile.getContentType();
        ThrowUtils.throwIf(StrUtil.isNotBlank(contentType) && !contentType.startsWith("image/"),
                ErrorCode.PARAMS_ERROR, "文件内容类型错误");
    }

    @Override
    protected String getOriginFilename(Object inputSource) {
        return ((MultipartFile) inputSource).getOriginalFilename();
    }

    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        ((MultipartFile) inputSource).transferTo(file);
    }
}
