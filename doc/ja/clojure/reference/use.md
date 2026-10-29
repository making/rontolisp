# use

`(use 'clause ...)`

namespace をロードし、クォートされた各節が列挙する名前を refer して、`nil` を返します -- `(:only [...])` が名前を狭め、`(:exclude [...])` がそこから差し引く、`ns` が行うのと同じ refer の配線です。フィルタがなければ namespace の public な var をすべて refer します（private な var は refer しません）。[組み込みの namespace](namespaces.md#built-in-namespaces) はファイルを必要とせず、それ以外の namespace はソースパス上のファイルからロードします。ライブラリのシンボルを裸で書くとその全体を refer し、未知の namespace はエラーです。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。2 番目の要素がキーワードでないベクター（`'[clojure [set :as s] walk]`）も、oracle と同じくプレフィックスリストです。求まる形の spec（ライブラリを指すローカルや var、それを返す呼び出し）と値としてのどの適用も、インタプリタでは呼び出しの実行時にロードします（インタプリタはプログラムの lower を実行中も保持します）。コンパイル済みプログラムはライブラリをプログラムを下ろすときにロードするため、実行時に拒否します（spec がなければどちらでも oracle と同じ `Nothing specified to load`）。

```clojure
(use '[clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
