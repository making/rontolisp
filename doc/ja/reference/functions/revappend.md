# revappend

`(revappend list tail)`

`list` の要素を逆順に並べ、その後ろに `tail` を続けた新しいリストを返します。`(append (reverse list) tail)` と等価ですが、1 回の走査で行われます。`list` はコピーされ (変更されません)、`tail` は結果と共有されて最終部分になります。

```lisp
(revappend '(1 2 3) '(4 5)) ; => (3 2 1 4 5)
```
