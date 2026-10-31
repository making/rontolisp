# h88. `define-condition` answers the class object instead of its name

Difficulty: Low

Regression from `2dc41e6ad` (`defclass` now answers the class object). `define-condition` must
still answer the NAME (CLHS; SBCL). `./mvnw test` at `fbae24086` fails `DocExamplesTest` on
`doc/{en,ja}/reference/macros/define-condition.md`:

```lisp
(define-condition my-parse-error (error)
  ((input :initarg :input :reader my-parse-error-input))
  (:report "input did not parse")) ; => MY-PARSE-ERROR
```

Interpreter answers `#<STANDARD-CLASS :NAME MY-PARSE-ERROR ...>`. Check the compile paths too, and
pin the value on all four backends (extend the `defclass-answers-the-class-object` ci-spec case).
