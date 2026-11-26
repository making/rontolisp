# torch:relu

`(torch:relu a)`

微分可能な要素ごとの `max(x, 0.0)` (`linalg:relu`) です。勾配は `x > 0` の位置を通過し、それ以外では `0` です (`x = 0` ちょうどでは PyTorch と同じく `0`)。

```lisp
(torch:data (torch:relu (torch:tensor '(-1.0 0.0 2.0)))) ; => #f(0.0 0.0 2.0)
```
