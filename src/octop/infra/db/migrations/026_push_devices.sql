-- Schema v26: mobile push device registrations (FCM / future APNs).

CREATE TABLE IF NOT EXISTS push_devices (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform      TEXT NOT NULL,
  device_token  TEXT NOT NULL,
  app_id        TEXT NOT NULL DEFAULT '',
  created_at    INTEGER NOT NULL,
  last_seen     INTEGER NOT NULL,
  UNIQUE (user_id, platform, device_token)
);

CREATE INDEX IF NOT EXISTS idx_push_devices_user_id
  ON push_devices(user_id);

UPDATE _schema_version SET version = 26;
