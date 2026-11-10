CREATE TABLE records (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ns VARCHAR(128) NOT NULL,
  k VARCHAR(255) NOT NULL,
  v LONGTEXT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  deleted_at_ms BIGINT NULL,
  feed_sync_id BIGINT NULL,
  shard INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY u_ns_k (ns, k),
  UNIQUE KEY u_fsi (feed_sync_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
