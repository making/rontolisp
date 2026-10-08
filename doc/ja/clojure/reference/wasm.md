# WASM ホスト関数 (rontolisp.wasm)

`rontolisp.wasm` は、コンパイルした WebAssembly モジュールとホストのあいだを渡るものを
宣言します。`defimport` はホストが提供する関数を var に束縛し、`export`（または `defn` の
`{:wasm/export ...}` メタデータ）は var をホストに渡します。どちらも
[WASM ホスト境界ガイド](../../guides/wasm-host-boundary.md)のディレクティブ
（`rontolisp:wasm-import`、`rontolisp:wasm-export`）にローワリングされるので、同じ宣言をした
Clojure のモジュールと Common Lisp のモジュールは同じバイト列にコンパイルされます。
`clojure.string` と同じように require します。

| Name | Example | Result |
|---|---|---|
| `rontolisp.wasm/defimport` | `(defimport add {:from "host" :params [:int :int] :returns :int})` | `add` がホストの `add` を呼ぶ |
| `rontolisp.wasm/export` | `(export add10 {:params [:int] :returns :int})` | モジュールが `add10` をエクスポートする |
| `:wasm/export` メタデータ | `(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] ...)` | モジュールが `add10` をエクスポートする |

```console
$ cat main.clj
(ns main (:require [rontolisp.wasm :as wasm]))

(wasm/defimport add {:from "host" :params [:int :int] :returns :int})

(defn add10 {:wasm/export {:params [:int] :returns :int}} [n]
  (add n 10))
$ rontolisp main.clj -o main.wasm --no-wasi
$ rontolisp host.clj -o host.wasm --no-wasi     # a module exporting "add"
$ wasmtime run --preload host=host.wasm --invoke add10 main.wasm 32
42
```

インタプリタと JVM では、エクスポートは普通の関数で、インポートには呼ぶ相手のホストが
ありません。

```clojure
(require '[rontolisp.wasm :as wasm])

(wasm/defimport add {:from "host" :params [:int :int] :returns :int})

(defn add10 {:wasm/export {:params [:int] :returns :int}} [n]
  (+ n 10))

(println (add10 32))
(println (try (add 1 2) (catch UnsupportedOperationException e (ex-message e))))
```

```
42
add is a host function (rontolisp.wasm/defimport): only a compiled WASM module can call it
```

## 型

型キーワードは境界のものです。`:int`、`:long`、`:s8` ... `:u64`、`:float`、`:bool`、
`:string`、`:s-expr`、`:extern`（インポートのみ）、それに結果用の `:void`（`:returns` を
書かない宣言はこれを意味します）。Clojure と境界とで綴りが異なる値は、渡るときに
変換します。

| 型 | ホストへ | ホストから |
|---|---|---|
| `:bool` | `false` と `nil` は偽として渡る | 偽は `nil` ではなく `false` として届く |
| `:s-expr` | `pr-str` が出力するテキスト | `read-string` が読む値 |

したがって `:s-expr` を通ると、ベクタ、マップ、キーワード、`false` はそのまま往復します。
`:s-expr` はどのホストでも `:string` と同じ UTF-8 テキストとして渡ります。どちらの型も
含まない宣言は、Common Lisp のソースが書くディレクティブとまったく同じものに
ローワリングされます。`:bytes` は拒否します。これは `(unsigned-byte 8)` ベクタを転送する型で、
そういう Clojure の値は存在しないためです。`:async` も拒否します。サスペンドする境界越えが
返す future は、まだ Clojure の future に対応していないためです。

## 動くターゲット

| ターゲット | `defimport` | `export` |
|---|---|---|
| WASM コアモジュール（`-o out.wasm`、`--no-wasi`） | モジュールのインポート | モジュールのエクスポート |
| `--component` | `rontolisp:wasm-import` が拒否する。コンポーネントは WIT インターフェースを呼ぶ（[rontolisp.wit](wit.md)） | 型付きのコンポーネントエクスポート |
| インタプリタ、JVM | `UnsupportedOperationException` を投げる関数 | なし。関数は呼べるまま |

`--emit-js-glue` は、Common Lisp のモジュールと同じく `--no-wasi` モジュールの境界の
JavaScript 側を書き出します。生成されたファイルを node から使うドライバを含む一式は
[`examples/clojure/host-boundary/`](https://github.com/making/rontolisp/tree/develop/examples/clojure/host-boundary)
にあります。
