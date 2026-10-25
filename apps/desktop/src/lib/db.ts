import initSqlite, { type Database, type Sqlite3Static } from "@sqlite.org/sqlite-wasm";
import type { CaptureType, SearchAdapters, SearchHit, SearchResult } from "@recall/shared-types";
import {
  displayTitle,
  makeSnippet,
  search as runSearch,
  type SynonymIndex,
} from "@recall/search-core";
import { bytesToBase64 } from "@recall/crypto-core";

/**
 * Local SQLite store for the extension. Uses SQLite's official WASM build because sql.js's
 * shipped build has no FTS5 module (see docs/build-report.md, "Deviations"). The database
 * bytes are exported to IndexedDB after every mutation; the service worker is the only writer.
 */

export interface SqlitePersistence {
  load(): Promise<Uint8Array | null>;
  save(bytes: Uint8Array): Promise<void>;
}

export class MemoryPersistence implements SqlitePersistence {
  private bytes: Uint8Array | null = null;
  async load(): Promise<Uint8Array | null> {
    return this.bytes;
  }
  async save(bytes: Uint8Array): Promise<void> {
    this.bytes = new Uint8Array(bytes);
  }
}

export interface CaptureRow {
  id: string;
  created_at: number;
  type: CaptureType;
  url: string | null;
  title: string | null;
  text: string | null;
  ocr_text: string | null;
  thumbnail: Uint8Array | null;
  blob_ref: string;
  synced: 0 | 1;
}

export interface NewCapture {
  id: string;
  created_at: number;
  type: CaptureType;
  url?: string | null;
  title?: string | null;
  text?: string | null;
  ocr_text?: string | null;
  thumbnail?: Uint8Array | null;
  blob_ref?: string;
  synced?: 0 | 1;
}

export interface TimelineFilter {
  type?: CaptureType;
  domain?: string;
  limit?: number;
}

