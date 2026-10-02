# 正規表現

`#"..."` リテラルはパターン値に読まれます（印字は `#"..."`、`str` は素の綴りを返します）。`re-pattern` は文字列をコンパイルしてパターンを返します。パターンの比較は同一性です（オラクル通り）。`re-find`・`re-seq`・`re-matches` と `re-matcher`/`re-groups` の循環は 1 つの spliced バックトラックランタイムの上で走るため、すべてのバックエンドが同じ答えを返します。

| Name | Example | Result |
|---|---|---|
| `re-pattern` | `(re-pattern "a+")` | `#"a+"` |
| `re-find` | `(re-find #"a+" "aaab")` | `aaa` |
| `re-seq` | `(re-seq #"a+" "aaabbaa")` | `(aaa aa)` |
| `re-matcher` | `(do (def m (re-matcher #"\w+" "hi there")) (re-find m))` | `hi` |
| `re-groups` | `(do (def g (re-matcher #"(\w+)@(\w+)" "user@host")) (re-find g) (re-groups g))` | `[user@host user host]` |
| `re-matches` | `(re-matches #"a+" "aaa")` | `aaa` |

対応する構文:リテラルとエスケープ（`\w` `\W` `\s` `\S` `\d` `\D`、`.` は `\n`/`\r` を除く、`^` `$` `\b` `\B` `\A` `\z`、`\t` `\n` `\r` `\f` `\a` `\e`、`\xhh` `\uhhhh` `\cX`・8進数、`\Q..\E`、範囲と否定つきクラス、後方参照）、グループ（`(...)`・`(?:...)`）、`|` による選択、貪欲・最短・所有の `*` `+` `?` `{n,m}`。`clojure.string/split`・`replace`・`replace-first` もパターンを取ります（素の文字列は文字通りのままです）。文字列の置換は `$` グループを展開します（`$0` は全体。`re-quote-replacement` がクォートします）。関数による置換はマッチに `str` 越しに適用されます。それ以外 -- 先読み・名前付きグループ・インラインフラグ・POSIX クラス・`&&`・`\G` -- は誤った答えを返す代わりに `unsupported regex` をシグナルします。

仕様との差異:リーダーは `\\` の並びを半分にするため、`#"\\d"` は数字クラスに読まれます（オラクルはバックスラッシュと `d` の並びに読みます。クラスはバックスラッシュ 1 つで書きます）。`re-seq` は strict リストを返します（オラクルの lazy も同じ印字）。`split` は seq を返しベクターにはなりません。`.` は `\n`/`\r` のみを除き、`$` は末尾か最後の改行 1 つの直前にマッチし、`\s`/`\b` は ASCII です。置換関数は `str` 経由で描画されるため、`nil` はオラクルが投げる代わりに `""` になります。

```clojure
(println (re-find #"\d+" "abc123"))  ; 123
(println (re-seq #"a+" "aaabbaa"))  ; (aaa aa)
(println (clojure.string/split "a b  c" #"\s+"))  ; (a b c)
```
