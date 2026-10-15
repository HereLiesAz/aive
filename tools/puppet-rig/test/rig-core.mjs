// Pure format/evaluator regression checks. Run: node tools/puppet-rig/test/rig-core.mjs
import assert from 'node:assert/strict';
import { evaluate, validateRig } from '../rig-core.js';

const rig = {
  format: 'aive-puppet-rig', version: 1, role: 'test', canvas: { width: 100, height: 100 },
  atlas: { image: 'a.png', width: 64, height: 64 },
  parts: [
    { id: 'body', rect: { x: 0, y: 0, w: 20, h: 20 },
      rest: { x: 50, y: 50, rotation: 0, scaleX: 1, scaleY: 1, alpha: 1 }, z: 0 },
    { id: 'tail', parent: 'body', rect: { x: 20, y: 0, w: 10, h: 20 },
      pivot: { x: 0.5, y: 0 }, kind: 'tail', rigidity: 0.5,
      stretch: { axis: 'y', length: 80 }, connectTo: 'root', z: 1 },
  ],
  attachments: [
    { id: 'root', part: 'body', x: 1, y: 0.5, kind: 'tail-base' },
    { id: 'tip', part: 'tail', x: 0.5, y: 1, kind: 'tail-tip' },
  ],
  states: {
    Active: { parts: { tail: { keys: {
      x: [{ t: 0, v: 20, ease: 'linear' }],
      scaleY: [{ t: 0, v: 3, ease: 'linear' }],
    } } } },
  },
};
assert.deepEqual(validateRig(rig), []);
const pose = evaluate(rig, 'Active', 0);
assert.equal(pose.byId.tail.m[4], 70); // body 50 + right socket 10 + half of keyed x=20
assert.equal(pose.byId.tail.m[5], 50);
assert.equal(pose.byId.tail.h, 80);
assert.equal(pose.attachments.tip.x, 70);
assert.equal(pose.attachments.tip.y, 210); // 80px length × effective scale 2, from y50

const stiff = structuredClone(rig);
stiff.parts[1].rigidity = 1;
const stiffPose = evaluate(stiff, 'Active', 0);
assert.equal(stiffPose.byId.tail.m[4], 60);
assert.equal(stiffPose.attachments.tip.y, 130);

const tendril = structuredClone(rig);
tendril.parts[1].kind = 'tendril';
assert.equal(evaluate(tendril, 'Active', 0).byId.tail.h, 80);
tendril.parts[1].z = -1;
assert.deepEqual(evaluate(tendril, 'Active', 0).parts.map((e) => e.part.id), ['tail', 'body']);

for (const [key, value] of [
  ['rigidity', 1.5], ['stretch', { axis: 'y', length: -20 }],
  ['connectTo', 'missing'],
]) {
  const broken = structuredClone(rig);
  broken.parts[1][key] = value;
  assert.ok(validateRig(broken).length > 0, `invalid ${key} must be rejected`);
}
const duplicatedSocket = structuredClone(rig);
duplicatedSocket.attachments.push({ ...duplicatedSocket.attachments[0] });
assert.ok(validateRig(duplicatedSocket).some((e) => e.includes('duplicate id')));

const legacy = structuredClone(rig);
for (const part of legacy.parts) {
  delete part.kind; delete part.rigidity; delete part.stretch; delete part.connectTo;
}
assert.deepEqual(validateRig(legacy), []);
assert.equal(evaluate(legacy, 'Active', 0).byId.tail.h, 20);
console.log('puppet-rig core: all checks passed');
