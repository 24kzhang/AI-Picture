-- 图片协同工作台：新增表，可重复执行。业务草稿继续使用既有 Agent 会话、图层和资产表。
CREATE TABLE IF NOT EXISTS picture_collaboration (
    picture_id BIGINT PRIMARY KEY,
    session_id BIGINT NULL,
    editor_id BIGINT NULL,
    client_id VARCHAR(64) NULL,
    epoch BIGINT NOT NULL DEFAULT 0,
    lease_until BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS picture_collaboration_session (
    session_id BIGINT PRIMARY KEY,
    picture_id BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS picture_collaboration_run (
    run_id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS picture_collaboration_request (
    session_id BIGINT NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    digest VARCHAR(64) NOT NULL,
    PRIMARY KEY (session_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO picture_collaboration_session (session_id, picture_id)
SELECT session_id, picture_id FROM picture_collaboration WHERE session_id IS NOT NULL;
