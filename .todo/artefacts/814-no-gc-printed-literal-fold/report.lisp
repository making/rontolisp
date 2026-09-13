;;; A report-shaped printing module: seven literals, every one only ever princ'd
;;; in statement position, plus a number rendered between them.
(defun banner ()
  (princ "== rontolisp report ==") (terpri))
(defun row (label n)
  (princ label) (princ ": ") (princ n) (terpri))
(defun main (n)
  (banner)
  (princ "items processed") (princ ": ") (princ n) (terpri)
  (princ "items skipped") (princ ": ") (princ (- n 1)) (terpri)
  (princ "status") (princ ": ") (princ "ok") (terpri)
  (princ "-- end of report --") (terpri))
(rontolisp:wasm-export 'main :as "main" :params '(:s32) :returns nil)
