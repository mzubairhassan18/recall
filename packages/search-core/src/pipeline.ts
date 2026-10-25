import type { SearchAdapters, SearchHit, SearchResult, SynonymDictionary } from "@recall/shared-types";
import { dedupe, normalizeQuery } from "./normalize.js";
import { buildSynonymIndex, EMPTY_SYNONYMS, expandTerms, type SynonymIndex } from "./synonyms.js";
import { displayTitle, makeSnippet } from "./snippet.js";

/** 180-day recency half-life — docs/search-spec.md. */
export const RECENCY_DAYS = 180;
const DAY_MS = 86_400_000;
const FTS_LIMIT = 50;
const FUZZY_LIMIT = 20;
const FUZZY_THRESHOLD = 3;
const FALLBACK_MIN = 10;
const FALLBACK_POOL = 100;
const FUZZY_SCORE = 0.5;
const RECENT_SCORE = 0.25;
const SHARED_SCORE = 0.1;

export interface SearchOptions {
  adapters: SearchAdapters;
  /** Pre-built index, or a parsed dictionary to build one from. */
  synonyms?: SynonymIndex | SynonymDictionary | null;
  now?: number;
  limit?: number;
  ftsLimit?: number;
  fuzzyThreshold?: number;
}

export function recencyMultiplier(createdAt: number, now: number): number {
  const ageDays = Math.max(0, (now - createdAt) / DAY_MS);
  return 1 / (1 + ageDays / RECENCY_DAYS);
}

/** Safe FTS5 MATCH expression: each term quoted, OR-joined. */
export function buildMatchExpression(terms: readonly string[]): string {
  return terms
    .map((term) => term.replace(/["]/g, "").trim())
    .filter((term) => term.length > 0)
    .map((term) => `"${term}"`)
    .join(" OR ");
}

/**
 * The fixed 7-step pipeline. Pure over the injected adapters; no SQLite, no clock, no I/O.
 * `now` is injectable so fixtures are deterministic.
 */
export async function search(query: string, options: SearchOptions): Promise<SearchResult[]> {
  const now = options.now ?? Date.now();
  const limit = Math.max(1, options.limit ?? 50);
  const adapters = options.adapters;
  const synonyms = resolveSynonyms(options.synonyms);

  // 1. normalize, 2. expand
  const { terms } = normalizeQuery(query);
  const expanded = expandTerms(terms, synonyms);

  const merged = new Map<string, { hit: SearchHit; score: number }>();
  const add = (hit: SearchHit, score: number): void => {
    if (!Number.isFinite(score)) return;
    const existing = merged.get(hit.id);
    if (!existing || score > existing.score) merged.set(hit.id, { hit, score });
  };

  // 3. FTS5 + BM25
  if (expanded.length > 0) {
    const match = buildMatchExpression(expanded);
    const hits = await adapters.ftsQuery(match, options.ftsLimit ?? FTS_LIMIT);
    for (const hit of hits) {
      // 4. recency decay
      add(hit, hit.score * recencyMultiplier(hit.created_at, now));
    }
  }

  // 5. fuzzy fallback when FTS is thin
  const threshold = options.fuzzyThreshold ?? FUZZY_THRESHOLD;
  if (merged.size < threshold && terms.length > 0) {
    const fuzzyHits = await adapters.fuzzyQuery(terms, FUZZY_LIMIT);
    for (const hit of fuzzyHits) {
      add(hit, FUZZY_SCORE * recencyMultiplier(hit.created_at, now));
    }
  }

  // 6. never return an empty screen
  if (merged.size === 0) {
    const pool = await adapters.recentQuery(FALLBACK_POOL);

    const recent = pool.slice(0, 5);
    for (const hit of recent) {
      add(hit, RECENT_SCORE * recencyMultiplier(hit.created_at, now));
    }

    if (terms.length > 0) {
      const shared = pool
        .map((hit) => ({ hit, count: countSharedTokens(hit, terms) }))
        .filter((entry) => entry.count > 0)
        .sort((a, b) => b.count - a.count || b.hit.created_at - a.hit.created_at)
        .slice(0, 5);
      for (const entry of shared) {
        add(entry.hit, SHARED_SCORE * entry.count * recencyMultiplier(entry.hit.created_at, now));
      }
    }

    for (const hit of pool) {
      if (merged.size >= FALLBACK_MIN) break;
      add(hit, RECENT_SCORE * recencyMultiplier(hit.created_at, now));
    }
  }

  // 7. shape the result rows
  const results = [...merged.values()]
    .filter((entry) => entry.score > 0)
    .sort(
      (a, b) =>
        b.score - a.score ||
        b.hit.created_at - a.hit.created_at ||
        a.hit.id.localeCompare(b.hit.id),
    )
    .slice(0, limit)
    .map(({ hit, score }) => {
      const result: SearchResult = {
        id: hit.id,
        title: displayTitle(hit),
        snippet: makeSnippet(hit, terms),
        created_at: hit.created_at,
        type: hit.type,
        score: round(score),
      };
      if (hit.url) result.url = hit.url;
      if (hit.thumbnail_b64) result.thumbnail_b64 = hit.thumbnail_b64;
      return result;
    });

  return results;
}

function resolveSynonyms(
  value: SearchOptions["synonyms"],
): SynonymIndex {
  if (!value) return EMPTY_SYNONYMS;
  if ("lookup" in value) return value;
  return buildSynonymIndex(value);
}

function countSharedTokens(hit: SearchHit, terms: readonly string[]): number {
  const haystack = dedupe(
    normalizeQuery([hit.title, hit.text, hit.ocr_text, hit.url].filter(Boolean).join(" ")).tokens,
  );
  const set = new Set(haystack);
  let count = 0;
  for (const term of terms) if (set.has(term)) count += 1;
  return count;
}

function round(value: number): number {
  return Math.round(value * 1e9) / 1e9;
}
