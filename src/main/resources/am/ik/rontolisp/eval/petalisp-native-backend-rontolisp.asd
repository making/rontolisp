;;;; A replacement for petalisp's code/native-backend/petalisp.native-backend.asd
;;;; (eval.AsdOverrides): the same system without static-vectors.
;;;;
;;;; The backend reads a Lisp array's storage by its address -- a static vector's
;;;; pointer -- on SBCL and CCL, and a Lisp vector has no address on the JVM or wasm-GC.
;;;; Its rontolisp arm (eval.PetalispForms) keeps every buffer in a simple vector and
;;;; reads an element with row-major-aref, so nothing in it names static-vectors, whose
;;;; own .asd refuses every implementation it does not list. The rest is upstream's.

(defsystem "petalisp.native-backend"
  :author "Marco Heisig <marco.heisig@fau.de>"
  :license "AGPLv3"
  :depends-on ("alexandria" "bordeaux-threads" "cffi" "lparallel" "trivia"
               "trivial-garbage" "petalisp.utilities" "petalisp.core"
               "petalisp.ir" "petalisp.codegen" "typo")
  :serial t
  :components ((:file "packages") (:file "request") (:file "pin-current-thread")
               (:file "worker-pool") (:file "backend") (:file "scheduling")
               (:file "allocation") (:file "evaluator") (:file "compilation")))
