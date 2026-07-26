;; Loads the REAL cl-ppcre (BSD-2-Clause, Dr. Edmund Weitz) via asdf:load-system
;; and runs the Perl-compatible regex API. Run with:
;;   rontolisp examples/asdf/cl-ppcre-demo.lisp --system-path src/test/resources/cl-ppcre
;; Runs on all four backends: the scanner closures rely on named block/return-from
;; crossing loops, which the compile backends implement as lexical named exits.

(asdf:load-system :cl-ppcre)

;; scan: match bounds plus register bounds as four values
(print (multiple-value-list (cl-ppcre:scan "(a)*b" "xaaabd")))

;; scan-to-strings: the whole match plus a register vector
(print (multiple-value-list (cl-ppcre:scan-to-strings "(\\d+)-(\\d+)" "phone 03-1234")))

;; split, with a regex separator
(print (cl-ppcre:split "\\s+" "foo bar   baz"))

;; replacement, single and global
(print (cl-ppcre:regex-replace "fo+" "foo bar" "frob"))
(print (cl-ppcre:regex-replace-all "a" "banana" "o"))

;; all matches as strings
(print (cl-ppcre:all-matches-as-strings "[a-z]+" "one 2 three 4 five"))

;; the iteration macros (do-scans family builds on &environment + get-setf-expansion)
(let ((acc nil))
  (cl-ppcre:do-matches-as-strings (m "[0-9]+" "a1 b22 c333")
    (push m acc))
  (print (nreverse acc)))

;; register-groups-bind destructures the registers by name
(print (cl-ppcre:register-groups-bind (area num)
           ("(\\d+)-(\\d+)" "tel 03-1234 end")
         (list area num)))

;; a parse tree instead of a regex string
(print (cl-ppcre:scan-to-strings '(:sequence "b" (:greedy-repetition 1 nil #\a)) "xbaaay"))

;; (?i) inline modifier
(print (cl-ppcre:scan-to-strings "(?i)hello|bye" "say HELLO now"))
