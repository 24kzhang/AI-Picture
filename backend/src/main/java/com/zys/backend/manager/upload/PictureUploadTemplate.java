package com.zys.backend.manager.upload;

import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.io.File;

/**
 * 图片上传模板：统一下载到受控临时文件，再写入腾讯云 COS。
 */
@Slf4j
public abstract class PictureUploadTemplate {

    @Resource
    private CosStorageManager cosStorageManager;

    public UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        validPicture(inputSource);
        File tempFile = null;
        try {
            tempFile = File.createTempFile("gallery-upload-", ".tmp");
            processFile(inputSource, tempFile);
            return cosStorageManager.storePicture(
                    tempFile,
                    getOriginFilename(inputSource),
                    uploadPathPrefix
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("腾讯云 COS 图片上传失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            deleteTempFile(tempFile);
        }
    }

    protected abstract void validPicture(Object inputSource);

    protected abstract String getOriginFilename(Object inputSource);

    protected abstract void processFile(Object inputSource, File file) throws Exception;

    private void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("临时图片删除失败：{}", file.getAbsolutePath());
        }
    }
}
