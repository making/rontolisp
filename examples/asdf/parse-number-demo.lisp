;;;; parse-number via asdf:load-system
;;;; Loads the REAL parse-number v1.8 (unmodified upstream sources) and
;;;; parses integers, ratios, floats, radix-prefixed literals and exponent
;;;; markers without going through the reader. Runs identically on all four
;;;; backends; see README.md in this directory for the run commands (the
;;;; library directory is passed with --system-path).
;;;;
;;;; Run (library vendored in this repository):
;;;;   rontolisp examples/asdf/parse-number-demo.lisp --system-path src/test/resources/parse-number

(asdf:load-system :parse-number)

;; Integers, signs and surrounding whitespace.
(print (parse-number:parse-number "42"))
(print (parse-number:parse-number "-13"))
(print (parse-number:parse-number "  3.14  "))

;; Ratios are exact and normalized.
(print (parse-number:parse-number "1/3"))
(print (parse-number:parse-number "-4/8"))

;; Exponent markers produce floats.
(print (parse-number:parse-number "1e3"))
(print (parse-number:parse-number "2.5e2"))
(print (parse-number:parse-number "5d0"))

;; Radix-prefixed literals, including the general #NNr form.
(print (parse-number:parse-number "#xFF"))
(print (parse-number:parse-number "#b101"))
(print (parse-number:parse-number "#o777"))
(print (parse-number:parse-number "#3r12"))

;; The entry points for reals only / positive reals only.
(print (parse-number:parse-real-number "-42.5"))
(print (parse-number:parse-positive-real-number "17"))
