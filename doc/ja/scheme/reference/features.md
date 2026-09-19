# features

`(features)`

[cond-expand](cond-expand.md) が判定する機能識別子の新しいリストを返します: `r7rs`、`exact-closed`（`/` 以外の正確な演算は正確なまま）、`ieee-float`（非正確数は IEEE 754 の倍精度浮動小数点数）、`full-unicode`（Unicode のコードポイントごとに 1 文字）、`ratios`（正確数どうしの `/` は正確）、`rontolisp` です。どれもすべてのバックエンドで同じように成り立つので、オペレーティングシステムやプロセッサの名前は含みません。Gauche は独自のより長いリストを返します。

```scheme
(features) ; => (r7rs exact-closed ieee-float full-unicode ratios rontolisp)
(if (memq 'ratios (features)) 'exact 'inexact) ; => exact
```
