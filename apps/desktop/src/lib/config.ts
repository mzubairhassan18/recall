import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import type { ClientConfig } from "@recall/shared-types";

/**
 * Desktop settings. The relay secret is the only secret persisted by the app; it is wrapped by
 * Electron safeStorage (DPAPI on Windows, Keychain on macOS, libsecret on Linux) when available.
 * The passphrase, derived key, and decrypted captures are never written here.
 */
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

export interface SecretBox {
  available: boolean;
  encrypt(plaintext: string): string;
  decrypt(token: string): string;
}

type StoredSecret = { encoding: "safeStorage" | "base64"; value: string } | null;

interface ConfigFile {
  relayUrl: string | null;
  username: string | null;
  userId: string | null;
  salt: string | null;
  syncCursor: number;
  localOnly: boolean;
  fingerprint: string | null;
  secret: StoredSecret;
}

export async function loadConfig(file: string, box: SecretBox): Promise<StoredConfig> {
  let raw: string;
  try {
    raw = await readFile(file, "utf8");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return { ...EMPTY_CONFIG };
    throw error;
  }

  let parsed: Partial<ConfigFile>;
  try {
    parsed = JSON.parse(raw) as Partial<ConfigFile>;
  } catch {
    return { ...EMPTY_CONFIG };
  }

  let relaySecret: string | null = null;
  if (parsed.secret && typeof parsed.secret.value === "string") {
    try {
      relaySecret =
        parsed.secret.encoding === "safeStorage"
          ? box.decrypt(parsed.secret.value)
          : Buffer.from(parsed.secret.value, "base64").toString("utf8");
    } catch {
      relaySecret = null;
    }
  }

  return {
    relayUrl: typeof parsed.relayUrl === "string" ? parsed.relayUrl : null,
    username: typeof parsed.username === "string" ? parsed.username : null,
    userId: typeof parsed.userId === "string" ? parsed.userId : null,
    salt: typeof parsed.salt === "string" ? parsed.salt : null,
    syncCursor: Number.isFinite(parsed.syncCursor) ? Number(parsed.syncCursor) : 0,
    localOnly: parsed.localOnly !== false,
    fingerprint: typeof parsed.fingerprint === "string" ? parsed.fingerprint : null,
    relaySecret,
  };
}

export async function saveConfig(file: string, config: StoredConfig, box: SecretBox): Promise<void> {
  const secret: StoredSecret = config.relaySecret
    ? box.available
      ? { encoding: "safeStorage", value: box.encrypt(config.relaySecret) }
      : { encoding: "base64", value: Buffer.from(config.relaySecret, "utf8").toString("base64") }
    : null;

  const body: ConfigFile = {
    relayUrl: config.relayUrl,
    username: config.username,
    userId: config.userId,
    salt: config.salt,
    syncCursor: config.syncCursor,
    localOnly: config.localOnly,
    fingerprint: config.fingerprint,
    secret,
  };

  await mkdir(dirname(file), { recursive: true });
  const temporary = `${file}.tmp`;
  await writeFile(temporary, `${JSON.stringify(body, null, 2)}\n`, "utf8");
  await rename(temporary, file);
}
