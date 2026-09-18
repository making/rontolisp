# environment

`(environment import-set ...)`

Returns an environment specifier for `eval`. The import sets are checked against the libraries the front end knows, and an unknown library is an error; the result is the one global environment every specifier names, which prints as `#[environment]`, so it holds more than the named libraries.

```scheme
(environment '(scheme base)) ; => #[environment]
(eval '(if #t 'yes 'no) (environment '(scheme base))) ; => yes
```
