(defvar *h* (make-hash-table))
(defun f (x) (setf (gethash x *h*) x) (remhash (+ x 1) *h*) (if (gethash x *h*) 1 0))
(rontolisp:wasm-export 'f :as "f" :params '(:u32) :returns :s32)
