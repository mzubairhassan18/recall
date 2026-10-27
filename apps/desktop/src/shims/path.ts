/**
 * Minimal `node:path` stand-in for the webview bundle. esbuild aliases `node:path` here so
 * `src/lib/config.ts`, `src/lib/file-persistence.ts`, and the app core keep their original
 * source. Only the handful of functions the desktop app uses are implemented.
 */

function separatorFor(path: string): string {
  return /^[A-Za-z]:/.test(path) || path.startsWith("\\\\") || path.includes("\\") ? "\\" : "/";
}

export function join(...parts: string[]): string {
  const present = parts.filter((part) => part.length > 0);
  if (present.length === 0) return "";
  const separator = separatorFor(present[0]!);
  let result = present[0]!;
  for (const part of present.slice(1)) {
    result = `${result.replace(/[\\/]+$/, "")}${separator}${part.replace(/^[\\/]+/, "")}`;
  }
  return result;
}

export function dirname(path: string): string {
  const index = Math.max(path.lastIndexOf("\\"), path.lastIndexOf("/"));
  if (index < 0) return ".";
  if (index === 0) return path.slice(0, 1);
  if (index === 2 && /^[A-Za-z]:/.test(path)) return path.slice(0, 2);
  return path.slice(0, index);
}

export function basename(path: string): string {
  const index = Math.max(path.lastIndexOf("\\"), path.lastIndexOf("/"));
  return index < 0 ? path : path.slice(index + 1);
}

export function isAbsolute(path: string): boolean {
  return /^[A-Za-z]:[\\/]/.test(path) || path.startsWith("\\\\") || path.startsWith("/");
}

export const sep = "\\";
