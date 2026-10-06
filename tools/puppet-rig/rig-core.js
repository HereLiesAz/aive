// rig-core.js — format constants, evaluator and validator for the aive puppet rig format.
//
// This file mirrors shared/src/commonMain/kotlin/com/hereliesaz/geministrator/puppet/
// (PuppetRig.kt + PuppetRigEvaluator.kt). Keep the math identical; the spec is
// docs/architecture/PUPPET_RIG_FORMAT.md. No DOM access here so Node can import it.

export const FORMAT = 'aive-puppet-rig';
export const VERSION = 1;
/** Exactly the H2g2WorkflowState enum names. */
export const STATE_NAMES = ['Pending', 'Ready', 'Active', 'Gate', 'Blocked', 'Complete', 'Failed'];
export const CHANNELS = ['x', 'y', 'rotation', 'scaleX', 'scaleY', 'alpha'];
export const EASES = ['linear', 'step', 'easeIn', 'easeOut', 'easeInOut'];

export const channelIdentity = (ch) => (ch === 'scaleX' || ch === 'scaleY' || ch === 'alpha' ? 1 : 0);

// ---- affine helpers: m = [a, b, c, d, tx, ty] (same layout as Canvas setTransform) ----
export const IDENTITY = [1, 0, 0, 1, 0, 0];
export function mul(m, n) {
  return [
    m[0] * n[0] + m[2] * n[1],
    m[1] * n[0] + m[3] * n[1],
    m[0] * n[2] + m[2] * n[3],
    m[1] * n[2] + m[3] * n[3],
    m[0] * n[4] + m[2] * n[5] + m[4],
    m[1] * n[4] + m[3] * n[5] + m[5],
  ];
}
export function trs(x, y, deg, sx, sy) {
  const r = (deg * Math.PI) / 180, c = Math.cos(r), s = Math.sin(r);
  return [c * sx, s * sx, -s * sy, c * sy, x, y];
}
export function apply(m, x, y) {
  return { x: m[0] * x + m[2] * y + m[4], y: m[1] * x + m[3] * y + m[5] };
}
export function invert(m) {
  const det = m[0] * m[3] - m[1] * m[2] || 1e-9;
  const a = m[3] / det, b = -m[1] / det, c = -m[2] / det, d = m[0] / det;
  return [a, b, c, d, -(a * m[4] + c * m[5]), -(b * m[4] + d * m[5])];
}

export function partSize(p) {
  return {
    w: p.size?.w ?? p.rect?.w ?? 0,
    h: p.size?.h ?? p.rect?.h ?? 0,
  };
}

export function ease(kind, u) {
  switch (kind) {
    case 'step': return 0;
    case 'easeIn': return u * u;
    case 'easeOut': return 1 - (1 - u) * (1 - u);
    case 'easeInOut': return u < 0.5 ? 2 * u * u : 1 - 2 * (1 - u) * (1 - u);
    default: return u;
  }
}

/** Samples looping keys at cycle in [0,1). Segment from the last key wraps to the first at t+1. */
export function sampleKeys(keys, cycle) {
  const k = [...keys].sort((p, q) => p.t - q.t);
  if (k.length === 0) return null;
  if (k.length === 1) return k[0].v;
  let from = k[k.length - 1], to = k[0], fromT = from.t - 1, toT = to.t;
  for (let i = 0; i < k.length; i++) {
    const last = i + 1 >= k.length;
    const next = k[(i + 1) % k.length];
    const nextT = last ? next.t + 1 : next.t;
    if (cycle >= k[i].t && cycle < nextT) { from = k[i]; to = next; fromT = k[i].t; toT = nextT; break; }
  }
  const span = toT - fromT;
  const u = span <= 0 ? 0 : Math.min(1, Math.max(0, (cycle - fromT) / span));
  return from.v + (to.v - from.v) * ease(from.ease || 'linear', u);
}

export function channelValue(motion, ch, cycle) {
  if (!motion) return channelIdentity(ch);
  const keys = motion.keys?.[ch];
  const keyed = keys && keys.length ? sampleKeys(keys, cycle) : channelIdentity(ch);
  const s = motion.sine?.[ch];
  const wave = s ? s.amplitude * Math.sin(2 * Math.PI * ((s.frequency ?? 1) * cycle + (s.phase ?? 0))) : 0;
  return keyed + wave;
}

/**
 * Evaluates the rig. Returns { parts: [{part, m, alpha, w, h, boxLeft, boxTop}] in draw order,
 * byId, attachments: {id: {x, y}}, resolvedState }.
 */
