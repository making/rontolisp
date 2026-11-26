# let

`(let ((variable init)...) body...)` `(let name ((variable init)...) body...)`

すべての `init` を評価し、各 `variable` をその値に束縛して `body` を評価します。2 つ目の形（名前付き `let`）は、さらに `name` を「その変数を仮引数とし `body` を本体とする手続き」に束縛するもので、ループに使います。名前が末尾位置でだけ呼ばれる名前付き `let` は一定のスタックで動きます。

```scheme
(let ((a 1) (b 2)) (+ a b)) ; => 3
(let loop ((i 0) (acc '())) (if (= i 3) acc (loop (+ i 1) (cons i acc)))) ; => (2 1 0)
```
