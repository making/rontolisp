# find-symbol

`(find-symbol string [package])`

[`intern`](intern.md) と似ていますが新しく作りません。名前がすでにイメージ内で既知ならそのシンボルを、そうでなければ nil を返します。「既知」とは `cl` シンボル(関数・マクロ・特殊形式)、キーワード、ユーザー定義のいずれかです。`package` 指定子を渡すと、カレントパッケージではなくそのパッケージ内を探します。存在しないパッケージはシンボルを提供しないため、Common Lisp が通知する `package-error` ではなく `nil` を返します -- これにより、オプションのシステムを調べる用途(`(find-symbol "TIMESTAMP" :simple-date)`)がすべてのバックエンドで同じように動きます。

Common Lisp との差異: コンパイルバックエンド(JVM/WASM)で `nil` を返せるのは**リテラル**文字列のときだけです -- 判定はコンパイル時の視界(cl シンボルとプログラム自身の `defun`)に対して畳み込まれるため、実行時に定義された変数やマクロはそこからは見えません(インタプリタはグローバル変数や `defmacro` マクロを含む生きたイメージを調べます)。計算された名前は代わりに intern されるので常にシンボルが返り、そのステータスもイメージではなく生成された綴り(修飾付きなら `:external`、修飾なしなら `:internal`)から決まります。

第 2 の値はそのパッケージにおける ANSI のアクセス可能性ステータス — `:external`、`:inherited`、`:internal`、あるいはパッケージがその名前を提供しないときは `nil` — を返します。2 つの値は同時に `nil` になります:

```lisp
(multiple-value-list (find-symbol "CAR" 'common-lisp)) ; => (CAR :EXTERNAL)
```

```lisp
(multiple-value-list (find-symbol "CAR")) ; => (CAR :INHERITED)
```

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

```lisp
(find-symbol "TIMESTAMP" :simple-date) ; => NIL
```
