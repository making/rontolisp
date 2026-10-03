# .

`(. receiver member args...)` `(. receiver (method args...))`

`receiver` のメンバーを呼びます。メソッドシンボルならインスタンス呼び出し `(. obj m args)`、receiver がクラスなら静的呼び出し `(. Class m args)` です。引数なし -- `(. System currentTimeMillis)` -- では、ホストクラスにあれば引数なし静的メソッド、なければフィールド読みで、`(Class/m)` と同じです。`(. obj -field)` と `(. Class FIELD)` はフィールドを読みます。receiver が構築リテラル -- `(.isEmpty (java.util.ArrayList.))` -- またはそれを束縛した `let`/`if-let`/`when-let` ローカル、または `..` ステップの宣言戻り値型で、その引数個数のオーバーロードがすべてプリミティブ boolean を答えるインスタンス呼び出しは、オラクル同様 `true`/`false` を答えます。receiver が文字列・数値・文字で、そのクラスのその引数個数のオーバーロードがすべてプリミティブ boolean を答える呼び出しも同じです。それ以外のホスト boolean は共有の `java:` unmarshal のままとなり、`false` は `nil` と表示されます。receiver が経路を決めます。文字列は対応する core 操作を取り、これは全バックエンドで動きます。それ以外は `java:call` へ渡り、文字列は `String`、数値はそのボックス、文字は `Character` として呼ばれます（`(.codePointAt "abc" 0)`、`(.compareTo 1 2)`）。クラスは書かれた通りにドット付きで、`:import` 経由、または `java.lang` 経由で解決されます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (. "hi" length)) ; 2
(println (.toUpperCase "hi")) ; HI
(println (.codePointAt "abc" 0)) ; 97
(println (Integer/parseInt "42")) ; 42
(println (.isEmpty (java.util.ArrayList.))) ; true
(println (.contains (java.util.ArrayList. [1]) 2)) ; false
```
