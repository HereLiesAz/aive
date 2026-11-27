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
    // Matches the perspective matrix used by the vertex shader, including aspect ratio.
    const pick = (bodies, camera, x, y, aspect) => {
        const {eye,f,r,u}=basis(camera), ray=norm(add(f,add(mul(r,x*aspect*.52),mul(u,y*.52))));
        let nearest=Infinity, hit=null;
        for(const b of bodies) {
            const d=sub(V(b.position),eye), t=dot(d,ray), disc=b.radius*b.radius-dot(d,d)+t*t;
            if(disc<0)continue;
            const near=t-Math.sqrt(disc), far=t+Math.sqrt(disc), at=near>0?near:far;
            if(at>0&&at<nearest){nearest=at;hit=b;}
        }
        return hit;
    };
    const tint = body => {
        if(body.form==="Nucleus") return [.95,.33,.075];
        if(body.form==="Spore") {
            const palette={Source:[.32,.66,.8],Context:[.1,.85,.65],Tag:[.95,.6,.18],Phrase:[.57,.36,.9],Summary:[.4,.7,.95],Category:[.9,.36,.3],Generalization:[.9,.7,.32],Contrast:[.95,.28,.55],Keeper:[.95,.85,.53],Habit:[.2,.9,.4],Procedure:[.28,.7,.95]};
            return palette[body.state]||[.2,.7,.65];
        }
        if(body.state==="Failed"||body.form==="WitheredEgg")return [.5,.12,.18];
        if(body.state==="Gate")return [.9,.55,.15];
        const palettes=[[.12,.62,.57],[.28,.42,.85],[.55,.24,.75],[.12,.67,.83],[.8,.39,.2]];
        return palettes[(body.seed>>>0)%palettes.length];
    };
    // Interleaved position, normal and color. Tubes and organisms are real 3D triangle meshes.
    function mesh(frame) {
        const out=[], vertex=(p,n,c)=>out.push(...p,...n,...c);
        function tri(a,b,c,na,nb,nc,color){vertex(a,na,color);vertex(b,nb,color);vertex(c,nc,color);}
        function sphere(center,radius,color,seed,form,detail=18) {
            const phase=(seed>>>0)%628/100;
            const lobes=form==="Nucleus"?5:3+((seed>>>0)%4);
            const shape=form==="Creature"||form==="ProgrammaticNeuron"||form==="Nucleus";
            function sample(i,j) {
                const phi=Math.PI*i/detail, theta=2*Math.PI*j/(detail*2);
                const n=[Math.sin(phi)*Math.cos(theta),Math.cos(phi),Math.sin(phi)*Math.sin(theta)];
                const ripple=shape?1+.13*Math.sin(theta*lobes+phase)*Math.sin(phi*3)+.09*Math.cos(phi*5+theta*2+phase):1;
                const squash=shape&&form!=="Nucleus"?.7+((seed>>>8)&255)/510:1;
                return {p:add(center,[n[0]*radius*ripple,n[1]*radius*ripple*squash,n[2]*radius*ripple]),n};
            }
            for(let i=0;i<detail;i++)for(let j=0;j<detail*2;j++){
                const a=sample(i,j),b=sample(i+1,j),c=sample(i+1,j+1),d=sample(i,j+1);
                tri(a.p,b.p,c.p,a.n,b.n,c.n,color);tri(a.p,c.p,d.p,a.n,c.n,d.n,color);
            }
        }
        function tube(points,width,color,sides=7) {
            for(let i=1;i<points.length;i++){
                const a=points[i-1],b=points[i],delta=sub(b,a);
                if(Math.hypot(...delta)<1e-6)continue;
                const f=norm(delta),r=norm(cross(f,Math.abs(f[1])>.9?[1,0,0]:[0,1,0])),u=cross(r,f);
                for(let j=0;j<sides;j++){
                    const ring=k=>add(mul(r,Math.cos(k*2*Math.PI/sides)),mul(u,Math.sin(k*2*Math.PI/sides)));
                    const n=ring(j),m=ring(j+1),p=add(a,mul(n,width)),q=add(b,mul(n,width)),
                        s=add(b,mul(m,width)),t=add(a,mul(m,width));
                    tri(p,q,s,n,n,m,color);tri(p,s,t,n,m,m,color);
                }
            }
        }
        for(const c of frame.cords) {
            const palette={Neural:[.15,.54,.62],Exploratory:[.2,.48,.37],Reticulate:[.32,.3,.62],Cord:[.7,.27,.16],Memory:[.28,.58,.34],Dependency:[.18,.36,.5],Transfer:[1,.55,.18]};
            tube(c.points.map(V),c.width,palette[c.kind]||[.15,.5,.46]);
        }
        for(const b of [...frame.bodies,...frame.spores]) {
            const c=V(b.position),color=tint(b),r=b.radius,seed=b.seed>>>0;
            sphere(c,r,color,b.seed,b.form,b.form==="Spore"?7:18);
            if(b.form==="Nucleus"||b.form==="Creature"||b.form==="ProgrammaticNeuron"){
                // Seeded organic anatomy; not a signal of task progress.
                const count=b.form==="Nucleus"?12:5+seed%9;
                for(let k=0;k<count;k++){
                    const angle=k*2.399963+(seed%100)/100, y=1-2*(k+.5)/count,
                        d=[Math.cos(angle)*Math.sqrt(1-y*y),y,Math.sin(angle)*Math.sqrt(1-y*y)];
                    const a=add(c,mul(d,r*.88)),e=add(c,mul(d,r*(1.4+(seed%7)*.05)));
                    tube([a,add(a,mul(d,r*.22)),e],r*.035,color,5);
                    if(b.form!=="Nucleus"&&k<2+seed%3){
                        const eye=add(c,mul(d,r*.94));
                        sphere(eye,r*.22,[.56,.91,.8],seed,"Eye",8);
                        sphere(add(eye,mul(d,r*.17)),r*.105,[.016,.035,.04],seed,"Eye",8);
                    }
                    if(b.form==="Nucleus")sphere(add(c,mul(d,r*.68)),r*.32,[.88,.21,.055],seed+k,"Nucleus",10);
                }
            }
        }
        return new Float32Array(out);
    }
    const vertexSource="#version 300 es\nprecision highp float;in vec3 p;in vec3 n;in vec3 c;uniform vec3 eye,forward,right,up;uniform float aspect;out vec3 normal,color,world;void main(){vec3 d=p-eye;float z=dot(d,forward);gl_Position=vec4(dot(d,right)/(.52*aspect),dot(d,up)/.52,1.002*z-.2002,z);normal=n;color=c;world=p;}";
    const fragmentSource="#version 300 es\nprecision highp float;in vec3 normal,color,world;uniform vec3 eye;out vec4 frag;void main(){vec3 n=normalize(normal),v=normalize(eye-world),l=normalize(vec3(-.4,.9,.7));float diffuse=max(dot(n,l),0.);float rim=pow(1.-abs(dot(n,v)),2.);float spec=pow(max(dot(n,normalize(l+v)),0.),42.);vec3 lit=color*(.48+.76*diffuse+.48*rim)+vec3(.55,.84,.92)*spec*.8;float fog=exp(-length(eye-world)*.026);frag=vec4(mix(vec3(.012,.045,.047),lit,fog),1.);}";
    const instances=new WeakMap();
    function available(){return typeof root.WebGL2RenderingContext!=="undefined";}
    function attach(canvas){
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
            s.uniforms=Object.fromEntries(["eye","forward","right","up","aspect"].map(n=>[n,gl.getUniformLocation(p,n)]));
            s.count=0;
        }catch(e){emit({type:"error"});return;}
        const listen=(name,fn,opts)=>{canvas.addEventListener(name,fn,opts);s.cleanup.push(()=>canvas.removeEventListener(name,fn,opts));};
        let pointer=null;
        listen("pointerdown",e=>{pointer={id:e.pointerId,x:e.clientX,y:e.clientY,d:0,pan:e.shiftKey||e.button===1||e.button===2};canvas.setPointerCapture(e.pointerId);canvas.focus();});
        listen("pointermove",e=>{if(!pointer||pointer.id!==e.pointerId)return;const dx=e.clientX-pointer.x,dy=e.clientY-pointer.y;pointer.x=e.clientX;pointer.y=e.clientY;pointer.d+=Math.abs(dx)+Math.abs(dy);if(pointer.d<3)return;
            if(pointer.pan&&s.camera){const {r,u}=basis(s.camera),d=add(mul(r,-dx*.012),mul(u,dy*.012));emit({type:"pan",x:d[0],y:d[1],z:d[2]});}
            else emit({type:"orbit",x:-dx*.34,y:dy*.34});
        });
        listen("pointerup",e=>{if(!pointer||pointer.id!==e.pointerId)return;const moved=pointer.d;pointer=null;if(moved>=3||!s.frame||!s.camera)return;
            const rect=canvas.getBoundingClientRect();if(!rect.width||!rect.height)return;
            const x=(e.clientX-rect.left)/rect.width*2-1,y=1-(e.clientY-rect.top)/rect.height*2;
            const b=pick(s.camera.memory?s.frame.spores:[...s.frame.bodies,...s.frame.spores],s.camera,x,y,rect.width/rect.height);
            emit(b?{type:b.form==="Spore"?"memory":"agent",id:b.id}:{type:"empty"});
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
            gl.viewport(0,0,w,h);gl.clearColor(.012,.045,.047,1);gl.enable(gl.DEPTH_TEST);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
            gl.useProgram(s.program);gl.bindVertexArray(s.vao);
            const {eye,f,r,u}=basis(s.camera);
            for(const [name,value] of [["eye",eye],["forward",f],["right",r],["up",u]])gl.uniform3fv(s.uniforms[name],value);
            gl.uniform1f(s.uniforms.aspect,w/h);gl.drawArrays(gl.TRIANGLES,0,s.count);
        }
        s.raf=root.requestAnimationFrame(draw);
    }
    function detach(canvas){const s=instances.get(canvas);if(!s)return;instances.delete(canvas);root.cancelAnimationFrame(s.raf);s.cleanup.forEach(fn=>fn());if(s.gl){s.gl.deleteBuffer(s.buffer||null);s.gl.deleteVertexArray(s.vao||null);s.gl.deleteProgram(s.program||null);}}
    function frame(canvas,encoded){const s=instances.get(canvas);if(!s||!s.program)return;try{s.frame=JSON.parse(encoded);const data=mesh(s.frame);s.gl.bindBuffer(s.gl.ARRAY_BUFFER,s.buffer);s.gl.bufferData(s.gl.ARRAY_BUFFER,data,s.gl.STATIC_DRAW);s.count=data.length/9;}catch(e){s.events.push(JSON.stringify({type:"error"}));}}
    function camera(canvas,encoded){const s=instances.get(canvas);if(s)s.camera=JSON.parse(encoded);}
    function poll(canvas){return instances.get(canvas)?.events.shift()||null;}
    root.AiveSpatialGpu={available,attach,detach,frame,camera,poll};
    if(typeof module!=="undefined")module.exports={basis,pick,mesh};
})(globalThis);
