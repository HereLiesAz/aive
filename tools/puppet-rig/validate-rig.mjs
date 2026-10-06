#!/usr/bin/env node
// Validates aive-puppet-rig JSON files against the format (docs/architecture/PUPPET_RIG_FORMAT.md),
// using the same validator the editor runs before export. Optionally checks the atlas PNG size.
//   node tools/puppet-rig/validate-rig.mjs path/to/role.rig.json [...]
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { validateRig, evaluate, STATE_NAMES } from './rig-core.js';

function pngSize(path) {
  const b = readFileSync(path);
  if (b.toString('ascii', 1, 4) !== 'PNG') return null;
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) };
}

let failed = 0;
for (const file of process.argv.slice(2)) {
  const errs = [];
  let rig;
  try { rig = JSON.parse(readFileSync(file, 'utf8')); } catch (e) { errs.push(`unreadable: ${e.message}`); }
  if (rig) {
    errs.push(...validateRig(rig));
    const atlas = rig.atlas && join(dirname(file), rig.atlas.image);
    if (atlas && existsSync(atlas)) {
      const s = pngSize(atlas);
      if (!s || s.width !== rig.atlas.width || s.height !== rig.atlas.height) errs.push(`atlas ${atlas} size ${JSON.stringify(s)} != declared ${rig.atlas.width}x${rig.atlas.height}`);
    }
    if (!errs.length) for (const st of STATE_NAMES) evaluate(rig, st, 0.5); // must not throw
  }
  if (errs.length) { failed++; console.error(`FAIL ${file}\n  ${errs.join('\n  ')}`); } else console.log(`ok   ${file}`);
}
process.exit(failed ? 1 : 0);
