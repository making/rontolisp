# subst

`(subst new old tree &key test key)`

非破壊的な木の置換: `old` にマッチするすべての部分木・葉を `new` に置き換えた `tree` のコピーを返します。マッチは `(funcall test old (funcall key subtree))` で判定され、`:test` の既定は `eql`(既定ではアトムのみマッチ)、`:key` の既定は部分木そのものです。変更されない部分木はコピーされず元の木と共有されます。

```lisp
(subst 'x 'a '(a (b a) c)) ; => (x (b x) c)
```

```lisp
(subst 9 '(m) '(f (m) g) :test #'equal) ; => (f 9 g)
```
