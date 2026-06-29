# floor

`(floor number)`

`number` を負の無限大方向に丸めて整数にします。rontolisp では引数を 1 つ取り、単一の整数値を返します (完全な Common Lisp のようなオプションの除数や 2 番目の剰余値はありません)。

```lisp
(floor 3.7) ; => 3
```

```lisp
(floor -3.7) ; => -4
```
