package com.zys.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 初始化本地图片仓库；文件读取由受控接口完成。
 */
@Configuration
public class LocalStorageConfig implements WebMvcConfigurer {

    @Value("${gallery.storage.root}")
    private String storageRoot;

    public Path storageRoot() {
        return getStorageRoot();
    }

    private Path getStorageRoot() {
        Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地图片仓库：" + root, e);
        }
        return root;
    }
}
