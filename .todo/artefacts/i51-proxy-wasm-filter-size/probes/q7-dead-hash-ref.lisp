(defvar *h* (lambda (x) (+ x 1)))
(defun dead (l) (mapcar #'string l))
(defun f (x) (funcall *h* x))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
