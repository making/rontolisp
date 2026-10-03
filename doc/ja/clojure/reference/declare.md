# declare

`(declare name...)`

各名前を前方宣言します。pass one の事前走査が `declare` の名前もトップレベルの `def`/`defn` の名前とともに集めるため、上にある定義は下で初めて定義される関数を呼べます。実際の定義は宣言に勝ります。宣言されただけで定義されない名前はオラクルと同じく未束縛の var です。その値と `@#'name` は未束縛のルート（真として扱われ、`str` は `Unbound: #'user/name`、表示は `#<Unbound: #'user/name>`）で、呼び出すと `Attempting to call unbound fn: #'user/name` をシグナルし、[bound?](bound-p.md) は `false` です。`declare` は束縛済みのルートを変えず、var のメタデータに `:declared true` を加えます。`^:dynamic` の名前は `binding` で再束縛できます。フォーム自身は `nil` を返します。

```clojure
(declare dcl-f)
(defn dcl-g [] (dcl-f 1))
(defn dcl-f [x] (* 2 x))
(println (dcl-g)) ; 2
(declare dcl-u)
(println (str dcl-u) (bound? #'dcl-u)) ; Unbound: #'user/dcl-u false
```
