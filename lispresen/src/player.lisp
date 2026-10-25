;;;; player.lisp -- the script that presents the slides.
;;;;
;;;; *PLAYER-SCRIPT* scales the fixed canvas to the window (letterboxed), moves between
;;;; slides (keys, tap, swipe, #N in the URL), toggles full screen (F, double-click, the
;;;; button), shows the overview (O) and the presenter window (P), and corrects the
;;;; static sizes where the fonts the browser has are wider than the model (the runtime
;;;; check; ?check shows what it changed), and morphs the code of a (slide :morph t ...)
;;;; from the previous slide's on a page advance. *RUNNER-SCRIPT*, included only when the deck
;;;; has runnable code, runs code blocks on the playground runtime in a Web Worker.
;;;; *SAY-SCRIPT*, included only when the deck has callouts, places their bubbles again on
;;;; the geometry the browser drew. *VIDEO-SCRIPT*, included only when the deck has a
;;;; video, plays and pauses videos as slides are entered and left and as steps reveal and
;;;; hide them, and on a click. *FRAME-SCRIPT*, included only when the deck has a frame,
;;;; keeps a copy of a slide from loading its page again. The slides are laid out without
;;;; any of them; they only present and correct them.
;;;;
;;;; They are written without double quotes and backslashes so they sit in a Lisp string
;;;; as is. The emitter wraps them as (function(){ PLAYER RUNNER SAY VIDEO FRAME START })();
;;;; the runner, the callouts, the videos and the frames extend the player through HOOKS.

(in-package :lispresen)