export function evaluate(rig, state, phase) {
  const resolved = rig.states?.[state] ? state
    : rig.fallbackState && rig.states?.[rig.fallbackState] ? rig.fallbackState : null;
  const motion = resolved ? rig.states[resolved] : null;
  const cycle = phase - Math.floor(phase);
  const byIdPart = Object.fromEntries(rig.parts.map((p) => [p.id, p]));
  const world = {};
  const resolve = (p, depth = 0) => {
    if (world[p.id]) return world[p.id];
    const parentPart = p.parent ? byIdPart[p.parent] : null;
    const parent = parentPart && depth < 256 ? resolve(parentPart, depth + 1) : { m: IDENTITY, alpha: 1 };
    const pm = motion?.parts?.[p.id];
    const ch = (name) => channelValue(pm, name, cycle);
    const r = { x: 0, y: 0, rotation: 0, scaleX: 1, scaleY: 1, alpha: 1, ...(p.rest || {}) };
    const local = trs(r.x + ch('x'), r.y + ch('y'), r.rotation + ch('rotation'), r.scaleX * ch('scaleX'), r.scaleY * ch('scaleY'));
    const alpha = Math.min(1, Math.max(0, parent.alpha * r.alpha * ch('alpha')));
    world[p.id] = { m: mul(parent.m, local), alpha };
    return world[p.id];
  };
  const parts = rig.parts
    .map((p, i) => ({ p, i }))
    .sort((a, b) => (a.p.z ?? 0) - (b.p.z ?? 0) || a.i - b.i)
    .map(({ p }) => {
      const { m, alpha } = resolve(p);
      const { w, h } = partSize(p);
      const pv = p.pivot || { x: 0.5, y: 0.5 };
      return { part: p, m, alpha, w, h, boxLeft: -pv.x * w, boxTop: -pv.y * h };
    });
  const byId = Object.fromEntries(parts.map((e) => [e.part.id, e]));
  const attachments = {};
  for (const a of rig.attachments || []) {
    const e = byId[a.part];
    if (!e) continue;
    const pv = e.part.pivot || { x: 0.5, y: 0.5 };
    attachments[a.id] = apply(e.m, (a.x - pv.x) * e.w, (a.y - pv.y) * e.h);
  }
  return { parts, byId, attachments, resolvedState: resolved };
}

/** Validates an exported rig document. Returns a list of error strings (empty = valid). */
export function validateRig(rig) {
  const errs = [];
  const num = (v) => typeof v === 'number' && Number.isFinite(v);
  const need = (cond, msg) => { if (!cond) errs.push(msg); };
  if (!rig || typeof rig !== 'object') return ['document is not an object'];
  need(rig.format === FORMAT, `format must be "${FORMAT}"`);
  need(rig.version === VERSION, `version must be ${VERSION}`);
  need(rig.canvas && num(rig.canvas.width) && rig.canvas.width > 0 && num(rig.canvas.height) && rig.canvas.height > 0, 'canvas.width/height must be positive numbers');
  need(rig.atlas && typeof rig.atlas.image === 'string' && Number.isInteger(rig.atlas.width) && Number.isInteger(rig.atlas.height), 'atlas {image, width, height} required');
  if (!Array.isArray(rig.parts)) return [...errs, 'parts must be an array'];
  const ids = new Set();
  for (const p of rig.parts) {
    const at = `part ${p?.id}`;
    need(typeof p.id === 'string' && p.id.length > 0, 'part id must be a non-empty string');
    need(!ids.has(p.id), `${at}: duplicate id`);
    ids.add(p.id);
    if (p.rect != null) {
      const r = p.rect;
      need(['x', 'y', 'w', 'h'].every((k) => Number.isInteger(r[k])) && r.w > 0 && r.h > 0, `${at}: rect must be positive integers`);
      if (rig.atlas) need(r.x >= 0 && r.y >= 0 && r.x + r.w <= rig.atlas.width && r.y + r.h <= rig.atlas.height, `${at}: rect outside atlas`);
    }
    if (p.size != null) need(num(p.size.w) && num(p.size.h), `${at}: size must be numbers`);
    if (p.pivot != null) need(num(p.pivot.x) && num(p.pivot.y), `${at}: pivot must be numbers`);
    if (p.z != null) need(num(p.z), `${at}: z must be a number`);
    if (p.rest != null) for (const k of Object.keys(p.rest)) need(num(p.rest[k]), `${at}: rest.${k} must be a number`);
  }
  const byId = Object.fromEntries(rig.parts.map((p) => [p.id, p]));
  for (const p of rig.parts) {
    if (p.parent == null) continue;
    if (!byId[p.parent]) { errs.push(`part ${p.id}: missing parent ${p.parent}`); continue; }
    let cur = p.parent, hops = 0;
    while (cur != null && hops <= rig.parts.length) {
      if (cur === p.id) { errs.push(`part ${p.id}: parent cycle`); break; }
      cur = byId[cur]?.parent; hops++;
    }
  }
  for (const a of rig.attachments || []) {
    need(typeof a.id === 'string' && byId[a.part] && num(a.x) && num(a.y), `attachment ${a.id}: needs id, existing part, numeric x/y`);
  }
  for (const [state, sm] of Object.entries(rig.states || {})) {
    need(STATE_NAMES.includes(state), `state "${state}" is not an H2g2WorkflowState name`);
    for (const [pid, pm] of Object.entries(sm?.parts || {})) {
      need(byId[pid], `state ${state}: unknown part ${pid}`);
      for (const [ch, keys] of Object.entries(pm.keys || {})) {
        need(CHANNELS.includes(ch), `state ${state}/${pid}: unknown channel ${ch}`);
        need(Array.isArray(keys) && keys.every((k) => num(k.t) && k.t >= 0 && k.t <= 1 && num(k.v) && (k.ease == null || EASES.includes(k.ease))),
          `state ${state}/${pid}/${ch}: keys need t in [0,1], numeric v, known ease`);
      }
      for (const [ch, s] of Object.entries(pm.sine || {})) {
        need(CHANNELS.includes(ch), `state ${state}/${pid}: unknown sine channel ${ch}`);
        need(num(s.amplitude) && (s.frequency == null || num(s.frequency)) && (s.phase == null || num(s.phase)), `state ${state}/${pid}/${ch}: bad sine`);
      }
    }
  }
  if (rig.fallbackState != null) need(STATE_NAMES.includes(rig.fallbackState), 'fallbackState must be a state name');
  return errs;
}
