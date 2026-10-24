package com.zys.backend.manager;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileManagerTest {
    @Test
    void resolvesOnlyFilesInsideStorageRoot() throws Exception {
        Path root = Files.createTempDirectory("gallery-local-test");
        Path file = root.resolve("space/8/image.png");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        LocalFileManager manager = new LocalFileManager();
        ReflectionTestUtils.setField(manager, "storageRoot", root.toString());
        ReflectionTestUtils.setField(manager, "publicBaseUrl", "http://localhost:8090/api/files");
        assertEquals(file, manager.resolveLocalPathByRelative("space/8/image.png"));
        assertNull(manager.resolveLocalPathByRelative("../application.yml"));
        assertEquals("http://localhost:8090/api/files/space/8/image.png", manager.publicUrl("space/8/image.png"));
    }
}
