# reverse

`(reverse sequence)`

`sequence` の要素を逆順に並べた新しいシーケンスを返し、元のシーケンスはそのまま残します。これは `nreverse` の非破壊版です。空のリストを逆順にすると `nil` になります。

```lisp
(reverse '(1 2 3)) ; => (3 2 1)
```
