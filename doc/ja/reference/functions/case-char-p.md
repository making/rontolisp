# lower-case-p, upper-case-p

`(lower-case-p character)`
`(upper-case-p character)`

`lower-case-p` は `character` が小文字であれば `t` を、`upper-case-p` は大文字であれば `t` を、それ以外は `nil` を返します。文字が小文字であるとは大文字化すると変化することであり（大文字であるとは小文字化すると変化すること）、どちらもプラットフォームの Unicode ケース表に従います。`--no-gc` を除くすべてのバックエンドで利用できます。文字でない引数は、演算子名を付けた `type-error` を通知します。

```lisp
(list (lower-case-p #\a) (upper-case-p #\A) (lower-case-p #\5)) ; => (T T NIL)
```
