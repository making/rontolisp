# torch:gelu

`(torch:gelu a &key approximate)`

The Gaussian error linear unit (PyTorch's `nn.GELU` /
`torch.nn.functional.gelu`). `:approximate` selects the formulation:

| `:approximate` | formula | PyTorch |
| --- | --- | --- |
| `:none` (default) | `x * (1 + erf(x / sqrt(2))) / 2` | `approximate='none'` |
| `:tanh` | `x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 x^3))) / 2` | `approximate='tanh'` |

The default is the EXACT `x * P(X <= x)` for a standard normal `X`, over
[`linalg:erf`](linalg-erf.md): one operation with an adjoint of its own that
spells out the backward of the five operations it is made of, so it computes
exactly what the composition would and runs as a single pass under
[`--gpu`](../../guides/gpu-acceleration.md). The `:tanh` form is the GPT/BERT
formulation, composed from torch operations, and agrees with the exact form to
about `1e-3`. Unlike [`torch:relu`](torch-relu.md) it is smooth everywhere and
passes a small negative gradient, which is why a transformer feed-forward block
uses it.

```lisp
(torch:data (torch:gelu (torch:tensor '(-1.0 0.0 1.0))))
; => #f(-0.15865526 0.0 0.8413447)
```
