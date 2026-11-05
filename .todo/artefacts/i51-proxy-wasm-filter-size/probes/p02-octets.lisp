(rontolisp:wasm-import '%set :from "env" :as "set" :params '(:s32 :octets) :returns :s32)
(defun f (x) (%set x "abc"))
(rontolisp:wasm-export 'f :as "f" :params '(:s32) :returns :s32)
