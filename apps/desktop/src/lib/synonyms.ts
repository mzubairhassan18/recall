import { readFile } from "node:fs/promises";
import {
  buildSynonymIndex,
  EMPTY_SYNONYMS,
  parseSynonymDictionary,
  type SynonymIndex,
} from "@recall/search-core";

/** Synonyms ship as data (docs/test-vectors/synonyms.json), never compiled into the bundle. */
let cached: SynonymIndex | null = null;

export async function loadSynonyms(file: string): Promise<SynonymIndex> {
  if (cached) return cached;
  try {
    const parsed = JSON.parse(await readFile(file, "utf8")) as unknown;
    cached = buildSynonymIndex(parseSynonymDictionary(parsed));
  } catch {
    cached = EMPTY_SYNONYMS;
  }
  return cached;
}
