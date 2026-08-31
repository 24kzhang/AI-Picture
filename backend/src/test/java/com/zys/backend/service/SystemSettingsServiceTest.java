package com.zys.backend.service;

import com.zys.backend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemSettingsServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldPersistConnectionSettingsWithoutEchoingSecrets() throws Exception {
        Path settingsFile = temporaryDirectory.resolve("system-settings.properties");
        SystemSettingsService service = createService(settingsFile);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("database.url", "jdbc:mysql://127.0.0.1:3306/cloud_gallery");
        values.put("database.username", "root");
        values.put("database.password", "local-test-password");
        values.put("redis.host", "127.0.0.1");
        values.put("redis.port", "6379");
        values.put("redis.database", "1");
        values.put("vector.serviceUrl", "http://127.0.0.1:18001");

        Map<String, Object> result = service.update(values);
        String fileContent = new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8);

        assertTrue(fileContent.contains("database.url=jdbc\\:mysql\\://127.0.0.1\\:3306/cloud_gallery"));
        assertTrue(fileContent.contains("database.password=local-test-password"));
        assertEquals(true, result.get("databasePasswordConfigured"));
        assertEquals(true, result.get("restartRequired"));
        assertFalse(result.containsKey("databasePassword"));
    }

    @Test
    void shouldRejectInvalidRedisPort() {
        SystemSettingsService service = createService(temporaryDirectory.resolve("invalid.properties"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("redis.port", "70000");

        assertThrows(BusinessException.class, () -> service.update(values));
    }

    @Test
    void shouldClearSecretWhenClearMarkerIsSubmitted() throws Exception {
        Path settingsFile = temporaryDirectory.resolve("clear.properties");
        SystemSettingsService service = createService(settingsFile);
        Map<String, String> initial = new LinkedHashMap<>();
        initial.put("redis.password", "temporary-password");
        service.update(initial);

        Map<String, String> clear = new LinkedHashMap<>();
        clear.put("redis.password", "__CLEAR__");
        Map<String, Object> result = service.update(clear);

        String fileContent = new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8);
        assertTrue(fileContent.contains("redis.password="));
        assertEquals(false, result.get("redisPasswordConfigured"));
    }

    private SystemSettingsService createService(Path settingsFile) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/cloud_gallery")
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.datasource.password", "")
                .withProperty("spring.redis.host", "127.0.0.1")
                .withProperty("spring.redis.port", "6379")
                .withProperty("spring.redis.database", "1")
                .withProperty("spring.redis.password", "")
                .withProperty("gallery.vector.service-url", "http://127.0.0.1:18001")
                .withProperty("cos.client.host", "")
                .withProperty("cos.client.secretId", "")
                .withProperty("cos.client.secretKey", "")
                .withProperty("cos.client.region", "ap-shanghai")
                .withProperty("cos.client.bucket", "")
                .withProperty("aliYunAi.apiKey", "");
        SystemSettingsService service = new SystemSettingsService();
        ReflectionTestUtils.setField(service, "settingsPath", settingsFile.toString());
        ReflectionTestUtils.setField(service, "environment", environment);
        service.load();
        return service;
    }
}
