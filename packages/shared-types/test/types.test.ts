import { describe, expect, it } from "vitest";
import type {
  Capture,
  CapturePayload,
  ClientConfig,
  Envelope,
  SearchAdapters,
  SearchResult,
  SyncItem,
} from "../src/index.js";

describe("shared-types", () => {
  it("composes the fixed shapes without logic", () => {
    const payload: CapturePayload = { type: "text", text: "hello" };
    const envelope: Envelope = { v: 1, iv: "AAAAAAAAAAAAAAAA", ct: "AAAA" };
    const capture: Capture = {
      id: "11111111-1111-4111-8111-111111111111",
      created_at: 1,
      type: "text",
      text: "hello",
      blob_ref: "11111111-1111-4111-8111-111111111111",
      synced: 0,
    };
    const result: SearchResult = {
      id: capture.id,
      title: "hello",
      snippet: "hello",
      created_at: 1,
      type: "text",
      score: 1,
    };
    const item: SyncItem = { id: capture.id, created_at: 1, size: 128 };
    const config: ClientConfig = {
      relayUrl: null,
      username: null,
      userId: null,
      salt: null,
      syncCursor: 0,
      localOnly: true,
    };
    const adapters = {} as SearchAdapters;

    expect(payload.type).toBe("text");
    expect(envelope.v).toBe(1);
    expect(capture.synced).toBe(0);
    expect(result.score).toBe(1);
    expect(item.size).toBe(128);
    expect(config.localOnly).toBe(true);
    expect(adapters).toBeDefined();
  });
});
