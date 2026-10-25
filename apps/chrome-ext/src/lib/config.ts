import type { ClientConfig } from "@recall/shared-types";
import { bytesToBase64, base64ToBytes } from "@recall/crypto-core";
import {
  buildSynonymIndex,
  EMPTY_SYNONYMS,
  parseSynonymDictionary,
  type SynonymIndex,
} from "@recall/search-core";

export interface StoredConfig extends ClientConfig {
  relaySecret: string | null;
  fingerprint: string | null;
}

export const EMPTY_CONFIG: StoredConfig = {
  relayUrl: null,
  username: null,
  userId: null,
  salt: null,
  syncCursor: 0,
  localOnly: true,
  relaySecret: null,
  fingerprint: null,
};

const CONFIG_KEY = "recall-config";
const SESSION_KEY = "recall-session-key";

export async function loadConfig(): Promise<StoredConfig> {
  const stored = await chrome.storage.local.get(CONFIG_KEY);
  const value = stored[CONFIG_KEY] as Partial<StoredConfig> | undefined;
  return { ...EMPTY_CONFIG, ...(value ?? {}) };
}

export async function saveConfig(config: StoredConfig): Promise<void> {
  await chrome.storage.local.set({ [CONFIG_KEY]: config });
}

export async function saveSessionKey(raw: Uint8Array): Promise<void> {
  await chrome.storage.session.set({ [SESSION_KEY]: bytesToBase64(raw) });
}

export async function loadSessionKey(): Promise<Uint8Array | null> {
  const stored = await chrome.storage.session.get(SESSION_KEY);
  const value = stored[SESSION_KEY];
  if (typeof value !== "string") return null;
  try {
    const bytes = base64ToBytes(value);
    return bytes.length === 32 ? bytes : null;
  } catch {
    return null;
  }
}

export async function clearSessionKey(): Promise<void> {
  await chrome.storage.session.remove(SESSION_KEY);
}

/** Synonyms are loaded from the extension directory at runtime, not compiled in. */
let cachedSynonyms: SynonymIndex | null = null;

export async function loadSynonyms(): Promise<SynonymIndex> {
  if (cachedSynonyms) return cachedSynonyms;
  try {
    const response = await fetch(chrome.runtime.getURL("synonyms.json"));
    if (!response.ok) throw new Error("synonyms-missing");
    cachedSynonyms = buildSynonymIndex(parseSynonymDictionary(await response.json()));
  } catch {
    cachedSynonyms = EMPTY_SYNONYMS;
  }
  return cachedSynonyms;
}
