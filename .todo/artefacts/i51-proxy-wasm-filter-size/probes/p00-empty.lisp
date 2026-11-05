(defun f (x) (+ x 1))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
