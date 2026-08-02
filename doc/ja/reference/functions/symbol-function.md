# symbol-function

`(symbol-function symbol)`

関数名前空間で `symbol` に束縛された関数値、つまり `#'name` が表すのと同じ値を返します。結果は `funcall`/`apply` に渡したり保存したりできます。rontolisp は Lisp-2 であるため、これは関数名前空間のみを参照し、同名の変数を参照することはありません。クォートされたシンボルのリテラル (`(symbol-function 'car)`) はコンパイラではコンパイル時に解決されます。実行時に計算されたシンボルは、結果が呼び出されたときにコンパイル済み名前レジストリを通じて遅延解決されます — その場合の相違点が 2 つあります: 結果への `functionp` は `nil` を返し、未定義の名前は `symbol-function` 自体ではなく呼び出し時にエラーを通知します。

```lisp
(funcall (symbol-function 'car) '(1 2 3)) ; => 1
```
