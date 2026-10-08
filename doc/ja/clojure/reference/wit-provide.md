# rontolisp.wit/provide

`[rontolisp.wit :as wit]` での `(provide interface provider)`

import したインターフェースの実装を、プログラム自身が提供する場所（インタプリタと JVM）で
束縛します（[WIT 契約](wit.md)）。`provider` は、メンバー名（`"open"` や `"bucket-get"` の
ような文字列）と、それに続くそのメンバーの引数（リソースのメソッドならハンドルが先頭）を
受け取る関数です。返した値が呼び出しの値になり、投げた例外は呼び出しが投げます。同じ
インターフェースへの後の `provide` が前のものを置き換えます。答えは `interface` です。

`interface` はインターフェースの完全な ID です。上にある [rontolisp.wit/import](wit-import.md)
が書いた綴り（`"wasi:keyvalue/store"`）も同じ ID を指します。

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

WASM のビルドではホストがすべてのインポートを提供するので、`provide` は何も束縛しません。
同じソースを `--component` でコンパイルすると、wasmtime 自身の `wasi:keyvalue` に対して
動きます（`wasmtime run -S keyvalue=y`）。プロバイダが受け取るのは境界の値で、`bool` の
引数は `true` か `nil` として届きます。プロバイダが束縛されていないとき、呼び出しは
インターフェースを名指して例外を投げます。
