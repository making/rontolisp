# The spliced runtime `format` renderer keeps every function dispatchable

Difficulty: High

Follow-on to the funcall-dispatch gate that landed with `.todo/260`
(`.kb/optimize-dead-code-elimination.md`, "The funcall-dispatch gate"). The gate
works and is worth **-49.3%** on a program where it applies; this item is about
the programs where it does not.

## The measurement (2026-08-05, `--optimize`, wasm-GC)

| program | before | after | gate |
| --- | --- | --- | --- |
| `md5` via `ql:quickload` | 1,177,653 | **597,641 (-49.3%)** | applies |
| `split-sequence` | 645,453 | 619,722 (-4.0%) | bails |
| `com.inuoe.jzon` | 1,158,253 | 1,118,502 (-3.4%) | bails |
| `cl-ppcre` | 2,513,244 | 2,419,251 (-3.7%) | bails |
| `examples/db/postgres-hello` (`--component`) | 8,392,789 | 8,085,309 (-3.7%) | bails |

The bailing rows keep only the unrelated `_ub_read` saving. Every one of them
bails for the same reason, and `-Drontolisp.debug.dispatchgate=true` names it:

```
[dispatch-gate] every function stays dispatchable because of: INTERN
```

which resolves to ONE form, `format-render.lisp:510`:

```lisp
(defun %fmt-function-designator (name)
  (let ((k (%fmt-colon-index name)))
    (if (< k 0)
        (intern name)                       ; <- here
        ...)))
```

`%fmt-function-designator` implements the `~/name/` directive: it takes the
name OUT OF THE CONTROL STRING at run time and `funcall`s it. So the trigger is
CORRECT -- a program carrying the runtime renderer really can reach any function
by name. It is not a false positive, and the two obvious narrowings were already
measured and rejected (see the `.kb` file and `RuntimeNameProducers`' class
javadoc).

`postgres-hello` additionally trips `read` (some library in the cl-postgres
stack uses `read-from-string`), so it needs both halves fixed.

## The shape of the fix

The renderer is spliced WHOLE, exactly like the library splices `LibraryDefunPruner`
exists for. `%fmt-user-function` / `%fmt-function-designator` are reachable from
the renderer's directive dispatch, so the pruner keeps them, so the gate bails --
for every program that has a runtime `format` control anywhere.

- **Split the `~/.../` arm out of the always-spliced renderer** and inject it only
  when the program can actually reach that directive. The control string is
  runtime data, so this cannot be proven from the source; the honest gate is
  "some format control the compile CAN see contains `~/`", plus a call-time error
  from the arm's stub otherwise -- the same policy `.kb/clack.md` records for
  Preview-1 call-time errors. That is a behavior narrowing, so it needs the
  bilingual doc note and a ci-spec case.
- The `read` half needs its own answer, and it is a different one: `read` produces
  a symbol but only a `funcall` consumes it. The gate is currently
  producer-shaped; a consumer-shaped refinement ("no runtime function designator
  anywhere") is unsound for cl-postgres, which HAS one. What is actually needed is
  the dataflow answer `RuntimeNameProducers` deliberately does not attempt.

## Also on the table (separate, measured)

`examples/db/postgres-hello --component` is 8.2 MB. Where the bytes are, once the
gate is out of the picture (2026-08-05, 2618 functions, 8,139,447 B of code):

- WASM code is **3.1x** the JVM bytecode for the same program (2,014,123 vs
  6,201,390 over the 1060 functions both emit). Integer-heavy library code is far
  worse: `IRONCLAD::UPDATE-SHA256-BLOCK` is 6,746 B on the JVM and 191,320 B on
  WASM (**28.4x**), `MD5:UPDATE-MD5-BLOCK` 14.6x.
- The two causes are both documented speed/size trades, and turning them off
  measures the price: integer fusion emits the tree TWICE (fast + generic
  fallback) -- worth 6.9% of the module -- and unboxed dual-representation locals
  are worth 16.3%. Both together: 8,519,343 -> 6,656,381 (**-21.9%**), with the
  ci-spec pinning tests green either way (they exist to assert the two paths
  agree).
- That is a `--optimize-size` / `-Os` flag's worth of saving, opt-in so no
  program's speed changes by default. It was scoped and NOT taken in the session
  that landed the gate; the numbers above are the whole design input.
