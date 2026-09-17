-- AI 协同云图库：Java 原生修图 Agent 相关表结构
-- 适用于已有 cloud_gallery 库的增量升级；全新建库请先执行 create_table.sql。
USE `cloud_gallery`;

-- picture 增加乐观锁编辑版本与当前正式版本指针（存量库执行一次即可，重复执行会因列已存在报错）
ALTER TABLE `picture`
    ADD COLUMN `editVersion` BIGINT NOT NULL DEFAULT 0 COMMENT '编辑乐观锁版本号，提交正式版本时递增',
    ADD COLUMN `currentVersionId` BIGINT NULL COMMENT '当前正式版本 id（picture_version.id）';

CREATE TABLE IF NOT EXISTS `picture_version`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `pictureId`        BIGINT       NOT NULL COMMENT '图片 id',
    `versionNo`        INT          NOT NULL COMMENT '版本号，从 1 递增',
    `url`              VARCHAR(512) NOT NULL COMMENT '该版本图片 URL',
    `thumbnailUrl`     VARCHAR(512) NULL COMMENT '该版本缩略图 URL',
    `picSize`          BIGINT       NULL COMMENT '图片体积（字节）',
    `picWidth`         INT          NULL COMMENT '图片宽度',
    `picHeight`        INT          NULL COMMENT '图片高度',
    `picFormat`        VARCHAR(32)  NULL COMMENT '图片格式',
    `picColor`         VARCHAR(16)  NULL COMMENT '图片主色调',
    `source`           VARCHAR(32)  NOT NULL COMMENT '版本来源：upload/quick_edit/agent/restore',
    `sourceSessionId`  BIGINT       NULL COMMENT '来源 Agent 编辑会话 id',
    `sourceRunId`      BIGINT       NULL COMMENT '来源 Agent 运行 id',
    `operatorId`       BIGINT       NOT NULL COMMENT '提交人 id',
    `createTime`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `isDelete`         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pictureId_versionNo` (`pictureId`, `versionNo`),
    KEY `idx_pictureId_createTime` (`pictureId`, `createTime`)
) ENGINE = InnoDB COMMENT = '图片正式版本快照' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `picture_edit_session`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `pictureId`          BIGINT       NOT NULL COMMENT '图片 id',
    `spaceId`            BIGINT       NULL COMMENT '空间 id；NULL 表示公共图库',
    `userId`             BIGINT       NOT NULL COMMENT '会话创建人 id',
    `baseEditVersion`    BIGINT       NOT NULL DEFAULT 0 COMMENT '创建会话时的 picture.editVersion 快照',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'active' COMMENT '会话状态：active/committed/conflict/expired/closed',
    `finalAssetId`       BIGINT       NULL COMMENT '用户选定的最终草稿资产 id',
    `currentAssetId`     BIGINT       NULL COMMENT '当前画布资产 id',
    `revision`           INT          NOT NULL DEFAULT 1 COMMENT '画布文档版本号',
    `historySeq`         INT          NOT NULL DEFAULT 0 COMMENT '撤销重做历史序号',
    `documentJson`       TEXT         NULL COMMENT '画布文档 JSON（图层、尺寸等）',
    `committedVersionId` BIGINT       NULL COMMENT '已提交的正式版本 id',
    `expireTime`         DATETIME     NULL COMMENT '会话过期时间',
    `createTime`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `editTime`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '编辑时间',
    `updateTime`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_pictureId_status` (`pictureId`, `status`, `isDelete`),
    KEY `idx_userId_createTime` (`userId`, `createTime`)
) ENGINE = InnoDB COMMENT = 'Agent 编辑会话' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `picture_agent_run`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'id',
    `editSessionId` BIGINT      NOT NULL COMMENT '编辑会话 id',
    `userId`        BIGINT      NOT NULL COMMENT '发起人 id',
    `revision`      INT         NOT NULL DEFAULT 1 COMMENT '发起时的画布 revision',
    `goal`          VARCHAR(512) NULL COMMENT '用户自然语言指令',
    `reply`         TEXT        NULL COMMENT 'Agent 文本回复',
    `planJson`      TEXT        NULL COMMENT '工具调用计划 JSON',
    `status`        VARCHAR(32) NOT NULL DEFAULT 'planning' COMMENT '运行状态：planning/waiting/running/succeeded/failed/canceled',
    `errorMessage`  VARCHAR(512) NULL COMMENT '失败原因',
    `createTime`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_editSessionId_createTime` (`editSessionId`, `createTime`)
) ENGINE = InnoDB COMMENT = 'Agent 一轮运行' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `picture_tool_run`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `agentRunId`    BIGINT       NOT NULL COMMENT '所属 Agent 运行 id',
    `editSessionId` BIGINT       NOT NULL COMMENT '编辑会话 id',
    `userId`        BIGINT       NOT NULL COMMENT '发起人 id',
    `stepId`        VARCHAR(64)  NOT NULL COMMENT '计划内步骤 id',
    `tool`          VARCHAR(64)  NOT NULL COMMENT '工具名',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '任务状态：pending/waiting/queued/running/succeeded/failed/canceled',
    `progress`      INT          NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    `stage`         VARCHAR(128) NULL COMMENT '当前阶段描述',
    `paramsJson`    TEXT         NULL COMMENT '工具入参 JSON',
    `resultJson`    TEXT         NULL COMMENT '工具结果 JSON',
    `errorMessage`  VARCHAR(512) NULL COMMENT '失败原因',
    `retries`       INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    `startedAt`     DATETIME     NULL COMMENT '开始执行时间',
    `finishedAt`    DATETIME     NULL COMMENT '结束时间',
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_agentRunId` (`agentRunId`),
    KEY `idx_editSessionId_status` (`editSessionId`, `status`)
) ENGINE = InnoDB COMMENT = 'Agent 工具任务' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `picture_agent_asset`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `editSessionId` BIGINT       NOT NULL COMMENT '编辑会话 id',
    `pictureId`     BIGINT       NOT NULL COMMENT '图片 id',
    `userId`        BIGINT       NOT NULL COMMENT '产出人 id',
    `kind`          VARCHAR(32)  NOT NULL COMMENT '资产类型：original/candidate/marketing/mask/delivery',
    `source`        VARCHAR(64)  NULL COMMENT '产出来源工具名或操作',
    `url`           VARCHAR(512) NOT NULL COMMENT '资产 URL',
    `thumbnailUrl`  VARCHAR(512) NULL COMMENT '缩略图 URL',
    `storageKey`    VARCHAR(512) NULL COMMENT 'COS 对象 key',
    `width`         INT          NULL COMMENT '宽度',
    `height`        INT          NULL COMMENT '高度',
    `sizeBytes`     BIGINT       NULL COMMENT '体积（字节）',
    `position`      INT          NOT NULL DEFAULT 0 COMMENT '资产墙排序位置',
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `isDelete`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_editSessionId_kind` (`editSessionId`, `kind`, `isDelete`)
) ENGINE = InnoDB COMMENT = 'Agent 会话资产墙' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `picture_edit_history`
(
    `id`            BIGINT   NOT NULL AUTO_INCREMENT COMMENT 'id',
    `editSessionId` BIGINT   NOT NULL COMMENT '编辑会话 id',
    `seq`           INT      NOT NULL COMMENT '历史序号，会话内递增',
    `action`        VARCHAR(64) NOT NULL COMMENT '动作类型：工具名或 undo 标记',
    `paramsJson`    TEXT     NULL COMMENT '动作入参 JSON',
    `resultJson`    TEXT     NULL COMMENT '动作结果 JSON（含回滚所需信息）',
    `createTime`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_editSessionId_seq` (`editSessionId`, `seq`)
) ENGINE = InnoDB COMMENT = 'Agent 编辑撤销重做历史' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `integration_outbox`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `eventType`    VARCHAR(64)  NOT NULL COMMENT '事件类型：thumbnail_rebuild/vector_rebuild/orphan_cleanup',
    `bizId`        VARCHAR(64)  NULL COMMENT '业务 id（如 pictureId）',
    `payloadJson`  TEXT         NULL COMMENT '事件载荷 JSON',
    `status`       VARCHAR(32)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending/processing/succeeded/failed',
    `retries`      INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    `errorMessage` VARCHAR(512) NULL COMMENT '失败原因',
    `createTime`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `processTime`  DATETIME     NULL COMMENT '最近处理时间',
    `updateTime`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_createTime` (`status`, `createTime`),
    KEY `idx_eventType_bizId` (`eventType`, `bizId`)
) ENGINE = InnoDB COMMENT = '集成事件发件箱' COLLATE = utf8mb4_unicode_ci;
