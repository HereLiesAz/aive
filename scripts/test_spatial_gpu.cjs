const assert = require("node:assert/strict");
const {basis,decodeMesh} = require("../webApp/src/webMain/resources/spatial-hive.js");
const camera={eye:{x:0,y:0,z:10},gaze:{x:0,y:0,z:0},memory:false};
const view=basis(camera);
assert.deepEqual(view.f,[0,0,-1]);
assert.equal(view.r[0],1);
const encoded=Buffer.from("0000803f000000c000004040000000000000803f000000000000003f0000803e00000000","hex").toString("base64");
assert.deepEqual(Array.from(decodeMesh(encoded)),[1,-2,3,0,1,0,.5,.25,0]);
assert.throws(()=>decodeMesh("AQID"));
