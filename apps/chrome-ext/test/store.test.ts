import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { CapturePayload, SynonymDictionary } from "@recall/shared-types";
import {
  buildSynonymIndex,
  parseSynonymDictionary,
  type CorpusRow,
} from "@recall/search-core";
import { base64ToBytes, bytesToBase64, decrypt, deriveKeyBytes, encrypt, utf8Encode } from "@recall/crypto-core";
import { CaptureStore, MemoryPersistence, initSqliteOnce } from "../src/lib/db.js";

interface SearchFixture {
  now: number;
  corpus: CorpusRow[];
  cases: Array<{ id: string; query: string; expected_top3_ids: string[] }>;
}

const fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../../docs/test-vectors/search.json", import.meta.url)), "utf8"),
) as SearchFixture;
const synonymDictionary = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../../docs/test-vectors/synonyms.json", import.meta.url)), "utf8"),
) as SynonymDictionary;
const synonyms = buildSynonymIndex(parseSynonymDictionary(synonymDictionary));

const persistence = new MemoryPersistence();
let store: CaptureStore;

beforeAll(async () => {
  store = await CaptureStore.open(persistence);
  for (const row of fixture.corpus) {
    await store.insertCapture({ ...row, synced: 1 });
  }
});

afterAll(() => store.close());

describe("sqlite store (FTS5)", () => {
  it("creates the schema and indexes the corpus", () => {
    expect(store.count()).toBe(fixture.corpus.length);
  });

  it("keeps FTS in sync with inserts", async () => {
    const hits = await store.adapters.ftsQuery('"sourdough"', 5);
    expect(hits.map((hit) => hit.id)).toEqual(["c12"]);
  });

  for (const testCase of fixture.cases) {
    it(`matches the fixture top-3: ${testCase.id}`, async () => {
      const results = await store.search(testCase.query, synonyms, fixture.now);
      const ids = results.map((row) => row.id);
      expect(ids.slice(0, testCase.expected_top3_ids.length)).toEqual(testCase.expected_top3_ids);
    });
  }

  it("never returns a blank screen", async () => {
    for (const query of ["zzzzz", "the of and", ""]) {
      const results = await store.search(query, synonyms, fixture.now);
      expect(results.length).toBeGreaterThanOrEqual(10);
    }
  });

  it("survives a close/reopen through persistence bytes", async () => {
    store.close();
    store = await CaptureStore.open(persistence);
    expect(store.count()).toBe(fixture.corpus.length);
    const results = await store.search("distributed locks", synonyms, fixture.now);
    expect(results[0]?.id).toBe("c01");
  });
});

describe("capture round-trip through encryption and SQLite", () => {
  it("stores only plaintext locally, and the envelope decrypts to the same payload", async () => {
    const keyBytes = await deriveKeyBytes("recall test passphrase", utf8Encode("recall-salt-0016"));
    const thumbnail = base64ToBytes(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    );
    const payload: CapturePayload = {
      type: "screenshot",
      title: "LinkedIn post",
      ocr_text: "Fencing tokens for a distributed lock",
      thumbnail_b64: bytesToBase64(thumbnail),
    };

    const envelope = await encrypt(keyBytes, payload);
    const decrypted = await decrypt(keyBytes, envelope);
    expect(decrypted).toEqual(payload);

    const id = "11111111-1111-4111-8111-111111111111";
    const inserted = await store.insertCapture({
      id,
      created_at: fixture.now,
      type: decrypted.type,
      title: decrypted.title ?? null,
      ocr_text: decrypted.ocr_text ?? null,
      thumbnail: base64ToBytes(decrypted.thumbnail_b64 ?? ""),
      synced: 0,
    });
    expect(inserted).toBe(true);
    expect(await store.insertCapture({ id, created_at: 1, type: "text" })).toBe(false);

    const row = store.getCapture(id);
    expect(row?.synced).toBe(0);
    expect(row?.thumbnail).toBeInstanceOf(Uint8Array);
    expect(store.listUnsynced().map((entry) => entry.id)).toContain(id);

    await store.markSynced(id);
    expect(store.getCapture(id)?.synced).toBe(1);
    expect(store.listUnsynced().map((entry) => entry.id)).not.toContain(id);

    const results = await store.search("fencing tokens", synonyms, fixture.now);
    expect(results[0]?.id).toBe(id);
  });

  it("builds a browsable timeline filtered by type and domain", async () => {
    const timeline = store.timeline({ limit: 5 });
    expect(timeline).toHaveLength(5);
    expect(timeline[0]?.created_at).toBeGreaterThanOrEqual(timeline[1]?.created_at ?? 0);

    const urls = store.timeline({ type: "url", domain: "dmv.ca.gov" });
    expect(urls.map((row) => row.id)).toEqual(["c13"]);

    const screenshots = store.timeline({ type: "screenshot" });
    expect(screenshots.map((row) => row.id)).toContain("c06");
  });
});

