# `rontolisp format` keeps a deep alignment when the argument that overflows is excluded from the vote

Difficulty: Medium

Two syntactically identical calls in one function get different layouts, and the deeper
one overruns the margin by 15 columns where the shallower one does not.
`examples/wasmcloud/service-tcp/http-api.lisp`:

```lisp
              (text-response 400
                             (format nil
                                     "expected a JSON object with a string payload field~%"))))
        (text-response 400
         (format nil "expected a JSON object with a string payload field~%")))))
```

The second is the layout the first wants: forcing it gives a 74-column line where the
checked-in one is 95.

## Why it happens

`LispFormatter.argumentColumn` picks between `aligned` (under the first argument) and
`shallow` (`indent + 1`). It walks the arguments, measures each, and takes `shallow` only
if the widest argument that **fits at `shallow`** would overrun at `aligned`. An argument
that fits at neither column is skipped, on the stated ground that it "has to wrap
whichever column it starts in, so it cannot speak to which column the others should get".

That skip decides these two sites, and it decides them opposite ways:

| site | `indent` | `aligned` | `shallow` | `(format nil "…")` at `shallow` | voters | result |
| --- | --- | --- | --- | --- | --- | --- |
| outer | 8 | 23 | 9 | `9 + 67 + 4 = 80` -- fits, by one column | `400` (3), `format` (71) | `23 + 71 = 94 > 80` -> `shallow` |
| inner | 14 | 29 | 15 | `15 + 67 + 4 = 86` -- does not fit | `400` (3) only | `29 + 3 = 32` -> `aligned` |

(67 is the `format` call's flat width, 4 the closing parens charged to it.)

So at the inner site the column is chosen by `400`, and the argument that actually
overflows has no say. Depth is not the cause: shortening the string by six characters --
which changes nothing about the nesting, only whether `format` fits at `shallow` and can
therefore vote -- makes the inner site adopt the outer site's layout.

```lisp
              (text-response 400
               (format nil "expected a JSON object with a string payload~%"))))
        (text-response 400
         (format nil "expected a JSON object with a string payload~%")))))
```

## The fix

The skip is right for its stated purpose -- choosing between competing alignments for the
arguments that DO fit -- and wrong as a reason to keep `aligned` when an argument fits
nowhere. A wrapping argument started at a shallower column has strictly more room, so its
internal layout is never worse and usually shorter. Track whether some argument fit at
neither column and prefer `shallow` when one did, keeping the existing `shallow < aligned`
guard.

Note this is already the rule for a call with exactly ONE argument (`items.size() == 2`
returns `shallow` whenever the argument does not fit at `aligned`, fitting or not). The
change makes the many-argument path agree with the one-argument path it currently
contradicts.

## What has to be measured before it lands

Every call whose last argument is a long unbreakable string moves left -- `(format t "~a~%"
x)` with a long control string is the common shape -- so this is an aggregate change, not a
local one. Re-take the measurement `.kb/formatter.md` asks for ("output lines over the
margin that are neither a comment nor a trailing comment, compared against the same count
in the input") over the corpus `LispFormatterTest.repositoryLispSources` walks, and require
it to go DOWN:

```
files=377  code lines over 80: source=1375 formatted=480  files-made-worse=7
```

`formatted=480` is the number to beat; it is the "481" recorded in `.kb/formatter.md`,
re-taken on the tree as of `cbb6509a`. The `source` column moved from the recorded 1111
because `examples/` and `src/main/resources/` are formatted now, so it no longer measures
the same input -- only the `formatted` column is comparable across sessions.

## Non-goals

The margin itself. A line whose content cannot be broken still exceeds it, and
`LispFormatterTest` deliberately does not pin the margin. The goal is that the two sites
above get the SAME layout, and that the aggregate improves.

## Done when

- A `LispFormatterTest` fixture holds the same call at two nesting depths and asserts both
  get the shallow layout -- the pair is the bug, so a single-depth fixture cannot pin it.
- The whole-corpus assertions still hold (identical token stream + fixpoint over every
  checked-in `.lisp`/`.asd`).
- The measurement above is re-taken, is lower than 480, and `.kb/formatter.md` is updated
  with the new figure and with the reason the "fits nowhere" argument is no longer skipped
  when it is the one overflowing.
- `rontolisp format --check examples/ src/main/resources/` is re-run and its result
  committed. The tree is formatted as of `80c70d57`, so a rule change that is not followed
  by a reformat leaves the gate red.
