# count

`(count item list)`

`list` の中で `item` と `eql` である要素の個数を返します。比較は `eql` のみで行います。述語で数えるには `count-if` を使います。

```lisp
(count 2 '(1 2 3 2 2)) ; => 3
```
