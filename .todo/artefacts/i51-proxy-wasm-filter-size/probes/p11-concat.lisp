(rontolisp:wasm-import '%log :from "env" :as "proxy_log" :params '(:s32 :string) :returns :s32)
(rontolisp:wasm-import '%str :from "rontolisp:memory" :as "string" :params '(:u32 :u32) :returns :string)
(defun f (p n) (%log 2 (concatenate 'string "x=" (%str p n))) 0)
(rontolisp:wasm-export 'f :as "f" :params '(:u32 :u32) :returns :s32)
