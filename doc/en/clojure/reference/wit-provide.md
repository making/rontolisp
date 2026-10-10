# rontolisp.wit/provide

`(provide interface provider)` with `[rontolisp.wit :as wit]`

Binds the implementation of an imported interface where the program provides it itself: the
interpreter and the JVM ([WIT contracts](wit.md)). `provider` is a function of the member's
name (a string such as `"open"` or `"bucket-get"`) followed by that member's arguments, a
resource method's handle first; what it answers is the call's value, and what it throws, the
call throws. A later `provide` of the interface replaces it. The answer is `interface`.

`interface` is a string: the full id of an interface a [rontolisp.wit/import](wit-import.md)
above binds, or the spelling that import wrote (`"wasi:keyvalue/store"`). Any other interface,
or one computed when the program runs, is refused when the program compiles: the import's WIT
is what the provider's values are converted by. For the same reason `provide` has no value.

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
  (kv/bucket-set bucket "hits" (.getBytes "42"))
  (println (String. (kv/bucket-get bucket "hits"))))
$ rontolisp hits.clj
42
```

A WASM build's host provides every import, so there `provide` binds nothing: the same source
compiled with `--component` runs against wasmtime's own `wasi:keyvalue`
(`wasmtime run -S keyvalue=y`). With no provider bound, a call throws naming the interface.

A provider sees and answers Clojure values, the ones a caller passes and gets
([What crosses](wit.md#what-crosses)): a `bool` arrives as `true` or `false`, a record as a
map, a variant's case as a keyword or `[:case payload]`. A member answering a `result` answers
its ok value; its error arm is an `ExceptionInfo` holding the error value under
`:rontolisp.wit/error`, the exception a caller catches:
`(throw (ex-info "no such store" {::wit/error :no-such-store}))`. A Common Lisp program calling
the interface receives that arm as `rontolisp:wit-error`, its payload in the Common Lisp
spelling (`:NO-SUCH-STORE`).
