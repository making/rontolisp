# serve mode accepts user WIT imports (an HTTP server with a real store)

**Status:** open, unstarted. **Small** — plumbing, no new machinery. Closes the last item
of `.todo/52` (wasi:keyvalue).

## The hole

A component built in **serve mode** (`rontolisp:http-handler` + `--component`, run under
`wasmtime serve`) cannot import a user WIT interface:

```console
$ rontolisp kv-server.lisp -o server.wasm --component
rontolisp:wit-import cannot be combined with rontolisp:http-handler yet: a serve-mode
component's imports are the fixed wasi:http surface
```

So the two halves that most obviously belong together — **an HTTP server whose state lives
in a real key-value store** — cannot be written. Today you get one or the other:

- an HTTP server whose state is a process-local hash table (dies with the instance), or
- a `wasi:keyvalue` program that runs once and exits.

## Why it is small

The restriction is not structural; it is **unimplemented plumbing**. `WasmComponentBuilder`
gained `appendUserImports` (instance type + component import + per-function alias +
`canon lower` + a synthesized core instance, everything shifting by the user-import counts)
and it is wired into the three non-serve variants (`buildBase` / `buildHttp` / `buildSock`).
`buildServe` — which delegates to `WasmServeComponentBuilder` — simply never got it, and
`WasmLispCompiler` raises the error above rather than emitting something wrong.

The work is to do for `WasmServeComponentBuilder` exactly what was done for the other
three: derive its next-free type / import-instance / component-func / core-func indices,
call `appendUserImports`, thread the extra instantiation args into the rontolisp core
instance, and shift the downstream constants. The serve variant has TWO adapters (the
preview1 bridge and the http adapter), so the index bookkeeping is fussier than the others
— that is the whole of the difficulty, and it is the reason this was not just folded into
the component-import work.

Note `--emit-wit` must pick the imports up too (`WitEmitter.emit` already takes them; the
serve call site passes `List.of()`).

## Definition of done

- A program with BOTH `rontolisp:http-handler` and `rontolisp:wit-import` compiles under
  `--component` and runs under `wasmtime serve -S keyvalue=y ...`, serving requests whose
  state survives in the store.
- The example: a page-hit counter as an HTTP server — i.e. `examples/wit/keyvalue`'s
  program, but served (`.todo/52` has wanted this since 2026-07-04; its `kv-server.lisp`
  sketch keeps state in a process-local hash table precisely because it had no store).
- An import-free serve component stays **byte-identical** (the stash-dance proof, as for
  the other three variants).
- `--emit-wit` on a serve component shows the user imports.
- `.kb/wasi-component.md` + the "cannot be combined" paragraphs in
  `doc/{en,ja}/compiling/wasm.md` and `rontolisp-wit-import.md` updated.

## Relationship to `.todo/135`

`.todo/135` makes serve's HTTP glue itself a WIT import, which would give this for free —
but it is a much larger restructure and needs `.todo/133` first. **This todo is the cheap
path to the capability**, and its plumbing is not wasted: `appendUserImports` is what
`.todo/135` would use as well.
