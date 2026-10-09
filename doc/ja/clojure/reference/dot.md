# .

`(. receiver member args...)` `(. receiver (method args...))`

`receiver` のメンバーを呼びます。メソッドシンボルならインスタンス呼び出し `(. obj m args)`、receiver がクラスなら静的呼び出し `(. Class m args)` です。引数なし -- `(. System nanoTime)` -- では、ホストクラスにあれば引数なし静的メソッド、なければフィールド読みで、`(Class/m)` と同じです。`(. obj -field)` と `(. Class FIELD)` はフィールドを読みます。Java の `false` は `false` として返ります。boolean の答えも、ホストコレクションから読んだ `Boolean.FALSE` も同じです。引数に渡した値は、Java からは本家自身のオブジェクトと同じように読めるオブジェクトとして届き、戻るときは元の値になります（違いは[仕様との差異](../deviations.md)にあります）。receiver が経路を決めます。文字列は対応する core 操作を取り、これは全バックエンドで動きます。それ以外は `java:call` へ渡り、文字列は `String`、数値はそのボックス、文字は `Character` として呼ばれます（`(.codePointAt "abc" 0)`、`(.compareTo 1 2)`）。クラスは書かれた通りにドット付きで、`:import` 経由、または `java.lang` 経由で解決されます。インタープリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

```clojure
(println (. "hi" length)) ; 2
(println (.toUpperCase "hi")) ; HI
(println (.codePointAt "abc" 0)) ; 97
(println (Integer/parseInt "42")) ; 42
(println (.isEmpty (java.util.ArrayList.))) ; true
(println (.contains (java.util.ArrayList. [1]) 2)) ; false
```
