# torch:erf

`(torch:erf a)`

微分可能な要素ごとのガウス誤差関数 (PyTorch の `torch.erf`、
[`linalg:erf`](linalg-erf.md) の上に構築) です。随伴はガウシアン
`2 / sqrt(pi) * e^(-x^2)` であり、順伝播側の近似をさらに近似したものではなく
厳密な勾配になります。

```lisp
(defparameter *x* (torch:tensor '(0.0) :requires-grad t))
(torch:backward (torch:sum (torch:erf *x*)))
(< (abs (- (torch:item (torch:tensor (torch:grad *x*))) 1.1283791670955126))
   1.0e-12)                              ; => T
```
