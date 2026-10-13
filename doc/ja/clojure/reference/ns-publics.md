# ns-publics

`(ns-publics ns)`

名前空間 `ns`（シンボル、文字列、`*ns*`）の公開 intern。各名前とその var のマップ。oracle と同じ。下ろしながら分かるものだけが載る。サブセットにない `clojure.core` の var は載らない（`resolve` と同じ）。求まる形でないものは実行時に拒否される。値としては 1 引数を取る。

```clojure
(def doc-eg-p 1)
(println (get (ns-publics 'user) 'doc-eg-p)) ; #'user/doc-eg-p
```
