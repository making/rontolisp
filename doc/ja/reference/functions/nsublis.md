# nsublis

`(nsublis alist tree &key key test test-not)`

連想リストによる木の置換。[`sublis`](sublis.md) と同じです。木は変更されません([`nsubst`](nsubst.md) を参照)。

```lisp
(nsublis (list (cons 'a 1)) (list 'a (list 'b 'a))) ; => (1 (B 1))
```
