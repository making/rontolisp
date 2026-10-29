# インスタントと UUID

[`#inst` と `#uuid`](../syntax.md#tagged-literals) が読まれた先の値で、すべてのバックエンドで
同じように動きます。インスタントはオラクルの `java.util.Date` で、1970-01-01T00:00:00Z からの
ミリ秒を保持します。`print` でも `pr` でも UTC の `#inst "..."` と印字され、`str` はオラクルの
`toString` を UTC で返します（`Wed Jan 01 00:00:00 UTC 2020`）。UUID はオラクルの
`java.util.UUID` で、`#uuid "..."` と印字され、`str` は小文字の綴りを返します。同じ種類の 2 つの
値は、オラクルで `=` になるときに `=` です。`compare` はオラクルと同じ順に並べ（UUID は 2 つの
半分を符号付き long として比べます）、マップやセットは `=` でキーを見つけます。どちらも
コレクションでも関数でもありません。`class` は `:java.util.Date` と `:java.util.UUID` を返し、
`class` で振り分けるマルチメソッドとプロトコルはこれらのクラスでディスパッチします。
種類の判定は [inst?](inst-p.md) と [uuid?](uuid-p.md) が行い、
[clojure.instant](clojure-instant.md) はタイムスタンプを `java.sql.Timestamp` や
`java.util.Calendar` にも読みます。

interop の書き方も、すべてのバックエンドで同じ値を作ります。`(java.util.Date.)` は現在の
インスタント、`(java.util.Date. ms)` と `(java.sql.Timestamp. ms)` はそのミリ秒のインスタント、
`(java.util.UUID. msb lsb)` は 2 つの半分からなる UUID です。`UUID/randomUUID` と
`UUID/fromString` は [random-uuid](random-uuid.md) と `#uuid` と同じ値を返し、
`System/currentTimeMillis` はエポックからのミリ秒を返します。Date の `getTime`、`setTime`
（その場で書き換えます）、`before`、`after`、`compareTo`、Timestamp の `getNanos` と
`setNanos`（その場で書き換えます）、UUID の 2 つの半分と `version`、`variant` はどこでも
答えます。Calendar の `set`（1 つのフィールド、または年・月・日と省略可能な時刻）、
`setTimeZone`、`get`、`getTime`（Date を返します）、`getTimeInMillis` もどこでも答え、
Calendar をその場で書き換えます。インタプリタと JVM では、こうした値は
Java のメンバーにホストオブジェクトとして渡り（`(.format sdf (java.util.Date.))`）、
そのクラスのほかのメソッドはホストオブジェクトが答えます（`(.toInstant d)`）。メンバーが
返したホストの Date や UUID は、こうした値と `=` になり、並べて比較できます。wasm
バックエンドでは、Date や Timestamp の `.toInstant` はインスタント自身を返すため、下の
持ち運べる `ISO_INSTANT` フォーマッタがそのまま綴ります
（`(.format java.time.format.DateTimeFormatter/ISO_INSTANT (.toInstant (java.util.Date. 0)))`
は `"1970-01-01T00:00:00Z"` です）。ホストだけが答えるそれ以外のメソッドは、wasm では
名前を挙げて拒否します。

wasm バックエンドでは、`(java.util.GregorianCalendar. year month day)`（引数 5 つや 6 つ、
または引数なしで現在のインスタント）はこの種類の Calendar、`java.util.Calendar` の定数
（`Calendar/MILLISECOND` など）はその整数、`GMT`・`UTC`・`GMT+hh:mm` のオフセットに対する
`(java.util.TimeZone/getTimeZone id)` は Calendar が受け取るゾーンになるため、tools.reader の
`#inst` リーダーがそこで動きます。暦はオラクルの寛容な暦です。範囲外のフィールドは繰り上がり、
1582-10-15 より前の日付はユリウス暦で、設定してまだ計算していないフィールドは最後に設定した
ゾーンで読みます。インタプリタと JVM では、これらはホストのクラスのままです。

wasm バックエンドでは、`java.time.format.DateTimeFormatter/ISO_INSTANT` は持ち運べる値です
（ホストに `java.time` がないため）。`.format` は Date や Timestamp を ISO-8601 で答えるので、
`(.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. 0))` は
`"1970-01-01T00:00:00Z"` になります。インタプリタと JVM では、このフィールドはホストの
オブジェクトのままで、`.format` もホストの呼び出しです。

| 名前 | 例 | 結果 |
|---|---|---|
| `inst-ms` | `(inst-ms #inst "1970-01-01T00:00:01Z")` | `1000` |
| `random-uuid` | `(uuid? (random-uuid))` | `true` |
| `parse-uuid` | `(parse-uuid "1-1-1-1-1")` | `#uuid "00000001-0001-0001-0001-000000000001"` |
| `java.util.Date.` | `(java.util.Date. 1000)` | `#inst "1970-01-01T00:00:01.000-00:00"` |
| `java.util.UUID/fromString` | `(= (java.util.UUID/fromString "1-1-1-1-1") #uuid "1-1-1-1-1")` | `true` |
| `System/currentTimeMillis` | `(integer? (System/currentTimeMillis))` | `true` |

```clojure
(def at #inst "2020-06-15T10:20:30.456+02:00")
(def id #uuid "550e8400-e29b-41d4-a716-446655440000")
(println at (str at))
(println (= at #inst "2020-06-15T08:20:30.456Z") (sort [#inst "2021" at #inst "1999"]))
(println id (get {id :found} (parse-uuid (str id))))
```

```
#inst "2020-06-15T08:20:30.456-00:00" Mon Jun 15 08:20:30 UTC 2020
true (#inst "1999-01-01T00:00:00.000-00:00" #inst "2020-06-15T08:20:30.456-00:00" #inst "2021-01-01T00:00:00.000-00:00")
#uuid "550e8400-e29b-41d4-a716-446655440000" :found
```

## 違い

- `class` は、ほかの値と同じくキーワードを返します（[仕様との差異](../deviations.md)）。
- インスタントの `str` は、タイムゾーンを UTC としたオラクルの `toString` です。オラクルは
  JVM の既定のタイムゾーンで答えますが、wasm バックエンドにはタイムゾーンがありません。
- UUID の 16 進数字は ASCII の数字と英字に限ります。オラクルの `UUID.fromString` は Unicode の
  10 進数字と全角ラテン文字も受け付けます。
- Java のメンバーが返したホストの `java.util.Date`、`java.sql.Timestamp`、`java.util.UUID`
  （インタプリタと JVM）はホストオブジェクトのままです。ここで作った値とは、先に置いたほうの
  `equals` が決めるとおりに `=` になり、`compareTo` で並べて比較でき、`inst?`、`uuid?`、
  `inst-ms` も受け付けます（`java.time.Instant` も同様です）。ただし `#<java java.util.Date>` と
  印字され、マップのキーやセットの要素としては、ここで作った値とは別のものになります。
- 実行時に計算した文字列を渡す `(java.util.Date. s)` は拒否します。オラクルが行う非推奨の
  `Date(String)` による解析はありません（リテラルの文字列なら、インタプリタと JVM では従来どおり
  ホストのコンストラクタに届きます）。Date の `setTime` 以外の変更メソッド（`setYear` など）は
  名前を挙げて拒否します。オラクルの Date はこれらで書き換わります。
- [clojure.instant](clojure-instant.md) の Calendar にはホストオブジェクトがありません。Java の
  メンバーには渡らず、そのクラスのメソッドは上に挙げたもの以外を名前を挙げて拒否します。wasm
  では、Date や UUID のメソッドのうちホストだけが答えるその他のもの（たとえば `getYear` や
  `hashCode`）も同じく拒否します。
- wasm バックエンドでは、`GregorianCalendar` の構築はゾーンを設定するまで UTC です（オラクルは
  JVM の既定のゾーンです）。規則を持つゾーン（`(TimeZone/getTimeZone "America/New_York")`）は
  名前を挙げて拒否します。オラクルは知らない ID に `GMT` を返します。`set` は `ERA`、`YEAR`、
  `MONTH`、`DAY_OF_MONTH`、`HOUR_OF_DAY`、`MINUTE`、`SECOND`、`MILLISECOND` を、`get` はさらに
  `DAY_OF_WEEK`、`AM_PM`、`HOUR`、`ZONE_OFFSET`、`DST_OFFSET` を受け取り、それ以外のフィールドは
  名前を挙げて拒否します。ゾーンはリストとして印字され、`java.util.TimeZone` ではありません。
  フィールドをまだ計算していない Calendar を印字しても計算は保留されたままなので、後の
  `setTimeZone` はそれを新しいゾーンで読みます。オラクルの印字はフィールドを計算するため、
  その後のゾーン設定はインスタントを保ちます。
- wasm バックエンドでは、`java.time.format.DateTimeFormatter/ISO_INSTANT` は持ち運べる値です
  （ホストに `java.time` がないため）。`.format` は Date や Timestamp を ISO-8601 で答えるので、
  `(.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.util.Date. 0))` は
  `"1970-01-01T00:00:00Z"` です。`nil` はオラクルの `NullPointerException`、それ以外は
  `TemporalAccessor` への `ClassCastException` です。この値は `(:C%DATE-FORMATTER)` と印字され、
  `class` は `:list` を返します。オラクルは Date の `.format` を拒否します（Date は
  `TemporalAccessor` ではないため）。インタプリタと JVM では、このフィールドはホストの
  オブジェクトのままで、`.format` もホストの呼び出しです。
