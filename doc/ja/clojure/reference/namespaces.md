# 名前空間

名前空間フォームは節を接続するだけで何も定義しません::as は別名、:refer/:use は非修飾名、:import は interop 用クラス名、(:refer-clojure :only/:exclude ...) は見える核を狭めます。解決するのは clojure.string と clojure.java.io（reader のみ）で、未知の名前空間はエラーです。名前空間自体はフラットです。

| Name | Example | Result |
|---|---|---|
| `ns` | `(do (ns demo (:require [clojure.string :as s])) (s/upper-case "hi"))` | `HI` |
| `require` | `(do (require '[clojure.string :as s]) (s/join "," ["a"]))` | `a` |
| `use` | `(do (use '[clojure.string :only [upper-case]]) (upper-case "hi"))` | `HI` |
| `import` | `(do (import java.util.Date) nil)` | `nil` |
| `in-ns` | `(do (in-ns 'demo) nil)` | `nil` |