# class

`(class x)`

値の種類をキーワードで返します。`:map`・`:vector`・`:set`・`:list`・`:string`・
`:number`・`:keyword`・`:symbol`・`:char`・`:boolean`・`:nil`・`:function`・`:atom` です
（ソート済みマップは `:map`、ソート済みセットは `:set`）。
オラクルはホストクラスを返しますが、wasm バックエンドにはないため、全バックエンド共通で
種類名のキーワードを返します。record/deftype はタグのキーワードを返します。例外と実行時エラーは
クラス名をキーワードで返します（`:java.lang.IllegalArgumentException`、`:clojure.lang.ExceptionInfo`。
実行時エラーはオラクルがその箇所で投げるクラス、クラスを示さないコンディションの拒否は
`:java.lang.RuntimeException`）。ストリーム（`*out*`・`*in*`・`*err*`、`StringWriter`、リーダー）はプリンタが示すホストクラスを
キーワードで返します（`:java.io.OutputStreamWriter`・`:java.io.StringWriter`・`:java.io.BufferedReader` など）。ホストオブジェクトは
インタプリタと JVM でホストクラスを返すため、それに対する `class` ディスパッチは `Object` か
`:default` のメソッドに届きます。例外やストリームの `.getClass` も同じキーワードを返し、
そのクラスを書いた `defmethod`・`isa?`・`derive` はクラス名をそのキーワードとして読みます。
値としては1引数ラムダです。

```clojure
(println (class "a") (class 1)) ; :string :number
(println (class *out*) (class (java.io.StringWriter.))) ; :java.io.OutputStreamWriter :java.io.StringWriter
(println (class (ex-info "m" {})) (try (+ 1 "a") (catch Exception e (class e)))) ; :clojure.lang.ExceptionInfo :java.lang.ClassCastException
```
