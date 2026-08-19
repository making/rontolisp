# torch:unsqueeze

`(torch:unsqueeze a axis)`

微分可能な広がり 1 の軸の挿入 (`linalg:expand-dims`、torch の `unsqueeze`) です。負の軸は結果の末尾から数えるため `-1` は末尾に追加します。行優先の並びは変わらないので backward は reshape です。

```lisp
(torch:shape (torch:unsqueeze (torch:tensor '(1.0 2.0 3.0)) 0))  ; => (1 3)
(torch:shape (torch:unsqueeze (torch:tensor '(1.0 2.0 3.0)) -1)) ; => (3 1)
```
