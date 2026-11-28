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
  (kv/bucket-set bucket "hits" (.getBytes "42"))
  (println (String. (kv/bucket-get bucket "hits"))))
$ rontolisp hits.clj -o hits.wasm --component
$ wasmtime run -S keyvalue=y hits.wasm
42
```

どちらも WIT ファイルを、それを書いたファイルからの相対パスで指します（`load` と同じ）。パスには
公開されている形の WIT パッケージのディレクトリも指定できます。その `*.wit` ファイル群が 1 つの
パッケージで、`deps/` の各エントリは `use` 節が参照できるパッケージです。

エクスポートはプログラムのトップレベルを読むので、その中には閉じ込めた `def` や、
`Integer/parseInt` と `parse-long` が読む 10 進数字の表もあります。コマンドコンポーネントでは、
ホストがエクスポートを直接呼ぶとき（`wasmtime run --invoke`、合成したコンポーネント）に
トップレベルが走らないため、そうしたエクスポートはそこでトラップします。
`(export "num.wit" {:reactor true})` は、代わりにエクスポートをエントリーポイントにします。
トップレベルは最初のエクスポート呼び出しで 1 度だけ走り、`wasi:cli/run` はリフトされません。
既定はコマンドのままで、トップレベルからも出力するプログラムはこちらを必要とします。
WASM コマンドモジュールはこのオプションを拒否します。

```console
$ cat num.clj
(ns num (:require [rontolisp.wit :as wit]))

(wit/export "num.wit" {:reactor true})

(defn parse [text] (Integer/parseInt text))
$ rontolisp num.clj -o num.wasm --component
$ wasmtime run --invoke 'parse("42")' num.wasm
42
```

## 動くターゲット

| ターゲット | `import` | `export` |
|---|---|---|
| インタプリタ、JVM | 各関数がインターフェースのプロバイダを呼ぶ（[provide](wit-provide.md)） | world をプログラムに照らして検査する |
| WASM コアモジュール（`-o out.wasm`、`--no-wasi`） | 関数ごとにホストのインポートひとつ | world のエクスポートごとにエクスポートひとつ |
| `--component` | canonical ABI を通じて呼ぶコンポーネントのインポート | 型付きのコンポーネントエクスポート |

## 渡るもの

WIT の値は、下の表の Clojure の値として渡ります。変換は渡るときに両方向で行い、呼び出す側でも
[プロバイダ](wit-provide.md)の側でも world のエクスポートでも同じです。ラベルは WIT の綴りのままなので、ケース
`DNS-error` はキーワード `:DNS-error` になります。

| WIT | Clojure |
|---|---|
| `s8` ... `u64`、`f32`、`f64` | 数 |
| `bool` | `true` か `false` |
| `char` | 文字 |
| `string` | 文字列 |
| `list<u8>` | [バイト配列](byte-array.md) |
| リソースのハンドル（`own`、`borrow`） | 不透明な整数 |
| `option<T>` | 値か `nil` |
| `record` | 各フィールドをそのキーワードの下に持つマップ: `{:port 0 :address [127 0 0 1]}` |
| `enum` | ケースのキーワード: `:ipv4` |
| `variant` | ペイロードのないケースはそのキーワード `:get`、ペイロードのあるケースは `[:case payload]`（`[:other "PATCH"]`） |
| `flags` | キーワードの集合: `#{:read :write}` |
| `tuple<...>`、`list<T>` | ベクタ |
| 引数として、またはほかの値の中の `result<T, E>` | `[:ok v]` か `[:error e]`。ペイロードのない側は `:ok` か `:error` |
| 関数の結果としての `result<T, E>` | ok の値。エラー側は、`ex-data` の `:rontolisp.wit/error` にエラーの値を持つ `ExceptionInfo`。呼び出す側には投げられ、プロバイダとエクスポートはこれを投げる |

ホストへ渡すとき、リスト、タプル、フラグはどのコレクション（ベクタ、リスト、seq、集合）でも
受け取り、レコードはどのマップでも受け取ります。マップにないフィールドは `nil`、つまり
option の none です。型のどの形にも当てはまらない値は、型を名指す
`IllegalArgumentException` を投げます。`list<u8>` はバイト配列だけを受け取り、それ以外の
値は `byte[]` へのキャストの `ClassCastException` を投げます。エラー側の例外はメンバーを
名指し、`rontolisp.wit` のエイリアスがあればその値は `(::wit/error (ex-data e))` です。

