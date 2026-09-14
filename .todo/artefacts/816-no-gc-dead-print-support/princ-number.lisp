(defun main (n) (princ n) (terpri))
(rontolisp:wasm-export 'main :as "main" :params '(:s32) :returns nil)
