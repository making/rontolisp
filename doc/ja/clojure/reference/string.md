# (clojure.string)

文字列ライブラリです。alias/var、clojure.string/var、refer された裸の名前のいずれでも届き、関数値としても動きます。split/replace はリテラル文字列のみをマッチし、パターンは扱いません -- 正規表現実装がないためです。subs は核の操作で、ここに一緒に載せています。

| Name | Example | Result |
|---|---|---|
| `subs` | `(subs "hello" 1 3)` | `el` |
| `clojure.string/join` | `(clojure.string/join "," ["a" "b"])` | `a,b` |
| `clojure.string/split` | `(clojure.string/split "a,b" ",")` | `("a" "b")` |
| `clojure.string/split-lines` | `(clojure.string/split-lines "a\nb")` | `("a" "b")` |
| `clojure.string/upper-case` | `(clojure.string/upper-case "hi")` | `HI` |
| `clojure.string/lower-case` | `(clojure.string/lower-case "HI")` | `hi` |
| `clojure.string/capitalize` | `(clojure.string/capitalize "hi there")` | `Hi there` |
| `clojure.string/trim` | `(clojure.string/trim "  hi ")` | `hi` |
| `clojure.string/triml` | `(clojure.string/triml "  hi ")` | `hi ` |
| `clojure.string/trimr` | `(clojure.string/trimr "  hi ")` | `  hi` |
| `clojure.string/trim-newline` | `(clojure.string/trim-newline "hi\n")` | `hi` |
| `clojure.string/blank?` | `(clojure.string/blank? "  ")` | `true` |
| `clojure.string/starts-with?` | `(clojure.string/starts-with? "hi" "h")` | `true` |
| `clojure.string/ends-with?` | `(clojure.string/ends-with? "hi" "i")` | `true` |
| `clojure.string/includes?` | `(clojure.string/includes? "hi" "i")` | `true` |
| `clojure.string/index-of` | `(clojure.string/index-of "hi" "i")` | `1` |
| `clojure.string/last-index-of` | `(clojure.string/last-index-of "hihi" "i")` | `3` |
| `clojure.string/replace` | `(clojure.string/replace "aa" "a" "b")` | `bb` |
| `clojure.string/replace-first` | `(clojure.string/replace-first "aa" "a" "b")` | `ba` |
| `clojure.string/escape` | `(clojure.string/escape "<>" {\< "&lt;" \> "&gt;"})` | `&lt;&gt;` |
| `clojure.string/re-quote-replacement` | `(clojure.string/re-quote-replacement "$1")` | `$1` |
| `clojure.string/reverse` | `(clojure.string/reverse "abc")` | `cba` |