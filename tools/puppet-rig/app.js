// app.js — the puppet rig editor UI. Vanilla JS + Canvas 2D, no build step.
//
// Data flow: `doc` is the single editable document (serialisable; images live as data URLs in
// `doc.sources`). Every edit mutates `doc`, calls `changed()` (redraw + debounced autosave), and
// re-renders the side panel only when its structure changes. Export converts `doc` into the
// aive-puppet-rig JSON + a packed atlas PNG (see docs/architecture/PUPPET_RIG_FORMAT.md).

import * as core from './rig-core.js';

// ---------------------------------------------------------------- document & ui state

function emptyDoc() {
  return {
    role: 'creature',
    canvas: { width: 256, height: 256 },
    sources: [], // {id, name, url}
    parts: [], // {id, source, cut:{x,y,w,h}, poly:[{x,y}]|null, parent, z, pivot, size, rest}
    attachments: [], // {id, part, x, y, kind}
    states: {}, // stateName -> {parts: {partId: {keys:{ch:[{t,v,ease}]}, sine:{ch:{amplitude,frequency,phase}}}}}
    fallbackState: 'Pending',
  };
}

let doc = emptyDoc();
const ui = {
  mode: 'cut',
  source: null, // selected source id (cut mode)
  sel: null, // selected part id
  tool: { cut: 'rect', rig: 'move' },
  view: { s: 1, ox: 0, oy: 0 },
  fitted: { cut: false, rig: false },
  polyDraft: null,
  playing: true,
  phase: 0,
  state: 'Active',
  loopSeconds: 2.4,
};
const images = new Map(); // source id -> HTMLImageElement
const partCanvases = new Map(); // part id -> {key, canvas}

const $ = (sel) => document.querySelector(sel);
const stage = $('#stage');
const ctx = stage.getContext('2d');
const panel = $('#panel');

// ---------------------------------------------------------------- helpers

const uid = (prefix, taken) => {
  let i = 1;
  while (taken.has(`${prefix}${i}`)) i++;
  return `${prefix}${i}`;
};
const round = (v, d = 3) => Math.round(v * 10 ** d) / 10 ** d;
const partById = (id) => doc.parts.find((p) => p.id === id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), 2600);
}

function loadImage(url) {
  return new Promise((res, rej) => {
    const img = new Image();
    img.onload = () => res(img);
    img.onerror = () => rej(new Error('image failed to load'));
    img.src = url;
  });
}

const readAs = (file, how) => new Promise((res, rej) => {
  const r = new FileReader();
  r.onload = () => res(r.result);
  r.onerror = () => rej(r.error);
  how === 'text' ? r.readAsText(file) : r.readAsDataURL(file);
});

/** Cropped (and polygon-masked) bitmap for a part, cached by its cut geometry. */
function partCanvas(p) {
  const key = JSON.stringify([p.source, p.cut, p.poly]);
  const hit = partCanvases.get(p.id);
  if (hit && hit.key === key) return hit.canvas;
  const img = images.get(p.source);
  if (!img) return null;
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(p.cut.w));
  c.height = Math.max(1, Math.round(p.cut.h));
  const g = c.getContext('2d');
  if (p.poly && p.poly.length >= 3) {
    g.beginPath();
    p.poly.forEach((pt, i) => (i ? g.lineTo : g.moveTo).call(g, pt.x - p.cut.x, pt.y - p.cut.y));
    g.closePath();
    g.clip();
  }
  g.drawImage(img, p.cut.x, p.cut.y, p.cut.w, p.cut.h, 0, 0, p.cut.w, p.cut.h);
  partCanvases.set(p.id, { key, canvas: c });
  return c;
}

/** The doc viewed as a rig for the shared evaluator. Rig mode evaluates the rest pose. */
function rigForEval(withMotion) {
  return {
    canvas: doc.canvas,
    parts: doc.parts,
    attachments: doc.attachments,
    states: withMotion ? doc.states : {},
    fallbackState: withMotion ? doc.fallbackState : null,
  };
}
const currentPose = () => core.evaluate(rigForEval(ui.mode === 'animate'), ui.state, ui.phase);

function descendants(id) {
  const out = new Set();
  const walk = (pid) => doc.parts.filter((p) => p.parent === pid).forEach((c) => { out.add(c.id); walk(c.id); });
  walk(id);
  return out;
}

// ---------------------------------------------------------------- persistence (IndexedDB)

const DB = 'aive-puppet-rig';
function idb() {
  return new Promise((res, rej) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore('docs');
    req.onsuccess = () => res(req.result);
    req.onerror = () => rej(req.error);
  });
}
async function saveDoc() {
  try {
    const db = await idb();
    await new Promise((res, rej) => {
      const tx = db.transaction('docs', 'readwrite');
      tx.objectStore('docs').put(JSON.stringify(doc), 'current');
      tx.oncomplete = res;
      tx.onerror = () => rej(tx.error);
    });
  } catch (e) {
    // Private windows / blocked storage: editing keeps working, autosave is just off.
    console.warn('autosave failed', e);
  }
}
async function loadDoc() {
  try {
    const db = await idb();
    const text = await new Promise((res, rej) => {
      const req = db.transaction('docs').objectStore('docs').get('current');
      req.onsuccess = () => res(req.result);
      req.onerror = () => rej(req.error);
    });
    return text ? JSON.parse(text) : null;
  } catch (e) {
    console.warn('autosave load failed', e);
    return null;
  }
}
let saveTimer;
function changed() {
  draw();
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveDoc, 400);
}

async function setDoc(next) {
  doc = { ...emptyDoc(), ...next };
  images.clear();
  partCanvases.clear();
  for (const s of doc.sources) {
    try { images.set(s.id, await loadImage(s.url)); } catch (e) { console.warn(e); }
  }
  ui.source = doc.sources[0]?.id ?? null;
  ui.sel = doc.parts[0]?.id ?? null;
  ui.fitted = { cut: false, rig: false };
  renderPanel();
  changed();
}

// ---------------------------------------------------------------- import

