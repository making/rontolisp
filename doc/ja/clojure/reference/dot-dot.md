# ..

`(.. receiver step... name)`

`.` フォームを入れ子にします。各ステップの答えが次の receiver になります。裸の末尾の名前は読み取り、リストのもの `(m args...)` は呼び出しです。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.. "hi" (toUpperCase) (length))) ; 2
```
