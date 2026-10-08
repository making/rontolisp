;; The wasm-import / wasm-export pair of the WASM host boundary guide, in Clojure:
;; `add` is a function the HOST provides, `add10` one the host calls.
;;
;;   rontolisp main.clj -o main.wasm --no-wasi
;;   rontolisp host.clj -o host.wasm --no-wasi
;;   wasmtime run --preload host=host.wasm --invoke add10 main.wasm 32    ; 42
;;
;; The declarations lower to rontolisp:wasm-import and rontolisp:wasm-export, so
;; the module is the one the Common Lisp guide's source compiles to.
(ns main
  (:require [rontolisp.wasm :as wasm]))

(wasm/defimport add {:from "host" :params [:int :int] :returns :int})

(defn add10 {:wasm/export {:params [:int] :returns :int}} [n]
  (add n 10))
