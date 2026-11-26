# parse-boolean

`(parse-boolean s)`

Parses the strings `"true"` and `"false"` and answers a boolean, or `nil` when the
string is anything else. A non-string is the oracle's `IllegalArgumentException`
(`Expected string, got ...`). Runs on every backend; as a value a one-argument
function.

```clojure
(println (parse-boolean "true") (parse-boolean "false") (parse-boolean "yes")) ; true false nil
(println (map parse-boolean ["true" "xx"])) ; (true nil)
```
