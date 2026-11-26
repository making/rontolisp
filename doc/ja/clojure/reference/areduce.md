# areduce

`(areduce array index result init expr)`

`expr` を `array` に対して添字で畳み込みます:配列を 1 回だけ評価してから、蓄積子
`result` を `init` から各段階での `expr` の答えに置き換えながら添字を 0 から進め、その
蓄積子を答えます。`index` はループの添字を指す名前で、この形の中でのみ有効です。

```clojure
(def ar-a (int-array [1 2 3]))
(println (areduce ar-a i r 0 (+ r (aget ar-a i)))) ; 6
(println (areduce ar-a i r [] (conj r (aget ar-a i)))) ; [1 2 3]
```
