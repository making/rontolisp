# (scheme r5rs)

R7RS が `(scheme r5rs)` にだけ残している R5RS の名前です。このライブラリはここでは import できません。これらの名前は `import` のないファイルと REPL からだけ見え、[`--scheme-standard r7rs`](../standards.md) ではどこからも見えません。

| 名前 | 例 | 結果 |
|---|---|---|
| `exact->inexact` | `(exact->inexact 1/4)` | `0.25` |
| `inexact->exact` | `(inexact->exact 0.25)` | `1/4` |
| `scheme-report-environment` | `(scheme-report-environment 5)` | `#[environment]` |
