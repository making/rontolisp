# nsubst

`(nsubst new old tree &key test test-not key)`

木の置換。[`subst`](subst.md) と同じです。CL は `nsubst` が木を破壊的に書き換えることを許しますが、rontolisp は規格が認める非破壊的な結果を返します。変更されない部分木はもともとコピーされず元の木と共有されます。

```lisp
(nsubst 'x 'a (list 'a (list 'b 'a) 'c)) ; => (X (B X) C)
```
