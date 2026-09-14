(defun main () (print "hi") (terpri))
(rontolisp:wasm-export 'main :as "main" :params '() :returns nil)
