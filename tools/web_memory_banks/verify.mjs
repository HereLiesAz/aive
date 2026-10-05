// Browser check of per-bank memory workers and the one-time split of the old shared store.
//
// Serves a built web distribution on localhost and drives it in headless Chromium through the
// dev-only `?aiveMemoryDebug=` hook (shared/src/webMain/.../memory/WebMemoryDebugHook.kt). No
// provider or API key is needed. See docs/architecture/LIVE_RUNTIME_ACCEPTANCE.md, "Browser memory
// banks", for how to build the distribution and run this.
//
//   node tools/web_memory_banks/verify.mjs webApp/build/dist/js/developmentExecutable
//
// Needs the `playwright` package (global installs work: NODE_PATH=$(npm root -g)) and a Chromium
// (PLAYWRIGHT_BROWSERS_PATH, or CHROMIUM_PATH for an explicit binary).
import { createServer } from "node:http";
import { readFile, mkdtemp, rm } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { extname, join, normalize, resolve } from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const distDir = resolve(process.argv[2] ?? "webApp/build/dist/js/developmentExecutable");
const types = {
  ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".wasm": "application/wasm",
  ".css": "text/css", ".json": "application/json", ".png": "image/png", ".svg": "image/svg+xml",
  ".webmanifest": "application/manifest+json", ".map": "application/json",
};

const server = createServer(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, "http://localhost").pathname)).replace(/^([/\\])+/, "");
  try {
    const body = await readFile(join(distDir, path || "index.html"));
    response.writeHead(200, { "content-type": types[extname(path || "index.html")] ?? "application/octet-stream" });
    response.end(body);
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const origin = `http://localhost:${server.address().port}`;

const failures = [];
const check = (ok, message) => {
  console.log(`${ok ? "PASS" : "FAIL"} ${message}`);
  if (!ok) failures.push(message);
};

const profile = await mkdtemp(join(tmpdir(), "aive-memory-banks-"));
const context = await chromium.launchPersistentContext(profile, {
  headless: true,
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ["--no-sandbox"],
});
// Record every Worker the page constructs, with its name (the bank it opens).
await context.addInitScript(() => {
  const Original = self.Worker;
  self.__aiveWorkers = [];
  self.Worker = class extends Original {
    constructor(url, options) {
      super(url, options);
      self.__aiveWorkers.push({ url: String(url), name: (options && options.name) || "" });
    }
  };
});

async function visit(commands) {
  const page = await context.newPage();
  const logs = [];
  page.on("console", (message) => logs.push(message.text()));
  page.on("pageerror", (error) => logs.push(`pageerror: ${error.message}`));
  await page.goto(`${origin}/?aiveMemoryDebug=${commands}`);
  const deadline = Date.now() + 90_000;
  let dump = null;
  while (Date.now() < deadline && !dump) {
    const line = logs.find((text) => text.startsWith("AIVE_MEMORY_DEBUG {"));
    if (line) dump = JSON.parse(line.slice("AIVE_MEMORY_DEBUG ".length));
    else await page.waitForTimeout(250);
  }
  const workers = await page.evaluate(() => self.__aiveWorkers.filter((w) => w.name === "" || w.name.startsWith("bank-")));
  const opfs = await page.evaluate(async () => {
    const names = [];
    for await (const name of (await navigator.storage.getDirectory()).keys()) names.push(name);
    return names.sort();
  });
  const fallbacks = logs.filter((text) => text.includes("Aive memory: SQLite unavailable"));
  await page.close();
  return { dump, workers, opfs, fallbacks, logs };
}

const ids = (dump, workflow) => (dump?.banks?.[workflow]?.episodes ?? []).slice().sort();

try {
  // 1. First start with an old shared store: split once into two banks, each on its own worker.
  const first = await visit("seed-legacy:one,dump");
  check(first.dump !== null, `first start dumped memory (${first.logs.filter((l) => l.startsWith("AIVE")).join(" | ")})`);
  check(first.fallbacks.length === 0, `first start opened SQLite in OPFS without fallback ${JSON.stringify(first.fallbacks)}`);
  const stemA = first.dump?.banks?.["wf-a"]?.stem;
  const stemB = first.dump?.banks?.["wf-b"]?.stem;
  const names = first.workers.map((w) => w.name);
  check(names.includes(""), "the old shared store was opened (unnamed worker) for the split");
  check(names.includes(`bank-${stemA}`) && names.includes(`bank-${stemB}`) && stemA !== stemB,
    `two bank workers bank-${stemA} and bank-${stemB} (workers: ${JSON.stringify(names)})`);
  check(first.opfs.some((n) => n.includes(stemA)) && first.opfs.some((n) => n.includes(stemB)),
    `one OPFS pool per bank (OPFS root: ${JSON.stringify(first.opfs)})`);
  check(JSON.stringify(ids(first.dump, "wf-a")) === JSON.stringify(["legacy-one-a"]), `bank wf-a holds only its split episode ${JSON.stringify(ids(first.dump, "wf-a"))}`);
  check(JSON.stringify(ids(first.dump, "wf-b")) === JSON.stringify(["legacy-one-b"]), `bank wf-b holds only its split episode ${JSON.stringify(ids(first.dump, "wf-b"))}`);
  check(first.dump?.migration?.episodesPerWorkflow?.["wf-a"] === 1 && first.dump?.migration?.episodesPerWorkflow?.["wf-b"] === 1,
    `split report recorded ${JSON.stringify(first.dump?.migration)}`);

  // 2. Reload with more old-store data and a write to one bank: no second split, banks isolated.
  const second = await visit("seed-legacy:two,write:wf-a:fresh-a,dump");
  check(second.dump !== null, "second start dumped memory");
  check(second.fallbacks.length === 0, `second start opened SQLite in OPFS without fallback ${JSON.stringify(second.fallbacks)}`);
  check(!second.workers.some((w) => w.name === ""), `the old shared store was not opened again (workers: ${JSON.stringify(second.workers.map((w) => w.name))})`);
  check(JSON.stringify(second.dump?.migration) === JSON.stringify(first.dump?.migration), "split report unchanged on reload");
  check(JSON.stringify(ids(second.dump, "wf-a")) === JSON.stringify(["fresh-a", "legacy-one-a"]), `wf-a persisted its split episode and the new write ${JSON.stringify(ids(second.dump, "wf-a"))}`);
  check(JSON.stringify(ids(second.dump, "wf-b")) === JSON.stringify(["legacy-one-b"]), `wf-b does not see wf-a's write ${JSON.stringify(ids(second.dump, "wf-b"))}`);
  check(!JSON.stringify(second.dump).includes("legacy-two"), "old-store data added after the split was not re-split into banks");

  // 3. One more reload: the write survives, still only in its own bank.
  const third = await visit("dump");
  check(JSON.stringify(ids(third.dump, "wf-a")) === JSON.stringify(["fresh-a", "legacy-one-a"]) &&
    JSON.stringify(ids(third.dump, "wf-b")) === JSON.stringify(["legacy-one-b"]), "banks persist across a further reload");
} finally {
  await context.close();
  server.close();
  await rm(profile, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`${failures.length} check(s) failed`);
  process.exit(1);
}
console.log("All browser memory bank checks passed");
