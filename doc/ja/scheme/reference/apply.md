# apply

`(apply proc arg ... list)`

`list` の要素を引数として `proc` を呼び出します。リストの前に `arg` を書くと、それらが先頭の引数になります。

```scheme
(apply + 1 2 '(3 4)) ; => 10
(apply max '(3 1 4)) ; => 4
```
