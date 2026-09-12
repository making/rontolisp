# The lowering shapes the residue census names

Difficulty: High (several independent chunks, each an expander or emitter change with its
own semantics to keep; land them one at a time, largest first)

Third of the three items `791` item 3's measurement split into
(`.kb/optimize-dead-code-elimination.md`, "What an external optimizer still finds, and what
it is made of"; scripts in `.todo/artefacts/791-module-level-slack-globals-types-data-hooks/`).
What is left of the residue after `.todo/798`'s peepholes is not what a post-emit pass can
reach: it is HOW a few very common forms are lowered. Measured 2026-09-12 on the
hello-clack Worker (`--optimize=size`, 815,414 B) and `zlib` (90,874 B), by shape:

1. **The `&key` prologue** (`LambdaLists.keyCellScan` / `unknownKeyCheck`): every keyword
   parameter is its own inline `do` loop over the rest list, ~140 B of wasm each; 701 of
   them on the Worker (~10% of the module), 22 on `zlib`. A shared defun taking the rest
   list and the keyword (and one taking the known-keyword list for the check) makes each
   ~10 B -- on the JVM too, since `LambdaLists.expand` is shared by every backend. Keep the
   semantics: the upcased twin, `:allow-other-keys` in both spellings, the odd-length
   complaint's precedence, `%program-error`. The interpreter expands at lambda creation, so
   the helper has to exist for it as well (`.kb/lambda-lists.md`, `.kb/adding-primitives.md`).
2. **Temps are never recycled** (`Ctx.allocTemp` is `nextLocal++`): `USOCKET:SOCKET-CONNECT`
   declares ~120 locals, 31 Worker functions have more than 128, and 11,646 `local.*`
   immediates on the Worker are 2-byte indices (240 on `zlib`). A per-function scratch slot
   handed out and released around each use (or, byte-level, a liveness-free renumbering
   that puts the most-read locals below 128) is worth up to ~11 KB there. **Trap**: a
   scratch slot cached on `Ctx` must be reset wherever `nextLocal` is
   (`WasmToplevelEmit`, `WasmAsyncEmit.freshCtx`, the import/export wrappers, the defun
   and lambda contexts) -- a stale slot after a chunk cut collides with a later local.
3. **An assertion in value position**: `if (result eqref) call err else ref.null eq end;
   ref.is_null` (3,304 on the Worker, 303 on `zlib`, ~8 B each) -- a `(when c (error))`
   whose nil result is then tested by the `or`/`and` it sits in. Find the expander that
   produces it (the `do`-loop `endp` checks are the visible case) and emit the check as a
   statement.
4. **A boxed variable is built empty, then set** (`ref.null; struct.new $cell; local.set k`
   then `local.get k; V; struct.set`): 204 on the Worker, 45 on `zlib`, 33 in
   `%INFLATE-STATE-MACHINE` alone, ~8 B each. `let` can build the cell with its value;
   `labels`/`letrec` needs the cell first and is the reason the shape exists.
5. **Sparse arity ladders**: a `br_table` names every funcId from 0 to the largest, one
   label each; `zlib`'s `_dispatch_0` is 517 B for one callable at 416 (3,785 default labels
   over its six ladders, ~1.5 KB); the Worker's are dense (6). Bias the table to the
   smallest live id (`funcIdBias` already exists for the pages) or select by comparison
   below some density. `.kb/wasm-function-body-size.md` owns the paging invariants.
6. **A character or float literal compared through its box**: `(char= c #\~)` builds
   `struct.new $char 126`, casts and reads it back, then boxes both codes as i31 to `and`
   them (`%FMT-CONTROL`, 228 literal box-then-unbox sites on the Worker) -- the literal's
   code is a compile-time constant and the comparison an `i32.eq`.
7. **Statement values**: a `setq`'s value dropped (`local.tee; drop`, 1,197), nil-valued
   statements (`ref.null eq; drop`, part of 1,416) -- `.todo/798` removes the bytes; the
   emitter could stop producing them by compiling a non-final `progn` form in a
   value-free mode.

Each chunk is measured the same way: the census scripts before and after, on the
size-report programs and the Worker. The JVM backend shares 1 and 6 (the expander is
common) and has its own twin of 2.
