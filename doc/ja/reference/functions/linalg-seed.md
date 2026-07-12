# linalg:seed

`(linalg:seed n)`

linalg パッケージが共有する乱数生成器 ([`linalg:rand`](linalg-rand.md)・[`linalg:randn`](linalg-randn.md)・[`linalg:uniform`](linalg-uniform.md)・[`linalg:choice`](linalg-choice.md)・[`linalg:permutation`](linalg-permutation.md) が使うもの) を非負整数 `n` から決定的に初期化し、`n` を返します。生成器は Wichmann-Hill の合成で、各 draw は正確な整数演算と正確な被演算子への IEEE double 演算だけで構成されるため、シード済みの列はすべてのバックエンド (インタプリタ / JVM / WASM) で bit-identical です。

```lisp
(linalg:seed 42) ; => 42
```
