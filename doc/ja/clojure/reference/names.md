# 名前とキーワード

キーワードはコロンの後ろに名前をそのまま綴ります（`:a/b` は全体のままです --
`name`/`namespace` でのみ分割されます）。シンボルは `c%` 接頭辞の裏で mangle
されるため、demangle して表示され、全体で比較されます。`name` は最初の `/` より
後ろの部分、`namespace` は前の部分を読みます。

| Name | Example | Result |
|---|---|---|
| `name` | `(name :foo/bar)` | `bar` |
| `namespace` | `(namespace :foo/bar)` | `foo` |
| `keyword` | `(keyword "a" "b")` | `:a/b` |
| `symbol` | `(symbol "a" "b")` | `a/b` |
