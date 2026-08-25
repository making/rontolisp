# 516. A variadic selector crashes the process instead of signalling

Difficulty: Medium

`objc:send` promises that a wrong selector, arity or operand type is an `ObjcException` ->
a Lisp `error`, never a crash (`.kb/objc.md`). A VARIADIC selector breaks that promise.
Found 2026-08-25 while writing `examples/macos/objc-runtime.lisp`:

```lisp
(objc:send "NSArray" "arrayWithObjects:" (objc:string "a"))
```

```text
# SIGSEGV (0xb) at pc=..., pid=..., tid=...
# C  [libobjc.A.dylib+0x7a28]  objc_retain+0x10
```

Under `java -jar`, on the interpreter. The native binary and a compiled class take the
same route, so all three die the same way.

**Why.** The runtime's type encoding does not say a method is variadic:
`+[NSArray arrayWithObjects:]` is declared `@@:@`, exactly like `+[NSArray
arrayWithObject:]`. `TypeEncoding` therefore builds a non-variadic
`FunctionDescriptor`, and on the Apple arm64 ABI a variadic argument is passed on the
STACK while a fixed one is passed in a register. The callee walks its `va_list` off a
stack slot nobody wrote and retains whatever is there. Nothing in the binding can see this
coming: the declaration it trusts is complete for every other method and silently
incomplete here.

**The affected selectors are a small, known set** -- the nil-terminated Foundation
constructors and the format-string family: `arrayWithObjects:`, `initWithObjects:`,
`setWithObjects:`, `dictionaryWithObjectsAndKeys:`, `initWithObjectsAndKeys:`,
`stringWithFormat:`, `initWithFormat:`, `localizedStringWithFormat:`,
`predicateWithFormat:`, `raise:format:`, `appendFormat:`. (`arrayWithObjects:count:` is
NOT one: it takes a real array and a count.)

**Two rungs, in order.**

1. *Refuse by name*, which restores the invariant. The binding already refuses blocks,
   unions and bitfields by name (`TypeEncoding`); a table of variadic selectors is the
   same policy, and the message should name the fixed-arity alternative
   (`arrayWithObjects:` -> `arrayWithObject:` / `arrayWithObjects:count:`). Cheap, and it
   turns a process kill into a Lisp `error`.
2. *Support them*, which is what a user actually wants for `stringWithFormat:` and
   `dictionaryWithObjectsAndKeys:`. FFM can make a variadic call --
   `Linker.Option.firstVariadicArg(n)` -- so `objc:send` could accept arguments BEYOND the
   declared arity for a selector in that table, marshal each as `@` (or by the format
   string, which it must not parse), append the `nil` terminator itself, and bind a
   descriptor per (shape, extra count). Note the native binary's closed table
   (`reachability-metadata.json`): a variadic descriptor is a distinct registration, so
   only a bounded number of extra arguments can be served there -- pick a cap, register
   it, and signal above it.

Both rungs need a case in `TypeEncodingTest` (the table is data, so it is testable off a
Mac) and one in `ObjcInteropTest` for the signal. Rung 2 adds a row per shape to
`ObjcNativeImageForeignConfigTest`.
