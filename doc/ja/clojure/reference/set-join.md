# clojure.set/join

`(clojure.set/join xrel yrel)`
`(clojure.set/join xrel yrel km)`

関係の結合を返します。2 つの関係の先頭メンバーが共有するキーで（`km` があれば、`km` が `yrel` のキーへ対応させる `xrel` のキーで）一致するメンバーの組をそれぞれ 1 つのマップへマージします。小さい方の関係に索引を作ります。共通のキーがなければすべての組が結合し、空の関係には `#{}` を返します。`alias/var` や refer された裸の `join` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(def composers #{{:composer "Bach" :country "Germany"}})
(def nations #{{:nation "Germany" :language "German"}})
(println (= (set/join composers nations {:country :nation})
            #{{:composer "Bach" :country "Germany" :nation "Germany" :language "German"}})) ; true
(println (set/join #{{:a 1 :b 2}} #{{:a 2 :c 3}})) ; #{}
```
