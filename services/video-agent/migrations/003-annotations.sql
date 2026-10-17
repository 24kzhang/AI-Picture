CREATE TABLE IF NOT EXISTS video_annotation_member (
  project_id CHAR(36) NOT NULL,
  session_id CHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  epoch BIGINT NOT NULL DEFAULT 1,
  lease_until DATETIME(3) NULL,
  PRIMARY KEY(project_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
