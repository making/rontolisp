# linalg:erf

`(linalg:erf a)`

Elementwise Gauss error function (`scipy.special.erf`), an odd function rising
from `-1` to `1`:

```text
erf(x) = 2 / sqrt(pi) * integral from 0 to x of e^(-t^2) dt
```

Accurate to the last few ulps of a double over the whole range: it sums the
all-positive-term series `A&S 7.1.6` rather than the alternating Maclaurin
series, whose cancellation loses every significant digit by `|x| ~ 3`, and
returns exactly `+-1` beyond `|x| = 6`, where the difference is below a double's
resolution.

There is no `erfc` member: `(linalg:sub 1.0 (linalg:erf a))` is it, and the far
tail where a dedicated `erfc` would be more accurate is also where `erf` itself
is already `1`. The differentiable counterpart is
[`torch:erf`](torch-erf.md), and `x * (1 + erf(x / sqrt(2))) / 2` is
[`torch:gelu`](torch-gelu.md).

```lisp
(linalg:erf #(0.0 1.0 -1.0)) ; => #d(0.0 0.842700792949715 -0.842700792949715)
```
