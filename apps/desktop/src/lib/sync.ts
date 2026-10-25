import type { CapturePayload, SyncReport } from "@recall/shared-types";
import { base64ToBytes, bytesToBase64, decrypt, encrypt } from "@recall/crypto-core";
import type { CaptureRow, CaptureStore } from "./db.js";
import type { RelayApi } from "./relay-client.js";

export interface SyncConfig {
  relayUrl: string | null;
  relaySecret: string | null;
  userId: string | null;
  syncCursor: number;
  localOnly: boolean;
}

export interface SyncDeps {
  store: CaptureStore;
  key: CryptoKey;
  client: RelayApi | null;
  config: SyncConfig;
  /** Overlap window so a capture created slightly before the cursor is never missed. */
  overlapMs?: number;
  pushLimit?: number;
}

const DEFAULT_OVERLAP_MS = 60_000;
const DEFAULT_PUSH_LIMIT = 50;

/**
 * Pull new envelopes, decrypt them locally, then push unsynced local captures.
 * Returns the cursor the caller should persist. LOCAL_ONLY makes zero network calls.
 */
export async function syncNow(deps: SyncDeps): Promise<SyncReport> {
  const { store, key, client, config } = deps;
  const localOnly = config.localOnly || client === null || !config.userId;

  if (localOnly || client === null) {
    return { pulled: 0, uploaded: 0, failed: 0, cursor: config.syncCursor, localOnly: true };
  }

  let failed = 0;
  let pulled = 0;
  let cursor = config.syncCursor;

  const since = Math.max(0, config.syncCursor - (deps.overlapMs ?? DEFAULT_OVERLAP_MS));
  const items = await client.list(since);

  for (const item of items) {
    if (store.hasCapture(item.id)) {
      cursor = Math.max(cursor, item.created_at);
      continue;
    }
    try {
      const envelope = await client.getBlob(item.id);
      const payload = await decrypt(key, envelope);
      const inserted = await store.insertCapture({
        id: item.id,
        created_at: item.created_at,
        type: payload.type,
        url: payload.url ?? null,
        title: payload.title ?? null,
        text: payload.text ?? null,
        ocr_text: payload.ocr_text ?? null,
        thumbnail: payload.thumbnail_b64 ? base64ToBytes(payload.thumbnail_b64) : null,
        synced: 1,
      });
      if (inserted) pulled += 1;
      cursor = Math.max(cursor, item.created_at);
    } catch {
      failed += 1;
      // Do not advance past an item we could not read; retry next poll.
      break;
    }
  }

  let uploaded = 0;
  const unsynced = store.listUnsynced(deps.pushLimit ?? DEFAULT_PUSH_LIMIT);
  for (const row of unsynced) {
    try {
      const payload = rowToPayload(row);
      const envelope = await encrypt(key, payload);
      await client.putBlob(row.id, envelope, row.created_at);
      await store.markSynced(row.id);
      uploaded += 1;
    } catch {
      failed += 1;
    }
  }

  return { pulled, uploaded, failed, cursor, localOnly: false };
}

export function rowToPayload(row: CaptureRow): CapturePayload {
  const payload: CapturePayload = { type: row.type };
  if (row.url) payload.url = row.url;
  if (row.title) payload.title = row.title;
  if (row.text) payload.text = row.text;
  if (row.ocr_text !== null && row.ocr_text !== undefined) payload.ocr_text = row.ocr_text;
  if (row.thumbnail) payload.thumbnail_b64 = bytesToBase64(row.thumbnail);
  return payload;
}
