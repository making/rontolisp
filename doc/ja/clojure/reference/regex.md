# 正規表現

`#"..."` リテラルはパターン値に読まれます（印字は `#"..."`、`str` は素の綴りを返します）。`re-pattern` は文字列をコンパイルしてパターンを返します。パターンの比較は同一性です（オラクル通り）。`re-find`・`re-seq`・`re-matches` と `re-matcher`/`re-groups` の循環は、`java.util.regex` のパーサーとマッチャーを移植した 1 つの spliced エンジンの上で走るため、すべてのバックエンドがオラクルと同じ答えを返します。

| Name | Example | Result |
|---|---|---|
| `re-pattern` | `(re-pattern "a+")` | `#"a+"` |
| `re-find` | `(re-find #"a+" "aaab")` | `aaa` |
| `re-seq` | `(re-seq #"a+" "aaabbaa")` | `(aaa aa)` |
| `re-matcher` | `(do (def m (re-matcher #"\w+" "hi there")) (re-find m))` | `hi` |
| `re-groups` | `(do (def g (re-matcher #"(\w+)@(\w+)" "user@host")) (re-find g) (re-groups g))` | `[user@host user host]` |
| `re-matches` | `(re-matches #"a+" "aaa")` | `aaa` |

構文は `java.util.regex` のものです。エスケープとクラス（和 `[a[bc]]`、積 `[a-z&&[^aeiou]]`、`\h` `\v` `\R`、`\x{h...h}`）、グループ（捕捉、`(?:...)`、`\k<name>` で参照する名前付きの `(?<name>...)`、アトミックな `(?>...)`）、先読み `(?=...)` `(?!...)` と後読み `(?<=...)` `(?<!...)`（オラクルが求める通り最大長のあるもの）、後方参照、貪欲・最短・所有の量指定子、インラインフラグ `i` `m` `s` `x` `d` `u` -- `(?i)` は外側のグループの終わりまで、`(?i:...)` はその中だけで効き、`(?-i)` で外します。不正なパターンはオラクルの `PatternSyntaxException` になり、メッセージも同じです。`clojure.string/split`・`replace`・`replace-first` もパターンを取ります（素の文字列は文字通りのままです）。文字列の置換は `Matcher.appendReplacement` と同じく `$n` と `${name}` を展開します（`re-quote-replacement` がクォートします）。関数による置換はマッチに `str` 越しに適用されます。

このエンジンがマッチできないものは `unsupported regex` をシグナルします。`\p{...}` と `\P{...}`、`\N{...}`、`\X`、`\b{g}`、`\G`、`(?U)` の下の `\d` `\s` `\w` `\b`、`(?c)` の下の文字クラスです。

仕様との差異:リーダーはバックスラッシュをオラクル通りに verbatim に保ちます（`#"\\d"` はバックスラッシュと `d` の並びに読まれるため、クラスはバックスラッシュ 1 つで書きます）。未知の ASCII 英字のエスケープ、短い・16進でない `\u`/`\x`、8進数字を伴わない `\0`、単独の `\E` は読み取り時の refusal で、それ以外の不正なパターンはコンパイルされる時点で拒否します。`re-seq` は strict リストを返します（オラクルの lazy も同じ印字）。位置は UTF-16 の単位ではなくコードポイントで数えます。文字の後の非スペーシングマークは `\b` の単語文字ではありません。置換関数は `str` 経由で描画されるため、`nil` はオラクルが投げる代わりに `""` になります。

```clojure
(println (re-find #"\d+" "abc123"))  ; 123
(println (re-seq #"(?<=\$)\d+" "$10 20 $30"))  ; (10 30)
(println (re-find #"(?i)hello" "Say HELLO"))  ; HELLO
(println (clojure.string/replace "2026-10" #"(?<y>\d+)-(?<m>\d+)" "${m}/${y}"))  ; 10/2026
```
