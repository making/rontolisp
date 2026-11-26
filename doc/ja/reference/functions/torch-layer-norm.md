# torch:layer-norm

`(torch:layer-norm d-model &key eps)`

最終軸に対する層正規化レイヤー (PyTorch の `nn.LayerNorm`) を返します。フィールドは `:weight` (要素がすべて 1 の `(d-model)` パラメータ)、`:bias` (すべて 0 の `(d-model)` パラメータ)、そしてハイパーパラメータ `:eps` (既定 `1.0e-5`) です。順伝播は `(x - mean) / sqrt(var + eps) * weight + bias` で、分散は**不偏でない**もの (`ddof` 0、PyTorch の `unbiased=False`) を使います。

式全体が `torch` の演算で組み立てられているため、正規化そのものが微分可能です。勾配はアフィン変換のパラメータだけでなく平均と分散を通っても流れます。

```lisp
(torch:data (torch:forward (torch:layer-norm 2 :eps 0.0)
                           (torch:tensor '((1.0 3.0)))))  ; => #f((-1.0 1.0))
```
