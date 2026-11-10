CREATE TABLE collections (
  id BIGINT NOT NULL AUTO_INCREMENT,
  slug VARCHAR(96) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT NULL,
  icon VARCHAR(32) NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  deleted_at_ms BIGINT NULL,
  feed_sync_id BIGINT NULL,
  shard INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY u_slug (slug),
  UNIQUE KEY u_fsi (feed_sync_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
