# Class/member

`(Class/member args...)` `(Class/FIELD)` `(Class/.method target args...)` `(Class/new args...)`
と裸の `Class/member` 値

静的メソッドを呼びます。引数なし -- `(System/nanoTime)` または
`(. System nanoTime)` -- では、ホストクラスにあれば引数なし静的メソッド、
なければ静的フィールドを読みます（`(Integer/MAX_VALUE)` や `(. Math PI)` は
フィールドを読みます）。裸の `Class/member` 値は、ホストクラスにあれば静的
フィールドを読み、なければ静的呼び出しへアリティ毎に振り分ける関数を答えるため、
`(every? Character/isWhitespace s)` が動きます。可変長のみのメンバーは拒否されます。
オーバーロードがどれも真偽値を答える静的呼び出し・メンバー値は `true`/`false` を
答えます。リテラルなキーからの `(System/getProperty k)` は nil、
`(System/getProperty k dflt)` は既定値を答えます（wasm バックエンド。プロパティは
存在しません。nil はオラクルの `NullPointerException` です）。計算されたキーは
ホストの呼び出しのまま残ります。`Long/MAX_VALUE`（および `MIN_VALUE`、`Integer`/`Short`/`Byte`
の組）は wasm バックエンドではその値自身になります（ホストのフィールドは存在しないため）。
それ以外のメンバーはホストのフィールドのまま残ります。1 引数の `Long/valueOf`・`Double/valueOf`・
`Long/parseLong`、文字列とリテラルな基数 16 からの `Integer/parseInt`、1 引数の
`Integer/toHexString`、2 引数の `Math/min` は wasm バックエンドでも移植性のある読み取りとして
評価され、オラクルの文法と拒否に従います。その他のアリティはホストの呼び出しのまま残ります。
クラスはドット付き・インポート済み・`java.lang` のいずれでも解決されます。
ロード可能なクラス名単体（`String`）はクラスオブジェクトで、
`(Class/forName "java.lang.String")` と等しく、文字列への `.getClass` も同じものを答えます。
wasm バックエンドでは代わりに対応する `(:C%CLASS name)` 指示子になるため、クラスをキーとする
マップはロード時に構築でき、同じクラスの2つのリテラルはやはり等しくなります。
`DateTimeFormatterBuilder` の連鎖は wasm バックエンドでは評価されます（各ビルダー呼び出しは
マーカーを受け渡し、`toFormatter` はフォーマッター、`ofPattern` と `ZoneId/of` はそれぞれの
マーカーになります）。ただしそれでの構文解析や書式化は拒否されます。
クラスオブジェクトはオラクルと同じくクラス名（`java.lang.String`）で表示され、
`str` はその `toString`（`class java.lang.String`）を答えます。
インタプリターと JVM でのみ動作し、wasm バックエンドは `java:` を拒否します。

`Class/.method` はインスタンスメソッドです。呼び出し位置では最初の引数が対象で、
`(.method target args...)` と同じです。値としては対象を最初に取る関数で、
そのクラスの public なインスタンスメソッドのアリティ毎に振り分けます（該当する
メソッドがない名前はプログラムを読む時点で拒否されます）。`Class/new` は
コンストラクタで、呼び出し位置でも値でも `(Class. args...)` と同じです。レコードと
deftype の `R/new` は位置引数のコンストラクタです。これらの前に `^[types]` の
パラメータタグを付けるとオーバーロードを指定でき、値はちょうどその個数（と対象）の
引数を取ります。タグはクラス名、プリミティブ、プリミティブ配列と `Object` 配列を表す
`ints`/`longs`/... と `objects`、`N` 次元配列を表す `T/N`、任意の型を表す `_` の
いずれかです。タグ付き呼び出しの引数の個数がタグと違うと、プログラムを読む時点で
拒否されます。

```clojure
(ns doc-static (:import (java.awt.event KeyEvent)))
(println (Integer/parseInt "42")) ; 42
(println KeyEvent/VK_LEFT) ; 37
(println (every? Character/isWhitespace "   ")) ; true
(println (> (System/currentTimeMillis) 0)) ; true
(println (System/getProperty "no.such.property" "dflt")) ; dflt
(println Long/MAX_VALUE Long/MIN_VALUE) ; 9223372036854775807 -9223372036854775808
(println (Long/parseLong "12")) ; 12
(println (= String String)) ; true
(println (= String (.getClass "s"))) ; true
(println (.getName String)) ; java.lang.String
(println String (str String)) ; java.lang.String class java.lang.String
```

```clojure
(ns doc-qualified (:import (java.util ArrayList)))
(println (String/.toUpperCase "abc")) ; ABC
(println (map String/.length ["ab" "abcd"])) ; (2 4)
(println (String/new "q")) ; q
(let [a (ArrayList/new)] (.add a 1) (println (ArrayList/.size a))) ; 1
(println (map ^[double] Math/abs [-1 2])) ; (1.0 2.0)
(println (map ^[int] String/.charAt ["ab"] [1])) ; (b)
```
