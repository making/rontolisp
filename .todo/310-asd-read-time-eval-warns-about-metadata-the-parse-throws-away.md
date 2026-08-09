# A `.asd` read-time-eval warns about metadata the parse immediately throws away

Difficulty: Medium

`(ql:quickload '(:ningle :clack))` writes 2.6 KB of stderr before it loads
anything -- seven repeats of one 370-character line, identical except for
nothing:

```console
$ echo "(ql:quickload '(:ningle :clack))" > app.lisp && rontolisp app.lisp
warning: skipping unsupported #. read-time-eval form: (WITH-OPEN-FILE (STREAM (MERGE-PATHNAMES #P"README.markdown" (OR *LOAD-PATHNAME* *COMPILE-FILE-PATHNAME*)) :IF-DOES-NOT-EXIST NIL :DIRECTION :INPUT) (WHEN STREAM (LET ((SEQ (MAKE-ARRAY (FILE-LENGTH STREAM) :ELEMENT-TYPE (QUOTE CHARACTER) :FILL-POINTER T))) (SETF (FILL-POINTER SEQ) (READ-SEQUENCE SEQ STREAM)) SEQ)))
... x7
```

Same seven on the compile path (`-o Prog.class`), since the `.asd` front end is
shared. The load itself is fine; only the noise is wrong. Counts: `ningle` 7,
`lack-request` 6, `fast-http` 4, `clack` alone 0.

## Cause

Every one of the seven is the cl-project skeleton's

```lisp
  :long-description
  #.(with-open-file (stream (merge-pathnames #p"README.markdown" ...)) ...)
```

in `myway.asd`, `http-body.asd`, `fast-http.asd`, `proc-parse.asd`,
`smart-buffer.asd`, `xsubseq.asd`, `circular-streams.asd`.

`AsdfSystems.parseAsdSource` (line 247) runs `substituteReadEval` over the WHOLE
`defsystem` form before `parseDefsystem` has looked at a single option. The mini
evaluator (`evalDataForm`, line 533 -- deny by default: literals, keywords,
earlier `defparameter`s, `quote`/`if`/`or`/`and`/`not`) cannot evaluate
`with-open-file`, so line 617 warns and substitutes nil -- and then
`parseDefsystem`'s option loop reaches `case ":LONG-DESCRIPTION" -> { }` (line
715) and discards the value. **The warning is about a value the very next
function throws away.** The eager whole-form rewrite is the bug: it evaluates
positions nothing consumes.

## What the corpus actually contains

Every `#.` in the 31 cached Quicklisp `.asd` files that have one:

| position | `.asd` files | consumed? | today |
| --- | --- | --- | --- |
| `:long-description #.(with-open-file ...)` / `#.(uiop:read-file-string ...)` | 14 | no (line 715) | warns, nil |
| `:version #.(with-open-file ...)` (cl-mustache, snooze) | 2 | no (line 715) | warns, nil |
| top-level `#.(unless (version<= "3.1" ...) (error ...))` ASDF guard | 5 | no | skipped, silent |
| `:perform` body `(intern #.(string :run-test-system) ...)` in `*-test.asd` | 7 | no (line 721) | would warn |
| `(:file #.*string-file*)` + `:depends-on (#.*string-file*)` (cl-postgres) | 1 | **yes** | resolves -- this is why `evalDataForm` exists |
| `:if-feature (#.(if (version< "3.1.8" (asdf-version)) :or :and))` (cffi-toolchain) | 1 | **yes** | warns, and the silent nil DROPS the component's file |

So the mini evaluator fails only in positions whose value is discarded -- except
one, where the failure silently removes a source file from the system. Both
halves are wrong in the same way: the decision "does this value matter" is being
made by the evaluator instead of by the consumer. (cffi-toolchain is not a live
regression: its top-level `(load-system "asdf")` already makes the whole file a
hard error.)

## The fix: resolve a `#.` where the value is CONSUMED

Move the substitution out of `parseAsdSource` and into `parseDefsystem`'s option
loop, so each option decides for itself:

- **Never resolved, never warned** -- `:name :description :long-description
  :version :author :maintainer :license :licence :homepage :bug-tracker
  :source-control :mailto` (parsed-and-ignored metadata) and `:in-order-to`
  `:perform` (tolerated test-op wiring). Nothing reads these, so nothing may
  complain about them.
- **Resolved, and unresolvable is a HARD ERROR naming the `.asd` path and the
  option** -- `:depends-on :components :serial :pathname :class
  :rontolisp-features`, and a component's `:if-feature`. This is the rule the
  rest of the file already follows ("any other option/component type is a hard
  error naming the clause"); a silent nil here drops a dependency or a source
  file and surfaces much later as an undefined symbol far from the cause.

The parameter env has to travel into `parseDefsystem` for this; its two other
callers (`LoadInliner.java:212`, `LispEvaluator.java:3006` -- a `defsystem`
written directly in a `.lisp` file) pass an empty one.

**Do not** teach `evalDataForm` `with-open-file` / `uiop:read-file-string`. A
`.asd` is parsed as data on purpose; opening a README at parse time to fill a
field no one reads is work, not correctness.

## The second warning site

`LispLexer.java:219` prints the same sentence without the datum, the file, or
the option, when a `#.` datum cannot even be re-lexed. `SKIP_UNREADABLE` mode
has exactly two callers -- `readAllSkippingReadEval` (only
`AsdfSystems.parseAsdSource`) and `readFirstForm` -- so that warning is an
`.asd`-front-end concern too, and the lexer is the one layer that cannot know
whether the position matters. It should carry the datum's raw source text into
the marker instead of warning and collapsing to `NIL`, so the same
consumer-decides rule covers it: silent in metadata, hard error quoting the raw
text in a load-bearing position.

`readFirstForm` is the amplifier to keep in mind: a package-inferred system
(`ningle` is one) opens EVERY source file just to read its leading `defpackage`,
so an unlexable `#.` early in any of those files warns once per file for a form
that is read and thrown away.

## Work

- Per-option `#.` resolution in `parseDefsystem`, with the two lists above; drop
  the whole-form `substituteReadEval` pass from `parseAsdSource` (a top-level
  marker stays ignored -- the ASDF version guard).
- Load-bearing unresolvable -> `IllegalStateException` naming the `.asd` path and
  the option, like every other unsupported clause.
- Lexer: raw-text marker instead of warn + `NIL`; delete the `System.err` line.
- Tests (`AsdfSystemsTest`): rewrite
  `unresolvableReadEvalInIgnoredMetadataDegradesToNil` to assert the parse
  succeeds AND stderr is EMPTY (capture `System.err` the way
  `NoWasiLoadPathRefusalsTest` does); add the verbatim cl-project
  `:long-description` skeleton as a case; add the hard error for an unresolvable
  `#.` in `:depends-on` and in `:components`; keep the cl-postgres
  `(:file #.*string-file*)` case green. While there,
  `parseAsdSourceSkipsAReadEvalGuardWithAWarning` is misnamed -- that path never
  warned.
- `.kb/asdf.md` ("**`#.` in a `.asd`**" -- it currently documents
  "unresolvable -> warn + nil" as the rule) and `.kb/reader-features.md`
  (tolerant mode "skips the datum with a `System.err` warning").

## Done when

`(ql:quickload '(:ningle :clack))` writes nothing to stderr, on the interpreter
and on the compile paths, and an unresolvable `#.` in a position that decides a
dependency or a source file fails the parse by name instead of quietly dropping
it.
