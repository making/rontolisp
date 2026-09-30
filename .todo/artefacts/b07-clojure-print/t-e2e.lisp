;; E2E: print REAL lowered Clojure values through the spike printer.
;; Run from spike/: sbcl --load t-e2e.lisp | java -jar ...-exec.jar t-e2e.lisp

(load "clj-print.lisp")
(load "e2e-values.clj")

;; The REAL false object, as a spliced clojure.lisp would read it.
(setq *clj-false* rontolisp::%clojure-false)

(defun e2e (label name)
  (princ label)
  (princ " => ")
  (princ (clj%to-string (symbol-value name) nil "nil"))
  (terpri))

(princ "false-eq-real? => ")
(princ (if (eq (symbol-value '|c%e2e-false|) rontolisp::%clojure-false) "yes" "NO"))
(terpri)

(e2e "e2e-false " '|c%e2e-false|)
(e2e "e2e-true  " '|c%e2e-true|)
(e2e "e2e-nil   " '|c%e2e-nil|)
(e2e "e2e-kw    " '|c%e2e-kw|)
(e2e "e2e-KW    " '|c%e2e-KW|)
(e2e "e2e-vec   " '|c%e2e-vec|)
(e2e "e2e-map   " '|c%e2e-map|)
(e2e "e2e-nested" '|c%e2e-nested|)
(e2e "e2e-set   " '|c%e2e-set|)
(e2e "e2e-list  " '|c%e2e-list|)
(e2e "e2e-sym   " '|c%e2e-sym|)

(princ "spike-e2e-done")
(terpri)