async function importFiles(fileList) {
  const files = [...fileList];
  const jsons = files.filter((f) => f.name.endsWith('.json') || f.type === 'application/json');
  const pngs = files.filter((f) => f.type === 'image/png' || f.name.endsWith('.png'));
  if (jsons.length) return importRig(jsons[0], pngs);
  const added = [];
  for (const f of pngs) {
    const url = await readAs(f, 'url');
    const img = await loadImage(url);
    const id = uid('src', new Set(doc.sources.map((s) => s.id)));
    doc.sources.push({ id, name: f.name, url });
    images.set(id, img);
    added.push({ id, img, name: f.name });
  }
  if (!added.length) return toast('Nothing to import (PNG or rig JSON + atlas PNG).');
  ui.source = added[0].id;
  ui.fitted.cut = false;
  // Several PNGs at once = one pre-cut part per file.
  if (added.length > 1) {
    for (const a of added) {
      const pid = uniquePartId(a.name.replace(/\.png$/i, '').replace(/[^\w.-]+/g, '-'));
      addPart(pid, a.id, { x: 0, y: 0, w: a.img.width, h: a.img.height }, null);
    }
  }
  toast(`Imported ${added.length} image${added.length > 1 ? 's' : ''}.`);
  renderPanel();
  changed();
}

async function importRig(jsonFile, pngs) {
  let rig;
  try { rig = JSON.parse(await readAs(jsonFile, 'text')); } catch { return toast('Rig JSON is not valid JSON.'); }
  const errs = core.validateRig(rig);
  if (errs.length) return toast(`Invalid rig: ${errs[0]}`);
  const png = pngs.find((f) => f.name === rig.atlas.image) || (pngs.length === 1 ? pngs[0] : null);
  if (!png) return toast(`Select the atlas PNG (${rig.atlas.image}) together with the JSON.`);
  const url = await readAs(png, 'url');
  await setDoc({
    role: rig.role || 'creature',
    canvas: { ...rig.canvas },
    sources: [{ id: 'atlas', name: png.name, url }],
    parts: rig.parts.map((p) => {
      const sz = core.partSize(p);
      return {
        id: p.id,
        source: p.rect ? 'atlas' : null,
        cut: p.rect ? { ...p.rect } : null,
        poly: null,
        parent: p.parent ?? null,
        z: p.z ?? 0,
        pivot: { x: 0.5, y: 0.5, ...(p.pivot || {}) },
        size: { w: sz.w, h: sz.h },
        rest: { x: 0, y: 0, rotation: 0, scaleX: 1, scaleY: 1, alpha: 1, ...(p.rest || {}) },
      };
    }),
    attachments: (rig.attachments || []).map((a) => ({ kind: '', ...a })),
    states: structuredClone(rig.states || {}),
    fallbackState: rig.fallbackState ?? null,
  });
  toast(`Loaded rig "${rig.role}" with ${rig.parts.length} parts.`);
}

// ---------------------------------------------------------------- part operations

function uniquePartId(base) {
  const taken = new Set(doc.parts.map((p) => p.id));
  if (base && !taken.has(base)) return base;
  return uid(`${base || 'part'}-`, taken);
}

function addPart(id, source, cut, poly) {
  const n = doc.parts.length;
  const p = {
    id,
    source,
    cut: { x: Math.round(cut.x), y: Math.round(cut.y), w: Math.round(cut.w), h: Math.round(cut.h) },
    poly,
    parent: null,
    z: n,
    pivot: { x: 0.5, y: 0.5 },
    size: { w: Math.round(cut.w), h: Math.round(cut.h) },
    rest: { x: doc.canvas.width / 2 + (n % 5) * 6, y: doc.canvas.height / 2 + (n % 5) * 6, rotation: 0, scaleX: 1, scaleY: 1, alpha: 1 },
  };
  doc.parts.push(p);
  ui.sel = id;
  return p;
}

function renamePart(oldId, newId) {
  newId = newId.trim();
  if (!newId || newId === oldId) return;
  if (partById(newId)) return toast(`"${newId}" already exists.`);
  const p = partById(oldId);
  p.id = newId;
  doc.parts.forEach((q) => { if (q.parent === oldId) q.parent = newId; });
  doc.attachments.forEach((a) => { if (a.part === oldId) a.part = newId; });
  for (const s of Object.values(doc.states)) {
    if (s.parts?.[oldId]) { s.parts[newId] = s.parts[oldId]; delete s.parts[oldId]; }
  }
  partCanvases.delete(oldId);
  ui.sel = newId;
}

function deletePart(id) {
  const p = partById(id);
  doc.parts = doc.parts.filter((q) => q.id !== id);
  doc.parts.forEach((q) => { if (q.parent === id) q.parent = p.parent; });
  doc.attachments = doc.attachments.filter((a) => a.part !== id);
  for (const s of Object.values(doc.states)) delete s.parts?.[id];
  ui.sel = doc.parts[0]?.id ?? null;
}

/** Re-parent while keeping the part's current rest placement on screen. */
function setParent(id, parentId) {
  const p = partById(id);
  if (parentId === id || (parentId && descendants(id).has(parentId))) return toast('That would make a cycle.');
  const pose = core.evaluate(rigForEval(false), '', 0);
  const world = pose.byId[id].m;
  const parentM = parentId ? pose.byId[parentId].m : core.IDENTITY;
  const local = core.mul(core.invert(parentM), world);
  p.parent = parentId || null;
  p.rest.x = round(local[4]);
  p.rest.y = round(local[5]);
  p.rest.rotation = round((Math.atan2(local[1], local[0]) * 180) / Math.PI);
  p.rest.scaleX = round(Math.hypot(local[0], local[1]));
  p.rest.scaleY = round(Math.hypot(local[2], local[3]));
}

function partMotion(stateName, partId, create) {
  if (!doc.states[stateName]) { if (!create) return null; doc.states[stateName] = { parts: {} }; }
  const st = doc.states[stateName];
  st.parts ||= {};
  if (!st.parts[partId]) { if (!create) return null; st.parts[partId] = { keys: {}, sine: {} }; }
  const m = st.parts[partId];
  m.keys ||= {};
  m.sine ||= {};
  return m;
}

// ---------------------------------------------------------------- export

