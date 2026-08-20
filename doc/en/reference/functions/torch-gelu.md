# torch:gelu

`(torch:gelu a &key approximate)`

The Gaussian error linear unit (PyTorch's `nn.GELU` /
`torch.nn.functional.gelu`), composed from torch operations and therefore
differentiable with no adjoint of its own. `:approximate` selects the
formulation:

| `:approximate` | formula | PyTorch |
| --- | --- | --- |
| `:none` (default) | `x * (1 + erf(x / sqrt(2))) / 2` | `approximate='none'` |
| `:tanh` | `x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 x^3))) / 2` | `approximate='tanh'` |

The default is the EXACT `x * P(X <= x)` for a standard normal `X`, over
[`torch:erf`](torch-erf.md); the `:tanh` form is the GPT/BERT formulation and
agrees with it to about `1e-3`. Unlike [`torch:relu`](torch-relu.md) it is
smooth everywhere and passes a small negative gradient, which is why a
transformer feed-forward block uses it.

Accuracy is not the only axis: under [`--simd`](../../guides/simd-acceleration.md#accelerating-linalg)
the `:tanh` form is accelerated (it is `mul` / `add` / `tanh`) and the default is
not, because [`linalg:erf`](linalg-erf.md) is not among the intercepted kernels.

```lisp
(torch:data (torch:gelu (torch:tensor '(-1.0 0.0 1.0))))
; => #f(-0.15865526 0.0 0.8413447)
```
