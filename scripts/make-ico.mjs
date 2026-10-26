import { readFileSync, writeFileSync } from "node:fs";

const [out, ...inputs] = process.argv.slice(2);
if (!out || inputs.length === 0) {
  console.error("usage: node scripts/make-ico.mjs out.ico 16.png 32.png ...");
  process.exit(1);
}

const images = inputs.map((path) => {
  const data = readFileSync(path);
  // PNG IHDR width/height are big-endian at bytes 16..23.
  const width = data.readUInt32BE(16);
  const height = data.readUInt32BE(20);
  if (width !== height || width > 256) {
    throw new Error(`${path}: expected a square PNG <= 256px, got ${width}x${height}`);
  }
  return { data, width, height };
});

const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(images.length, 4);

let offset = 6 + images.length * 16;
const entries = images.map(({ data, width, height }) => {
  const entry = Buffer.alloc(16);
  entry.writeUInt8(width === 256 ? 0 : width, 0);
  entry.writeUInt8(height === 256 ? 0 : height, 1);
  entry.writeUInt8(0, 2);
  entry.writeUInt8(0, 3);
  entry.writeUInt16LE(1, 4);
  entry.writeUInt16LE(32, 6);
  entry.writeUInt32LE(data.length, 8);
  entry.writeUInt32LE(offset, 12);
  offset += data.length;
  return entry;
});

writeFileSync(out, Buffer.concat([header, ...entries, ...images.map((image) => image.data)]));
console.log(`wrote ${out} (${images.length} sizes)`);
