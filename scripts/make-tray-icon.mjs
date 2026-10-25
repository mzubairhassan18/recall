import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

/** Generates the tray + window icons without any image dependency. */

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(here, "../apps/desktop/assets");
mkdirSync(out, { recursive: true });

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([length, body, crc]);
}

const GLYPH_R = ["11110", "10001", "10001", "11110", "10100", "10010", "10001"];

function render(size) {
  const pixels = Buffer.alloc(size * size * 4);
  const radius = Math.round(size * 0.22);
  const glyphScale = Math.max(1, Math.round(size / 10.5));
  const glyphWidth = 5 * glyphScale;
  const glyphHeight = 7 * glyphScale;
  const glyphX = Math.round((size - glyphWidth) / 2);
  const glyphY = Math.round((size - glyphHeight) / 2);

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const index = (y * size + x) * 4;

      const dx = Math.max(radius - x, x - (size - 1 - radius), 0);
      const dy = Math.max(radius - y, y - (size - 1 - radius), 0);
      const inside = dx * dx + dy * dy <= radius * radius;
      if (!inside) continue;

      const t = y / Math.max(1, size - 1);
      const r = Math.round(59 + (103 - 59) * t);
      const g = Math.round(91 + (65 - 91) * t);
      const b = Math.round(219 + (217 - 219) * t);
      pixels[index] = r;
      pixels[index + 1] = g;
      pixels[index + 2] = b;
      pixels[index + 3] = 255;

      const gx = Math.floor((x - glyphX) / glyphScale);
      const gy = Math.floor((y - glyphY) / glyphScale);
      if (gx >= 0 && gx < 5 && gy >= 0 && gy < 7 && GLYPH_R[gy][gx] === "1") {
        pixels[index] = 255;
        pixels[index + 1] = 255;
        pixels[index + 2] = 255;
      }
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y += 1) {
    raw[y * (size * 4 + 1)] = 0;
    pixels.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

writeFileSync(resolve(out, "tray.png"), render(32));
writeFileSync(resolve(out, "icon.png"), render(256));
console.log(`wrote ${out}\\tray.png and icon.png`);
