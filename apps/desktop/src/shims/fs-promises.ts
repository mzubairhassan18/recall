import { invoke } from "@tauri-apps/api/core";
import { base64ToBytes, bytesToBase64 } from "@recall/crypto-core";
import { isAbsolute } from "./path.js";

/**
 * `node:fs/promises` stand-in for the webview bundle (aliased by esbuild). Absolute paths go
 * through the Rust host; relative paths are fetched from the app bundle (synonyms.json).
 * Errors keep the `code: "ENOENT"` shape the original callers test for.
 */

type FsError = Error & { code: string };

function enoent(path: string): FsError {
  const error = new Error(`ENOENT: no such file or directory, open '${path}'`) as FsError;
  error.code = "ENOENT";
  return error;
}

export async function readFile(path: string): Promise<Uint8Array>;
export async function readFile(path: string, encoding: "utf8"): Promise<string>;
export async function readFile(path: string, encoding?: "utf8"): Promise<Uint8Array | string> {
  if (!isAbsolute(path)) {
    let response: Response;
    try {
      response = await fetch(path);
    } catch {
      throw enoent(path);
    }
    if (!response.ok) throw enoent(path);
    const text = await response.text();
    return encoding === "utf8" ? text : new TextEncoder().encode(text);
  }

  const encoded = await invoke<string | null>("fs_read", { path });
  if (encoded === null) throw enoent(path);
  const bytes = base64ToBytes(encoded);
  return encoding === "utf8" ? new TextDecoder().decode(bytes) : bytes;
}

export async function writeFile(
  path: string,
  data: Uint8Array | string,
): Promise<void> {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  await invoke("fs_write", { path, data: bytesToBase64(bytes) });
}

export async function mkdir(path: string, _options?: { recursive?: boolean }): Promise<void> {
  await invoke("fs_mkdir", { path });
}

export async function rename(from: string, to: string): Promise<void> {
  await invoke("fs_rename", { from, to });
}
