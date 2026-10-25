# Search spec

The pipeline is fixed. The agent must not improvise, reorder, or add steps.

## Pipeline (normative)

1. **Normalize** the query: lowercase, strip punctuation, drop stopwords.
2. **Expand** each surviving term with `docs/test-vectors/synonyms.json`.
3. `SELECT ... FROM captures_fts WHERE captures_fts MATCH ?` using **BM25**.
4. Multiply each score by `1 / (1 + age_days / 180)` (180-day recency half-life).
5. If fewer than 3 results, run the **fuzzy fallback**: `LIKE %term%` OR'd over the query terms.
6. If still 0, return the **5 most recent** plus the **5 sharing any query token** (filled to at
   least 10 with recent captures when the corpus is large enough).
7. Return `{ id, title, snippet, created_at, type, score }`, best first.

"Empty result" is a protocol violation: step 6 always returns items when the corpus is non-empty.

## Normalization details

- Lowercase; replace every non `[a-z0-9]` run with a space.
- Split on whitespace; drop empty tokens; drop stopwords (English list below).
- Keep numbers. Preserve token order (synonyms are appended, never replacing the original).
- Dedupe expanded terms case-insensitively.

Stopwords: `a an the of to in on for and or is are was were be been being it its that this these
those with at by from as about into over after before i me my we our you your he she they them
his her their there here what which who whom why how when where do does did done can could should
would will just very so than then too also`.

## BM25

- `k1 = 1.2`, `b = 0.75` (SQLite FTS5 defaults).
- The adapter returns a positive `score` (SQLite's `bm25()` is negated).
- Ties break by `created_at DESC`, then `id ASC`.

The Injected adapter contract (`packages/shared-types/src/index.ts`):

```ts
interface SearchAdapters {
  ftsQuery(match: string, limit: number): Promise<SearchHit[]>;
  fuzzyQuery(terms: string[], limit: number): Promise<SearchHit[]>;
  recentQuery(limit: number): Promise<SearchHit[]>;
}
```

`ftsQuery` receives a safe FTS5 MATCH expression built from quoted terms (`"a" OR "b"`).
The extension, desktop, and Android each adapt their local SQLite to it. `search-core`'s test
suite uses an in-memory BM25 reference adapter so the pipeline is tested without SQLite.

## Snippets

- Prefer `text`, then `ocr_text`, then `title`, then `url`.
- Find the first occurrence of any original (non-expanded) query term, case-insensitive.
- Window: 60 chars before and 120 after, clipped to word boundaries, `…` on either side.
- If no term occurs, use the first 160 chars of the preferred field.
- Highlighting is a UI concern: the UI wraps original and expanded terms in `<mark>`.

## Recency

`age_days = max(0, (now - created_at) / 86_400_000)`; `recency = 1 / (1 + age_days / 180)`.
A 7-day-old item scores 0.96×, a 1-year-old item 0.33×, a 3-year-old item 0.14×.

## Fuzzy fallback scoring

Substring hits that were not already returned are appended with
`score = 0.5 * recency`, so they can never outrank a real BM25 hit of the same age. De-duplicate
by `id`; keep the higher score.

## Step 6 fallback scoring

- The 5 most recent items: `score = 0.25 * recency`.
- The 5 items sharing the most query tokens: `score = 0.1 * sharedTokenCount * recency`.
- De-duplicate by `id`, sort, and fill from `recentQuery` until 10 items or the corpus is exhausted.

## Runtime synonym loading

`synonyms.json` is loaded at runtime, not compiled in:

- Chrome/Edge: `chrome.runtime.getURL("synonyms.json")` fetched on popup start; edit the file in
  the unpacked extension and reload to change it.
- Desktop: read from `<resources>/synonyms.json` (copied from docs at build time) on app start.
- Android: read from `assets/synonyms.json` at process start.

If the file is missing or malformed, the engine falls back to an empty synonym map and records a
warning — search still works, expansion just becomes a no-op.
