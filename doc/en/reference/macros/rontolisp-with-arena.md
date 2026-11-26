# rontolisp:with-arena

`(rontolisp:with-arena () body...)`

Runs the body and returns its value, naming a **memory-reclamation boundary** for the
non-GC WASM backend ([`--no-gc`](../../guides/wasm-nogc.md)). On
that backend nothing is freed within one export call — the bump allocator only pops at
the export boundary — so a loop that allocates each iteration (string building, fresh
`vec:` vectors) grows the linear memory. `with-arena` closes that gap: it snapshots the
bump heap pointer, runs the body, and pops everything the body allocated, keeping only
the body's own value (a string or packed float array result is copied down to the
snapshot point). On the interpreter, the JVM backend and the default (wasm-GC) output a
real garbage collector already reclaims, so it is observationally a plain `progn` and
the same source runs on every backend.

```lisp
(rontolisp:with-arena ()
  (+ 1 2))  ; => 3
```

## Arguments

- An option list, which must currently be empty (`()`); it is reserved for future
  options.
- The body forms, evaluated in order like a `progn`. The value of the last form is the
  value of the whole expression (`nil` for an empty body).

## The escape contract (`--no-gc`)

Nothing allocated inside the body may be reachable after it, **except the body's own
value**. Storing an inner allocation somewhere that outlives the arena (for example
writing it into an array created outside) leaves a dangling pointer once the arena pops
— the same rule as the host-side `__ronto_alloc_reset`
([Reclaiming memory](../../guides/wasm-nogc.md#reclaiming-memory-the-arena-api)).

A typical use — keeping a hot loop flat on `--no-gc`:

```lisp
(defun train (epochs n)
  (let ((acc 0.0))
    (dotimes (i epochs)
      (rontolisp:with-arena ()                    ; everything allocated inside ...
        (setq acc (+ acc (vec:sum (vec:ones n)))) ; ... is popped here
        ))
    acc))
(train 3 4)  ; => 12.0
```

## Limitations

- The option list must be empty; a non-empty option list is an error.
- On `--no-gc`, a `return` that exits across the arena boundary skips the pop (the
  allocations inside are simply not reclaimed; nothing is corrupted).
- Macros have no function value: `#'rontolisp:with-arena` is an error.
