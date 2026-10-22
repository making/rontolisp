;;;; lispresen-test.lisp -- unit tests; run by check.sh on the interpreter.
;;;;
;;;; The rendered HTML as a whole is checked by the golden deck (examples/intro.html);
;;;; these tests pin the pieces the golden deck cannot reach: boundary cases of the
;;;; measurement, the solver's steps and failures, and the diagnostics.

#-rontolisp (require :asdf)

#+rontolisp (load "../src/all.lisp")
#-rontolisp (load (merge-pathnames "../src/all.lisp" *load-truename*))
(asdf:load-system :rove)

(defpackage :lispresen-test (:use :cl :rove))
(in-package :lispresen-test)

(setf *enable-colors* nil)

(defun render-diagnostics (source)
  "The diagnostics of SOURCE as (SEVERITY LINE MESSAGE) lists."
  (multiple-value-bind (html diagnostics) (lispresen:render source)
    (declare (ignore html))
    (mapcar (lambda (d)
              (list (lispresen:diagnostic-severity d)
                    (lispresen::diagnostic-line d)
                    (lispresen::diagnostic-message d))) diagnostics)))

(defun contains (html part) (and (search part html) t))

(deftest phrase-boundaries
  (testing "phrases match BudouX's own parser"
    (ok
     (equal (lispresen::phrases "プレゼンテーションの文字サイズを自動で調整する")
            '("プレゼンテーションの" "文字サイズを" "自動で" "調整する")))
    (ok
     (equal (lispresen::phrases "rontolispで作るプレゼンツール")
            '("rontolispで" "作る" "プレゼンツール"))))
  (testing "a phrase boundary becomes <wbr>, a space does not need one"
    (let ((html (lispresen:render "(slide \"聴衆が読みやすいプレゼン in HTML\")")))
      (ok (contains html "聴衆が<wbr>読みやすい<wbr>プレゼン in HTML")))))

(deftest line-wrapping
  (testing "full-width text is exactly 1 em per character"
    (let ((run (lispresen::make-text-run (list "一二三四五六七八九十"))))
      (ok (= (lispresen::run-lines run 56 560) 1))
      (ok (= (lispresen::run-lines run 56 559) 2))))
  (testing "Latin breaks at spaces, and a space ending a line takes no width"
    ;; aaa = 3 x 604 milli-em, the space 340, bbb = 3 x 677: 418.3 px at 100 px.
    (let ((run (lispresen::make-text-run (list "aaa bbb"))))
      (ok (= (lispresen::run-lines run 100 419) 1))
      (ok (= (lispresen::run-lines run 100 418) 2))
      (ok (= (lispresen::run-min-width run 100) 204))
      (ok (= (lispresen::run-full-width run 100) 419))))
  (testing "inline code breaks only at its spaces"
    (let ((run
           (lispresen::make-text-run
            (list "x " (list :tt "read-from-string")))))
      (ok (equal (mapcar #'first (lispresen::run-units run)) '(0 2))))))

(defparameter *long-item* "\"とても長い項目を何度も繰り返して書くことで、このスライドの文字が小さくなる。\"")

(defun slide-with-items (n)
  (format nil "(slide :title \"t\" (ul~{ ~a~}))"
          (loop repeat n collect *long-item*)))

(deftest fit-solver
  (testing "a slide steps down only as far as it must"
    (ok (contains (lispresen:render (slide-with-items 2)) "--body:56px"))
    (ok (contains (lispresen:render (slide-with-items 5)) "--body:48px")))
  (testing "a slide that does not fit at the hard floor is marked and reported"
    (let ((html (lispresen:render (slide-with-items 12))))
      (ok (contains html "--body:40px"))
      (ok (contains html "layout-standard overflow")))
    (ok
     (find-if
      (lambda (d) (and (eq (first d) :error) (search "does not fit" (third d))))
      (render-diagnostics (slide-with-items 12)))))
  (testing "columns share the size their fullest column needs"
    (flet ((columns (a b)
             (format nil "(slide :title \"t\" (column ~a) (column ~a))" a b)))
      (let ((long
             (format nil "(ul~{ ~a~})" (loop repeat 4 collect *long-item*))))
        (ok
         (contains (lispresen:render (columns "\"x\"" "\"y\"")) "--body:56px"))
        ;; A short column takes only its width, which leaves the long one room at 56px.
        (ok (contains (lispresen:render (columns long "\"y\"")) "--body:56px"))
        (ok (contains (lispresen:render (columns long long)) "--body:40px"))))
    (testing "a block above the columns spans them, a code column is as wide as its lines"
      ;; 40 characters at 44px are 1083px, with the padding 1127px; the other column takes
      ;; the rest of the 1616px the 80px gap leaves.
      (let ((html
             (lispresen:render
              (format nil
                      "(slide :title \"t\" (lead \"x\") (column (code \"~a\")) (column \"y\"))"
                      (make-string 40 :initial-element #\a)))))
        (ok
         (contains html
                   "<p class=\"lead\">x</p>
<div class=\"columns\">
<div class=\"column\" style=\"width:1127px\">"))
        (ok (contains html "<div class=\"column\" style=\"width:489px\">"))
        (ok (contains html "--code:44px")))))
  (testing "runnable code takes no more room than static code"
    ;; 11 lines at 44px are 726px of the 776px body: the output is an overlay.
    (let ((slide
           (format nil
            "(slide :title \"t\" (code :lang \"lisp\" \"~{(a ~d)~^~%~}\"))"
            (loop for i from 1 to 11 collect i))))
      (ok (contains (lispresen:render slide) "--code:44px"))
      (ok (contains (lispresen:render slide) "class=\"codebox"))
      ;; A console's output wraps at the panel's edge instead of stepping the code down:
      ;; 300 characters at 44px take 5 rows of 61.
      (let ((html
             (lispresen:render
              (format nil
               "(slide :title \"t\" (code :lang \"console\" \"$ ls~%~a\"))"
               (make-string 300 :initial-element #\a)))))
        (ok (contains html "--code:44px"))
        (ok (contains html "<pre class=\"code console\"")))
      (ok
       (not
        (contains (lispresen:render slide :runtime "none")
                  "class=\"codebox")))))
  (testing "a short untitled paragraph is a statement at the statement scale"
    (ok
     (contains (lispresen:render "(slide \"一文だけ\")")
               "layout-statement\" id=\"s1\" style=\"--stmt:120px"))))

(defun parsed-table (form)
  (let ((lispresen::*diagnostics* nil))
    (let ((tb (lispresen::parse-table (read-from-string form))))
      (dolist (c (lispresen::table-block-header tb))
        (setf (lispresen::cell-run c)
              (lispresen::make-text-run (lispresen::cell-inlines c) :bold t)))
      (dolist (row (lispresen::table-block-rows tb))
        (dolist (c row)
          (setf (lispresen::cell-run c)
                (lispresen::make-text-run (lispresen::cell-inlines c)))))
      tb)))

(deftest table-columns
  (let ((tb
         (parsed-table
          "(table :header (\"項目\" \"説明\") (\"短い\" \"表の列幅は最小幅を確保した上で残りを比例配分する\"))")))
    (testing "columns that fit fill the box, the narrow one raised toward the wide one"
      ;; The long column's one-line width is 24 x 48 + 56: it keeps it, the short column
      ;; takes the rest.
      (let ((widths (lispresen::column-widths tb 48 1696)))
        (ok (equal widths '(488 1208)))
        (ok (= (lispresen::table-height tb 48 widths) (* 2 (+ 68 24))))))
    (testing "otherwise the long column takes the slack and wraps"
      (let ((widths (lispresen::column-widths tb 48 900)))
        (ok (= (reduce #'+ widths) 900))
        (ok (= (first widths) (+ (* 2 48) 56))) ; the short column keeps its natural width
        (ok (> (lispresen::table-height tb 48 widths) (* 2 (+ 68 24))))))
    (testing "minima wider than the box do not fit"
      (ok (null (lispresen::column-widths tb 48 300)))))
  (testing "a numeric column is right-aligned"
    (let ((html
           (lispresen:render
            "(slide :title \"t\" (table :header (\"a\" \"b\") (\"x\" \"1,204\") (\"y\" \"-18.1%\") (\"z\" \"--\")))")))
      (ok (contains html "<th scope=\"col\" class=\"right\">b</th>"))
      (ok (contains html "<td class=\"right\">1,204</td>"))
      (ok (contains html "<td>x</td>")))))

(defun callout-side (html id)
  "The side the bubble ID was placed on in HTML."
  (let* ((at (search (format nil "id=\"~a\"" id) html))
         (side (search "data-side=\"" html :start2 at)))
    (subseq html (+ side 11) (position #\" html :start (+ side 11)))))

(deftest callouts
  (testing "a bubble goes right of an anchor ending its line, below one inside it"
    (let ((html
           (lispresen:render
            "(slide :title \"t\" (ul (say \"c\" \"短い項目\") (li \"日本語は \" (say \"d\" (mark \"文節\")) \" で改行する\")))")))
      (ok (equal (callout-side html "s1c1") "right"))
      (ok (equal (callout-side html "s1c2") "below"))
      (ok
       (contains html
                 "<span aria-describedby=\"s1c2\"><mark>文節</mark></span>"))))
  (testing "left of a right-aligned cell, right of an image, below a piece of code"
    (let ((html
           (lispresen:render
            "(slide :title \"t\" (table :header (\"a\" \"b\") (\"x\" (say \"c\" \"31\")) (\"y\" \"12\")))
(slide :title \"t\" (say \"c\" (image \"a.png\" :alt \"x\")))
(slide :title \"t\" (say \"c\" :at \"(f 2)\" (code :lang \"lisp\" \"(defun f (x) x)
(f 2)\")))")))
      (ok (equal (callout-side html "s1c1") "left"))
      (ok (equal (callout-side html "s2c1") "right"))
      (ok (contains html "<figure class=\"pointed\" style=\"margin-right:"))
      (ok (equal (callout-side html "s3c1") "below"))
      (ok (contains html "data-at=\"(f 2)\""))))
  (testing "a callout without room is marked and reported, a slide with one is no statement"
    (let* ((line
            (format nil "~{~a~}" (loop repeat 4 collect "とても長い項目を何度も繰り返して書く")))
           (deck
            (format nil
                    "(slide :title \"t\" \"~a\" (p \"~a\" (say \"c\" \"文字\") \"~a\") \"~a\")
(slide (say \"c\" \"一文\"))" line line line line)))
      (multiple-value-bind (html diagnostics) (lispresen:render deck)
        (ok (contains html "class=\"say overflow\" id=\"s1c1\""))
        (ok (contains html "layout-standard\" id=\"s2\""))
        (dolist (message
                 '("the callout \"c\" has no room beside its anchor; shorten it or the slide"
                   "callout text at 36px (floor 40px); shorten the callouts or make room"))
          (ok
           (member message (mapcar #'lispresen::diagnostic-message diagnostics)
                   :test #'equal))))))
  (testing "a page advance shows a step bubble, by its :step or the deck's :callouts"
    (let ((html
           (lispresen:render
            "(deck :callouts :step)
(slide :title \"t\" (ul (say \"a\" \"x\") (say \"b\" :step nil \"y\")))")))
      (ok (contains html "class=\"say step\" id=\"s1c1\""))
      (ok (contains html "class=\"say\" id=\"s1c2\""))))
  (testing "malformed callouts"
    (ok
     (equal (render-diagnostics
             "(deck :callouts :later)
(slide :title \"t\" (say \"a\" :at \"y\" (code \"x\")) (say \"b\" :at \"x\" (p \"x\")))
(slide :title \"t\" (p (say 1 \"x\") (say \"c\")))")
            '((:error 1 ":callouts must be :shown or :step")
              (:error 2 "the :at text \"y\" is not on a line of the code")
              (:error 2 ":at belongs to a callout around a code block")
              (:error 3 "(say ...) takes the comment string first")
              (:error 3 "(say ...) needs the content it points at"))))))

(deftest templates
  (testing "a use is the body with its arguments substituted, &rest spliced"
    ;; t2 is defined after t1 and before the use; :title is not the parameter title.
    (ok
     (equal (lispresen:render
             "(define t1 (title &rest points)
  (section title)
  (slide :title title (ul points) (t2 title)))
(define t2 (x) (p (b x)))
(t1 \"a\" \"b\" \"c\")")
            (lispresen:render
             "(section \"a\")
(slide :title \"a\" (ul \"b\" \"c\") (p (b \"a\")))"))))
  (testing "malformed templates and uses are reported, the use dropped"
    (ok
     (equal
      (render-diagnostics
       "(define)
(define ul (x) x)
(define a (x x) x)
(define g (p) p)
(define c (x &rest) x)
(define d (x) (slide x))
(define d () (slide))
(d)
(define e () (e) (e))
(e)
(slide (define f () \"x\"))")
      '((:error 1 "(define ...) takes a name, a parameter list and a body")
        (:error 2 "ul is an element and cannot be a template")
        (:error 3 "template a repeats a parameter")
        (:error 4 "parameter p of g is an element name")
        (:error 5
         "the parameters of c must be symbols, the last optionally after &rest")
        (:error 7 "template d is already defined")
        (:error 8 "template d takes 1 argument, given 0")
        (:error 10
                "template e nests more than 32 uses deep; does it use itself?")
        (:error 11 "(define ...) belongs at the top level")))))
  (testing "the expansion of one deck is bounded"
    (let ((lispresen::*expansion-limit* 10))
      (ok
       (equal (render-diagnostics
               "(define w (x) (slide x x x x x x x x x x))
(w \"a\")
(w \"b\")") '((:error 2 "templates expand to more than 10 forms")))))))

(deftest qr-codes
  (testing "Reed-Solomon, format and version bits match the standard's examples"
    ;; HELLO WORLD at 1-M, and the format and version tables of ISO/IEC 18004.
    (ok
     (equal (lispresen::rs-ec
             '(32 91 11 120 209 114 220 77 67 64 236 17 236 17 236 17) 10)
            '(196 35 39 119 235 215 231 226 93 23)))
    (ok (= (lispresen::qr-format-bits 0) #x5412))
    (ok (= (lispresen::qr-version-bits 7) #x07C94)))
  (testing "the version is the smallest whose byte capacity at level M holds the text"
    (ok
     (equal (mapcar (lambda (n)
                      (lispresen::qr-version (make-list n :initial-element 97)))
                    '(14 15 122 123 213 214)) '(1 2 7 8 10 nil))))
  (testing "the title slide shows the logo and the URL's QR code; their diagnostics"
    (let ((html
           (lispresen:render
            "(deck :title \"t\" :logo (image \"l.png\" :alt \"l\") :url \"https://e.x/\")")))
      (ok (contains html "<img class=\"logo\" src=\"l.png\" alt=\"l\">"))
      (ok (contains html "<svg class=\"qr-code\" viewBox=\"0 0 29 29\""))
      (ok (contains html "<a href=\"https://e.x/\">https://e.x/</a>")))
    (ok
     (equal (render-diagnostics
             (format nil "(deck :title \"t\" :url \"~a\")"
                     (make-string 214 :initial-element #\a)))
            '((:error 1 "the :url is 214 bytes; a QR code holds 213 at most"))))
    (ok
     (equal (render-diagnostics "(deck :logo (image \"l.png\" :alt \"l\"))")
            '((:warning 1
                        ":logo and :url are shown on the title slide, which needs a :title"))))))

(deftest embedding
  (testing "base64 pads the last group"
    (ok
     (equal (mapcar (lambda (s) (lispresen::base64 (map 'vector #'char-code s)))
                    '("" "f" "fo" "foo" "foob"))
            '("" "Zg==" "Zm8=" "Zm9v" "Zm9vYg=="))))
  (testing "local images become data: URIs typed by extension, in columns too"
    (multiple-value-bind (html diagnostics) (lispresen:render "(slide :title \"a\" (image \"a.bmp\" :alt \"x\"))
(slide :title \"b\" (image \"b.png\" :alt \"x\"))
(slide :title \"c\" (image \"https://example.com/c.png\" :alt \"x\"))
(slide :title \"d\" (column (image \"d.SVG\" :alt \"x\")))"
                                                              :read-file
                                                              (lambda (src)
                                                                (and (string=
                                                                      src
                                                                      "d.SVG")
                                                                     (vector 60
                                                                      115 62)))
                                                              :embed t)
      (ok (contains html "src=\"data:image/svg+xml;base64,PHM+\""))
      (ok
       (equal (mapcar #'lispresen::diagnostic-message diagnostics)
              '("cannot embed image a.bmp: unknown type; use .png, .jpg, .jpeg, .gif, .webp, .avif, .svg"
                "cannot embed image b.png: cannot read it"
                "image https://example.com/c.png is a URL; only local files are embedded")))))
  (testing "code files are read as UTF-8, by line range, the language by extension"
    (let ((a
           (concatenate 'vector (map 'vector #'char-code ";x
  (f \"")
                        #(#xC3 #xA9 #xE2 #x86 #x92 #xF0 #x9D #x84 #x9E)
                        (map 'vector #'char-code "\")
  (g)
"))))
      (multiple-value-bind (html diagnostics)
          (lispresen:render "(slide :title \"a\" (code :file \"a.clj\" :lines (2 3)))
(slide :title \"b\" (code :file \"b.txt\"))
(slide :title \"c\" (code :file \"c.lisp\"))
(slide :title \"d\" (code :file \"a.clj\" :lines (3 9)))
(slide :title \"e\" (code :lines (1 2) \"x\"))"
                            :read-file
                            (lambda (path)
                              (cond ((string= path "a.clj") a)
                                    ((string= path "b.txt") (vector 255)))))
        (ok (contains html "data-lang=\"clojure\""))
        (ok (contains html "é→𝄞"))
        (ok
         (equal (mapcar #'lispresen::diagnostic-message diagnostics)
                '("code file b.txt is not UTF-8" "cannot read code file c.lisp"
                  ":lines 3-9 runs past the 3 lines of a.clj"
                  ":lines belongs to (code :file ...)")))))))

(deftest diagnostics
  (ok
   (equal (render-diagnostics
           "(deck :title \"d\")
(slide :title \"s\"
  (frob \"x\"))

(slide (table (\"a\" \"b\") (\"c\")))
(other)
(slide \"a\" . \"b\")
(slide \"ok\"")
          '((:error 2 "unknown element frob")
            (:error 5 "a table row has 1 cells where the table has 2")
            (:error 6
             "expected (slide ...), (section ...) or (define ...), found other")
            (:error 7 "dotted lists and #n= labels are not deck syntax")
            (:error 8
             "cannot read this form (unbalanced parentheses or bad syntax)"))))
  (ok
   (equal (render-diagnostics
           "(deck :kicker :later
      :theme (theme :base :dark :muted \"#555555\" :code-muted \"#555555\" :accent \"blue\"))
(slide :kicker \"k\" \"b\")
(slide #.(+ 1 2))")
          '((:error 1 ":kicker of deck must be :section")
            (:error 1 ":accent of theme must be a color like #1f2328")
            (:warning 1
                      "theme: captions (--muted on --bg) has contrast 2.3:1, below 4.5:1")
            (:warning 1
                      "theme: code comments (--code-muted on --code-bg) has contrast 2.0:1, below 4.5:1")
            (:warning 3 "a kicker stands above a title; this slide has none")
            (:error 4
             "cannot read this form (unbalanced parentheses or bad syntax)"))))
  (testing "an agenda lists the sections; each section is the list with its own highlighted"
    (multiple-value-bind (html diagnostics) (lispresen:render
                                             "(deck :agenda t)
(define s (x) (section x))
(s \"a\")
(slide :title \"t\" (s))
(s \"b\")")
      (ok (contains html "id=\"s1\"")) ; the agenda slide
      (ok
       (contains html
        "<ol class=\"agenda\"><li>a</li><li class=\"current\">b</li></ol>"))
      ;; Slide numbers count the agenda slide, in the expansion's diagnostics too.
      (ok (equal (mapcar #'lispresen::diagnostic-slide diagnostics) '(3)))
      (ok
       (equal
        (render-diagnostics
         "(deck :agenda t)
(slide \"x\")")
        '((:warning 1 ":agenda lists the sections, and this deck has none"))))))
  (testing "the built-in themes pass the contrast lint"
    (dolist (theme '("light" "dark" "high-contrast"))
      (ok
       (null (nth-value 1 (lispresen:render "(slide \"x\")" :theme theme))))))
  (ok
   (equal (lispresen:format-diagnostic "t.lisp"
                                       (lispresen::make-diagnostic
                                        :severity :warning
                                        :line 3
                                        :slide 2
                                        :title "x"
                                        :message "m"))
          "t.lisp:3: slide 2 \"x\": warning: m")))

(uiop:quit (if (run-suite :lispresen-test) 0 1))
