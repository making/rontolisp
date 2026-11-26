# linalg:erf

`(linalg:erf a)`

要素ごとのガウス誤差関数 (`scipy.special.erf`) です。`-1` から `1` へ増加する奇関数です。

```text
erf(x) = 2 / sqrt(pi) * integral from 0 to x of e^(-t^2) dt
```

全域で double の下位数 ulp まで正確です。交代級数の Maclaurin 展開は `|x| ~ 3`
付近で桁落ちにより有効数字を失うため、代わりに全項が正の級数 (`A&S 7.1.6`) を
加算します。`|x| = 6` を超えると差が double の分解能を下回るため、ちょうど `+-1`
を返します。

`erfc` は用意していません。`(linalg:sub 1.0 (linalg:erf a))` がそれであり、専用の
`erfc` の方が精度が高くなる裾野の領域は、`erf` 自体が既に `1` である領域でも
あるためです。微分可能な対応物は [`torch:erf`](torch-erf.md) で、
`x * (1 + erf(x / sqrt(2))) / 2` が [`torch:gelu`](torch-gelu.md) です。

```lisp
(linalg:erf #(0.0 1.0 -1.0)) ; => #d(0.0 0.842700792949715 -0.842700792949715)
```
