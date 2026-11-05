import fs from 'fs';
const bytes = fs.readFileSync(process.argv[2]);
let mem, ex; const td = new TextDecoder();
const enc = new TextEncoder();
function pairs(list){ const n=list.reduce((a,[k,v])=>a+enc.encode(k).length+enc.encode(v).length+2,0);
  const out=new Uint8Array(4+8*list.length+n); const dv=new DataView(out.buffer); dv.setUint32(0,list.length,true); let s=4,d=4+8*list.length;
  for(const [k,v] of list){const a=enc.encode(k),b=enc.encode(v); dv.setUint32(s,a.length,true);dv.setUint32(s+4,b.length,true);s+=8;out.set(a,d);d+=a.length+1;out.set(b,d);d+=b.length+1;} return out;}
function give(b,pp,sp){ const p=ex.proxy_on_memory_allocate(b.length); new Uint8Array(mem.buffer).set(b,p); const dv=new DataView(mem.buffer); dv.setUint32(pp,p,true); dv.setUint32(sp,b.length,true); console.log('give',b.length,'at',p,'memsize',mem.buffer.byteLength); return 0;}
const headers=[[':method','GET'],[':path',process.argv[3]||'/'],['host','a']];
const env={ proxy_log:(l,p,n)=>{console.log('log',td.decode(new Uint8Array(mem.buffer,p,n)));return 0;},
 proxy_get_header_map_pairs:(m,pp,sp)=>give(pairs(headers),pp,sp),
 proxy_get_header_map_value:(m,kp,kl,pp,sp)=>{const k=td.decode(new Uint8Array(mem.buffer,kp,kl));const f=headers.find(h=>h[0]==k);return f?give(enc.encode(f[1]),pp,sp):1;},
 proxy_send_local_response:(c,dp,dl,bp,bl,hp,hl,g)=>{console.log('send',c,td.decode(new Uint8Array(mem.buffer,bp,bl)),JSON.stringify(td.decode(new Uint8Array(mem.buffer,hp,hl))),g);return 0;},
};
const {instance}=await WebAssembly.instantiate(bytes,{env}); ex=instance.exports; mem=ex.memory;
console.log('action',ex.proxy_on_request_headers(2,3,1));
console.log('action',ex.proxy_on_response_headers(2,3,0));
ex.proxy_on_log(2);
