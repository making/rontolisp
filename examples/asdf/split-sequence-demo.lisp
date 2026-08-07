;;;; split-sequence via asdf:load-system
;;;; Loads the REAL split-sequence v2.0.1 (unmodified upstream sources) and
;;;; exercises split-sequence / split-sequence-if / split-sequence-if-not on
;;;; strings and lists, including the second return value (the resume index),
;;;; the :count/:from-end/:start/:end bounds and the :test/:test-not/:key
;;;; designators. Runs identically on all four backends; see README.md in
;;;; this directory for the run commands (the library directory is passed
;;;; with --system-path).
;;;;
;;;; Run (library vendored in this repository):
;;;;   rontolisp examples/asdf/split-sequence-demo.lisp --system-path src/test/resources/split-sequence

(asdf:load-system :split-sequence)

;; Strings: empty subsequences are kept unless :remove-empty-subseqs.
(print (split-sequence:split-sequence #\, "a,b,,c"))
(print (split-sequence:split-sequence #\, "a,b,,c" :remove-empty-subseqs t))

;; Lists, and predicate variants.
(print (split-sequence:split-sequence 3 '(1 2 3 4 5 3 6)))
(print (split-sequence:split-sequence-if #'evenp '(1 2 3 4 5)))
(print (split-sequence:split-sequence-if-not #'oddp '(1 2 3 4 5)))

;; The second return value is the index where processing stopped -- it
;; crosses the function boundary through the multiple-value channel.
(multiple-value-bind (parts index)
    (split-sequence:split-sequence #\space "hello world lisp")
  (print parts)
  (print index))

;; Bounds and counts.
(print (split-sequence:split-sequence #\, "a,b,c,d" :count 2))
(print (split-sequence:split-sequence #\, "a,b,c,d" :count 2 :from-end t))
(print (split-sequence:split-sequence #\, "a,b,c,d" :start 2))
(print (split-sequence:split-sequence #\, "a,b,c,d" :end 3))

;; Custom :test and :key designators.
(print (split-sequence:split-sequence 2 '(1 2 3 2 4) :test #'eql))
(print
 (split-sequence:split-sequence #\A "aAbAc" :key #'char-upcase :test #'char=))
