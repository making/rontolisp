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
`:string`、`:s-expr`、`:bytes`、`:extern`（インポートのみ）、それに結果用の `:void`
（`:returns` を書かない宣言はこれを意味します）。Clojure と境界とで綴りが異なる値は、
渡るときに変換します。

| 型 | ホストへ | ホストから |
|---|---|---|
| `:bool` | `false` と `nil` は偽として渡る | 偽は `nil` ではなく `false` として届く |
| `:s-expr` | `pr-str` が出力するテキスト | `read-string` が読む値 |
| `:bytes` | [バイト配列](byte-array.md)のオクテット。それ以外の値は `ClassCastException` を投げる | バイト配列 |

したがって `:s-expr` を通ると、ベクタ、マップ、キーワード、`false` はそのまま往復します。
`:s-expr` はどのホストでも `:string` と同じ UTF-8 テキストとして渡ります。これらの型を
どれも含まない宣言は、Common Lisp のソースが書くディレクティブとまったく同じものに
ローワリングされます。`:async` は拒否します。サスペンドする境界越えが返す future は、
まだ Clojure の future に対応していないためです。

`:bytes` は生のオクテットを転送するので、`:string` なら UTF-8 として復号されるところを
`ff` は `ff` のまま渡ります。結果としての `:bytes` は呼び出す側のバッファです。`:bytes` を
答えるインポートは、宣言した引数のあとにホストが書き込むバイト配列を受け取り、値の全長を
答えます。配列の大きさを超える長さは、配列が小さすぎたことを意味します。`:bytes` を答える
エクスポートはバイト配列を答え、ホストはそれを自分が渡すバッファへ読み込みます。

```console
$ cat bin.clj
(ns bin (:require [rontolisp.wasm :as wasm]))

(wasm/defimport read-chunk {:from "host" :as "readChunk" :params [:int] :returns :bytes})

(defn checksum {:wasm/export {:params [:bytes] :returns :int}} [data]
  (reduce + (map #(if (neg? %) (+ % 256) %) data)))

(defn first-chunk {:wasm/export {:as "firstChunk" :params [:int] :returns :string}} [id]
  (let [buf (byte-array 4096)
        n (read-chunk id buf)]
    (String. buf 0 (min n 4096) "UTF-8")))
$ rontolisp bin.clj -o bin.wasm --no-wasi --emit-js-glue
```

Common Lisp の `:bytes` 型と同じく、渡れるのは GC バックエンドの WASM コアモジュールだけです。
インタプリタと JVM では、`:bytes` を答えるインポートもバイト配列を受け取るスタブです。

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
