# `objc`: blocks from Lisp closures, on the new base

Difficulty: High

Depends on .todo/a72 (the callback shapes a block's invoke function needs). Nothing here calls the
old verbs or the old bridge (.todo/a71).

A selector with a block parameter (`@?`) is refused by name today (`am.ik.objc.TypeEncoding`,
`encoding.rs`). That closes off completion handlers (`NSURLSession`, `NSWorkspace`, Core
Spotlight), enumeration (`enumerateObjectsUsingBlock:`) and libdispatch.

## API

LispWorks' `OBJC` package has no block interface (it lives in its FLI, which rontolisp does not
have). Use these names in `objc`, so the package stays one vocabulary:
`define-objc-block-type`, `make-objc-block`, `free-objc-block`, `with-objc-block`,
`call-objc-block`, `objc-block`, `objc-block-pointer`, `objc-block-live-p`. Decide whether a Lisp
closure passed where the encoding says `@?` may be wrapped implicitly -- the block's own signature
is not in the encoding.

## Mechanics to settle

- The block literal (`isa`, flags, reserved, invoke pointer, descriptor with copy/dispose helpers)
  in native memory, `Block_copy` when the callee keeps it, and when a Lisp-made block may be freed.
- **Foreign threads.** A completion handler usually runs on a libdispatch worker. On the JVM an
  FFM upcall attaches that thread and runs the closure there, concurrently with the Lisp thread --
  state the interpreter's rules for that (`.kb/dynamic-special-variables.md`). On `--native` the
  module runs on thread 0 and a wasmtime `Store` cannot be entered from another thread
  (`.kb/objc.md`, "--native"): the invoke function must queue the call to thread 0 and answer only
  for `void` blocks, or refuse.
- Native binary: the invoke function's shapes join .todo/a72's upcall registration.

## Done when

An `NSURLSession` data task with a completion handler, `enumerateObjectsUsingBlock:` and a
`dispatch_async` to a serial queue run on the interpreter, JVM class output and `--native`, each
checked on the thread the block actually arrives on.