(defparameter *player-script*
  "
var d=document,de=d.documentElement;de.classList.add('js');
var cfg=JSON.parse(d.getElementById('lp').textContent);
var stage=d.getElementById('stage'),deck=d.getElementById('deck'),fs=d.getElementById('fs'),pg=d.getElementById('progress');
var slides=Array.prototype.slice.call(deck.querySelectorAll(':scope>.slide'));
var W=deck.offsetWidth,H=deck.offsetHeight,cur=-1,shown=0,idle=null,x0=null,NL=String.fromCharCode(10);
var params=new URLSearchParams(location.search),presenter=params.has('presenter'),handout=params.has('notes');
var hooks={go:[],key:[],msg:[],thumb:[],bar:[],hello:[],place:[],copy:[]};
var chan=null;try{chan=new BroadcastChannel('lispresen:'+location.pathname);}catch(e){}
function send(m){if(chan)chan.postMessage(m);}
function fit(){var s=Math.min(innerWidth/W,innerHeight/H);
deck.style.transform='translate('+(innerWidth-W*s)/2+'px,'+(innerHeight-H*s)/2+'px) scale('+s+')';}
function steps(i){return +slides[i].dataset.steps||0;}
function focus(sl,k){sl.querySelectorAll('pre[data-focus]').forEach(function(p){var c=0;
p.dataset.focus.split(' ').forEach(function(x){if(+x<=k&&+x>c)c=+x;});
p.querySelectorAll('.ln').forEach(function(l){l.classList.toggle('dim',c>0&&(' '+(l.dataset.hot||'')+' ').indexOf(' '+c+' ')<0);});});}
var RM=window.matchMedia?matchMedia('(prefers-reduced-motion: reduce)'):null,mo=[],mgen=0;
function unmorph(){mgen++;mo.forEach(function(x){if(x.cancel)x.cancel();else x.remove();});mo=[];
deck.querySelectorAll('pre.mo').forEach(function(p){p.classList.remove('mo');});}
function snap(sl){var p=sl.querySelector('pre.code');if(!p||!p.querySelector('.tk'))return null;
var o=sl.getBoundingClientRect(),z=o.width/W,cs=getComputedStyle(p);
function at(e){var r=e.getBoundingClientRect();return {l:(r.left-o.left)/z,t:(r.top-o.top)/z,w:r.width/z,h:r.height/z};}
return {pre:p,box:at(p),fs:cs.fontSize,lh:cs.lineHeight,toks:Array.prototype.map.call(p.querySelectorAll('.tk'),function(e){
var l=e.closest('.dim');return {e:e,r:at(e),o:l?+getComputedStyle(l).opacity:1};})};}
function grab(a,b){if(a<0||Math.abs(a-b)!==1||presenter||(RM&&RM.matches)||!Element.prototype.animate)return null;
var fw=b>a;if(!slides[fw?b:a].querySelector('pre[data-morph]'))return null;var A=snap(slides[a]);
return A&&function(){var B=snap(slides[b]),L=[];if(!B)return;B.toks.forEach(function(t,j){L[j]=fw&&t.e.dataset.m?+t.e.dataset.m:-1;});
if(!fw)A.toks.forEach(function(t,j){if(t.e.dataset.m)L[+t.e.dataset.m]=j;});play(A,B,L);};}
function play(A,B,L){var sl=B.pre.closest('.slide'),cs=getComputedStyle(B.pre),used=[],g=++mgen,bg=d.createElement('div'),last;
function run(e,f){var x=e.animate(f,{duration:600,easing:'ease-in-out'});mo.push(x);return x;}
function box(r){return {left:r.l+'px',top:r.t+'px',width:r.w+'px',height:r.h+'px'};}
bg.className='mb';bg.style.cssText='left:'+B.box.l+'px;top:'+B.box.t+'px;width:'+B.box.w+'px;height:'+B.box.h+'px;background:'+cs.backgroundColor+';border-radius:'+cs.borderRadius+';box-shadow:'+cs.boxShadow;
sl.insertBefore(bg,sl.firstChild);mo.push(bg);B.pre.classList.add('mo');last=run(bg,[box(A.box),box(B.box)]);
B.toks.forEach(function(t,j){var s=L[j]>=0?A.toks[L[j]]:null;if(!s){run(t.e,[{opacity:0},{opacity:0,offset:.4},{opacity:1}]);return;}used[L[j]]=1;
run(t.e,[{transform:'translate('+(s.r.l-t.r.l)+'px,'+(s.r.t-t.r.t)+'px) scale('+(t.r.w?s.r.w/t.r.w:1)+')',opacity:Math.min(1,s.o/t.o)},{transform:'none',opacity:1}]);});
A.toks.forEach(function(s,j){if(used[j])return;var x=d.createElement('span');x.className='mg';x.textContent=s.e.textContent;
x.style.cssText='left:'+s.r.l+'px;top:'+s.r.t+'px;font-size:'+A.fs+';line-height:'+A.lh+';opacity:0;color:'+getComputedStyle(s.e).color;
sl.appendChild(x);mo.push(x);run(x,[{opacity:s.o},{opacity:0,offset:.6},{opacity:0}]);});
last.finished.then(function(){if(g===mgen)unmorph();},function(){});}
function show(i,quiet,k,dir){if(!slides.length)return;i=Math.max(0,Math.min(slides.length-1,i));
var n=steps(i);k=k===undefined?0:k<0?n:Math.min(k,n);if(i===cur&&k===shown)return;
var same=i===cur,m=null;if(!same){unmorph();if(dir)m=grab(cur,i);if(cur>=0)slides[cur].classList.remove('active');cur=i;slides[cur].classList.add('active');
try{if(location.hash!=='#'+(cur+1))history.replaceState(null,'','#'+(cur+1));}catch(e){}
if(pg)pg.style.width=(cur+1)*100/slides.length+'%';}
slides[cur].querySelectorAll('[data-step]').forEach(function(e){var s=+e.dataset.step;e.classList.toggle('pending',s>k);
if(same&&s>shown&&s<=k&&e.classList.contains('say'))e.classList.add('front');});focus(slides[cur],k);shown=k;if(m)m();
if(!quiet)send({t:'go',i:cur,k:shown,m:same?0:dir||0});hooks.go.forEach(function(f){f(cur);});}
function move(dir){var n=steps(cur);if(dir>0&&shown<n)show(cur,false,shown+1);else if(dir<0&&shown>0)show(cur,false,shown-1);
else if(cur+dir>=0&&cur+dir<slides.length)show(cur+dir,false,dir<0?-1:0,dir);}
function fromHash(){var n=parseInt(location.hash.slice(1),10);return n>0?n-1:0;}
function toggleFull(){if(d.fullscreenElement){d.exitFullscreen();}else if(stage.requestFullscreen){stage.requestFullscreen();}}
function wake(){stage.classList.add('pointer');stage.classList.remove('idle');clearTimeout(idle);
idle=setTimeout(function(){stage.classList.remove('pointer');if(d.fullscreenElement)stage.classList.add('idle');},2000);}
function thumb(i,live){var t=d.createElement('div');t.className='thumb';var c=slides[i].cloneNode(true);
c.removeAttribute('id');c.classList.add('active');if(!live)c.setAttribute('inert','');hooks.copy.forEach(function(f){f(c);});t.appendChild(c);return t;}
function scaleThumbs(root){root.querySelectorAll('.thumb').forEach(function(t){t.firstChild.style.transform='scale('+t.clientWidth/W+')';});}
var ov=null,sel=0;
function mark(){Array.prototype.forEach.call(ov.children,function(t,i){t.classList.toggle('sel',i===sel);});
ov.children[sel].scrollIntoView({block:'nearest'});}
function openOverview(){unmorph();if(ov)ov.remove();ov=d.createElement('div');ov.id='ov';
slides.forEach(function(s,i){var t=thumb(i);t.onclick=function(){closeOverview();show(i);};ov.appendChild(t);});
d.body.appendChild(ov);de.classList.add('ov');scaleThumbs(ov);sel=Math.max(cur,0);mark();}
function closeOverview(){de.classList.remove('ov');if(ov){ov.remove();ov=null;}}
function overviewKey(k){var t=ov.children,cols=0;while(cols<t.length&&t[cols].offsetTop===t[0].offsetTop)cols++;
if(k==='ArrowRight')sel++;else if(k==='ArrowLeft')sel--;else if(k==='ArrowDown')sel+=cols;else if(k==='ArrowUp')sel-=cols;
else if(k==='Enter'||k===' '){var i=sel;closeOverview();show(i);return true;}
else if(k==='o'||k==='O'||k==='Escape'){closeOverview();return true;}else return false;
sel=Math.max(0,Math.min(t.length-1,sel));mark();return true;}
function openPresenter(){var u=location.href.split('#')[0];
window.pw=open(u+(u.indexOf('?')<0?'?':'&')+'presenter#'+(cur+1),'lispresen-presenter','popup,width=1280,height=800');}
d.addEventListener('keydown',function(e){if(e.altKey||e.ctrlKey||e.metaKey)return;
var tg=e.target.tagName;if(tg==='TEXTAREA'||tg==='INPUT')return;var k=e.key;
if(ov){if(overviewKey(k))e.preventDefault();return;}
if(hooks.key.some(function(f){return f(k);})){e.preventDefault();return;}
if(k==='ArrowRight'||k==='ArrowDown'||k==='PageDown'||k===' '||k==='Enter')move(1);
else if(k==='ArrowLeft'||k==='ArrowUp'||k==='PageUp'||k==='Backspace')move(-1);
else if(k==='Home')show(0);else if(k==='End')show(slides.length-1);
else if((k==='f'||k==='F')&&!presenter)toggleFull();
else if(k==='o'||k==='O')openOverview();
else if((k==='p'||k==='P')&&!presenter)openPresenter();
else if(k==='Escape'&&d.fullscreenElement)d.exitFullscreen();else return;e.preventDefault();});
async function startHere(m){var o={};if(m.screen&&window.getScreenDetails){try{var sd=await getScreenDetails();
sd.screens.forEach(function(s){if(s.left===m.screen.left&&s.top===m.screen.top)o.screen=s;});}catch(e){}}
try{await stage.requestFullscreen(o);}catch(e){console.warn('lispresen: press F in this window for full screen',e);}}
if(chan)chan.onmessage=function(e){var m=e.data;if(m.t==='go')show(m.i,true,m.k,m.m);
else if(m.t==='hello'&&!presenter){send({t:'go',i:cur,k:shown});hooks.hello.forEach(function(f){f();});}
hooks.msg.forEach(function(f){f(m);});};
addEventListener('message',function(e){if(e.source===window.pw&&e.data&&e.data.t==='start')startHere(e.data);});
function pad(n){return (n<10?'0':'')+n;}
function buildPresenter(){de.classList.add('presenter');var pv=d.createElement('div');pv.id='pv';
var bar=d.createElement('div');bar.className='pv-bar';var timer=d.createElement('span');timer.className='pv-timer';
timer.title='Click to reset';var count=d.createElement('span');var start=d.createElement('button');start.textContent='Start';
start.title='Full screen on the other display';
var pn=d.createElement('button');pn.textContent='Print with notes';pn.title='Slides with their notes below, one per page';
pn.onclick=function(){var u=location.href.split('#')[0].replace(/[?&]presenter/,'');open(u+(u.indexOf('?')<0?'?':'&')+'notes&print','lispresen-handout');};
bar.append(timer,count,start,pn);
var cu=d.createElement('div');cu.className='pv-cur';var nx=d.createElement('div');nx.className='pv-next';
var notes=d.createElement('div');notes.className='pv-notes';pv.append(bar,cu,nx,notes);d.body.appendChild(pv);
hooks.bar.forEach(function(f){f(bar);});
var t0=Date.now();function tick(){var s=Math.floor((Date.now()-t0)/1000);timer.textContent=pad(Math.floor(s/60))+':'+pad(s%60);}
setInterval(tick,500);tick();timer.onclick=function(){t0=Date.now();tick();};
start.onclick=async function(){t0=Date.now();tick();var m={t:'start'};
if(window.getScreenDetails){try{var ps=await navigator.permissions.query({name:'window-management'});
if(ps.state==='granted'){var sd=await getScreenDetails();
var other=sd.screens.filter(function(s){return s!==sd.currentScreen;})[0];if(other)m.screen={left:other.left,top:other.top};}
else if(ps.state==='prompt')getScreenDetails().catch(function(){});}catch(e){}}
var w=window.opener,o=/^https?:$/.test(location.protocol)?location.origin:'*';if(!w||w.closed)return;
try{if(o==='*')throw 0;w.postMessage(m,{targetOrigin:o,delegate:'fullscreen'});}catch(e){w.postMessage(m,o);}};
function render(i){cu.replaceChildren(thumb(i,true));
nx.replaceChildren(i+1<slides.length?thumb(i+1):'');var a=slides[i].querySelector('.notes');notes.textContent=a?a.textContent:'';
count.textContent=(i+1)+' / '+slides.length;scaleThumbs(pv);hooks.thumb.forEach(function(f){f(cu);});}
hooks.go.push(render);addEventListener('resize',function(){scaleThumbs(pv);});}
function px(st,name){return parseInt(st.getPropertyValue(name),10);}
function next(list,v){var i=list.indexOf(v);return i>=0&&i<list.length-1?list[i+1]:0;}
function setVars(st,vs){for(var k in vs)st.setProperty('--'+k,vs[k]+'px');}
function bodyVars(s){return {'body':s,'body-lh':Math.ceil(s*15/10),'gap':Math.floor(s*3/5),'indent':Math.ceil(s*12/10),'item-gap':Math.floor(s/4)};}
function codeVars(c){return {'code':c,'code-lh':Math.ceil(c*14/10),'code-pad':Math.floor(c/2)};}
function tableVars(s){return {'table':s,'table-lh':Math.ceil(s*14/10),'cell-x':Math.floor(s*3/5),'cell-y':Math.floor(s/4)};}
function over(el){return el.scrollHeight>el.clientHeight+1;}
function check(){var log=[];slides.forEach(function(sl,i){var st=sl.style,notes=[];sl.classList.add('measure');
var t=sl.querySelector(':scope>.title'),b=sl.querySelector(':scope>.body'),c=sl.querySelector(':scope>.centered'),s;
if(t&&b){var room=parseInt(b.style.top,10)-cfg.top-cfg.gap;
while(t.offsetHeight>room+1&&(s=next(cfg.steps.title,px(st,'--title')))){notes.push('title '+px(st,'--title')+'px -> '+s+'px');
setVars(st,{'title':s,'title-lh':Math.ceil(s*13/10)});}}
if(b){var regions=[b].concat(Array.prototype.slice.call(b.querySelectorAll('.column')));
while(regions.some(over)){var moved=false;
if((s=next(cfg.steps.body,px(st,'--body')))){notes.push('body '+px(st,'--body')+'px -> '+s+'px');setVars(st,bodyVars(s));moved=true;}
if(b.querySelector('pre')&&(s=next(cfg.steps.code,px(st,'--code')))){notes.push('code '+px(st,'--code')+'px -> '+s+'px');setVars(st,codeVars(s));moved=true;}
b.querySelectorAll('table').forEach(function(tb){var ts=next(cfg.steps.table,px(tb.style,'--table'));
if(ts){notes.push('table '+px(tb.style,'--table')+'px -> '+ts+'px');setVars(tb.style,tableVars(ts));moved=true;}});
if(!moved)break;}
if(regions.some(over)){sl.classList.add('overflow');notes.push('overflows at the smallest sizes');}}
if(c){while(over(c)&&(s=next(cfg.steps.stmt,px(st,'--stmt')))){notes.push('text '+px(st,'--stmt')+'px -> '+s+'px');
setVars(st,{'stmt':s,'stmt-lh':Math.ceil(s*135/100)});}}
hooks.place.forEach(function(f){f(sl,notes);});sl.classList.remove('measure');if(notes.length){sl.classList.add('corrected');
notes.forEach(function(n){var line='slide '+(i+1)+': '+n;console.warn('lispresen: '+line);log.push(line);});}});
if(params.has('check')&&!presenter){de.classList.add('check');var o=d.createElement('div');o.id='chk';
o.textContent='runtime check: '+(log.length?NL+log.join(NL):'no corrections');d.body.appendChild(o);}}
")

(defparameter *runner-script*
  "
var boxes=Array.prototype.slice.call(d.querySelectorAll('#deck .codebox.runnable'));
var BS=String.fromCharCode(92),DQ=String.fromCharCode(34);
var HEADS=' lambda fn let let* letrec letfn flet labels if when unless cond case and or loop do dolist dotimes progn begin quote setf setq set! recur try catch handler-case unwind-protect block return-from multiple-value-bind destructuring-bind ns in-package require ';
var CONSTS=' nil t true false #t #f #true #false ';
function delim(c){return [32,9,10,13,12].indexOf(c.charCodeAt(0))>=0||('()[]{};`,'+DQ+String.fromCharCode(39)).indexOf(c)>=0;}
function esc(s){var o='';for(var i=0;i<s.length;i++){var c=s[i];o+=c==='&'?'&amp;':c==='<'?'&lt;':c==='>'?'&gt;':c===DQ?'&quot;':c===String.fromCharCode(39)?'&#39;':c;}return o;}
function highlight(t){var n=t.length,i=0,o='';
function emit(k,e){var s=esc(t.slice(i,e));o+=k?'<span class=hl-'+k+'>'+s+'</span>':s;i=e;}
function tend(j){while(j<n&&!delim(t[j]))j++;return j;}
function cls(j){var k=t.slice(i,j).toLowerCase();if(k[0]===':')return 'k';if(CONSTS.indexOf(' '+k+' ')>=0)return 'n';
var c=k[0],dg=function(x){return x>='0'&&x<='9';};if(dg(c)||(k.length>1&&'+-.'.indexOf(c)>=0&&dg(k[1])))return 'n';
if(i>0&&t[i-1]==='('&&((k.length>3&&k.slice(0,3)==='def')||HEADS.indexOf(' '+k+' ')>=0))return 'd';return null;}
while(i<n){var c=t[i],nx=i+1<n?t[i+1]:'',e,j;
if(c===';'){e=t.indexOf(NL,i);emit('c',e<0?n:e);}
else if(c==='#'&&nx==='|'){e=t.indexOf('|#',i+2);emit('c',e<0?n:e+2);}
else if(c===DQ){j=i+1;while(j<n){if(t[j]===BS)j+=2;else if(t[j]===DQ){j++;break;}else j++;}emit('s',Math.min(j,n));}
else if(c==='#'&&nx===BS)emit('s',tend(Math.min(n,i+3)));
else if(c===BS)emit('s',tend(Math.min(n,i+2)));
else if(delim(c))emit(null,i+1);
else{j=tend(i);emit(cls(j),j);}}
return o;}
var orig=[],state=[],rt='loading',why='',gen=0,worker=null,busy=-1,queue=[],timer=null,editing=null;
var base=cfg.runtime;try{base=new URL(base,location.href).href;}catch(e){}if(base.slice(-1)!=='/')base+='/';
function workerMain(B){self.rontoFetchBroker=true;Promise.resolve().then(function(){var c=new GraalVM.Config();c.wasm_path=B+'rontoplayground.js.wasm';return GraalVM.run([],c);})
.then(function(){self.onmessage=function(e){var m=e.data,v;try{rontoSetLanguage(m.lang);v=m.fresh?rontoRunProgram(m.src,m.stdin):rontoEval(m.src);}
catch(x){v='ERROR:'+(x&&x.message||x);}postMessage({type:'result',id:m.id,v:v});};postMessage({type:'ready'});})
.catch(function(x){postMessage({type:'fail',error:String(x)});});}
function broker(m){var i32=new Int32Array(m.sab,0,2),S=String.fromCharCode(1),hs=(m.reqHeaders||'').split(NL),h={},k;
for(k=0;k+1<hs.length;k+=2)if(hs[k])h[hs[k]]=hs[k+1];
fetch(m.url,{method:m.method,headers:h,body:m.body===null?undefined:m.body}).then(function(r){return r.text().then(function(t){
var c=r.headers.get('content-encoding'),dec=c!==null&&c.split(',').every(function(x){return /^(x-gzip|gzip|deflate|br)$/i.test(x.trim());}),l='';
r.headers.forEach(function(v,n){if(!dec||(n!=='content-encoding'&&n!=='content-length'))l+=n+': '+v+String.fromCharCode(13)+NL;});return r.status+S+l+S+t;});})
.catch(function(x){return 'ERR'+S+(x&&x.message||'request failed');})
.then(function(t){var p=new TextEncoder().encode(t);if(m.sab.byteLength<8+p.length)m.sab.grow(8+p.length);
new Uint8Array(m.sab).set(p,8);i32[1]=p.length;Atomics.store(i32,0,1);Atomics.notify(i32,0);});}
function label(k){k.dataset.state=rt;k.textContent=rt==='ready'?'Run':rt==='loading'?'Loading':'Failed';
k.title=rt==='ready'?'Run (Ctrl+Enter)':rt==='loading'?'Loading the runtime; a run waits for it':why+'; Run retries';}
function setRt(s){rt=s;d.querySelectorAll('.tools .run').forEach(label);
d.querySelectorAll('.rt').forEach(function(e){e.dataset.state=s;e.textContent='runtime: '+s;e.title=s==='failed'?why:'';});if(!presenter)send({t:'rt',s:s,why:why});}
function fail(g,text){if(g!==gen)return;why=text;console.error('lispresen: '+text);setRt('failed');
queue.forEach(function(i){output(i,text,true);});queue=[];}
function startWorker(){if(worker)worker.terminate();worker=null;busy=-1;clearTimeout(timer);setRt('loading');var g=++gen;
fetch(base+'rontoplayground.js').then(function(r){if(!r.ok)throw new Error('HTTP '+r.status+' for '+r.url);return r.text();})
.then(function(t){if(g!==gen)return;
worker=new Worker(URL.createObjectURL(new Blob([t,NL+';('+workerMain.toString()+')('+JSON.stringify(base)+')'],{type:'text/javascript'})));
worker.onmessage=function(e){var m=e.data;if(g!==gen)return;if(m.type==='ronto-fetch')broker(m);else if(m.type==='ready'){setRt('ready');pump();}
else if(m.type==='fail')fail(g,'the runtime did not start from '+base+': '+m.error);else if(m.type==='result')done(m.id,m.v);};
worker.onerror=function(e){fail(g,'the runtime did not start from '+base+': '+e.message);};})
.catch(function(x){fail(g,!worker&&base.indexOf('file:')===0?'a deck opened as a file cannot fetch the runtime from '+base+
'; serve the deck over HTTP (jwebserver) or use :runtime :hosted':'the runtime did not load from '+base+': '+x);});}
function codeOf(b){return b.querySelector('pre.code').textContent;}
function output(i,text,err,wait){var s=state[i];s.out=text;s.err=!!err;s.busy=!!wait;s.open=true;paint(boxes[i],s);share(i);}
function outOf(b){var os=b.closest('.slide').querySelectorAll('.out');for(var k=0;k<os.length;k++)if(os[k].dataset.o===b.dataset.i)return os[k];}
function place(b,o){var body=b.offsetParent;if(!body)return;var sl=b.closest('.slide'),st=sl.style;
var top=body.offsetTop+b.offsetTop+b.offsetHeight+16,pre=b.querySelector('[aria-describedby]'),ids=pre?pre.getAttribute('aria-describedby').split(' '):[],room;
sl.querySelectorAll(':scope>.say').forEach(function(c){if(ids.indexOf(c.id)>=0&&!c.classList.contains('pending')&&c.style.display!=='none')
top=Math.max(top,c.offsetTop+c.offsetHeight+16);});room=H-cfg.bottom-top;
if(room>=3*px(st,'--code-lh')+2*px(st,'--code-pad')){o.style.top=top+'px';o.style.bottom='auto';o.style.maxHeight=room+'px';}
else{o.style.top='auto';o.style.bottom=cfg.bottom+'px';o.style.maxHeight=Math.floor(H*45/100)+'px';}}
function paint(b,s){var o=outOf(b);o.firstChild.textContent=s.out;o.classList.toggle('err',s.err);o.classList.toggle('busy',s.busy);
o.classList.toggle('open',s.open);if(s.open)place(b,o);
if(s.busy)b.closest('.slide').querySelectorAll('.say.front').forEach(function(c){c.classList.remove('front');});}
function scrollOut(root,k){if(k!=='ArrowDown'&&k!=='ArrowUp')return false;var o=root&&root.querySelector('.out.open pre');if(!o)return false;
var step=3*px(o.closest('.slide').style,'--code-lh');
if(k==='ArrowDown'?o.scrollTop+o.clientHeight<o.scrollHeight-1:o.scrollTop>0){o.scrollTop+=k==='ArrowDown'?step:-step;return true;}return false;}
function hide(i){state[i].open=false;paint(boxes[i],state[i]);share(i);}
function share(i){send({t:'code',i:i,s:state[i]});}
function run(b){var i=+b.dataset.i;if(busy===i||queue.indexOf(i)>=0)return;queue.push(i);
output(i,rt==='ready'?'running...':'waiting for the runtime...',false,true);if(rt==='failed')startWorker();else pump();}
function pump(){if(busy>=0||!queue.length||rt!=='ready')return;var i=queue.shift(),b=boxes[i];busy=i;
worker.postMessage({id:i,lang:b.dataset.lang.toLowerCase(),src:codeOf(b),fresh:b.hasAttribute('data-fresh'),stdin:b.dataset.stdin||''});
var secs=+b.dataset.timeout||10;timer=setTimeout(function(){output(i,'timed out after '+secs+' s; the runtime restarted, so earlier definitions are gone',true);startWorker();},secs*1000);}
function done(i,v){if(i!==busy)return;clearTimeout(timer);busy=-1;var err=typeof v==='string'&&v.indexOf('ERROR:')===0;output(i,err?v.slice(6).trim():String(v),err);pump();}
function setCode(b,text,html){b.querySelector('pre.code code').innerHTML=html||highlight(text);var i=+b.dataset.i;state[i].text=text;share(i);
var sl=b.closest('.slide');hooks.place.forEach(function(f){f(sl,[]);});}
function reset(i){leave();setCode(boxes[i],orig[i].text,orig[i].html);focus(slides[cur],shown);hide(i);}
function leave(){if(!editing)return;var ta=editing.querySelector('textarea');editing.closest('.slide').classList.remove('editing');editing=null;if(ta)ta.remove();}
function edit(b){if(editing===b)return;leave();editing=b;b.closest('.slide').classList.add('editing');var pre=b.querySelector('pre.code'),ta=d.createElement('textarea');
ta.value=codeOf(b);ta.spellcheck=false;ta.setAttribute('wrap','off');ta.style.height=pre.offsetHeight+'px';b.appendChild(ta);ta.focus();
ta.addEventListener('input',function(){setCode(b,ta.value);});
ta.addEventListener('scroll',function(){pre.scrollTop=ta.scrollTop;pre.scrollLeft=ta.scrollLeft;});
ta.addEventListener('keydown',function(e){e.stopPropagation();
if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();run(b);}
else if(e.key==='Escape'){e.preventDefault();leave();}
else if(e.key==='Tab'){e.preventDefault();ta.setRangeText('  ',ta.selectionStart,ta.selectionEnd,'end');setCode(b,ta.value);}});
ta.addEventListener('blur',function(){setTimeout(function(){if(editing===b&&d.activeElement!==ta)leave();},0);});}
function tools(b,onRun,onReset){var t=d.createElement('div');t.className='tools';
[['Run','Run (Ctrl+Enter)',onRun],['Reset','Restore the code',onReset]].forEach(function(x){var k=d.createElement('button');
k.textContent=x[0];k.title=x[1];if(x[2]===onRun){k.className='run';label(k);}k.addEventListener('mousedown',function(e){e.preventDefault();});
k.addEventListener('click',function(e){e.stopPropagation();x[2]();});t.appendChild(k);});b.appendChild(t);
b.addEventListener('dblclick',function(e){e.stopPropagation();});}
function apply(b,s){if(!s)return;b.querySelector('pre.code code').innerHTML=highlight(s.text);paint(b,s);}
function openOn(i){return boxes.filter(function(b){return state[+b.dataset.i].open&&slides[i].contains(b);});}
boxes.forEach(function(b,i){b.dataset.i=i;orig[i]={text:codeOf(b),html:b.querySelector('pre.code code').innerHTML};state[i]={text:orig[i].text,out:'',err:false,busy:false,open:false};
var o=d.createElement('div');o.className='out';o.dataset.o=i;o.setAttribute('aria-live','polite');
var c=d.createElement('button');c.textContent=String.fromCharCode(215);c.title='Close (Esc)';
o.append(d.createElement('pre'),c);b.closest('.slide').appendChild(o);});
hooks.go.push(function(i){openOn(i).forEach(function(b){place(b,outOf(b));});});
if(presenter){var rtl=d.createElement('span');
hooks.bar.push(function(bar){var r=d.createElement('button');r.textContent='Reset runtime';r.title='Start a new interpreter';
r.onclick=function(){send({t:'reset-rt'});};rtl.className='rt';rtl.dataset.state=rt;rtl.textContent='runtime: '+rt;bar.append(rtl,r);});
hooks.thumb.push(function(root){root.querySelectorAll('.codebox.runnable').forEach(function(b){var i=+b.dataset.i;
var old=b.querySelector('.tools');if(old)old.remove();apply(b,state[i]);
outOf(b).lastChild.onclick=function(){send({t:'hide',i:i});};
tools(b,function(){send({t:'run',i:i});},function(){send({t:'reset',i:i});});});});
hooks.key.push(function(k){return scrollOut(d.querySelector('.pv-cur'),k);});
hooks.key.push(function(k){var open=k==='Escape'?openOn(cur):[];
open.forEach(function(b){send({t:'hide',i:+b.dataset.i});});return open.length>0;});
hooks.msg.push(function(m){if(m.t==='rt'){why=m.why||'';setRt(m.s);}else if(m.t==='code'){state[m.i]=m.s;
d.querySelectorAll('#pv .codebox').forEach(function(b){if(+b.dataset.i===m.i)apply(b,m.s);});}});
send({t:'hello'});}
else{boxes.forEach(function(b,i){tools(b,function(){run(b);},function(){reset(i);});
outOf(b).lastChild.onclick=function(){hide(i);};
b.querySelector('pre.code').addEventListener('click',function(){edit(b);});});
hooks.key.push(function(k){return scrollOut(slides[cur],k);});
hooks.key.push(function(k){var open=k==='Escape'?openOn(cur):[];
open.forEach(function(b){hide(+b.dataset.i);});return open.length>0;});
hooks.key.push(function(k){if(k!=='e'&&k!=='E')return false;var b=slides[cur].querySelector('.codebox.runnable');if(b)edit(b);return !!b;});
hooks.msg.push(function(m){if(m.t==='run')run(boxes[m.i]);else if(m.t==='reset')reset(m.i);else if(m.t==='hide')hide(m.i);
else if(m.t==='reset-rt')startWorker();});
hooks.hello.push(function(){send({t:'rt',s:rt,why:why});boxes.forEach(function(b,i){share(i);});});
d.addEventListener('fullscreenchange',function(){var kb=navigator.keyboard;if(!kb||!kb.lock)return;
if(d.fullscreenElement)kb.lock(['Escape']).catch(function(){});else kb.unlock();});
startWorker();}
")

(defparameter *say-script*
  "
var SAY=cfg.say;
function cbox(r,o,k){return {l:Math.round((r.left-o.left)/k),t:Math.round((r.top-o.top)/k),r:Math.round((r.right-o.left)/k),b:Math.round((r.bottom-o.top)/k)};}
function crects(list,o,k){var a=[];for(var j=0;j<list.length;j++)if(list[j].width>=1&&list[j].height>=1)a.push(cbox(list[j],o,k));return a;}
function cunion(a){if(!a.length)return a;var u={l:a[0].l,t:a[0].t,r:a[0].r,b:a[0].b};
a.forEach(function(r){u.l=Math.min(u.l,r.l);u.t=Math.min(u.t,r.t);u.r=Math.max(u.r,r.r);u.b=Math.max(u.b,r.b);});return [u];}
function ctext(root,o,k){var a=[],w=d.createTreeWalker(root,NodeFilter.SHOW_TEXT),rg=d.createRange(),n;
while((n=w.nextNode())){if(n.parentElement.closest('.say,.notes,.out,.mx'))continue;rg.selectNodeContents(n);a=a.concat(crects(rg.getClientRects(),o,k));}return a;}
function cmeet(a,b){return a.l<b.r&&b.l<a.r&&a.t<b.b&&b.t<a.b;}
function canchor(sl,c,o,k){var el=null,at=c.dataset.at;sl.querySelectorAll('[aria-describedby]').forEach(function(e){
if(!el&&e.getAttribute('aria-describedby').split(' ').indexOf(c.id)>=0)el=e;});if(!el)return [];
if(c.dataset.box){var f=cbox(el.closest('.dg').getBoundingClientRect(),o,k),q=c.dataset.box.split(' ').map(Number);
return [{l:f.l+q[0],t:f.t+q[1],r:f.l+q[2],b:f.t+q[3]}];}
if(el.classList.contains('dg'))return [cbox(el.getBoundingClientRect(),o,k)];
if(at!==undefined){var i=el.textContent.indexOf(at),e=i+at.length,pos=0,rg=d.createRange(),w=d.createTreeWalker(el,NodeFilter.SHOW_TEXT),n;if(i<0)return [];
while((n=w.nextNode())){var len=n.data.length;if(pos<=i&&i<=pos+len)rg.setStart(n,i-pos);if(pos<=e&&e<=pos+len){rg.setEnd(n,e-pos);break;}pos+=len;}
return cunion(crects(rg.getClientRects(),o,k));}
if(el.tagName==='SPAN')return crects(el.getClientRects(),o,k);
if(el.tagName==='FIGURE'&&!el.classList.contains('quote')){var im=el.querySelector('img,video,.frame,svg.mx');return im?[cbox(im.getBoundingClientRect(),o,k)]:[];}
if(el.tagName==='PRE'||el.tagName==='TABLE')return [cbox(el.getBoundingClientRect(),o,k)];
return cunion(ctext(el,o,k));}
function cobstacles(sl,o,k){var a=ctext(sl,o,k);
sl.querySelectorAll('figure:not(.quote)').forEach(function(f){var im=f.querySelector('img,video,.frame'),fr=cbox(f.getBoundingClientRect(),o,k);
if(im){var ir=cbox(im.getBoundingClientRect(),o,k);a.push({l:fr.l,t:ir.t,r:fr.r,b:ir.b});}});
sl.querySelectorAll('.tools,.dg-art>:not(.dg-g,.dg-z,.dg-bg),.mx>*').forEach(function(t){a.push(cbox(t.getBoundingClientRect(),o,k));});return a;}
function ccands(f,w,h,bd){var f1=f[0],fl=f[f.length-1],R=SAY.reach,I=SAY.inset,HB=SAY.half,out=[],ax,ay;
function along(a,ext,lo,hi){return [a-Math.floor(ext/2),a-I,a-ext+I].map(function(p){return Math.max(lo,Math.min(hi-ext,p));});}
ax=fl.r;ay=Math.floor((fl.t+fl.b)/2);along(ay,h,bd.t,bd.b).forEach(function(y){out.push({side:'right',x:ax+R,y:y,tail:ay-y,tb:{l:ax,t:ay-HB,r:ax+R,b:ay+HB}});});
ax=Math.floor((fl.l+fl.r)/2);ay=fl.b;along(ax,w,bd.l,bd.r).forEach(function(x){out.push({side:'below',x:x,y:ay+R,tail:ax-x,tb:{l:ax-HB,t:ay,r:ax+HB,b:ay+R}});});
ax=Math.floor((f1.l+f1.r)/2);ay=f1.t;along(ax,w,bd.l,bd.r).forEach(function(x){out.push({side:'above',x:x,y:ay-R-h,tail:ax-x,tb:{l:ax-HB,t:ay-R,r:ax+HB,b:ay}});});
ax=f1.l;ay=Math.floor((f1.t+f1.b)/2);along(ay,h,bd.t,bd.b).forEach(function(y){out.push({side:'left',x:ax-R-w,y:y,tail:ay-y,tb:{l:ax-R,t:ay-HB,r:ax,b:ay+HB}});});
return out;}
function cfree(p,w,h,obs,bd){var C=SAY.clear,I=SAY.inset,g={l:p.x-C,t:p.y-C,r:p.x+w+C,b:p.y+h+C};
return bd.l<=p.x&&p.x+w<=bd.r&&bd.t<=p.y&&p.y+h<=bd.b&&I<=p.tail&&p.tail<=(p.side==='right'||p.side==='left'?h:w)-I&&
!obs.some(function(r){return cmeet(g,r)||cmeet(p.tb,r);});}
function callouts(sl,notes){var cs=sl.querySelectorAll(':scope>.say'),body=sl.querySelector(':scope>.body');if(!cs.length||!body||!sl.offsetWidth)return;
var o=sl.getBoundingClientRect(),k=o.width/W,bd=cbox(body.getBoundingClientRect(),o,k),obs=cobstacles(sl,o,k);
Array.prototype.forEach.call(cs,function(c,j){var f=canchor(sl,c,o,k),w=c.offsetWidth,h=c.offsetHeight,ps,p=null,q,x,y;
c.style.display=f.length?'':'none';if(!f.length)return;ps=ccands(f,w,h,bd);
for(q=0;q<ps.length&&!p;q++)if(cfree(ps[q],w,h,obs,bd))p=ps[q];
if(!p){if(!c.classList.contains('overflow')){c.classList.add('overflow');notes.push('callout '+(j+1)+' has no room');}}
else{if(c.dataset.side!==p.side)notes.push('callout '+(j+1)+' '+c.dataset.side+' -> '+p.side);
c.classList.remove('overflow');c.dataset.side=p.side;c.style.left=p.x+'px';c.style.top=p.y+'px';c.style.setProperty('--tail',(p.tail-SAY.border)+'px');}
x=parseInt(c.style.left,10);y=parseInt(c.style.top,10);obs.push({l:x,t:y,r:x+w,b:y+h});if(p)obs.push(p.tb);});}
hooks.place.push(callouts);
")

(defparameter *video-script*
  "
var vids=Array.prototype.slice.call(deck.querySelectorAll('video')),vat=-1,vk=0;
vids.forEach(function(v,i){v.controls=false;v.dataset.v=i;});
function vplay(v){var p=v.play();if(p&&p.catch)p.catch(function(e){console.warn('lispresen: the browser did not play the video',e);});}
function vtoggle(v){if(v.paused)vplay(v);else v.pause();}
function vclick(v,f){v.addEventListener('click',function(e){e.stopPropagation();f();});v.addEventListener('dblclick',function(e){e.stopPropagation();});}
if(presenter)hooks.thumb.push(function(root){root.querySelectorAll('video').forEach(function(v){vclick(v,function(){send({t:'video',i:+v.dataset.v});});});});
else{vids.forEach(function(v){vclick(v,function(){vtoggle(v);});});
hooks.go.push(function(i){var entered=vat>=0,moved=i!==vat,k0=vk;vat=i;vk=shown;
vids.forEach(function(v){if(!v.paused&&!slides[i].contains(v))v.pause();});
slides[i].querySelectorAll('video').forEach(function(v){var e=v.closest('[data-step]'),s=e?+e.dataset.step:0;
if(s>shown){if(!v.paused)v.pause();}else if(v.hasAttribute('data-autoplay')&&(moved?entered:s>k0))vplay(v);});});
hooks.msg.push(function(m){if(m.t==='video'&&vids[m.i])vtoggle(vids[m.i]);});}
")

(defparameter *frame-script*
  "
function fcopy(root){root.querySelectorAll('.frame iframe').forEach(function(f){var p=d.createElement('p');
p.className='frame-copy';p.textContent=f.title||f.getAttribute('src');f.replaceWith(p);});}
hooks.copy.push(fcopy);if(presenter)fcopy(deck);
")

(defparameter *start-script*
  "
addEventListener('resize',fit);
d.addEventListener('fullscreenchange',function(){fit();wake();keepAwake();});
var wl=null,wlBusy=false;
function keepAwake(){var want=presenter||!!d.fullscreenElement;
if(!want){if(wl){wl.release().catch(function(){});wl=null;}return;}
if(wl||wlBusy||d.visibilityState!=='visible'||!navigator.wakeLock)return;wlBusy=true;
navigator.wakeLock.request('screen').then(function(s){wlBusy=false;wl=s;
s.addEventListener('release',function(){if(wl===s)wl=null;});keepAwake();},function(){wlBusy=false;});}
d.addEventListener('visibilitychange',keepAwake);
addEventListener('hashchange',function(){show(fromHash());});
var y0=0,t0=0,tt=0,TAPPABLE='a,button,input,textarea,select,label,summary,video,audio,iframe,[contenteditable],[tabindex],.codebox.runnable,.out';
function typing(){var a=d.activeElement;return !!a&&(a.tagName==='TEXTAREA'||a.tagName==='INPUT'||a.isContentEditable);}
stage.addEventListener('dblclick',function(){if(Date.now()-tt>1000)toggleFull();});
fs.addEventListener('click',function(e){e.stopPropagation();fs.blur();toggleFull();});
fs.addEventListener('dblclick',function(e){e.stopPropagation();});
stage.addEventListener('mousemove',wake);
stage.addEventListener('touchstart',function(e){var v=window.visualViewport,p=e.touches[0];tt=Date.now();
x0=e.touches.length>1||(v&&v.scale>1.01)||typing()?null:p.clientX;y0=p.clientY;t0=tt;},{passive:true});
stage.addEventListener('touchcancel',function(){x0=null;});
stage.addEventListener('touchend',function(e){tt=Date.now();if(x0===null||e.touches.length)return;
var p=e.changedTouches[0],dx=p.clientX-x0,dy=p.clientY-y0,s=getSelection();x0=null;
if(Math.abs(dx)>50){move(dx<0?1:-1);return;}
if(Math.abs(dx)>10||Math.abs(dy)>10||tt-t0>500||ov||presenter||e.target.closest(TAPPABLE)||(s&&!s.isCollapsed))return;
e.preventDefault();wake();move(p.clientX<stage.clientWidth/3?-1:1);});
window.lispresen={show:function(i){show(i);},current:function(){return cur;}};
if(presenter)buildPresenter();
keepAwake();
if(handout&&!presenter){de.classList.add('handout');var ps=d.createElement('style');
ps.textContent='@media print{@page{size:'+W+'px '+2*H+'px;margin:0}}';d.head.appendChild(ps);
if(params.has('print'))addEventListener('load',function(){(d.fonts&&d.fonts.ready?d.fonts.ready:Promise.resolve()).then(function(){setTimeout(print,300);});});}
deck.querySelectorAll('[data-step]').forEach(function(e){e.classList.add('pending');});
fit();show(fromHash(),presenter);
if(d.fonts&&d.fonts.ready)d.fonts.ready.then(check);else check();
")
