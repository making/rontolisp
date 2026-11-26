;; The host of main.clj's import, written in Clojure too: a module exporting its
;; `host-add` under the name main.wasm imports, `add`. wasmtime's
;; `--preload host=host.wasm` answers main.wasm's "host" module with it.
(ns host
  (:require [rontolisp.wasm :as wasm]))

(defn host-add {:wasm/export {:as "add" :params [:int :int] :returns :int}} [a b]
  (+ a b))
