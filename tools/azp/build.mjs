// Pack every Azphalt Store package under docs/azphalt-packages/<slug>/ into a
// distributable `.azp`, written to build/azp/.
//
//   npm install && npm run build   →   build/azp/<slug>-<version>.azp   (78 of them)
//
// Each package directory holds an already-authored manifest.json whose `files`
// map lists its payload (workflows/*.json, roles/*.json) plus LICENSE.
// `writeAzp()` recomputes those integrity digests itself, so this script strips
// the authored `files` map, hands writeAzp the manifest + payload, and then
// asserts the recomputed digests match what was authored — a package whose
// manifest drifted from its payload fails the build instead of shipping.
//
// Output is UNSIGNED on purpose. Signing is centralized: HereLiesAz/workflows
// runs `azp-sign-release` with the publisher key after the release is published.
import fs from "node:fs";
import path from "node:path";
import { writeAzp, verifyAzp } from "@azphalt/azp";

const ROOT = path.resolve(import.meta.dirname, "../..");
const SRC = path.join(ROOT, "docs/azphalt-packages");
const OUT = path.join(ROOT, "build/azp");

const slugs = fs
  .readdirSync(SRC, { withFileTypes: true })
  .filter((e) => e.isDirectory() && fs.existsSync(path.join(SRC, e.name, "manifest.json")))
  .map((e) => e.name)
  .sort();

if (slugs.length === 0) {
  console.error(`No Azphalt packages found under ${path.relative(ROOT, SRC)}`);
  process.exit(1);
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const failures = [];
let built = 0;

for (const slug of slugs) {
  const dir = path.join(SRC, slug);
  try {
    const authored = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf-8"));
    const { files: authoredFiles, ...manifest } = authored;

    // Payload: every entry of the authored `files` map except LICENSE, which
    // writeAzp writes as the package's required LICENSE entry from `license`.
    const payload = {};
    for (const rel of Object.keys(authoredFiles ?? {})) {
      if (rel === "LICENSE") continue;
      if (rel.includes("..") || path.posix.isAbsolute(rel)) {
        throw new Error(`unsafe payload path: ${rel}`);
      }
      payload[rel] = fs.readFileSync(path.join(dir, rel));
    }

    const licensePath = path.join(dir, "LICENSE");
    const license = fs.existsSync(licensePath)
      ? fs.readFileSync(licensePath, "utf-8")
      : manifest.license || "All Rights Reserved";

    const { azp, manifest: finished } = writeAzp({ manifest, payload, license });

    // The authored digests must agree with the ones writeAzp computed.
    for (const [rel, digest] of Object.entries(authoredFiles ?? {})) {
      const actual = finished.files?.[rel];
      if (actual !== digest) {
        throw new Error(`digest drift for ${rel}: manifest says ${digest}, payload hashes to ${actual}`);
      }
    }

    const verdict = verifyAzp(azp);
    if (!verdict.ok) throw new Error(`verifyAzp failed: ${verdict.errors.join("; ")}`);

    const out = path.join(OUT, `${slug}-${manifest.version}.azp`);
    fs.writeFileSync(out, azp);
    built++;
    console.log(`built ${path.relative(ROOT, out)} (${azp.length} bytes, ${Object.keys(payload).length} payload files)`);
  } catch (err) {
    failures.push(`${slug}: ${err.message}`);
  }
}

console.log(`\n${built}/${slugs.length} packages built into ${path.relative(ROOT, OUT)}`);
if (failures.length) {
  console.error(`\n${failures.length} package(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
