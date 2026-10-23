;;;; html.lisp -- HTML emission and the stylesheet.
;;;;
;;;; Every size the fit solver chose is written as a px custom property on its slide or
;;;; table, so the stylesheet stays fixed and the browser lays out exactly what was
;;;; measured. Phrase boundaries become <wbr>; with word-break: keep-all the browser
;;;; breaks Japanese text nowhere else, and the breaks it would still make where the model
;;;; makes none (at a space, after a dash) are taken away.

(in-package :lispresen)

(defparameter *font-stack*
  "'Hiragino Sans','Noto Sans JP','BIZ UDGothic','Yu Gothic',sans-serif")
(defparameter *mono-stack* "Menlo,Consolas,'BIZ UDGothic',monospace")

(defparameter *stylesheet*
  "
*{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%;background:var(--bg);color:var(--fg)}
body{font-family:FONTS}
#stage{position:fixed;inset:0;overflow:hidden;background:var(--bg)}
#deck{position:absolute;left:0;top:0;transform-origin:0 0}
.slide{position:absolute;left:0;top:0;overflow:hidden;background:var(--bg);display:none}
.slide.active{display:block}
html:not(.js) #stage{position:static;overflow:visible}
html.js #stage{touch-action:manipulation}
html:not(.js) #deck{position:static}
html:not(.js) .slide{display:block;position:relative;margin-bottom:32px}
h1,p,li,th,td,caption,figcaption{word-break:keep-all;overflow-wrap:anywhere;line-break:strict}
.nw{white-space:nowrap}
.title{position:absolute;font-size:var(--title);line-height:var(--title-lh);font-weight:700;text-wrap:balance}
.body{position:absolute;display:flex;flex-direction:column;gap:var(--gap);font-size:var(--body);line-height:var(--body-lh)}
ul,ol{padding-left:var(--indent)}
li+li,li>ul,li>ol{margin-top:var(--item-gap)}
li::marker{color:var(--accent)}
code{font-family:MONO;font-size:1em;line-height:1;padding:0 INLINE-PAD;border-radius:.2em}
p code,li code,th code,td code{background:var(--code-bg);color:var(--code-fg)}
pre{flex:none;font-family:MONO;font-size:var(--code);line-height:var(--code-lh);padding:var(--code-pad);background:var(--code-bg);color:var(--code-fg);border-radius:12px;white-space:pre;overflow:hidden}
pre.console{white-space:pre-wrap;line-break:anywhere}
pre code{line-height:inherit;padding:0}
strong{font-weight:700}
mark{background:var(--mark);color:inherit;padding:0 INLINE-PAD;border-radius:.2em}
.accent{color:var(--accent)}
.lead{color:var(--muted)}
.agenda>li{color:var(--muted)}
.agenda>li.current{color:var(--accent);font-weight:700}
.source{position:absolute;font-size:SOURCE;line-height:SOURCE-LH;color:var(--muted)}
#progress{position:absolute;left:0;bottom:0;width:0;height:PROGRESS;background:var(--accent)}
html:not(.js) #progress{display:none}
.kicker{position:absolute;font-size:KICKER;line-height:KICKER-LH;font-weight:700;color:var(--accent);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
a{color:var(--accent)}
figure{flex:1 1 0;min-height:0;display:flex;flex-direction:column;align-items:center}
figure img{flex:1 1 0;min-height:0;max-width:100%;object-fit:contain}
figure video{flex:1 1 0;min-height:0;width:100%;object-fit:contain}
html.js video{cursor:pointer}
figure.bleed{position:absolute;top:0;height:100%;display:block}
.bleed img,.bleed video{display:block;width:100%;height:100%;max-width:none;object-fit:cover}
.bleed.contain img,.bleed.contain video{object-fit:contain}
figcaption{color:var(--muted);text-align:center}
table{flex:none;border-collapse:collapse;table-layout:fixed;font-size:var(--table);line-height:var(--table-lh)}
caption{caption-side:top;text-align:left;color:var(--muted);padding-bottom:var(--cell-y)}
th,td{padding:var(--cell-y) var(--cell-x);text-align:left;vertical-align:top}
thead th{background:var(--head-bg);box-shadow:1px 0 var(--head-bg)}
tbody th,tbody td{box-shadow:inset 0 -1px var(--rule)}
th{font-weight:700}
.right{text-align:right;font-variant-numeric:tabular-nums}
.center{text-align:center}
.centered{position:absolute;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center}
.statement,.section-title,.deck-title{font-size:var(--stmt);line-height:var(--stmt-lh);font-weight:700;text-wrap:balance}
.section-title{color:var(--accent)}
.deck-meta{font-size:META;line-height:META-LH;color:var(--muted)}
.deck-title+.deck-meta{margin-top:48px}
.logo{height:LOGOpx;max-width:100%;object-fit:contain;margin-bottom:48px}
.qr{position:absolute;display:flex;flex-direction:column;gap:QR-GAP;font-size:QR-SIZE;line-height:QR-LH;white-space:nowrap}
.qr.right{align-items:flex-end}
.qr a{color:var(--muted)}
.page,.footer{position:absolute;bottom:24px;font-size:PAGE;line-height:PAGE;color:var(--muted)}
.footer{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.footer a{color:inherit}
.notes{display:none}
.overflow{outline:8px solid #d1242f;outline-offset:-8px}
.slide.measure{display:block;visibility:hidden}
html.check .corrected{outline:8px solid #bf8700;outline-offset:-8px}
#chk{position:fixed;right:8px;top:8px;max-width:60%;max-height:60%;overflow:auto;z-index:20;padding:8px 12px;background:rgba(0,0,0,.8);color:#fff;font:14px/1.5 MONO;white-space:pre-wrap}
.hl-c{color:var(--code-muted)}
.hl-s{color:var(--hl-str)}
.hl-k{color:var(--hl-kw)}
.hl-d{color:var(--hl-def)}
.hl-n{color:var(--hl-const)}
.codebox{flex:none;position:relative}
.out{position:absolute;z-index:5;display:none;border:3px solid var(--accent);border-radius:12px;background:var(--code-bg);box-shadow:0 16px 48px rgba(0,0,0,.35)}
.out.open{display:flex;flex-direction:column}
.out pre{flex:0 1 auto;min-height:0;margin:0;background:none;border-radius:0;padding:var(--code-pad);padding-right:96px;font-family:MONO;font-size:var(--code);line-height:var(--code-lh);color:var(--code-fg);white-space:pre-wrap;overflow-wrap:anywhere;overflow:auto}
.out pre::-webkit-scrollbar{width:20px}
.out pre::-webkit-scrollbar-thumb{background:var(--code-muted);background-clip:padding-box;border:5px solid transparent;border-radius:10px}
.out.err pre{color:var(--hl-def)}
.out.busy pre{color:var(--code-muted)}
.out button{position:absolute;top:8px;right:8px;width:64px;height:64px;border:0;border-radius:10px;background:none;color:var(--code-muted);font:400 48px/1 FONTS;cursor:pointer}
.codebox textarea{position:absolute;left:0;top:0;width:100%;margin:0;border:0;resize:none;outline:4px solid var(--accent);border-radius:12px;padding:var(--code-pad);background:transparent;color:transparent;caret-color:var(--code-fg);font-family:MONO;font-size:var(--code);line-height:var(--code-lh);white-space:pre;overflow:hidden}
.codebox textarea::selection{background:var(--mark)}
.runnable pre.code{cursor:text}
.tools{position:absolute;top:TOOLS-INSET;right:TOOLS-INSET;display:flex;gap:12px}
.band>pre.code,.band>textarea{padding-top:calc(TOOLS-BAND + var(--code-pad))}
.tools button{font:700 28px/1 FONTS;padding:10px 18px;border:0;border-radius:10px;background:var(--accent);color:var(--bg);cursor:pointer;opacity:.85}
.tools button+button{background:var(--rule);color:var(--fg)}
.tools .run[data-state=loading]{background:var(--rule);color:var(--muted)}
.tools .run[data-state=failed]{background:var(--hl-def)}
.pv-bar .rt{color:var(--muted)}
.pv-bar .rt[data-state=failed]{color:var(--hl-def)}
.quote{flex:none;display:block;border-left:8px solid var(--accent);padding-left:calc(var(--indent) - 8px)}
.quote figcaption{margin-top:var(--item-gap);text-align:left}
.columns{flex:none;display:flex;column-gap:COLUMN-GAP}
.columns.grow{flex:1 1 0;min-height:0}
.column{flex:none;display:flex;flex-direction:column;gap:var(--gap);min-height:0}
#ov{position:fixed;inset:0;z-index:10;display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:24px;align-content:start;padding:24px;overflow:auto;background:var(--bg)}
.thumb{position:relative;overflow:clip;outline:1px solid var(--rule);cursor:pointer}
.thumb.sel{outline:4px solid var(--accent)}
.thumb>.slide{transform-origin:0 0}
.thumb>.slide .page,#ov .tools,#ov .out{display:none}
html.ov #stage,html.presenter #stage{visibility:hidden}
#pv{position:fixed;inset:0;display:grid;grid-template-columns:60% 1fr;grid-template-rows:auto auto 1fr;gap:16px;padding:16px;font-size:18px;line-height:1.5}
.pv-bar{grid-column:1/3;display:flex;gap:16px;align-items:center}
.pv-timer{font:700 32px MONO;cursor:pointer}
.pv-bar button{font:inherit;padding:4px 14px}
.pv-cur{grid-row:2/4}
.pv-cur>.thumb{cursor:default}
.pv-next>.thumb{opacity:.85}
.pv-notes{overflow:auto;font-size:24px;line-height:1.6;white-space:pre-wrap}
.say{position:absolute;z-index:4;display:flex;flex-direction:column;justify-content:center;border:3px solid var(--accent);border-radius:16px;background:var(--head-bg);padding:var(--say-y) var(--say-x);font-size:var(--say);line-height:var(--say-lh);-webkit-print-color-adjust:exact;print-color-adjust:exact}
.say::before,.say::after{content:'';position:absolute}
.say::before{background:var(--accent)}
.say::after{background:var(--head-bg)}
.say[data-side=below]::before,.say[data-side=above]::before{left:calc(var(--tail) - 18px);width:36px;height:32px}
.say[data-side=below]::after,.say[data-side=above]::after{left:calc(var(--tail) - 16px);width:32px;height:29px}
.say[data-side=below]::before{top:-35px;clip-path:polygon(50% 0,100% 100%,0 100%)}
.say[data-side=below]::after{top:-29px;clip-path:polygon(50% 0,100% 100%,0 100%)}
.say[data-side=above]::before{bottom:-35px;clip-path:polygon(0 0,100% 0,50% 100%)}
.say[data-side=above]::after{bottom:-29px;clip-path:polygon(0 0,100% 0,50% 100%)}
.say[data-side=right]::before,.say[data-side=left]::before{top:calc(var(--tail) - 18px);width:32px;height:36px}
.say[data-side=right]::after,.say[data-side=left]::after{top:calc(var(--tail) - 16px);width:29px;height:32px}
.say[data-side=right]::before{left:-35px;clip-path:polygon(0 50%,100% 0,100% 100%)}
.say[data-side=right]::after{left:-29px;clip-path:polygon(0 50%,100% 0,100% 100%)}
.say[data-side=left]::before{right:-35px;clip-path:polygon(0 0,100% 50%,0 100%)}
.say[data-side=left]::after{right:-29px;clip-path:polygon(0 0,100% 50%,0 100%)}
.say.front{z-index:6}
.slide.editing .say,.pending{visibility:hidden}
#ov .pending{visibility:visible}
#pv .pending{visibility:visible;opacity:.35}
.ln.dim{opacity:.35}
.slide.editing .ln.dim{opacity:1}
.tk{display:inline-block;transform-origin:0 0}
pre.mo{overflow:visible;background:none;box-shadow:none}
.mb{position:absolute}
.mg{position:absolute;z-index:3;font-family:MONO;white-space:pre;transform-origin:0 0}
figure.dg{flex:none;display:block;position:relative;align-self:center}
.dg-art{position:absolute;left:0;top:0;overflow:visible}
.dg-b{fill:var(--accent)}
.dg-f{fill:var(--head-bg)}
.dg-e{fill:var(--fg)}
.dg-flow{padding:0}
.dg-l{position:absolute;display:block;margin:0;font-size:var(--dg);line-height:var(--dg-lh);text-align:center}
.dg-flow>.dg-l{padding:0 var(--dg-x)}
span.dg-l{font-family:MONO;white-space:pre}
.dg-t,.dg-v{text-align:left}
.dg-v{white-space:nowrap}
figure.math{flex:none;display:block;align-self:center;line-height:0}
svg.mx{overflow:visible;font-weight:400}
figure.math>svg.mx{display:block}
.mx text{fill:currentColor;text-anchor:middle;white-space:pre}
.mx rect,.mx polygon{fill:currentColor}
.mx .i{font-style:italic}
.mx .b{font-weight:700}
figure.pointed{align-items:flex-end}
figure.pointed video{object-position:right}
figure.pointed figcaption{align-self:stretch}
#fs{position:fixed;right:16px;bottom:16px;width:44px;height:44px;border:0;border-radius:8px;background:rgba(127,127,127,.25);color:var(--fg);cursor:pointer;opacity:0;transition:opacity .2s}
#stage.pointer #fs{opacity:1}
#stage.idle{cursor:none}
html:not(.js) #fs{display:none}
")

(defun replace-all (s old new)
  (with-output-to-string (out)
    (let ((start 0))
      (loop
        (let ((at (search old s :start2 start)))
          (write-string s out :start start :end (or at (length s)))
          (if at
              (progn
                (write-string new out)
                (setf start (+ at (length old))))
              (return)))))))

(defun px-string (n) (format nil "~dpx" n))

(defun milli-em-string (n)
  "N milli-em as a CSS length, .125em for 125."
  (format nil ".~3,'0dem" n))

(defun write-stylesheet (deck theme out)
  (let ((width (canvas-width deck)) (content (content-width deck)))
    (write-theme-css theme out)
    (write-string (reduce
                   (lambda (s pair) (replace-all s (first pair) (second pair)))
                   (list (list "FONTS" *font-stack*) (list "MONO" *mono-stack*)
                         (list "INLINE-PAD" (milli-em-string *inline-pad*))
                         (list "META-LH" (px-string (body-lh *meta-size*)))
                         (list "META" (px-string *meta-size*))
                         (list "PAGE" (px-string *page-number-size*))
                         (list "KICKER-LH" (px-string *kicker-lh*))
                         (list "LOGO" (princ-to-string *logo-height*))
                         (list "QR-GAP" (px-string *qr-caption-gap*))
                         (list "QR-SIZE" (px-string *qr-caption-size*))
                         (list "QR-LH" (px-string (qr-lh)))
                         (list "KICKER" (px-string *kicker-size*))
                         (list "SOURCE-LH" (px-string *source-lh*))
                         (list "SOURCE" (px-string *source-size*))
                         (list "PROGRESS" (px-string *progress-height*))
                         (list "COLUMN-GAP" (px-string *column-gap*))
                         (list "TOOLS-INSET" (px-string (third *tools-box*)))
                         (list "TOOLS-BAND"
                               (px-string (+ (second *tools-box*)
                                             (third *tools-box*)))))
                   :initial-value *stylesheet*) out)
    (write-theme-style theme out)
    ;; The canvas-dependent geometry.
    ;; The text region, which a split slide moves beside its image (SLIDE-STYLE).
    (format out ".slide{width:~dpx;height:~dpx;--left:~dpx;--width:~dpx;--right:~dpx}~%"
            width *canvas-height* *margin-x* content *margin-x*)
    (format out
            ".kicker,.title,.body,.centered,.source,.out{left:var(--left);width:var(--width)}~%")
    (format out ".source{bottom:~dpx}~%" *margin-bottom*)
    (format out ".kicker{top:~dpx}~%" (- *margin-top* *kicker-lh* 8))
    (format out ".title,.centered{top:~dpx}~%" *margin-top*)
    (format out ".centered{height:~dpx}~%" (safe-height))
    (format out ".page{right:var(--right)}~%")
    (format out ".footer{left:var(--left);width:calc(var(--width) - ~dpx)}~%"
            (- content (footer-width deck)))
    (format out ".thumb{aspect-ratio:~d/~d}~%" width *canvas-height*)
    (format out
            "@media print{@page{size:~dpx ~dpx;margin:0}#stage{position:static}#deck{transform:none!important}.slide{display:block!important;position:relative;break-after:page}#fs,.tools,#chk,#progress{display:none}.pending{visibility:visible}.ln.dim{opacity:1}html.handout .slide{overflow:visible;clip-path:inset(0 0 -~dpx 0);margin-bottom:~dpx}html.handout .notes{display:block;position:absolute;left:var(--left);width:var(--width);top:~dpx;height:~dpx;overflow:hidden;box-sizing:border-box;padding-top:48px;color:#000;font-size:36px;line-height:1.6;white-space:pre-wrap}}~%"
            width *canvas-height* *canvas-height* *canvas-height* *canvas-height* *canvas-height*)))

;;; --- text ------------------------------------------------------------------------

(defun write-escaped-char (c out)
  (let ((code (char-code c)))
    (cond ((= code 38) (write-string "&amp;" out))
          ((= code 60) (write-string "&lt;" out))
          ((= code 62) (write-string "&gt;" out))
          ((= code 34) (write-string "&quot;" out))
          ((= code 39) (write-string "&#39;" out))
          (t (write-char c out)))))

(defun write-escaped (s out)
  (loop for c across s do (write-escaped-char c out)))

(defun write-inlines (inlines run out)
  "INLINES as HTML, breaking where RUN breaks: a <wbr> at each boundary not next to a
space, a no-break space for a space that is no boundary (1 時間), and a dash kept on one
line with the character after it when that is no boundary, as Chrome breaks after a dash
(2026-10-22, end-to-end) and Safari and the model do not."
  (let ((text (run-text run)) (breaks (run-breaks run)) (i 0) (nowrap nil) (wbr 0))
    (labels ((boundary ()
               ;; A <wbr> before the character at I, once: an element starting there
               ;; writes it before its tag, so the element's padding moves to the next
               ;; line with its text instead of ending the line.
               (when (and (< wbr i (length text)) (svref breaks i)
                          (char/= (char text (1- i)) #\Space)
                          (char/= (char text i) #\Space))
                 (write-string "<wbr>" out)
                 (setf wbr i)))
             (glued-p (k)
               (and (< k (length text)) (not (svref breaks k))
                    (char/= (char text k) #\Space)))
             (chars (s)
               (loop for c across s
                     for j from 1
                     do
                       (let ((dash (and (find c "-‐–") (< j (length s))
                                        (glued-p (1+ i)))))
                         (boundary)
                         (when (and dash (not nowrap))
                           (write-string "<span class=\"nw\">" out)
                           (setf nowrap t))
                         (if (and (char= c #\Space) (glued-p (1+ i)))
                             (write-string "&nbsp;" out)
                             (write-escaped-char c out))
                         (when (and nowrap (not dash))
                           (write-string "</span>" out)
                           (setf nowrap nil)))
                       (incf i)))
             (wrap (tag nodes)
               (format out "<~a>" tag)
               (walk nodes)
               (format out "</~a>" tag))
             (walk (nodes)
               (dolist (n nodes)
                 (unless (stringp n) (boundary))
                 (cond ((stringp n) (chars n))
                       ((eq (car n) :b) (wrap "strong" (second n)))
                       ((eq (car n) :i) (wrap "em" (second n)))
                       ((eq (car n) :mark) (wrap "mark" (second n)))
                       ((eq (car n) :accent)
                        (write-string "<span class=\"accent\">" out)
                        (walk (second n))
                        (write-string "</span>" out))
                       ((eq (car n) :say)
                        (if (callout-side (second n))
                            (progn
                              (write-string "<span" out)
                              (write-attribute "aria-describedby"
                                               (callout-id (second n)) out)
                              (write-string ">" out)
                              (walk (third n))
                              (write-string "</span>" out))
                            (walk (third n))))
                       ((eq (car n) :tt)
                        (write-string "<code>" out)
                        (chars (second n))
                        (write-string "</code>" out))
                       ((eq (car n) :math)
                        ;; One character of the run stands for it.
                        (write-formula (second n) out)
                        (incf i))
                       ((eq (car n) :link)
                        (write-string "<a href=\"" out)
                        (write-escaped (second n) out)
                        (write-string "\">" out)
                        (walk (third n))
                        (write-string "</a>" out))))))
      (walk inlines))))

(defun write-text (s run out) (write-inlines (list s) run out))

;;; --- blocks ------------------------------------------------------------------------

(defun write-described-by (b out)
  "The aria-describedby attribute naming the bubbles of the callouts pointing at the
block B, which the player also finds the anchor by; nothing when there are none."
  (let ((ids
         (loop for c in *slide-callouts*
               when (and (eq (callout-target c) b) (callout-side c))
               collect (callout-id c))))
    (when ids
      (write-attribute "aria-describedby" (format nil "~{~a~^ ~}" ids) out))))

(defun write-list (b out)
  (let ((tag (if (bullets-ordered b) "ol" "ul")))
    (format out "<~a" tag)
    (when (bullets-agenda b) (write-string " class=\"agenda\"" out))
    (write-described-by b out)
    (write-string ">" out)
    (dolist (it (bullets-items b))
      (write-string (if (item-current it) "<li class=\"current\"" "<li") out)
      (when (item-step it) (format out " data-step=\"~d\"" (item-step it)))
      (write-string ">" out)
      (write-inlines (item-inlines it) (item-run it) out)
      (when (item-sublist it) (write-list (item-sublist it) out))
      (write-string "</li>" out))
    (format out "</~a>~%" tag)))

(defun write-code-text (b out)
  (cond ((code-block-steps b)
         (write-stepped-lines (with-output-to-string (s)
                                (write-code-text (copy-code-block-plain b) s))
                              b out))
        ((code-block-morph b) (write-morph-text b out))
        (t (write-highlighted (code-text b) (code-block-lang b) out))))

(defun copy-code-block-plain (b)
  (let ((c (copy-code-block b))) (setf (code-block-steps c) nil) c))

(defun write-stepped-lines (html b out)
  "HTML, the code text of B, with each line in a <span class=\"ln\"> naming the page
advances that bring it forward (data-hot). The highlight spans never nest, so one that
runs across a line end is closed there and opened again on the next line."
  (let ((line 0) (open nil) (i 0) (n (length html)))
    (flet ((start-line ()
             (let ((hot
                    (loop for lines in (code-block-steps b)
                          for k in (code-block-step-numbers b)
                          when (member line lines)
                          collect k)))
               (format out "<span class=\"ln\"~@[ data-hot=\"~{~d~^ ~}\"~]>" hot))
             (when open (write-string open out))))
      (start-line)
      (loop while (< i n)
            do
              (let ((c (char html i)))
                (cond ((char= c #\Newline)
                       (when open (write-string "</span>" out))
                       (write-string "</span>" out)
                       (terpri out)
                       (incf line)
                       (start-line)
                       (incf i))
                      ((char= c #\<)
                       (let* ((end (1+ (position #\> html :start i)))
                              (tag (subseq html i end)))
                         (setf open (if (char= (char tag 1) #\/) nil tag))
                         (write-string tag out)
                         (setf i end)))
                      (t
                       (write-char c out)
                       (incf i)))))
      (write-string "</span>" out))))

(defun write-attribute (name value out)
  (format out " ~a=\"" name)
  (write-escaped value out)
  (write-string "\"" out))

(defun write-code (b out)
  "A code block; runnable code is wrapped in a .codebox carrying what the player needs
to run it. Its output is an overlay the player adds, so it takes no room on the slide."
  (let ((run (code-block-runnable b)))
    (when run
      (write-string (if (code-block-band b)
                        "<div class=\"codebox runnable band\""
                        "<div class=\"codebox runnable\"") out)
      (write-attribute "data-lang" (string-downcase (code-block-lang b)) out)
      (format out " data-timeout=\"~d\"" (code-block-timeout b))
      (when (code-block-fresh b) (write-string " data-fresh" out))
      (when (code-block-stdin b)
        (write-attribute "data-stdin" (code-block-stdin b) out))
      (write-string ">" out))
    (write-string
     (if (console-p b) "<pre class=\"code console\"" "<pre class=\"code\"") out)
    (when (and (code-block-lang b) (not run))
      (write-attribute "data-lang" (code-block-lang b) out))
    (write-described-by b out)
    (when (code-block-step-numbers b)
      (format out " data-focus=\"~{~d~^ ~}\"" (code-block-step-numbers b)))
    (when (consp (code-block-morph b)) (write-string " data-morph" out))
    (write-string "><code>" out)
    (write-code-text b out)
    (write-string "</code></pre>" out)
    (when run (write-string "</div>" out))
    (terpri out)))

(defun write-quote (q out)
  (write-string "<figure class=\"quote\"" out)
  (write-described-by q out)
  (write-string "><blockquote><p>" out)
  (write-inlines (quotation-inlines q) (quotation-run q) out)
  (write-string "</p></blockquote>" out)
  (when (quotation-cite q)
    (write-string "<figcaption>" out)
    (write-text (cite-text q) (quotation-cite-run q) out)
    (write-string "</figcaption>" out))
  (format out "</figure>~%"))

(defun write-columns (r out)
  "A row of columns, each at the width the fit solver gave it. A row holding an image
takes a share of the room the other blocks leave, as the image does."
  (format out "<div class=\"columns~:[~; grow~]\">~%" (grows-p r))
  (loop for c in (column-row-columns r)
        for width in (column-row-widths r)
        do (format out "<div class=\"column\" style=\"width:~dpx\">~%" width)
          (dolist (b (column-blocks c)) (write-block b out))
          (format out "</div>~%"))
  (format out "</div>~%"))

(defun write-media (b out)
  "The <img> of the image B, or the <video> of a video. A video has no autoplay attribute,
which would play it on page load: the player plays it when its slide is entered. Its
controls, which the player removes, let a page without the script still play it."
  (cond ((figure-video b)
         (write-string "<video" out)
         (write-attribute "src" (figure-src b) out)
         (when (figure-poster b) (write-attribute "poster" (figure-poster b) out))
         (when (figure-alt b) (write-attribute "aria-label" (figure-alt b) out))
         (format out " preload=\"metadata\" playsinline controls~:[~; loop~]~:[~; muted~]~:[~; data-autoplay~]></video>"
                 (figure-loop b) (figure-muted b) (figure-autoplay b)))
        (t
         (write-string "<img src=\"" out)
         (write-escaped (figure-src b) out)
         (write-string "\" alt=\"" out)
         (write-escaped (or (figure-alt b) "") out)
         (write-string "\">" out))))

(defun write-bleed (bleed out)
  "The image of a full or split slide, covering its part of the canvas top to bottom, or
shown whole on the background with :fit :contain."
  (destructuring-bind (b left width) bleed
    (format out "<figure class=\"bleed~:[~; contain~]\" style=\"left:~dpx;width:~dpx\">"
            (figure-fit b) left width)
    (write-media b out)
    (format out "</figure>~%")))

(defun write-figure (b out)
  (write-string "<figure" out)
  (when (figure-strip b)
    (format out " class=\"pointed\" style=\"margin-right:~dpx\""
            (figure-strip b)))
  (write-described-by b out)
  (write-string ">" out)
  (write-media b out)
  (when (figure-caption b)
    (write-string "<figcaption>" out)
    (write-text (figure-caption b) (figure-caption-run b) out)
    (write-string "</figcaption>" out))
  (format out "</figure>~%"))

(defun write-diagram (b out)
  "A diagram as an SVG of filled shapes in the theme's colors, with its labels as HTML
over it where the fit solver put them: a flow's boxes as an ordered list, a bar chart's
labels and values as paragraphs, the atoms of cells as spans, the whole of cells named by
the list it draws."
  (let* ((d (diagram-drawing b))
         (s (diagram-size b))
         (w (drawing-width d))
         (h (drawing-height d))
         (flow (eq (diagram-kind b) :flow))
         (shapes (drawing-shapes d)))
    (write-string "<figure class=\"dg\"" out)
    (when (eq (diagram-kind b) :cells)
      (write-string " role=\"img\"" out)
      (write-attribute "aria-label" (print-datum (diagram-datum b)) out))
    (format out
     " style=\"width:~dpx;height:~dpx;--dg:~dpx;--dg-lh:~dpx;--dg-x:~dpx\"" w h
     s (dg-lh s) (dg-pad-x s))
    (write-described-by b out)
    (format out
     ">~%<svg class=\"dg-art\" width=\"~d\" height=\"~d\" aria-hidden=\"true\">"
     w h)
    (dolist (shape shapes)
      (case (car shape)
        (:rect
         (destructuring-bind (x y sw sh class rx) (cdr shape)
           (format out
            "<rect class=\"~a\" x=\"~d\" y=\"~d\" width=\"~d\" height=\"~d\""
            class x y sw sh)
           (when (> rx 0) (format out " rx=\"~d\"" rx))
           (write-string "/>" out)))
        (:poly (format out "<polygon class=\"~a\" points=\"~{~d,~d~^ ~}\"/>"
                       (second shape) (cddr shape)))
        (:dot
         (destructuring-bind (x y r) (cdr shape)
           (format out "<circle class=\"dg-e\" cx=\"~d\" cy=\"~d\" r=\"~d\"/>" x
                   y r)))))
    (format out "</svg>~%")
    (when flow (write-string "<ol class=\"dg-flow\">" out))
    (dolist (shape shapes)
      (case (car shape)
        (:box (destructuring-bind (x y bw bh i pad) (cdr shape)
                (format out
                        "<li class=\"dg-l\" style=\"left:~dpx;top:~dpx;width:~dpx;height:~dpx;padding-top:~dpx\">"
                        x y bw bh pad)
                (write-inlines (nth i (diagram-labels b))
                               (nth i (diagram-runs b)) out)
                (write-string "</li>" out)))
        (:bar-label
         (destructuring-bind (x y lw lh i) (cdr shape)
           (format out
                   "<p class=\"dg-l dg-t\" style=\"left:~dpx;top:~dpx;width:~dpx;height:~dpx\">"
                   x y lw lh)
           (write-inlines (nth i (diagram-labels b)) (nth i (diagram-runs b))
                          out)
           (write-string "</p>" out)))
        (:value
         (destructuring-bind (x y vw vh text) (cdr shape)
           (format out
                   "<p class=\"dg-l dg-v\" style=\"left:~dpx;top:~dpx;width:~dpx;height:~dpx\">"
                   x y vw vh)
           (write-escaped text out)
           (write-string "</p>" out)))
        (:atom (destructuring-bind (x y aw ah text) (cdr shape)
                 (format out
                         "<span class=\"dg-l\" style=\"left:~dpx;top:~dpx;width:~dpx;height:~dpx\">"
                         x y aw ah)
                 (write-escaped text out)
                 (write-string "</span>" out)))))
    (when flow (write-string "</ol>" out))
    (format out "~%</figure>~%")))

(defun write-cell (c tag align scope out)
  (format out "<~a" tag)
  (when scope (format out " scope=\"~a\"" scope))
  (when (member align '("right" "center") :test #'equal)
    (format out " class=\"~a\"" align))
  (when (> (cell-colspan c) 1) (format out " colspan=\"~d\"" (cell-colspan c)))
  (write-string ">" out)
  (write-inlines (cell-inlines c) (cell-run c) out)
  (format out "</~a>" tag))

(defun write-row (row tb header-p out)
  (let ((col 0) (aligns (table-block-aligns tb)))
    (write-string "<tr>" out)
    (dolist (c row)
      (let ((align (nth col aligns)))
        (cond (header-p (write-cell c "th" align "col" out))
              ((and (= col 0) (table-block-row-header tb))
               (write-cell c "th" align "row" out))
              (t (write-cell c "td" align nil out))))
      (incf col (cell-colspan c)))
    (write-string "</tr>" out)))

(defun write-table (b out)
  (let ((s (table-block-size b)))
    (format out
            "<table style=\"--table:~dpx;--table-lh:~dpx;--cell-x:~dpx;--cell-y:~dpx;width:~dpx\""
            s (table-lh s) (cell-pad-x s) (cell-pad-y s)
            (reduce #'+ (table-block-widths b)))
    (write-described-by b out)
    (write-string ">" out)
    (when (table-block-caption b)
      (write-string "<caption>" out)
      (write-text (table-block-caption b) (table-block-caption-run b) out)
      (write-string "</caption>" out))
    (write-string "<colgroup>" out)
    (dolist (w (table-block-widths b))
      (format out "<col style=\"width:~dpx\">" w))
    (write-string "</colgroup>" out)
    (when (table-block-header b)
      (write-string "<thead>" out)
      (write-row (table-block-header b) b t out)
      (write-string "</thead>" out))
    (write-string "<tbody>" out)
    (dolist (row (table-block-rows b)) (write-row row b nil out))
    (format out "</tbody></table>~%")))

(defun write-block (b out)
  (cond ((para-p b)
         (write-string (if (para-lead b) "<p class=\"lead\"" "<p") out)
         (write-described-by b out)
         (write-string ">" out)
         (write-inlines (para-inlines b) (para-run b) out)
         (format out "</p>~%"))
        ((bullets-p b) (write-list b out))
        ((code-block-p b) (write-code b out))
        ((figure-p b) (write-figure b out))
        ((quotation-p b) (write-quote b out))
        ((table-block-p b) (write-table b out))
        ((diagram-p b) (write-diagram b out))
        ((formula-p b)
         (write-string "<figure class=\"math\"" out)
         (write-described-by b out)
         (write-string ">" out)
         (write-formula b out)
         (format out "</figure>~%"))
        ((column-row-p b) (write-columns b out))))

;;; --- slides ------------------------------------------------------------------------

(defun slide-style (deck fit)
  (with-output-to-string (s)
    ;; The text region of a split slide, beside its image.
    (unless (= (fit-width fit) (content-width deck))
      (format s "--left:~dpx;--width:~dpx;--right:~dpx;" (fit-left fit) (fit-width fit)
              (- (canvas-width deck) (fit-left fit) (fit-width fit))))
    (when (fit-title-size fit)
      (format s "--title:~dpx;--title-lh:~dpx;" (fit-title-size fit)
              (title-lh (fit-title-size fit))))
    (when (fit-body-size fit)
      (let ((b (fit-body-size fit)) (c (fit-code-size fit)))
        (format s
                "--body:~dpx;--body-lh:~dpx;--gap:~dpx;--indent:~dpx;--item-gap:~dpx;--code:~dpx;--code-lh:~dpx;--code-pad:~dpx;"
                b (body-lh b) (block-gap b) (list-indent b) (item-gap b) c
                (code-lh c) (code-pad c))))
    (when (fit-stmt-size fit)
      (format s "--stmt:~dpx;--stmt-lh:~dpx;" (fit-stmt-size fit)
              (statement-lh (fit-stmt-size fit))))))

(defun write-callout (c out)
  "The bubble of the callout C, placed where the fit solver found room for it. Its tail
is drawn by the stylesheet at --tail along the side facing the anchor. A step bubble is
shown here; the player hides it until a page advance."
  (let ((s (callout-size c)) (step (callout-step-number c)))
    (format out
            "<div class=\"say~:[~; step~]~:[~; overflow~]\" id=\"~a\" role=\"note\" data-side=\"~a\""
            step (callout-overflow c) (callout-id c) (callout-side c))
    (when step (format out " data-step=\"~d\"" step))
    (when (callout-at c) (write-attribute "data-at" (callout-at c) out))
    (format out
            " style=\"left:~dpx;top:~dpx;width:~dpx;height:~dpx;--say:~dpx;--say-lh:~dpx;--say-x:~dpx;--say-y:~dpx;--tail:~dpx\"><p>"
            (callout-x c) (callout-y c) (callout-width c) (callout-height c) s
            (say-lh s) (say-pad-x s) (say-pad-y s)
            (- (callout-tail c) *say-border*))
    (write-text (callout-text c) (callout-run c) out)
    (format out "</p></div>~%")))

(defun write-title-slide (deck slide out)
  "The centered logo, title and lines under it, and the QR code of the deck's URL in the
corner the fit solver chose."
  (let ((logo (deck-logo deck))
        (url (deck-url deck))
        (qr (fit-qr (slide-fit slide))))
    (write-string "<div class=\"centered\">" out)
    (when logo
      (write-string "<img class=\"logo\"" out)
      (write-attribute "src" (figure-src logo) out)
      (write-attribute "alt" (or (figure-alt logo) "") out)
      (write-string ">" out))
    (write-string "<h1 class=\"deck-title\">" out)
    (write-text (slide-title slide) (slide-title-run slide) out)
    (write-string "</h1>" out)
    (loop for (text run) in (slide-meta slide)
          do (write-string "<p class=\"deck-meta\">" out)
            (write-text text run out) (write-string "</p>" out))
    (format out "</div>~%")
    (when qr
      (destructuring-bind (x y size width align) qr
        (format out
                "<div class=\"qr ~a\" style=\"left:~dpx;top:~dpx;width:~dpx\">"
                align x y width)
        (write-qr-svg url size out)
        (write-string "<p><a" out)
        (write-attribute "href" url out)
        (write-string ">" out)
        (write-escaped url out)
        (format out "</a></p></div>~%")))))

(defun write-slide (deck slide out)
  (let* ((fit (slide-fit slide))
         (layout (fit-layout fit))
         (*slide-callouts* (slide-callouts slide)))
    (format out "<section class=\"slide layout-~a" layout)
    (when (stringp (slide-class slide))
      (write-char #\Space out)
      (write-escaped (slide-class slide) out))
    (when (fit-overflow fit) (write-string " overflow" out))
    (format out "\" id=\"s~d\"" (slide-number slide))
    (when (> (slide-step-count slide) 0)
      (format out " data-steps=\"~d\"" (slide-step-count slide)))
    (format out " style=\"~a\">~%" (slide-style deck fit))
    (when (fit-bleed fit) (write-bleed (fit-bleed fit) out))
    (cond ((equal layout "full"))
          ((equal layout "title") (write-title-slide deck slide out))
          ((equal layout "section")
           (write-string "<div class=\"centered\"><h1 class=\"section-title\">"
                         out)
           (when (slide-title slide)
             (write-text (slide-title slide) (slide-title-run slide) out))
           (format out "</h1></div>~%"))
          ((equal layout "statement")
           (let ((p (car (slide-blocks slide)))
                 (source (source-height slide (fit-width fit))))
             (write-string "<div class=\"centered\"" out)
             (when (> source 0)
               (format out " style=\"height:~dpx\"" (- (safe-height) source)))
             (write-string "><p class=\"statement\">" out)
             (write-inlines (para-inlines p) (para-run p) out)
             (format out "</p></div>~%")))
          (t
           (when (and (slide-kicker-run slide) (slide-title slide))
             (write-string "<p class=\"kicker\">" out)
             (write-text (slide-kicker slide) (slide-kicker-run slide) out)
             (format out "</p>~%"))
           (when (slide-title slide)
             (write-string "<h1 class=\"title\">" out)
             (write-text (slide-title slide) (slide-title-run slide) out)
             (format out "</h1>~%"))
           (format out "<div class=\"body\" style=\"top:~dpx;height:~dpx\">~%"
                   (fit-body-top fit) (fit-body-height fit))
           (dolist (b (slide-blocks slide)) (write-block b out))
           (format out "</div>~%")
           (dolist (c (slide-callouts slide)) (write-callout c out))))
    (when (slide-source-run slide)
      (write-string "<p class=\"source\">" out)
      (write-inlines (slide-source slide) (slide-source-run slide) out)
      (format out "</p>~%"))
    (when (footer-shown-p deck slide)
      (write-string "<p class=\"footer\">" out)
      (write-inlines (deck-footer deck) (deck-footer-run deck) out)
      (format out "</p>~%"))
    (when (page-number-shown-p deck slide)
      (format out "<div class=\"page\">~d</div>~%" (slide-number slide)))
    (when (slide-notes slide)
      (write-string "<aside class=\"notes\">" out)
      (write-escaped (slide-notes slide) out)
      (format out "</aside>~%"))
    (format out "</section>~%")))

(defparameter *full-screen-icon*
  "<svg viewBox=\"0 0 24 24\" width=\"24\" height=\"24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5\"/></svg>")

(defun write-deck (deck theme out)
  (write-string "<!DOCTYPE html>
<html lang=\"" out)
  (write-escaped (or (deck-lang deck) "ja") out)
  (format out "\">~%<head>~%<meta charset=\"utf-8\">~%")
  (format out
   "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">~%")
  (write-string "<title>" out)
  (write-escaped (or (deck-title deck) "lispresen") out)
  (format out "</title>~%")
  (write-preview-meta deck out)
  (format out "<style>~%")
  (write-stylesheet deck theme out)
  (format out "</style>~%</head>~%<body>~%<div id=\"stage\">~%")
  (format out "<div id=\"deck\" style=\"width:~dpx;height:~dpx\">~%"
          (canvas-width deck) *canvas-height*)
  (dolist (slide (deck-slides deck)) (write-slide deck slide out))
  ;; The player sets its length; it is no slide's, so thumbnails and print leave it out.
  (when (deck-progress deck)
    (format out "<div id=\"progress\" aria-hidden=\"true\"></div>~%"))
  (format out
          "</div>~%<button id=\"fs\" title=\"Full screen (F)\" aria-label=\"Full screen\">~a</button>~%</div>~%"
          *full-screen-icon*)
  (write-config deck out)
  (format out "<script>(function(){~a~a~a~a~a})();</script>~%</body>~%</html>~%"
          *player-script* (if (deck-runnable-p deck) *runner-script* "")
          (if (some #'slide-callouts (deck-slides deck)) *say-script* "")
          (if (deck-video-p deck) *video-script* "")
          *start-script*))

(defun write-meta (attribute name content out)
  (write-string "<meta" out)
  (write-attribute attribute name out)
  (write-attribute "content" content out)
  (format out ">~%"))

(defun write-preview-meta (deck out)
  "The page's description and, with a :page-url, the Open Graph and Twitter Card tags a
link preview is built from; nothing for a deck that sets neither."
  (let ((description (deck-description deck))
        (url (deck-page-url deck))
        (image (deck-page-image deck)))
    (when description (write-meta "name" "description" description out))
    (when url
      (write-meta "property" "og:type" "website" out)
      (write-meta "property" "og:url" url out)
      (write-meta "property" "og:title" (or (deck-title deck) "lispresen") out)
      (when description (write-meta "property" "og:description" description out))
      (when image
        (write-meta "property" "og:image" (resolve-url url image) out)
        (when (drawn-page-image-p deck)
          (write-meta "property" "og:image:width" (princ-to-string *page-image-width*) out)
          (write-meta "property" "og:image:height" (princ-to-string *page-image-height*)
                      out)))
      (write-meta "name" "twitter:card" (if image "summary_large_image" "summary") out))))

(defparameter *hosted-runtime* "https://making.github.io/rontolisp/")

(defun deck-video-p (deck)
  "True when a slide of DECK holds a video, in its body or beside it."
  (some (lambda (slide)
          (let ((bleed (fit-bleed (slide-fit slide))))
            (or (and bleed (figure-video (first bleed)))
                (some (lambda (b) (and (figure-p b) (figure-video b)))
                      (region-blocks (slide-blocks slide))))))
        (deck-slides deck)))

(defun deck-runnable-p (deck)
  (some (lambda (slide)
          (some (lambda (b) (and (code-block-p b) (code-block-runnable b)))
                (region-blocks (slide-blocks slide)))) (deck-slides deck)))

(defun write-config (deck out)
  "The numbers the player needs: the type scale for the runtime check, the geometry of a
callout's bubble, and where the runtime is when the deck has runnable code."
  (write-string "<script type=\"application/json\" id=\"lp\">{\"runtime\":" out)
  (if (deck-runnable-p deck)
      (let ((r (deck-runtime deck)))
        (write-char (code-char 34) out)
        (write-json-chars (if (stringp r) r *hosted-runtime*) out)
        (write-char (code-char 34) out))
      (write-string "null" out))
  (format out ",\"top\":~d,\"bottom\":~d,\"gap\":~d,\"steps\":{" *margin-top*
          *margin-bottom* *title-gap*)
  (format out
          "\"title\":[~{~d~^,~}],\"body\":[~{~d~^,~}],\"code\":[~{~d~^,~}],\"table\":[~{~d~^,~}],\"stmt\":[~{~d~^,~}]},"
          *title-steps* *body-steps* *code-steps* *table-steps*
          *statement-steps*)
  (format out
   "\"say\":{\"reach\":~d,\"half\":~d,\"inset\":~d,\"clear\":~d,\"border\":~d}}"
   *tail-reach* *tail-half* *tail-inset* *say-clearance* *say-border*)
  (format out "</script>~%"))

(defun write-json-chars (s out)
  "S inside a JSON string in a script element: quotes, backslashes, control characters
and < escaped."
  (loop for c across s
        do
          (let ((code (char-code c)))
            (cond ((or (= code 34) (= code 92))
                   (write-char (code-char 92) out)
                   (write-char c out))
                  ((or (< code 32) (= code 60)) (format out "\\u~4,'0x" code))
                  (t (write-char c out))))))
