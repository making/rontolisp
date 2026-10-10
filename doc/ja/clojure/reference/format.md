# format

`(format fmt args...)`

書式文字列を `java.util.Formatter` と同じく描画して返します。`%s`・`%S` は `str` 同様に
変換し、`%b`・`%B` は真偽値綴り、`%c`・`%C` は文字、`%d`・`%o`・`%x`・`%X` は整数（負の
long の 8 進・16 進は 2 の補数）、`%e`・`%E`・`%f`・`%g`・`%G` は double（表示される最短の
10 進表記から四捨五入）、`%%` と `%n` はそれ自体です。フラグ（`-` `#` `+` 空白 `0` `,`
`(`）・幅・精度・引数番号（`%2$s`、`%<s`）はすべてオラクルと同じで、`nil` はどの変換でも
`null` と綴ります。

不正な書式文字列、変換が受け付けないフラグ、足りない引数や型の合わない引数は、オラクルと
同じ例外（`MissingFormatWidthException`、`IllegalFormatConversionException` など）を同じ
メッセージで、引数を評価した後に送出します。書式文字列はリテラル必須で、`%h`・`%a`・日時の
`%t` はプログラムの読み込み時に拒否されます。

```clojure
(println (format "%s=%d" :a 5)) ; :a=5
(println (format "%5s|%.2f" "ab" 3.14159)) ;    ab|3.14
(println (format "%%x%04x|%,d|%+.1e" 10 1234567 -0.5)) ; %x000a|1,234,567|-5.0e-01
(println (format "%2$s %1$s %<s" "a" "b")) ; b a a
```
