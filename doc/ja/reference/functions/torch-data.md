# torch:data

`(torch:data tensor)`

テンソルのデータ、すなわち linalg 配列 (任意ランクのパックド float)、スカラーテンソルなら数値を返します。`linalg` 関数が受け取る生の配列なので、値はこのリーダーを通じて微分可能レイヤーの外に出ます (テンソルのままテープから切り離すには [`torch:detach`](torch-detach.md))。

```lisp
(torch:data (torch:tensor '((1.0 2.0) (3.0 4.0)))) ; => #f((1.0 2.0) (3.0 4.0))
(torch:data (torch:sum (torch:tensor '(1.0 2.0))))  ; => 3.0
```