describe("local metadata: tags, favorites, archive", () => {
  it("stores normalized tags and filters active captures", async () => {
    const local = await CaptureStore.open(new MemoryPersistence());
    await local.insertCapture({
      id: "a",
      created_at: 1_000,
      type: "text",
      text: "alpha",
      favorite: 1,
      tags: ["Work", " idea ", "work"],
    });
    await local.insertCapture({
      id: "b",
      created_at: 2_000,
      type: "url",
      url: "https://example.com/beta",
      title: "Beta",
      tags: ["reading"],
    });
    await local.insertCapture({ id: "c", created_at: 3_000, type: "text", text: "gamma" });

    expect(local.getCapture("a")).toMatchObject({ favorite: 1, archived: 0 });
    expect(local.tagsFor("a")).toEqual(["work", "idea"]);
    expect(local.allTags()).toEqual(["idea", "reading", "work"]);
    expect(local.timeline({ favoritesOnly: true }).map((row) => row.id)).toEqual(["a"]);
    expect(local.timeline({ tag: "WORK" }).map((row) => row.id)).toEqual(["a"]);
    expect(local.timeline({ descending: false }).map((row) => row.id)).toEqual(["a", "b", "c"]);

    await local.setArchived("b", true);
    expect(local.count()).toBe(2);
    expect(local.countArchived()).toBe(1);
    expect(local.timeline().map((row) => row.id)).toEqual(["c", "a"]);
    expect(local.archived().map((row) => row.id)).toEqual(["b"]);
    expect(local.allTags()).toEqual(["idea", "work"]);
    expect((await local.search("beta", synonyms, 4_000)).map((row) => row.id)).not.toContain("b");

    await local.restoreMany(["b"]);
    expect(local.count()).toBe(3);
    expect(local.countArchived()).toBe(0);

    const added = await local.addTag("c", " New ");
    expect(added).toContain("new");
    const removed = await local.removeTag("c", "new");
    expect(removed).not.toContain("new");

    expect(await local.archiveMany(["a", "c"])).toBe(2);
    expect(local.timeline().map((row) => row.id)).toEqual(["b"]);
    expect(await local.restoreMany(["a", "c"])).toBe(2);
    expect(local.timeline().map((row) => row.id)).toEqual(["c", "b", "a"]);
    local.close();
  });

  it("migrates a legacy database without favorite or archived columns", async () => {
    const sqlite = await initSqliteOnce();
    const legacy = new sqlite.oo1.DB(":memory:", "c");
    legacy.exec(`
      CREATE TABLE captures (
        id TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        type TEXT NOT NULL,
        url TEXT, title TEXT, text TEXT, ocr_text TEXT,
        thumbnail BLOB,
        blob_ref TEXT NOT NULL,
        synced INTEGER NOT NULL DEFAULT 0
      );
      CREATE TABLE annotations (
        id TEXT PRIMARY KEY,
        target_id TEXT NOT NULL,
        kind TEXT NOT NULL,
        value TEXT,
        created_at INTEGER NOT NULL
      );
      CREATE VIRTUAL TABLE captures_fts USING fts5(
        title, text, ocr_text, url,
        content='captures', content_rowid='rowid'
      );
      CREATE TRIGGER captures_ai AFTER INSERT ON captures BEGIN
        INSERT INTO captures_fts(rowid, title, text, ocr_text, url)
        VALUES (new.rowid, new.title, new.text, new.ocr_text, new.url);
      END;
      CREATE TRIGGER captures_ad AFTER DELETE ON captures BEGIN
        INSERT INTO captures_fts(captures_fts, rowid, title, text, ocr_text, url)
        VALUES ('delete', old.rowid, old.title, old.text, old.ocr_text, old.url);
      END;
      CREATE TRIGGER captures_au AFTER UPDATE ON captures BEGIN
        INSERT INTO captures_fts(captures_fts, rowid, title, text, ocr_text, url)
        VALUES ('delete', old.rowid, old.title, old.text, old.ocr_text, old.url);
        INSERT INTO captures_fts(rowid, title, text, ocr_text, url)
        VALUES (new.rowid, new.title, new.text, new.ocr_text, new.url);
      END;
      INSERT INTO captures (id, created_at, type, text, blob_ref, synced)
      VALUES ('legacy', 1234, 'text', 'old row', 'legacy', 1);
    `);
    const bytes = sqlite.capi.sqlite3_js_db_export(legacy);
    legacy.close();

    const persistence = new MemoryPersistence();
    await persistence.save(bytes);
    const migrated = await CaptureStore.open(persistence);
    expect(migrated.getCapture("legacy")).toMatchObject({
      text: "old row",
      favorite: 0,
      archived: 0,
      synced: 1,
    });
    expect(migrated.count()).toBe(1);
    await migrated.setFavorite("legacy", true);
    await migrated.setArchived("legacy", true);
    expect(migrated.getCapture("legacy")).toMatchObject({ favorite: 1, archived: 1 });
    expect(migrated.count()).toBe(0);
    expect(migrated.countArchived()).toBe(1);
    migrated.close();
  });
});
