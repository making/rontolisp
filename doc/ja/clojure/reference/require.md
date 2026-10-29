# require

`(require 'clause ...)`

namespace をロードし、各節の `:as` alias と `:refer` された名前を配線して、`nil` を返します -- `ns` が行うのと同じ配線を、クォートされた libspec でトップレベルに綴ったものです。[組み込みの namespace](namespaces.md#built-in-namespaces) はファイルを必要とせず、それ以外の namespace はソースパス上のファイルからプログラムにつき 1 度だけロードされ（[セマンティクス](../semantics.md#namespaces-and-files)）、どのルートにもないものはエラーです。名前を refer するのは `:refer` だけで、`:only` だけを付けても oracle と同じく何も refer しません。`:rename {old new}` は refer される var を `old` ではなく `new` で refer し、`:as-alias` は namespace をロードせずに alias だけを登録します（`::alias/k` やシンタックスクォート内の `alias/x` はその名前に展開されます）。クォートされていないベクター節も受け付けますが、本物の Clojure はそれを拒否します。`clojure.core` 自体は何もロードしません。`[clojure.core :as c]` で `c/x` は core の var `clojure.core/x` を表し（malli と cuerdas のエイリアス）、その `:refer` は `(:refer-clojure :exclude [...])` が外した名前を戻します。プレフィックスリスト `'(prefix [sub ...])` は各メンバーをプレフィックスの下に配線します（クォートの有無は問いません）。2 番目の要素がキーワードでないベクター（`'[clojure [set :as s] walk]`）も、oracle と同じくプレフィックスリストです。`:reload` は指定した各 namespace を再実行し（`def` はリセット、`defonce` はルートを保持）、`:reload-all` は依存先を先に再実行します。関数本体の中の `require` は本体の実行時にロードし、他の `require` と同じく `nil` を返します。そこと `try` や `when` の中では、どのルートにもない namespace はコードの実行時にだけ、oracle と同じ `FileNotFoundException`（`Could not locate a/b__init.class, a/b.clj or a/b.cljc on classpath.`）で失敗します。そのため省略可能なライブラリの `(try (require 'x) (catch Exception e ...))` が動きます。値としては、ライブラリのロードがプログラムを下ろすときに行われるため、実行時のどの適用も拒否します（spec がなければ oracle と同じ `Nothing specified to load`）。

```clojure
(require '[clojure.string :as s])
(println (s/join "," ["a"])) ; a
```
