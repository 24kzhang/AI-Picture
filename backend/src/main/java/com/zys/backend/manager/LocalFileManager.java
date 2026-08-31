package com.zys.backend.manager;

import cn.hutool.core.io.FileUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地图片仓库。所有路径都经过归一化和越界校验。
 */
@Slf4j
@Service
public class LocalFileManager {

    private static final int THUMBNAIL_MAX_EDGE = 480;

    @Value("${gallery.storage.root}")
    private String storageRoot;

    @Value("${gallery.storage.public-base-url}")
    private String publicBaseUrl;

    public UploadPictureResult storePicture(File sourceFile, String originalFilename, String pathPrefix) {
        Path root = getStorageRoot();
        String safePrefix = normalizePrefix(pathPrefix);
        BufferedImage image = readImage(sourceFile);
        String format = detectFormat(sourceFile);
        String fileStem = LocalDate.now() + "_" + UUID.randomUUID().toString().replace("-", "");
        Path directory = root.resolve(safePrefix).normalize();
        ensureInsideRoot(directory, root);
        Path originalPath = directory.resolve(fileStem + "." + format);
        Path thumbnailPath = directory.resolve(fileStem + "_thumbnail.jpg");

        try {
            Files.createDirectories(directory);
            Files.copy(sourceFile.toPath(), originalPath, StandardCopyOption.REPLACE_EXISTING);
            writeThumbnail(image, thumbnailPath);

            int width = image.getWidth();
            int height = image.getHeight();
            UploadPictureResult result = new UploadPictureResult();
            result.setUrl(toPublicUrl(root.relativize(originalPath)));
            result.setThumbnailUrl(toPublicUrl(root.relativize(thumbnailPath)));
            result.setPicName(FileUtil.mainName(originalFilename));
            result.setPicSize(Files.size(originalPath));
            result.setPicWidth(width);
            result.setPicHeight(height);
            result.setPicScale(Math.round(width * 100.0 / height) / 100.0);
            result.setPicFormat(format);
            result.setPicColor(averageColor(image));
            return result;
        } catch (IOException e) {
            deleteQuietly(originalPath);
            deleteQuietly(thumbnailPath);
            log.error("写入本地图片仓库失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存到本地仓库失败");
        }
    }

    public Path resolveLocalPath(String fileUrl) {
        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            return null;
        }
        try {
            String urlPath = URI.create(fileUrl).getPath();
            String marker = "/files/";
            int markerIndex = urlPath.indexOf(marker);
            if (markerIndex < 0) {
                return null;
            }
            String relativeValue = urlPath.substring(markerIndex + marker.length());
            Path root = getStorageRoot();
            Path resolved = root.resolve(relativeValue.replace('/', File.separatorChar)).normalize();
            ensureInsideRoot(resolved, root);
            return Files.isRegularFile(resolved) ? resolved : null;
        } catch (Exception e) {
            log.warn("无法解析本地图片地址：{}", fileUrl);
            return null;
        }
    }

    public void deleteByUrl(String fileUrl) {
        Path path = resolveLocalPath(fileUrl);
        if (path != null) {
            deleteQuietly(path);
        }
    }

    private Path getStorageRoot() {
        Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法创建本地图片仓库");
        }
        return root;
    }

    private String normalizePrefix(String prefix) {
        String value = prefix == null ? "public/unknown" : prefix.replace('\\', '/');
        if (!value.matches("[a-zA-Z0-9/_-]+") || value.contains("..") || value.startsWith("/")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片仓库路径不合法");
        }
        return value;
    }

    private void ensureInsideRoot(Path path, Path root) {
        if (!path.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片路径越界");
        }
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

    private String toPublicUrl(Path relativePath) {
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/" + relativePath.toString().replace(File.separatorChar, '/');
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除本地文件失败：{}", path);
        }
    }
}
