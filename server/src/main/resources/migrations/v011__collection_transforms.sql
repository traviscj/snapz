CREATE TABLE collection_transforms (
  id BIGINT NOT NULL AUTO_INCREMENT,
  collection_id BIGINT NOT NULL,
  position INT NOT NULL,
  transform VARCHAR(64) NOT NULL,
  params LONGTEXT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY u_collection_position (collection_id, position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
