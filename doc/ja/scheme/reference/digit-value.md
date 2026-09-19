# digit-value

`(digit-value char)`

`char` がいずれかの文字体系の 10 進数字なら、その値（0 から 9）を返し、そうでなければ `#f` を返します。`char-numeric?` が `#t` を返すのとちょうど同じ文字です。

```scheme
(digit-value #\3) ; => 3
(digit-value #\٤) ; => 4
(digit-value #\x) ; => #f
```
