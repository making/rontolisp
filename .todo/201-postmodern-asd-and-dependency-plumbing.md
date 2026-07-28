# postmodern.asd override + dependency plumbing (bordeaux-threads, uiop, global-vars)

Goal: `(ql:quickload "postmodern")` resolves and orders the dependency graph
on rontolisp's mini-ASDF. Pure plumbing -- no language features -- but it
decides the build variant, so land it FIRST and make the feature decisions
explicit. Mechanics of the substitution ladder: `.kb/asdf.md`.

Blocks `.todo/202-postmodern-non-mop-milestone.md`.

## 1. `AsdOverrides` entry: `postmodern.asd` -> `postmodern-deps.asd`

Upstream `postmodern.asd` starts with a top-level `eval-when` pushing
`:postmodern-thread-safe` / `:postmodern-use-mop` per implementation --
unparseable by `AsdfSystems` (only defsystem/defpackage/in-package/pure-data
defparameter are allowed) AND the pushes would be invisible anyway
(`.todo/181`). Replacement `.asd` (same pattern as `cl-postgres-deps.asd`):

- Drop the `eval-when`; encode the feature decisions statically:
  - **non-MOP first**: no `:postmodern-use-mop`, so `table.lisp` (the
    `:if-feature` component) is skipped and the `postmodern` package `:use`s
    `:common-lisp`, not `:closer-common-lisp`. The MOP build flips this later
    (`.todo/203-dao-mop-layer.md`).
  - `:postmodern-thread-safe`: DECIDE, don't inherit. Recommended ON with the
    bordeaux-threads shim below -- rontolisp DOES run concurrent handlers
    (virtual-thread `serve`, `.todo/190/193` are exactly concurrent-connection
    bugs), so compiling the lock sites to `(progn ...)` is a semantic
    statement; a shim whose `with-lock-held` really serializes on the JVM/
    interpreter is more honest. Preview-1/component are single-threaded --
    no-op there is fine.
- `:depends-on`: drop `"global-vars"` (declared upstream, ZERO call sites --
  it exists only as a bordeaux-threads dependency and its non-SBCL branch
  needs `define-symbol-macro` + `remprop`, both absent; dropping it avoids
  that entire problem). Keep `alexandria cl-postgres s-sql split-sequence
  uiop` + the bt shim if thread-safe.
- Preserve the component graph incl.
  `(:file "deftable" :depends-on ("query" (:feature ...)))` -- note the
  upstream `(:feature :postmodern-use-mop "table" "config")` has a trailing
  `"config"` that real ASDF silently IGNORES (only first+second are read);
  match that tolerance in `AsdfSystems` rather than erroring on the 3-element
  form. Also `s-sql.asd` and `simple-date.asd` live in the same release dir --
  make sure locate() finds `s-sql` there without its own override (it is
  plain data).
- Undeclared-but-used deps (`cl-ppcre`, `uax-15`) already load transitively
  via cl-postgres; declare them in the replacement anyway so pruning /
  ordering never depends on luck.
- native-image: every new bundled `.asd`/shim resource needs a
  `resource-config.json` entry or it fails ONLY on the native binary
  (`.kb/asdf.md` trap).

## 2. `bordeaux-threads` shim system

postmodern uses exactly `bt:make-lock` and `bt:with-lock-held` (3 locks, 8
sites; it never spawns a thread). Upstream bordeaux-threads hard-errors in
its `.asd` on unknown implementations, so a shim system (Tier 1, like
`usocket`) is the right move -- `.todo/147`'s "usocket-class portability
layer" criterion applies. Package nickname `bt` + `bt2`? (postmodern uses
`bordeaux-threads:` qualified names -- check which spellings appear). JVM/
interpreter: back with a real `java:` monitor or a channel; WASM: no-op.

## 3. `uiop` function subset

The uiop shim is a package stub. postmodern calls `uiop:file-exists-p` (5),
`uiop:run-program` (1, in `execute-file.lisp`), `uiop::get-pathname-defaults`
(1). `file-exists-p`/`get-pathname-defaults` need real (SourceLoader-mediated)
implementations for the execute-file path; `run-program` should be an honest
runtime error ("not supported") -- it must not block LOADING (undefined-fn
call-time semantics already give that; verify for a shim-package symbol).

## 4. Loose ends

- `simple-date` is NOT a postmodern dependency (`(ql:quickload :postmodern)`
  never loads it); json-encoder/table probe for it via runtime `find-package`
  and must degrade gracefully (`.todo/198`). A separate simple-date todo is
  only warranted when a user asks for it.
- Load postmodern's own `:nicknames (:pomo)` through `PackageResolver` --
  verify nickname registration from a library defpackage works (cl-postgres
  already exercises nicknames? check).
