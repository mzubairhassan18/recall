import type { SearchHit } from "@recall/shared-types";

const SNIPPET_BEFORE = 60;
const SNIPPET_AFTER = 120;
const SNIPPET_FALLBACK = 160;

/** Snippet extraction — docs/search-spec.md "Snippets". */
export function makeSnippet(hit: SearchHit, originalTerms: readonly string[]): string {
  const preferred = firstNonEmpty(hit.text, hit.ocr_text, hit.title, hit.url);
  if (!preferred) return "";

  const haystack = preferred.replace(/\s+/g, " ").trim();
  let bestIndex = -1;
  for (const term of originalTerms) {
    if (!term) continue;
    const index = haystack.toLowerCase().indexOf(term.toLowerCase());
    if (index === -1) continue;
    if (bestIndex === -1 || index < bestIndex) bestIndex = index;
  }

  if (bestIndex === -1) {
    return haystack.length <= SNIPPET_FALLBACK
      ? haystack
      : `${haystack.slice(0, SNIPPET_FALLBACK).trimEnd()}…`;
  }

  const rawStart = Math.max(0, bestIndex - SNIPPET_BEFORE);
  const rawEnd = Math.min(haystack.length, bestIndex + SNIPPET_AFTER);
  let start = rawStart;
  let end = rawEnd;
  if (start > 0) {
    const space = haystack.indexOf(" ", start);
    if (space !== -1 && space < bestIndex) start = space + 1;
  }
  if (end < haystack.length) {
    const space = haystack.lastIndexOf(" ", end);
    if (space !== -1 && space > bestIndex) end = space;
  }

  return `${start > 0 ? "…" : ""}${haystack.slice(start, end).trim()}${end < haystack.length ? "…" : ""}`;
}

export function displayTitle(hit: SearchHit): string {
  const title = firstNonEmpty(hit.title, hit.url, hit.text, hit.ocr_text);
  if (!title) return "(untitled)";
  const flat = title.replace(/\s+/g, " ").trim();
  return flat.length <= 120 ? flat : `${flat.slice(0, 120).trimEnd()}…`;
}

function firstNonEmpty(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim().length > 0) return value;
  }
  return "";
}
