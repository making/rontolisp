# clojure.set/rename

`(clojure.set/rename xrel kmap)`

関係の各メンバーのキーを `rename-keys` と同じく `kmap` で改名した集合を返します。`alias/var` や refer された裸の `rename` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/rename #{{:a 1}} {:a :z})) ; #{{:z 1}}
```
