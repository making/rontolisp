;;;; calibrate.lisp -- compare the width model with a browser.
;;;;
;;;;   rontolisp lispresen/tools/calibrate.lisp -- calibrate.html
;;;;
;;;; Writes a page of sample paragraphs (Japanese, Latin, mixed, bold, inline code, a
;;;; marker, brackets) at several sizes and widths, each carrying the line count the model
;;;; predicts. Opened in a browser, the page compares them with the lines it renders and
;;;; prints the result at the top. The model must never predict fewer lines than the
;;;; browser renders; predicting one more is the margin's cost. The page also sets every
;;;; printable ASCII character in both weights and reports the ones wider than the model's
;;;; width: a paragraph meets a line boundary only by chance, a character always. The
;;;; emoji, the symbol blocks and the Greek letters are checked the same way, and the ink
;;;; heights the formula layout assumes (the axis, the x-height, the delimiters) against
;;;; what the browser measures.

(load "../src/all.lisp")

(in-package :lispresen)

(defparameter *samples*
  (list
   (list
    "rontolispはCommon Lispのサブセットで、インタプリタ、JVM、WebAssemblyの三つのバックエンドで同じプログラムを実行できる。発表では、それぞれの仕組みと性能を比べながら、実際にコードを動かして違いを確かめる。")
   (list
    "聴衆が読みやすいプレゼンテーションを作るために、" (list :mark (list "文字の大きさと配置"))
    "をツールが自動で調整し、最小の大きさでも収まらないスライドはエラーとして報告する。")
   (list
    "The quick brown fox jumps over the lazy dog while WebAssembly modules compile ahead of time and the JVM warms up its just-in-time compiler.")
   (list "Use " (list :tt "read-from-string") " with " (list :tt ":start")
         " to read each top-level form, then record its line number for diagnostics and keep going until "
         (list :tt ":eof") ".")
   (list (list :b (list "太字の見出し語")) "と通常の文字が混ざる段落で、"
         (list :b (list "Bold Latin WORDS"))
         "も含めて幅を見積もる。数値 1,234,567 や 98.6% も入れる。")
   (list
    (list :b (list "lispresen: S式で書くプレゼンテーションとBold Latin Titles of a Deck")))
   (list "「かぎ括弧」『二重かぎ括弧』（丸括弧）、そして句読点。、が行頭に来ないように禁則処理を確かめるための少し長めの文章をここに書いておく。")
   (list "デプロイ完了 ✅ 速度は 🚀 倍、失敗は ❌ 件。開発者 👨‍💻 と 🇯🇵 の利用者が ❤️ を送った。")
   (list "計算量は "
         (list :math (lispresen::parse-math '(math (O (* n (log n)))) nil))
         " で、分散 " (list :math (lispresen::parse-math '(math (= (^ sigma 2) (/ (sum (= i 1) n (^ (- (_ x i) mu) 2)) n))) nil))
         " は x の散らばりを表す。")
   (list
    "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM")))

(defparameter *emoji*
  '("🚀" "✅" "✨" "❌" "⚠️" "❤️" "⭐" "😀" "🔥" "📦" "✔️" "➡️" "⏭" "🙏" "🫠" "🩷" "🇯🇵" "🇺🇸"
    "👨‍💻" "👨‍👩‍👧‍👦" "👍🏽" "1️⃣" "☀️" "✂️" "🗓️" "🧠")
  "Emoji and emoji sequences the page sets alone and compares with the model's width.")

(defun symbol-table ()
  "Flat list of (code normal-width bold-width) for Latin-1 symbols, the accents and
Greek formulas set, punctuation and symbol blocks up to U+2BFF, and the tiles and
enclosed letters of U+1F000-1F2FF."
  (loop for (a b) in '((#xA0 #xFF) (#x2C6 #x2DC) (#x370 #x3FF) (#x2000 #x2BFF) (#x1F000 #x1F2FF))
        append (loop for c from a to b
                     append (list c (char-width c :normal) (char-width c :bold)))))

(defparameter *check-script*
  "
document.fonts.ready.then(function(){
var ps=document.querySelectorAll('p[data-model]'),under=[],over=0;
ps.forEach(function(p,i){var lines=Math.round(p.offsetHeight/parseFloat(p.style.lineHeight));
var m=+p.dataset.model;if(lines>m)under.push(i+': model '+m+', browser '+lines);else if(lines<m)over++;});
var wide=[];[['400',MODEL.normal],['700',MODEL.bold]].forEach(function(t){for(var c=32;c<127;c++){
var s=document.createElement('span');s.style.cssText='font-size:1000px;white-space:pre;font-weight:'+t[0];
s.textContent=String.fromCharCode(c).repeat(20);document.body.appendChild(s);var w=s.getBoundingClientRect().width/20;s.remove();
if(w>t[1][c-32])wide.push(t[0]+' '+String.fromCharCode(c)+' '+Math.round(w)+'>'+t[1][c-32]);}});
var emo=[];EMOJI.forEach(function(e){['400','700'].forEach(function(t){var s=document.createElement('span');
s.style.cssText='font-size:1000px;white-space:pre;font-weight:'+t+';font-family:'+getComputedStyle(document.body).fontFamily;
s.textContent=e[0].repeat(20);document.body.appendChild(s);var w=s.getBoundingClientRect().width/20;s.remove();
if(w>e[1])emo.push(e[0]+' '+t+' '+Math.round(w)+'>'+e[1]);});});
var sym=[],host=document.createElement('div');document.body.appendChild(host);
SYMBOLS.forEach(function(e){var ch=String.fromCodePoint(e[0]);if(/\p{Cn}|\p{C}|\p{M}|\p{Z}/u.test(ch))return;
[['400',e[1]],['700',e[2]]].forEach(function(t){var s=document.createElement('span');
s.style.cssText='font-size:1000px;white-space:pre;font-weight:'+t[0]+';font-family:'+getComputedStyle(document.body).fontFamily;
s.textContent=ch.repeat(4);host.appendChild(s);var w=s.getBoundingClientRect().width/4;s.remove();
if(w>t[1]+0.5)sym.push(e[0].toString(16)+' '+t[0]+' '+Math.round(w)+'>'+t[1]);});});
var cx=document.createElement('canvas').getContext('2d'),vm=[];cx.font='1000px '+getComputedStyle(document.body).fontFamily;
function ink(ch){var m=cx.measureText(ch);return [Math.round(m.actualBoundingBoxAscent),Math.round(m.actualBoundingBoxDescent)];}
MATHM.forEach(function(e){var a=ink(e[0]),asc=e[1]==='axis'?Math.round((a[0]-a[1])/2):a[0];
if(Math.abs(asc-e[2])>10||(e[1]!=='axis'&&Math.abs(a[1]-e[3])>10))vm.push(e[1]+' '+e[0]+' '+asc+(e[1]==='axis'?'':'/'+a[1])+' (model '+e[2]+(e[1]==='axis'?'':'/'+e[3])+')');});
document.getElementById('result').textContent=ps.length+' paragraphs; model under browser: '+under.length+
(under.length?' ('+under.join('; ')+')':'')+'; model one or more lines over: '+over+
'; characters wider than the model: '+wide.length+(wide.length?' ('+wide.join('; ')+')':'')+
'; emoji wider than the model: '+emo.length+(emo.length?' ('+emo.join('; ')+')':'')+
'; symbols wider than the model: '+sym.length+(sym.length?' ('+sym.join('; ')+')':'')+
'; formula metrics off by more than 10 milli-em: '+vm.length+(vm.length?' ('+vm.join('; ')+')':'');});
")

(defun math-metrics ()
  "Flat list of (glyph name ascent descent): the vertical metrics the formula layout
assumes, checked against the ink the browser measures."
  (append
   (list "+" "axis" lispresen::*math-axis* 0)
   (loop for (glyph name) in '(("x" "x-height") ("H" "capital") ("2" "digit") ("g" "descender")
                               ("(" "paren") ("[" "bracket") ("{" "brace") ("∑" "sum")
                               ("∫" "integral") ("√" "radical") ("α" "alpha") ("β" "beta"))
         for e = (if (string= glyph "√")
                     (cons (first lispresen::*radical-glyph*) (second lispresen::*radical-glyph*))
                     (lispresen::glyph-extent (char glyph 0)))
         append (list glyph name (car e) (cdr e)))))

(defun write-calibration (path)
  (with-open-file (out path
                       :direction :output
                       :if-exists :supersede
                       :if-does-not-exist :create)
    (format out
            "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"utf-8\"><style>body{margin:16px;font-family:~a}code{font-family:~a;font-size:1em;line-height:1}code,mark{padding:0 ~a}p{word-break:keep-all;overflow-wrap:anywhere;line-break:strict;margin:0 0 20px}#result{font:16px monospace;margin-bottom:24px}</style></head><body><div id=\"result\">measuring...</div>~%"
            *font-stack* *mono-stack* (milli-em-string *inline-pad*))
    (dolist (width '(1696 1100 700))
      (dolist (s '(56 48 40 120))
        (dolist (sample *samples*)
          (let ((run (make-text-run sample)))
            (format out
                    "<p data-model=\"~d\" style=\"font-size:~dpx;line-height:~dpx;width:~dpx\">"
                    (run-lines run s width) s (body-lh s) width)
            (write-inlines sample run out)
            (format out "</p>~%")))))
    ;; The model's milli-em width of each printable ASCII character, margin included.
    (format out
            "<script>var MODEL={normal:[~{~d~^,~}],bold:[~{~d~^,~}]},EMOJI=[~{[\"~a\",~d]~^,~}],SYMBOLS=[~{[~d,~d,~d]~^,~}],MATHM=[~{[\"~a\",\"~a\",~d,~d]~^,~}];~a</script></body></html>~%"
            (loop for c from 32 to 126 collect (char-width c :normal))
            (loop for c from 32 to 126 collect (char-width c :bold))
            (loop for e in *emoji*
                  append (list e (run-full-width (make-text-run (list e)) 1000)))
            (symbol-table)
            (math-metrics)
            *check-script*)))

(write-calibration (or (car (uiop:command-line-arguments)) "calibrate.html"))
