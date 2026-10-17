CREATE TABLE IF NOT EXISTS picture_annotation_member (
 picture_id BIGINT NOT NULL, client_id VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL,
 session_id BIGINT NOT NULL, epoch BIGINT NOT NULL, lease_until BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(picture_id,client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS picture_annotation_document (
 session_id BIGINT PRIMARY KEY, applied_revision INT NOT NULL, document MEDIUMTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
