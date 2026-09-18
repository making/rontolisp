# (scheme case-lambda)

A procedure that takes a different body for each number of arguments.

| Name | Example | Result |
|---|---|---|
| `case-lambda` | `((case-lambda ((x) (list x)) ((x y) (+ x y))) 1 2)` | `3` |
