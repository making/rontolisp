# prog

`(prog (bindings...) {tag | form}...)`

[`let`](../special-forms/let.md) と同様に変数を束縛し、本体をブロック内の [`tagbody`](../special-forms/tagbody.md) として実行します。[`go`](../special-forms/go.md) が本体のタグ間をジャンプし、`(return value)` が `value` を返して `prog` を抜けます。末尾に到達すると nil を返します。

```lisp
(prog ((n 5) (acc 1))
 top
  (when (<= n 1) (return acc))
  (setq acc (* acc n))
  (setq n (- n 1))
  (go top)) ; => 120
```
