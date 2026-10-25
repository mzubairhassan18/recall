# Data model

Identical logical schema on every client. SQLite dialect follows SQLite 3.35+ (FTS5) on every
client. Android uses SQLCipher (the file and FTS index are encrypted at rest) + Room; the FTS5
table and external-content triggers are created manually because Room only exposes FTS4
annotations. The logical columns are identical.

```sql
CREATE TABLE captures (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  type TEXT NOT NULL,
  url TEXT, title TEXT, text TEXT, ocr_text TEXT,
  thumbnail BLOB,
  blob_ref TEXT NOT NULL,      -- R2 object key == capture id
  synced INTEGER NOT NULL DEFAULT 0
);

CREATE VIRTUAL TABLE captures_fts USING fts5(
  title, text, ocr_text, url,
  content='captures', content_rowid='rowid'
);

CREATE TABLE annotations (
  id TEXT PRIMARY KEY,
  target_id TEXT NOT NULL,
  kind TEXT NOT NULL,          -- "tag" | "note" | "done"
  value TEXT,
  created_at INTEGER NOT NULL
);
```

## Rules

- `id` is a UUID generated on the capturing device. `blob_ref` is the same value (the relay
  key is `<user_id>/<id>`); it is not a separate concept.
- `created_at` is capture time in unix milliseconds, not sync time. Recency ranking and the
  timeline use it.
- `thumbnail` is a BLOB in SQLite (base64-decoded from the payload on insert). It is never sent
  to the relay outside the envelope.
- `synced = 0` means "local, needs upload". After a successful `PUT`, set `synced = 1`.
  Downloaded blobs are inserted with `synced = 1`.
- FTS is kept in sync with triggers on desktop/extension (insert/update/delete). Android's
  DAO writes both tables in one transaction.
- Search adapters only ever read `captures` / `captures_fts`; they never decrypt.
- `annotations` are local-only in v1 (no relay route). They exist so tags/notes/done do not
  require a schema migration later.

## Indexes

```sql
CREATE INDEX idx_captures_created_at ON captures(created_at DESC);
CREATE INDEX idx_captures_type ON captures(type);
CREATE INDEX idx_captures_synced ON captures(synced) WHERE synced = 0;
CREATE INDEX idx_annotations_target ON annotations(target_id);
```
