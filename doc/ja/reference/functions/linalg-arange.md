# linalg:arange

`(linalg:arange stop)` / `(linalg:arange start stop &optional step)`

`start` (デフォルト 0) から `stop` の直前まで、`step` (デフォルト 1、負値も可) ずつ進む数のベクタを作成します。引数が 1 つの場合は numpy の `arange` と同様に 0 から数えます。両端点を含めて要素数を固定したい場合は、代わりに [`linalg:linspace`](linalg-linspace.md) を使ってください。

```lisp
(linalg:arange 5)      ; => #f(0.0 1.0 2.0 3.0 4.0)
(linalg:arange 2 10 2) ; => #f(2.0 4.0 6.0 8.0)
(linalg:arange 5 0 -1) ; => #f(5.0 4.0 3.0 2.0 1.0)
```
