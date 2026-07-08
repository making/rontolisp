# linalg:from-list

`(linalg:from-list list)`

リストを linalg 配列に変換します。フラットなリストはランク 1 のベクタに、同じ長さの行リストからなるリストはランク 2 の行列になります。linalg のコードで配列リテラルを書く際の標準的な方法です。逆方向の変換は [`linalg:to-list`](linalg-to-list.md) です。

```lisp
(linalg:from-list '(1 2 3))     ; => #f(1.0 2.0 3.0)
(linalg:from-list '((1 2) (3 4))) ; => #f((1.0 2.0) (3.0 4.0))
```
