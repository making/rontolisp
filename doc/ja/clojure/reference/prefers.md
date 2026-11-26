# prefers

`(prefers multifn)`

各ディスパッチ値と、それより優先される値の集合の対応。oracle のマップと同じ（優先指定がなければ `{}`）。`methods` と同じくマルチメソッドの名前を取る。ローカルに束縛されたものは lowering 時に拒否され、関数値はない。

```clojure
(defmulti serve :kind)
(defmethod serve :coffee [m] :hot)
(prefer-method serve :coffee :tea)
(println (= (prefers serve) {:coffee #{:tea}})) ; true
```
