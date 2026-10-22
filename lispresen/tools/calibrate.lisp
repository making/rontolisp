;;;; calibrate.lisp -- compare the width model with a browser.
;;;;
;;;;   rontolisp lispresen/tools/calibrate.lisp -- calibrate.html
;;;;
;;;; Writes a page of sample paragraphs (Japanese, Latin, mixed, bold, inline code,
;;;; brackets) at several sizes and widths, each carrying the line count the model
;;;; predicts. Opened in a browser, the page compares them with the lines it renders and
;;;; prints the result at the top. The model must never predict fewer lines than the
;;;; browser renders; predicting one more is the margin's cost. The page also sets every
;;;; printable ASCII character in both weights and reports the ones wider than the model's
;;;; width: a paragraph meets a line boundary only by chance, a character always.

(load "../src/all.lisp")

(in-package :lispresen)

(defparameter *samples*
  (list
   (list
    "rontolispはCommon Lispのサブセットで、インタプリタ、JVM、WebAssemblyの三つのバックエンドで同じプログラムを実行できる。発表では、それぞれの仕組みと性能を比べながら、実際にコードを動かして違いを確かめる。")
   (list
    "聴衆が読みやすいプレゼンテーションを作るために、文字の大きさと配置をツールが自動で調整し、最小の大きさでも収まらないスライドはエラーとして報告する。")
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
   (list
    "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM")))

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
document.getElementById('result').textContent=ps.length+' paragraphs; model under browser: '+under.length+
(under.length?' ('+under.join('; ')+')':'')+'; model one or more lines over: '+over+
'; characters wider than the model: '+wide.length+(wide.length?' ('+wide.join('; ')+')':'');});
")

(defun write-calibration (path)
  (with-open-file (out path
                       :direction :output
                       :if-exists :supersede
                       :if-does-not-exist :create)
    (format out
            "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"utf-8\"><style>body{margin:16px;font-family:~a}code{font-family:~a;font-size:1em;line-height:1}p{word-break:keep-all;overflow-wrap:anywhere;line-break:strict;margin:0 0 20px}#result{font:16px monospace;margin-bottom:24px}</style></head><body><div id=\"result\">measuring...</div>~%"
            *font-stack* *mono-stack*)
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
            "<script>var MODEL={normal:[~{~d~^,~}],bold:[~{~d~^,~}]};~a</script></body></html>~%"
            (loop for c from 32 to 126 collect (char-width c :normal))
            (loop for c from 32 to 126 collect (char-width c :bold))
            *check-script*)))

(write-calibration (or (car (uiop:command-line-arguments)) "calibrate.html"))
