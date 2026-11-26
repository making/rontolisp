;;;; stats -- the Lisp statistician.
;;;;
;;;; One plain function, `vowel-count`, exported through the `statistician` world
;;;; of wit/pipeline.wit. The Rust shouter IMPORTS this function and calls it to
;;;; decide how many `!` to append -- so this is the "Rust calls Lisp" edge of the
;;;; pipeline.
;;;;
;;;;   rontolisp stats.lisp -o stats.wasm --component --optimize
;;;;
;;;; produces a reactor component that exports `vowel-count`. It is not run on its
;;;; own -- `wac compose` plugs it into the Rust shouter (see README.md).

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

(rontolisp:wit-export "wit/pipeline.wit" :world statistician)
