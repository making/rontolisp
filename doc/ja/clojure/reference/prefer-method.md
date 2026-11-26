# prefer-method

`(prefer-method multifn val1 val2)`

ディスパッチ検索が両者を候補の祖先として見つけ、どちらも狭義にはより具体的でないときに `val1` が `val2` に勝つことを記録します。multimethod を返します。これがなければ、そのような同順は `Multiple methods ...` をシグナルします。

```clojure
(defmulti p :k)
(defmethod p :x [m] 1)
(defmethod p :y [m] 2)
(derive :x :y)
(prefer-method p :x :y)
(println (p {:k :x})) ; 1
```
