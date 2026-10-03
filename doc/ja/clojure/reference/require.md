# require

`(require 'clause ...)`

namespace をロードし、各節の `:as` alias と `:refer` された名前を配線して、`nil` を返します -- `ns` が行うのと同じ配線を、クォートされた libspec でトップレベルに綴ったものです。`clojure.string`、`clojure.set`、`clojure.java.io`（`reader` のみ）、`clojure.test` は組み込みで、それ以外の namespace はソースパス上のファイルからプログラムにつき 1 度だけロードされ（[セマンティクス](../semantics.md#namespaces-and-files)）、どのルートにもないものはエラーです。名前を refer するのは `:refer` だけで、`:only` だけを付けても oracle と同じく何も refer しません。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。`:reload` は指定した各 namespace を再実行し（`def` はリセット、`defonce` はルートを保持）、`:reload-all` は依存先を先に再実行します。関数本体の中の `require` は本体の実行時にロードし、他の `require` と同じく `nil` を返します。

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
