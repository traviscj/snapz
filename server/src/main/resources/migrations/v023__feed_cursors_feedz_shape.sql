ALTER TABLE feed_cursors
  MODIFY created_at DATETIME(3) NOT NULL,
  MODIFY updated_at DATETIME(3) NOT NULL,
  DROP COLUMN position,
  DROP COLUMN updated_at_ms
