CREATE TABLE photo_versions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  token VARCHAR(32) NOT NULL,
  photo_id BIGINT NOT NULL,
  parent_version_id BIGINT NULL,
  transform VARCHAR(64) NULL,
  sha256 CHAR(64) NOT NULL,
  content_type VARCHAR(64) NOT NULL,
  bytes BIGINT NOT NULL,
  width INT NULL,
  height INT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_token (token),
  KEY idx_photo (photo_id),
  KEY idx_sha (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
