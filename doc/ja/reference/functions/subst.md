# subst

`(subst new old tree &key test test-not key)`

非破壊的な木の置換: `old` にマッチするすべての部分木・葉を `new` に置き換えた `tree` のコピーを返します。マッチは `(funcall test old (funcall key subtree))` で判定され、`:test` の既定は `eql`(既定ではアトムのみマッチ)、`:key` の既定は部分木そのものです。`:test-not` は否定されたテストで、`:test` がないときに使われます。変更されない部分木はコピーされず元の木と共有されます。項目ではなく述語でマッチさせる場合は [`subst-if`](subst-if.md) を使います。

```lisp
(subst 'x 'a '(a (b a) c)) ; => (X (B X) C)
```

```lisp
(subst 9 '(m) '(f (m) g) :test #'equal) ; => (F 9 G)
```
