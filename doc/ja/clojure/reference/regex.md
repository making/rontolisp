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

構文は `java.util.regex` のものです。エスケープとクラス（和 `[a[bc]]`、積 `[a-z&&[^aeiou]]`、`\h` `\v` `\R`、`\x{h...h}`）、グループ（捕捉、`(?:...)`、`\k<name>` で参照する名前付きの `(?<name>...)`、アトミックな `(?>...)`）、先読み `(?=...)` `(?!...)` と後読み `(?<=...)` `(?<!...)`（オラクルが求める通り最大長のあるもの）、後方参照、貪欲・最短・所有の量指定子、`\G`（マッチャーの直前のマッチの終わり）、インラインフラグ `i` `m` `s` `x` `d` `u` `U` -- `(?i)` は外側のグループの終わりまで、`(?i:...)` はその中だけで効き、`(?-i)` で外します。不正なパターンはオラクルの `PatternSyntaxException` になり、メッセージも同じです。`clojure.string/split`・`replace`・`replace-first` もパターンを取ります（素の文字列は文字通りのままです）。文字列の置換は `Matcher.appendReplacement` と同じく `$n` と `${name}` を展開します（`re-quote-replacement` がクォートします）。関数による置換はマッチに `str` 越しに適用されます。

Unicode のプロパティはオラクルと同じです。`\p{...}` と `\P{...}` は一般カテゴリ（`\p{L}`、`\pL`、`\p{Lu}`、`\p{gc=Nd}`）、用字（`\p{IsGreek}`、`\p{sc=Han}`）、ブロック（`\p{InBasicLatin}`、`\p{block=Greek}`）、二値プロパティ（`\p{IsAlphabetic}`、`\p{IsEmoji}`）、POSIX クラス（`\p{Alpha}`。`(?U)` がなければ ASCII）、`Character` のメソッド（`\p{javaLowerCase}`）を名指し、JDK の Unicode テーブルから引きます。`(?U)` の下では `\w` `\d` `\s` `\b` も Unicode のものになり、`\b` は英字か数字の後の非スペーシングマークをその単語の一部とみなします。テーブルを持つのは、パターンのどれかがプロパティ、`U` フラグ、単語境界を名指しうるプログラムか、実行時に文字列からパターンを作るプログラムだけです。

`\X` は拡張書記素クラスタに、`\b{g}` はクラスタの境界にマッチします（オラクル通り、JDK が適用する Unicode 16 の規則）。JDK から生成したテーブルを引き、テーブルを持つのは、パターンのどれかがそのどちらかを名指しうるプログラムか、実行時に文字列からパターンを作るプログラムだけです。正規表現リテラル、またはリテラル文字列の `re-pattern` の中の `\N{name}` は、`Character.codePointOf` がその名前に返す文字です。名前はプログラムのコンパイル時に引きます。

このエンジンがマッチできないものは `unsupported regex` をシグナルします。計算した文字列から作るパターンの `\N{...}` と、`(?c)` の下の文字クラスとプロパティです。

仕様との差異:リーダーはバックスラッシュをオラクル通りに verbatim に保ちます（`#"\\d"` はバックスラッシュと `d` の並びに読まれるため、クラスはバックスラッシュ 1 つで書きます）。未知の ASCII 英字のエスケープ、短い・16進でない `\u`/`\x`、8進数字を伴わない `\0`、単独の `\E` は読み取り時の refusal で、それ以外の不正なパターンはコンパイルされる時点で拒否します。`re-seq` は strict リストを返します（オラクルの lazy も同じ印字）。位置は UTF-16 の単位ではなくコードポイントで数えます。置換関数は `str` 経由で描画されるため、`nil` はオラクルが投げる代わりに `""` になります。

```clojure
(println (re-find #"\d+" "abc123"))  ; 123
(println (re-seq #"(?<=\$)\d+" "$10 20 $30"))  ; (10 30)
(println (re-find #"(?i)hello" "Say HELLO"))  ; HELLO
(println (re-seq #"\p{L}+" "héllo wörld 42"))  ; (héllo wörld)
(println (re-seq #"\G\d" "12a3"))  ; (1 2)
(println (clojure.string/replace "2026-10" #"(?<y>\d+)-(?<m>\d+)" "${m}/${y}"))  ; 10/2026
```
