# rontolisp.wit/provide

`(provide interface provider)` with `[rontolisp.wit :as wit]`

Binds the implementation of an imported interface where the program provides it itself: the
interpreter and the JVM ([WIT contracts](wit.md)). `provider` is a function of the member's
name (a string such as `"open"` or `"bucket-get"`) followed by that member's arguments, a
resource method's handle first; what it answers is the call's value, and what it throws, the
call throws. A later `provide` of the interface replaces it. The answer is `interface`.

`interface` is the interface's full id; the spelling a [rontolisp.wit/import](wit-import.md)
above wrote (`"wasi:keyvalue/store"`) stands for the same id.

```console
$ cat hits.clj
(ns hits (:require [rontolisp.wit :as wit]))

(wit/import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})

(def store (atom {}))

(wit/provide "wasi:keyvalue/store@0.2.0-draft"
             (fn [member & [_ k v]]
               (cond
                 (= member "open") 0
                 (= member "bucket-get") (get @store k)
                 (= member "bucket-set") (do (swap! store assoc k v) nil))))

(let [bucket (kv/open "")]
  (kv/bucket-set bucket "hits" "42")
  (println (kv/bucket-get bucket "hits")))
$ rontolisp hits.clj
42
```

A WASM build's host provides every import, so there `provide` binds nothing: the same source
compiled with `--component` runs against wasmtime's own `wasi:keyvalue`
(`wasmtime run -S keyvalue=y`). A provider sees the boundary's values: a `bool` argument
arrives as `true` or `nil`. With no provider bound, a call throws naming the interface.
