(rontolisp:wasm-import '%log :from "env" :as "proxy_log" :params '(:s32 :string) :returns :s32)
(defun lg (c &rest a) (%log 2 (apply #'format nil c a)) 0)
(defun f (x) (lg "x=~a" x))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
