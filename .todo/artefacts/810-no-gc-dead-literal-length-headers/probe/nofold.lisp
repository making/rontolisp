;;; 8. NO import folds (a runtime :string argument at one h1 site), so every literal
;;; must stay headered and the layout must be the pre-change one.
(rontolisp:wasm-import 'h1 :from "env" :as "h1" :params '(:string) :returns nil)
(defun c1 () (h1 "only-folded"))
(defun c2 () (h1 "both-ways") (print (length "both-ways")))
(defun c8 (s) (h1 s) (h1 "after"))
(defun run-all (s) (c1) (c2) (c8 s) (princ "done") (terpri))
(rontolisp:wasm-export 'run-all :as "RunAll" :params '(:string) :returns nil)
