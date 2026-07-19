# with-package-iterator

`(with-package-iterator (name package-list symbol-type...) body...)`

ライト版展開: `name` を「もうシンボルはない」と常に報告するローカル関数(CL の `macrolet` ではなく `flet`)に束縛します — 反復対象の intern テーブルが存在しないため、反復ループは 0 回実行されます(cl-ppcre の `regex-apropos`)。package-list フォームは 1 回だけ評価されます。

```lisp
(with-package-iterator (next nil :external)
  (multiple-value-bind (morep sym) (next)
    (list morep sym))) ; => (nil nil)
```
