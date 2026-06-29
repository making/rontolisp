# ceiling

`(ceiling number)`

`number` を正の無限大方向へ丸めて整数にします。rontolisp では引数を 1 つだけ受け取り、整数値を 1 つ返します（フルの Common Lisp のようなオプションの除数や 2 つ目の剰余値はありません）。

```lisp
(ceiling 3.2) ; => 4
```

```lisp
(ceiling -3.2) ; => -3
```
