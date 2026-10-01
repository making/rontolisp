# new

`(new Class args...)`

ホストオブジェクトを構築します。`(Class. args)` の接尾辞の綴りは同じ操作です。インスタンスはほかのどの interop 動詞にも応えます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (.length (new String "hi"))) ; 2
```
