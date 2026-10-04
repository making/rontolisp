# methods

`(methods multifn)`

multimethod のメソッドテーブルを、ディスパッチ値からメソッドのラムダへのマップとして返します。マップはコピーで、以降の `defmethod` や `remove-method` は反映されません。`nil` の行は `nil` をキーとし、`Object` 行とデフォルト行も含まれます。ホストクラスの行は `class` がそのクラスに対して返すキーワードの下にあります（オラクルは `Class` 自身をキーにします）。引数は `get-method` と同じく multimethod の名前です。

```clojure
(defmulti f :t)
(defmethod f :a [x] 1)
(defmethod f :b [x] 2)
(println (sort (keys (methods f)))) ; (:a :b)
(println ((get (methods f) :b) {})) ; 2
```
