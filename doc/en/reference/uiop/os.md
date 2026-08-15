# uiop/os

`uiop/os` is what a portable library asks about the host: which operating system
this is, which implementation, what the environment says, where the working
directory is. **All 22 exports are implemented**, and every host answer is
derived from one source — upstream's own `uiop:featurep` over
[`*features*`](../data-types.md#comments-feature-conditionals-and-features) — so the same rule decides them
on all four backends.

Every name is reachable through either spelling: `uiop:getenv` and
`uiop/os:getenv` are the same function
([The uiop Package](../uiop.md#sub-packages)).

Three answers here are rontolisp's own, and each is a decision rather than a
gap:

- **`uiop:os-unix-p` is `t` outright.** Every backend presents the POSIX-shaped
  file and namestring model, so the answer is right — but `*features*`
  deliberately carries no `:unix`, because that would flip the `#+unix` reader
  branch of every library the frontend reads, which is a far wider claim than
  one OS predicate.
- **The environment is read from the host and written to an override map.** No
  backend can rewrite its own process environment (the JVM cannot at all, WASI's
  is read-only), so `(setf (uiop:getenv name) value)` records the value in a
  per-program map that `uiop:getenv` consults first.
- **There is no `chdir` anywhere**, and no working directory on the WASM
  backends. See [The working directory](#the-working-directory).

## Host identity

| Function | What it answers |
|----------|-----------------|
| `uiop:implementation-type` / `uiop:*implementation-type*` | `:rontolisp` |
| `uiop:lisp-version-string` | this build's version, the same string `(rontolisp:version)` carries |
| `uiop:operating-system` | `:unix` |
| `uiop:detect-os` | `:os-unix` — upstream pushes the winning feature and returns it; nothing here can push, so it just returns it |
| `uiop:architecture` | the ABI the artifact targets: `:jvm` on the interpreter and the JVM (a class file is CPU-independent), `:wasm32` on both WASM outputs |
| `uiop:implementation-identifier` | all four joined and downcased, the way upstream builds a fasl-cache directory name: `"rontolisp-<version>-unix-jvm"` |
| `uiop:hostname` | `nil` — no backend has a host-identity primitive, and this is exactly what upstream answers on an implementation its own `#+` clauses do not name |
| `uiop:os-unix-p` | `t` (see above) |
| `uiop:os-macosx-p` / `uiop:os-windows-p` / `uiop:os-genera-p` | `nil` — upstream's own derivations over `*features*`, which carries no host-OS feature |
| `uiop:os-cond` | a `cond` over the predicates above, choosing the first clause whose test is true |

```lisp
(print (list (uiop:implementation-type) (uiop:operating-system) (uiop:detect-os)))
(print (list (uiop:os-unix-p) (uiop:os-windows-p) (uiop:hostname)))
(print (uiop:os-cond ((uiop:os-windows-p) :windows) ((uiop:os-unix-p) :unix) (t :other)))
```

```
(:RONTOLISP :UNIX :OS-UNIX)
(T NIL NIL)
:UNIX
```

## Feature expressions

`uiop:featurep` evaluates a feature expression against `*features*` at **run
time**, exactly as `#+` does at read time: an atom is a membership test, and
`(:not e)`, `(:or e...)` and `(:and e...)` combine them.

```lisp
(print (list (uiop:featurep :rontolisp)
             (uiop:featurep '(:and :rontolisp :unicode))
             (uiop:featurep '(:or :no-such-feature :rontolisp))
             (uiop:featurep '(:not :no-such-feature))
             (uiop:featurep :no-such-feature)))
```

```
(T T T T NIL)
```

The feature set differs per backend by design — `:rontolisp-interpreter`,
`:rontolisp-jvm` and `:rontolisp-wasm` are what tell them apart — so
`uiop:featurep` (and `uiop:architecture` with it) answers for the backend that
is running. A second, optional argument tests a feature set of your own:
`(uiop:featurep :x '(:x))` is `T`. Rebinding `*features*` around the call, which
upstream's parameter list invites, is an interpreter-only shape here: the
compile backends substitute the variable at read time.

## Environment variables

| Function | What it does |
|----------|--------------|
| [`uiop:getenv`](../functions/uiop-getenv.md) | the value of a variable as a string, or `nil` when unset |
| `(setf uiop:getenv)` | record an override the reads above consult first; a `nil` value is an unset |
| `uiop:getenvp` | the value, but `nil` for the empty string as well — "is this variable really set?" |

```lisp
(setf (uiop:getenv "RONTOLISP_DOC_VAR") "hello")
(print (list (uiop:getenv "RONTOLISP_DOC_VAR") (uiop:getenvp "RONTOLISP_DOC_VAR")))
(setf (uiop:getenv "RONTOLISP_DOC_VAR") "")
(print (list (uiop:getenv "RONTOLISP_DOC_VAR") (uiop:getenvp "RONTOLISP_DOC_VAR")))
(setf (uiop:getenv "RONTOLISP_DOC_VAR") nil)
(print (uiop:getenv "RONTOLISP_DOC_VAR"))
```

```
("hello" "hello")
("" NIL)
NIL
```

The override is **per program run**, not a change to the process environment: a
subprocess would not see it, and neither would anything outside this image.
That is the honest shape of `(setf (uiop:getenv ...))` on hosts that do not
allow the write, and it is what makes the option-setting idiom libraries use —
bind some variables, run a body, put them back — behave the same on all four
backends.

## The working directory

| Function | What it does |
|----------|--------------|
| `uiop:getcwd` | the host working directory in directory form, or a signalled `uiop:not-implemented-error` where the host has none |
| `uiop:chdir` | signals `uiop:not-implemented-error` on every backend |

`uiop:getcwd` answers on the interpreter and the JVM, where the host process has
a working directory. **Both WASM backends signal**: a WASI program is given
preopened directories and no current one, so there is nothing to answer.

`uiop:chdir` signals everywhere, including the JVM. Java reads `user.dir` once
at startup and cannot move the process working directory, and WASI has no
`chdir` at all — an answer that only changed what merges look like, while
`open` kept resolving elsewhere, would be worse than the error.

```lisp
(handler-case (uiop:chdir "/tmp")
  (uiop:not-implemented-error () :cannot-change-directory))   ; => :CANNOT-CHANGE-DIRECTORY
```

## Windows shortcuts

Upstream carries a small `.lnk` reader, and its two octet primitives are
generally useful:

| Function | What it does |
|----------|--------------|
| `uiop:read-little-endian` | read an unsigned little-endian integer of *n* octets (4 by default) from a binary stream |
| `uiop:read-null-terminated-string` | read octets up to a `0` and answer them as a string |
| `uiop:parse-windows-shortcut` / `uiop:parse-file-location-info` | signal `uiop:not-implemented-error` |

The two readers are real stream work and run everywhere `read-byte` does. The
two `.lnk` parsers navigate the file with `file-position`, which no rontolisp
file stream supports, so they name that primitive instead of misparsing
silently.
