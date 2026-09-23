-- 云图库 Java Agent 存量库兼容升级（MySQL 5.7）
-- 目标：将旧版 Agent 表补齐到当前实体所需字段；不删除、不重命名旧列。
-- 可重复执行。执行前请先备份 cloud_gallery 数据库。
USE `cloud_gallery`;

-- 新版实体使用的基础表和运行表在旧库中可能不存在；先补齐完整表，再对存量表补列。
CREATE TABLE IF NOT EXISTS `picture_version`
(
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NOT NULL,
    `versionNo` INT NOT NULL,
    `url` VARCHAR(512) NOT NULL,
    `thumbnailUrl` VARCHAR(512) NULL,
    `picSize` BIGINT NULL,
    `picWidth` INT NULL,
    `picHeight` INT NULL,
    `picFormat` VARCHAR(32) NULL,
    `picColor` VARCHAR(16) NULL,
    `source` VARCHAR(32) NOT NULL,
    `sourceSessionId` BIGINT NULL,
    `sourceRunId` BIGINT NULL,
    `operatorId` BIGINT NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pictureId_versionNo` (`pictureId`, `versionNo`),
    KEY `idx_pictureId_createTime` (`pictureId`, `createTime`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '图片正式版本快照';

CREATE TABLE IF NOT EXISTS `picture_edit_session`
(
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pictureId` BIGINT NOT NULL,
    `spaceId` BIGINT NULL,
    `userId` BIGINT NOT NULL,
    `baseEditVersion` BIGINT NOT NULL DEFAULT 0,
    `status` VARCHAR(32) NOT NULL DEFAULT 'active',
    `finalAssetId` BIGINT NULL,
    `currentAssetId` BIGINT NULL,
    `revision` INT NOT NULL DEFAULT 1,
    `historySeq` INT NOT NULL DEFAULT 0,
    `documentJson` TEXT NULL,
    `committedVersionId` BIGINT NULL,
    `expireTime` DATETIME NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `editTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_pictureId_status` (`pictureId`, `status`, `isDelete`),
    KEY `idx_userId_createTime` (`userId`, `createTime`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 编辑会话';

CREATE TABLE IF NOT EXISTS `picture_agent_run`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `editSessionId` BIGINT       NOT NULL,
    `userId`        BIGINT       NOT NULL,
    `revision`      INT          NOT NULL DEFAULT 1,
    `goal`          VARCHAR(512) NULL,
    `reply`         TEXT         NULL,
    `planJson`      TEXT         NULL,
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'planning',
    `errorMessage`  VARCHAR(512) NULL,
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_editSessionId_createTime` (`editSessionId`, `createTime`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 一轮运行';

CREATE TABLE IF NOT EXISTS `picture_tool_run`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `agentRunId`    BIGINT       NOT NULL,
    `editSessionId` BIGINT       NOT NULL,
    `userId`        BIGINT       NOT NULL,
    `stepId`        VARCHAR(64)  NOT NULL,
    `tool`          VARCHAR(64)  NOT NULL,
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'pending',
    `progress`      INT          NOT NULL DEFAULT 0,
    `stage`         VARCHAR(128) NULL,
    `paramsJson`    TEXT         NULL,
    `resultJson`    TEXT         NULL,
    `errorMessage`  VARCHAR(512) NULL,
    `retries`       INT          NOT NULL DEFAULT 0,
    `startedAt`     DATETIME     NULL,
    `finishedAt`    DATETIME     NULL,
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agentRunId` (`agentRunId`),
    KEY `idx_editSessionId_status` (`editSessionId`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 工具任务';

CREATE TABLE IF NOT EXISTS `picture_agent_asset`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `editSessionId` BIGINT       NOT NULL,
    `pictureId`     BIGINT       NOT NULL,
    `userId`        BIGINT       NOT NULL,
    `kind`          VARCHAR(32)  NOT NULL,
    `source`        VARCHAR(64)  NULL,
    `url`           VARCHAR(512) NOT NULL,
    `thumbnailUrl`  VARCHAR(512) NULL,
    `storageKey`    VARCHAR(512) NULL,
    `width`         INT          NULL,
    `height`        INT          NULL,
    `sizeBytes`     BIGINT       NULL,
    `position`      INT          NOT NULL DEFAULT 0,
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `isDelete`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_editSessionId_kind` (`editSessionId`, `kind`, `isDelete`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 会话资产墙';

CREATE TABLE IF NOT EXISTS `picture_edit_history`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `editSessionId` BIGINT       NOT NULL,
    `seq`           INT          NOT NULL,
    `action`        VARCHAR(64)  NOT NULL,
    `paramsJson`    TEXT         NULL,
    `resultJson`    TEXT         NULL,
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_editSessionId_seq` (`editSessionId`, `seq`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 编辑撤销重做历史';

CREATE TABLE IF NOT EXISTS `integration_outbox`
(
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `eventType` VARCHAR(64) NOT NULL,
    `bizId` VARCHAR(64) NULL,
    `payloadJson` TEXT NULL,
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending',
    `retries` INT NOT NULL DEFAULT 0,
    `errorMessage` VARCHAR(512) NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `processTime` DATETIME NULL,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status_createTime` (`status`, `createTime`),
    KEY `idx_eventType_bizId` (`eventType`, `bizId`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '集成事件发件箱';

-- 存量旧表补列。使用信息_schema 判断，兼容 MySQL 5.7（不使用 ADD COLUMN IF NOT EXISTS）。
DROP PROCEDURE IF EXISTS `add_agent_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `add_agent_column_if_missing`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = p_table
           AND COLUMN_NAME = p_column
    ) THEN
        SET @agent_ddl = p_ddl;
        PREPARE agent_stmt FROM @agent_ddl;
        EXECUTE agent_stmt;
        DEALLOCATE PREPARE agent_stmt;
    END IF;
END$$
DELIMITER ;

CALL `add_agent_column_if_missing`('picture_version', 'isDelete',
    'ALTER TABLE `picture_version` ADD COLUMN `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否删除''');

CALL `add_agent_column_if_missing`('picture_edit_session', 'finalAssetId',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `finalAssetId` BIGINT NULL COMMENT ''用户选定的最终草稿资产 id''');
CALL `add_agent_column_if_missing`('picture_edit_session', 'currentAssetId',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `currentAssetId` BIGINT NULL COMMENT ''当前画布资产 id''');
CALL `add_agent_column_if_missing`('picture_edit_session', 'revision',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `revision` INT NOT NULL DEFAULT 1 COMMENT ''画布文档版本号''');
CALL `add_agent_column_if_missing`('picture_edit_session', 'historySeq',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `historySeq` INT NOT NULL DEFAULT 0 COMMENT ''撤销重做历史序号''');
CALL `add_agent_column_if_missing`('picture_edit_session', 'documentJson',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `documentJson` TEXT NULL COMMENT ''画布文档 JSON''');
CALL `add_agent_column_if_missing`('picture_edit_session', 'editTime',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `editTime` DATETIME NULL COMMENT ''编辑时间''');
CALL `add_agent_column_if_missing`('picture_edit_session', 'isDelete',
    'ALTER TABLE `picture_edit_session` ADD COLUMN `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否删除''');

CALL `add_agent_column_if_missing`('integration_outbox', 'bizId',
    'ALTER TABLE `integration_outbox` ADD COLUMN `bizId` VARCHAR(64) NULL COMMENT ''业务 id''');
CALL `add_agent_column_if_missing`('integration_outbox', 'payloadJson',
    'ALTER TABLE `integration_outbox` ADD COLUMN `payloadJson` TEXT NULL COMMENT ''事件载荷 JSON''');
CALL `add_agent_column_if_missing`('integration_outbox', 'retries',
    'ALTER TABLE `integration_outbox` ADD COLUMN `retries` INT NOT NULL DEFAULT 0 COMMENT ''重试次数''');
CALL `add_agent_column_if_missing`('integration_outbox', 'errorMessage',
    'ALTER TABLE `integration_outbox` ADD COLUMN `errorMessage` VARCHAR(512) NULL COMMENT ''失败原因''');
CALL `add_agent_column_if_missing`('integration_outbox', 'processTime',
    'ALTER TABLE `integration_outbox` ADD COLUMN `processTime` DATETIME NULL COMMENT ''最近处理时间''');

-- 旧实现的兼容列不再由当前实体写入，若列存在则放宽约束；新库没有旧列时跳过。
DROP PROCEDURE IF EXISTS `modify_agent_column_if_exists`;
DELIMITER $$
CREATE PROCEDURE `modify_agent_column_if_exists`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl VARCHAR(1000)
)
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = p_table
           AND COLUMN_NAME = p_column
    ) THEN
        SET @agent_modify_ddl = p_ddl;
        PREPARE agent_modify_stmt FROM @agent_modify_ddl;
        EXECUTE agent_modify_stmt;
        DEALLOCATE PREPARE agent_modify_stmt;
    END IF;
END$$
DELIMITER ;

CALL `modify_agent_column_if_exists`('picture_edit_session', 'agentSessionId',
    'ALTER TABLE `picture_edit_session` MODIFY COLUMN `agentSessionId` VARCHAR(64) NULL');
CALL `modify_agent_column_if_exists`('picture_edit_session', 'finalAgentAssetId',
    'ALTER TABLE `picture_edit_session` MODIFY COLUMN `finalAgentAssetId` VARCHAR(64) NULL');
CALL `modify_agent_column_if_exists`('integration_outbox', 'aggregateId',
    'ALTER TABLE `integration_outbox` MODIFY COLUMN `aggregateId` BIGINT NULL');
CALL `modify_agent_column_if_exists`('integration_outbox', 'payload',
    'ALTER TABLE `integration_outbox` MODIFY COLUMN `payload` TEXT NULL');

UPDATE `picture_edit_session`
   SET `revision` = 1
 WHERE `revision` IS NULL OR `revision` < 1;
UPDATE `picture_edit_session`
   SET `historySeq` = 0
 WHERE `historySeq` IS NULL OR `historySeq` < 0;
UPDATE `picture_edit_session`
   SET `editTime` = COALESCE(`editTime`, `updateTime`, `createTime`)
 WHERE `editTime` IS NULL;

-- 旧版本曾写入大写状态和 READY_TO_COMMIT；统一到当前 Java 状态机的值。
UPDATE `picture_edit_session`
   SET `status` = CASE UPPER(`status`)
       WHEN 'ACTIVE' THEN 'active'
       WHEN 'COMMITTED' THEN 'committed'
       WHEN 'CONFLICT' THEN 'conflict'
       WHEN 'EXPIRED' THEN 'expired'
       WHEN 'CLOSED' THEN 'closed'
       WHEN 'READY_TO_COMMIT' THEN 'active'
       WHEN 'CANCELED' THEN 'closed'
       ELSE `status`
   END
 WHERE UPPER(`status`) IN ('ACTIVE', 'COMMITTED', 'CONFLICT', 'EXPIRED', 'CLOSED',
                          'READY_TO_COMMIT', 'CANCELED');

UPDATE `picture_agent_run`
   SET `status` = LOWER(`status`)
 WHERE UPPER(`status`) IN ('PLANNING', 'WAITING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED');

UPDATE `picture_tool_run`
   SET `status` = LOWER(`status`)
 WHERE UPPER(`status`) IN ('PENDING', 'WAITING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED');

-- 旧会话没有画布文档，不能被当前版本安全恢复。MySQL 默认大小写不敏感，
-- 这类旧的 ACTIVE 记录会被 createOrReuse 误命中，必须关闭以便创建新会话。
UPDATE `picture_edit_session`
   SET `status` = 'closed',
       `editTime` = NOW()
 WHERE LOWER(`status`) = 'active'
   AND (`documentJson` IS NULL OR CHAR_LENGTH(TRIM(`documentJson`)) = 0)
   AND `isDelete` = 0;

DROP PROCEDURE IF EXISTS `add_agent_column_if_missing`;
DROP PROCEDURE IF EXISTS `modify_agent_column_if_exists`;
