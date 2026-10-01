# Class/member

`(Class/member args...)` `(Class/FIELD)` と裸の `Class/member` 値

静的メソッドを呼びます。引数なし -- `(System/currentTimeMillis)` または
`(. System currentTimeMillis)` -- では、ホストクラスにあれば引数なし静的メソッド、
なければ静的フィールドを読みます（`(Integer/MAX_VALUE)` や `(. Math PI)` は
フィールドを読みます）。裸の `Class/member` 値は、ホストクラスにあれば静的
フィールドを読み、なければ静的呼び出しへアリティ毎に振り分ける関数を答えるため、
`(every? Character/isWhitespace s)` が動きます。可変長のみのメンバーは拒否されます。
オーバーロードがどれも真偽値を答える静的呼び出し・メンバー値は `true`/`false` を
答えます。クラスはドット付き・インポート済み・`java.lang` のいずれでも解決されます。
インタプリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(ns doc-static (:import (java.awt.event KeyEvent)))
(println (Integer/parseInt "42")) ; 42
(println KeyEvent/VK_LEFT) ; 37
(println (every? Character/isWhitespace "   ")) ; true
(println (> (System/currentTimeMillis) 0)) ; true
```
