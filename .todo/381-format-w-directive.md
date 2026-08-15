# `format` `~W`

Difficulty: Low

Part of `.todo/372` (rove). Rove renders every assertion description with it:

```lisp
(format nil "Expect ~W to be ~:[true~;false~]." `(,function ,@args) negative)
;; SBCL:      "Expect (= (ADD 1 2) 3) to be true."
;; rontolisp: "Expect ~W to be false."
```

`~W` is unknown to both renderings of the directive set (`.kb/format.md`: the
compile-time expansion of a literal control AND the Lisp-source runtime
renderer `format-render.lisp`), so it is emitted literally, consumes no
argument, and the following `~:[` eats the form -- every rove description is
wrong twice. Verified on the interpreter and the JVM, literal and `defvar`-held
controls.

`~W` = `write` of the argument under the current printer variables (`~:W` binds
`*print-pretty*` t, `~@W` binds `*print-level*`/`*print-length*` nil; here
`write` is `prin1` plus the printer-control table of `.kb/pretty-printer.md`,
so both modifiers lower to the same call until those variables do more). Add
it to both renderings, next to `~S`; `.todo/001` is the directive backlog --
mark it there.

Acceptance: literal + runtime control on all four backends (`ci-spec.yaml`
format case, the three backend suites), `format-render.lisp`, the `format`
doc page's directive table, `.kb/format.md` directive list.
