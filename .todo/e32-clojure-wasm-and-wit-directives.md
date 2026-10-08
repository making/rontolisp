# e32. Clojure surface for wasm-import / wasm-export / wit-import / wit-export

Difficulty: High

A Clojure program cannot reach the four host-boundary directives: Clojure has no way to name
a Common Lisp function (`.kb/clojure-frontend.md`, "Ring adapter"), so a `.clj` file can neither
export a function to a host nor call one. Give it a Clojure-idiomatic surface that LOWERS to the
existing directives -- no new export/import path on any backend (`.kb/wasm-import.md`,
`.kb/wit.md`, `.kb/wasm-export-no-wasi.md`).

## Surface (proposed; settle in step 1)

Built-in namespaces, the `ring.adapter.rontolisp` precedent (lowered by a `Clojure*Lowering`
slice, not a library):

```clojure
(ns app.core
  (:require [rontolisp.wasm :as wasm]
            [rontolisp.wit :as wit]))

(wasm/defimport add {:from "host" :as "add" :params [:int :int] :returns :int})

(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] (add n 10))
;; or a top-level form, for a defn defined elsewhere:
(wasm/export add10 {:as "add10" :params [:int] :returns :int})

(wit/import "wit/kv.wit" {:interface "wasi:keyvalue/store@0.2.0" :as kv})
(kv/bucket-set (kv/open "cache") "hello" "world")
(wit/provide "wasi:keyvalue/store@0.2.0" (fn [member & args] ...))

(wit/export "wit/greeter.wit" {:world "greeter"})
```

Options are a map, types are keyword vectors (the CL designators, so `BoundaryType` stays the
one vocabulary); `:async`, `:param-names`, `:field-style` carry over by name.

## What the lowering must handle (each is a reason this is not a macro)

1. Names. An identifier is `c%name` / `|c%ns/name|`. Every lowered directive must pass `:as`
   (or the WIT label) explicitly, else `Decl.exportName()` / `unqualifiedMember` hands the host
   `c%add10`. `wit/export` checks the program defines each world export: map the WIT label to
   the mangled defn name inside `WitExportDirective` through a naming hook, not by restating it.
2. Pre-scan. Pass one must know every name a `defimport` / `wit/import` binds (an unknown head
   is an error or a dispatcher call). `wit/import` therefore reads the WIT at lower time
   (`SourceLoader`) and binds into the alias namespace; give `WitImportDirective` the naming hook
   rather than synthesizing a `defpackage` the Clojure side cannot see.
3. Values at the boundary. `false` is a non-NIL symbol: a `:bool` export result of `false` would
   cross as true, a `:bool` import answers `T`/`NIL` where Clojure wants `true`/`false`. `:s-expr`
   prints/reads with the CL printer, which does not round-trip vectors, maps, keywords or `false`:
   either convert through the Clojure printer/reader (`%clojure-read-string`) or refuse it by name.
   Decide where the conversion lives (lowered wrapper vs a boundary flag) and that a program
   without one stays byte-identical.
4. Export shape. A multi-arity or variadic `defn` does not lower to a fixed-arity defun; refuse
   it naming the form (wit-export already refuses `&optional`/`&rest`).
5. WIT rich types (records, variants, options, lists, results) in the Clojure tier: maps with
   keyword keys, vectors, keywords, `nil`. Measure what the CL tier hands over first; if the
   mapping is large, ship primitives + `string` + resources first and record the rest as a new
   todo.
6. `wit/provide` on the interpreter/JVM: a Clojure fn as the provider; the member arrives as a
   string. On wasm it is dropped, as for CL.
7. Refusals by name: `--no-gc` is already refused for Clojure; `:async` until a Clojure future
   maps onto `async-defun`'s; `--scaffold-wit` emits CL source (a `.clj` scaffold is its own item
   if wanted).
8. Ordering: `WitImportInliner` runs before `UserMacroExpander`, `WitExportInliner` after
   (`.kb/wit.md`, "Pass order"). The Clojure lowering happens at the seam, before both; confirm
   the lowered top-level forms land where both expect, and that `wit/export` may sit anywhere in
   the file (the CL rule "put it at the END" comes from the interpreter's special form).
9. A session (REPL) and the playground (`RontoPlayground` runs the WIT inliners through an
   injected `SourceLoader`).

## Plan

1. Read `.kb/clojure-frontend.md` (Where it sits, Values, Namespaces, Java interop),
   `.kb/wasm-import.md`, `.kb/wit.md`, `.kb/wasm-export-no-wasi.md`, `.kb/source-language.md`.
   Settle the surface above; there is no oracle (`clj` has no such namespace), so the doc page
   is the contract and `clojure-spec.yaml` cannot diff it.
2. `ClojureWasmLowering` (`wasm/defimport`, `wasm/export`, `:wasm/export` metadata) to
   `rontolisp:wasm-import` / `wasm-export`, with points 1, 3, 4.
3. `ClojureWitLowering` (`wit/import`, `wit/export`, `wit/provide`) through the naming hooks in
   `WitImportDirective` / `WitExportDirective`, with points 2, 5, 6.
4. Examples: a Clojure counterpart of the `wasm-host-boundary` guide's `add`/`add10` and of the
   greeter world (`examples/clojure/`, `examples.yaml` legs), `--emit-js-glue` and `--emit-wit`
   on one of them.
5. Tests on every backend a Clojure program runs on: interpreter (stubs / provider), JVM
   (stubs / provider), wasm-GC P1 + `--no-wasi` driven by node, `--component` under wasmtime
   (wit-import's `%component-import`, wit-export). Pin byte identity of each lowering against the
   hand-written CL directive block, as `WitImportInlinerTest` does.
6. Docs: `doc/{en,ja}/clojure/reference/` pages (+ `_catalog.yaml`), a pointer from
   `doc/{en,ja}/guides/wasm-host-boundary.md` and `wit-contracts.md`; `.kb/clojure-frontend.md`
   lowering-table rows and a section; the naming hooks in `.kb/wit.md`.
