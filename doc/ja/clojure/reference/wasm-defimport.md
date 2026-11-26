# rontolisp.wasm/defimport

`[rontolisp.wasm :as wasm]` での `(defimport name options)`

現在の名前空間に、ホストが提供する関数として var `name` を定義します。WASM コアモジュールに
コンパイルすると、その呼び出しはホストのインポートを呼びます（[WASM ホスト関数](wasm.md)）。
オプションマップは次のとおりです。

- `:from` -- インポートモジュール。既定は `"env"`（JavaScript ホストのインポートオブジェクトの
  キー、wasmtime の `--preload` の名前）。
- `:as` -- モジュール内のフィールド。既定は書かれたとおりの var 名。
- `:params` -- 引数ごとの型キーワードのベクタ。既定は引数なし。
- `:returns` -- 結果の型キーワード。既定は `:void`（nil）。

`defn` と同じく定義なので、ファイル内でこれより上にある呼び出しもこれに届き、REPL は var を
エコーし、`#'name` や `(map name ...)` は関数を受け取ります。ローワリング先は
`rontolisp:wasm-import` ひとつです。`:bool` か `:s-expr` の値が渡るときは、その周りに
ラッパー関数が付きます（[型](wasm.md#types)）。

```console
$ cat clock.clj
(ns clock (:require [rontolisp.wasm :as wasm]))

(wasm/defimport now-ms {:from "env" :as "nowMs" :returns :float})
(wasm/defimport log-line {:from "env" :as "log" :params [:string]})

(defn tick {:wasm/export {:returns :float}} []
  (log-line (str "tick at " (now-ms)))
  (now-ms))
$ rontolisp clock.clj -o clock.wasm --no-wasi --emit-js-glue
```

インタプリタと JVM にはホストがないので、この関数は宣言した引数の数を取り、呼ばれると
`UnsupportedOperationException` を投げます。`--component` ではディレクティブが拒否されます。
コンポーネントは代わりに WIT インターフェースを呼びます（[rontolisp.wit/import](wit-import.md)）。
