/* GPU-only presentation backend for the Compose spatial hive. No runtime, page navigation or bank access. */
(function (root) {
    "use strict";
    const V = p => [p.x,p.y,p.z], add = (a,b) => a.map((x,i)=>x+b[i]),
        sub = (a,b) => a.map((x,i)=>x-b[i]), mul = (a,s)=>a.map(x=>x*s),
        dot = (a,b)=>a.reduce((s,x,i)=>s+x*b[i],0),
        cross = (a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],
        norm = a=>mul(a,1/(Math.hypot(...a)||1));
    const basis = camera => {
        const eye=V(camera.eye), f=norm(sub(V(camera.gaze),eye));
        const r=norm(cross(f,Math.abs(f[1])>.999?[0,0,1]:[0,1,0]));
        return {eye,f,r,u:cross(r,f)};
    };
    // Shared Kotlin owns the triangle stream; decode its little-endian float transport.
    function decodeMesh(encoded) {
        const raw=root.atob(encoded);
        if(raw.length%36!==0)throw Error("Invalid spatial vertex stream");
        const bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));
        const view=new DataView(bytes.buffer),out=new Float32Array(bytes.length/4);
        for(let i=0;i<out.length;i++)out[i]=view.getFloat32(i*4,true);
        return out;
    }
    // Resize-scoped color/depth attachments. Failure falls back to the existing direct 3D draw.
    function createOptics(gl,vsSource,fsSource){
        if(!vsSource||!fsSource)return null;
        let program=null,vs=null,fs=null;
        function compile(type,source){
            const shader=gl.createShader(type);
            gl.shaderSource(shader,source);gl.compileShader(shader);
            if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS)){gl.deleteShader(shader);throw Error("Optics shader");}
            return shader;
        }
        try {
            vs=compile(gl.VERTEX_SHADER,vsSource);fs=compile(gl.FRAGMENT_SHADER,fsSource);
            program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
            if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error("Optics link");
        }catch(e){if(program)gl.deleteProgram(program);return null;}
        finally{if(vs)gl.deleteShader(vs);if(fs)gl.deleteShader(fs);}
        const uniforms=Object.fromEntries(["sceneColor","sceneDepth","focusDepth","aperture"].map(n=>[n,gl.getUniformLocation(program,n)]));
        let framebuffer=null,color=null,depth=null,width=0,height=0,ready=false;
        function release(){
            if(framebuffer)gl.deleteFramebuffer(framebuffer);
            if(color)gl.deleteTexture(color);
            if(depth)gl.deleteTexture(depth);
            framebuffer=color=depth=null;ready=false;
        }
        function texture(internal,format,type,filter,w,h){
            const tex=gl.createTexture();
            gl.bindTexture(gl.TEXTURE_2D,tex);
            gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,filter);
            gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,filter);
            gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
            gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
            gl.texImage2D(gl.TEXTURE_2D,0,internal,w,h,0,format,type,null);
            return tex;
        }
        function begin(w,h){
            if(w!==width||h!==height){
                release();width=w;height=h;
                try{
                    framebuffer=gl.createFramebuffer();
                    gl.activeTexture(gl.TEXTURE0);
                    color=texture(gl.RGBA8,gl.RGBA,gl.UNSIGNED_BYTE,gl.LINEAR,w,h);
                    depth=texture(gl.DEPTH_COMPONENT24,gl.DEPTH_COMPONENT,gl.UNSIGNED_INT,gl.NEAREST,w,h);
                    gl.bindTexture(gl.TEXTURE_2D,null);
                    gl.bindFramebuffer(gl.FRAMEBUFFER,framebuffer);
                    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,color,0);
                    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.DEPTH_ATTACHMENT,gl.TEXTURE_2D,depth,0);
                    ready=gl.checkFramebufferStatus(gl.FRAMEBUFFER)===gl.FRAMEBUFFER_COMPLETE;
                }catch(e){ready=false;}
                if(!ready)release();
            }
            gl.bindFramebuffer(gl.FRAMEBUFFER,ready?framebuffer:null);
            return ready;
        }
        function finish(focus,aperture){
            gl.bindFramebuffer(gl.FRAMEBUFFER,null);
            gl.disable(gl.DEPTH_TEST);
            gl.useProgram(program);
            gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,color);gl.uniform1i(uniforms.sceneColor,0);
            gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,depth);gl.uniform1i(uniforms.sceneDepth,1);
            gl.uniform1f(uniforms.focusDepth,focus);gl.uniform1f(uniforms.aperture,aperture);
            gl.drawArrays(gl.TRIANGLES,0,3);
            gl.bindTexture(gl.TEXTURE_2D,null);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,null);
            gl.enable(gl.DEPTH_TEST);
        }
        return {begin,finish,dispose(){release();gl.deleteProgram(program);}};
    }
    const instances=new WeakMap();
    function available(){return typeof root.WebGL2RenderingContext!=="undefined";}
    function attach(canvas,vertexSource,fragmentSource,opticsVertex,opticsFragment){
        const s={events:[],canvas,frame:null,camera:null,raf:0,cleanup:[]};instances.set(canvas,s);
        const emit=e=>{if(s.events.length<128)s.events.push(JSON.stringify(e));};
        const gl=canvas.getContext("webgl2",{alpha:false,antialias:true});
        if(!gl){emit({type:"error"});return;}
        s.gl=gl;
        try {
            function compile(type,src){const shader=gl.createShader(type);gl.shaderSource(shader,src);gl.compileShader(shader);if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(shader));return shader;}
            const vs=compile(gl.VERTEX_SHADER,vertexSource),fs=compile(gl.FRAGMENT_SHADER,fragmentSource);
            const p=gl.createProgram();gl.attachShader(p,vs);gl.attachShader(p,fs);gl.linkProgram(p);gl.deleteShader(vs);gl.deleteShader(fs);
            if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(p));
            s.program=p;s.buffer=gl.createBuffer();s.vao=gl.createVertexArray();gl.bindVertexArray(s.vao);gl.bindBuffer(gl.ARRAY_BUFFER,s.buffer);
            for(const [name,offset] of [["p",0],["n",12],["c",24]]){const loc=gl.getAttribLocation(p,name);gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,3,gl.FLOAT,false,36,offset);}
            s.uniforms=Object.fromEntries(["eye","forward","right","up","aspect","motionPhase"].map(n=>[n,gl.getUniformLocation(p,n)]));
            s.count=0;
        }catch(e){emit({type:"error"});return;}
        s.optics=createOptics(gl,opticsVertex,opticsFragment);
        const listen=(name,fn,opts)=>{canvas.addEventListener(name,fn,opts);s.cleanup.push(()=>canvas.removeEventListener(name,fn,opts));};
        let pointer=null;
        listen("pointerdown",e=>{pointer={id:e.pointerId,x:e.clientX,y:e.clientY,d:0,pan:e.shiftKey||e.button===1||e.button===2};canvas.setPointerCapture(e.pointerId);canvas.focus();});
        listen("pointermove",e=>{if(!pointer||pointer.id!==e.pointerId)return;const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;pointer.x=e.clientX;pointer.y=e.clientY;pointer.d+=Math.abs(dx)+Math.abs(dy);if(pointer.d<3)return;
            if(pointer.pan&&s.camera){const {r,u}=basis(s.camera),d=add(mul(r,-dx*.012),mul(u,dy*.012));emit({type:"pan",x:d[0],y:d[1],z:d[2]});}
            else emit({type:"orbit",x:-dx*.34,y:dy*.34});
        });
        listen("pointerup",e=>{if(!pointer||pointer.id!==e.pointerId)return;const moved=pointer.d;pointer=null;if(moved>=3||!s.camera)return;
            const rect=canvas.getBoundingClientRect();if(!rect.width||!rect.height)return;
            const x=(e.clientX-rect.left)/rect.width*2-1,y=1-(e.clientY-rect.top)/rect.height*2;
            emit({type:"tap",x,y,z:rect.width/rect.height});
        });
        listen("pointercancel",()=>{pointer=null;});listen("contextmenu",e=>e.preventDefault());
        listen("wheel",e=>{e.preventDefault();emit({type:"zoom",x:Math.exp(Math.max(-250,Math.min(250,e.deltaY))*.002)});},{passive:false});
        listen("keydown",e=>{const events={ArrowLeft:{type:"orbit",x:8},ArrowRight:{type:"orbit",x:-8},ArrowUp:{type:"orbit",y:8},ArrowDown:{type:"orbit",y:-8},"+":{type:"zoom",x:.88},"-":{type:"zoom",x:1.12},Escape:{type:"empty"}};if(events[e.key]){e.preventDefault();emit(events[e.key]);}});
        listen("webglcontextlost",e=>{e.preventDefault();emit({type:"error"});});
        function draw(){
            if(instances.get(canvas)!==s)return;
            s.raf=root.requestAnimationFrame(draw);
            if(!s.camera)return;
            const ratio=Math.min(2,root.devicePixelRatio||1),w=Math.max(1,Math.round(canvas.clientWidth*ratio)),h=Math.max(1,Math.round(canvas.clientHeight*ratio));
            if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;}
            const blurred=s.optics?.begin(w,h)===true;
            gl.viewport(0,0,w,h);gl.clearColor(.012,.045,.047,1);gl.enable(gl.DEPTH_TEST);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
            gl.useProgram(s.program);gl.bindVertexArray(s.vao);
            const {eye,f,r,u}=basis(s.camera);
            for(const [name,value] of [["eye",eye],["forward",f],["right",r],["up",u]])gl.uniform3fv(s.uniforms[name],value);
            gl.uniform1f(s.uniforms.aspect,w/h);gl.uniform1f(s.uniforms.motionPhase,s.camera.motionPhase||0);gl.drawArrays(gl.TRIANGLES,0,s.count);
            if(blurred)s.optics.finish(s.focusDepth,s.aperture);
        }
        s.raf=root.requestAnimationFrame(draw);
    }
    function detach(canvas){const s=instances.get(canvas);if(!s)return;instances.delete(canvas);root.cancelAnimationFrame(s.raf);s.cleanup.forEach(fn=>fn());if(s.gl){s.optics?.dispose();s.gl.deleteBuffer(s.buffer||null);s.gl.deleteVertexArray(s.vao||null);s.gl.deleteProgram(s.program||null);}}
    function frame(canvas,encoded){const s=instances.get(canvas);if(!s||!s.program)return;try{const data=decodeMesh(encoded);s.gl.bindBuffer(s.gl.ARRAY_BUFFER,s.buffer);s.gl.bufferData(s.gl.ARRAY_BUFFER,data,s.gl.STATIC_DRAW);s.count=data.length/9;}catch(e){s.events.push(JSON.stringify({type:"error"}));}}
    function camera(canvas,encoded,focusDepth,aperture){const s=instances.get(canvas);if(s){s.camera=JSON.parse(encoded);s.focusDepth=focusDepth;s.aperture=aperture;}}
    function poll(canvas){return instances.get(canvas)?.events.shift()||null;}
    root.AiveSpatialGpu={available,attach,detach,frame,camera,poll};
    if(typeof module!=="undefined")module.exports={basis,decodeMesh,createOptics};
})(globalThis);
