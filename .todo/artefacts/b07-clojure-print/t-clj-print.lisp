;; Spike driver: exercises clj-print.lisp the way lowered Clojure values look.
;; Runs on SBCL (sbcl --script) and on rontolisp (java -jar ...-exec.jar file).
;; Lowercase spellings use |..| so both readers keep the case (CL upcases bare).

(load "clj-print.lisp")

;; The false object: a DISTINCT non-NIL symbol spelled "false", like the value
;; ClojureLowering binds rontolisp::%clojure-false to. The printer only ever
;; compares against the variable's value (EQ with the same object), never a literal.
(setq *clj-false* '|false|)

(defun check (label value readable nil-replacement)
  (princ label)
  (princ " => ")
  (princ (clj%to-string value readable nil-replacement))
  (terpri))

(defun mk-map (pairs)
  (let ((h (make-hash-table :test #'equal)))
    (dolist (p pairs h)
      (setf (gethash (car p) h) (cdr p)))))

(defun mk-set (members)
  (let ((h (make-hash-table :test #'equal)))
    (dolist (m members h)
      (setf (gethash m h) m))))

;; --- scalars ---------------------------------------------------------------
(check "true/plain " t nil "nil")
(check "false/plain" *clj-false* nil "nil")
(check "nil/plain " nil nil "nil")
(check "nil/str   " nil nil "")
(check "num       " 42 nil "nil")
(check "ratio     " 7/2 nil "nil")
(check "float     " 1.5 nil "nil")
(check "str/plain " "hi" nil "nil")
(check "str/read  " "a\"b\\c" t "nil")
(check "char/plain" #\a nil "nil")
(check "char/read " #\a t "nil")
(check "nl/read   " #\Newline t "nil")

;; --- keywords: top-level and nested ------------------------------------------
(check "kw/plain  " (list :C%KEYWORD "a") nil "nil")
(check "kw/str    " (list :C%KEYWORD "a/b") nil "")
(check "kw/read   " (list :C%KEYWORD "A") t "nil")

;; --- symbols: demangle c%.., leave CL alone ----------------------------------
(check "sym/foo   " '|c%foo| nil "nil")
(check "sym/Foo   " '|c%Foo| nil "nil")
(check "sym/pct   " '|c%%%| nil "nil")
(check "sym/colon " '|c%%c| nil "nil")
(check "sym/plain " 'FOO nil "nil")
(check "sym/kw    " :else nil "nil")

;; --- collections --------------------------------------------------------------
(check "vec       " #(1 2 3) nil "nil")
(check "vec/nested" (vector 1 (list :C%KEYWORD "a") "s") nil "nil")
(check "vec/read  " (vector "x" #\a) t "nil")
(check "list      " '(1 2 3) nil "nil")
(check "list/mixed" (list t *clj-false* nil (list :C%KEYWORD "k")) nil "nil")
(check "list/read " (list "x" #\a t) t "nil")
(check "map/1     " (mk-map (list (cons (list :C%KEYWORD "a") 1))) nil "nil")
(check "map/nested"
       (mk-map (list (cons (list :C%KEYWORD "a")
                           (mk-map (list (cons (list :C%KEYWORD "b")
                                               (vector 1 2)))))))
       nil "nil")
(check "map/read  " (mk-map (list (cons "k" "v"))) t "nil")
(check "set/2     " (list :C%SET (mk-set '(1))) nil "nil")
(check "empty-vec " #() nil "nil")
(check "empty-map " (mk-map nil) nil "nil")

;; --- str vs print joining is the lowering's job (concatenate); here the parts --
(check "str/false " *clj-false* nil "")
(check "str/true  " t nil "")

;; --- depth cap stands in for datum labels (Scheme needs ~130 lines for those) --
(check "capped    " (let ((x (list 1))) (setf (cdr x) x) x) nil "nil")

(princ "spike-done")
(terpri)
