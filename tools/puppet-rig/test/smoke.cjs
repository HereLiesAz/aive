// Headless smoke test for the puppet rig PWA.
//   python3 -m http.server 8765 --directory tools/puppet-rig &
//   NODE_PATH="$(npm root -g)" node tools/puppet-rig/test/smoke.cjs http://localhost:8765/ out.png
// Imports a generated sheet, cuts two parts by dragging, parents one to the other, exports,
// validates the exported JSON with rig-core's validator and saves a screenshot of the Rig view.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

const url = process.argv[2] || 'http://localhost:8765/';
const shot = process.argv[3] || path.join(os.tmpdir(), 'puppet-rig.png');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, acceptDownloads: true });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url);
  await page.waitForFunction(() => window.puppetRig);

  // A generated 200x120 sheet: a grey circle "body" and a white bar "arm".
  const png = await page.evaluate(() => {
    const c = document.createElement('canvas');
    c.width = 200; c.height = 120;
    const g = c.getContext('2d');
    g.fillStyle = '#888'; g.beginPath(); g.arc(50, 60, 40, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#eee'; g.fillRect(120, 40, 60, 16);
    return c.toDataURL('image/png').split(',')[1];
  });
  await page.setInputFiles('#import', { name: 'sheet.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') });
  await page.waitForFunction(() => window.puppetRig.doc.sources.length === 1);

  // Drag two rectangles on the stage (sheet coordinates -> screen via the fitted view).
  const box = await page.locator('#stage').boundingBox();
  const view = await page.evaluate(() => {
    const r = document.querySelector('#stage').getBoundingClientRect();
    const s = Math.min((r.width - 40) / 200, (r.height - 40) / 120);
    return { s, ox: (r.width - 200 * s) / 2, oy: (r.height - 120 * s) / 2 };
  });
  const scr = (x, y) => [box.x + view.ox + x * view.s, box.y + view.oy + y * view.s];
  async function dragRect(x0, y0, x1, y1) {
    await page.mouse.move(...scr(x0, y0)); await page.mouse.down();
    await page.mouse.move(...scr((x0 + x1) / 2, (y0 + y1) / 2), { steps: 4 });
    await page.mouse.move(...scr(x1, y1), { steps: 4 }); await page.mouse.up();
  }
  await dragRect(10, 20, 90, 100);
  await dragRect(120, 40, 180, 56);
  const ids = await page.evaluate(() => window.puppetRig.doc.parts.map((p) => p.id));
  if (ids.length !== 2) throw new Error(`expected 2 parts, got ${ids}`);

  // Rename both through the UI, then parent arm -> body in the Rig tab.
  for (const [i, name] of [[0, 'body'], [1, 'arm']]) {
    await page.locator('#part-list li').nth(i).click();
    await page.fill('#part-name', name);
    await page.press('#part-name', 'Enter');
  }
  await page.click('.tabs button[data-mode=rig]');
  await page.locator('#part-list li', { hasText: 'arm' }).click();
  await page.selectOption('#parent', 'body');
  await page.fill('input[data-f="rest.rotation"]', '-30');
  // add an attachment point on the body
  await page.click('button[data-tool=attach]');
  const body = await page.evaluate(() => { const e = window.puppetRig.evaluate('Pending', 0).byId.body; return { x: e.m[4], y: e.m[5] }; });
  const rv = await page.evaluate(() => {
    const r = document.querySelector('#stage').getBoundingClientRect();
    const c = window.puppetRig.doc.canvas; const s = Math.min((r.width - 40) / c.width, (r.height - 40) / c.height);
    return { s, ox: (r.width - c.width * s) / 2, oy: (r.height - c.height * s) / 2 };
  });
  await page.mouse.click(box.x + rv.ox + (body.x - 30) * rv.s, box.y + rv.oy + body.y * rv.s);
  await page.click('button[data-tool=move]');

  // Animate: give the arm a rotation key + sine in Active.
  await page.click('.tabs button[data-mode=animate]');
  await page.selectOption('#state', 'Active');
  await page.locator('#part-list li', { hasText: 'arm' }).click();
  await page.click('button[data-act=add-key][data-ch=rotation]');
  await page.fill('tr[data-ch=rotation][data-sine] input[data-f=amplitude]', '12');
  await page.click('.tabs button[data-mode=rig]');
  await page.locator('#part-list li', { hasText: 'arm' }).click();

  // Export through the button and capture both downloads.
  const downloads = [];
  page.on('download', (d) => downloads.push(d));
  await page.click('#export');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.startsWith('Exported'));
  await page.waitForTimeout(500);
  const out = {};
  for (const d of downloads) { const p = path.join(os.tmpdir(), d.suggestedFilename()); await d.saveAs(p); out[d.suggestedFilename()] = p; }
  const jsonName = Object.keys(out).find((n) => n.endsWith('.rig.json'));
  if (!jsonName || !Object.keys(out).some((n) => n.endsWith('.png'))) throw new Error(`downloads: ${Object.keys(out)}`);
  const rig = JSON.parse(fs.readFileSync(out[jsonName], 'utf8'));
  const { validateRig } = await import(path.join(__dirname, '..', 'rig-core.js'));
  const errs = validateRig(rig);
  if (errs.length) throw new Error(`exported rig invalid: ${errs.join('; ')}`);
  const arm = rig.parts.find((p) => p.id === 'arm');
  if (arm.parent !== 'body') throw new Error('parent not exported');
  if (!rig.states.Active?.parts?.arm?.sine?.rotation) throw new Error('motion not exported');
  if (!rig.attachments.length) throw new Error('attachment not exported');

  // Edit an already-cut part: rigidity, extensible tail, parent connection and typed points.
  await page.locator('#part-list li', { hasText: 'arm' }).click();
  await page.selectOption('#part-kind', 'tail');
  await page.fill('input[data-f="rigidity"]', '0.5');
  await page.selectOption('#stretch-axis', 'y');
  await page.fill('input[data-f="stretch.length"]', '80');
  const bodySocket = await page.evaluate(() => window.puppetRig.doc.attachments.find((a) => a.part === 'body')?.id);
  if (!bodySocket) throw new Error('missing body socket for tail connection');
  await page.selectOption('#connection', bodySocket);

  const pose = await page.evaluate(() => window.puppetRig.evaluate('Active', 0.25));
  const angle = Math.atan2(pose.byId.arm.m[1], pose.byId.arm.m[0]) * 180 / Math.PI;
  // A tail never rotates: its base stays at rest -30°, and half (rigidity) the 12° sine at quarter
  // phase bends it instead, twice that across the arc.
  if (Math.abs(angle - (-30)) > 0.02) throw new Error(`tail rotated: ${angle}`);
  if (Math.abs(pose.byId.arm.bend - 12) > 0.02) throw new Error(`rigidity did not attenuate the bend: ${pose.byId.arm.bend}`);
  if (pose.byId.arm.h !== 80) throw new Error('tail was not stretched to 80px');

  await page.selectOption('#new-attachment-kind', 'tail-tip');
  await page.click('button[data-tool=attach]');
  const armPoint = await page.evaluate(() => {
    const e = window.puppetRig.evaluate('Pending', 0).byId.arm;
    return { x: e.m[4] + e.m[2] * 20, y: e.m[5] + e.m[3] * 20 };
  });
  await page.mouse.click(box.x + rv.ox + armPoint.x * rv.s, box.y + rv.oy + armPoint.y * rv.s);
  await page.click('button[data-tool=move]');
  const hasTip = await page.evaluate(() => window.puppetRig.doc.attachments.some((a) => a.part === 'arm' && a.kind === 'tail-tip'));
  if (!hasTip) throw new Error('tail-tip connection point not placed');

  const initialOrder = await page.evaluate(() => Object.fromEntries(window.puppetRig.doc.parts.map((p) => [p.id, p.z])));
  await page.click('button[data-act=layer-back]');
  const behind = await page.evaluate(() => Object.fromEntries(window.puppetRig.doc.parts.map((p) => [p.id, p.z])));
  if (!(behind.arm < behind.body && initialOrder.arm > initialOrder.body)) throw new Error('send backward failed');
  await page.click('button[data-act=layer-front]');

  await page.click('button[data-act=duplicate-part]');
  const duplicated = await page.evaluate(() => ({
    ids: window.puppetRig.doc.parts.map((p) => p.id),
    duplicate: window.puppetRig.doc.parts.find((p) => p.id === 'arm-copy'),
    newAttachments: window.puppetRig.doc.attachments.filter((a) => a.part === 'arm-copy'),
    newMotion: window.puppetRig.doc.states.Active.parts['arm-copy'],
  }));
  if (!duplicated.duplicate || duplicated.duplicate.connectTo !== bodySocket ||
      duplicated.duplicate.stretch.length !== 80 || duplicated.duplicate.rigidity !== 0.5 ||
      !duplicated.newAttachments.some((a) => a.kind === 'tail-tip') ||
      !duplicated.newMotion?.sine?.rotation) throw new Error('duplicate lost rig settings or animation');
  await page.selectOption('#part-kind', 'tendril');
  await page.locator('#part-list li', { hasText: 'arm' }).first().click();
  await page.click('button[data-act=delete-part]');
  const edited = await page.evaluate(() => window.puppetRig.buildExport().json);
  if (edited.parts.some((p) => p.id === 'arm') ||
      !edited.parts.some((p) => p.id === 'arm-copy' && p.kind === 'tendril') ||
      edited.attachments.some((a) => a.part === 'arm') ||
      !edited.states.Active.parts['arm-copy']) throw new Error('delete, reattribute or duplicate not persisted');
  const editedErrors = validateRig(edited);
  if (editedErrors.length) throw new Error(`edited export invalid: ${editedErrors.join('; ')}`);

  // Round-trip: import the layered UX Designer rig (JSON + atlas) and preview it.
  const rigs = path.join(__dirname, '..', '..', '..', 'shared', 'src', 'commonMain', 'composeResources', 'files', 'rigs');
  await page.setInputFiles('#import', [path.join(rigs, 'ux-designer.rig.json'), path.join(rigs, 'ux_designer_rig_atlas.png')]);
  await page.waitForFunction(() => window.puppetRig.doc.role === 'ux-designer');
  const n = await page.evaluate(() => window.puppetRig.doc.parts.length);
  if (n !== 20) throw new Error(`ux-designer import: ${n} parts`);
  const reexport = await page.evaluate(() => window.puppetRig.buildExport().json);
  const reErrs = validateRig(reexport);
  if (reErrs.length) throw new Error(`re-export invalid: ${reErrs.join('; ')}`);
  await page.click('.tabs button[data-mode=animate]');
  await page.selectOption('#state', 'Active');
  await page.locator('#part-list li', { hasText: 'tendril.1' }).click();
  await page.waitForTimeout(700);
  await page.screenshot({ path: shot.replace(/\.png$/, '-animate.png') });
  await page.click('.tabs button[data-mode=rig]');
  await page.locator('#part-list li', { hasText: 'soma' }).click();
  await page.screenshot({ path: shot });
  if (errors.length) throw new Error(`page errors: ${errors.join(' | ')}`);
  console.log(JSON.stringify({ ok: true, files: Object.keys(out), parts: rig.parts.map((p) => [p.id, p.parent, p.rect]), attachments: rig.attachments, atlas: rig.atlas, screenshot: shot }, null, 1));
  await browser.close();
})().catch((e) => { console.error('SMOKE FAIL', e); process.exit(1); });
