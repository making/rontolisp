;; Loads the REAL uax-15 (MIT, Chris Bagley and Sabra Crolleton) via
;; asdf:load-system and runs the four Unicode normalization forms. Run with:
;;   rontolisp examples/asdf/uax-15-demo.lisp --system-path src/test/resources/uax-15:src/test/resources/split-sequence:src/test/resources/cl-ppcre
;; It is the one demo here whose library has dependencies of its own, so the
;; system path carries three directories. Runs on all four backends: the tables
;; the library builds by parsing 2.7 MB of bundled Unicode text are DERIVED from
;; the same files at compile/load time and emitted as data, so every backend
;; loads in seconds instead of minutes with identical results.

(asdf:load-system :uax-15)

;; The API is stringly typed and the results carry combining marks, so print
;; code-point lists rather than the raw normalized strings.
(defun codes (s) (map 'list #'char-code s))

;; NFC composes: A + U+030A (COMBINING RING ABOVE) -> U+00C5.
(print (codes (uax-15:normalize (format nil "A~C" (code-char #x030A)) :nfc)))

;; NFD decomposes it back.
(print (codes (uax-15:normalize (string (code-char #x00C5)) :nfd)))

;; NFKC compatibility-composes: U+2460 (circled 1) -> 1, U+00BD -> 1 / 2.
(print (codes (uax-15:normalize (format nil "~C~C" (code-char #x2460) (code-char #x00BD)) :nfkc)))

;; NFKD of U+FB00 (LATIN SMALL LIGATURE FF) -> two 'f' characters.
(print (codes (uax-15:normalize (string (code-char #xFB00)) :nfkd)))

;; U+212B (ANGSTROM SIGN) canonically decomposes, so NFC yields U+00C5.
(print (codes (uax-15:normalize (string (code-char #x212B)) :nfc)))

;; The canonical combining class of U+0301 (COMBINING ACUTE ACCENT).
(print (gethash #x0301 (uax-15:get-canonical-combining-class-map) 0))

;; The NFC illegal-character list: its length and both endpoints.
(let ((illegal (uax-15:get-illegal-char-list :nfc)))
  (print (list (length illegal) (first illegal) (car (last illegal)))))

;; unicode-letter-p over a Latin letter, a hiragana, a digit and a CJK ideograph.
;; rontolisp answers T for the letters; the upstream load answers NIL for every
;; character outside four hardcoded ranges (its key computation reads #+utf-32,
;; a feature a file's own pushnew never gets to the reader).
(print (mapcar (lambda (code) (uax-15:unicode-letter-p (code-char code)))
               (list #x41 #x3042 #x30 #x4E00)))
