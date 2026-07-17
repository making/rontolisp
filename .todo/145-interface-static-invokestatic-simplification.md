# Simplify the interface-static invokestatic workarounds now that v61 allows them

Carved out of the class-version-61 upgrade (`.kb/stackmap-augmenter.md`).

At class version 50 an `invokestatic` whose target is an interface method was
illegal, which forced workarounds in the hand-assembled JVM runtime. The bump
to version 61 (interface-statics are legal from v52) makes them removable:

- `JvmHttpHandlerRuntimeBuilder`: the injected `handle(Request)` returns
  `new Response(status, Collections.emptyList(), body)` because `List.of()`
  was unusable (`.kb/fetch-http.md`). Could now call `List.of()` directly --
  note `ByteCodeWriter`/`ConstantPool` must emit the InterfaceMethodref
  constant (tag 11) + the `invokestatic`-on-interface form for this to work;
  check the assembler supports it before switching.
- Survey the other `Jvm*RuntimeBuilder`s for the same avoidance pattern
  (anywhere `Collections.*` or a concrete class was chosen purely to dodge an
  interface-static call).

Low priority: the current code works; this is cleanup only. Verify with
`HttpHandlerJvmTest` +
`JvmLispCompilerTest.compileHttpHandlerImplementsHandlerInterface`.
