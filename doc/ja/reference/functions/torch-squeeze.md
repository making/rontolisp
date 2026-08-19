# torch:squeeze

`(torch:squeeze a &key axis)`

微分可能な広がり 1 の軸の除去 (`linalg:squeeze`) です。`:axis` なしではすべて、指定すると名指しした軸 (またはそのリスト) だけを除去します。すべての軸を除去するとスカラーテンソルになります。

```lisp
(torch:shape (torch:squeeze (torch:tensor '((1.0 2.0 3.0))))) ; => (3)
(torch:data (torch:squeeze (torch:tensor '((7.0)))))          ; => 7.0
```
