// Dev tool (not run at test time): generates docs/test-vectors/search.json and prints
// the reference ranking so expectations can be reviewed and frozen.
import { writeFileSync, readFileSync } from "node:fs";
import { buildSynonymIndex, MemorySearchAdapter, parseSynonymDictionary, search } from "../packages/search-core/src/index.ts";

const NOW = 1767225600000; // 2026-01-01T00:00:00Z
const H = 3_600_000;
const D = 86_400_000;

const corpus = [
  { id: "c01", created_at: NOW - 2 * H, type: "text", title: "Distributed locks with Redis", text: "A practical guide to distributed locking using Redis, covering mutex safety and lease expiry.", url: "https://engineering.example.com/distributed-locks-redis" },
  { id: "c02", created_at: NOW - 30 * D, type: "text", title: "Distributed systems reading list", text: "Papers on consensus, replication and sharding. Raft, Paxos and quorum systems." },
  { id: "c03", created_at: NOW - 400 * D, type: "text", title: "Mutex vs semaphore", text: "When to use a mutex, when a semaphore, and why a mutex is not a lock across processes." },
  { id: "c04", created_at: NOW - 3 * D, type: "text", title: "PostgreSQL index tuning", text: "How B-tree indexes work in PostgreSQL and when a sequential scan is faster.", url: "https://example.com/postgres-index-tuning" },
  { id: "c05", created_at: NOW - 200 * D, type: "text", title: "Postgres query planner deep dive", text: "Reading EXPLAIN ANALYZE output for PostgreSQL queries." },
  { id: "c06", created_at: NOW - 1 * D, type: "screenshot", title: "Screenshot", ocr_text: "Fencing tokens are the part everyone forgets when they build a distributed lock. Notes from a LinkedIn post." },
  { id: "c07", created_at: NOW - 5 * D, type: "text", title: "Kubernetes pod scheduling", text: "How the kube-scheduler places pods on nodes, taints and tolerations." },
  { id: "c08", created_at: NOW - 400 * D, type: "text", title: "Rust ownership explained", text: "Borrowing, lifetimes and the borrow checker." },
  { id: "c09", created_at: NOW - 10 * D, type: "text", title: "Rust async runtimes compared", text: "Tokio vs async-std, futures and executors." },
  { id: "c10", created_at: NOW - 60 * D, type: "text", title: "The article about sharding", text: "A writeup on sharding Postgres with Citus. Shard keys and rebalancing." },
  { id: "c11", created_at: NOW - 730 * D, type: "text", title: "Coffee brewing ratios", text: "Espresso and pour-over ratios, grind size, water temperature." },
  { id: "c12", created_at: NOW - 1100 * D, type: "text", title: "Sourdough starter guide", text: "Feeding schedule and hydration for a healthy starter." },
  { id: "c13", created_at: NOW - 365 * D, type: "url", title: "CA DMV appointment", url: "https://dmv.ca.gov/appointment", text: "Renew license, bring proof of insurance." },
  { id: "c14", created_at: NOW - 1100 * D, type: "text", title: "Meeting notes: Q3 planning", text: "Roadmap, milestones, owners and deadlines." },
  { id: "c15", created_at: NOW - 500 * D, type: "text", title: "Distributed locks with Redis", text: "Distributed locking with Redis: mutex, lease, fencing tokens and clock skew.", url: "https://engineering.example.com/distributed-locks-redis-old" },
  { id: "c16", created_at: NOW - 7 * D, type: "text", title: "SQLite FTS5 tokenizer notes", text: "unicode61 tokenizer, porter stemming, external content tables, bm25 ranking." },
  { id: "c17", created_at: NOW - 20 * D, type: "text", title: "Encryption at rest with AES-GCM", text: "Key derivation with PBKDF2, 12-byte IVs, authentication tags." },
  { id: "c18", created_at: NOW - 45 * D, type: "text", title: "Cloudflare Workers limits", text: "CPU time, subrequests, R2 and D1 free tier limits." },
  { id: "c19", created_at: NOW - 800 * D, type: "text", title: "semaphore implementation in Go", text: "Buffered channels as counting semaphores, context cancellation." },
  { id: "c20", created_at: NOW - 2000 * D, type: "text", title: "notes about the mutex", text: "Mutexes, spinlocks and priority inversion." },
  { id: "c21", created_at: NOW - 15 * D, type: "text", title: "Python asyncio pitfalls", text: "Blocking the event loop, task cancellation, gather vs wait." },
  { id: "c22", created_at: NOW - 60_000, type: "text", title: "2026 goals", text: "ship recall, read more papers, run 500 km" },
  { id: "c23", created_at: NOW - 90 * D, type: "text", title: "Kubernetes ingress debugging", text: "Ingress controllers, 502s, service endpoints and DNS." },
  { id: "c24", created_at: NOW - 25 * D, type: "text", title: "Redis persistence options", text: "RDB snapshots vs AOF, fsync policies, replication." },
  { id: "c25", created_at: NOW - 4 * D, type: "url", title: "Show HN: Distributed lock service", url: "https://news.ycombinator.com/item?id=12345", text: "A hosted lock service built on etcd." },
  { id: "c26", created_at: NOW - 1000 * D, type: "text", title: "SQLite vs Postgres", text: "When an embedded database beats a server database." },
];

