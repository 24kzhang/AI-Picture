CREATE TABLE IF NOT EXISTS ai_image_edit_task (
    task_id VARCHAR(80) PRIMARY KEY,
    owner_id BIGINT NULL,
    source_url VARCHAR(1024) NULL,
    status VARCHAR(24) NOT NULL,
    result_url VARCHAR(1024) NULL,
    request_id VARCHAR(120) NULL,
    message VARCHAR(300) NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_task_result (result_url)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
