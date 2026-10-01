# (clojure.string)

The string library, reached as `alias/var`, `clojure.string/var`, or a referred bare var; each works as a function value too. `split`/`replace` take pattern values as well as literal strings (a plain string never compiles to a pattern). `subs` is a core operation, listed here with them.

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
