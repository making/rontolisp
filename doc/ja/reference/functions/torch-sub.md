# torch:sub

`(torch:sub a b)`

numpy スタイルのブロードキャストを伴う、微分可能な要素ごとの `a - b` (`linalg:sub`) です。第 2 オペランドの勾配は符号が反転します (ブロードキャストの縮約は [`torch:add`](torch-add.md) と同じです)。

```lisp
(torch:data (torch:sub (torch:tensor '(5.0 7.0)) (torch:tensor '(1.0 2.0)))) ; => #f(4.0 5.0)
```
