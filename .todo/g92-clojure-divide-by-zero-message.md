# Clojure: `(/ 1 0)` message is `Division by zero`, the oracle's `Divide by zero`

Difficulty: Low

`(try (/ 1 0) (catch ArithmeticException e (ex-message e)))` answers `"Division by zero"`
(the CL condition's report, `.kb/clojure-frontend.md` "Exceptions"); clj and cljw 1.15.4
answer `"Divide by zero"`. Map the CL `division-by-zero` report to the oracle's text on all
four backends and pin it in `clojure-spec.yaml`.
