# clojure.instant

RFC 3339 のタイムスタンプをインスタントに読む名前空間です。Clojure と同じくプログラムより前に
ロードされるので、`clojure.instant/read-instant-date` は `require` なしで使えます。Clojure の
同名の名前空間について文書化された振る舞いをもとに rontolisp 向けに書いた Clojure ソースで、
すべてのバックエンドで同じように動きます。`#inst` は `read-instant-date` を通して読まれます
（[構文](../syntax.md#tagged-literals)）。ただし `inst` のデータリーダ（`data_readers.clj` が
対応づける、あるいは `read-string` の周りで `*data-readers*` に束縛した、ほかのリーダ）が
あれば、そちらが先に読みます。

| var | 振る舞い |
|---|---|
| `parse-timestamp` | `(parse-timestamp new-instant cs)`: タイムスタンプ `cs` を照合し、年・月・日・時・分・秒・ナノ秒・オフセットの符号（-1、0、1）・オフセットの時・オフセットの分の 10 個の整数で `new-instant` を呼ぶ。省いた月と日は 1、それ以外の省いた数は 0 で、小数部は先頭 9 桁を数える。照合できなければオラクルと同じ `Unrecognized date/time syntax` |
| `validated` | `(validated new-instance)`: 10 個の整数の範囲をオラクルと同じ順に検査してから `new-instance` を呼ぶ関数。最初に失敗した検査をオラクルの文言（`failed: (<= 1 months 12)`）で拒否する |
| `read-instant-date` | `(read-instant-date cs)`: タイムスタンプの `java.util.Date`。オフセットは UTC に繰り込む |
| `read-instant-timestamp` | `(read-instant-timestamp cs)`: タイムスタンプの `java.sql.Timestamp`。小数部の 9 桁をすべて保持する |
| `read-instant-calendar` | `(read-instant-calendar cs)`: タイムスタンプの `java.util.Calendar`。オフセットを保持する |

```clojure
(require '[clojure.instant :as inst])
(prn (inst/parse-timestamp vector "2020-01-01T10:20:30.5+05:30"))
(prn (inst/read-instant-timestamp "2020-01-01T10:20:30.123456789Z"))
(prn (inst/read-instant-calendar "2020-01-01T10:20:30+05:30"))
(println (try ((inst/validated vector) 2021 2 29 0 0 0 0 0 0 0) (catch RuntimeException e (ex-message e))))
```

```
[2020 1 1 10 20 30 500000000 1 5 30]
#inst "2020-01-01T10:20:30.123456789-00:00"
#inst "2020-01-01T10:20:30.000+05:30"
failed: (<= 1 days (days-in-month months (leap-year? years)))
```

Timestamp は Date と同じくインスタントで、`inst?` と `inst-ms` が受け付け、`str` はオラクルと
同じ値（`2020-01-01 10:20:30.123456789`）を返します。オラクルと同じく、Date はミリ秒の等しい
Timestamp と `=` ですが、Timestamp が Date と `=` になることはありません。Calendar は `inst?` では
ありません。2 つの Calendar は、インスタントとタイムゾーンがともに等しいときに `=` で
（`-00:00` は `Z` とは別のタイムゾーンです）、`compare` はインスタントの順に並べます。

## 違い

- Calendar の `str` は印字される `#inst` を返します。オラクルの `str` はカレンダーの
  フィールドを書き出します。
