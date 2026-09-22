// Structural check over everything `npm run build` produced.
//   npm test   →   verifies every build/azp/*.azp with @azphalt/azp's verifyAzp
// Packages are unsigned at this stage (signing is centralized), so `signed`
// is reported but not required.
import fs from "node:fs";
import path from "node:path";
import { readAzp, verifyAzp } from "@azphalt/azp";

const OUT = path.resolve(import.meta.dirname, "../../build/azp");
if (!fs.existsSync(OUT)) {
  console.error(`No build output at ${OUT} — run \`npm run build\` first.`);
  process.exit(1);
}

const files = fs.readdirSync(OUT).filter((f) => f.endsWith(".azp")).sort();
const failures = [];

for (const f of files) {
  const bytes = fs.readFileSync(path.join(OUT, f));
  const verdict = verifyAzp(bytes);
  if (!verdict.ok) {
    failures.push(`${f}: ${verdict.errors.join("; ")}`);
    continue;
  }
  const { manifest } = readAzp(bytes);
  if (!manifest.id || !manifest.version || !manifest.kind) {
    failures.push(`${f}: manifest missing id/version/kind`);
  }
}

console.log(`verified ${files.length - failures.length}/${files.length} .azp packages`);
if (failures.length) {
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
