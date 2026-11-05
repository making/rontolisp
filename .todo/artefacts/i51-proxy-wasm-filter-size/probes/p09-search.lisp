(rontolisp:wasm-import '%str :from "rontolisp:memory" :as "string" :params '(:u32 :u32) :returns :string)
(defun f (p n) (if (search "secret" (%str p n)) 1 0))
(rontolisp:wasm-export 'f :as "f" :params '(:u32 :u32) :returns :s32)
