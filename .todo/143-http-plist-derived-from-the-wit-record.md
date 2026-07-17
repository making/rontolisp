# The `http-handler` request/response plists should be DERIVED from the WIT record

**Status:** open, unstarted. Carved out of todo-124 (the WIT-as-universal-IDL anchor)
when it was closed on 2026-07-17. It is the one bullet of that anchor's "what gets absorbed
afterwards" list which is **not** actually discharged: 124's table credited it to
todo-135, but 135 delivered the other half.

## What 135 did, and what it did not

todo-135 put the serve/fetch GLUE through WIT: `http.lisp` rides a wit-imported
`wasi:http@0.3.0`, and every hand-written WAT HTTP adapter is deleted (`.kb/fetch-http.md`,
`.kb/wit.md`). That is real and it is done.

What it did not do is the bullet's actual wording -- "the request plist **derived** from
the WIT `record` instead of hand-shaped differently per backend". The plist SHAPE is still
hand-written, in **two** places, and they have to agree by inspection:

- **Interpreter + JVM** -- Java. `eval/HttpHandlerSupport.Request.of` splits path/query,
  `LispEvaluator.invokeHttpHandler` builds the plist, and
  `codegen/jvm/JvmHttpHandlerRuntimeBuilder` emits bytecode that builds the same plist as
  cons cells in the runtime value rep.
- **`--component`** -- Lisp. `src/main/resources/am/ik/rontolisp/eval/http.lisp` calls the
  wit-imported accessors and then writes
  `(list :method method :path path :query query :headers headers :body body)` by hand.

So the boundary is WIT-described and the plist is not. `.kb/fetch-http.md` records the
duplication honestly ("the split at the first `?` happens once in
`HttpHandlerSupport.Request.of` (interpreter and JVM inherit it) and in the synthesized
`%http-request` Lisp helper on the WASM component path") -- which is exactly the
two-places-to-maintain drift `wit-import` exists to kill, surviving one level up from the
imports it did kill.

## Why it is worth doing (and why it is not urgent)

The shape is small and stable (`:method :path :query :headers :body` /
`:status :headers :body`), so the drift risk today is low -- which is why it was never the
thing blocking anything. It matters as the LAST place the HTTP surface is hand-shaped, and
because `wasi:http`'s own record is the authority: a field added upstream should not need
two independent edits plus a doc.

## The work (sketch, not a decision)

1. Decide what "derived" means here. A record -> plist mapping already has a house
   convention (`record` = keyword plist; `compiler/WitTypeMapper` + `WitTypeMapperTest`
   are the machine-checked source of truth), so the question is whether the DERIVATION is
   compile-time codegen from the parsed WIT, or a generated Lisp helper the way
   `wit-import` already synthesizes defuns.
2. Whatever is chosen must reach all four backends, or the change is a third hand-written
   shape rather than a replacement. Note `HttpHandlerSupport` is `public` for the browser
   playground's substitution (`.kb/fetch-http.md`), so the interpreter's shape is not free
   to move without checking `src/web/java`.
3. The response direction (`:status :headers :body`) has the same duplication and should go
   the same way in the same change.

## Definition of done

- The plist shape is written once, and a `wasi:http` record field change does not need a
  hand edit per backend.
- All four backends verified (the component path uses a different I/O adapter -- see
  CLAUDE.md's four-backend rule), plus `-Pweb compile` for the playground substitution.
- `.kb/fetch-http.md`'s "happens once in ... and in ..." paragraph becomes untrue and is
  rewritten.
