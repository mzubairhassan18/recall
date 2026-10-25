import type { SynonymDictionary } from "@recall/shared-types";
import { dedupe, tokenize } from "./normalize.js";

/**
 * Bidirectional synonym index. Every term in a group expands to every other term in the
 * same group. Built at runtime from docs/test-vectors/synonyms.json.
 */
export interface SynonymIndex {
  /** term -> expanded terms (excluding the term itself) */
  readonly lookup: ReadonlyMap<string, readonly string[]>;
  readonly size: number;
}

export const EMPTY_SYNONYMS: SynonymIndex = { lookup: new Map(), size: 0 };

export function buildSynonymIndex(dictionary: SynonymDictionary | null | undefined): SynonymIndex {
  const groups = new Map<string, Set<string>>();

  if (dictionary && typeof dictionary === "object" && dictionary.groups) {
    for (const [canonical, aliases] of Object.entries(dictionary.groups)) {
      if (!Array.isArray(aliases)) continue;
      const members = new Set<string>();
      for (const raw of [canonical, ...aliases]) {
        for (const token of tokenize(String(raw))) members.add(token);
      }
      if (members.size < 2) continue;
      for (const member of members) {
        const existing = groups.get(member) ?? new Set<string>();
        for (const other of members) if (other !== member) existing.add(other);
        groups.set(member, existing);
      }
    }
  }

  const lookup = new Map<string, readonly string[]>();
  for (const [term, expanded] of groups) {
    lookup.set(term, Object.freeze([...expanded].sort()));
  }
  return { lookup, size: lookup.size };
}

/** Expand terms with synonyms, original terms first, capped to keep MATCH expressions sane. */
export function expandTerms(
  terms: readonly string[],
  synonyms: SynonymIndex = EMPTY_SYNONYMS,
  cap = 24,
): string[] {
  const out: string[] = [];
  for (const term of terms) {
    if (out.length >= cap) break;
    out.push(term);
    const synonymsForTerm = synonyms.lookup.get(term);
    if (!synonymsForTerm) continue;
    for (const synonym of synonymsForTerm) {
      if (out.length >= cap) break;
      out.push(synonym);
    }
  }
  return dedupe(out);
}

/** Parse the on-disk synonyms.json shape defensively. */
export function parseSynonymDictionary(value: unknown): SynonymDictionary | null {
  if (typeof value !== "object" || value === null) return null;
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.groups !== "object" || candidate.groups === null) return null;
  const groups: Record<string, string[]> = {};
  for (const [key, aliases] of Object.entries(candidate.groups as Record<string, unknown>)) {
    if (!Array.isArray(aliases)) continue;
    groups[key] = aliases.filter((entry): entry is string => typeof entry === "string");
  }
  return {
    version: typeof candidate.version === "number" ? candidate.version : 1,
    groups,
  };
}
