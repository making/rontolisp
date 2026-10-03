# 名前空間

名前空間はそれぞれ自分の var を持ちます。定義は現在の名前空間に属し、名前はまずその名前空間自身の var に、次に refer された var に解決され、`alias/name` や `full.name/name` は別の名前空間の public な var を指します。名前空間フォームはその名前空間へ切り替えて節を接続します::as は別名、:refer/:use は非修飾名、:import は interop 用クラス名を登録し、(:refer-clojure :only/:exclude ...) は見える核を狭めます。名前に付いたメタデータ（`^{...}`・`#^{...}`）、ドキュメント文字列、属性マップは読み飛ばします。clojure.string、clojure.set、clojure.java.io（reader のみ）、clojure.test は組み込みで、それ以外はプログラムが `ns` で宣言した名前空間か、ソースパス上のファイルから 1 度だけロードされる名前空間です（[セマンティクス](../semantics.md#namespaces-and-files)）。どのルートにもない名前空間はエラーです。

| Name | Example | Result |
|---|---|---|
| `ns` | `(do (ns demo (:require [clojure.string :as s])) (s/upper-case "hi"))` | `HI` |
| `require` | `(do (require '[clojure.string :as s]) (s/join "," ["a"]))` | `a` |
| `use` | `(do (use '[clojure.string :only [upper-case]]) (upper-case "hi"))` | `HI` |
| `import` | `(do (import java.util.Date) nil)` | `nil` |
| `in-ns` | `(do (in-ns 'demo) nil)` | `nil` |
