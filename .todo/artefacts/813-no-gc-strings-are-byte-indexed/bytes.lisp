;;; Every string primitive that indexes or measures, exported so a host can drive
;;; it with a runtime :string. Compile BOTH ways and compare against the interpreter:
;;;   --no-gc --no-wasi   (byte semantics)
;;;   --no-wasi           (the wasm-GC backend, character semantics)
(rontolisp:wasm-export 'slen :as "SLen" :params '(:string) :returns :s32)
(rontolisp:wasm-export 'c0 :as "C0" :params '(:string) :returns :s32)
(rontolisp:wasm-export 'c1 :as "C1" :params '(:string) :returns :s32)
(rontolisp:wasm-export 'subtail :as "SubTail" :params '(:string) :returns :s32)

(defun slen (s) (length s))
(defun c0 (s) (char-code (char s 0)))
(defun c1 (s) (char-code (char s 1)))
(defun subtail (s) (length (subseq s 1)))
