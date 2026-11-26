# transduce

`(transduce xform f coll)` / `(transduce xform f init coll)`

`coll` を `init` から `(xform f)` で畳み込み、最後に完了ステップ `((xform f) result)` を
走らせます。`init` を省くと `(f)` を使います（`xform` が `f` を受け取る前に呼びます）。
[`reduced`](reduced.md) を返すステップで畳み込みを止めます。値としては3引数か4引数を取ります。

```clojure
(println (transduce (map inc) + [1 2 3])) ; 9
(println (transduce (filter odd?) conj [1 2 3 4 5])) ; [1 3 5]
(println (transduce (map inc) + 10 [1 2 3])) ; 19
```
