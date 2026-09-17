package com.zys.backend.agent.service;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.service.SystemSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Agent 统一存储服务：临时资产、mask、候选图、正式版本全部走腾讯云 COS。
 *
 * <p>模型侧不允许直接使用私有 COS 地址，喂给生成模型的图片一律通过
 * {@link #presignedUrl(String, long)} 生成短期可访问签名地址。</p>
 */
@Slf4j
@Service
public class AgentStorageService {

    private static final int THUMBNAIL_MAX_EDGE = 480;
    private static final String AGENT_PREFIX = "agent";
    private static final String VERSION_PREFIX = "picture-version";

    @Resource
    private SystemSettingsService settingsService;

    /**
     * 写入会话资产
     *
     * @param sessionId 会话 id
     * @param kind      资产类型目录，如 candidate/mask/delivery
     * @param format    图片格式
     * @param data      图片字节
     */
    public StoredObject storeSessionAsset(Long sessionId, String kind, String format, byte[] data) {
        String prefix = AGENT_PREFIX + "/session/" + sessionId + "/" + StrUtil.blankToDefault(kind, "tmp");
        return store(prefix, format, data, true);
    }

    /**
     * 写入正式版本对象
     */
    public StoredObject storeVersion(Long pictureId, String format, byte[] data) {
        String prefix = VERSION_PREFIX + "/" + pictureId;
        return store(prefix, format, data, true);
    }

    /**
     * 读取对象字节，入参可以是 storageKey 或完整 URL
     */
    public byte[] load(String storageKeyOrUrl) {
        if (StrUtil.isBlank(storageKeyOrUrl)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "存储地址为空");
        }
        Settings settings = readSettings();
        String key = extractKey(storageKeyOrUrl, settings.host);
        COSClient client = null;
        try {
            client = createClient(settings);
            COSObject object = client.getObject(new GetObjectRequest(settings.bucket, key));
            try (InputStream input = object.getObjectContent();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取 COS 对象失败，key={}", key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取图片失败，请重试");
        } finally {
            shutdownQuietly(client);
        }
    }

    /**
     * 生成短期可访问签名地址，默认 30 分钟
     */
    public String presignedUrl(String storageKeyOrUrl, long seconds) {
        Settings settings = readSettings();
        String key = extractKey(storageKeyOrUrl, settings.host);
        COSClient client = null;
        try {
            client = createClient(settings);
            Date expiration = new Date(System.currentTimeMillis() + seconds * 1000);
            return client.generatePresignedUrl(settings.bucket, key, expiration).toString();
        } catch (Exception e) {
            log.error("生成 COS 签名地址失败，key={}", key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成图片访问地址失败");
        } finally {
            shutdownQuietly(client);
        }
    }

    /**
     * 静默删除对象，失败仅记录日志（清理失败由 Outbox 人工核查）
     */
    public boolean deleteQuietly(String storageKeyOrUrl) {
        if (StrUtil.isBlank(storageKeyOrUrl)) {
            return true;
        }
        Settings settings;
        String key;
        try {
            settings = readSettings();
            key = extractKey(storageKeyOrUrl, settings.host);
        } catch (BusinessException e) {
            log.warn("跳过无法解析的对象地址：{}", storageKeyOrUrl);
            return false;
        }
        COSClient client = null;
        try {
            client = createClient(settings);
            client.deleteObject(settings.bucket, key);
            return true;
        } catch (Exception e) {
            log.warn("删除 COS 对象失败，key={}", key, e);
            return false;
        } finally {
            shutdownQuietly(client);
        }
    }

    /**
     * 判断 URL 是否属于当前 COS 存储桶
     */
    public boolean isExternalUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        try {
            Settings settings = readSettings();
            URI target = URI.create(url);
            URI host = URI.create(settings.host);
            return !StrUtil.equalsIgnoreCase(target.getAuthority(), host.getAuthority());
        } catch (Exception e) {
            return true;
        }
    }

    private StoredObject store(String prefix, String format, byte[] data, boolean withThumbnail) {
        Settings settings = readSettings();
        String safeFormat = normalizeFormat(format);
        String stem = LocalDate.now() + "_" + UUID.randomUUID().toString().replace("-", "");
        String key = prefix + "/" + stem + "." + safeFormat;
        String thumbnailKey = prefix + "/" + stem + "_thumbnail.jpg";
        COSClient client = null;
        File tempFile = null;
        File thumbnailFile = null;
        boolean uploaded = false;
        try {
            tempFile = File.createTempFile("gallery-agent-", "." + safeFormat);
            Files.write(tempFile.toPath(), data);
            Integer width = null;
            Integer height = null;
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
                if (withThumbnail) {
                    thumbnailFile = File.createTempFile("gallery-agent-thumb-", ".jpg");
                    writeThumbnail(image, thumbnailFile);
                }
            }
            client = createClient(settings);
            putObject(client, settings.bucket, key, tempFile, contentType(safeFormat));
            uploaded = true;
            StoredObject result = new StoredObject()
                    .setUrl(buildUrl(settings.host, key))
                    .setStorageKey(key)
                    .setSizeBytes(data.length)
                    .setWidth(width)
                    .setHeight(height)
                    .setFormat(safeFormat);
            if (thumbnailFile != null) {
                putObject(client, settings.bucket, thumbnailKey, thumbnailFile, "image/jpeg");
                result.setThumbnailUrl(buildUrl(settings.host, thumbnailKey));
            }
            return result;
        } catch (BusinessException e) {
            if (client != null && uploaded) {
                deleteQuietlyInternal(client, settings.bucket, key);
            }
            throw e;
        } catch (Exception e) {
            if (client != null && uploaded) {
                deleteQuietlyInternal(client, settings.bucket, key);
            }
            log.error("Agent 资产写入 COS 失败，key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片资产写入云存储失败，请检查 COS 配置");
        } finally {
            shutdownQuietly(client);
            deleteTempQuietly(tempFile);
            deleteTempQuietly(thumbnailFile);
        }
    }

    private void putObject(COSClient client, String bucket, String key, File file, String contentType) {
        PutObjectRequest request = new PutObjectRequest(bucket, key, file);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.length());
        metadata.setContentType(contentType);
        request.setMetadata(metadata);
        client.putObject(request);
    }

    private void writeThumbnail(BufferedImage image, File target) throws Exception {
        int maxEdge = Math.max(image.getWidth(), image.getHeight());
        double scale = maxEdge > THUMBNAIL_MAX_EDGE ? (double) THUMBNAIL_MAX_EDGE / maxEdge : 1.0;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        ImageIO.write(thumbnail, "jpg", target);
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
        return settings;
    }

    private COSClient createClient(Settings settings) {
        COSCredentials credentials = new BasicCOSCredentials(settings.secretId, settings.secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(settings.region));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        return new COSClient(credentials, clientConfig);
    }

    private String extractKey(String fileUrlOrKey, String configuredHost) {
        String value = fileUrlOrKey.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return value.replaceFirst("^/+", "");
        }
        URI url = URI.create(value);
        URI host = URI.create(configuredHost);
        if (!StrUtil.equalsIgnoreCase(url.getAuthority(), host.getAuthority())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片不属于当前 COS 存储桶");
        }
        String hostPath = host.getPath() == null ? "" : host.getPath();
        String path = url.getPath();
        if (!hostPath.isEmpty() && path.startsWith(hostPath)) {
            path = path.substring(hostPath.length());
        }
        String key = path.replaceFirst("^/+", "");
        if (key.isEmpty() || key.contains("..")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的对象 key");
        }
        return key;
    }

    private String buildUrl(String host, String key) {
        return trimTrailingSlash(host) + "/" + key;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String normalizeFormat(String format) {
        if (StrUtil.isBlank(format)) {
            return "png";
        }
        String lower = format.toLowerCase(Locale.ROOT).replace(".", "");
        if ("jpeg".equals(lower) || "jpg".equals(lower)) {
            return "jpg";
        }
        if ("png".equals(lower) || "webp".equals(lower) || "gif".equals(lower) || "bmp".equals(lower)) {
            return lower;
        }
        return "png";
    }

    private String contentType(String format) {
        switch (format) {
            case "jpg":
                return "image/jpeg";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            default:
                return "image/png";
        }
    }

    private void deleteQuietlyInternal(COSClient client, String bucket, String key) {
        try {
            client.deleteObject(bucket, key);
        } catch (Exception e) {
            log.warn("回滚 COS 对象失败，key={}", key);
        }
    }

    private void shutdownQuietly(COSClient client) {
        if (client != null) {
            client.shutdown();
        }
    }

    private void deleteTempQuietly(Path path) {
        if (path == null) {
            return;
        }
        deleteTempQuietly(path.toFile());
    }

    private void deleteTempQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("临时文件删除失败：{}", file);
        }
    }

    private static class Settings {
        private String host;
        private String secretId;
        private String secretKey;
        private String region;
        private String bucket;
    }
}
