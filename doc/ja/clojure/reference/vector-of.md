# vector-of

`(vector-of type x ...)`

`clojure.core/vector-of`: 要素を、プリミティブ型 `type` が保持する形にして並べたベクターです。
`:int`・`:long`・`:short`・`:byte` は数値を切り捨て（文字はコードにし）、範囲外の値は
オラクルの文言でシグナルを上げます（`integer overflow`、`Value out of range for byte: 200`）。
`:double` と `:float` は倍精度浮動小数点数にし、`:char` は文字かコードを取り、`:boolean` は
真偽値にします。それ以外の `type` は `Unrecognized type` でシグナルを上げます。結果は通常の
ベクターなので、あとの `conj` や `assoc` は値をそのまま格納します（オラクルの Vec は型変換を
続けます）。`:float` も倍精度で保持します（オラクルは単精度に丸めます）。値としては型と
任意個の要素を取る関数です。

```clojure
(println (vector-of :int 1.7 -1.7 \a)) ; [1 -1 97]
(println (vector-of :double 1 1/2))    ; [1.0 0.5]
```
