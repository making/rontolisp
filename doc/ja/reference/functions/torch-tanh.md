# torch:tanh

`(torch:tanh a)`

微分可能な要素ごとの双曲線正接 (`linalg:tanh`)、古典的な活性化関数です。勾配は順伝播の結果から計算される `g * (1 - tanh^2 x)` です。

```lisp
(torch:data (torch:tanh (torch:tensor '(0.0)))) ; => #f(0.0)
```
