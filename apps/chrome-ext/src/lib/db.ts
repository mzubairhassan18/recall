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
  favorite: 0 | 1;
  archived: 0 | 1;
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
  favorite?: 0 | 1;
  archived?: 0 | 1;
  tags?: string[];
}

/** Search/timeline row with local-only metadata. */
export interface CaptureSummary extends SearchResult {
  favorite: boolean;
  archived: boolean;
  tags: string[];
}

export interface TimelineFilter {
  type?: CaptureType;
  domain?: string;
  favoritesOnly?: boolean;
  tag?: string;
  descending?: boolean;
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
  synced INTEGER NOT NULL DEFAULT 0,
  favorite INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0
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

const CAPTURE_INDEXES = `
CREATE INDEX IF NOT EXISTS idx_captures_favorite ON captures(favorite);
CREATE INDEX IF NOT EXISTS idx_captures_archived ON captures(archived);
CREATE INDEX IF NOT EXISTS idx_annotations_kind_value ON annotations(kind, value);
`;

const COLUMNS =
  "id, created_at, type, url, title, text, ocr_text, thumbnail, blob_ref, synced, favorite, archived";

const MAX_TAGS = 8;
const MAX_TAG_LENGTH = 32;

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
    migrateSchema(db);
    db.exec(CAPTURE_INDEXES);
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
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        input.favorite ?? 0,
        input.archived ?? 0,
      ],
    );
    if (rows.length === 0) return false;
    for (const tag of normalizeTags(input.tags ?? [])) {
      this.insertTagSync(input.id, tag);
    }
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
    return Number(
      this.rows<{ n: number }>("SELECT COUNT(*) AS n FROM captures WHERE archived = 0")[0]?.n ?? 0,
    );
  }

  countArchived(): number {
    return Number(
      this.rows<{ n: number }>("SELECT COUNT(*) AS n FROM captures WHERE archived = 1")[0]?.n ?? 0,
    );
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
    const clauses: string[] = ["archived = 0"];
    const params: BindValue[] = [];
    if (filter.type) {
      clauses.push("type = ?");
      params.push(filter.type);
    }
    if (filter.domain && filter.domain.trim()) {
      clauses.push("lower(coalesce(url, '')) LIKE ?");
      params.push(`%${filter.domain.trim().toLowerCase()}%`);
    }
    if (filter.favoritesOnly) {
      clauses.push("favorite = 1");
    }
    if (filter.tag && filter.tag.trim()) {
      clauses.push(
        `EXISTS (
          SELECT 1 FROM annotations a
          WHERE a.target_id = captures.id AND a.kind = 'tag'
            AND lower(coalesce(a.value, '')) = lower(?)
        )`,
      );
      params.push(filter.tag.trim());
    }
    const direction = filter.descending === false ? "ASC" : "DESC";
    params.push(limit);
    return this.rows<CaptureRow>(
      `SELECT ${COLUMNS} FROM captures
       WHERE ${clauses.join(" AND ")}
       ORDER BY created_at ${direction}, id ASC LIMIT ?`,
      params,
    ).map(normalizeRow);
  }

  timeline(filter: TimelineFilter = {}): CaptureSummary[] {
    const rows = this.listRecent(filter);
    const tags = this.tagsByTarget(rows.map((row) => row.id));
    return rows.map((row) => this.toSummary(row, tags.get(row.id) ?? []));
  }

  archived(limit = 200): CaptureSummary[] {
    const rows = this.rows<CaptureRow>(
      `SELECT ${COLUMNS} FROM captures WHERE archived = 1
       ORDER BY created_at DESC, id ASC LIMIT ?`,
      [Math.max(1, Math.min(500, limit))],
    ).map(normalizeRow);
    const tags = this.tagsByTarget(rows.map((row) => row.id));
    return rows.map((row) => this.toSummary(row, tags.get(row.id) ?? []));
  }

  async search(
    query: string,
    synonyms: SynonymIndex,
    now = Date.now(),
    filter: TimelineFilter = {},
  ): Promise<CaptureSummary[]> {
    const results = await runSearch(query, { adapters: this.adapters, synonyms, now });
    const enriched = this.enrich(results);
    return applyFilters(enriched, filter);
  }

  allTags(): string[] {
    return this.rows<{ value: string }>(
      `SELECT DISTINCT a.value FROM annotations a
       JOIN captures c ON c.id = a.target_id
       WHERE a.kind = 'tag' AND c.archived = 0
         AND a.value IS NOT NULL AND a.value != ''
       ORDER BY a.value COLLATE NOCASE`,
    ).map((row) => row.value);
  }

  tagsFor(id: string): string[] {
    return this.rows<{ value: string | null }>(
      `SELECT value FROM annotations
       WHERE target_id = ? AND kind = 'tag'
       ORDER BY created_at ASC, rowid ASC`,
      [id],
    )
      .map((row) => row.value)
      .filter((value): value is string => Boolean(value && value.trim()));
  }

  async setFavorite(id: string, favorite: boolean): Promise<boolean> {
    const rows = this.rows<{ id: string }>(
      "UPDATE captures SET favorite = ? WHERE id = ? RETURNING id",
      [favorite ? 1 : 0, id],
    );
    if (rows.length === 0) return false;
    await this.persist();
    return true;
  }

  async setArchived(id: string, archived: boolean): Promise<boolean> {
    const rows = this.rows<{ id: string }>(
      "UPDATE captures SET archived = ? WHERE id = ? RETURNING id",
      [archived ? 1 : 0, id],
    );
    if (rows.length === 0) return false;
    await this.persist();
    return true;
  }

  async archiveMany(ids: string[]): Promise<number> {
    return this.setArchivedMany(ids, true);
  }

  async restoreMany(ids: string[]): Promise<number> {
    return this.setArchivedMany(ids, false);
  }

  async addTag(id: string, value: string): Promise<string[]> {
    const tag = normalizeTag(value);
    if (!tag || !this.hasCapture(id)) return this.tagsFor(id);
    this.insertTagSync(id, tag);
    await this.persist();
    return this.tagsFor(id);
  }

  async removeTag(id: string, value: string): Promise<string[]> {
    const tag = normalizeTag(value);
    if (!tag) return this.tagsFor(id);
    const rows = this.rows<{ id: string }>(
      "DELETE FROM annotations WHERE target_id = ? AND kind = 'tag' AND value = ? RETURNING id",
      [id, tag],
    );
    if (rows.length > 0) await this.persist();
    return this.tagsFor(id);
  }

  exportBytes(): Uint8Array {
    return this.sqlite.capi.sqlite3_js_db_export(this.db);
  }

  close(): void {
    this.db.close();
  }

  private async setArchivedMany(ids: string[], archived: boolean): Promise<number> {
    const unique = [...new Set(ids.filter((id) => typeof id === "string" && id.length > 0))].slice(
      0,
      500,
    );
    if (unique.length === 0) return 0;
    const placeholders = unique.map(() => "?").join(", ");
    const rows = this.rows<{ id: string }>(
      `UPDATE captures SET archived = ?
       WHERE id IN (${placeholders}) AND archived = ?
       RETURNING id`,
      [archived ? 1 : 0, ...unique, archived ? 0 : 1],
    );
    if (rows.length > 0) await this.persist();
    return rows.length;
  }

  private toSummary(row: CaptureRow, tags: string[]): CaptureSummary {
    const hit = toHit(row, 0);
    const result: CaptureSummary = {
      id: row.id,
      title: displayTitle(hit),
      snippet: makeSnippet(hit, []),
      created_at: row.created_at,
      type: row.type,
      score: 0,
      favorite: row.favorite === 1,
      archived: row.archived === 1,
      tags,
    };
    if (row.url) result.url = row.url;
    if (row.thumbnail) result.thumbnail_b64 = bytesToBase64(row.thumbnail);
    return result;
  }

  private enrich(results: SearchResult[]): CaptureSummary[] {
    if (results.length === 0) return [];
    const ids = [...new Set(results.map((result) => result.id))];
    const placeholders = ids.map(() => "?").join(", ");
    const rows = this.rows<CaptureRow>(
      `SELECT ${COLUMNS} FROM captures WHERE id IN (${placeholders})`,
      ids,
    ).map(normalizeRow);
    const byId = new Map(rows.map((row) => [row.id, row]));
    const tags = this.tagsByTarget(ids);
    const enriched: CaptureSummary[] = [];
    for (const result of results) {
      const row = byId.get(result.id);
      if (!row || row.archived === 1) continue;
      enriched.push({
        ...result,
        favorite: row.favorite === 1,
        archived: false,
        tags: tags.get(row.id) ?? [],
      });
    }
    return enriched;
  }

  private tagsByTarget(ids: string[]): Map<string, string[]> {
    const map = new Map<string, string[]>();
    const unique = [...new Set(ids)];
    if (unique.length === 0) return map;
    const placeholders = unique.map(() => "?").join(", ");
    const rows = this.rows<{ target_id: string; value: string | null }>(
      `SELECT target_id, value FROM annotations
       WHERE target_id IN (${placeholders}) AND kind = 'tag'
       ORDER BY created_at ASC, rowid ASC`,
      unique,
    );
    for (const row of rows) {
      if (!row.value || !row.value.trim()) continue;
      const values = map.get(row.target_id) ?? [];
      if (!values.includes(row.value)) values.push(row.value);
      map.set(row.target_id, values);
    }
    return map;
  }

  private insertTagSync(id: string, tag: string): void {
    const existing = this.tagsFor(id);
    if (
      existing.length >= MAX_TAGS ||
      existing.some((value) => value.toLowerCase() === tag.toLowerCase())
    ) {
      return;
    }
    this.rows<{ id: string }>(
      `INSERT INTO annotations (id, target_id, kind, value, created_at)
       VALUES (?, ?, 'tag', ?, ?)
       RETURNING id`,
      [crypto.randomUUID(), id, tag, Date.now()],
    );
  }

  private async ftsQuery(match: string, limit: number): Promise<SearchHit[]> {
    if (!match) return [];
    const rows = this.rows<HitRow>(
      `SELECT c.id, c.created_at, c.type, c.title, c.url, c.text, c.ocr_text, c.thumbnail,
              -bm25(captures_fts) AS score
       FROM captures_fts
       JOIN captures c ON c.rowid = captures_fts.rowid
       WHERE captures_fts MATCH ? AND c.archived = 0
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
       FROM captures WHERE archived = 0 AND (${clauses.join(" OR ")})
       ORDER BY created_at DESC, id ASC LIMIT ?`,
      params,
    );
    return rows.map((row) => toHit(row, 0.5));
  }

  private async recentQuery(limit: number): Promise<SearchHit[]> {
    const rows = this.rows<HitRow>(
      `SELECT ${COLUMNS}, 1 AS score FROM captures
       WHERE archived = 0 ORDER BY created_at DESC, id ASC LIMIT ?`,
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

function migrateSchema(db: Database): void {
  const columns = new Set<string>();
  db.exec({
    sql: "PRAGMA table_info(captures)",
    rowMode: "object",
    callback: (row) => {
      const name = (row as { name?: unknown }).name;
      if (typeof name === "string") columns.add(name);
    },
  });
  if (!columns.has("favorite")) {
    db.exec("ALTER TABLE captures ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0");
  }
  if (!columns.has("archived")) {
    db.exec("ALTER TABLE captures ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
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
    favorite: Number(row.favorite) === 1 ? 1 : 0,
    archived: Number(row.archived) === 1 ? 1 : 0,
    thumbnail:
      thumbnail instanceof Uint8Array
        ? thumbnail
        : thumbnail instanceof ArrayBuffer
          ? new Uint8Array(thumbnail)
          : null,
  };
}

function applyFilters(rows: CaptureSummary[], filter: TimelineFilter): CaptureSummary[] {
  let filtered = rows;
  if (filter.type) filtered = filtered.filter((row) => row.type === filter.type);
  if (filter.domain && filter.domain.trim()) {
    const domain = filter.domain.trim().toLowerCase();
    filtered = filtered.filter((row) => (row.url ?? "").toLowerCase().includes(domain));
  }
  if (filter.favoritesOnly) filtered = filtered.filter((row) => row.favorite);
  if (filter.tag && filter.tag.trim()) {
    const tag = filter.tag.trim().toLowerCase();
    filtered = filtered.filter((row) => row.tags.some((value) => value.toLowerCase() === tag));
  }
  if (filter.descending === undefined) {
    if (filter.limit === undefined) return filtered;
    return filtered.slice(0, Math.max(1, Math.min(500, filter.limit)));
  }
  const direction = filter.descending ? -1 : 1;
  const sorted = [...filtered].sort(
    (a, b) => direction * (a.created_at - b.created_at) || a.id.localeCompare(b.id),
  );
  if (filter.limit === undefined) return sorted;
  return sorted.slice(0, Math.max(1, Math.min(500, filter.limit)));
}

function normalizeTag(value: string): string | null {
  const tag = value.trim().toLowerCase().slice(0, MAX_TAG_LENGTH);
  return tag.length > 0 ? tag : null;
}

function normalizeTags(values: string[]): string[] {
  const tags: string[] = [];
  for (const value of values) {
    const tag = normalizeTag(value);
    if (tag && !tags.includes(tag)) tags.push(tag);
    if (tags.length >= MAX_TAGS) break;
  }
  return tags;
}

function escapeLike(value: string): string {
  return value.replace(/[\\%_]/g, (character) => `\\${character}`);
}
