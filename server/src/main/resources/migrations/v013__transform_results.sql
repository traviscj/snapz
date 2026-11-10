CREATE TABLE transform_results (
  id BIGINT NOT NULL AUTO_INCREMENT,
  intent_id BIGINT NOT NULL,
  photo_id BIGINT NOT NULL,
  transform VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  data LONGTEXT NULL,
  output_version_id BIGINT NULL,
  error TEXT NULL,
  worker VARCHAR(128) NULL,
  created_at_ms BIGINT NOT NULL,
  feed_sync_id BIGINT NULL,
  shard INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY u_fsi (feed_sync_id),
  KEY idx_intent (intent_id),
  KEY idx_photo (photo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
