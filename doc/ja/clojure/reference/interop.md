# Java interop

interop は java: サーフェスへ低下し、インタプリタと JVM でのみ動きます -- wasm バックエッドは java: を拒否します。クラス名は記されたドット区切り、:import 経由、java.lang 経由のいずれかで解決されます。文字列レシーバは対応する核操作を返します（Lisp の文字列はホストオブジェクトではない）。

| Name | Example | Result |
|---|---|---|
| `.` | `(. "hi" length)` | `2` |
| `..` | `(.. "hi" (toUpperCase) (length))` | `2` |
| `.name / .-name` | `(.toUpperCase "hi")` | `HI` |
| `Class/member` | `(Integer/parseInt "42")` | `42` |
| `Class/member` (値) | `(every? Character/isWhitespace " ")` | `true` |
| `new` | `(.length (new String "hi"))` | `2` |
| `memfn` | `((memfn toUpperCase) "hi")` | `HI` |
| `proxy` | `(.toString (proxy [java.lang.Object] [] (toString [] "p")))` | `p` |