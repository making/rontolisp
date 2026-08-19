# torch:sum

`(torch:sum a &key axis keepdims)`

微分可能な合計です。`:axis` なしでは全要素、`:axis` を渡すとその軸に沿って計算し、`:axis` / `:keepdims` の規則は `linalg:sum` と同じです。backward は縮約された広がりへ勾配をブロードキャストして戻します。

```lisp
(torch:item (torch:sum (torch:tensor '(1.0 2.0 3.0))))               ; => 6.0
(torch:data (torch:sum (torch:tensor '((1.0 2.0) (3.0 4.0))) :axis 0)) ; => #d(4.0 6.0)
```
