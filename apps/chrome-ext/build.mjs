import { readFileSync, mkdirSync, copyFileSync, cpSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import * as esbuild from "esbuild";

const here = dirname(fileURLToPath(import.meta.url));
const outdir = resolve(here, "dist");
const watch = process.argv.includes("--watch");

mkdirSync(outdir, { recursive: true });

const options = {
  entryPoints: [
    resolve(here, "src/background.ts"),
    resolve(here, "src/popup/main.ts"),
    resolve(here, "src/editor/main.ts"),
  ],
  bundle: true,
  outdir,
  format: "esm",
  target: ["chrome110"],
  sourcemap: true,
  logLevel: "info",
  define: { "process.env.NODE_ENV": JSON.stringify(watch ? "development" : "production") },
};

function copyStatic() {
  copyFileSync(resolve(here, "manifest.json"), resolve(outdir, "manifest.json"));
  copyFileSync(resolve(here, "public/popup.html"), resolve(outdir, "popup.html"));
  copyFileSync(resolve(here, "public/popup.css"), resolve(outdir, "popup.css"));
  copyFileSync(resolve(here, "public/editor.html"), resolve(outdir, "editor.html"));
  copyFileSync(resolve(here, "public/editor.css"), resolve(outdir, "editor.css"));
  cpSync(resolve(here, "public/fonts"), resolve(outdir, "fonts"), { recursive: true });
  cpSync(resolve(here, "public/icons"), resolve(outdir, "icons"), { recursive: true });
  copyFileSync(resolve(here, "../../docs/test-vectors/synonyms.json"), resolve(outdir, "synonyms.json"));
  const wasm = resolve(here, "node_modules/@sqlite.org/sqlite-wasm/dist/sqlite3.wasm");
  if (!existsSync(wasm)) throw new Error(`missing sqlite3.wasm at ${wasm}; run pnpm install`);
  copyFileSync(wasm, resolve(outdir, "sqlite3.wasm"));
}

copyStatic();

if (watch) {
  const context = await esbuild.context({
    ...options,
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
  console.log("watching…");
} else {
  await esbuild.build(options);
  console.log("built apps/chrome-ext/dist — load unpacked in chrome://extensions");
}
