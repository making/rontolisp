# remove-all-methods

`(remove-all-methods multifn)`

すべてのメソッドと優先指定を消し、マルチメソッドを返す。oracle と同じ。`methods` と同じくマルチメソッドの名前を取る。ローカルに束縛されたものは lowering 時に拒否され、関数値はない。

```clojure
(defmulti serve :kind)
(defmethod serve :coffee [m] :hot)
(remove-all-methods serve)
(println (methods serve)) ; {}
```
