package com.zys.backend.service;

import cn.hutool.core.util.StrUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 管理可在本机控制台中维护的连接参数与运行时密钥。
 *
 * <p>配置保存到 Git 忽略的运行目录。COS 参数会立即用于新请求；数据库、Redis、
 * 向量服务和百炼参数由启动脚本在下次启动时加载。</p>
 */
@Slf4j
@Service
public class SystemSettingsService {

    private static final String CLEAR_VALUE = "__CLEAR__";

    private static final Map<String, String> PROPERTY_NAMES;

    private static final List<String> SECRET_KEYS = Arrays.asList(
            "database.password",
            "redis.password",
            "cos.secretId",
            "cos.secretKey",
            "aliyun.apiKey"
    );

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("database.url", "spring.datasource.url");
        names.put("database.username", "spring.datasource.username");
        names.put("database.password", "spring.datasource.password");
        names.put("redis.host", "spring.redis.host");
        names.put("redis.port", "spring.redis.port");
        names.put("redis.database", "spring.redis.database");
        names.put("redis.password", "spring.redis.password");
        names.put("vector.serviceUrl", "gallery.vector.service-url");
        names.put("cos.host", "cos.client.host");
        names.put("cos.secretId", "cos.client.secretId");
        names.put("cos.secretKey", "cos.client.secretKey");
        names.put("cos.region", "cos.client.region");
        names.put("cos.bucket", "cos.client.bucket");
        names.put("aliyun.apiKey", "aliYunAi.apiKey");
        PROPERTY_NAMES = Collections.unmodifiableMap(names);
    }

    @Value("${gallery.settings.path}")
    private String settingsPath;

    @Resource
    private Environment environment;

    private final Object lock = new Object();

    private Properties overrides = new Properties();

    @PostConstruct
    public void load() {
        synchronized (lock) {
            Properties loaded = new Properties();
            Path path = getPath();
            if (Files.isRegularFile(path)) {
                try (java.io.Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    loaded.load(reader);
                } catch (IOException e) {
                    throw new IllegalStateException("无法读取系统密钥配置：" + path, e);
                }
            }
            overrides = loaded;
        }
    }

    public String getValue(String key) {
        String propertyName = PROPERTY_NAMES.get(key);
        if (propertyName == null) {
            throw new IllegalArgumentException("不支持的配置项：" + key);
        }
        synchronized (lock) {
            String override = overrides.getProperty(key);
            if (override != null) {
                return override.trim();
            }
        }
        return StrUtil.trim(environment.getProperty(propertyName, ""));
    }

    public Map<String, Object> getPublicSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settingsFile", getPath().toString());
        result.put("databaseUrl", getValue("database.url"));
        result.put("databaseUsername", getValue("database.username"));
        result.put("databasePasswordConfigured",
                StrUtil.isNotBlank(getValue("database.password")));
        result.put("redisHost", getValue("redis.host"));
        result.put("redisPort", getValue("redis.port"));
        result.put("redisDatabase", getValue("redis.database"));
        result.put("redisPasswordConfigured", StrUtil.isNotBlank(getValue("redis.password")));
        result.put("vectorServiceUrl", getValue("vector.serviceUrl"));
        result.put("cosHost", getValue("cos.host"));
        result.put("cosRegion", getValue("cos.region"));
        result.put("cosBucket", getValue("cos.bucket"));
        result.put("cosSecretIdConfigured", StrUtil.isNotBlank(getValue("cos.secretId")));
        result.put("cosSecretKeyConfigured", StrUtil.isNotBlank(getValue("cos.secretKey")));
        result.put("aliyunApiKeyConfigured", StrUtil.isNotBlank(getValue("aliyun.apiKey")));
        return result;
    }

    /**
     * 空白密钥表示保留原值；传入 __CLEAR__ 才会清除覆盖值。
     */
    public Map<String, Object> update(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有可保存的配置");
        }
        synchronized (lock) {
            Properties next = new Properties();
            next.putAll(overrides);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (!PROPERTY_NAMES.containsKey(key)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的配置项：" + key);
                }
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                validateSingleLine(value);
                validateValue(key, value);
                if (CLEAR_VALUE.equals(value)) {
                    next.setProperty(key, "");
                } else if (SECRET_KEYS.contains(key) && value.isEmpty()) {
                    // 页面中的空白密钥输入框表示不修改。
                    continue;
                } else {
                    next.setProperty(key, value);
                }
            }
            write(next);
            overrides = next;
        }
        Map<String, Object> result = getPublicSettings();
        result.put("restartRequired", true);
        return result;
    }

    private void write(Properties values) {
        Path path = getPath();
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("# AI 协同云图库本机运行配置，请勿提交到版本库。");
            for (String key : PROPERTY_NAMES.keySet()) {
                String value = values.getProperty(key);
                if (value != null) {
                    lines.add(key + "=" + escape(value));
                }
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存系统密钥配置失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存密钥配置失败");
        }
    }

    private Path getPath() {
        return Paths.get(settingsPath).toAbsolutePath().normalize();
    }

    private void validateSingleLine(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "配置值不能包含换行");
        }
    }

    private void validateValue(String key, String value) {
        if (value.isEmpty() || CLEAR_VALUE.equals(value)) {
            return;
        }
        if ("redis.port".equals(key)) {
            validateIntegerRange(value, 1, 65535, "Redis 端口");
        } else if ("redis.database".equals(key)) {
            validateIntegerRange(value, 0, 1024, "Redis 数据库编号");
        } else if ("database.url".equals(key) && !value.startsWith("jdbc:")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据库地址必须以 jdbc: 开头");
        } else if (("cos.host".equals(key) || "vector.serviceUrl".equals(key))
                && !(value.startsWith("http://") || value.startsWith("https://"))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, key + " 必须是 HTTP(S) 地址");
        }
    }

    private void validateIntegerRange(String value, int minimum, int maximum, String label) {
        try {
            int number = Integer.parseInt(value);
            if (number < minimum || number > maximum) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    label + "必须是 " + minimum + " 到 " + maximum + " 之间的整数");
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("=", "\\=")
                .replace(":", "\\:");
    }
}
