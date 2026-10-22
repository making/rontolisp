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
    ;; aaa = 3 x 587 milli-em, the space 340, bbb = 3 x 659: 407.8 px at 100 px.
    (let ((run (lispresen::make-text-run (list "aaa bbb"))))
      (ok (= (lispresen::run-lines run 100 408) 1))
      (ok (= (lispresen::run-lines run 100 407) 2))
      (ok (= (lispresen::run-min-width run 100) 198))
      (ok (= (lispresen::run-full-width run 100) 408))))
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
        (ok (contains (lispresen:render (columns long "\"y\"")) "--body:40px"))
        (ok (contains (lispresen:render (columns long long)) "--body:40px")))))
  (testing "runnable code takes no more room than static code"
    ;; 11 lines at 44px are 726px of the 776px body: the output is an overlay.
    (let ((slide
           (format nil
            "(slide :title \"t\" (code :lang \"lisp\" \"~{(a ~d)~^~%~}\"))"
            (loop for i from 1 to 11 collect i))))
      (ok (contains (lispresen:render slide) "--code:44px"))
      (ok (contains (lispresen:render slide) "class=\"codebox"))
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
    (testing "columns that fit keep their natural widths"
      (let ((widths (lispresen::column-widths tb 48 1696)))
        (ok (< (reduce #'+ widths) 1696))
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

(deftest diagnostics
  (ok
   (equal (render-diagnostics
           "(deck :title \"d\")
(slide :title \"s\"
  (frob \"x\"))

(slide (table (\"a\" \"b\") (\"c\")))
(other)
(slide \"ok\"")
          '((:error 2 "unknown element frob")
            (:error 5 "a table row has 1 cells where the table has 2")
            (:error 6 "expected (slide ...) or (section ...), found other")
            (:error 7
             "cannot read this form (unbalanced parentheses or bad syntax)"))))
  (ok
   (equal (render-diagnostics
           "(deck :theme (theme :base :dark :muted \"#555555\" :accent \"blue\"))
(slide :title \"s\" (column \"a\") \"b\")")
          '((:error 1 ":accent of theme must be a color like #1f2328")
            (:warning 1
                      "theme: captions (--muted on --bg) has contrast 2.3:1, below 4.5:1")
            (:warning 1
                      "theme: code comments (--muted on --code-bg) has contrast 2.0:1, below 4.5:1")
            (:error 2 "a slide with columns holds only (column ...) blocks"))))
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
