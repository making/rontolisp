# ns-map

`(ns-map ns)`

名前空間 `ns`（シンボル、文字列、`*ns*`）の全対応。intern、refer、参照する `clojure.core` の var、import を各名前とその var で。oracle と同じ。下ろしながら分かるものだけが載り、求まる形でないものは実行時に拒否される。import はクラスのシンボルに対応する（クラスはホストバックエンドにしかない）。対が多すぎる対応は pieces に分けて作るため、どのメソッドもバックエンドの大きさ制限を超えない。値としては 1 引数を取る。

```clojure
(println (get (ns-map 'user) 'map)) ; #'clojure.core/map
```
