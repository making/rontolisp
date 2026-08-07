# Make the wasm-GC `pi_approx` the smallest module in the size lineup

Difficulty: High

`examples/wasm-size/` compares the compiled `.wasm` of two programs against the
same programs written in four other languages. rontolisp already wins
`hello_world` outright (518 B against a 1,974 B best elsewhere). `pi_approx` does
not:

| | bytes |
| --- | ---: |
| smallest entry in the comparison table | 6,034 |
| second smallest | 10,608 |
| **rontolisp, `--optimize`, default wasm-GC Preview 1** | **17,012** |
| rontolisp, `--optimize=size` | 16,083 |
| rontolisp, `--no-gc --optimize=size` (reactor, `princ` output) | 1,042 |

**Goal: `pi_approx.lisp`, unchanged, under 6,000 bytes at `--optimize` -- the
DEFAULT level, not `--optimize=size`** -- still printing exactly
`pi = 3.141591653589774`, still byte-identical on all four backends.

Two things that goal is deliberately not:

- **Not a source rewrite.** The comparison is only worth something if every
  language runs the same program. Dropping `format`, printing fewer digits or
  hoisting the loop into a `defun` produces a different measurement. The
  `--no-gc` row is already the "what if we could rewrite it" answer, and at
  1,042 B it is the existence proof that neither the arithmetic nor a
  float->decimal conversion is what costs 17 KB.
- **Not a size/speed trade.** `--optimize=size` exists for those, and on this
  program it buys 929 bytes -- 5.5%. Requiring the default level therefore costs
  about a kilobyte of headroom, not a different strategy. Every lever below is a
  size AND speed win; none of them needs the `=size` level.

## Reproducing

```bash
examples/wasm-size/build.sh          # builds every variant, validates, prints the table
```

Per-function byte sizes come out of the code section directly (`wasm-tools
objdump` for the section split, `wasm-tools print` for the bodies). All the
numbers below are `--optimize`, compiler `c900ada`, and each probe was run to
check it still prints the right thing.

## Where the bytes are

Section split of the 17,012-byte module: code is essentially all of it (types
212, data 124, functions 45, imports 35, the rest under 35). 46 function bodies,
and they are not evenly distributed:

| | bytes | share |
| --- | ---: | ---: |
| the program's own top-level body | 8,607 | 50.6% |
| the other 45 functions | 7,830 | 46.0% |

**Half the module is the five-line program**, and that body declares 152 `eqref`
locals for a program with three variables. The module holds 395 `ref.test`
instructions.

### It is the `~,nF` directive, not the loop and not the digit count

Each row is a whole program compiled at `--optimize`; the last two columns split
its code section into the top-level body and everything else:

| probe | total | top-level | rest |
| --- | ---: | ---: | ---: |
| `(princ "hi") (terpri)` | 504 | | |
| `(princ (length "abcde"))` -- a runtime integer | 426 | | |
| `(princ-to-string (length "abcde"))` | 503 | | |
| the empty `(dotimes (i 1000000))` | 3,060 | | |
| the full Leibniz loop, printing `"done"` | 3,778 | 894 | 2,517 |
| `(princ 1.5)` -- a float | 4,614 | 35 | 4,145 |
| `(format t "~A~%" <float>)` | 4,945 | 210 | 4,292 |
| `(format t "~,2F~%" <float>)` | 16,003 | 7,616 | 7,830 |
| `(format t "~,15F~%" 3.14159165358977)` -- no loop at all | 15,823 | | |
| **`pi_approx.lisp`** | **17,012** | **8,607** | **7,830** |

Read off it:

- **The loop is 3,778 bytes, everything included.** The million iterations, the
  f64 accumulate, the divide, the sign flip and a `princ` -- 894 bytes of body.
  It is not the problem.
- **Printing the answer is 13,234 bytes.** To land under 6,000 with the loop
  where it is, that has to become ~2,200.
- **`~,2F` costs the same as `~,15F`.** The digit count is not the driver: the
  SHAPE of the lowering is.
- Going from `~A` to `~,nF` adds **7,406 bytes of inline top-level code** and
  **3,538 bytes / 22 more runtime functions**.

### Why the lowering costs that

A literal control string is parsed at COMPILE time (`.kb/format.md`,
`LispMacroExpander.expandFormat`), and `~,15F` lowers to eight ordinary forms:

```lisp
(let* ((v (* arg 1.0))
       (neg (< v 0.0))
       (sc (if neg (- 0 (round (* v 1.0E15))) (round (* v 1.0E15))))
       (s (let ((r (princ-to-string sc)))
            (while (< (length r) 16) (setq r (%string-concat "0" r)))
            r))
       (len (length s))
       (ip (subseq s 0 (- len 15))))
  (%string-concat (if neg "-" "")
                  (%string-concat (%string-concat ip ".") (subseq s (- len 15)))))
```

