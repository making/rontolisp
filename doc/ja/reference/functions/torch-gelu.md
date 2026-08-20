# torch:gelu

`(torch:gelu a &key approximate)`

ガウス誤差線形ユニット (PyTorch の `nn.GELU` / `torch.nn.functional.gelu`) です。
torch の演算だけで組み立てているため、専用の随伴なしで微分可能です。
`:approximate` で定式化を選びます。

| `:approximate` | 式 | PyTorch |
| --- | --- | --- |
| `:none` (既定) | `x * (1 + erf(x / sqrt(2))) / 2` | `approximate='none'` |
| `:tanh` | `x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 x^3))) / 2` | `approximate='tanh'` |

既定は標準正規分布 `X` に対する `x * P(X <= x)` の厳密形で、
[`torch:erf`](torch-erf.md) の上に構築されています。`:tanh` 形式は GPT/BERT の
定式化で、厳密形とは `1e-3` 程度で一致します。[`torch:relu`](torch-relu.md) と
違ってどこでも滑らかで、負側にもわずかな勾配を通します。Transformer の
フィードフォワードブロックがこれを使うのはそのためです。

```lisp
(torch:data (torch:gelu (torch:tensor '(-1.0 0.0 1.0))))
; => #d(-0.15865525393145702 0.0 0.841344746068543)
```
