# append!

`(append! list ...)`

リストを破壊的に連結します。空でない各リストの最後のペアを次のリストを指すように書き換え、結果は引数と構造を共有します。自分で作ったリストにだけ使い、クォートしたリテラルには使わないでください。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(append! (list 1 2) (list 3 4)) ; => (1 2 3 4)
(define a (list 1 2))
(append! a (list 3))
a ; => (1 2 3)
```
