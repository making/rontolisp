# use

`(use 'clause ...)`

namespace をロードし、クォートされた各節が列挙する名前を refer して、`nil` を返します -- `(:only [...])` が名前を狭め、`(:exclude [...])` がそこから差し引く、`ns` が行うのと同じ refer の配線です。フィルタがなければ namespace の public な var をすべて refer します（private な var は refer しません）。[組み込みの namespace](namespaces.md#built-in-namespaces) はファイルを必要とせず、それ以外の namespace はソースパス上のファイルからロードします。ライブラリのシンボルを裸で書くとその全体を refer し、未知の namespace はエラーです。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。2 番目の要素がキーワードでないベクター（`'[clojure [set :as s] walk]`）も、oracle と同じくプレフィックスリストです。値としては、ライブラリのロードがプログラムを下ろすときに行われるため、実行時のどの適用も拒否します（spec がなければ oracle と同じ `Nothing specified to load`）。

```clojure
(use '[clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
