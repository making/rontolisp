# 名前空間

名前空間はそれぞれ自分の var を持ちます。定義は現在の名前空間に属し、名前はまずその名前空間自身の var に、次に refer された var に解決され、`alias/name` や `full.name/name` は別の名前空間の public な var を指します。名前空間フォームはその名前空間へ切り替えて節を接続します::as は別名、:refer/:use は非修飾名、:import は interop 用クラス名を登録し、(:refer-clojure :only/:exclude ...) は見える核を狭めます。名前に付いたメタデータ（`^{...}`・`#^{...}`）、ドキュメント文字列、属性マップは読み飛ばします。[組み込みの名前空間](#built-in-namespaces)はファイルを必要としません。それ以外はプログラムが `ns` で宣言した名前空間か、ソースパス上のファイルから 1 度だけロードされる名前空間です（[セマンティクス](../semantics.md#namespaces-and-files)）。どのルートにもない名前空間はエラーです。

| Name | Example | Result |
|---|---|---|
| `ns` | `(do (ns demo (:require [clojure.string :as s])) (s/upper-case "hi"))` | `HI` |
| `require` | `(do (require '[clojure.string :as s]) (s/join "," ["a"]))` | `a` |
| `use` | `(do (use '[clojure.string :only [upper-case]]) (upper-case "hi"))` | `HI` |
| `import` | `(do (import java.util.Date) nil)` | `nil` |
| `in-ns` | `(do (in-ns 'demo) nil)` | `nil` |
| `the-ns` | `(str (the-ns 'user))` | `"user"` |
| `find-ns` | `(find-ns 'no-such)` | `nil` |
| `ns-name` | `(ns-name *ns*)` | `user` |

## 組み込みの名前空間

Clojure で書かれた組み込みの名前空間は、プロジェクトのファイルと同じ手順で、すべてのソースルートの
あとに読み込まれます。そのため、ソースパス上に同じ名前のファイルがあればそちらが優先されます。
例外は `clojure.walk` と `clojure.core.protocols` で、Clojure と同じくプログラムより先に読み込まれており、
`clojure.walk/postwalk` のような修飾名は、`clojure.edn` や `clojure.string` の修飾名と同じく `require`
なしで届きます。Clojure 本体に含まれない `clojure.*` 名前空間
（`clojure.data.json` などの contrib ライブラリ）は、ほかのライブラリと同じくソースパスから
読み込みます。それ以外の Clojure 本体の名前空間はエラーです。

| 名前空間 | ページ |
|---|---|
| `clojure.string` | [(clojure.string)](string.md) |
| `clojure.set` | [(clojure.set)](clojure-set.md) |
| `clojure.walk` | [clojure.walk](clojure-walk.md) |
| `clojure.edn` | [clojure.edn](clojure-edn.md) |
| `clojure.data` | [clojure.data](clojure-data.md) |
| `clojure.zip` | [clojure.zip](clojure-zip.md) |
| `clojure.datafy`, `clojure.core.protocols` | [clojure.datafy](clojure-datafy.md) |
| `clojure.core.reducers` | [clojure.core.reducers](clojure-core-reducers.md) |
| `clojure.stacktrace` | [clojure.stacktrace](clojure-stacktrace.md) |
| `clojure.math` | [clojure.math](clojure-math.md) |
| `clojure.pprint` | [clojure.pprint](clojure-pprint.md) |
| `clojure.template` | [clojure.template](clojure-template.md) |
| `clojure.java.io`（`reader` のみ） | [入出力](io.md) |
| `clojure.test` | [テスト (clojure.test)](test.md) |
| `ring.adapter.rontolisp` | [Ring アダプター](ring.md) |
| `ring.util.*`、`ring.middleware.*` | [Ring ユーティリティ](ring-util.md) |
| `rontolisp.http-client` | [HTTP クライアント](http-client.md) |
