# `search` costs 107 us a call in the interpreter, 240x `char` and 76x `search` on WASM

Difficulty: Medium

Measured 2026-08-31 while doing `.todo/590` (`position` on a string). 590 fixed
the sequence family by converting `(coerce x 'list)` natively in the interpreter
(`.kb/seq-coerce-runtime.md`): `position` 28.8 -> 3.95 us, `find` 27.5 -> 2.78,
`remove` 90.9 -> 18.7, `reduce` 27.1 -> 3.23. **`search` did not move at all**,
because it never reaches `coerce`. It is now by a wide margin the most expensive
operator in the family, and the only one left that is absurd.

Apple M4 Max, 100,000 iterations under a machine-exclusive lock, us per call.
Haystack is the 46-character string
`"v -3.4101800e-003 1.3031957e-001 2.1754370e-002"`, needle `"e-001"` (a hit at
index 33):

| backend | `(search "e-001" *line*)` | `(char *line* 3)` |
| --- | ---: | ---: |
| interpreter | **106.6** | 0.45 |
| JVM `.class` | 0.73 | 0.14 |
| WASM preview 1 | 1.39 | 0.01 |
| WASM `--component` | 1.40 | 0.01 |

Two more interpreter probes from the same session:

| form | us |
| --- | ---: |
| `(search "v " *line*)` -- a hit at index 0 | 11.9 |
| `(search "zzzz" *line*)` -- a full miss | 130.0 |
| `(search '(3 4) '(1 2 ... 10))` -- a 10-element LIST | 18.3 |

## Where it resolves and why it is slow

`search` is NOT a macro expansion and NOT a Java built-in. It is a Lisp-source
prelude `defun` -- `LispPreludeLibrary.SOURCES.put(LispNames.SEARCH, ...)` --
loaded lazily on the first resolution of the name
(`LispEvaluator`'s `LispPreludeLibrary.isPreludeFunction(name)` branch). Its body
is the naive O(n*m) double loop, and the inner loop's body is, per CHARACTER
PAIR:

```lisp
(let ((a (elt seq1 (+ start1 i)))
      (b (elt seq2 (+ pos i))))
  (unless (funcall test (if key (funcall key a) a)
                   (if key (funcall key b) b))
    (setq ok nil)))
```

Two `elt` calls and a `funcall` of the `:test` designator, all interpreted.
Measured on the interpreter: `(elt s 3)` is 0.83 us, `(funcall #'eql 1 1)` is
0.33 us, so ~2.5 us per character pair accounts for the whole number. There is
nothing pathological here -- it is the tree-walking interpreter's per-node cost
multiplied by n*m. The identical source COMPILES to 0.7-1.4 us, which is why only
the interpreter has the problem.

Note the LIST case is worse than it looks: `elt` on a list is an `nth` walk from
the head, so `search` over two lists is O(n^2 * m), not O(n*m). That is the same
defect `.kb/sequence-op-runtimes.md` records fixing in `replace`'s list source
arm (an `elt` per element -> a `nthcdr`/`cdr` cursor), and it applies here
unchanged.

## What was ruled out, and why

- **The `coerce` fast arm (590's fix) cannot reach it.** `search` does not use
  `seqAsListForm`; it indexes both sequences with `elt`. Re-running 590's
  benchmark before/after shows `search` flat at 107 us.
- **The compiled backends need nothing** -- 0.73 us (JVM) and 1.39/1.40 us
  (WASM). A cross-backend primitive in front of the prelude loop would cost every
  wasm module bytes to fix a problem three of the four backends do not have.
- **Replacing the prelude `defun` with a native `Environment` built-in is not a
  fast path, it is a REPLACEMENT.** The prelude loads only when the name is not
  already defined, so a native registration means the Lisp source never runs and
  the interpreter's `search` is a second implementation of the full keyword set
  (`:start1`/`:end1`/`:start2`/`:end2`/`:test`/`:key`/`:from-end`) that has to
  agree with the compile paths' version in every corner. That is exactly the
  interpreter/compile divergence the repo warns about, and it is why 590 did not
  do it in passing.

## What a fix would have to touch

The shape that keeps the contract is the declining one
(`.kb/binary-sequence-io.md`): a `case LispNames.SEARCH` in
`LispEvaluator.evalCons` that answers only the narrow shape it can prove
identical -- both sequences `LispString`, no `:key`, no `:from-end`, `:test`
absent (the default is `eql`, and `eql` on two `LispChar`s is code-point
equality) -- honouring `:start1`/`:end1`/`:start2`/`:end2`, and falls through to
the ordinary function-call path for everything else. The catch to design for:
`nil` is a legitimate "not found" answer, and the fall-through happens AFTER the
argument forms are evaluated, so the decline must either be decided
syntactically before evaluation or re-enter the call over quoted values (the way
`evalSequenceCoerce` does).

A cheaper, narrower alternative worth measuring first: fix the prelude loop's
LIST arm to walk with a cursor instead of `elt`. That helps every backend, is one
edit to `LispPreludeLibrary`, and removes a genuine O(n^2) -- but it does nothing
for the string case, which is the 107 us above.

Whichever is chosen: `.kb/seq-coerce-runtime.md`'s "`search` is a different
defect" section is the place to record the outcome, and the numbers above are its
"before".
