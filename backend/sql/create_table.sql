-- AI 协同云图库：全新本地数据库结构
CREATE DATABASE IF NOT EXISTS `cloud_gallery`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE `cloud_gallery`;

CREATE TABLE IF NOT EXISTS `user`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `userAccount`   VARCHAR(32)  NOT NULL COMMENT '账号',
    `userPassword`  VARCHAR(32)  NOT NULL COMMENT '密码摘要',
    `userName`      VARCHAR(32)  NULL COMMENT '用户昵称',
    `userAvatar`    VARCHAR(512) NULL COMMENT '用户头像',
    `userProfile`   VARCHAR(128) NULL COMMENT '用户简介',
    `userRole`      VARCHAR(32)  NOT NULL DEFAULT 'user' COMMENT '用户角色：user/admin',
    `editTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '编辑时间',
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_userAccount` (`userAccount`),
    KEY `idx_userName` (`userName`),
    KEY `idx_userRole_isDelete` (`userRole`, `isDelete`)
) ENGINE = InnoDB COMMENT = '用户' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `space`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'id',
    `spaceName`   VARCHAR(64) NOT NULL COMMENT '空间名称',
    `spaceLevel`  INT         NOT NULL DEFAULT 0 COMMENT '空间级别：0-普通 1-专业 2-旗舰',
    `spaceType`   INT         NOT NULL DEFAULT 0 COMMENT '空间类型：0-私人 1-多人',
    `maxSize`     BIGINT      NOT NULL DEFAULT 0 COMMENT '最大总大小（字节）',
    `maxCount`    BIGINT      NOT NULL DEFAULT 0 COMMENT '最大图片数',
    `totalSize`   BIGINT      NOT NULL DEFAULT 0 COMMENT '当前总大小（字节）',
    `totalCount`  BIGINT      NOT NULL DEFAULT 0 COMMENT '当前图片数',
    `userId`      BIGINT      NOT NULL COMMENT '创建用户 id',
    `createTime`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `editTime`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '编辑时间',
    `updateTime`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_userId_type_delete` (`userId`, `spaceType`, `isDelete`),
    KEY `idx_spaceName` (`spaceName`)
) ENGINE = InnoDB COMMENT = '图库空间' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `picture`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'id',
    `url`           VARCHAR(512) NOT NULL COMMENT '原图 URL（新数据为腾讯 COS URL）',
    `thumbnailUrl`  VARCHAR(512) NULL COMMENT '缩略图 URL（新数据为腾讯 COS URL）',
    `name`          VARCHAR(128) NOT NULL COMMENT '图片名称',
    `introduction`  VARCHAR(128) NULL COMMENT '简介',
    `category`      VARCHAR(128) NULL COMMENT '分类',
    `tags`          VARCHAR(512) NULL COMMENT '标签（JSON 数组）',
    `picSize`       BIGINT       NULL COMMENT '图片体积（字节）',
    `picWidth`      INT          NULL COMMENT '图片宽度',
    `picHeight`     INT          NULL COMMENT '图片高度',
    `picScale`      DOUBLE       NULL COMMENT '图片宽高比例',
    `picFormat`     VARCHAR(32)  NULL COMMENT '图片格式',
    `picColor`      VARCHAR(16)  NULL COMMENT '图片主色调',
    `userId`        BIGINT       NOT NULL COMMENT '创建用户 id',
    `spaceId`       BIGINT       NULL COMMENT '图库空间 id；NULL 表示公共图库',
    `reviewStatus`  INT          NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核 1-通过 2-拒绝',
    `reviewMessage` VARCHAR(128) NULL COMMENT '审核信息',
    `reviewerId`    BIGINT       NULL COMMENT '审核人 id',
    `reviewTime`    DATETIME     NULL COMMENT '审核时间',
    `createTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `editTime`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '编辑时间',
    `updateTime`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_public_review_delete` (`spaceId`, `reviewStatus`, `isDelete`),
    KEY `idx_space_createTime` (`spaceId`, `createTime`),
    KEY `idx_userId` (`userId`),
    KEY `idx_name` (`name`),
    KEY `idx_category` (`category`)
) ENGINE = InnoDB COMMENT = '图片' COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `space_user`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'id',
    `spaceId`    BIGINT      NOT NULL COMMENT '空间 id',
    `userId`     BIGINT      NOT NULL COMMENT '用户 id',
    `spaceRole`  VARCHAR(16) NOT NULL DEFAULT 'viewer' COMMENT '角色：viewer/editor/admin',
    `createTime` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_spaceId_userId` (`spaceId`, `userId`),
    KEY `idx_userId` (`userId`)
) ENGINE = InnoDB COMMENT = '多人图库成员' COLLATE = utf8mb4_unicode_ci;

-- 本地演示账号：管理员 admin/admin123；普通用户 alice/gallery123、bobby/gallery123。
INSERT INTO `user`
    (`id`, `userAccount`, `userPassword`, `userName`, `userRole`)
VALUES
    (1, 'admin', 'e832b72dc3854c1112a2aa9472c7d1e2', '图库管理员', 'admin'),
    (2, 'alice', '0e8e1911b5ac9e9172aefef80fbac967', 'Alice', 'user'),
    (3, 'bobby', '0e8e1911b5ac9e9172aefef80fbac967', 'Bob', 'user')
ON DUPLICATE KEY UPDATE
    `userAccount` = VALUES(`userAccount`),
    `userName` = VALUES(`userName`),
    `userRole` = VALUES(`userRole`),
    `isDelete` = 0;

INSERT INTO `space`
    (`id`, `spaceName`, `spaceLevel`, `spaceType`, `maxSize`, `maxCount`, `userId`)
VALUES
    (1001, 'Alice 私人图库', 1, 0, 1073741824, 1000, 2),
    (1002, '协作示例图库', 1, 1, 1073741824, 1000, 2)
ON DUPLICATE KEY UPDATE
    `spaceName` = VALUES(`spaceName`),
    `spaceType` = VALUES(`spaceType`),
    `maxSize` = VALUES(`maxSize`),
    `maxCount` = VALUES(`maxCount`),
    `isDelete` = 0;

INSERT INTO `space_user` (`spaceId`, `userId`, `spaceRole`)
VALUES
    (1002, 2, 'admin'),
    (1002, 3, 'viewer')
ON DUPLICATE KEY UPDATE
    `spaceRole` = VALUES(`spaceRole`);
