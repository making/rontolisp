# The `format` renderer and the `#'map-into` wrapper still index a list per element

Difficulty: Medium

Found 2026-08-31 by the survey `.todo/594` asked for. 594 closed five sites of
the `elt`-per-element family -- `make-array :initial-contents` at rank 1 and rank
>= 2, `replace`'s list-DESTINATION arm, `expandMap`, and the interpreter's native
`replace` -- and `.todo/593` closed `search`/`mismatch` before it. The survey that
ran alongside 594 turned up three more. **None of them is in the prelude and none
of them is in `Environment`**, which is why the earlier passes missed them: two
live in a Lisp resource file the renderer loads, one in the first-class-value
wrapper catalog.

Read first: `.kb/seq-coerce-runtime.md`, "The rest of the `elt`-per-element
family" -- it holds the cursor shape, the reason the fallback is never a
`(null cell)` stop, and the differential standard these fixes are held to.
`.kb/format.md` for the renderer's own contract.

All timings: Apple M4 Max, under
`scratchpad/heavy-lock.sh`, warm-up equal to the timed run, **ms per call**.

## 1. The runtime `format` renderer walks its argument list by index

`src/main/resources/am/ik/rontolisp/macro/format-render.lisp`. The argument list
`all` (and `~{`'s one list argument, `items`) is ALWAYS a list, and the renderer
reads it with `(nth i all)` per directive while `i` advances monotonically. `~{`
is worse than that: `%fmt-iterate-list` calls `(length items)` TWICE per pass, so
that loop is quadratic on its own before a single `nth`.

The reads, by defun:

- `%fmt-value` (:534) -- `:536` `(nth i all)`, one per value directive.
- `%fmt-iterate-list` (:733) -- `:735` and `:746` `(>= cur (length items))` /
  `(>= next (length items))`, and `:741` `(nth cur items)`. **The hot one.**
- `%fmt-iterate-args` (:752) -- `:755`, `:761`, `:766`, the same three reads over
  `all` for `~@{` / `~:@{`.
- `%fmt-escape` (:527) `(>= i (length all))` per `~^`; `%fmt-token` (:99)
  `(- (length all) i)` per `~#`.
- `%fmt-logical-block` (:489), `%fmt-recursive` (:543, :547), the `~[` arms
  (:680, :685, :689), `%fmt-iterate` (:720, :728) -- one `(nth i all)` each.
- `format-render-slash.lisp:31`, `%fmt-user-function` --
  `(funcall fn stream (nth i all) colon at)`, the `~/name/` arm.

Effect: `(format nil "~{~a~}" <n-element list>)` is quadratic in `n` on every
path that reaches the runtime renderer -- the interpreter always, the compilers
whenever the control string is computed.

`(format nil "~{~a~}" <n-element list>)`, ms per call, 2026-08-31 under the lock:

| n | 250 | 500 | 1000 | 2000 |
| --- | ---: | ---: | ---: | ---: |
| interpreter | 0.208 | 0.370 | 1.07 | **2.52** |
| JVM `.class` | 0.045 | 0.095 | 0.220 | **0.560** |
| WASM p1 | 0.065 | 0.245 | 0.780 | **3.00** |
| the same string via `(dolist (x l) (princ x s))`, n = 2000 | | | | 0.68 / 0.12 / 0.06 |

Doubling n multiplies WASM by 3.2-3.8x, which is the class. **50x slower than
building the same string by hand at n = 2000 on wasm-GC**, 21x on the JVM, 3.7x
in the interpreter (where the tree-walker's per-node cost hides part of it).

**The fix is not one cursor.** `~*` backs the argument pointer UP, `~:*` and
`~@*` reposition it outright, and `~?`/`~{` recurse into a sub-render with their
own pointer, so a monotone cursor is wrong on its own -- it needs the same
re-seed-on-backward-read that `Environment.SequenceSourceCursor` has, or an
explicit `(nthcdr i all)` re-seed at every reposition. The two `(length items)`
calls per pass are separable and are the bigger win: hoist the length, or replace
the `>=`-against-length test with a `null`-cursor test, which is what the list
already tells you.

## 2. The `#'map-into` wrapper stores by index

`src/main/java/am/ik/rontolisp/compiler/BuiltinFunctionWrappers.java:536`,
`mapIntoWrapper()`. The wrapper already `coerce`s every SOURCE to a list and
walks it with `cdr`; only the DESTINATION store is indexed:

```java
LispVal store = callV(LispNames.SETF, callV(LispNames.ELT, new LispSymbol("r"), index), apply);
```

`(setf (elt <list> i) v)` lowers to `(rplaca (nthcdr i list) v)`, so
`(funcall #'map-into <list> #'f <list>)` is O(n^2). This is the one `map-into`
site that never got the cursor: `LispMacroExpander.mapIntoDispatch` has carried
`rcurVar`/`advanceCursor` for the result since it was written, and the wrapper is
a separate body.

`(funcall #'map-into <n-element list> #'1+ <n-element list>)`, ms per call:

| n | 500 | 1000 | 2000 |
| --- | ---: | ---: | ---: |
| interpreter | 1.045 | 3.02 | **9.86** |
| JVM `.class` | 0.230 | 0.710 | **2.84** |
| WASM p1 | 0.150 | 0.550 | **2.14** |
| the same call in CALL position (has the cursor), n = 2000 | | | 2.44 / 0.060 / 0.040 |
| the wrapper with an ARRAY destination, n = 2000 | | | 2.52 / 0.160 / 0.100 |

Every row roughly quadruples per doubling. At n = 2000 the wrapper is **47x**
(JVM) to **54x** (WASM) slower than the same operation in call position, and 21x
slower than the same wrapper with an array destination -- which is the store, in
one number.

The fix is the `mapIntoDispatch` shape verbatim: a result cursor beside the
index, `(if (consp rc) (rplaca rc v) (setf (elt r i) v))`, advanced with the
sources. An ARRAY destination must keep the `(setf (elt ...))` store exactly as
it is, and the wrapper must keep answering `r`.

## 3. `(map 'string ...)` is quadratic for a DIFFERENT reason

Not this family, but found in the same run and worth one line here.
`expandMap`'s `'string` accumulator is
`(string-concat acc (princ-to-string call))` per element, so it rebuilds the
whole string once per character: `(map 'string #'char-upcase <4000-char string>)`
measured **56.2 ms** on wasm-GC, **22.0** on the JVM and **9.65** in the
interpreter, and 594's cursor did not move it (it is the STORE, not the read).
The `'list` accumulator conses and `nreverse`s, which is right; `'string` should
build into a buffer the same way `coerceToStringBody` does, or accumulate a list
and `coerce` once at the end.

## Not worth fixing (measured and dismissed)

- `geom.lisp:422-423`, `geom:wireframe` -- `(dotimes (i m) (nth i facet) ...)`
  over a facet's vertex list. Exactly the shape, but `m` is a polygon's vertex
  count, 3-4 in every mesh the repo builds. Note it in a comment if you touch the
  file; do not make it a commit of its own.
- `LispMacroExpander.arityDispatchedCall` (:27281), `bindParams` (:26949) and
  `WasmArityBundler` (:274) all emit `(nth i args)` over a list -- bounded by a
  lambda list's length, so O(1) in every real program.

## What "done" requires

The same bar 593 and 594 met, and it is not negotiable for the renderer: the
`format` directive surface is wide and its argument pointer moves in four
directions.

- A differential check of the OLD body against the NEW one over a deterministic
  generator, across representations and directive sets, on all four backends,
  with the comparison count reported. 594's harness is
  `scratchpad/t594/{safe,unsafe,diff.sh}` and it works by diffing one program's
  output between the parent jar and the built one -- reuse it.
- Both ladders re-run after the change, on all four backends, showing the
  COMPLEXITY moved and not just the constant. Report the cost as well as the win.
- `.kb/format.md` and `.kb/seq-coerce-runtime.md`'s family section updated with
  the new numbers and the date; `.kb/sequence-op-runtimes.md`'s `map-into`
  re-evaluation trigger struck once the wrapper has its cursor.
- Tests in `LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest`, and a `ci-spec.yaml` case -- check whether an
  existing `format` case is the right home to extend before adding one.
