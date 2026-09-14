(defun main (n) (princ (> n 0)) (terpri))
(rontolisp:wasm-export 'main :as "main" :params '(:s32) :returns nil)
