/**
 * @recall/shared-types
 *
 * Every cross-package interface lives here. This package contains no logic and no
 * runtime dependencies. Nothing else may cross a package boundary.
 */

/** The three capture kinds. Fixed by docs/wire-format.md. */
export type CaptureType = "url" | "text" | "screenshot";

/** Plaintext payload, inside the encrypted envelope only. */
export interface CapturePayload {
  type: CaptureType;
  url?: string;
  title?: string;
  text?: string;
  ocr_text?: string;
  /** Base64 of a PNG/JPEG thumbnail, longest edge <= 256px. */
  thumbnail_b64?: string;
}

/** Wire envelope. `ct` is ciphertext || 16-byte GCM tag, base64. */
export interface Envelope {
  v: 1;
  iv: string;
  ct: string;
}

/** A local row in `captures` (docs/data-model.md). */
export interface Capture {
  id: string;
  created_at: number;
  type: CaptureType;
  url?: string | null;
  title?: string | null;
  text?: string | null;
  ocr_text?: string | null;
  thumbnail?: Uint8Array | null;
  blob_ref: string;
  synced: 0 | 1;
}

/** A local row in `annotations`. Local-only in v1. */
export type AnnotationKind = "tag" | "note" | "done";

export interface Annotation {
  id: string;
  target_id: string;
  kind: AnnotationKind;
  value: string | null;
  created_at: number;
}

/** One result row returned by the search pipeline. */
export interface SearchResult {
  id: string;
  title: string;
  snippet: string;
  created_at: number;
  type: CaptureType;
  /** Higher is better. BM25 * recency decay, or a fallback score. */
  score: number;
  url?: string;
  /** Base64 thumbnail for screenshot rows (256px longest edge). */
  thumbnail_b64?: string;
}

/**
 * A candidate row produced by a SQLite adapter, before recency decay.
 * `score` is positive (SQLite bm25() is negated by the adapter).
 */
export interface SearchHit {
  id: string;
  created_at: number;
  type: CaptureType;
  title: string | null;
  url: string | null;
  text: string | null;
  ocr_text: string | null;
  score: number;
  /** Base64 thumbnail for screenshot rows, when the adapter can supply it cheaply. */
  thumbnail_b64?: string;
}

/** Injected SQLite adapter. Keeps the pipeline testable without a real database. */
export interface SearchAdapters {
  /** Evaluate a safe FTS5 MATCH expression (`"a" OR "b"`). */
  ftsQuery(match: string, limit: number): Promise<SearchHit[]>;
  /** Substring fallback, OR of the supplied terms. */
  fuzzyQuery(terms: string[], limit: number): Promise<SearchHit[]>;
  /** Most recent captures, newest first. */
  recentQuery(limit: number): Promise<SearchHit[]>;
}

/** Parsed synonym dictionary (docs/test-vectors/synonyms.json). */
export interface SynonymDictionary {
  version: number;
  groups: Record<string, string[]>;
}

/** One entry from GET /list. */
export interface SyncItem {
  id: string;
  created_at: number;
  size: number;
}

/** Relay registration response. */
export interface AuthRegisterResponse {
  user_id: string;
  salt: string;
}

/** Generic relay error body. */
export interface ApiError {
  error: string;
}

export type ApiResponse<T> = T | ApiError;

/** Client-side persisted settings. Never contains the passphrase or relay secret. */
export interface ClientConfig {
  relayUrl: string | null;
  /** Derived sync handle (not the passphrase). */
  username: string | null;
  userId: string | null;
  /** Base64 16-byte salt. */
  salt: string | null;
  /** Highest created_at pulled from the relay. */
  syncCursor: number;
  localOnly: boolean;
}

/** Result of a sync pass. */
export interface SyncReport {
  pulled: number;
  uploaded: number;
  failed: number;
  cursor: number;
  localOnly: boolean;
}

/** Portable export manifest entry. The zip contains `<id>.json` envelopes only. */
export interface ExportManifest {
  version: 1;
  exported_at: number;
  count: number;
  ids: string[];
}

/** Screenshot proposal mode (Android only; off by default). */
export type ScreenshotMode = "ask" | "hold" | "allowlist";

export interface ScreenshotSettings {
  mode: ScreenshotMode;
  /** Packages auto-saved in allowlist mode. Requires PACKAGE_USAGE_STATS. */
  allowlist: string[];
  /** Hold mode inbox lifetime, ms. 60 minutes per spec. */
  holdMs: number;
}
