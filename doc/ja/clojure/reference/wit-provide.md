# rontolisp.wit/provide

`[rontolisp.wit :as wit]` での `(provide interface provider)`

import したインターフェースの実装を、プログラム自身が提供する場所（インタプリタと JVM）で
束縛します（[WIT 契約](wit.md)）。`provider` は、メンバー名（`"open"` や `"bucket-get"` の
ような文字列）と、それに続くそのメンバーの引数（リソースのメソッドならハンドルが先頭）を
受け取る関数です。返した値が呼び出しの値になり、投げた例外は呼び出しが投げます。同じ
インターフェースへの後の `provide` が前のものを置き換えます。答えは `interface` です。

`interface` は文字列で、上にある [rontolisp.wit/import](wit-import.md) が束縛する
インターフェースの完全な ID か、その import が書いた綴り（`"wasi:keyvalue/store"`）です。
ほかのインターフェースや、プログラムの実行時に計算するインターフェースはコンパイル時に
拒否します。プロバイダの値を変換する基準が import の WIT だからです。同じ理由で `provide`
は値を持ちません。

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
動きます（`wasmtime run -S keyvalue=y`）。プロバイダが束縛されていないとき、呼び出しは
インターフェースを名指して例外を投げます。

プロバイダが受け取り、答えるのは Clojure の値で、呼び出す側が渡し、受け取る値と同じです
（[渡るもの](wit.md#what-crosses)）。`bool` は `true` か `false`、レコードはマップ、
バリアントのケースはキーワードか `[:case payload]` として届きます。`result` を答える
メンバーは ok の値を答えます。エラー側は、エラーの値を `:rontolisp.wit/error` に持つ
`ExceptionInfo` で、呼び出す側が捕まえる例外と同じです:
`(throw (ex-info "no such store" {::wit/error :no-such-store}))`。このインターフェースを呼ぶ
Common Lisp のプログラムは、このエラー側を `rontolisp:wit-error` として受け取り、その
ペイロードは Common Lisp の綴り（`:NO-SUCH-STORE`）です。
