import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import type { SqlitePersistence } from "./db.js";

/**
 * Byte persistence backed by a single file in Electron's userData directory.
 * Writes are atomic (temp file + rename) so a crash mid-save cannot truncate the store.
 */
export class FilePersistence implements SqlitePersistence {
  constructor(private readonly file: string) {}

  async load(): Promise<Uint8Array | null> {
    try {
      const bytes = await readFile(this.file);
      return new Uint8Array(bytes);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
      throw error;
    }
  }

  async save(bytes: Uint8Array): Promise<void> {
    await mkdir(dirname(this.file), { recursive: true });
    const temporary = `${this.file}.tmp`;
    await writeFile(temporary, bytes);
    await rename(temporary, this.file);
  }
}
