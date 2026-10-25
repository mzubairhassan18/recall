#!/usr/bin/env node
/**
 * Verifies the published search fixtures against the TypeScript reference pipeline.
 * Run with `pnpm fixtures:verify` (tsx resolves the workspace TypeScript packages).
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import assert from "node:assert/strict";
import {
  MemorySearchAdapter,
  buildSynonymIndex,
  search,
} from "@recall/search-core";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const readJson = (name) =>
  JSON.parse(readFileSync(path.join(ROOT, "docs", "test-vectors", name), "utf8"));

const fixture = readJson("search.json");
const synonyms = buildSynonymIndex(readJson("synonyms.json"));
const adapters = new MemorySearchAdapter(fixture.corpus);

const ids = (rows) => rows.map((row) => row.id);

async function runCase(testCase) {
  const results = await search(testCase.query, {
    adapters,
    synonyms,
    now: fixture.now,
    limit: 10,
  });
  assert.ok(results.length > 0, `${testCase.id}: results must never be empty`);
  assert.deepEqual(
    ids(results).slice(0, testCase.expected_top3_ids.length),
    testCase.expected_top3_ids,
    `${testCase.id}: unexpected top-3`,
  );
  for (const row of results) {
    assert.ok(row.id, `${testCase.id}: missing id`);
    assert.ok(row.title.length > 0, `${testCase.id}: missing title`);
    assert.equal(typeof row.snippet, "string", `${testCase.id}: missing snippet`);
    assert.ok(row.created_at <= fixture.now, `${testCase.id}: future timestamp`);
    assert.ok(["url", "text", "screenshot"].includes(row.type), `${testCase.id}: bad type`);
    assert.ok(row.score > 0, `${testCase.id}: non-positive score`);
  }
  return results;
}

async function main() {
  assert.equal(fixture.cases.length, 20, "expected 20 search cases");
  const categories = new Map();
  for (const testCase of fixture.cases) {
    await runCase(testCase);
    for (const tag of testCase.tags) {
      categories.set(tag, (categories.get(tag) ?? 0) + 1);
    }
  }

  for (const required of ["exact", "synonym", "paraphrase", "fallback", "recency"]) {
    assert.ok(categories.has(required), `missing category: ${required}`);
  }

  // The pipeline must never return an empty list, even for junk or empty input.
  for (const query of ["", "   ", "zzzzqqqq", "!!!", "the and of"]) {
    const results = await search(query, {
      adapters,
      synonyms,
      now: fixture.now,
      limit: 10,
    });
    assert.ok(results.length > 0, `never-empty failed for query: ${JSON.stringify(query)}`);
  }

  const summary = [...categories.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([tag, count]) => `${tag}=${count}`)
    .join(" ");
  console.log(`[fixtures] ${fixture.cases.length} cases verified (${summary})`);
  console.log("[fixtures] never-empty fallback verified for 5 edge queries");
  console.log("\nPASS: search fixtures match the TypeScript reference pipeline.");
}

main().catch((error) => {
  console.error(`\nFAIL: ${error.message}`);
  process.exitCode = 1;
});
