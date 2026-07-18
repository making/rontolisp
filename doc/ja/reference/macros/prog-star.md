# prog*

`(prog* (bindings...) {tag | form}...)`

[`prog`](prog.md) と同様ですが、束縛が逐次的 ([`let*`](let-star.md) 方式) です。各初期化フォームはそれより前に束縛された変数を参照できます。

```lisp
(prog* ((x 5) (y (* x 2)))
  (return (+ x y))) ; => 15
```
