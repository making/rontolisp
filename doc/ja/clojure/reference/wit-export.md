# rontolisp.wit/export

`[rontolisp.wit :as wit]` での `(export "path.wit")` または `(export "path.wit" {:world "name"})`

プログラムが WIT の world を実装すると宣言します。world の各エクスポートは、現在の
名前空間でそのラベルが指す var（ファイルのどこで定義した `defn` でも、refer した var でも
よい）で、モジュールはそれを world の型でエクスポートします（[WIT 契約](wit.md)）。
ファイルが複数の world を宣言しているときは `:world` で選びます。WIT ファイルは、`load` と
同じく、それを書いたファイルからの相対パスです。

```console
$ cat wit/greeter.wit
package example:greeter@0.1.0;

world greeter {
  export greet: func(name: string) -> string;
  export is-short: func(name: string) -> bool;
}
$ cat greeter.clj
(ns greeter (:require [rontolisp.wit :as wit]))

(wit/export "wit/greeter.wit")

(defn greet [name] (str "Hello, " name "!"))
(defn is-short [name] (< (count name) 5))
$ rontolisp greeter.clj -o greeter.wasm --component --emit-wit
$ wasmtime run --invoke 'is-short("Grace")' greeter.wasm
false
```

world はプログラムのエクスポートの全リストなので、隣に [rontolisp.wasm/export](wasm-export.md)
を書くと拒否します。world はどのターゲットでもコンパイル時に検査し、var のないエクスポート、
引数の数に合うアリティのない `defn`、エクスポート境界が扱えない型、`async func` は、
いずれも WIT の行を名指して拒否します。`bool` は `true` か `false` として渡り、引数をちょうど
取る単一アリティの `defn` でないエクスポートは、[rontolisp.wasm/export](wasm-export.md) と
同じく、そのアリティで var を呼ぶ関数を介します。`--emit-wit` は world を引数名ごと
書き戻します。

REPL では、world はその時点までの定義に照らして検査されます。先に関数を定義してください。
