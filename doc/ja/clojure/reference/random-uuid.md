# random-uuid

`(random-uuid)`

`clojure.core/random-uuid`: 暗号論的な乱数源 `rontolisp:random-bytes` から得た 122 ビットの
乱数によるバージョン 4 の UUID を返します（オラクルの乱数源は `SecureRandom` です）。すべての
バックエンドで動きます。値としては引数なしの関数です。

```clojure
(def id (random-uuid))
(println (uuid? id) (.version id) (.variant id) (count (str id)) (= id (parse-uuid (str id))))
```

```
true 4 2 36 true
```
