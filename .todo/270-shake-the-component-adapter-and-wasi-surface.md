# Shake the component adapter and the fixed WASI surface

Difficulty: High

Split out of todo-268's third section, which closed the two CORE-module halves (the type
section and the string blob) and left the component wrapper untouched.

After that pass, `(print "Hello World!")` is:

```
   645  Preview 1 (--optimize)
 7,690  --component (--optimize)
         653  shaken core module
       3,624  P1 adapter module          (adapter.wat)
         158  shared-memory module
      ~3,255  component types/imports/aliases/canonical functions
```

The core is now 8% of the component. Everything else is fixed cost that does not depend
on the program at all.

## The adapter

`adapter.wat` carries all nine WASI Preview-1 shims however few the core still imports
(the core shake already drops its unused imports, so the surviving import set is exactly
known by the time the wrapper is built). The adapter's exports are bound BY NAME
(`core:instantiate <module> vec((name, instanceidx))`), so dropping the ones nothing
imports composes exactly like the core shake -- the same `WasmTreeShaker` should run on
the adapter with the core's post-shake import names as roots. Note the adapter is a WAT
blob assembled at build time, not emitted by the compiler; check how it reaches the
component (`WasmComponentBuilder`, `adapter.wat`) before deciding where the shake goes.

## The component type/import surface

The wrapper declares the full fixed WASI surface (`wasi:cli`, `wasi:filesystem`,
`wasi:clocks`, `wasi:random`, ... -- the 1,192-byte type section in the middle is the
filesystem one) regardless of what the adapter, and hence the core, still needs. Emitting
only the interfaces the surviving imports reach would roughly halve the remaining
metadata. `WitEmitter` produces the WIT text from the same list, so the two must move
together or the emitted `.wit` stops describing the component.

Read `.kb/wasi-component.md` first, and `.kb/optimize-dead-code-elimination.md`
("Why the component path is safe") for what the core shake already relies on.

## Non-goals

The `--component` floor for a REAL program (a fetch/serve handler, a database client) is
dominated by the core, not by this; the win here is the small-module floor and the
wasmCloud-shaped cold start. Do not trade core-module correctness for it.
