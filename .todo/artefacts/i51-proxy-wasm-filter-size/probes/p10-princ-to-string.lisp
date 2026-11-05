(rontolisp:wasm-import '%log :from "env" :as "proxy_log" :params '(:s32 :string) :returns :s32)
(defun f (x) (%log 2 (princ-to-string x)) 0)
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