/** Shelf packing (tallest first). Returns {width, height, placements: Map id -> {x,y}}. */
function packShelves(items, pad = 2) {
  const sorted = [...items].sort((a, b) => b.h - a.h || b.w - a.w);
  const area = sorted.reduce((s, i) => s + (i.w + pad) * (i.h + pad), 0);
  const maxW = Math.max(...sorted.map((i) => i.w + pad * 2), 1);
  const width = Math.max(maxW, Math.ceil(Math.sqrt(area) * 1.15));
  let x = pad, y = pad, shelf = 0;
  const placements = new Map();
  for (const it of sorted) {
    if (x + it.w + pad > width) { x = pad; y += shelf + pad; shelf = 0; }
    placements.set(it.id, { x, y });
    x += it.w + pad;
    shelf = Math.max(shelf, it.h);
  }
  return { width, height: y + shelf + pad, placements };
}

/** Builds {json (object), fileName, atlasName, atlasCanvas}. */
function buildExport() {
  const role = (doc.role || 'creature').trim().replace(/\s+/g, '-');
  const atlasName = `${role.replace(/[^\w]+/g, '_')}_rig_atlas.png`;
  const drawable = doc.parts.filter((p) => p.cut && images.get(p.source));
  const items = drawable.map((p) => ({ id: p.id, w: Math.round(p.cut.w), h: Math.round(p.cut.h) }));
  const pack = packShelves(items.length ? items : [{ id: '_', w: 1, h: 1 }]);
  const atlas = document.createElement('canvas');
  atlas.width = pack.width;
  atlas.height = pack.height;
  const g = atlas.getContext('2d');
  for (const p of drawable) {
    const at = pack.placements.get(p.id);
    g.drawImage(partCanvas(p), at.x, at.y);
  }
  const states = {};
  for (const [name, st] of Object.entries(doc.states)) {
    const parts = {};
    for (const [pid, m] of Object.entries(st.parts || {})) {
      if (!partById(pid)) continue;
      const keys = Object.fromEntries(Object.entries(m.keys || {}).filter(([, k]) => k.length)
        .map(([ch, k]) => [ch, [...k].sort((a, b) => a.t - b.t).map((kf) => ({ t: kf.t, v: kf.v, ease: kf.ease || 'linear' }))]));
      const sine = Object.fromEntries(Object.entries(m.sine || {}).filter(([, s]) => s && s.amplitude));
      if (Object.keys(keys).length || Object.keys(sine).length) parts[pid] = { keys, sine };
    }
    if (Object.keys(parts).length) states[name] = { parts };
  }
  const json = {
    format: core.FORMAT,
    version: core.VERSION,
    role,
    canvas: { width: doc.canvas.width, height: doc.canvas.height },
    atlas: { image: atlasName, width: atlas.width, height: atlas.height },
    parts: doc.parts.map((p) => {
      const at = pack.placements.get(p.id);
      const out = { id: p.id, parent: p.parent ?? null, z: p.z };
      if (at) out.rect = { x: at.x, y: at.y, w: Math.round(p.cut.w), h: Math.round(p.cut.h) };
      out.size = { w: round(p.size.w), h: round(p.size.h) };
      out.pivot = { x: round(p.pivot.x, 4), y: round(p.pivot.y, 4) };
      out.rest = Object.fromEntries(Object.entries(p.rest).map(([k, v]) => [k, round(v)]));
      return out;
    }),
    attachments: doc.attachments.map((a) => ({ id: a.id, part: a.part, x: round(a.x, 4), y: round(a.y, 4), kind: a.kind || '' })),
    states,
    fallbackState: doc.fallbackState || null,
  };
  return { json, fileName: `${role}.rig.json`, atlasName, atlasCanvas: atlas };
}

function download(name, blob) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}

async function exportRig() {
  const out = buildExport();
  const errs = core.validateRig(out.json);
  if (errs.length) return toast(`Export blocked: ${errs[0]}`);
  download(out.fileName, new Blob([JSON.stringify(out.json, null, 2)], { type: 'application/json' }));
  const png = await new Promise((res) => out.atlasCanvas.toBlob(res, 'image/png'));
  download(out.atlasName, png);
  toast(`Exported ${out.fileName} + ${out.atlasName}`);
}

// ---------------------------------------------------------------- drawing

function resize() {
  const dpr = window.devicePixelRatio || 1;
  const r = stage.getBoundingClientRect();
  stage.width = Math.max(1, Math.round(r.width * dpr));
  stage.height = Math.max(1, Math.round(r.height * dpr));
  draw();
}

function fitView(w, h) {
  const r = stage.getBoundingClientRect();
  const s = Math.min((r.width - 40) / w, (r.height - 40) / h) || 1;
  ui.view = { s, ox: (r.width - w * s) / 2, oy: (r.height - h * s) / 2 };
}
const toWorld = (sx, sy) => ({ x: (sx - ui.view.ox) / ui.view.s, y: (sy - ui.view.oy) / ui.view.s });
const toScreen = (x, y) => ({ x: x * ui.view.s + ui.view.ox, y: y * ui.view.s + ui.view.oy });

function draw() {
  const dpr = window.devicePixelRatio || 1;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, stage.width, stage.height);
  if (ui.mode === 'cut') drawCut(dpr); else drawRig(dpr);
}

function drawCut(dpr) {
  const img = images.get(ui.source);
  if (!img) return drawHint(dpr, 'Import a character sheet PNG (or several part PNGs).');
  if (!ui.fitted.cut) { fitView(img.width, img.height); ui.fitted.cut = true; }
  const { s, ox, oy } = ui.view;
  ctx.setTransform(dpr * s, 0, 0, dpr * s, dpr * ox, dpr * oy);
  ctx.imageSmoothingEnabled = s < 1;
  ctx.drawImage(img, 0, 0);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  for (const p of doc.parts.filter((q) => q.source === ui.source && q.cut)) {
    const a = toScreen(p.cut.x, p.cut.y);
    ctx.strokeStyle = p.id === ui.sel ? '#fff' : 'rgba(255,255,255,.45)';
    ctx.lineWidth = p.id === ui.sel ? 2 : 1;
    ctx.setLineDash(p.id === ui.sel ? [] : [4, 3]);
    if (p.poly) {
      ctx.beginPath();
      p.poly.forEach((pt, i) => { const q = toScreen(pt.x, pt.y); i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y); });
      ctx.closePath();
      ctx.stroke();
    } else {
      ctx.strokeRect(a.x, a.y, p.cut.w * s, p.cut.h * s);
    }
    label(p.id, a.x + 3, a.y + 12);
  }
  ctx.setLineDash([]);
  if (drag?.kind === 'cut-rect') {
    const r = normRect(drag.a, drag.b);
    const a = toScreen(r.x, r.y);
    ctx.strokeStyle = '#fff';
    ctx.strokeRect(a.x, a.y, r.w * s, r.h * s);
  }
  if (ui.polyDraft?.length) {
    ctx.strokeStyle = '#fff';
    ctx.beginPath();
    ui.polyDraft.forEach((pt, i) => { const q = toScreen(pt.x, pt.y); i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y); });
    ctx.stroke();
    ui.polyDraft.forEach((pt) => { const q = toScreen(pt.x, pt.y); dot(q.x, q.y, 3, '#fff'); });
  }
}

