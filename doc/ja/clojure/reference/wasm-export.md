# rontolisp.wasm/export

`[rontolisp.wasm :as wasm]` での `(export var options)`、または `defn` の属性マップ
（あるいは名前のメタデータ）に書く `{:wasm/export options}`

`var` が指す関数をエクスポートし、ホストから呼べるようにします（[WASM ホスト関数](wasm.md)）。
オプションマップは次のとおりです。

- `:as` -- ホストが呼ぶときの名前。既定は var の名前。
- `:params` -- 引数ごとの型キーワードのベクタ。既定は引数なし。
- `:returns` -- 結果の型キーワード。既定は `:void`（結果は捨てる）。

var は宣言のある場所で、ファイル全体のローワリングが済んでから解決します。そのため var は
下で定義してもよく、再定義した `defn` は最新の定義がエクスポートされます。宣言した引数を
ちょうど取る単一アリティの `defn` は、手書きの `rontolisp:wasm-export` が `defun` を名指す
のと同じく、それ自身がエクスポートされます。それ以外（複数のアリティや rest 引数を持つ
`defn`、`def` した関数、マルチメソッド）は、宣言したアリティで var を呼ぶ関数を介して
エクスポートされます。その数の引数を取るアリティがない `defn` は、エクスポートを名指して
拒否します。メタデータは書いたとおり var に残ります。

```console
$ cat calc.clj
(ns calc (:require [rontolisp.wasm :as wasm]))

(defn positive? {:wasm/export {:as "is-positive" :params [:s32] :returns :bool}} [n]
  (> n 0))

(defn total ([a] a) ([a b] (+ a b)))
(wasm/export total {:params [:s32 :s32] :returns :s32})
$ rontolisp calc.clj -o calc.wasm --component
$ wasmtime run --invoke 'is-positive(-3)' calc.wasm
false
$ wasmtime run --invoke 'total(2, 40)' calc.wasm
42
```

WIT の world を実装するプログラムは、代わりに [rontolisp.wit/export](wit-export.md) で
エクスポートを宣言します。この 2 つは併用できません。インタプリタと JVM では何も
エクスポートされず、関数は普通の関数のままです。
