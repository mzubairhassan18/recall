import type { CaptureType, SearchHit } from "@recall/shared-types";
import { tokenize } from "./normalize.js";

export interface CorpusRow {
  id: string;
  created_at: number;
  type: CaptureType;
  title?: string | null;
  url?: string | null;
  text?: string | null;
  ocr_text?: string | null;
}

/**
 * In-memory BM25 reference adapter. Used by the fixture tests and by `scripts/verify-fixtures.mjs`.
 * It implements the same adapter contract as the SQLite-backed apps; scores are not expected to
 * be numerically identical to SQLite's bm25(), but ordering for the fixture corpus is stable.
 */
export class MemorySearchAdapter {
  private readonly docs: Array<{ hit: SearchHit; tokens: string[]; length: number }>;
  private readonly averageLength: number;

  constructor(corpus: readonly CorpusRow[]) {
    this.docs = corpus.map((row) => {
      const tokens = [
        ...tokenize(row.title ?? ""),
        ...tokenize(row.text ?? ""),
        ...tokenize(row.ocr_text ?? ""),
        ...tokenize(row.url ?? ""),
      ];
      const hit: SearchHit = {
        id: row.id,
        created_at: row.created_at,
        type: row.type,
        title: row.title ?? null,
        url: row.url ?? null,
        text: row.text ?? null,
        ocr_text: row.ocr_text ?? null,
        score: 0,
      };
      return { hit, tokens, length: tokens.length };
    });
    const total = this.docs.reduce((sum, doc) => sum + Math.max(1, doc.length), 0);
    this.averageLength = this.docs.length > 0 ? total / this.docs.length : 1;
  }

  async ftsQuery(match: string, limit: number): Promise<SearchHit[]> {
    const { terms, mode } = parseMatch(match);
    if (terms.length === 0) return [];
    const queryTerms = [...new Set(terms)];

    const scored = this.docs
      .map((doc) => ({ doc, score: this.bm25(doc, queryTerms, mode) }))
      .filter((entry) => entry.score > 0)
      .sort(compare)
      .slice(0, limit);

    return scored.map((entry) => cloneHit(entry.doc.hit, entry.score));
  }

  async fuzzyQuery(terms: string[], limit: number): Promise<SearchHit[]> {
    const needles = terms.map((term) => term.toLowerCase()).filter(Boolean);
    if (needles.length === 0) return [];
    const scored = this.docs
      .filter((doc) => {
        const haystack = [
          doc.hit.title ?? "",
          doc.hit.text ?? "",
          doc.hit.ocr_text ?? "",
          doc.hit.url ?? "",
        ]
          .join(" ")
          .toLowerCase();
        return needles.some((needle) => haystack.includes(needle));
      })
      .map((doc) => ({ doc, score: 0.5 }))
      .sort(compare)
      .slice(0, limit);
    return scored.map((entry) => cloneHit(entry.doc.hit, entry.score));
  }

  async recentQuery(limit: number): Promise<SearchHit[]> {
    return [...this.docs]
      .sort((a, b) => b.hit.created_at - a.hit.created_at || a.hit.id.localeCompare(b.hit.id))
      .slice(0, limit)
      .map((doc) => cloneHit(doc.hit, 1));
  }

  private bm25(
    doc: { tokens: string[]; length: number },
    terms: readonly string[],
    mode: "or" | "and",
  ): number {
    const k1 = 1.2;
    const b = 0.75;
    const docLength = Math.max(1, doc.length);
    const frequencies = new Map<string, number>();
    for (const token of doc.tokens) frequencies.set(token, (frequencies.get(token) ?? 0) + 1);

    const matches = terms.filter((term) => (frequencies.get(term) ?? 0) > 0);
    if (mode === "and" && matches.length !== terms.length) return 0;
    if (matches.length === 0) return 0;

    let score = 0;
    for (const term of matches) {
      const tf = frequencies.get(term) ?? 0;
      const df = this.docs.filter((candidate) => candidate.tokens.includes(term)).length;
      const idf = Math.log(1 + (this.docs.length - df + 0.5) / (df + 0.5));
      score += idf * ((tf * (k1 + 1)) / (tf + k1 * (1 - b + (b * docLength) / this.averageLength)));
    }
    return score;
  }
}

function parseMatch(match: string): { terms: string[]; mode: "or" | "and" } {
  const terms = [...match.matchAll(/"([^"]*)"/g)].map((entry) => (entry[1] ?? "").toLowerCase());
  return { terms: terms.filter(Boolean), mode: /\bAND\b/.test(match) ? "and" : "or" };
}

function compare(
  a: { doc: { hit: SearchHit }; score: number },
  b: { doc: { hit: SearchHit }; score: number },
): number {
  return b.score - a.score || b.doc.hit.created_at - a.doc.hit.created_at || a.doc.hit.id.localeCompare(b.doc.hit.id);
}

function cloneHit(hit: SearchHit, score: number): SearchHit {
  return { ...hit, score };
}
