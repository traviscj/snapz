CREATE TABLE transforms (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  description TEXT NULL,
  builtin TINYINT(1) NOT NULL DEFAULT 0,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
