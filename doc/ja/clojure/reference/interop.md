# Java interop

interop は java: サーフェスへ低下し、インタプリタと JVM でのみ動きます -- wasm バックエッドは java: を拒否します。クラス名は記されたドット区切り、:import 経由、java.lang 経由（オラクルの既定インポートと同じ一覧で、`IllegalStateException` などの代表的な例外クラスは含まれ、`AutoCloseable` は含まれません）のいずれかで解決されます。文字列レシーバは対応する核操作を返し（Lisp の文字列はホストオブジェクトではない）、ホストオブジェクトでない値の `.toString` は全バックエンドでその `str` の綴りを返します。ホストオブジェクトの `str` はその `toString` を返し、表示は `#<java C>` になります（クラスオブジェクトはクラス名で表示されます）。

| Name | Example | Result |
|---|---|---|
| `.` | `(. "hi" length)` | `2` |
| `..` | `(.. "hi" (toUpperCase) (length))` | `2` |
| `.name / .-name` | `(.toUpperCase "hi")` | `HI` |
| `Class/member` | `(Integer/parseInt "42")` | `42` |
| `Class/member` (値) | `(every? Character/isWhitespace " ")` | `true` |
| `Class/.method` | `(map String/.length ["ab" "abcd"])` | `(2 4)` |
| `Class/new` | `(String/new "q")` | `q` |
| `^[types]` | `(map ^[double] Math/abs [-1 2])` | `(1.0 2.0)` |
| `new` | `(.length (new String "hi"))` | `2` |
| `memfn` | `((memfn toUpperCase) "hi")` | `HI` |
| `proxy` | `(.get (proxy [java.util.function.Supplier] [] (get [] "p")))` | `p` |