# Class/member

`(Class/member args...)` `(Class/FIELD)`

静的メソッドを呼びます。引数なしの位置では静的フィールドを読みます。引数なしの静的メソッドは `(. Class m)` と書きます。裸の形式はフィールドを読むためです。クラスはドット付き・インポート済み・`java.lang` のいずれでも解決されます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (Integer/parseInt "42")) ; 42
```
