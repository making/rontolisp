# 438. Hash tables key by the PRINTED form of the key

Difficulty: Medium

Child of `.todo/436` (read it first). Wave 3: after `.todo/437`.

**Scope decision (2026-08-18): `EQUAL` only.** Making `:test` real -- `eq` /
`eql` / `equalp` -- is deliberately OUT of scope and lives in `.todo/444`, with
the measurement that justified deferring it and the WASM design already worked
out. Keeping this todo to EQUAL is what makes it free on WASM: that backend
already has the right architecture (`_hash` + `_equal`), so nothing there grows
or slows down.

## The defect

`LispHashTable.get`/`put`/`remove` key on `key.print()` -- the printed text of
the key -- and the JVM runtime map does the same. Two consequences:

```lisp
;; 1. a cyclic key does not terminate: printing it never ends
(let ((h (make-hash-table)) (x (list 1 2)))
  (setf (cdr (last x)) x)
  (setf (gethash x h) :v))       ; => OutOfMemoryError
;; an instance graph that is cyclic overflows the stack instead

;; 2. every get and put costs the size of the key's printed graph
```

The first is what killed the ASDF spike (`.kb/asdf.md`); the second is a
constant tax on every table-using program.

## The fix

Separate HASHING from COMPARISON, which is what a hash table is:

- hash = a real structural fold over the key, **with a depth/size cap** so it
  terminates on a cyclic key (a hash need not be injective, so a cap is free
  correctness);
- compare = the real `equal` predicate on the candidates in the bucket.

WASM already works this way (`FUNC_HASH` + `_equal`, `.kb/hash-tables.md`), so
its share is the cap alone. The interpreter (`LispHashTable`) and the JVM
(`JvmHashRuntimeBuilder`, keyed by printed text) are where the work is.

**`equal` must short-circuit on identity** (`a == b` -> true) before recursing.
That is what makes a cyclic key usable in practice: storing and retrieving under
the SAME object terminates. Two DISTINCT cyclic structures compared with `equal`
may still not terminate -- CL leaves that undefined, and this todo does not
change it. Write that boundary into `.kb/hash-tables.md`.

## Watch

- **`_hash` must agree with `_equal`** -- equal keys hash equal. That invariant
  is already stated in `.kb/hash-tables.md`; the cap must not break it (cap by
  DEPTH/COUNT deterministically, never by anything order- or address-dependent).
- Strings and instances must keep finding each other structurally: a
  runtime-built string equal to a literal is one key (`.kb/json.md`), and two
  separately built instances with equal slots are one key (pinned by the
  ci-spec case `instance-print-syntax-and-identity`).
- `maphash` order: interpreter and JVM walk INSERTION order today, WASM walks
  bucket order (`.kb/hash-tables.md`). Do not change either.
- `hash-table-test` keeps answering `EQUAL`, and the printed
  `#<HASH-TABLE :TEST EQUAL :COUNT n>` stays exactly as it is -- both are
  honest under this scope, and `.kb/hash-tables.md` explains why. They move in
  `.todo/444`, not here.
- WASM emitted output must be byte-identical for programs whose keys are not
  cyclic. Check a `size-report` build before and after.

## Acceptance

The cyclic-key snippet stores and retrieves on all four backends; existing hash
table tests green; a ci-spec case (`cyclic-hash-key-438`). Plus a measurement,
in the commit message, of what dropping `print()` did to a table-heavy program's
run time.
