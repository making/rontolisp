# clojure.set/rename-keys

`(clojure.set/rename-keys m kmap)`

`m` が持つ `kmap` の各キーを、`kmap` でそのキーに対応する値へ改名した `m` を返します。先に `kmap` のキーをすべて取り除くので、2 つのキーを入れ替えられます。`nil` には `nil` を返します。レコードは、宣言されたフィールドを改名で失わない限りレコードのままです。`alias/var` や refer された裸の `rename-keys` としても届き、関数値としても動きます。

```clojure
(ns demo (:require [clojure.set :as set]))
(println (set/rename-keys {:a 1 :b 2} {:a :b})) ; {:b 1}
(println (= (set/rename-keys {:a 1 :b 2} {:a :b :b :a}) {:a 2 :b 1})) ; true
```
