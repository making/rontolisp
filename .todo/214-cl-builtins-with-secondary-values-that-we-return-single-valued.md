# CL built-ins with secondary values that we return single-valued

Found 2026-07-30 while making the REPL echo multiple values, by diffing our REPL
against SBCL 2.2.9 on the host. **Pre-existing** and already listed as a
limitation (`doc/*/guides/missing-features.md`, "other built-ins with secondary
values in CL (`read-from-string`, `macroexpand-1`, `intern`, ...) remain
single-value") -- this item is the concrete inventory plus the diff harness, so
the list stops being open-ended.

```console
$ sbcl --noinform                  $ rontolisp
* (read-from-string "abc")         > (read-from-string "abc")
ABC                                ABC
3                                             <- SBCL also echoes the stop index
* (macroexpand-1 '(when t 1))      > (macroexpand-1 '(when t 1))
(IF T                              (IF T 1 NIL)
    1)
T                                             <- SBCL also echoes the expanded-p flag
```

## The inventory (what SBCL returns, what we return)

| operator | CL secondary value(s) | ours |
| --- | --- | --- |
| `read-from-string` | index after the object read | primary only |
| `macroexpand-1` / `macroexpand` | expanded-p | primary only |
| `intern` | `:internal` / `:external` / `:inherited` / nil | primary only |
| `find-symbol` | same status keyword | primary only |
| `get-setf-expansion` | 5 values (we DO return all 5 -- keep) | ok |
| `parse-integer` | stop index (we DO publish it) | ok |
| `floor`-family, `gethash`, `array-displacement` | remainder / present-p / offset | `.todo/212` |
| `subtypep` | valid-p | primary only |
| `decode-universal-time` | 9 values | primary only |
| `truncate`-family on ratios / `ffloor` &c | remainder | primary only |
| `string-to-octets`-style helpers, `gethash`-like table ops in our own libraries | -- | audit while here |

Complete the table by grepping the CLHS list against `PackageRegistry.CL_FUNCTIONS`
before implementing -- the point of this item is that the set is finite and known.

## Scope

Each entry is the same small change, once the channel is decided:

- Today: publish the extra values to `%mv-spill` from the built-in
  (`Environment` for the interpreter -- `parse-integer` at
  `Environment.java` is the worked example) and from the compilers' expansion,
  so the value crosses the call boundary on all four backends.
- If `.todo/213`'s runtime multiple-value carrier lands first, these become
  ordinary multi-value returns and no channel work is needed -- so **check
  `.todo/213` before starting**: doing these by hand first means redoing them.
- `decode-universal-time` (9 values) is the one that argues for waiting: the
  spill route makes a 9-element list per call.

## Non-goals

- Inventing secondary values we have no consumer for. Land the ones real library
  code reads (`read-from-string`'s index and `macroexpand-1`'s flag are the two
  that appear in ported CL sources) and leave the rest listed here.
- The `macroexpand-1` expansion difference the transcript above also shows
  (`(IF T 1 NIL)` vs SBCL's `(IF T 1)`, and SBCL's pretty-printed line breaks):
  that is our `when` lowering and our printer, unrelated to multiple values.

## Verification

- The REPL-vs-SBCL diff harness, kept with this item so the next visitor can
  re-run it (SBCL is installed on the dev host):

```console
$ cat cases.txt
(read-from-string "abc")
(macroexpand-1 '(when t 1))
(intern "FOO")
(find-symbol "CAR" "CL")
(subtypep 'integer 'number)
$ sbcl --noinform --disable-debugger < cases.txt | sed 's/^\(\* \)*//' > sbcl.out
$ rontolisp < cases.txt | sed 's/^\(> \)*//' > ronto.out
$ diff -u sbcl.out ronto.out
```

- Per operator: a `LispEvaluatorTest` `multiple-value-bind` case, the same on the
  JVM and wasm-GC, and a `ci-spec.yaml` line (native E2E re-run).
- Update the `missing-features.md` bullet in BOTH language trees as entries land.
