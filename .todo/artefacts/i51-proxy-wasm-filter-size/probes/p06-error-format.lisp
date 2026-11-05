(rontolisp:wasm-import '%log :from "env" :as "proxy_log" :params '(:s32 :string) :returns :s32)
(defun f (x) (handler-case (if (= x 1) (error "boom ~a" x) x) (error (c) (%log 4 (format nil "e: ~a" c)) 0)))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
