# `:test` / `:key` keyword args for sequence & alist functions (incomplete)

**Status (re-measured 2026-07-04 on the native binary, HEAD=40598cc):**

- `member`, `assoc`, `rassoc` accept `:test` and it works -- `assoc` with
  `#'string=` / `#'=` verified on **all three backends** (interpreter / JVM
  / WASM Preview 1).
- `assoc` with `:key` is **silently ignored** (returns as if `:key` were
  absent) -- worse than erroring; fix to either implement or reject loudly.
- `find`, `position`, `remove`, `union` **error** when given `:test`
  (measured); presumably also `count` / `delete` / `remove-duplicates` /
  `intersection` / `set-difference` / `adjoin` / `substitute` -- re-probe
  when picking this up.

An earlier version of this note claimed `assoc :test` errors with "assoc
expects 2 arguments, got 4"; that has since been fixed. Remaining work:

## What to add

- **`:test`** on `find`, `position`, `count`, `remove`, `delete`,
  `remove-duplicates`, `union`, `intersection`, `set-difference`, `adjoin`,
  `substitute`/`nsubstitute`. Default stays `eql`; common explicit values
  are `#'equal` / `#'string=` / `#'=`.
- **`:key`** on the full set, including `member`/`assoc`/`rassoc` where
  `:test` already works (apply a selector to each element before testing --
  CL code leans on this heavily). Fix the `assoc :key` silent-ignore first.

`member`/`assoc` are the existing reference implementations for `:test`
parsing -- follow their shape for the others.

## Where to implement

- `Environment.java` -- the interpreter builtins (these live in the
  `registerSequenceOps` / assoc registration area; the `member`/`assoc`
  `:test` handling is the template). Update `requireArgCount` call sites so
  the keyword pairs are parsed instead of rejected.
- JVM/WASM: the matching `Jvm<Name>Compiler` / `Wasm<Name>Compiler` for each
  function; keyword args must be resolved (statically where possible, like
  the existing `member` handling).
- Docs: per-operator pages under `doc/en/reference/functions/` -- verify
  `assoc.md`/`rassoc.md`/`member.md` document `:test` today, and add
  `:test`/`:key` to each newly supporting page, mirrored byte-identically in
  `doc/ja/**`. Add E2E cases to `ci-spec.yaml`.

## Why it matters

Unblocks string-keyed alists (a lighter alternative to hash tables) and a
large class of ordinary CL list code. Newly relevant: the URL/query library
(`.todo/55-url-query-library.md`) returns alists whose idiomatic lookup is
`(assoc name params :test #'string=)` -- that works today, but `:key` and
the sequence functions remain for parity.
