# torch:gelu

`(torch:gelu a &key approximate)`

ガウス誤差線形ユニット (PyTorch の `nn.GELU` / `torch.nn.functional.gelu`) です。
`:approximate` で定式化を選びます。

| `:approximate` | 式 | PyTorch |
| --- | --- | --- |
| `:none` (既定) | `x * (1 + erf(x / sqrt(2))) / 2` | `approximate='none'` |
| `:tanh` | `x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 x^3))) / 2` | `approximate='tanh'` |

既定は標準正規分布 `X` に対する `x * P(X <= x)` の厳密形で、
[`linalg:erf`](linalg-erf.md) の上に構築されています。これは専用の随伴を持つ
1 つの演算で、その随伴は構成要素である 5 つの演算の逆伝播をそのまま綴っているため、
合成と厳密に同じ値を計算し、[`--gpu`](../../guides/gpu-acceleration.md) では
1 パスで実行されます。`:tanh` 形式は GPT/BERT の定式化で、torch の演算から
組み立てられており、厳密形とは `1e-3` 程度で一致します。[`torch:relu`](torch-relu.md) と
違ってどこでも滑らかで、負側にもわずかな勾配を通します。Transformer の
フィードフォワードブロックがこれを使うのはそのためです。

```lisp
(torch:data (torch:gelu (torch:tensor '(-1.0 0.0 1.0))))
; => #f(-0.15865526 0.0 0.8413447)
```
