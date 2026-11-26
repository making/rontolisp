# length

`(length list)`

`list` の要素数を返します。

仕様との差異: 真でないリスト（improper list）はエラーにならず、ペアの数を数えます。`(length '(1 . 2))` は `1` です。

```scheme
(length '(1 2 3)) ; => 3
(length '((1 2) 3)) ; => 2
(length '()) ; => 0
```
