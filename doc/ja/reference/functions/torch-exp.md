# torch:exp

`(torch:exp a)`

微分可能な要素ごとの `e^x` (`linalg:exp`) です。backward は順伝播の結果を再利用します (`d/dx e^x = e^x`)。

以下の結果は意図的に丸めてあります。`e^x` はプラットフォーム自身の `exp` が返す値であり、その最後の桁はマシンやバックエンドによって異なりうるからです。

```lisp
(linalg:emap (lambda (x) (/ (round (* x 1000)) 1000.0)) (torch:data (torch:exp (torch:tensor '(0.0 1.0))))) ; => #f(1.0 2.718)
```
