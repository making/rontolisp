# with-package-iterator

`(with-package-iterator (name package-list symbol-type...) body...)`

`name` を、`package-list`(指示子またはそのリスト)のパッケージのシンボルのうちアクセス可能性が `symbol-type`(`:internal`、`:external`、`:inherited`。少なくとも 1 つ必要で、なければ `program-error`)のいずれかであるものを走査するローカル関数(CL の `macrolet` ではなく `flet`)に束縛します。呼び出すたびに 4 つの値 -- `t`、シンボル、そのステータス、そのパッケージ -- を返し、走査が終わると `nil` を返します。package-list フォームは 1 回だけ評価され、走査する集合は [`do-symbols`](do-symbols.md) が訪れるものと同じなので、渡される各シンボルはそのパッケージでその名前に対して [`find-symbol`](../functions/find-symbol.md) が返すものと一致します。

```lisp
(make-package :wpi-demo :use nil)
(intern "A" :wpi-demo)
(intern "B" :wpi-demo)
(let ((names nil))
  (with-package-iterator (next :wpi-demo :internal)
    (loop (multiple-value-bind (more sym status) (next)
            (unless more (return))
            (push (list (symbol-name sym) status) names))))
  (sort names #'string< :key #'car)) ; => (("A" :INTERNAL) ("B" :INTERNAL))
```
