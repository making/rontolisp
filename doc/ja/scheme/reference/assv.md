# assv

`(assv obj alist)`

`assq` と同様ですが `eqv?` で比較するため、数値や文字のキーは値で見つかります。

```scheme
(assv 5 '((2 3) (5 7) (11 13))) ; => (5 7)
```
