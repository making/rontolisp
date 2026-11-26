;;;; counter -- the Lisp component a Rust program calls.
;;;;
;;;; It exports one plain function, `vowel-count`, through the `counter` world of
;;;; wit/vowels.wit. `rontolisp:wit-export` implements PLAIN function exports (not
;;;; interfaces), so the world declares `export vowel-count: func(...)` at the top
;;;; level, and the compiler checks the defun below against it on every build.
;;;;
;;;; Built as a reactor component (no _start, no printing of its own):
;;;;
;;;;   rontolisp counter.lisp -o counter.wasm --component --optimize
;;;;
;;;; `wac` plugs it straight into the Rust describer. It is not run on its own --
;;;; see README.md / build.sh.

;;; A vowel is one of a e i o u, in either case. Spelled out with char= so it
;;; lowers to the smallest subset, with no dependence on sequence built-ins.
(defun vowelp (c)
  (let ((d (char-downcase c)))
    (or (char= d #\a) (char= d #\e) (char= d #\i) (char= d #\o) (char= d #\u))))

;;; Count the vowels in TEXT.
;;; WIT: vowel-count: func(text: string) -> s32
(defun vowel-count (text)
  (let ((n 0))
    (dotimes (i (length text)) (when (vowelp (char text i)) (incf n)))
    n))

(rontolisp:wit-export "wit/vowels.wit" :world counter)