function drawRig(dpr) {
  const W = doc.canvas.width, H = doc.canvas.height;
  if (!ui.fitted.rig) { fitView(W, H); ui.fitted.rig = true; }
  const { s, ox, oy } = ui.view;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.strokeStyle = 'rgba(255,255,255,.25)';
  ctx.setLineDash([6, 4]);
  ctx.strokeRect(ox, oy, W * s, H * s);
  ctx.setLineDash([]);
  if (!doc.parts.length) return drawHint(dpr, 'Cut some parts first (Cut tab).');
  const pose = currentPose();
  const view = [dpr * s, 0, 0, dpr * s, dpr * ox, dpr * oy];
  for (const e of pose.parts) {
    const c = e.part.cut && partCanvas(e.part);
    if (!c || e.alpha <= 0) continue;
    const m = core.mul(view, e.m);
    ctx.setTransform(m[0], m[1], m[2], m[3], m[4], m[5]);
    ctx.globalAlpha = e.alpha;
    ctx.drawImage(c, e.boxLeft, e.boxTop, e.w, e.h);
  }
  ctx.globalAlpha = 1;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  // bones: parent pivot -> child pivot
  ctx.strokeStyle = 'rgba(255,255,255,.35)';
  for (const e of pose.parts) {
    if (!e.part.parent) continue;
    const a = pivotScreen(pose.byId[e.part.parent]), b = pivotScreen(e);
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
  }
  const sel = pose.byId[ui.sel];
  if (sel) {
    const m = core.mul([s, 0, 0, s, ox, oy], sel.m);
    ctx.setTransform(dpr * m[0], dpr * m[1], dpr * m[2], dpr * m[3], dpr * m[4], dpr * m[5]);
    ctx.lineWidth = 1 / Math.max(0.01, Math.hypot(m[0], m[1]));
    ctx.strokeStyle = '#fff';
    ctx.strokeRect(sel.boxLeft, sel.boxTop, sel.w, sel.h);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.lineWidth = 1;
    const pv = pivotScreen(sel), rh = rotateHandle(sel);
    ctx.beginPath(); ctx.moveTo(pv.x, pv.y); ctx.lineTo(rh.x, rh.y); ctx.stroke();
    dot(rh.x, rh.y, 7, '#000', '#fff');
    dot(pv.x, pv.y, 5, '#fff', '#000');
  }
  for (const [id, pt] of Object.entries(pose.attachments)) {
    const q = toScreen(pt.x, pt.y);
    ctx.strokeStyle = '#fff';
    ctx.beginPath(); ctx.moveTo(q.x - 5, q.y); ctx.lineTo(q.x + 5, q.y); ctx.moveTo(q.x, q.y - 5); ctx.lineTo(q.x, q.y + 5); ctx.stroke();
    label(id, q.x + 6, q.y - 6);
  }
}

const pivotScreen = (e) => toScreen(e.m[4], e.m[5]);
function rotateHandle(e) {
  const p = pivotScreen(e);
  const a = Math.atan2(e.m[1], e.m[0]) - Math.PI / 2; // local "up"
  return { x: p.x + Math.cos(a) * 44, y: p.y + Math.sin(a) * 44 };
}
function dot(x, y, r, fill, stroke) {
  ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fillStyle = fill; ctx.fill();
  if (stroke) { ctx.strokeStyle = stroke; ctx.stroke(); }
}
function label(text, x, y) {
  ctx.font = '11px ui-sans-serif, system-ui, sans-serif';
  ctx.fillStyle = 'rgba(0,0,0,.7)';
  const w = ctx.measureText(text).width;
  ctx.fillRect(x - 2, y - 10, w + 4, 13);
  ctx.fillStyle = '#fff';
  ctx.fillText(text, x, y);
}
function drawHint(dpr, text) {
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = '#8a8a8a';
  ctx.font = '13px ui-sans-serif, system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(text, stage.clientWidth / 2, stage.clientHeight / 2);
  ctx.textAlign = 'start';
}
const normRect = (a, b) => ({ x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(a.x - b.x), h: Math.abs(a.y - b.y) });

// ---------------------------------------------------------------- pointer interaction

let drag = null;
const pointers = new Map();

function local(ev) {
  const r = stage.getBoundingClientRect();
  return { x: ev.clientX - r.left, y: ev.clientY - r.top };
}

function hitPart(pose, w) {
  for (let i = pose.parts.length - 1; i >= 0; i--) {
    const e = pose.parts[i];
    if (!e.part.cut) continue;
    const l = core.apply(core.invert(e.m), w.x, w.y);
    if (l.x >= e.boxLeft && l.x <= e.boxLeft + e.w && l.y >= e.boxTop && l.y <= e.boxTop + e.h) return e;
  }
  return null;
}

