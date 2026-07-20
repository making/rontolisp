# find-symbol

`(find-symbol string)`

[`intern`](intern.md) に似ていますが、決して新しいシンボルを作りません: 名前がイメージに既知の場合はそのシンボルを、そうでなければ nil を返します。「既知」とは `cl` のシンボル(関数・マクロ・特殊形式)、キーワード、またはユーザー定義を指します。Common Lisp からの相違点: パッケージ引数と第 2 の `status` 値はありません。またコンパイルバックエンド(JVM/WASM)では引数は**リテラル**文字列でなければならず、判定はコンパイル時の情報(`cl` シンボル + プログラム自身の `defun`)に対して畳み込まれるため、実行時に定義された変数やマクロはそこでは見えません(インタプリタはグローバル変数や `defmacro` マクロを含む生きたイメージを検査します)。

```lisp
(find-symbol "car") ; => NIL
```

```lisp
(find-symbol "cond") ; => NIL
```

```lisp
(find-symbol "no-such-name") ; => NIL
```

```lisp
(defun greet (n) n)
(find-symbol "greet") ; => NIL
```
