CREATE TABLE api_tokens (
  id BIGINT NOT NULL AUTO_INCREMENT,
  kind VARCHAR(16) NOT NULL,
  name VARCHAR(255) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  principal VARCHAR(255) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  last_used_at_ms BIGINT NULL,
  expires_at_ms BIGINT NULL,
  revoked_at_ms BIGINT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_hash (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
