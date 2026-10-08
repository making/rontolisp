# e50. Clojure rontolisp.wit: records, variants, enums, flags, tuples and lists

Difficulty: High

`rontolisp.wit/import` binds a member only when every value it takes and answers is spelled the
same in Clojure and in the Common Lisp tier (numbers, `char`, `string`, `list<u8>`, handles, an
`option` of those, a `result` answering one), or crosses through the wrapper the lowering already
emits (`bool`, an `option<bool>` argument). Any other member is left unbound and a reference to it is refused,
naming the WIT line (`ClojureWitLowering.refusal`, `.kb/clojure-frontend.md`, "Host boundary").
That keeps most of `wasi:http` and `wasi:sockets` out of a Clojure program's reach.

## What the Common Lisp tier hands over

`WitTypeMapper` (`.kb/wit.md`, "The settled type mapping", "Rich PARAMETERS"). Labels become
UPCASED CL keywords (`WasmComponentImportCompiler` spells `":" + label.toUpperCase()`); a
Clojure keyword is `(:C%KEYWORD "label")`, a map an `equal` hash table, a vector a CL vector, a
set `(:C%SET table)` (`.kb/clojure-frontend.md`, "Values").

| WIT | CL tier | Clojure (to decide) |
|---|---|---|
| `record` | keyword plist `(:PORT 0 :ADDRESS (127 0 0 1))` | map with keyword keys |
| `enum` | keyword `:IPV4` | keyword `:ipv4` |
| `variant` | `(:CASE . payload)`, a payload-less case the bare keyword | `[:case payload]` or `{:case payload}`; the bare keyword |
| `flags` | keyword list (does not cross the component boundary yet) | set of keywords |
| `tuple`, `list<T>` | list | vector |
| `result` argument | envelope `(:OK . v)` / `(:ERR . e)` | to decide |
| `result` error arm | `rontolisp:wit-error` with `:payload` | today the condition itself; `ex-data` carrying the payload? |

## Plan

1. Settle the Clojure value per row (no oracle: the reference page is the contract).
2. Carry the full shape through `ClojureBoundary.Type` (record fields, variant cases, enum and
   flags labels, tuple elements); today it holds the representation, one element and the WIT
   spelling only. `WitImportDirective.describe` builds it.
3. Convert in the lowered wrapper (`ClojureWasmLowering.importWrapper`, the `bool` crossing's
   place), recursively by shape, through `clojure.lisp` runtime functions referenced only when
   used, so a program without a rich member stays byte-identical.
4. `rontolisp.wit/provide` on the interpreter and the JVM: the provider sees the boundary's CL
   values today; convert there too so a Clojure provider sees Clojure values (the member's shape
   is known: `provide` names an imported interface).
5. World exports stay out: the CL export boundary itself carries only primitives
   (`WitExportDirective.designator`).
6. Tests: every row on the interpreter, the JVM and `--component` against a real host interface
   (`wasi:sockets/types` as `WasmLispCompilerIntegrationTest` does); a P1 core module refuses as
   for CL. Docs: `doc/{en,ja}/clojure/reference/wit.md` "What crosses", `semantics.md` refusal
   row.

`async func` members and exports, and `:async` on `rontolisp.wasm`, stay refused until the
Clojure front end has a future (`future`/`promise` are refused, `doc/en/clojure/semantics.md`,
"Not yet").
