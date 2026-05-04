CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
INSERT INTO schema_version (version)
  SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM schema_version);

CREATE TABLE IF NOT EXISTS projects (
  id              INTEGER PRIMARY KEY,
  path            TEXT    NOT NULL UNIQUE,
  name            TEXT    NOT NULL,
  parent_dir      TEXT    NOT NULL,
  tempo           REAL,
  time_sig_num    INTEGER,
  time_sig_den    INTEGER,
  key             TEXT,
  track_count     INTEGER NOT NULL DEFAULT 0,
  audio_tracks    INTEGER NOT NULL DEFAULT 0,
  midi_tracks     INTEGER NOT NULL DEFAULT 0,
  return_tracks   INTEGER NOT NULL DEFAULT 0,
  length_seconds  REAL,
  live_version    TEXT,
  last_modified   REAL    NOT NULL,
  last_scanned    REAL    NOT NULL,
  file_hash       TEXT,
  is_archived     INTEGER NOT NULL DEFAULT 0,
  color_tag       INTEGER,
  notes           TEXT,
  effort_score    INTEGER,
  effort_breakdown TEXT
);

CREATE INDEX IF NOT EXISTS idx_projects_parent_dir ON projects(parent_dir);
CREATE INDEX IF NOT EXISTS idx_projects_last_modified ON projects(last_modified);
CREATE INDEX IF NOT EXISTS idx_projects_tempo ON projects(tempo);
CREATE INDEX IF NOT EXISTS idx_projects_effort_score ON projects(effort_score);
CREATE INDEX IF NOT EXISTS idx_projects_color_tag ON projects(color_tag);

CREATE TABLE IF NOT EXISTS project_plugins (
  id          INTEGER PRIMARY KEY,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  plugin_name TEXT NOT NULL,
  plugin_type TEXT NOT NULL,
  track_name  TEXT
);
CREATE INDEX IF NOT EXISTS idx_pp_project ON project_plugins(project_id);
CREATE INDEX IF NOT EXISTS idx_pp_name ON project_plugins(plugin_name);

CREATE TABLE IF NOT EXISTS project_samples (
  id           INTEGER PRIMARY KEY,
  project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  sample_path  TEXT NOT NULL,
  sample_hash  TEXT,
  is_missing   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ps_project ON project_samples(project_id);
CREATE INDEX IF NOT EXISTS idx_ps_hash ON project_samples(sample_hash);

CREATE TABLE IF NOT EXISTS tags (
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS project_tags (
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (project_id, tag_id)
);

CREATE VIRTUAL TABLE IF NOT EXISTS projects_fts USING fts5(
  name, parent_dir, plugin_names, sample_filenames, notes,
  tokenize='porter unicode61'
);
