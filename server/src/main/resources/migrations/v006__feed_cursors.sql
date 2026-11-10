CREATE TABLE feed_cursors (
  id BIGINT NOT NULL AUTO_INCREMENT,
  consumer VARCHAR(255) NOT NULL,
  feed VARCHAR(64) NOT NULL,
  shard INT NOT NULL DEFAULT 0,
  shard_count INT NOT NULL DEFAULT 1,
  position BIGINT NOT NULL DEFAULT 0,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_consumer_feed_shard (consumer, feed, shard)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
