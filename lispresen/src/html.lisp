;;;; html.lisp -- HTML emission and the stylesheet.
;;;;
;;;; Every size the fit solver chose is written as a px custom property on its slide or
;;;; table, so the stylesheet stays fixed and the browser lays out exactly what was
;;;; measured. Phrase boundaries become <wbr>; with word-break: keep-all the browser
;;;; breaks Japanese text nowhere else.

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
html:not(.js) #deck{position:static}
html:not(.js) .slide{display:block;position:relative;margin-bottom:32px}
h1,p,li,th,td,caption,figcaption{word-break:keep-all;overflow-wrap:anywhere;line-break:strict}
.title{position:absolute;font-size:var(--title);line-height:var(--title-lh);font-weight:700;text-wrap:balance}
.body{position:absolute;display:flex;flex-direction:column;gap:var(--gap);font-size:var(--body);line-height:var(--body-lh)}
ul,ol{padding-left:var(--indent)}
li+li,li>ul,li>ol{margin-top:var(--item-gap)}
li::marker{color:var(--accent)}
code{font-family:MONO;font-size:1em;line-height:1}
p code,li code,th code,td code{background:var(--code-bg);border-radius:.2em}
pre{flex:none;font-family:MONO;font-size:var(--code);line-height:var(--code-lh);padding:var(--code-pad);background:var(--code-bg);border-radius:12px;white-space:pre;overflow:hidden}
pre code{line-height:inherit}
strong{font-weight:700}
mark{background:var(--mark);color:inherit}
a{color:var(--accent)}
figure{flex:1 1 0;min-height:0;display:flex;flex-direction:column;align-items:center}
figure img{flex:1 1 0;min-height:0;max-width:100%;object-fit:contain}
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
.page{position:absolute;bottom:24px;font-size:PAGE;line-height:PAGE;color:var(--muted)}
.notes{display:none}
.overflow{outline:8px solid #d1242f;outline-offset:-8px}
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

(defun write-stylesheet (deck theme out)
  (let ((width (canvas-width deck)) (content (content-width deck)))
    (write-theme-css theme out)
    (write-string (reduce
                   (lambda (s pair) (replace-all s (first pair) (second pair)))
                   (list (list "FONTS" *font-stack*) (list "MONO" *mono-stack*)
                         (list "META-LH" (px-string (body-lh *meta-size*)))
                         (list "META" (px-string *meta-size*))
                         (list "PAGE" (px-string *page-number-size*)))
                   :initial-value *stylesheet*) out)
    ;; The canvas-dependent geometry.
    (format out ".slide{width:~dpx;height:~dpx}~%" width *canvas-height*)
    (format out ".title,.body,.centered{left:~dpx;width:~dpx}~%" *margin-x*
            content)
    (format out ".title,.centered{top:~dpx}~%" *margin-top*)
    (format out ".centered{height:~dpx}~%" (safe-height))
    (format out ".page{right:~dpx}~%" *margin-x*)
    (format out
            "@media print{@page{size:~dpx ~dpx;margin:0}#stage{position:static}#deck{transform:none!important}.slide{display:block!important;position:relative;break-after:page}#fs{display:none}}~%"
            width *canvas-height*)))

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
  "INLINES as HTML, with <wbr> at each phrase boundary of RUN not next to a space."
  (let ((text (run-text run)) (breaks (run-breaks run)) (i 0))
    (labels ((chars (s)
               (loop for c across s
                     do
                       (when (and (> i 0) (svref breaks i)
                                  (char/= (char text (1- i)) #\Space)
                                  (char/= c #\Space))
                         (write-string "<wbr>" out)) (write-escaped-char c out)
                       (incf i)))
             (wrap (tag nodes)
               (format out "<~a>" tag)
               (walk nodes)
               (format out "</~a>" tag))
             (walk (nodes)
               (dolist (n nodes)
                 (cond ((stringp n) (chars n))
                       ((eq (car n) :b) (wrap "strong" (second n)))
                       ((eq (car n) :i) (wrap "em" (second n)))
                       ((eq (car n) :mark) (wrap "mark" (second n)))
                       ((eq (car n) :tt)
                        (write-string "<code>" out)
                        (chars (second n))
                        (write-string "</code>" out))
                       ((eq (car n) :link)
                        (write-string "<a href=\"" out)
                        (write-escaped (second n) out)
                        (write-string "\">" out)
                        (walk (third n))
                        (write-string "</a>" out))))))
      (walk inlines))))

(defun write-text (s run out) (write-inlines (list s) run out))

;;; --- blocks ------------------------------------------------------------------------

(defun write-list (b out)
  (let ((tag (if (bullets-ordered b) "ol" "ul")))
    (format out "<~a>" tag)
    (dolist (it (bullets-items b))
      (write-string "<li>" out)
      (write-inlines (item-inlines it) (item-run it) out)
      (when (item-sublist it) (write-list (item-sublist it) out))
      (write-string "</li>" out))
    (format out "</~a>~%" tag)))

(defun write-code (b out)
  (write-string "<pre class=\"code\"" out)
  (when (code-block-lang b)
    (write-string " data-lang=\"" out)
    (write-escaped (code-block-lang b) out)
    (write-string "\"" out))
  (write-string "><code>" out)
  (let ((first t))
    (dolist (line (code-block-lines b))
      (unless first (write-char #\Newline out))
      (setf first nil)
      (write-escaped line out)))
  (format out "</code></pre>~%"))

(defun write-figure (b out)
  (write-string "<figure><img src=\"" out)
  (write-escaped (figure-src b) out)
  (write-string "\" alt=\"" out)
  (write-escaped (or (figure-alt b) "") out)
  (write-string "\">" out)
  (when (figure-caption b)
    (write-string "<figcaption>" out)
    (write-text (figure-caption b) (figure-caption-run b) out)
    (write-string "</figcaption>" out))
  (format out "</figure>~%"))

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
            "<table style=\"--table:~dpx;--table-lh:~dpx;--cell-x:~dpx;--cell-y:~dpx;width:~dpx\">"
            s (table-lh s) (cell-pad-x s) (cell-pad-y s)
            (reduce #'+ (table-block-widths b)))
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
         (write-string "<p>" out)
         (write-inlines (para-inlines b) (para-run b) out)
         (format out "</p>~%"))
        ((bullets-p b) (write-list b out))
        ((code-block-p b) (write-code b out))
        ((figure-p b) (write-figure b out))
        ((table-block-p b) (write-table b out))))

;;; --- slides ------------------------------------------------------------------------

(defun slide-style (fit)
  (with-output-to-string (s)
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

(defun write-slide (deck slide out)
  (let* ((fit (slide-fit slide)) (layout (fit-layout fit)))
    (format out "<section class=\"slide layout-~a" layout)
    (when (stringp (slide-class slide))
      (write-char #\Space out)
      (write-escaped (slide-class slide) out))
    (when (fit-overflow fit) (write-string " overflow" out))
    (format out "\" id=\"s~d\" style=\"~a\">~%" (slide-number slide)
            (slide-style fit))
    (cond ((equal layout "title")
           (write-string "<div class=\"centered\"><h1 class=\"deck-title\">"
                         out)
           (write-text (slide-title slide) (slide-title-run slide) out)
           (write-string "</h1>" out)
           (dolist (meta (list (deck-author deck) (deck-date deck)))
             (when meta
               (write-string "<p class=\"deck-meta\">" out)
               (write-escaped meta out)
               (write-string "</p>" out)))
           (format out "</div>~%"))
          ((equal layout "section")
           (write-string "<div class=\"centered\"><h1 class=\"section-title\">"
                         out)
           (when (slide-title slide)
             (write-text (slide-title slide) (slide-title-run slide) out))
           (format out "</h1></div>~%"))
          ((equal layout "statement")
           (let ((p (car (slide-blocks slide))))
             (write-string "<div class=\"centered\"><p class=\"statement\">"
                           out)
             (write-inlines (para-inlines p) (para-run p) out)
             (format out "</p></div>~%")))
          (t
           (when (slide-title slide)
             (write-string "<h1 class=\"title\">" out)
             (write-text (slide-title slide) (slide-title-run slide) out)
             (format out "</h1>~%"))
           (format out "<div class=\"body\" style=\"top:~dpx;height:~dpx\">~%"
                   (fit-body-top fit) (fit-body-height fit))
           (dolist (b (slide-blocks slide)) (write-block b out))
           (format out "</div>~%")))
    (when (and (deck-page-numbers deck) (not (eq (slide-kind slide) :title)))
      (format out "<div class=\"page\">~d</div>~%" (slide-number slide)))
    (when (slide-notes slide)
      (write-string "<aside class=\"notes\">" out)
      (write-escaped (slide-notes slide) out)
      (format out "</aside>~%"))
    (format out "</section>~%")))

(defparameter *full-screen-icon*
  "<svg viewBox=\"0 0 24 24\" width=\"24\" height=\"24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5\"/></svg>")

(defun write-deck (deck theme out)
  (format out
   "<!DOCTYPE html>~%<html lang=\"ja\">~%<head>~%<meta charset=\"utf-8\">~%")
  (format out
   "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">~%")
  (write-string "<title>" out)
  (write-escaped (or (deck-title deck) "lispresen") out)
  (format out "</title>~%<style>~%")
  (write-stylesheet deck theme out)
  (format out "</style>~%</head>~%<body>~%<div id=\"stage\">~%")
  (format out "<div id=\"deck\" style=\"width:~dpx;height:~dpx\">~%"
          (canvas-width deck) *canvas-height*)
  (dolist (slide (deck-slides deck)) (write-slide deck slide out))
  (format out
          "</div>~%<button id=\"fs\" title=\"Full screen (F)\" aria-label=\"Full screen\">~a</button>~%</div>~%"
          *full-screen-icon*)
  (format out "<script>~a</script>~%</body>~%</html>~%" *player-script*))
