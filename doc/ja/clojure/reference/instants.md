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

| 名前 | 例 | 結果 |
|---|---|---|
| `inst-ms` | `(inst-ms #inst "1970-01-01T00:00:01Z")` | `1000` |
| `random-uuid` | `(uuid? (random-uuid))` | `true` |
| `parse-uuid` | `(parse-uuid "1-1-1-1-1")` | `#uuid "00000001-0001-0001-0001-000000000001"` |

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
- Timestamp は `=`、`compare`、マルチメソッドのディスパッチをオラクルと同じように行いますが、
  `java.util.Date` に拡張したプロトコルは Timestamp に届きません。`java.sql.Timestamp` も
  拡張してください。
- UUID の 16 進数字は ASCII の数字と英字に限ります。オラクルの `UUID.fromString` は Unicode の
  10 進数字と全角ラテン文字も受け付けます。
- interop で得たホストの `java.util.Date`、`java.time.Instant`、`java.util.UUID`
  （インタプリタと JVM）はホストオブジェクトのままです。`inst?`、`uuid?`、`inst-ms` は受け付けますが、
  プログラムが読んだインスタントや UUID と `=` になることはありません。
