import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import * as esbuild from "esbuild";

const here = dirname(fileURLToPath(import.meta.url));
const outdir = resolve(here, "dist");
const watch = process.argv.includes("--watch");

mkdirSync(resolve(outdir, "renderer"), { recursive: true });
mkdirSync(resolve(outdir, "assets"), { recursive: true });

const common = {
  bundle: true,
  sourcemap: true,
  logLevel: "info",
  define: { "process.env.NODE_ENV": JSON.stringify(watch ? "development" : "production") },
};

const mainOptions = {
  ...common,
  entryPoints: [resolve(here, "src/main.ts")],
  outfile: resolve(outdir, "main.js"),
  platform: "node",
  format: "esm",
  target: ["node22"],
  external: ["electron", "@sqlite.org/sqlite-wasm"],
};

const preloadOptions = {
  ...common,
  entryPoints: [resolve(here, "src/preload.ts")],
  outfile: resolve(outdir, "preload.cjs"),
  platform: "node",
  format: "cjs",
  target: ["node22"],
  external: ["electron"],
};

const rendererOptions = {
  ...common,
  entryPoints: [resolve(here, "src/renderer/main.ts")],
  outfile: resolve(outdir, "renderer/renderer.js"),
  platform: "browser",
  format: "esm",
  target: ["chrome120"],
};

function copyStatic() {
  copyFileSync(resolve(here, "public/index.html"), resolve(outdir, "renderer/index.html"));
  copyFileSync(resolve(here, "public/styles.css"), resolve(outdir, "renderer/styles.css"));
  copyFileSync(resolve(here, "assets/tray.png"), resolve(outdir, "assets/tray.png"));
  copyFileSync(resolve(here, "assets/icon.png"), resolve(outdir, "assets/icon.png"));
  copyFileSync(resolve(here, "../../docs/test-vectors/synonyms.json"), resolve(outdir, "synonyms.json"));
}

copyStatic();

if (watch) {
  const contexts = await Promise.all([
    esbuild.context(mainOptions),
    esbuild.context(preloadOptions),
    esbuild.context({
      ...rendererOptions,
      plugins: [
        {
          name: "recall-static",
          setup(build) {
            build.onEnd(() => copyStatic());
          },
        },
      ],
    }),
  ]);
  await Promise.all(contexts.map((context) => context.watch()));
  console.log("watching apps/desktop…");
} else {
  await Promise.all([esbuild.build(mainOptions), esbuild.build(preloadOptions), esbuild.build(rendererOptions)]);
  console.log("built apps/desktop/dist — run with `pnpm --filter @recall/desktop start`");
}
