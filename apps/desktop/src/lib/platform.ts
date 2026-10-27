import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { SecretBox } from "./config.js";

/**
 * The Tauri side of the desktop shell. Every call here maps to a Rust command in
 * `src-tauri/src/commands.rs`; the domain logic (crypto, SQLite, sync, UI) stays in
 * TypeScript so it matches the Chrome extension and the wire format byte for byte.
 */

export interface AppPaths {
  userData: string;
  smoke: boolean;
}

export interface ClipboardImage {
  width: number;
  height: number;
  /** PNG base64, already capped at 256 px on the long edge. */
  png_b64: string;
}

export async function appPaths(): Promise<AppPaths> {
  return invoke<AppPaths>("app_paths");
}

export async function secretBox(): Promise<SecretBox> {
  let available = false;
  try {
    available = await invoke<boolean>("secret_available");
  } catch {
    available = false;
  }
  return {
    available,
    encrypt: (plaintext) => invoke<string>("secret_encrypt", { plaintext }),
    decrypt: (token) => invoke<string>("secret_decrypt", { token }),
  };
}

export async function clipboardText(): Promise<string> {
  try {
    return await invoke<string>("clipboard_text");
  } catch {
    return "";
  }
}

export async function clipboardHtml(): Promise<string> {
  try {
    return await invoke<string>("clipboard_html");
  } catch {
    return "";
  }
}

export async function clipboardImage(): Promise<ClipboardImage | null> {
  try {
    return await invoke<ClipboardImage | null>("clipboard_image");
  } catch {
    return null;
  }
}

export async function clipboardOwnerApp(): Promise<string | null> {
  try {
    return await invoke<string | null>("clipboard_owner_app");
  } catch {
    return null;
  }
}

export async function notify(title: string, body: string): Promise<void> {
  try {
    await invoke("notify", { title, body });
  } catch {
    // Notifications are best-effort (some sessions have no daemon).
  }
}

export async function setTrayTooltip(text: string): Promise<void> {
  try {
    await invoke("set_tray_tooltip", { text });
  } catch {
    // The tray may be gone during shutdown.
  }
}

export async function openExternal(url: string): Promise<void> {
  await invoke("open_external", { url });
}

export async function quit(): Promise<void> {
  await invoke("quit_app");
}

export async function smokeReport(ok: boolean, message: string): Promise<void> {
  await invoke("smoke_report", { ok, message });
}

/** The window is created hidden; show it once the config is loaded (Electron's ready-to-show). */
export async function frontendReady(): Promise<void> {
  await invoke("frontend_ready");
}

export async function onCapture(callback: () => void): Promise<UnlistenFn> {
  return listen("recall:capture", () => callback());
}

export async function onTraySync(callback: () => void): Promise<UnlistenFn> {
  return listen("recall:tray-sync", () => callback());
}

export async function onTrayLock(callback: () => void): Promise<UnlistenFn> {
  return listen("recall:tray-lock", () => callback());
}

export async function onFocusSearch(callback: () => void): Promise<UnlistenFn> {
  return listen("recall:focus-search", () => callback());
}