type BindValue = string | number | null | Uint8Array;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS captures (
  id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL,
  type TEXT NOT NULL,
  url TEXT, title TEXT, text TEXT, ocr_text TEXT,
  thumbnail BLOB,
  blob_ref TEXT NOT NULL,
  synced INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_captures_created_at ON captures(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_captures_type ON captures(type);
CREATE INDEX IF NOT EXISTS idx_captures_synced ON captures(synced);
CREATE TABLE IF NOT EXISTS annotations (
  id TEXT PRIMARY KEY,
  target_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  value TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_annotations_target ON annotations(target_id);
CREATE VIRTUAL TABLE IF NOT EXISTS captures_fts USING fts5(
  title, text, ocr_text, url,
  content='captures', content_rowid='rowid'
);
CREATE TRIGGER IF NOT EXISTS captures_ai AFTER INSERT ON captures BEGIN
  INSERT INTO captures_fts(rowid, title, text, ocr_text, url)
  VALUES (new.rowid, new.title, new.text, new.ocr_text, new.url);
END;
CREATE TRIGGER IF NOT EXISTS captures_ad AFTER DELETE ON captures BEGIN
  INSERT INTO captures_fts(captures_fts, rowid, title, text, ocr_text, url)
  VALUES ('delete', old.rowid, old.title, old.text, old.ocr_text, old.url);
END;
CREATE TRIGGER IF NOT EXISTS captures_au AFTER UPDATE ON captures BEGIN
  INSERT INTO captures_fts(captures_fts, rowid, title, text, ocr_text, url)
  VALUES ('delete', old.rowid, old.title, old.text, old.ocr_text, old.url);
  INSERT INTO captures_fts(rowid, title, text, ocr_text, url)
  VALUES (new.rowid, new.title, new.text, new.ocr_text, new.url);
END;
`;

const COLUMNS = "id, created_at, type, url, title, text, ocr_text, thumbnail, blob_ref, synced";

let sqlitePromise: Promise<Sqlite3Static> | null = null;

export function initSqliteOnce(): Promise<Sqlite3Static> {
  sqlitePromise ??= initSqlite();
  return sqlitePromise;
}

export class CaptureStore {
  readonly db: Database;
  private readonly sqlite: Sqlite3Static;
  private readonly persistence: SqlitePersistence;
  readonly adapters: SearchAdapters;

  private constructor(db: Database, sqlite: Sqlite3Static, persistence: SqlitePersistence) {
    this.db = db;
    this.sqlite = sqlite;
    this.persistence = persistence;
    this.adapters = {
      ftsQuery: (match, limit) => this.ftsQuery(match, limit),
      fuzzyQuery: (terms, limit) => this.fuzzyQuery(terms, limit),
      recentQuery: (limit) => this.recentQuery(limit),
    };
  }

  static async open(persistence: SqlitePersistence): Promise<CaptureStore> {
    const sqlite = await initSqliteOnce();
    const bytes = await persistence.load();
    const db = new sqlite.oo1.DB(":memory:", "c");
    if (bytes && bytes.length > 0) {
      const pointer = sqlite.wasm.allocFromTypedArray(bytes);
      const rc = sqlite.capi.sqlite3_deserialize(
        db,
        "main",
        pointer,
        bytes.byteLength,
        bytes.byteLength,
        sqlite.capi.SQLITE_DESERIALIZE_FREEONCLOSE | sqlite.capi.SQLITE_DESERIALIZE_RESIZEABLE,
      );
      if (rc !== 0) throw new Error("sqlite-deserialize-failed");
    }
    db.exec(SCHEMA);
    const store = new CaptureStore(db, sqlite, persistence);
    await store.persist();
    return store;
  }

  async persist(): Promise<void> {
    await this.persistence.save(this.sqlite.capi.sqlite3_js_db_export(this.db));
  }

  /** Insert if the id is new. Returns true when a row was written. */
  async insertCapture(input: NewCapture): Promise<boolean> {
    const rows = this.rows<{ id: string }>(
      `INSERT OR IGNORE INTO captures (${COLUMNS})
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       RETURNING id`,
      [
        input.id,
        input.created_at,
        input.type,
        input.url ?? null,
        input.title ?? null,
        input.text ?? null,
        input.ocr_text ?? null,
        input.thumbnail ?? null,
        input.blob_ref ?? input.id,
        input.synced ?? 0,
      ],
    );
    if (rows.length === 0) return false;
    await this.persist();
    return true;
  }

  getCapture(id: string): CaptureRow | null {
    const rows = this.rows<CaptureRow>(`SELECT ${COLUMNS} FROM captures WHERE id = ?`, [id]);
    return rows[0] ? normalizeRow(rows[0]) : null;
  }

  hasCapture(id: string): boolean {
    return this.getCapture(id) !== null;
  }

  count(): number {
    return Number(this.rows<{ n: number }>("SELECT COUNT(*) AS n FROM captures")[0]?.n ?? 0);
  }

  listUnsynced(limit = 50): CaptureRow[] {
    return this.rows<CaptureRow>(
      `SELECT ${COLUMNS} FROM captures WHERE synced = 0 ORDER BY created_at ASC, id ASC LIMIT ?`,
      [limit],
    ).map(normalizeRow);
  }

  async markSynced(id: string): Promise<void> {
    const rows = this.rows<{ id: string }>("UPDATE captures SET synced = 1 WHERE id = ? RETURNING id", [id]);
    if (rows.length > 0) await this.persist();
  }

  listRecent(filter: TimelineFilter = {}): CaptureRow[] {
    const limit = Math.max(1, Math.min(500, filter.limit ?? 100));
    const clauses: string[] = [];
    const params: BindValue[] = [];
    if (filter.type) {
      clauses.push("type = ?");
      params.push(filter.type);
    }
    if (filter.domain && filter.domain.trim()) {
      clauses.push("lower(coalesce(url, '')) LIKE ?");
      params.push(`%${filter.domain.trim().toLowerCase()}%`);
    }
    const where = clauses.length > 0 ? `WHERE ${clauses.join(" AND ")}` : "";
    params.push(limit);
    return this.rows<CaptureRow>(
      `SELECT ${COLUMNS} FROM captures ${where} ORDER BY created_at DESC, id ASC LIMIT ?`,
      params,
    ).map(normalizeRow);
  }

  timeline(filter: TimelineFilter = {}): SearchResult[] {
    return this.listRecent(filter).map((row) => {
      const hit = toHit(row, 0);
      const result: SearchResult = {
        id: row.id,
        title: displayTitle(hit),
        snippet: makeSnippet(hit, []),
        created_at: row.created_at,
        type: row.type,
        score: 0,
      };
      if (row.url) result.url = row.url;
      if (row.thumbnail) result.thumbnail_b64 = bytesToBase64(row.thumbnail);
      return result;
    });
  }

  search(query: string, synonyms: SynonymIndex, now = Date.now()): Promise<SearchResult[]> {
    return runSearch(query, { adapters: this.adapters, synonyms, now });
  }

  exportBytes(): Uint8Array {
    return this.sqlite.capi.sqlite3_js_db_export(this.db);
  }

  close(): void {
    this.db.close();
  }

  private async ftsQuery(match: string, limit: number): Promise<SearchHit[]> {
    if (!match) return [];
    const rows = this.rows<HitRow>(
      `SELECT c.id, c.created_at, c.type, c.title, c.url, c.text, c.ocr_text, c.thumbnail,
              -bm25(captures_fts) AS score
       FROM captures_fts
       JOIN captures c ON c.rowid = captures_fts.rowid
       WHERE captures_fts MATCH ?
       ORDER BY score DESC, c.created_at DESC, c.id ASC
       LIMIT ?`,
      [match, limit],
    );
    return rows.map((row) => toHit(row, Number(row.score)));
  }

  private async fuzzyQuery(terms: string[], limit: number): Promise<SearchHit[]> {
    // SELECT includes thumbnail so screenshot rows can render a preview.
    const needles = terms.filter(Boolean);
    if (needles.length === 0) return [];
    const fields = ["title", "text", "ocr_text", "url"];
    const clauses: string[] = [];
    const params: BindValue[] = [];
    for (const term of needles) {
      const pattern = `%${escapeLike(term.toLowerCase())}%`;
      for (const field of fields) {
        clauses.push(`lower(coalesce(${field}, '')) LIKE ? ESCAPE '\\'`);
        params.push(pattern);
      }
    }
    params.push(limit);
    const rows = this.rows<HitRow>(
      `SELECT ${COLUMNS}, 0.5 AS score
       FROM captures WHERE ${clauses.join(" OR ")}
       ORDER BY created_at DESC, id ASC LIMIT ?`,
      params,
    );
    return rows.map((row) => toHit(row, 0.5));
  }

  private async recentQuery(limit: number): Promise<SearchHit[]> {
    const rows = this.rows<HitRow>(
      `SELECT ${COLUMNS}, 1 AS score FROM captures ORDER BY created_at DESC, id ASC LIMIT ?`,
      [limit],
    );
    return rows.map((row) => toHit(row, 1));
  }

  private rows<T>(sql: string, params: BindValue[] = []): T[] {
    const out: T[] = [];
    this.db.exec({
      sql,
      bind: params,
      rowMode: "object",
      callback: (row) => {
        out.push(row as T);
      },
    });
    return out;
  }
}

interface HitRow {
  id: string;
  created_at: number;
  type: CaptureType;
  title: string | null;
  url: string | null;
  text: string | null;
  ocr_text: string | null;
  thumbnail?: Uint8Array | null;
  score: number;
}

function toHit(row: CaptureRow | HitRow, score: number): SearchHit {
  const hit: SearchHit = {
    id: row.id,
    created_at: Number(row.created_at),
    type: row.type,
    title: row.title,
    url: row.url,
    text: row.text,
    ocr_text: row.ocr_text,
    score,
  };
  const thumbnail = (row as { thumbnail?: unknown }).thumbnail;
  if (thumbnail instanceof Uint8Array && thumbnail.length > 0) {
    hit.thumbnail_b64 = bytesToBase64(thumbnail);
  }
  return hit;
}

function normalizeRow(row: CaptureRow): CaptureRow {
  const thumbnail = row.thumbnail as unknown;
  return {
    ...row,
    created_at: Number(row.created_at),
    synced: Number(row.synced) === 1 ? 1 : 0,
    thumbnail:
      thumbnail instanceof Uint8Array
        ? thumbnail
        : thumbnail instanceof ArrayBuffer
          ? new Uint8Array(thumbnail)
          : null,
  };
}

function escapeLike(value: string): string {
  return value.replace(/[\\%_]/g, (character) => `\\${character}`);
}
