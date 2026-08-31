package com.zys.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zys.backend.manager.vector.VectorClient;
import com.zys.backend.manager.vector.model.VectorDuplicateResponse;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 根据本机 Chroma 向量清理重复图片。
 */
@Slf4j
@Service
public class PictureDedupService {

    private static final double DUPLICATE_THRESHOLD = 0.999999D;
    private static final int PREVIEW_GROUP_LIMIT = 20;

    @Resource
    private VectorClient vectorClient;

    @Resource
    private PictureService pictureService;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${gallery.dedup.audit-dir:${user.dir}/../runtime-logs}")
    private String auditDirectory;

    public Map<String, Object> preview() {
        return summarize(buildPlan(), null, 0, Collections.emptyList());
    }

    public synchronized Map<String, Object> execute() {
        DedupPlan plan = buildPlan();
        Path auditPath = prepareAuditPath();
        Map<String, Object> audit = fullAudit(plan);
        audit.put("status", "planned");
        writeAudit(auditPath, audit);

        User localOperator = new User();
        localOperator.setId(0L);
        int deletedCount = 0;
        List<Long> failedIds = new ArrayList<>();
        for (DedupGroup group : plan.groups) {
            for (Picture picture : group.removed) {
                try {
                    pictureService.deletePicture(picture.getId(), localOperator);
                    deletedCount++;
                } catch (RuntimeException e) {
                    failedIds.add(picture.getId());
                    log.error("重复图片删除失败，pictureId={}", picture.getId(), e);
                }
            }
        }

        audit.put("status", failedIds.isEmpty() ? "completed" : "partial");
        audit.put("deletedCount", deletedCount);
        audit.put("failedIds", failedIds);
        writeAudit(auditPath, audit);
        return summarize(plan, auditPath, deletedCount, failedIds);
    }

    private DedupPlan buildPlan() {
        VectorDuplicateResponse response = vectorClient.findDuplicates(DUPLICATE_THRESHOLD);
        List<List<Long>> vectorGroups = response == null || response.getGroups() == null
                ? Collections.emptyList()
                : response.getGroups();
        List<Long> allIds = vectorGroups.stream()
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Picture> pictures = allIds.isEmpty()
                ? Collections.emptyMap()
                : pictureService.listByIds(allIds).stream()
                        .collect(Collectors.toMap(Picture::getId, picture -> picture));
        List<DedupGroup> groups = new ArrayList<>();
        Comparator<Picture> oldestFirst = Comparator
                .comparing(Picture::getCreateTime, Comparator.nullsLast(Date::compareTo))
                .thenComparing(Picture::getId);

        for (List<Long> vectorGroup : vectorGroups) {
            Map<Long, List<Picture>> bySpace = new HashMap<>();
            for (Long pictureId : vectorGroup) {
                Picture picture = pictures.get(pictureId);
                if (picture != null) {
                    bySpace.computeIfAbsent(picture.getSpaceId(), key -> new ArrayList<>())
                            .add(picture);
                }
            }
            for (List<Picture> sameSpace : bySpace.values()) {
                if (sameSpace.size() < 2) {
                    continue;
                }
                sameSpace.sort(oldestFirst);
                groups.add(new DedupGroup(
                        sameSpace.get(0),
                        new ArrayList<>(sameSpace.subList(1, sameSpace.size()))
                ));
            }
        }
        groups.sort(Comparator.comparing(group -> group.kept.getId()));
        return new DedupPlan(
                response == null || response.getVectorCount() == null
                        ? 0 : response.getVectorCount(),
                vectorGroups.size(),
                groups
        );
    }

    private Map<String, Object> summarize(DedupPlan plan,
                                          Path auditPath,
                                          int deletedCount,
                                          List<Long> failedIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threshold", DUPLICATE_THRESHOLD);
        result.put("vectorCount", plan.vectorCount);
        result.put("vectorGroupCount", plan.vectorGroupCount);
        result.put("duplicateGroupCount", plan.groups.size());
        result.put("duplicatePictureCount", plan.groups.stream()
                .mapToInt(group -> group.removed.size()).sum());
        result.put("retainedPictureCount", plan.groups.size());
        result.put("deletedCount", deletedCount);
        result.put("failedCount", failedIds.size());
        result.put("failedIds", failedIds.stream().limit(100).collect(Collectors.toList()));
        result.put("auditFile", auditPath == null ? null : auditPath.toString());
        result.put("sampleGroups", plan.groups.stream()
                .limit(PREVIEW_GROUP_LIMIT)
                .map(this::groupSummary)
                .collect(Collectors.toList()));
        return result;
    }

    private Map<String, Object> fullAudit(DedupPlan plan) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("createdAt", LocalDateTime.now().toString());
        audit.put("threshold", DUPLICATE_THRESHOLD);
        audit.put("vectorCount", plan.vectorCount);
        audit.put("vectorGroupCount", plan.vectorGroupCount);
        audit.put("groups", plan.groups.stream()
                .map(this::groupSummary)
                .collect(Collectors.toList()));
        return audit;
    }

    private Map<String, Object> groupSummary(DedupGroup group) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("keepId", group.kept.getId());
        item.put("keepName", group.kept.getName());
        item.put("spaceId", group.kept.getSpaceId());
        item.put("removeIds", group.removed.stream()
                .map(Picture::getId)
                .collect(Collectors.toList()));
        return item;
    }

    private Path prepareAuditPath() {
        String timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Paths.get(auditDirectory).toAbsolutePath().normalize()
                .resolve("picture-dedup-" + timestamp + ".json");
    }

    private void writeAudit(Path path, Map<String, Object> audit) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), audit);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入重复图片审计文件：" + path, e);
        }
    }

    private static class DedupPlan {
        private final int vectorCount;
        private final int vectorGroupCount;
        private final List<DedupGroup> groups;

        private DedupPlan(int vectorCount, int vectorGroupCount, List<DedupGroup> groups) {
            this.vectorCount = vectorCount;
            this.vectorGroupCount = vectorGroupCount;
            this.groups = groups;
        }
    }

    private static class DedupGroup {
        private final Picture kept;
        private final List<Picture> removed;

        private DedupGroup(Picture kept, List<Picture> removed) {
            this.kept = kept;
            this.removed = removed;
        }
    }
}
