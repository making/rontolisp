# port?

`(port? obj)`

`obj` がポートなら `#t` を返します。

```scheme
(port? (open-input-string "x")) ; => #t
```
