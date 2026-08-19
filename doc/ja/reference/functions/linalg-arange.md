# linalg:arange

`(linalg:arange stop &key element-type)` / `(linalg:arange start stop &optional step &key element-type)`

`start` (デフォルト 0) から `stop` の直前まで、`step` (デフォルト 1、負値も可) ずつ進む数のベクタを作成します。引数が 1 つの場合は numpy の `arange` と同様に 0 から数えます。デフォルトは packed double-float で、`:element-type 'single-float` を渡すと packed single-float (`#f`) になります。両端点を含めて要素数を固定したい場合は、代わりに [`linalg:linspace`](linalg-linspace.md) を使ってください。

```lisp
(linalg:arange 5)      ; => #d(0.0 1.0 2.0 3.0 4.0)
(linalg:arange 2 10 2) ; => #d(2.0 4.0 6.0 8.0)
(linalg:arange 5 0 -1) ; => #d(5.0 4.0 3.0 2.0 1.0)
(linalg:arange 0 4 :element-type 'single-float) ; => #f(0.0 1.0 2.0 3.0)
```
