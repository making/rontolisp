# 425. cl-mustache (Mustache template renderer) support (parent)

Difficulty: Medium

Parent item for making [cl-mustache](https://github.com/kanru/cl-mustache) --
Kan-Ru Chen's Mustache v1.1.2+λ template renderer (MIT/Expat, quicklisp dist
`cl-mustache-20241012-git`) -- loadable and usable on rontolisp. The children
are the gaps a spike found (2026-08-17); this file holds the picture, the
verified baseline and the ordering. Every child is a general gap; none of them
is cl-mustache-only.

## What the spike did

`(ql:quickload "cl-mustache")` fetched `cl-mustache-20241012-git` into the
quicklisp cache. The library is tiny -- 924 lines over `packages.lisp`,
`mustache.lisp`, `compat-api-v1.lisp` -- and its only dependency is `uiop`,
which is a built-in shim, so **nothing about the dependency graph or the `.asd`
needed widening**: the `defsystem` form parses as-is, `#.`-read-time-eval
`:version` included.

A scratch copy was patched form by form until the library loaded and its own
test suites ran. The whole gap list is **three patches**:

```diff
-  (ctypecase maybe-context                                    ; .todo/426
+  (etypecase maybe-context

 (defmethod context-get ((key string) (context hash-table))    ; .todo/427
-  (gethash (string-upcase key) context))
+  (multiple-value-bind (v f) (gethash (string-upcase key) context) (values v f)))

 (defun version ()                                             ; .todo/428
-  #.(format nil "CL-MUSTACHE ~A (Mustache spec ~A)" ... *load-truename* ...))
+  "CL-MUSTACHE 0.12.3 (Mustache spec 1.1.2, including lambdas)")
```

## The baseline the spike reached

The library ships two suites under `t/`, both written against `prove`. `prove`
is not in the tree, so the spike stood in a 20-line `plan`/`is`/`is-error`/
`finalize` shim and ran the files verbatim. **`t/test-spec.lisp` is the
official mustache spec suite, machine-generated from
`github.com/mustache/spec`** -- 194 cases -- which makes it an oracle we did
not write.

| suite | rontolisp | SBCL 2.x, same shim, same files |
| --- | --- | --- |
| `t/test-spec.lisp` (mustache spec, 194 cases) | **158 pass / 36 fail** | **158 pass / 36 fail** |
| `t/test-api.lisp` (20 cases) | 20 pass / 0 fail | 20 pass / 0 fail |

The 36 spec failures are **byte-identically the same 36 cases on both
implementations** (the only textual difference in the whole run is SBCL's
richer `#<HASH-TABLE ...>` print). They are upstream limitations, not ours:
nulls do not interpolate as the empty string, dotted names push a context
frame, and the entire `~inheritance.json` module (26 cases) is unimplemented --
cl-mustache targets spec 1.1.2, which predates it. So on the INTERPRETER
cl-mustache is already at parity with SBCL, with the three patches above.

Per backend, over the spec suite split by spec file (the inheritance module
dropped, since it fails everywhere):

| section | interp | JVM | WASM P1 | WASM component |
| --- | --- | --- | --- | --- |
| comments / delimiters / inverted / partials / lambdas | all pass | all pass | all pass | all pass |
| interpolation | 39/42 | 39/42 | 36/42 | 36/42 |
| sections | 32/34 | 32/34 | **trap** | **trap** |
| ~dynamic-names | 17/21 | 17/21 | 17/21 | 17/21 |
| `t/test-api.lisp` | 20/20 | 20/20 | **trap** | **trap** |

Everything the compiled backends lose is one of the children below.

## The children

Blockers, in the order that unblocks the most:

1. `.todo/426` -- `ctypecase` does not exist. `ensure-context` is the first
   thing every entry point calls, so nothing renders at all. `ccase` and
   `etypecase` both ship, so this is the two names' missing sibling.
2. `.todo/427` -- `gethash`'s present-p (and `floor`'s remainder, and
   `find-symbol`'s status) is a SYNTACTIC second value: it exists only when the
   producer is written directly inside the consumer, and is silently lost
   through a function or method return. cl-mustache's `context-get` is a
   `defmethod` whose body IS `(gethash ...)`, so every variable lookup answered
   "not found" and `{{name}}` rendered empty. THE blocker: without it the
   library loads and renders nothing.
3. `.todo/428` -- `#.` read-time eval sees `*load-truename*` = nil on the
   COMPILE path (the interpreter binds it correctly). cl-mustache's `version`
   reads two `*.lisp-expr` data files next to the source that way, so
   `-o X.class` / `-o x.wasm` refuse the system outright with
   `OPEN: cannot open file version.lisp-expr`.
4. `.todo/429` -- on the three COMPILED backends, `signal` of a condition that
   no `handler-case` clause matches is fatal instead of returning nil. A
   missing partial signals `partial-cant-be-found`, so any program that wraps
   `render` in `(handler-case ... (error ...))` -- the ordinary shape -- dies
   on a template it should have rendered as the empty string.
5. `.todo/430` -- printing a hash-table diverges three ways: `#<HASH-TABLE>`
   (interpreter), Java's `toString` leaking `[Ljava.lang.Object;@3fee733d`
   (JVM), unbounded recursion until the stack traps (WASM). A section variable
   bound to a map is printed by `{{.}}`, so this is what traps the WASM
   backends on the `sections` block.
6. `.todo/431` -- the WASM backends print floats with a lossy fixed-digit
   algorithm: `1.21` -> `1.209999`, `3.14159` -> `3.141589`,
   `(/ 1.0 3.0)` -> `0.333333`. Three spec cases ("Decimal Interpolation")
   fail on WASM alone; any template interpolating a float is wrong there.
7. `.todo/432` -- on both WASM backends a RUNTIME-computed absolute path cannot
   be opened at all (`open` errors, `probe-file` answers nil), because `_open`
   resolves everything against the first preopen without consulting the preopen
   names. That is what breaks `mustache:compile-template` on a file and
   `*load-path*` partial lookup, so `t/test-api.lisp` cannot run on WASM at
   all.

## Vendoring, for the E2E

`AsdfLibraryE2eSupport` needs the sources under `src/test/resources`.
To add: **cl-mustache** (MIT/Expat, stated in every file header; note there is
no separate `LICENSE` file in the distribution). `uiop` is a built-in shim, so
there is nothing else to vendor.

`t/test-spec.lisp` is the pinning target: it is generated from the mustache
spec, it is 194 independent cases, and the 158/36 split above is the number to
hold. It needs `prove`, which the tree does not have -- either port the file to
`rove` (in the tree, `rove-demo`) mechanically, since it uses only
`plan`/`is`/`finalize`, or keep the three-function shim beside it.

## Definition of done

`(ql:quickload "cl-mustache")` loads the UNPATCHED upstream system, and
`render` / `render*` / `compile-template` / `define` / `make-context` -- string
templates, file templates, alist and hash-table contexts, sections, inverted
sections, partials, lambdas, dynamic names -- run identically on all four
backends, at 158/194 on the spec suite and 20/20 on `t/test-api.lisp`. Then:
`ClMustacheE2eTest` via `AsdfLibraryE2eSupport`, an
`examples/asdf/mustache-demo.lisp` + README row, the `guides/asdf-systems.md`
row (en+ja), and `.kb/asdf.md`'s entry.

## Non-goal

Implementing the mustache inheritance module (`{{<parent}}` / `{{$block}}`).
Upstream does not have it and the 26 spec cases fail on SBCL identically; if we
ever wanted it, it is a change to cl-mustache, not to rontolisp.
