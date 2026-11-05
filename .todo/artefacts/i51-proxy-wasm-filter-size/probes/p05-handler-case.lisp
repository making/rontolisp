(defun f (x) (handler-case (if (= x 1) (error "boom") x) (error () 0)))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
