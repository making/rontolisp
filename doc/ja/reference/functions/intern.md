# intern

`(intern string)`

`string` を名前とするシンボルを、そのままの綴りで返します(ケース変換なし)。rontolisp のシンボルは名前で比較され、独立したインターンテーブルはないため、結果はクォートされたリテラルを含め同名のどのシンボルとも `eq` になります。Common Lisp からの相違点: カレントパッケージは無視され(名前は与えたとおりに使われるので、どの `in-package` の下でも `(intern "foo")` は裸のシンボル `foo` を返します)、パッケージ引数はエラーになり、第 2 の `status` 値もありません。

```lisp
(intern "hello") ; => hello
```

```lisp
(eq (intern "foo") 'foo) ; => t
```

```lisp
(defvar *level* 7)
(symbol-value (intern "*level*")) ; => 7
```