stage.addEventListener('pointerdown', (ev) => {
  stage.setPointerCapture(ev.pointerId);
  pointers.set(ev.pointerId, local(ev));
  const sp = local(ev), w = toWorld(sp.x, sp.y);
  if (pointers.size === 2) { drag = { kind: 'pinch', start: [...pointers.values()], view: { ...ui.view } }; return; }
  if (ev.button === 1 || ev.button === 2 || ev.shiftKey) { drag = { kind: 'pan', sp, view: { ...ui.view } }; return; }

  if (ui.mode === 'cut') {
    if (!images.get(ui.source)) return;
    const tool = ui.tool.cut;
    if (tool === 'poly') {
      const d = ui.polyDraft ||= [];
      const first = d[0] && toScreen(d[0].x, d[0].y);
      if (first && d.length >= 3 && Math.hypot(first.x - sp.x, first.y - sp.y) < 10) finishPoly();
      else d.push({ x: Math.round(w.x), y: Math.round(w.y) });
      draw();
      return;
    }
    if (tool === 'select') {
      const p = [...doc.parts].reverse().find((q) => q.source === ui.source && q.cut &&
        w.x >= q.cut.x && w.x <= q.cut.x + q.cut.w && w.y >= q.cut.y && w.y <= q.cut.y + q.cut.h);
      ui.sel = p?.id ?? null;
      if (p) drag = { kind: 'cut-move', w, cut: { ...p.cut }, poly: p.poly?.map((pt) => ({ ...pt })), part: p };
      else drag = { kind: 'pan', sp, view: { ...ui.view } };
      renderPanel(); draw();
      return;
    }
    drag = { kind: 'cut-rect', a: w, b: w };
    return;
  }

  const pose = currentPose();
  const sel = pose.byId[ui.sel];
  if (ui.mode === 'rig' && ui.tool.rig === 'attach') {
    const e = (sel && hitPart({ parts: [sel] }, w)) || hitPart(pose, w);
    if (!e) return toast('Tap inside a part to add an attachment point.');
    const l = core.apply(core.invert(e.m), w.x, w.y);
    const id = uid('attach.', new Set(doc.attachments.map((a) => a.id)));
    doc.attachments.push({ id, part: e.part.id, x: round(e.part.pivot.x + l.x / e.w, 4), y: round(e.part.pivot.y + l.y / e.h, 4), kind: 'arm-socket' });
    ui.sel = e.part.id;
    renderPanel(); changed();
    return;
  }
  if (sel && ui.mode === 'rig') {
    const rh = rotateHandle(sel), pv = pivotScreen(sel);
    if (Math.hypot(rh.x - sp.x, rh.y - sp.y) < 14) {
      drag = { kind: 'rotate', start: Math.atan2(sp.y - pv.y, sp.x - pv.x), rot: partById(ui.sel).rest.rotation };
      return;
    }
    if (Math.hypot(pv.x - sp.x, pv.y - sp.y) < 10) { drag = { kind: 'pivot' }; return; }
  }
  const hit = hitPart(pose, w);
  if (hit) {
    ui.sel = hit.part.id;
    renderPanel();
    if (ui.mode === 'rig') {
      const parent = hit.part.parent ? pose.byId[hit.part.parent].m : core.IDENTITY;
      drag = { kind: 'move', w, rest: { ...hit.part.rest }, inv: core.invert(parent) };
    }
    draw();
  } else {
    drag = { kind: 'pan', sp, view: { ...ui.view } };
  }
});

stage.addEventListener('pointermove', (ev) => {
  if (pointers.has(ev.pointerId)) pointers.set(ev.pointerId, local(ev));
  if (!drag) return;
  const sp = local(ev), w = toWorld(sp.x, sp.y);
  const p = partById(ui.sel);
  switch (drag.kind) {
    case 'pinch': {
      const [a0, b0] = drag.start, [a1, b1] = [...pointers.values()];
      if (!a1 || !b1) return;
      const k = Math.hypot(a1.x - b1.x, a1.y - b1.y) / Math.max(1, Math.hypot(a0.x - b0.x, a0.y - b0.y));
      const c0 = { x: (a0.x + b0.x) / 2, y: (a0.y + b0.y) / 2 }, c1 = { x: (a1.x + b1.x) / 2, y: (a1.y + b1.y) / 2 };
      ui.view = { s: drag.view.s * k, ox: c1.x - (c0.x - drag.view.ox) * k, oy: c1.y - (c0.y - drag.view.oy) * k };
      break;
    }
    case 'pan':
      ui.view = { ...drag.view, ox: drag.view.ox + sp.x - drag.sp.x, oy: drag.view.oy + sp.y - drag.sp.y };
      break;
    case 'cut-rect':
      drag.b = w;
      break;
    case 'cut-move': {
      const dx = Math.round(w.x - drag.w.x), dy = Math.round(w.y - drag.w.y);
      drag.part.cut = { ...drag.cut, x: drag.cut.x + dx, y: drag.cut.y + dy };
      if (drag.poly) drag.part.poly = drag.poly.map((pt) => ({ x: pt.x + dx, y: pt.y + dy }));
      break;
    }
    case 'move': {
      const a = core.apply(drag.inv, drag.w.x, drag.w.y), b = core.apply(drag.inv, w.x, w.y);
      p.rest.x = round(drag.rest.x + b.x - a.x, 2);
      p.rest.y = round(drag.rest.y + b.y - a.y, 2);
      syncInspector();
      break;
    }
    case 'rotate': {
      const pv = pivotScreen(currentPose().byId[ui.sel]);
      const ang = Math.atan2(sp.y - pv.y, sp.x - pv.x);
      let deg = drag.rot + ((ang - drag.start) * 180) / Math.PI;
      if (ev.shiftKey) deg = Math.round(deg / 15) * 15;
      p.rest.rotation = round(((deg + 540) % 360) - 180, 2);
      syncInspector();
      break;
    }
    case 'pivot':
      movePivotTo(p, w);
      syncInspector();
      break;
  }
  draw();
});

function endDrag(ev) {
  pointers.delete(ev.pointerId);
  if (!drag) return;
  if (drag.kind === 'cut-rect') {
    const r = normRect(drag.a, drag.b);
    if (r.w >= 3 && r.h >= 3) {
      addPart(uniquePartId('part'), ui.source, r, null);
      renderPanel();
    }
  }
  const was = drag.kind;
  drag = null;
  if (was !== 'pan' && was !== 'pinch') changed(); else draw();
}
stage.addEventListener('pointerup', endDrag);
stage.addEventListener('pointercancel', endDrag);
stage.addEventListener('contextmenu', (e) => e.preventDefault());
stage.addEventListener('wheel', (ev) => {
  ev.preventDefault();
  const sp = local(ev), k = Math.exp(-ev.deltaY * 0.0015);
  ui.view = { s: ui.view.s * k, ox: sp.x - (sp.x - ui.view.ox) * k, oy: sp.y - (sp.y - ui.view.oy) * k };
  draw();
}, { passive: false });

