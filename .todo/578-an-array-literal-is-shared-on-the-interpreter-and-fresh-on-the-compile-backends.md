# An array literal is shared on the interpreter and fresh on the compile backends

Difficulty: Medium

Found 2026-08-29 while asking whether `geom.lisp`'s hardcoded `(geom:vec3 1 0 0)` calls
could be written `#f(1.0 0.0 0.0)`. They cannot, and the reason is not about `geom`: the
three array literal syntaxes do not mean the same thing on the interpreter as they do on
the two compile backends.

## The measurement

```lisp
(defun f () #f(1.0 0.0 0.0))
(print (eq (f) (f)))
(let ((a (f))) (setf (aref a 0) 9.0) (print a) (print (f)))
```

| backend | `(eq (f) (f))` | the second `(f)` |
|---|---|---|
| interpreter | `T` | `#f(9.0 0.0 0.0)` -- the constant in the source is gone |
| JVM class | `NIL` | `#f(1.0 0.0 0.0)` |
| WASM preview 1 | `NIL` | `#f(1.0 0.0 0.0)` |

`#d(...)` and `#(...)` behave the same way as `#f(...)`. A STRING literal does not diverge:
`(eq (fs) (fs))` is `T` on all three, which is the shape the rest of the tree already
assumes (`.kb/asdf.md`, cl-base64 item 3 -- a `(setf (schar ...))` on a string literal
falls back to a setq-rebuild on the compile path precisely because the literal is not a
buffer to write into).

So the divergence is exactly: an array literal is a shared, WRITABLE object on the
interpreter, and a fresh allocation per evaluation on the compile backends.

## Why it matters

Neither half is obviously wrong on its own -- ANSI leaves the consequences of modifying a
literal undefined, and coalescing is explicitly permitted -- but the tree's rule is that
the four backends agree, and here they do not. A program that writes through an array
literal is silently correct on three backends and silently corrupts its own source
constant on the fourth. Nothing pins it today.

## The decision this needs first

Two coherent answers, and the choice is the work:

- **Shared and immutable everywhere.** The CL-conformant reading, and consistent with how
  string literals already behave here. A write must then be an ERROR rather than silent
  corruption, on every backend -- silently ignoring it would be a third behavior. Cost: any
  shipped Lisp or user program that fills an array literal in place stops working, so the
  blast radius has to be measured across `src/main/resources/am/ik/rontolisp/eval/*.lisp`
  and `examples/` before committing to it.
- **Fresh per evaluation everywhere.** Matches what the compile backends already do, so
  only the interpreter moves and no existing program breaks. It costs an allocation per
  evaluation and gives up the coalescing ANSI allows, which is a real loss in a loop that
  mentions a literal.

Measure both before choosing. The second is cheaper to land; the first is the one a CL
programmer coming from SBCL expects, and the one that lets a literal be a genuine constant.

## Do

- Decide the semantics above, and write the reason into `.kb/` (a new file, or the reader
  section that owns literal materialization) -- this is a language decision, not a bug fix.
- Make the reader/interpreter and both compile backends agree, `#f(...)`, `#d(...)` and
  `#(...)` alike. A bit-vector literal `#*1011` is in the same family and must be checked.
- Pin it in `ci-spec.yaml`: the `eq` answer AND the write behavior, so a later change to
  either half fails on all four backends at once.
- Say what happens in `doc/{en,ja}` -- today neither documents whether a literal may be
  written.

## What it unblocks, and what it does not

If the answer is "fresh per evaluation", `geom.lisp`'s ten never-mutated `geom:vec3`
constant sites (`geom:axis-vector`'s six, the two default colors, the identity translation,
the degenerate centroid) can become `#f(...)` literals mechanically, and so can the two
`bounds` sites that write through the result.

If the answer is "shared and immutable", they cannot -- `geom:axis-vector` and
`geom:color-of` hand their value to the caller, and a caller that writes into it is doing
something that works today. Making those literals would be an API change (the returned
vector becomes read-only) and needs to be decided as one, in `geom`'s own terms.

Either way the motivation is brevity, not speed: `geom:vec3` is on no hot path -- the
cached-mesh invariant means a frame never calls it (`.kb/geom.md`), and the remaining
callers are per-solid.
