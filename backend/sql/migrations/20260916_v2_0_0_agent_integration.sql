-- V2.0 Agent 融合：图片版本、编辑会话、运行记录与事务 Outbox
-- 前置：V1.0 建表脚本 create_table.sql 已执行
USE `cloud_gallery`;

-- picture 表新增乐观锁版本号与当前版本指针
ALTER TABLE `picture`
    ADD COLUMN `editVersion` BIGINT NOT NULL DEFAULT 0 COMMENT '编辑乐观锁版本号' AFTER `picColor`,
    ADD COLUMN `currentVersionId` BIGINT NULL COMMENT '当前生效版本 id' AFTER `editVersion`;

-- 图片正式版本快照（不可变）
CREATE TABLE IF NOT EXISTS `picture_version`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `pictureId`       BIGINT       NOT NULL COMMENT '图片 id',
    `versionNo`       BIGINT       NOT NULL COMMENT '版本号，同一图片单调递增',
    `url`             VARCHAR(512) NOT NULL COMMENT '原图 URL',
    `thumbnailUrl`    VARCHAR(512) NULL COMMENT '缩略图 URL',
    `picSize`         BIGINT       NULL COMMENT '图片体积（字节）',
    `picWidth`        INT          NULL COMMENT '图片宽度',
    `picHeight`       INT          NULL COMMENT '图片高度',
    `picScale`        DOUBLE       NULL COMMENT '图片宽高比例',
    `picFormat`       VARCHAR(32)  NULL COMMENT '图片格式',
    `picColor`        VARCHAR(16)  NULL COMMENT '图片主色调',
    `metadataJson`    TEXT         NULL COMMENT '附加元数据 JSON',
    `source`          VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT '来源：UPLOAD/QUICK_EDIT/AGENT/RESTORE',
    `sourceSessionId` BIGINT       NULL COMMENT '来源编辑会话 id',
    `sourceRunId`     BIGINT       NULL COMMENT '来源编辑运行 id',
    `operatorId`      BIGINT       NOT NULL COMMENT '操作人 id',
    `createTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_picture_versionNo` (`pictureId`, `versionNo`)
) ENGINE = InnoDB COMMENT = '图片正式版本快照' COLLATE = utf8mb4_unicode_ci;

-- 图片编辑会话（云图库侧，映射修图 Agent 会话）
CREATE TABLE IF NOT EXISTS `picture_edit_session`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `agentSessionId`     VARCHAR(64)  NOT NULL COMMENT '修图 Agent 会话 ID（UUID）',
    `pictureId`          BIGINT       NOT NULL COMMENT '图片 id',
    `spaceId`            BIGINT       NULL COMMENT '空间 id；NULL 表示公共图库',
    `userId`             BIGINT       NOT NULL COMMENT '发起用户 id',
    `baseEditVersion`    BIGINT       NOT NULL DEFAULT 0 COMMENT '创建会话时的图片编辑版本号',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：DRAFT/ACTIVE/READY_TO_COMMIT/COMMITTING/COMMITTED/CANCELED/EXPIRED/CONFLICT',
    `finalAgentAssetId`  VARCHAR(64)  NULL COMMENT '用户选定的最终 Agent 素材 ID',
    `committedVersionId` BIGINT       NULL COMMENT '提交后的图片版本 id',
    `createTime`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `expireTime`         DATETIME     NULL COMMENT '会话过期时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agentSessionId` (`agentSessionId`),
    KEY `idx_picture_status` (`pictureId`, `status`),
    KEY `idx_userId` (`userId`),
    KEY `idx_expireTime` (`expireTime`)
) ENGINE = InnoDB COMMENT = '图片编辑会话' COLLATE = utf8mb4_unicode_ci;

-- 图片编辑运行记录（镜像 Agent Run 状态）
CREATE TABLE IF NOT EXISTS `picture_edit_run`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `editSessionId` BIGINT       NOT NULL COMMENT '编辑会话 id',
    `agentRunId`    VARCHAR(64)  NULL COMMENT 'Agent Run ID',
    `runType`       VARCHAR(32)  NOT NULL DEFAULT 'TOOL' COMMENT '运行类型：TOOL/PLAN/BATCH/EXPORT',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending/waiting/queued/running/succeeded/failed/canceled',
    `progress`      INT          NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    `stage`         VARCHAR(64)  NULL COMMENT '当前阶段说明',
    `planJson`      TEXT         NULL COMMENT '多步计划 JSON',
    `resultJson`    TEXT         NULL COMMENT '结果 JSON',
    `errorCode`     VARCHAR(32)  NULL COMMENT '错误码',
    `errorMessage`  VARCHAR(512) NULL COMMENT '错误信息',
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `completeTime`  DATETIME     NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    KEY `idx_editSessionId` (`editSessionId`),
    KEY `idx_agentRunId` (`agentRunId`)
) ENGINE = InnoDB COMMENT = '图片编辑运行记录' COLLATE = utf8mb4_unicode_ci;

-- 集成事务消息 Outbox（最终一致：缩略图、向量索引等外部副作用）
CREATE TABLE IF NOT EXISTS `integration_outbox`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `eventType`     VARCHAR(64)  NOT NULL COMMENT '事件类型：PICTURE_VERSION_COMMITTED/PICTURE_THUMBNAIL_REQUIRED/PICTURE_VECTOR_REINDEX_REQUIRED',
    `aggregateId`   BIGINT       NOT NULL COMMENT '聚合根 id（如 pictureId）',
    `payload`       TEXT         NOT NULL COMMENT '事件负载 JSON',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/PROCESSING/SUCCEEDED/FAILED',
    `retryCount`    INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `nextRetryTime` DATETIME     NULL COMMENT '下次重试时间',
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_nextRetryTime` (`status`, `nextRetryTime`),
    KEY `idx_aggregateId` (`aggregateId`)
) ENGINE = InnoDB COMMENT = '集成事务消息 Outbox' COLLATE = utf8mb4_unicode_ci;
