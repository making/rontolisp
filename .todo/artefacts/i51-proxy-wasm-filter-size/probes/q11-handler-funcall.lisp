(defvar *h* (lambda (x) (+ x 1)))
(defun f (x) (handler-case (funcall *h* x) (error () 0)))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
