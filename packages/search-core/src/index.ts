export { STOPWORDS, normalizeQuery, tokenize, dedupe } from "./normalize.js";
export {
  buildSynonymIndex,
  EMPTY_SYNONYMS,
  expandTerms,
  parseSynonymDictionary,
  type SynonymIndex,
} from "./synonyms.js";
export {
  buildMatchExpression,
  recencyMultiplier,
  search,
  type SearchOptions,
} from "./pipeline.js";
export { displayTitle, makeSnippet } from "./snippet.js";
export { MemorySearchAdapter, type CorpusRow } from "./memory-adapter.js";
