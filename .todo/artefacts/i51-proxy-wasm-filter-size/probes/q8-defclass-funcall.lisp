(defclass c () ((id :initarg :id :reader c-id)))
(defvar *h* (lambda (x) (make-instance 'c :id x)))
(defun f (x) (c-id (funcall *h* x)))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
