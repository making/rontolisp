;;;; player.lisp -- the script that presents the slides.
;;;;
;;;; Scales the fixed canvas to the window (letterboxed), moves between slides (keys,
;;;; swipe, #N in the URL) and toggles full screen (F, double-click, the button). The
;;;; slides are laid out without it; it only presents them. Written without double
;;;; quotes and backslashes so it sits in a Lisp string as is.

(in-package :lispresen)

(defparameter *player-script*
  "
(function(){
var d=document;d.documentElement.classList.add('js');
var stage=d.getElementById('stage'),deck=d.getElementById('deck'),fs=d.getElementById('fs');
var slides=Array.prototype.slice.call(d.querySelectorAll('.slide'));
var W=deck.offsetWidth,H=deck.offsetHeight,cur=-1,idle=null,x0=null;
function fit(){var s=Math.min(innerWidth/W,innerHeight/H);
deck.style.transform='translate('+(innerWidth-W*s)/2+'px,'+(innerHeight-H*s)/2+'px) scale('+s+')';}
function show(i){if(!slides.length)return;i=Math.max(0,Math.min(slides.length-1,i));if(i===cur)return;
if(cur>=0)slides[cur].classList.remove('active');cur=i;slides[cur].classList.add('active');
if(location.hash!=='#'+(cur+1))history.replaceState(null,'','#'+(cur+1));}
function fromHash(){var n=parseInt(location.hash.slice(1),10);return n>0?n-1:0;}
function toggleFull(){if(d.fullscreenElement){d.exitFullscreen();}else if(stage.requestFullscreen){stage.requestFullscreen();}}
function wake(){stage.classList.add('pointer');stage.classList.remove('idle');clearTimeout(idle);
idle=setTimeout(function(){stage.classList.remove('pointer');if(d.fullscreenElement)stage.classList.add('idle');},2000);}
addEventListener('resize',fit);
d.addEventListener('fullscreenchange',function(){fit();wake();});
addEventListener('hashchange',function(){show(fromHash());});
d.addEventListener('keydown',function(e){if(e.altKey||e.ctrlKey||e.metaKey)return;var k=e.key;
if(k==='ArrowRight'||k==='ArrowDown'||k==='PageDown'||k===' '||k==='Enter')show(cur+1);
else if(k==='ArrowLeft'||k==='ArrowUp'||k==='PageUp'||k==='Backspace')show(cur-1);
else if(k==='Home')show(0);else if(k==='End')show(slides.length-1);
else if(k==='f'||k==='F')toggleFull();else return;e.preventDefault();});
stage.addEventListener('dblclick',toggleFull);
fs.addEventListener('click',function(e){e.stopPropagation();fs.blur();toggleFull();});
fs.addEventListener('dblclick',function(e){e.stopPropagation();});
stage.addEventListener('mousemove',wake);
stage.addEventListener('touchstart',function(e){x0=e.touches[0].clientX;},{passive:true});
stage.addEventListener('touchend',function(e){if(x0===null)return;var dx=e.changedTouches[0].clientX-x0;x0=null;
if(Math.abs(dx)>50)show(cur+(dx<0?1:-1));});
fit();show(fromHash());
})();
")
