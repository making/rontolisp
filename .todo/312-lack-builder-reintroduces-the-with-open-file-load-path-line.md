# `lack:builder` puts the retired WITH-OPEN-FILE line back on every `--no-wasi` build

Difficulty: Medium

## Symptom

A Clack program that hands `clackup` a value built by `lack:builder` -- the
standard way to add a middleware -- prints, on every `--no-wasi` compile:

```
clack.lisp:21:3: warning: WITH-OPEN-FILE is reachable from a top-level form of
this --no-wasi module (a top-level form -> CLACK:CLACKUP -> CLACK:EVAL-FILE ->
CLACK::%LOAD-FILE), so it can run while the module LOADS ...
```

Reproduced 2026-08-10 on `examples/net/httpbin-clack.lisp` by replacing its last
form with

```lisp
(defun wrap-json (app)
  (lambda (env)
    (let ((response (funcall app env)))
      (list* (first response)
             (list* :content-type "application/json" (second response))
             (cddr response)))))

(clack:clackup (lack:builder #'wrap-json *app*) :server :rontolisp ...)
```

`(clack:clackup (wrap-json *app*) ...)` -- the same middleware, applied without
`builder` -- does NOT warn: a call's RETURN shape is already read through a
one-form defun. Only `builder` loses it.

## Cause

This is the false positive `.kb/wasm-export-no-wasi.md`'s re-evaluation trigger
names ("a value out of a multi-branch function, a struct slot, a list element"),
reached by a shape the lattice has no point for. `lack:builder` expands to

```lisp
(reduce #'funcall (remove-if #'null (list ...))
        :initial-value (to-app APP) :from-end t)
```

so what reaches `clackup` is the value of a `reduce` call. `ArgumentShapes`
answers UNKNOWN, UNKNOWN prunes nothing, and `clackup`'s
`(typecase app ((or pathname string) (eval-file app)) ...)` branch is walked
again -- the branch the shape rules exist to rule out. The same blindness keeps
the branch IN the module under `--optimize` (`DeadTypeBranchPruner`), so the fix
is worth bytes as well as quiet.

## Why it matters

`lack:builder` is not an exotic spelling; it is how a Clack application adds a
session, a static-file mount or a CSRF middleware, and `clackup` itself uses it
for the default middlewares. So the class of warning whose retirement
`compiler/ArgumentShapes` was written for comes back for exactly the programs
that grow past one handler -- and a warning class whose routine instance is a
false positive teaches the reader to skip the class, which is the argument that
retired it the first time.

## Shape of the fix

A new `ArgumentShapes.Shape` point, with the same "UNKNOWN prunes nothing"
discipline, for a value that provably cannot be a `pathname`/`string`. Two
candidate readings, and the choice is the work:

- **`reduce` with `:initial-value X` over a sequence of FUNCTIONs** yields
  either X's shape or a `funcall` result -- a join, which needs the lattice to
  have one.
- **The general rule**: a call to a known-pure builtin whose result shape is a
  join of its argument shapes. Wider, and it is what a struct slot / list
  element would want too.

Do NOT special-case `lack:builder` or `to-app` by name: the same warning is
right for a user who really does pass a pathname through a `reduce`.

## Pins to add

- `NoWasiLoadPathRefusalsTest`: a `lack:builder`-shaped `reduce` over function
  arguments prunes the branch; a `reduce` whose initial value is a literal
  string still reports.
- The four `examples/cloudflare-workers/*clack*` builds must stay warning-free,
  and a builder-using variant must join them.

## Where it is documented today

`examples/cloudflare-workers/httpbin-clack/README.md` warns the reader that the
line is a false alarm, under "Middleware, `lack:builder`, request bodies". Delete
that paragraph when this lands.
