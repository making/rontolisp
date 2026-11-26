# linalg:from-list

`(linalg:from-list list &key element-type)`

リストを linalg 配列に変換します。フラットなリストはランク 1 のベクタに、同じ長さの行リストからなるリストはランク 2 の行列になります。linalg のコードで配列リテラルを書く際の標準的な方法です。逆方向の変換は [`linalg:to-list`](linalg-to-list.md) です。 デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。 `:element-type 'bfloat16` を渡すと packed bfloat16 (`#bf16`) になります (インタプリタと JVM のみ。[単精度浮動小数点](../../guides/linear-algebra.md#single-float-precision))。

```lisp
(linalg:from-list '(1 2 3))     ; => #d(1.0 2.0 3.0)
(linalg:from-list '((1 2) (3 4))) ; => #d((1.0 2.0) (3.0 4.0))
(linalg:from-list '(1 2) :element-type 'single-float) ; => #f(1.0 2.0)
```
