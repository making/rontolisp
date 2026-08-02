# symbol-function

`(symbol-function symbol)`

関数名前空間で `symbol` に束縛された関数値、つまり `#'name` が表すのと同じ値を返します。結果は `funcall`/`apply` に渡したり保存したりできます。rontolisp は Lisp-2 であるため、これは関数名前空間のみを参照し、同名の変数を参照することはありません。クォートされたシンボルのリテラル (`(symbol-function 'car)`) はコンパイラではコンパイル時に解決されます。実行時に計算されたシンボルは、結果が呼び出されたときにコンパイル済み名前レジストリを通じて遅延解決されます — その場合の相違点が 2 つあります: 結果への `functionp` は `nil` を返し、未定義の名前は `symbol-function` 自体ではなく呼び出し時にエラーを通知します。

```lisp
(funcall (symbol-function 'car) '(1 2 3)) ; => 1
```

`symbol-function` は `setf` の place でもあります: `(setf (symbol-function 'name) fn)` は `fn` をそのシンボルのグローバルな関数定義としてインストールします — 既存関数の別名の定義や、置き換えに使えます。コンパイラではコンパイラが直接束縛済みの呼び出し箇所は元の関数のまま呼ばれます（[`fmakunbound`](fmakunbound.md) と同じ乖離）。この方法で**のみ**束縛された名前は完全に遅延束縛され、代入前に呼び出すと `The function NAME is undefined` をシグナルします。

```lisp
(defun double (x) (* x 2))
(setf (symbol-function 'twice) #'double)
(twice 21) ; => 42
```