const cases = [
  { id: "exact-term-redis", query: "redis", tags: ["exact"] },
  { id: "exact-phrase-distributed-locks", query: "distributed locks", tags: ["exact"] },
  { id: "synonym-mutex", query: "mutex", tags: ["synonym"] },
  { id: "synonym-article", query: "article", tags: ["synonym"] },
  { id: "paraphrase-distributed-locks", query: "that post about distributed locks", tags: ["paraphrase"] },
  { id: "recency-same-title", query: "distributed locks with redis", tags: ["recency"] },
  { id: "pure-fallback-no-matches", query: "blockchain quantum", tags: ["fallback"] },
  { id: "typo-postgre", query: "postgre", tags: ["fallback", "typo"] },
  { id: "url-domain", query: "engineering.example.com", tags: ["url"] },
  { id: "screenshot-ocr", query: "fencing tokens", tags: ["screenshot"] },
  { id: "stopword-only", query: "the of and", tags: ["fallback"] },
  { id: "q3-planning", query: "Q3 planning", tags: ["exact", "case"] },
  { id: "year-number", query: "2026", tags: ["exact", "number"] },
  { id: "case-insensitive-redis", query: "REDIS", tags: ["case"] },
  { id: "title-outranks-body", query: "SQLite FTS5", tags: ["exact"] },
  { id: "multi-synonym-encryption-key", query: "encryption key", tags: ["synonym"] },
  { id: "fallback-shared-token", query: "cache invalidation", tags: ["fallback"] },
  { id: "single-result-coffee", query: "coffee", tags: ["sparse"] },
  { id: "synonym-semaphore", query: "semaphore", tags: ["synonym"] },
  { id: "recency-tie-lock-service", query: "distributed lock service", tags: ["recency"] },
];

const synonyms = buildSynonymIndex(
  parseSynonymDictionary(JSON.parse(readFileSync(new URL("../docs/test-vectors/synonyms.json", import.meta.url), "utf8"))),
);
const adapter = new MemorySearchAdapter(corpus);

const out = [];
for (const testCase of cases) {
  const results = await search(testCase.query, { adapters: adapter, synonyms, now: NOW, limit: 10 });
  console.log(`\n${testCase.id}  "${testCase.query}"`);
  for (const [index, row] of results.slice(0, 5).entries()) {
    console.log(`  ${index + 1}. ${row.id}  score=${row.score.toFixed(4)}  ${row.title}`);
  }
  out.push({ ...testCase, expected_top3_ids: results.slice(0, 3).map((row) => row.id) });
}

if (process.argv.includes("--write")) {
  const fixture = {
    note: "Deterministic search fixtures. `now` is fixed; the corpus is shared by every case. expected_top3_ids were produced by the reference in-memory BM25 adapter (packages/search-core/src/memory-adapter.ts) and manually reviewed for semantic correctness.",
    now: NOW,
    corpus,
    cases: out,
  };
  writeFileSync(new URL("../docs/test-vectors/search.json", import.meta.url), `${JSON.stringify(fixture, null, 2)}\n`);
  console.log("\nwrote docs/test-vectors/search.json");
}
