package com.zys.backend.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.dto.file.UploadPictureResult;
import com.zys.backend.service.SystemSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * 腾讯云 COS 图片仓库。
 *
 * <p>新图片只写入 COS；改造前的本地 URL 仍可被读取和删除，便于平滑迁移。
 * 向量服务需要文件时会将 COS 原图下载到临时目录，并在调用结束后清理。</p>
 */
@Slf4j
@Service
public class CosStorageManager {

    private static final int THUMBNAIL_MAX_EDGE = 480;

    @Resource
    private SystemSettingsService settingsService;

    @Resource
    private LocalFileManager localFileManager;

    public UploadPictureResult storePicture(File sourceFile,
                                            String originalFilename,
                                            String pathPrefix) {
        Settings settings = readSettings();
        BufferedImage image = readImage(sourceFile);
        String format = detectFormat(sourceFile);
        String safePrefix = normalizePrefix(pathPrefix);
        String stem = LocalDate.now() + "_" + UUID.randomUUID().toString().replace("-", "");
        String originalKey = safePrefix + "/" + stem + "." + format;
        String thumbnailKey = safePrefix + "/" + stem + "_thumbnail.jpg";
        File thumbnailFile = null;
        COSClient client = null;
        boolean originalUploaded = false;
        boolean thumbnailUploaded = false;
        try {
            thumbnailFile = File.createTempFile("gallery-thumbnail-", ".jpg");
            writeThumbnail(image, thumbnailFile.toPath());
            client = createClient(settings);
            putObject(client, settings.bucket, originalKey, sourceFile, contentType(format));
            originalUploaded = true;
            putObject(client, settings.bucket, thumbnailKey, thumbnailFile, "image/jpeg");
            thumbnailUploaded = true;

            UploadPictureResult result = new UploadPictureResult();
            result.setUrl(buildUrl(settings.host, originalKey));
            result.setThumbnailUrl(buildUrl(settings.host, thumbnailKey));
            result.setPicName(FileUtil.mainName(originalFilename));
            result.setPicSize(sourceFile.length());
            result.setPicWidth(image.getWidth());
            result.setPicHeight(image.getHeight());
            result.setPicScale(Math.round(image.getWidth() * 100.0 / image.getHeight()) / 100.0);
            result.setPicFormat(format);
            result.setPicColor(averageColor(image));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            if (client != null) {
                if (thumbnailUploaded) {
                    deleteQuietly(client, settings.bucket, thumbnailKey);
                }
                if (originalUploaded) {
                    deleteQuietly(client, settings.bucket, originalKey);
                }
            }
            log.error("图片上传到腾讯云 COS 失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "图片上传到腾讯云 COS 失败，请检查 COS 配置和存储桶权限");
        } finally {
            if (client != null) {
                client.shutdown();
            }
            if (thumbnailFile != null && thumbnailFile.exists() && !thumbnailFile.delete()) {
                log.warn("临时缩略图删除失败：{}", thumbnailFile);
            }
        }
    }

    public ManagedImageFile materialize(String fileUrl) {
        Path legacyPath = localFileManager.resolveLocalPath(fileUrl);
        if (legacyPath != null) {
            return new ManagedImageFile(legacyPath, false);
        }
        Settings settings = readSettings();
        String key = extractKey(fileUrl, settings.host);
        Path temporary = null;
        COSClient client = null;
        try {
            String suffix = extensionFromKey(key);
            temporary = Files.createTempFile("gallery-cos-", suffix);
            client = createClient(settings);
            COSObject object = client.getObject(new GetObjectRequest(settings.bucket, key));
            try (InputStream input = object.getObjectContent()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            return new ManagedImageFile(temporary, true);
        } catch (Exception e) {
            deleteLocalQuietly(temporary);
            log.error("从腾讯云 COS 获取向量原图失败，url={}", fileUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "无法从腾讯云 COS 获取原图");
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    public void deleteByUrl(String fileUrl) {
        if (StrUtil.isBlank(fileUrl)) {
            return;
        }
        Path legacyPath = localFileManager.resolveLocalPath(fileUrl);
        if (legacyPath != null) {
            localFileManager.deleteByUrl(fileUrl);
            return;
        }
        Settings settings = readSettings();
        String key;
        try {
            key = extractKey(fileUrl, settings.host);
        } catch (BusinessException e) {
            log.warn("跳过不属于当前 COS 的图片地址：{}", fileUrl);
            return;
        }
        COSClient client = null;
        try {
            client = createClient(settings);
            client.deleteObject(settings.bucket, key);
        } catch (Exception e) {
            log.warn("删除腾讯云 COS 对象失败，key={}", key, e);
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    public boolean testConnection() {
        Settings settings;
        try {
            settings = readSettings();
        } catch (BusinessException e) {
            return false;
        }
        COSClient client = null;
        try {
            client = createClient(settings);
            return client.doesBucketExist(settings.bucket);
        } catch (Exception e) {
            log.warn("腾讯云 COS 连通性检查失败：{}", e.getMessage());
            return false;
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 生成预签名下载 URL（GET）
     */
    public String presignedGetUrl(String key, int ttlSeconds) {
        Settings settings = readSettings();
        COSClient client = null;
        try {
            client = createClient(settings);
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(settings.bucket, key, HttpMethodName.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + ttlSeconds * 1000L));
            return client.generatePresignedUrl(request).toString();
        } catch (Exception e) {
            log.error("生成 COS 预签名下载 URL 失败，key={}", key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成下载链接失败");
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 生成预签名上传 URL（PUT）
     */
    public String presignedPutUrl(String key, String contentType, int ttlSeconds) {
        Settings settings = readSettings();
        COSClient client = null;
        try {
            client = createClient(settings);
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(settings.bucket, key, HttpMethodName.PUT);
            request.setExpiration(new Date(System.currentTimeMillis() + ttlSeconds * 1000L));
            if (StrUtil.isNotBlank(contentType)) {
                request.putCustomRequestHeader(HttpHeaders.CONTENT_TYPE, contentType);
            }
            return client.generatePresignedUrl(request).toString();
        } catch (Exception e) {
            log.error("生成 COS 预签名上传 URL 失败，key={}", key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成上传链接失败");
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 校验对象键前缀并返回规范化后的键；不合法直接抛业务异常
     */
    public String validateKeyPrefix(String key, String allowedPrefix) {
        if (StrUtil.isBlank(key) || !key.startsWith(allowedPrefix) || key.contains("..")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 对象键前缀不合法");
        }
        return key;
    }

    /**
     * 判断对象是否存在
     */
    public boolean doesObjectExist(String key) {
        Settings settings = readSettings();
        COSClient client = null;
        try {
            client = createClient(settings);
            return client.doesObjectExist(settings.bucket, key);
        } catch (Exception e) {
            log.warn("查询 COS 对象存在性失败，key={}", key, e);
            return false;
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 获取对象元数据（大小、类型）；对象不存在返回 null
     */
    public ObjectMetadata statObject(String key) {
        Settings settings = readSettings();
        COSClient client = null;
        try {
            client = createClient(settings);
            return client.getObjectMetadata(settings.bucket, key);
        } catch (Exception e) {
            return null;
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    /**
     * 删除对象（尽力而为，失败仅告警）
     */
    public void deleteKeyQuietly(String key) {
        Settings settings;
        try {
            settings = readSettings();
        } catch (BusinessException e) {
            return;
        }
        COSClient client = null;
        try {
            client = createClient(settings);
            client.deleteObject(settings.bucket, key);
        } catch (Exception e) {
            log.warn("删除 COS 对象失败，key={}", key, e);
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    private Settings readSettings() {
        Settings settings = new Settings();
        settings.host = trimTrailingSlash(settingsService.getValue("cos.host"));
        settings.secretId = settingsService.getValue("cos.secretId");
        settings.secretKey = settingsService.getValue("cos.secretKey");
        settings.region = settingsService.getValue("cos.region");
        settings.bucket = settingsService.getValue("cos.bucket");
        if (StrUtil.hasBlank(settings.host, settings.secretId, settings.secretKey,
                settings.region, settings.bucket)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "腾讯云 COS 尚未配置，请先进入“系统管理”填写 COS 参数");
        }
        URI hostUri;
        try {
            hostUri = URI.create(settings.host);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 访问域名格式错误");
        }
        if (!"https".equalsIgnoreCase(hostUri.getScheme()) || StrUtil.isBlank(hostUri.getHost())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 访问域名必须是 HTTPS 地址");
        }
        return settings;
    }

    private COSClient createClient(Settings settings) {
        COSCredentials credentials = new BasicCOSCredentials(settings.secretId, settings.secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(settings.region));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        return new COSClient(credentials, clientConfig);
    }

    private void putObject(COSClient client,
                           String bucket,
                           String key,
                           File file,
                           String contentType) {
        PutObjectRequest request = new PutObjectRequest(bucket, key, file);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.length());
        metadata.setContentType(contentType);
        request.setMetadata(metadata);
        client.putObject(request);
    }

    private String extractKey(String fileUrl, String configuredHost) {
        if (StrUtil.isBlank(fileUrl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址为空");
        }
        try {
            URI url = URI.create(fileUrl);
            URI host = URI.create(configuredHost);
            if (!StrUtil.equalsIgnoreCase(url.getScheme(), host.getScheme())
                    || !StrUtil.equalsIgnoreCase(url.getAuthority(), host.getAuthority())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片不属于当前 COS 存储桶");
            }
            String hostPath = host.getPath() == null ? "" : host.getPath();
            String path = url.getPath();
            if (!hostPath.isEmpty() && path.startsWith(hostPath)) {
                path = path.substring(hostPath.length());
            }
            String key = path.replaceFirst("^/+", "");
            if (key.isEmpty() || key.contains("..")) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 对象键不合法");
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片地址格式错误");
        }
    }

    private String normalizePrefix(String prefix) {
        String value = prefix == null ? "public/unknown" : prefix.replace('\\', '/');
        value = value.replaceFirst("^/+", "");
        if (!value.matches("[a-zA-Z0-9/_-]+") || value.contains("..")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "COS 图片路径不合法");
        }
        return value;
    }

    private String buildUrl(String host, String key) {
        return trimTrailingSlash(host) + "/" + key;
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private String extensionFromKey(String key) {
        String suffix = FileUtil.getSuffix(key);
        if (StrUtil.isBlank(suffix) || !suffix.matches("[a-zA-Z0-9]{1,8}")) {
            return ".img";
        }
        return "." + suffix;
    }

    private String contentType(String format) {
        if ("png".equals(format)) {
            return "image/png";
        }
        return "image/jpeg";
    }

    private BufferedImage readImage(File file) {
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不是可识别的图片");
            }
            return image;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片内容解析失败");
        }
    }

    private String detectFormat(File file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的图片格式");
            }
            String format = readers.next().getFormatName().toLowerCase(Locale.ROOT);
            if ("jpeg".equals(format)) {
                return "jpg";
            }
            if (!"jpg".equals(format) && !"png".equals(format)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持 JPG、JPEG 和 PNG 图片");
            }
            return format;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片格式识别失败");
        }
    }

    private void writeThumbnail(BufferedImage source, Path target) throws IOException {
        double scale = Math.min(1.0,
                THUMBNAIL_MAX_EDGE * 1.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(thumbnail, "jpg", target.toFile())) {
            throw new IOException("当前 Java 运行时无法生成 JPEG 缩略图");
        }
    }

    private String averageColor(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        int step = Math.max(1, Math.max(image.getWidth(), image.getHeight()) / 128);
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xff;
                green += (rgb >> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
        }
        return String.format("#%02X%02X%02X", red / count, green / count, blue / count);
    }

    private void deleteQuietly(COSClient client, String bucket, String key) {
        try {
            client.deleteObject(bucket, key);
        } catch (Exception e) {
            log.warn("回滚 COS 对象失败，key={}", key);
        }
    }

    private void deleteLocalQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时 COS 文件删除失败：{}", path);
        }
    }

    private static class Settings {
        private String host;
        private String secretId;
        private String secretKey;
        private String region;
        private String bucket;
    }

    public static class ManagedImageFile implements AutoCloseable {
        private final Path path;
        private final boolean temporary;

        private ManagedImageFile(Path path, boolean temporary) {
            this.path = path;
            this.temporary = temporary;
        }

        public Path getPath() {
            return path;
        }

        @Override
        public void close() {
            if (!temporary) {
                return;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Windows 杀毒软件短暂占用时由系统临时目录后续清理。
            }
        }
    }
}
