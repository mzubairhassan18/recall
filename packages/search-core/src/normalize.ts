/** Step 1: lowercase, strip punctuation, drop stopwords. Fixed by docs/search-spec.md. */

export const STOPWORDS: ReadonlySet<string> = new Set([
  "a", "an", "the", "of", "to", "in", "on", "for", "and", "or", "is", "are", "was", "were",
  "be", "been", "being", "it", "its", "that", "this", "these", "those", "with", "at", "by",
  "from", "as", "about", "into", "over", "after", "before", "i", "me", "my", "we", "our",
  "you", "your", "he", "she", "they", "them", "his", "her", "their", "there", "here", "what",
  "which", "who", "whom", "why", "how", "when", "where", "do", "does", "did", "done", "can",
  "could", "should", "would", "will", "just", "very", "so", "than", "then", "too", "also",
]);

/** Unicode-aware tokenizer used for query terms and for the reference adapter. */
export function tokenize(value: string): string[] {
  if (typeof value !== "string") return [];
  return value
    .toLowerCase()
    .normalize("NFKC")
    .split(/[^\p{L}\p{N}]+/u)
    .filter((token) => token.length > 0);
}

/**
 * Normalize a query into searchable terms.
 * `tokens` keeps every token; `terms` drops stopwords (it may be empty — a stopword-only
 * query then falls through to the step-6 recency fallback, per the fixed pipeline).
 */
export function normalizeQuery(query: string): { tokens: string[]; terms: string[] } {
  const tokens = dedupe(tokenize(query));
  return { tokens, terms: tokens.filter((token) => !STOPWORDS.has(token)) };
}

export function dedupe(values: readonly string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const value of values) {
    const key = value.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(value);
  }
  return out;
}
