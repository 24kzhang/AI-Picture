-- V2.0 Agent 融合迁移回滚脚本（验证用；正式环境保留新增表与版本记录，不执行破坏性回滚）
USE `cloud_gallery`;

DROP TABLE IF EXISTS `integration_outbox`;
DROP TABLE IF EXISTS `picture_edit_run`;
DROP TABLE IF EXISTS `picture_edit_session`;
DROP TABLE IF EXISTS `picture_version`;

ALTER TABLE `picture`
    DROP COLUMN `currentVersionId`,
    DROP COLUMN `editVersion`;
