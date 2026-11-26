# rontolisp.wit/import

`[rontolisp.wit :as wit]` での `(import "path.wit" options)`

WIT ファイルを（それを書いたファイルからの相対パスで）読み、その中のインターフェースひとつの
関数を、専用の名前空間の var として束縛します（[WIT 契約](wit.md)）。オプションマップは
次のとおりです。

- `:interface` -- 束縛するインターフェース。完全な ID（`"wasi:keyvalue/store@0.2.0"`）、
  バージョンを除いた ID、またはファイル内でひとつしか定義されていないときの素の名前。必須。
- `:as` -- 現在の名前空間に作る、インターフェースの名前空間のエイリアス。
- `:refer` -- refer するメンバー名のベクタ、または `:all`。`:as` と `:refer` のどちらかが
  必須です。ほかに var に届く手段がないためです。
- `:from` -- WASM コアモジュールのインポートモジュール。既定はインターフェースの素の名前。
- `:field-style` -- WASM コアモジュールのインポートフィールドの綴り。`:camel`（既定。
  `create-shader` は `createShader` をインポートする）か `:kebab`（ラベルのまま）。

関数はその WIT の名前の var になります。リソースのメソッドはハンドルを最初の引数に取り、
リソース名が前に付きます（`bucket.get` は `kv/bucket-get`）。コンストラクタは
`<resource>-new`、解放は `<resource>-drop` です。これらの var は `defn` の var と同じく
関数です。`#'kv/open` は `#'wasi:keyvalue.store@0.2.0/open` と表示され（名前空間は
インターフェースの ID です）、`(map kv/bucket-get ...)` にも渡せます。インターフェースの束縛は
プログラムにつき一度で、2 度目の import はエイリアスと refer を結ぶだけです。

```console
$ cat math.wit
package example:host@0.1.0;

interface math {
  add-ints: func(a: s32, b: s32) -> s32;
  is-even: func(n: s32) -> bool;
}
$ cat app.clj
(ns app (:require [rontolisp.wit :as wit]))

(wit/import "math.wit" {:interface "example:host/math" :as math :refer [is-even]})

(defn report {:wasm/export {:params [:int] :returns :string}} [n]
  (str (math/add-ints n 1) " " (is-even n)))
$ rontolisp app.clj -o app.wasm --no-wasi --emit-js-glue
```

呼び出しは、ターゲットごとに WIT 自身の束縛へローワリングされます。インタプリタと JVM では
プロバイダ（[provide](wit-provide.md)）、WASM コアモジュールではホストのインポート
`math.addInts` / `math.isEven`、`--component` では canonical ABI のインポートです。コア
モジュールがインポートするのはプログラムが呼ぶメンバーです。値はそれぞれ Clojure の綴りで
渡り（レコードはマップ、バリアントのケースはキーワードか `[:case payload]`）、`result`
のエラー側はエラーの値を持つ `ExceptionInfo` を投げます（[渡るもの](wit.md#what-crosses)）。
型がストリームかフューチャーに届くメンバーや `async func` は束縛せず、それを参照すると
WIT の行を名指して拒否します。
