-- 新增图片的 AI 生图任务，可重复执行。
CREATE TABLE IF NOT EXISTS picture_generation_task (
    id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, space_id BIGINT NULL,
    prompt TEXT NOT NULL, width INT NOT NULL, height INT NOT NULL, status VARCHAR(24) NOT NULL,
    request_key VARCHAR(36) NOT NULL, digest VARCHAR(64) NOT NULL,
    model_id VARCHAR(100) NULL, source_url VARCHAR(1024) NULL, result_url VARCHAR(1024) NULL,
    error_message VARCHAR(300) NULL, saved_picture_id BIGINT NULL,
    worker_id VARCHAR(64) NULL, lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY user_request (user_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
