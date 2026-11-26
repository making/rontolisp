# elt

`(elt sequence index)`

`sequence` の 0 始まりの `index` にある要素を返します。文字列の場合は文字を、リストやベクタの場合はその要素を返します。シーケンスの外を指す `index`（負の値、または長さ以上の値）は、期待型が `(integer 0 (長さ))` の `type-error` を通知します。[`nth`](nth.md) と異なり、リストの終端より先を読んでも `nil` にはなりません。`setf` の place としても使えます。書き込み時にシーケンスの種類ごとに何が起きるかは [`setf`](../macros/setf.md) を参照してください。

```lisp
(elt '(a b c) 1) ; => B
```

```lisp
(elt "abcd" 1) ; => #\b
```

```lisp
(elt (vector 10 20 30) 2) ; => 30
```

```lisp
(handler-case (elt '(a b c) 3) (type-error (e) (type-error-expected-type e))) ; => (INTEGER 0 (3))
```
