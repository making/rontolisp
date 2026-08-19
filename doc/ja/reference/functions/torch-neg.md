# torch:neg

`(torch:neg a)`

微分可能な要素ごとの符号反転 (`linalg:negative`) です。勾配は逆伝播時に符号が反転します。

```lisp
(torch:data (torch:neg (torch:tensor '(1.0 -2.0)))) ; => #d(-1.0 2.0)
```
