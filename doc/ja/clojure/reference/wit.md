# WIT 契約 (rontolisp.wit)

`rontolisp.wit` は、コンパイル時に読む WIT ファイルにプログラムを結び付けます。`import` は
WIT インターフェースの関数を var にし、`export` はプログラムの var で WIT の world を実装し、
`provide` は import したインターフェースの実装を、プログラム自身が提供する場所で束縛します。
これらは [WIT 契約ガイド](../../guides/wit-contracts.md)のディレクティブ
（`rontolisp:wit-import`、`rontolisp:wit-export`、`rontolisp:wit-provide`）に
ローワリングされるので、どのバックエンドも Common Lisp のプログラムと同じやり方で
インターフェースを束縛します。`clojure.string` と同じように require します。

| Name | Example | Result |
|---|---|---|
| `rontolisp.wit/import` | `(import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})` | `kv/open`、`kv/bucket-get` などが var になる |
| `rontolisp.wit/export` | `(export "greeter.wit")` | world の各エクスポートは、ラベルが指す var |
| `rontolisp.wit/provide` | `(provide "wasi:keyvalue/store@0.2.0-draft" store)` | インターフェースの呼び出しが `store` に届く |

```console
$ cat hits.clj
(ns hits (:require [rontolisp.wit :as wit]))

(wit/import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})

(let [bucket (kv/open "")]
  (kv/bucket-set bucket "hits" "42")
  (println (kv/bucket-get bucket "hits")))
$ rontolisp hits.clj -o hits.wasm --component
$ wasmtime run -S keyvalue=y hits.wasm
42
```

どちらも WIT ファイルを、それを書いたファイルからの相対パスで指します（`load` と同じ）。

## 動くターゲット

| ターゲット | `import` | `export` |
|---|---|---|
| インタプリタ、JVM | 各関数がインターフェースのプロバイダを呼ぶ（[provide](wit-provide.md)） | world をプログラムに照らして検査する |
| WASM コアモジュール（`-o out.wasm`、`--no-wasi`） | 関数ごとにホストのインポートひとつ | world のエクスポートごとにエクスポートひとつ |
| `--component` | canonical ABI を通じて呼ぶコンポーネントのインポート | 型付きのコンポーネントエクスポート |

## 渡るもの

WIT の値は、下の表の Clojure の値として渡ります。`bool` は [rontolisp.wasm](wasm.md#types)
の `:bool` と同じく渡るときに変換し、ほかの行はどちらの言語でも同じ値です。

| WIT | Clojure |
|---|---|
| `s8` ... `u64`、`f32`、`f64` | 数 |
| `bool` | `true` か `false` |
| `char` | 文字 |
| `string` | 文字列 |
| `list<u8>` | 1 バイトを 1 文字とする文字列 |
| リソースのハンドル（`own`、`borrow`） | 不透明な整数 |
| `option<T>` | 値か `nil` |
| 結果としての `result<T, E>` | ok の値。エラー側は例外を投げ、`(catch Exception e ...)` で捕まえられる |

型がこの表を越えるメンバー（レコード、バリアント、enum、フラグ、タプル、`u8` 以外の
リスト、ストリームやフューチャー、引数としての `result`）は束縛しません。`async func` も
同様です。それを参照すると、コンパイル時に WIT の行を名指して拒否します。

```console
$ rontolisp app.clj
error: app.clj:4:1: plot of example:geo/api@0.1.0 takes point (parameter 'p'), a record, which the Clojure tier does not carry yet (geo.wit:5)
```

ターゲットによっては、Common Lisp と同じく範囲がさらに狭まります。WASM コアモジュールの
インポートを渡れる型は 32 ビットまでの整数、浮動小数点数、`bool`、`string`、`list<u8>`、
ハンドルで、world のエクスポートを渡れる型は整数、`f64`、`bool`、`string` です。
