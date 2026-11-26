# torch:mul

`(torch:mul a b)`

numpy スタイルのブロードキャストを伴う、微分可能な要素ごとの (アダマール) 積 `a * b` (`linalg:mul`) です。行列積は [`torch:matmul`](torch-matmul.md) です。各オペランドの勾配は、入ってくる勾配ともう一方のオペランドの積です。

```lisp
(torch:data (torch:mul (torch:tensor '(1.0 2.0 3.0)) (torch:tensor '(4.0 5.0 6.0)))) ; => #f(4.0 10.0 18.0)
(torch:data (torch:mul (torch:tensor '(1.0 2.0)) 2))                                  ; => #f(2.0 4.0)
```