/** Moves the pivot to world point w without moving the drawn box (exact compensation). */
function movePivotTo(p, w) {
  const pose = core.evaluate(rigForEval(false), '', 0);
  const e = pose.byId[p.id];
  const l = core.apply(core.invert(e.m), w.x, w.y);
  if (!e.w || !e.h) return;
  p.pivot = { x: round(p.pivot.x + l.x / e.w, 4), y: round(p.pivot.y + l.y / e.h, 4) };
  const parent = p.parent ? pose.byId[p.parent].m : core.IDENTITY;
  const at = core.apply(core.invert(parent), w.x, w.y);
  p.rest.x = round(at.x, 2);
  p.rest.y = round(at.y, 2);
}

function finishPoly() {
  const d = ui.polyDraft;
  ui.polyDraft = null;
  if (!d || d.length < 3) return;
  const xs = d.map((p) => p.x), ys = d.map((p) => p.y);
  const r = { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
  if (r.w < 3 || r.h < 3) return;
  addPart(uniquePartId('part'), ui.source, r, d);
  renderPanel();
  changed();
}

// ---------------------------------------------------------------- panel rendering

function setMode(mode) {
  ui.mode = mode;
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  $('#transport').hidden = mode !== 'animate';
  ui.fitted[mode === 'cut' ? 'cut' : 'rig'] = false;
  renderPanel();
  requestAnimationFrame(resize);
}

function renderStageTools() {
  const t = $('#stage-tools');
  const btn = (id, text, on) => `<button class="btn${on ? ' on' : ''}" data-tool="${id}">${text}</button>`;
  if (ui.mode === 'cut') {
    t.innerHTML = btn('rect', 'Rectangle', ui.tool.cut === 'rect') + btn('poly', 'Polygon', ui.tool.cut === 'poly') +
      btn('select', 'Select / move', ui.tool.cut === 'select') +
      (ui.tool.cut === 'poly' ? '<button class="btn" data-act="close-poly">Close polygon</button>' : '') +
      '<button class="btn ghost" data-act="fit">Fit</button><span class="small">shift-drag / two fingers to pan, wheel or pinch to zoom</span>';
  } else if (ui.mode === 'rig') {
    t.innerHTML = btn('move', 'Move / rotate / pivot', ui.tool.rig === 'move') + btn('attach', 'Add attachment', ui.tool.rig === 'attach') +
      '<button class="btn ghost" data-act="fit">Fit</button><span class="small">drag part = move · white ring = rotate (shift snaps) · centre dot = pivot</span>';
  } else {
    t.innerHTML = '<button class="btn ghost" data-act="fit">Fit</button><span class="small">tap a part to edit its motion for the selected state</span>';
  }
}

$('#stage-tools').addEventListener('click', (ev) => {
  const b = ev.target.closest('button');
  if (!b) return;
  if (b.dataset.tool) {
    ui.tool[ui.mode] = b.dataset.tool;
    if (b.dataset.tool !== 'poly') ui.polyDraft = null;
    renderStageTools();
  }
  if (b.dataset.act === 'fit') { ui.fitted[ui.mode === 'cut' ? 'cut' : 'rig'] = false; draw(); }
  if (b.dataset.act === 'close-poly') finishPoly();
});

function partListHtml(tree) {
  if (!doc.parts.length) return '<p class="small">No parts yet.</p>';
  const rows = [];
  const walk = (parent, depth) => {
    doc.parts.filter((p) => (p.parent ?? null) === parent).sort((a, b) => a.z - b.z).forEach((p) => {
      rows.push(`<li data-part="${esc(p.id)}" class="${p.id === ui.sel ? 'sel' : ''}" style="padding-left:${8 + depth * 14}px">${esc(p.id)}<span class="meta">z ${p.z}</span></li>`);
      walk(p.id, depth + 1);
    });
  };
  if (tree) walk(null, 0);
  else doc.parts.forEach((p) => rows.push(`<li data-part="${esc(p.id)}" class="${p.id === ui.sel ? 'sel' : ''}">${esc(p.id)}<span class="meta">${p.poly ? 'poly' : 'rect'} ${p.cut ? `${p.cut.w}×${p.cut.h}` : ''}</span></li>`));
  return `<ul class="list" id="part-list">${rows.join('')}</ul>`;
}

const num = (field, value, step = 1, extra = '') =>
  `<input type="number" data-f="${field}" value="${value}" step="${step}" ${extra}>`;

function renderPanel() {
  renderStageTools();
  const p = partById(ui.sel);
  let html = '';
  if (ui.mode === 'cut') {
    html += '<h3>Sheets</h3>';
    html += doc.sources.length
      ? `<ul class="list" id="source-list">${doc.sources.map((s) => `<li data-source="${esc(s.id)}" class="${s.id === ui.source ? 'sel' : ''}">${esc(s.name)}<span class="meta">${images.get(s.id)?.width ?? '?'}×${images.get(s.id)?.height ?? '?'}</span></li>`).join('')}</ul>`
      : '<p class="small">Import a PNG character sheet, or several part PNGs at once (each becomes a part).</p>';
    html += '<h3>Parts</h3>' + partListHtml(false);
    if (p) {
      html += `<h3>Selected part</h3>
        <div class="row"><label>name</label><input type="text" id="part-name" value="${esc(p.id)}"></div>
        ${p.cut ? `<div class="row"><label>cut</label>${num('cut.x', p.cut.x)}${num('cut.y', p.cut.y)}</div>
        <div class="row"><label></label>${num('cut.w', p.cut.w, 1, 'min=1')}${num('cut.h', p.cut.h, 1, 'min=1')}</div>` : ''}
        <div class="row"><button class="btn" data-act="delete-part">Delete part</button></div>`;
    }
  } else if (ui.mode === 'rig') {
    html += `<h3>Creature</h3>
      <div class="row"><label>role</label><input type="text" id="role" value="${esc(doc.role)}"></div>
      <div class="row"><label>artboard</label>${num('canvas.width', doc.canvas.width, 1, 'min=1')}${num('canvas.height', doc.canvas.height, 1, 'min=1')}</div>
      <h3>Hierarchy</h3>${partListHtml(true)}`;
    if (p) {
      const banned = descendants(p.id);
      const opts = ['<option value="">(none)</option>', ...doc.parts.filter((q) => q.id !== p.id && !banned.has(q.id))
        .map((q) => `<option value="${esc(q.id)}" ${q.id === p.parent ? 'selected' : ''}>${esc(q.id)}</option>`)].join('');
      html += `<h3>${esc(p.id)}</h3>
        <div class="row"><label>parent</label><select id="parent">${opts}</select></div>
        <div class="row"><label>z</label>${num('z', p.z, 1)}</div>
        <div class="row"><label>pivot</label>${num('pivot.x', p.pivot.x, 0.01)}${num('pivot.y', p.pivot.y, 0.01)}</div>
        <div class="row"><label>x, y</label>${num('rest.x', p.rest.x, 0.5)}${num('rest.y', p.rest.y, 0.5)}</div>
        <div class="row"><label>rotation</label>${num('rest.rotation', p.rest.rotation, 1)}</div>
        <div class="row"><label>scale</label>${num('rest.scaleX', p.rest.scaleX, 0.01)}${num('rest.scaleY', p.rest.scaleY, 0.01)}</div>
        <div class="row"><label>alpha</label>${num('rest.alpha', p.rest.alpha, 0.05, 'min=0 max=1')}</div>
        <div class="row"><label>size</label>${num('size.w', p.size.w, 0.5)}${num('size.h', p.size.h, 0.5)}
          ${p.cut ? '<button class="btn ghost" data-act="reset-size">1:1</button>' : ''}</div>
        <h3>Attachment points</h3>
        ${doc.attachments.filter((a) => a.part === p.id).map((a) => `
          <div class="row" data-attach="${esc(a.id)}"><input type="text" data-af="id" value="${esc(a.id)}">
            <select data-af="kind">${['arm-socket', 'prop-slot', 'eye', 'other'].map((k) => `<option ${k === a.kind ? 'selected' : ''}>${k}</option>`).join('')}</select>
            <input type="number" data-af="x" value="${a.x}" step="0.01"><input type="number" data-af="y" value="${a.y}" step="0.01">
            <button class="btn ghost" data-act="del-attach">×</button></div>`).join('') || '<p class="small">Use "Add attachment" and tap the part.</p>'}`;
    }
  } else {
    html += `<h3>Fallback state</h3><div class="row"><select id="fallback">${['', ...core.STATE_NAMES]
      .map((s) => `<option value="${s}" ${s === (doc.fallbackState ?? '') ? 'selected' : ''}>${s || '(rest pose)'}</option>`).join('')}</select></div>
      <h3>Parts</h3>${partListHtml(true)}`;
    if (p) html += motionEditorHtml(p);
  }
  panel.innerHTML = html;
}

function motionEditorHtml(p) {
  const m = partMotion(ui.state, p.id, false);
  const rows = core.CHANNELS.map((ch) => {
    const keys = m?.keys?.[ch] || [];
    const s = m?.sine?.[ch];
    return `<tr><td colspan="4"><strong>${ch}</strong> <span class="small">${['x', 'y', 'rotation'].includes(ch) ? 'adds' : 'multiplies'}</span>
        <button class="btn ghost" data-act="add-key" data-ch="${ch}">+ key @ phase</button></td></tr>
      ${keys.map((k, i) => `<tr data-ch="${ch}" data-k="${i}"><td>${num('t', k.t, 0.01, 'min=0 max=1')}</td><td>${num('v', k.v, 0.1)}</td>
        <td><select data-f="ease">${core.EASES.map((e) => `<option ${e === (k.ease || 'linear') ? 'selected' : ''}>${e}</option>`).join('')}</select></td>
        <td><button class="btn ghost" data-act="del-key">×</button></td></tr>`).join('')}
      <tr data-ch="${ch}" data-sine="1"><td colspan="4"><span class="small">sine</span>
        amp ${num('amplitude', s?.amplitude ?? 0, 0.1)} freq ${num('frequency', s?.frequency ?? 1, 1)} phase ${num('phase', s?.phase ?? 0, 0.05)}</td></tr>`;
  }).join('');
  return `<h3>${esc(p.id)} · ${ui.state}</h3>
    <div class="row"><button class="btn" data-act="blink">Blink keys (scaleY)</button><button class="btn ghost" data-act="clear-motion">Clear</button>
      <button class="btn ghost" data-act="copy-state">Copy state to empty states</button></div>
    <table class="keys">${rows}</table>`;
}

// ---- panel events (delegated)

panel.addEventListener('click', (ev) => {
  const li = ev.target.closest('li[data-part]');
  if (li) { ui.sel = li.dataset.part; renderPanel(); draw(); return; }
  const src = ev.target.closest('li[data-source]');
  if (src) { ui.source = src.dataset.source; ui.fitted.cut = false; renderPanel(); draw(); return; }
  const b = ev.target.closest('button[data-act]');
  if (!b) return;
  const p = partById(ui.sel);
  const act = b.dataset.act;
  if (act === 'delete-part' && p) deletePart(p.id);
  if (act === 'reset-size' && p) { p.size = { w: p.cut.w, h: p.cut.h }; }
  if (act === 'del-attach') doc.attachments = doc.attachments.filter((a) => a.id !== b.closest('[data-attach]').dataset.attach);
  if (act === 'add-key' && p) {
    const ch = b.dataset.ch;
    const m = partMotion(ui.state, p.id, true);
    const t = round(ui.phase, 3);
    const v = round(core.channelValue({ keys: m.keys, sine: {} }, ch, t), 3);
    (m.keys[ch] ||= []).push({ t, v, ease: 'easeInOut' });
  }
  if (act === 'del-key' && p) {
    const tr = b.closest('tr');
    partMotion(ui.state, p.id, true).keys[tr.dataset.ch].splice(+tr.dataset.k, 1);
  }
  if (act === 'blink' && p) {
    partMotion(ui.state, p.id, true).keys.scaleY = [
      { t: 0, v: 1, ease: 'step' }, { t: 0.9, v: 1, ease: 'easeIn' }, { t: 0.94, v: 0.1, ease: 'easeOut' }, { t: 0.98, v: 1, ease: 'step' }];
  }
  if (act === 'clear-motion' && p && doc.states[ui.state]?.parts) delete doc.states[ui.state].parts[p.id];
  if (act === 'copy-state') {
    const src = doc.states[ui.state];
    if (src) for (const s of core.STATE_NAMES) if (!doc.states[s] || !Object.keys(doc.states[s].parts || {}).length) doc.states[s] = structuredClone(src);
    toast('Copied to every state without motion.');
  }
  renderPanel();
  changed();
});

panel.addEventListener('input', (ev) => {
  const el = ev.target;
  const p = partById(ui.sel);
  const v = parseFloat(el.value);
  if (el.id === 'role') { doc.role = el.value; return changed(); }
  const tr = el.closest('tr[data-ch]');
  if (tr && p && el.dataset.f) {
    const m = partMotion(ui.state, p.id, true);
    if (tr.dataset.sine) {
      const s = (m.sine[tr.dataset.ch] ||= { amplitude: 0, frequency: 1, phase: 0 });
      if (Number.isFinite(v)) s[el.dataset.f] = v;
    } else {
      const k = m.keys[tr.dataset.ch][+tr.dataset.k];
      if (el.dataset.f === 'ease') k.ease = el.value; else if (Number.isFinite(v)) k[el.dataset.f] = el.dataset.f === 't' ? Math.min(1, Math.max(0, v)) : v;
    }
    return changed();
  }
  const ar = el.closest('[data-attach]');
  if (ar) {
    const a = doc.attachments.find((x) => x.id === ar.dataset.attach);
    if (el.dataset.af === 'kind') a.kind = el.value;
    else if (el.dataset.af === 'x' || el.dataset.af === 'y') { if (Number.isFinite(v)) a[el.dataset.af] = v; }
    return changed();
  }
  const f = el.dataset.f;
  if (!f || !Number.isFinite(v)) return;
  if (f.startsWith('canvas.')) { doc.canvas[f.split('.')[1]] = Math.max(1, v); return changed(); }
  if (!p) return;
  const [a, b] = f.split('.');
  if (b) p[a][b] = v; else p[a] = v;
  changed();
});

panel.addEventListener('change', (ev) => {
  const el = ev.target;
  const p = partById(ui.sel);
  if (el.id === 'part-name' && p) renamePart(p.id, el.value);
  else if (el.id === 'parent' && p) setParent(p.id, el.value || null);
  else if (el.id === 'fallback') doc.fallbackState = el.value || null;
  else if (el.dataset.af === 'id') {
    const a = doc.attachments.find((x) => x.id === el.closest('[data-attach]').dataset.attach);
    const next = el.value.trim();
    if (next && !doc.attachments.some((x) => x.id === next)) a.id = next;
  } else if (el.dataset.f === 'z' || el.closest('tr[data-ch]')) { /* re-sort lists / keys */ } else return;
  renderPanel();
  changed();
});

/** Updates inspector inputs in place during drags (no re-render, keeps focus). */
function syncInspector() {
  const p = partById(ui.sel);
  if (!p) return;
  panel.querySelectorAll('input[data-f]').forEach((el) => {
    const [a, b] = el.dataset.f.split('.');
    const v = b ? p[a]?.[b] : p[a];
    if (typeof v === 'number' && document.activeElement !== el) el.value = v;
  });
}

// ---------------------------------------------------------------- transport / animation loop

const stateSel = $('#state');
stateSel.innerHTML = core.STATE_NAMES.map((s) => `<option ${s === ui.state ? 'selected' : ''}>${s}</option>`).join('');
stateSel.addEventListener('change', () => { ui.state = stateSel.value; renderPanel(); draw(); });
$('#play').addEventListener('click', () => {
  ui.playing = !ui.playing;
  $('#play').textContent = ui.playing ? 'Pause' : 'Play';
});
$('#scrub').addEventListener('input', (e) => { ui.playing = false; $('#play').textContent = 'Play'; ui.phase = +e.target.value; tickUi(); draw(); });
$('#loop-seconds').addEventListener('input', (e) => { const v = +e.target.value; if (v > 0) ui.loopSeconds = v; });

function tickUi() {
  $('#scrub').value = ui.phase;
  $('#phase-out').textContent = ui.phase.toFixed(3);
}
let last = performance.now();
function frame(now) {
  const dt = (now - last) / 1000;
  last = now;
  if (ui.mode === 'animate' && ui.playing) {
    ui.phase = (ui.phase + dt / ui.loopSeconds) % 1;
    tickUi();
    draw();
  }
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- wiring

document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => setMode(b.dataset.mode)));
$('#import').addEventListener('change', async (e) => { await importFiles(e.target.files); e.target.value = ''; });
$('#export').addEventListener('click', exportRig);
$('#new').addEventListener('click', () => { if (confirm('Discard the current rig and start over?')) setDoc(emptyDoc()); });
window.addEventListener('resize', resize);
window.addEventListener('keydown', (e) => {
  if (e.target.closest('input, select, textarea')) return;
  if (e.key === 'Enter' && ui.polyDraft) finishPoly();
  if (e.key === 'Escape') { ui.polyDraft = null; draw(); }
  if ((e.key === 'Delete' || e.key === 'Backspace') && ui.sel && ui.mode !== 'animate') { deletePart(ui.sel); renderPanel(); changed(); }
  if (e.key === ' ' && ui.mode === 'animate') { e.preventDefault(); $('#play').click(); }
});

// Test / automation hook (used by tools/puppet-rig/test/smoke.mjs). Not part of the UI.
window.puppetRig = {
  get doc() { return doc; },
  buildExport: () => { const o = buildExport(); return { json: o.json, fileName: o.fileName, atlasName: o.atlasName, atlas: o.atlasCanvas.toDataURL('image/png') }; },
  setParent: (id, parent) => { setParent(id, parent); renderPanel(); changed(); },
  evaluate: (state, phase) => core.evaluate(rigForEval(true), state, phase),
};

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('service worker', e));
}

(async () => {
  const saved = await loadDoc();
  if (saved) await setDoc(saved); else { renderPanel(); }
  setMode('cut');
  requestAnimationFrame(frame);
})();
