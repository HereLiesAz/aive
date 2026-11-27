const assert = require("node:assert/strict");
const {basis,decodeMesh,createOptics} = require("../webApp/src/webMain/resources/spatial-hive.js");
const camera={eye:{x:0,y:0,z:10},gaze:{x:0,y:0,z:0},memory:false};
const view=basis(camera);
assert.deepEqual(view.f,[0,0,-1]);
assert.equal(view.r[0],1);
const encoded=Buffer.from("0000803f000000c000004040000000000000803f000000000000003f0000803e00000000","hex").toString("base64");
assert.deepEqual(Array.from(decodeMesh(encoded)),[1,-2,3,0,1,0,.5,.25,0]);
assert.throws(()=>decodeMesh("AQID"));


// Verify that a successful optical target renders to two attachments, resolves to the original
// canvas, reuses unchanged allocation, and releases resized/disposed resources.
const events=[];
let complete=true,next=0;
const gl=new Proxy({}, {get(_,key) {
    if(key==="FRAMEBUFFER_COMPLETE")return "complete";
    if(key==="checkFramebufferStatus")return ()=>complete?"complete":"incomplete";
    if(key==="getShaderParameter"||key==="getProgramParameter")return ()=>true;
    if(key==="getUniformLocation")return (_,name)=>name;
    if(key.startsWith("create"))return ()=>{const id=++next;events.push([key,id]);return id;};
    if(key.startsWith("gl")||/^[a-z]/.test(key))return (...args)=>events.push([key,...args]);
    return key;
}});
const optics=createOptics(gl,"vertex","fragment");
assert.ok(optics.begin(640,480));
assert.equal(events.filter(e=>e[0]==="framebufferTexture2D").length,2);
optics.finish(5,.065);
assert.ok(events.some(e=>e[0]==="uniform1f"&&e[1]==="focusDepth"&&e[2]===5));
assert.deepEqual(events.findLast(e=>e[0]==="drawArrays"),["drawArrays","TRIANGLES",0,3]);
assert.deepEqual(events.findLast(e=>e[0]==="bindFramebuffer"),["bindFramebuffer","FRAMEBUFFER",null]);
const allocated=events.filter(e=>e[0]==="createTexture").length;
assert.ok(optics.begin(640,480));
assert.equal(events.filter(e=>e[0]==="createTexture").length,allocated);
complete=false;
assert.equal(optics.begin(800,600),false);
assert.deepEqual(events.findLast(e=>e[0]==="bindFramebuffer"),["bindFramebuffer","FRAMEBUFFER",null]);
assert.equal(events.filter(e=>e[0]==="deleteTexture").length,4);
optics.dispose();
assert.ok(events.some(e=>e[0]==="deleteProgram"));
