import { copyFileSync, cpSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import * as esbuild from "esbuild";

const here = dirname(fileURLToPath(import.meta.url));
const outdir = resolve(here, "dist/renderer");
const watch = process.argv.includes("--watch");

// Start from a clean tree so leftovers from previous layouts (the old Electron dist/main.js,
// dist/preload.cjs) can never end up in the Tauri bundle.
rmSync(resolve(here, "dist"), { recursive: true, force: true });
mkdirSync(resolve(outdir, "assets"), { recursive: true });

const common = {
  bundle: true,
  sourcemap: true,
  logLevel: "info",
  define: { "process.env.NODE_ENV": JSON.stringify(watch ? "development" : "production") },
  // The renderer bundle keeps the shared TypeScript sources verbatim; only the Node
  // filesystem/path modules are swapped for the Tauri host equivalents.
  alias: {
    "node:fs/promises": resolve(here, "src/shims/fs-promises.ts"),
    "node:path": resolve(here, "src/shims/path.ts"),
  },
};

const rendererOptions = {
  ...common,
  entryPoints: [resolve(here, "src/renderer/main.ts")],
  outfile: resolve(outdir, "renderer.js"),
  platform: "browser",
  format: "esm",
  target: ["chrome120"],
};

function copyStatic() {
  copyFileSync(resolve(here, "public/index.html"), resolve(outdir, "index.html"));
  copyFileSync(resolve(here, "public/styles.css"), resolve(outdir, "styles.css"));
  copyFileSync(resolve(here, "assets/icon.png"), resolve(outdir, "assets/icon.png"));
  copyFileSync(resolve(here, "assets/tray.png"), resolve(outdir, "assets/tray.png"));
  cpSync(resolve(here, "assets/fonts"), resolve(outdir, "assets/fonts"), { recursive: true });
  copyFileSync(resolve(here, "../../docs/test-vectors/synonyms.json"), resolve(outdir, "synonyms.json"));

  // SQLite's WASM build loads sqlite3.wasm next to the importing module.
  const sqlite = resolve(here, "node_modules/@sqlite.org/sqlite-wasm/dist");
  copyFileSync(resolve(sqlite, "sqlite3.wasm"), resolve(outdir, "sqlite3.wasm"));
  copyFileSync(resolve(sqlite, "sqlite3-opfs-async-proxy.js"), resolve(outdir, "sqlite3-opfs-async-proxy.js"));
  copyFileSync(resolve(sqlite, "sqlite3-worker1.mjs"), resolve(outdir, "sqlite3-worker1.mjs"));
}

copyStatic();

if (watch) {
  const context = await esbuild.context({
    ...rendererOptions,
    plugins: [
      {
        name: "recall-static",
        setup(build) {
          build.onEnd(() => copyStatic());
        },
      },
    ],
  });
  await context.watch();
  console.log("watching apps/desktop — frontend served from dist/renderer");
} else {
  await esbuild.build(rendererOptions);
  console.log("built apps/desktop/dist/renderer — run with `pnpm --filter @recall/desktop start`");
}
