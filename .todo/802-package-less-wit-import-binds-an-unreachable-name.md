# A package-less `wit-import` binds a name no call site can reach

**Status:** open. Found 2026-09-12 while measuring
[`794`](794-no-gc-component-host-imports.md); the guide's own first example reproduces it.

Difficulty: Medium

## The finding

`doc/en/guides/wit-contracts.md` ("`rontolisp:wit-import`") documents the directive without
`:package`:

```lisp
(rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")
(defun add10 (n) (add-ints n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

Through the CLI that program fails on every backend:

| Target | Result |
| --- | --- |
| interpreter (`java -jar`, with a `wit-provide`) | `The function ADD-INTS is undefined` |
| `-o main.wasm --no-wasi` (wasm-GC) | `warning: the function ADD-INTS is undefined; compiled as a call-time error` |
| `-o main.wasm --no-gc --no-wasi` | `unsupported operation 'ADD-INTS' in function 'ADD10'` |

With `:package m` and `(m:add-ints n 10)` every target works. The lowering keeps a
package-less binding's bare WIT name verbatim -- `|add-ints|`, case-preserved
(`WitImportDirectiveTest.bindsTheNamesInTheCurrentPackageWithoutThePackageOption` pins
exactly that) -- while the reader upcases the call site to `ADD-INTS`, so the defined
symbol and the referenced symbol never meet. The packaged form works only because the
resolver matches a qualified reference against the package's export table.

Every existing test of the WASM lowering uses `:package`; nothing runs the package-less
form end to end, which is how the documented example stayed broken.

## What to do

Decide what a package-less binding's symbol SHOULD be -- the reader's spelling of the WIT
label (`ADD-INTS`) is what a call site produces, and is what `:package`'s export match
effectively gives the packaged form -- and make the lowering produce it on every backend
(the interpreter's provider defun, the JVM stub, both `wasm-import` lowerings and the
`%component-import` binding), or refuse the package-less form with a message that names
`:package`. Then add the guide's example as an E2E on at least the interpreter and one
WASM backend, so the doc and the code cannot drift apart again.

## Touch points

- `compiler/WitImportDirective.java` (`directive.pkg() == null ? member : ...`, four sites)
- `WitImportDirectiveTest.bindsTheNamesInTheCurrentPackageWithoutThePackageOption`
- `doc/en/guides/wit-contracts.md` / `doc/ja/guides/wit-contracts.md` (the example)
- `.kb/wit.md`
