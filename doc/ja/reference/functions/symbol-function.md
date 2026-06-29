# symbol-function

`(symbol-function symbol)`

関数名前空間で `symbol` に束縛された関数値、つまり `#'name` が表すのと同じ値を返します。結果は `funcall`/`apply` に渡したり保存したりできます。rontolisp は Lisp-2 であるため、これは関数名前空間のみを参照し、同名の変数を参照することはありません。コンパイラでは束縛がコンパイル時に解決されるため、引数はクォートされたシンボルのリテラル (`(symbol-function 'car)`) でなければなりません。

```lisp
(funcall (symbol-function 'car) '(1 2 3)) ; => 1
```
