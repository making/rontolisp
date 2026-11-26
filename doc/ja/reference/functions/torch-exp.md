# torch:exp

`(torch:exp a)`

微分可能な要素ごとの `e^x` (`linalg:exp`) です。backward は順伝播の結果を再利用します (`d/dx e^x = e^x`)。

以下の結果は、`e^x` がプラットフォーム自身の `exp` だった頃に丸めたものです。今はどのバックエンド・マシンでも fdlibm の値なので、桁をすべて出しても一致します。

```lisp
(linalg:emap (lambda (x) (/ (round (* x 1000)) 1000.0)) (torch:data (torch:exp (torch:tensor '(0.0 1.0))))) ; => #f(1.0 2.718)
```
