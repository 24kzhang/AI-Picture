package com.zys.backend.controller;

import com.zys.backend.annotation.AuthCheck;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.constant.UserConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * COS 仓库诊断接口，仅管理员可使用。
 */
@Slf4j
@RestController
@RequestMapping("/file")
public class FileController {

    @Resource
    private CosStorageManager cosStorageManager;

    @PostMapping("/test/upload")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<String> testUploadFile(@RequestPart("file") MultipartFile multipartFile) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("gallery-test-", ".tmp");
            multipartFile.transferTo(tempFile);
            UploadPictureResult result = cosStorageManager.storePicture(
                    tempFile,
                    multipartFile.getOriginalFilename(),
                    "test"
            );
            return ResultUtils.success(result.getUrl());
        } catch (Exception e) {
            log.error("COS 测试上传失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传文件失败");
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("临时测试文件删除失败：{}", tempFile);
            }
        }
    }

    @GetMapping("/test/download")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public void testDownloadFile(String fileUrl, HttpServletResponse response) {
        try (CosStorageManager.ManagedImageFile managedFile =
                     cosStorageManager.materialize(fileUrl)) {
            Path file = managedFile.getPath();
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + file.getFileName() + "\"");
            Files.copy(file, response.getOutputStream());
            response.flushBuffer();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载文件失败");
        }
    }
}
