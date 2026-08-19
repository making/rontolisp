# linalg:*

`(linalg:* &rest arrays)`

引数を左から順に要素ごとに乗算し (アダマール積であって、行列積では**ありません** -- 行列積は [`linalg:matmul`](linalg-matmul.md) です)、新しい配列を返します。[`linalg:mul`](linalg-mul.md) の CL 演算子スペルであり、同じ numpy の規則でブロードキャストします。引数がない場合は `1`、引数が 1 つの場合はその引数をそのまま返します。

```lisp
(linalg:* #(1 2) #(3 4))          ; => #d(3.0 8.0)
(linalg:* #(1 2 3) 2 10)          ; => #d(20.0 40.0 60.0)
(linalg:*)                        ; => 1
```
