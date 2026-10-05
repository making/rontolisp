# try

`(try expr* (catch Class e expr*)* (finally expr*)?)`

本体の式を評価し、最後の値を返します。`try` は `unwind-protect` の中の `handler-case` を守ります。`finally` の本体は出口で走り、結果はそれを乗り越えて残ります。catch 節は、自分が名指すクラスかそのサブクラスの例外を捕捉します。節は順に試され、例外を捕捉した最初の節がその例外を変数に束縛して走ります。どの節も捕捉しない例外は `finally` を経て外側のハンドラへ進みます（オラクルと同じです）。クラスはドット付きの名前、import した名前、`java.lang` の既定 import のいずれかで解決し、`Throwable` でなければなりません。それ以外はオラクルと同じく拒否します（`Unable to resolve classname: Foo`）。

実行時エラーはランタイムがシグナルする Common Lisp のコンディションで、catch はオラクルがその箇所で投げるクラスとして捕捉します。型エラーは `ClassCastException`（`nil` なら `NullPointerException`、範囲外の添字なら `IndexOutOfBoundsException`、コレクションでない値の `count` なら `UnsupportedOperationException`）、算術エラーは `ArithmeticException`、引数の個数の誤りは `clojure.lang.ArityException`、ファイルを開けなければ `java.io.FileNotFoundException` です。ランタイムの拒否は、同じ呼び出しでオラクルが投げるクラスとして捕捉します（`(first 5)` は `IllegalArgumentException`、失敗した `assert` は `AssertionError`）。クラスを示さないエラーは、`clojure.lang.ExceptionInfo` 以外のどの catch も捕捉します（[仕様との差異](../deviations.md)）。インタプリタと JVM では、Java のメンバが投げた例外はオラクルと同じくホスト自身のもので、catch はそのクラスで捕捉し、そのオブジェクトを束縛します。捕捉した例外は `ex-message`/`ex-data`/`ex-cause`、`.getMessage`/`.getCause`、`str` で読めます。

```clojure
(println (try 1 (catch Exception e 2) (finally nil))) ; 1
(println (try (throw (IllegalStateException. "bad")) (catch IllegalArgumentException e :iae) (catch IllegalStateException e :ise))) ; :ise
(println (try (+ 1 "a") (catch ArithmeticException e :arith) (catch ClassCastException e :cce))) ; :cce
(println (try (try (throw (ex-info "m" {})) (catch IllegalArgumentException e :iae)) (catch clojure.lang.ExceptionInfo e (ex-message e)))) ; m
(println (try (Integer/parseInt "x") (catch NumberFormatException e (.getMessage e)))) ; For input string: "x"
```
