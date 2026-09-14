(defun main () (princ "hi") (terpri))
(rontolisp:wasm-export 'main :as "main" :params '() :returns nil)
