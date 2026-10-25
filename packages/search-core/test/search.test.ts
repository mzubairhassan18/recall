import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { CaptureType, SynonymDictionary } from "@recall/shared-types";
import {
  buildMatchExpression,
  buildSynonymIndex,
  MemorySearchAdapter,
  normalizeQuery,
  parseSynonymDictionary,
  recencyMultiplier,
  search,
  type CorpusRow,
} from "../src/index.js";

interface SearchFixture {
  now: number;
  corpus: CorpusRow[];
  cases: Array<{ id: string; query: string; tags: string[]; expected_top3_ids: string[] }>;
}

const fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../../docs/test-vectors/search.json", import.meta.url)), "utf8"),
) as SearchFixture;
const synonymDictionary = JSON.parse(
  readFileSync(fileURLToPath(new URL("../../../docs/test-vectors/synonyms.json", import.meta.url)), "utf8"),
) as SynonymDictionary;

const adapters = new MemorySearchAdapter(fixture.corpus);
const synonyms = buildSynonymIndex(synonymDictionary);

describe("search pipeline against docs/test-vectors/search.json", () => {
  it("covers all five required categories", () => {
    const tags = new Set(fixture.cases.flatMap((entry) => entry.tags));
    expect(tags).toContain("exact");
    expect(tags).toContain("synonym");
    expect(tags).toContain("paraphrase");
    expect(tags).toContain("fallback");
    expect(tags).toContain("recency");
    expect(fixture.cases).toHaveLength(20);
  });

  for (const testCase of fixture.cases) {
    it(`${testCase.id}: "${testCase.query}"`, async () => {
      const results = await search(testCase.query, {
        adapters,
        synonyms,
        now: fixture.now,
        limit: 10,
      });
      const ids = results.map((row) => row.id);
      expect(ids.slice(0, testCase.expected_top3_ids.length)).toEqual(testCase.expected_top3_ids);

      for (const row of results) {
        expect(row.id).toBeTruthy();
        expect(row.title.length).toBeGreaterThan(0);
        expect(typeof row.snippet).toBe("string");
        expect(row.created_at).toBeLessThanOrEqual(fixture.now);
        expect(["url", "text", "screenshot"]).toContain(row.type);
        expect(row.score).toBeGreaterThan(0);
      }
    });
  }

  it("never returns an empty screen for a non-empty corpus", async () => {
    for (const query of ["zzzzz", "the of and", "!!!", "", "blockchain quantum"]) {
      const results = await search(query, { adapters, synonyms, now: fixture.now });
      expect(results.length).toBeGreaterThanOrEqual(10);
    }
  });

  it("returns [] only when the corpus is empty", async () => {
    const empty = new MemorySearchAdapter([]);
    await expect(search("anything", { adapters: empty, synonyms, now: fixture.now })).resolves.toEqual([]);
  });

  it("falls back to recency when the query is only stopwords", async () => {
    const results = await search("the of and", { adapters, synonyms, now: fixture.now });
    expect(results.slice(0, 3).map((row) => row.id)).toEqual(["c22", "c01", "c06"]);
    expect(results).toHaveLength(10);
  });

  it("expands synonyms bidirectionally at runtime", async () => {
    const edited = buildSynonymIndex({
      version: 1,
      groups: { zorblax: ["redis"] },
    });
    const results = await search("zorblax", {
      adapters,
      synonyms: edited,
      now: fixture.now,
      limit: 5,
    });
    expect(results.map((row) => row.id)).toContain("c01");
  });
});

describe("normalization", () => {
  it("lowercases, strips punctuation and drops stopwords", () => {
    expect(normalizeQuery("That post about Distributed Locks!")).toEqual({
      tokens: ["that", "post", "about", "distributed", "locks"],
      terms: ["post", "distributed", "locks"],
    });
  });

  it("keeps numbers and unicode tokens", () => {
    expect(normalizeQuery("Q3 2026 日本語").terms).toEqual(["q3", "2026", "日本語"]);
  });

  it("dedupes repeated terms", () => {
    expect(normalizeQuery("lock lock LOCK").terms).toEqual(["lock"]);
  });

  it("returns no terms for stopword-only queries", () => {
    expect(normalizeQuery("the of and").terms).toEqual([]);
  });
});

describe("recency", () => {
  it("uses a 180-day half-life", () => {
    const now = 1_800_000_000_000;
    expect(recencyMultiplier(now, now)).toBe(1);
    expect(recencyMultiplier(now - 7 * 86_400_000, now)).toBeCloseTo(1 / (1 + 7 / 180), 10);
    expect(recencyMultiplier(now - 365 * 86_400_000, now)).toBeCloseTo(1 / (1 + 365 / 180), 10);
    expect(recencyMultiplier(now + 86_400_000, now)).toBe(1);
  });
});

describe("MATCH expressions", () => {
  it("quotes each term and OR-joins", () => {
    expect(buildMatchExpression(["redis", "lock"])).toBe('"redis" OR "lock"');
  });

  it("strips quotes so a token cannot break out of the expression", () => {
    expect(buildMatchExpression(['x" OR "y'])).toBe('"x OR y"');
    expect(buildMatchExpression(["", "  "])).toBe("");
  });
});

describe("synonym dictionary parsing", () => {
  it("accepts the on-disk shape and rejects junk", () => {
    const parsed = parseSynonymDictionary(synonymDictionary);
    expect(parsed?.groups.redis ?? parsed?.groups.lock).toBeTruthy();
    expect(parseSynonymDictionary(null)).toBeNull();
    expect(parseSynonymDictionary({})).toBeNull();
    expect(parseSynonymDictionary({ groups: { lock: ["locks", 3] } })?.groups.lock).toEqual(["locks"]);
  });

  it("builds a bidirectional index", () => {
    const index = buildSynonymIndex({ version: 1, groups: { a: ["b", "c"] } });
    expect(index.lookup.get("a")).toEqual(["b", "c"]);
    expect(index.lookup.get("b")).toEqual(["a", "c"]);
    expect(index.lookup.get("c")).toEqual(["a", "b"]);
  });
});

describe("reference adapter", () => {
  it("orders BM25 ties by created_at desc", async () => {
    const adapter = new MemorySearchAdapter([
      { id: "old", created_at: 1, type: "text" as CaptureType, title: "same words" },
      { id: "new", created_at: 2, type: "text" as CaptureType, title: "same words" },
    ]);
    const hits = await adapter.ftsQuery('"same" OR "words"', 10);
    expect(hits.map((hit) => hit.id)).toEqual(["new", "old"]);
  });

  it("supports AND mode", async () => {
    const adapter = new MemorySearchAdapter([
      { id: "both", created_at: 1, type: "text" as CaptureType, title: "alpha beta" },
      { id: "one", created_at: 2, type: "text" as CaptureType, title: "alpha" },
    ]);
    expect((await adapter.ftsQuery('"alpha" AND "beta"', 10)).map((hit) => hit.id)).toEqual(["both"]);
  });
});
