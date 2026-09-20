# copy-structure

`(copy-structure structure)`

`structure` の型を持つ新しいインスタンスを返します(CLHS 18.3)。スロットは新たに確保されますが、その値は元のインスタンスと共有されます — 浅いコピーなので、コピー側のスロットを書き換えても元には影響せず、その逆も同様です。これは [`defstruct`](../special-forms/defstruct.md) が特定の型に対して生成する `copy-<name>` コピア関数の、型を問わない汎用版です。そのコピア関数はタグをコンパイル時に知っているので `%obj-new` インスタンスコンストラクタを直接呼び出しますが、`copy-structure` は型が実行時まで分からないため、引数自身のレイアウトを実行時に読み取ります。

```lisp
(defstruct point x y)
(let* ((p (make-point :x 1 :y 2))
       (q (copy-structure p)))
  (setf (point-x q) 99)
  (list (eq p q) (equal p q) (point-x p) (point-x q))) ; => (NIL T 1 99)
```