That is a fixed-decimal renderer written in Lisp: scale by `10^n`, `round` to an
integer, render the integer, then punch a decimal point into the string. It is a
reasonable thing to write in Lisp and a bad thing to inline into a caller,
because **every generic operation in it is emitted inline with its full
i31 / `TYPE_BIGNUM` / `TYPE_BIGINT` / `TYPE_RATIO` / `TYPE_FLOAT` ladder at every
site** -- `*`, `<`, `-`, `round`, `length`, `subseq`, `%string-concat`,
`princ-to-string`. Eight forms, 7,616 bytes.

Note also what `round` is for here: turning a f64 into a 16-digit integer so it
can be printed digit by digit. That is what drags the bignum-capable integer path
into a program whose only number is a float.

### The runtime helpers are shared; the inline code is not

Worth knowing before optimising the wrong half. Each generic family costs about
4.2 KB the FIRST time a program reaches it, and almost nothing after that:

| probe | total |
| --- | ---: |
| `(round <integer>)` alone | 5,015 |
| `%string-concat` + `subseq` alone | 4,867 |
| a single `(< <integer> 3)` alone | 4,701 |
| round **and** string ops together | 5,427 |
| round **and** string ops **and** float printing together | 5,527 |

So the 7,830 bytes of "rest" is one shared core plus small increments -- reaching
one more family is cheap. **The expensive half is the 8,607 bytes inlined into
the caller**, which no tree-shaker can ever remove because it is not a function.

## Levers, in order

1. **Give `~,nF` a real renderer instead of an expansion.** Lower it to a single
   call to one fixed-decimal routine that works on an unboxed `f64` and builds
   its digits directly -- no `round` to a bignum-capable integer, no
   `princ-to-string`, no `subseq`, no `%string-concat` chain. `--no-gc` renders a
   float inside a 1,042-byte whole module, so the routine itself is hundreds of
   bytes. Expected: **-7,400 inline and most of the -3,538 of `round`/string
   helpers that only this path needs.** This is the item. Keep
   `FormatRendererTest.staticAndRuntimeRenderingAgree` green: the runtime
   renderer (`macro/format-render.lisp`) must produce the same text, so either
   both paths call the new routine or the table pins that they still agree.
2. **Compact the generic float printer** (the 4,292-byte "rest" behind
   `(princ <float>)` / `~A`). Same existence proof: `--no-gc` does it in a
   fraction of that. After lever 1 this is the largest remaining block.
3. **Type-specialise the counted loop.** The empty `(dotimes (i 1000000))` is
   3,060 bytes; a loop whose bounds are literal integers should carry an i32
   induction variable, not a boxed one with a generic `<` per iteration. Smaller
   and faster, so it belongs at the default level.
4. **`.todo/276`'s shared coercion helpers and temp reuse** (395 `ref.test`
   sites, 152 never-released `eqref` locals in one body). This item is NOT
   blocked on 276 -- the probes above say the ladders are the tail, not the head
   -- but 276 is what stops the next program from rediscovering this, and its
   `_as_f64` helper is the only lever here that might want the `=size` level.

## Is under 6,000 reachable?

| after | estimate |
| --- | ---: |
| today, `--optimize` | 17,012 |
| lever 1 (`~,nF` -> one renderer) | ~6,700 |
| lever 2 (float printer compacted) | ~3,200 |
| **target** | **< 6,000** |

Lever 1 alone lands just above the line; lever 1 + 2 clears it with room. Levers
3 and 4 are then margin, and margin is worth having: the number in
`examples/wasm-size/README.md` should not be one regression away from losing the
row again.

## Done when

- `examples/wasm-size/build.sh` reports `pi_approx_optimize` under 6,000 bytes
  and every one of its ten validation lines still says OK.
- `pi = 3.141591653589774` byte-identical on interpreter / JVM / WASM P1 /
  component. The `ExamplesE2eTest` entries for `wasm-size/` already pin this with
  `equals`, so a per-backend divergence fails.
- `FormatRendererTest.staticAndRuntimeRenderingAgree` and the
  `format-runtime-control-string` ci-spec case still pass -- one directive set,
  two renderings, no drift (`.kb/format.md`).
- Every size in `examples/wasm-size/README.md` re-measured and rewritten in the
  same commit, including both cross-language tables and the "more than half is
  `format`" finding, which this work is meant to invalidate.
- The `hello_world` rows do not regress (518 / 1,672 / 406), and the `ml/` timing
  examples do not regress at `--optimize`.
- `.kb/format.md` records what `~,nF` lowers to now and why, so the next visitor
  can tell whether the expansion is back.

## Non-goals

- Changing `pi_approx.lisp`. A rewritten program is a different measurement.
- The `--component` and `--no-gc` rows. The component's ~1.2 KB canonical-ABI
  floor is a separate budget, and `--no-gc` is already the smallest thing here.
- Speed at the default level. `--optimize` may not trade it away; if some lever
  turns out to cost real time, that variant belongs in `--optimize=size` and the
  6,000-byte goal has to be met without it.
