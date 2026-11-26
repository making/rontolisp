;; Implements the WIT world wit/greeter.wit: each export is the var its label
;; names, so no parameter or result type is written here.
;;
;;   rontolisp greeter.clj -o greeter.wasm --component --emit-wit
;;   wasmtime run --invoke 'shout("Ada", true)' greeter.wasm     ; "HELLO, ADA!!!"
;;   wasmtime run --invoke 'is-short("Grace")' greeter.wasm      ; false
;;
;; --emit-wit writes greeter.wit beside the module: the world it was handed, the
;; parameter names included. A drifted program -- an export with no var, a var of
;; another arity -- is a compile error naming the WIT line, and so is a plain
;; `rontolisp greeter.clj` run.
(ns greeter
  (:require [rontolisp.wit :as wit]
            [clojure.string :as str]))

(wit/export "wit/greeter.wit")

(defn greet [name]
  (str "Hello, " name "!"))

(defn shout [name excited]
  (str (str/upper-case (greet name)) (if excited "!!" "")))

(defn is-short [name]
  (< (count name) 5))