```console
$ cat bind.clj
(ns bind (:require [rontolisp.wit :as wit]))

(wit/import "sockets.wit" {:interface "wasi:sockets/types@0.3.0" :as sock})

(let [s (sock/tcp-socket-create :ipv4)]
  (sock/tcp-socket-bind s [:ipv4 {:port 0 :address [127 0 0 1]}])
  (println (first (sock/tcp-socket-get-local-address s)))
  (try (sock/tcp-socket-bind s [:ipv4 {:port 0 :address [127 0 0 1]}])
       (catch clojure.lang.ExceptionInfo e
         (println (ex-message e))
         (println (::wit/error (ex-data e))))))
$ rontolisp bind.clj -o bind.wasm --component
$ wasmtime run -S inherit-network=y bind.wasm
:ipv4
tcp-socket-bind of wasi:sockets/types@0.3.0 answered its error arm
:invalid-state
```

[`examples/clojure/wit-types/`](https://github.com/making/rontolisp/tree/develop/examples/clojure/wit-types) は、enum、flags、variant、record、option、
タプルのリスト、`result` をプロバイダとの間で両方向に渡します。

world のエクスポートも同じ値を受け取り、返します。var は Clojure の値で呼ばれ、var が返す
`result` はその値、var がその `ExceptionInfo` を投げたときはエラー側です。それ以外の例外は
呼び出しをトラップさせます。

```console
$ cat shapes.wit
package example:shapes@0.1.0;

world shapes {
  record point { x: s32, y: s32 }

  export flip: func(p: point) -> point;
  export measure: func(text: string) -> result<u32, string>;
}
$ cat shapes.clj
(ns shapes (:require [rontolisp.wit :as wit]))

(wit/export "shapes.wit")

(defn flip [{:keys [x y]}] {:x y :y x})

(defn measure [text]
  (if (seq text)
    (count text)
    (throw (ex-info "nothing to measure" {::wit/error "empty text"}))))
$ rontolisp shapes.clj -o shapes.wasm --component
$ wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
{x: 2, y: 1}
$ wasmtime run --invoke 'measure("")' shapes.wasm
err("empty text")
```

`list<u8>` はどのターゲットでも、両方向にオクテットのまま渡ります。コンポーネント、
プロバイダ、WASM コアモジュールのいずれもです。コアモジュールのグルー（`--emit-js-glue`）は
ホストに `Uint8Array` を渡し、`Uint8Array` を受け取ります（テキストはその UTF-8 符号化として
受け取ります）。正しい UTF-8 でないオクテットもそのまま渡ります。Common Lisp のプロバイダの
文字列はその UTF-8 符号化として届き、Clojure のプロバイダを呼ぶ Common Lisp の側はオクテットを
`(unsigned-byte 8)` ベクタとして受け取ります。

型がストリームかフューチャーに届くメンバーは束縛しません。`async func` も同様です。
それを参照すると、コンパイル時に WIT の行を名指して拒否します。

```console
$ rontolisp app.clj
error: app.clj:4:1: feed of example:geo/api@0.1.0 takes option<stream<u8>> (parameter 'body'), an option carrying a stream, which the Clojure tier does not carry yet (geo.wit:8)
```

ターゲットによっては、Common Lisp と同じく範囲がさらに狭まります。WASM コアモジュールの
インポートを渡れる型は 32 ビットまでの整数、浮動小数点数、`bool`、`string`、`list<u8>`、
ハンドルで、それを越えるメンバーはプログラムが呼ぶところで拒否します。コンポーネントの
インポートは `u8` 以外のリストの引数を除くすべての行を渡せます。コンポーネントの
エクスポートはハンドルを除くすべての行を渡せ、型がハンドル、ストリーム、
フューチャーに届く world は WIT の行で拒否します。リソースを宣言するインタフェース、
または `use` 句でリソースを取り込むインタフェースのエクスポートも拒否します（Common Lisp の
プログラムはリソースをエクスポートでき、コンポーネントがインポートするリソースも受け取れます:
[リソース](../../reference/functions/rontolisp-wit-export.md#resources)）。
`--no-wasi` のモジュールのエクスポートも、`--emit-js-glue` のグルーを通して同じ型を渡せます（フラグは真偽値のオブジェクトになります）。
WASI モジュールと `--no-gc` のエクスポートを渡れる型は整数、`f64`、`bool`、`string` です
（[`rontolisp:wit-export`](../../reference/functions/rontolisp-wit-export.md#compound-types)）。
