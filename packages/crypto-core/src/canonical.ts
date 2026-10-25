import type { CapturePayload } from "@recall/shared-types";

/**
 * Canonical JSON for the payload, matching the Kotlin implementation byte-for-byte.
 * Key order is fixed by docs/wire-format.md. Undefined fields are omitted.
 */
const FIELD_ORDER = ["type", "url", "title", "text", "ocr_text", "thumbnail_b64"] as const;

export function canonicalPayloadJson(payload: CapturePayload): string {
  const parts: string[] = [];
  for (const key of FIELD_ORDER) {
    const value = payload[key];
    if (value === undefined) continue;
    if (typeof value !== "string") throw new Error("invalid-payload-field");
    parts.push(`${JSON.stringify(key)}:${JSON.stringify(value)}`);
  }
  return `{${parts.join(",")}}`;
}

export function isCapturePayload(value: unknown): value is CapturePayload {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  if (candidate.type !== "url" && candidate.type !== "text" && candidate.type !== "screenshot") {
    return false;
  }
  for (const key of ["url", "title", "text", "ocr_text", "thumbnail_b64"] as const) {
    if (candidate[key] !== undefined && typeof candidate[key] !== "string") return false;
  }
  if (candidate.type === "url" && typeof candidate.url !== "string") return false;
  if (candidate.type === "text" && typeof candidate.text !== "string") return false;
  if (candidate.type === "screenshot" && typeof candidate.ocr_text !== "string") return false;
  return true;
}
