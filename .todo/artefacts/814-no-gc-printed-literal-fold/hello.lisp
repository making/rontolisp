;;; A module that only PRINTS its literals: no host import, nothing folds today.
(defun main ()
  (princ "Hello, world!")
  (terpri))
(rontolisp:wasm-export 'main :as "main" :params '() :returns nil)
