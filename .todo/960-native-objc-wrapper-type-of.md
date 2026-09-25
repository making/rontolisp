# `--native`: an Objective-C wrapper's `type-of` and `structure-object` answer differ

Difficulty: Low

macOS aarch64 only (the one `--native` target with an Objective-C host).

An `objc:` object is `eq`/`eql`/`equal`/`equalp` by address on every backend (`.kb/objc.md`,
"--native"), but its TYPE still reads differently. On the interpreter and in a compiled class
(`LispObjcObject` / `JvmObjcHandle`) `(type-of w)` is `T` and `(typep w 'structure-object)` NIL;
in a `--native` executable the wrapper is the defstruct `objc::%object`, so `type-of` answers
`OBJC::%OBJECT` and `structure-object` T.

## Direction

Pick one answer for all four and pin it in `objc-native-corpus.lisp` (`NativeObjcE2eTest`, against
the interpreter). `T` is no type name a program can use; a named type (e.g. `objc:object`, with
`typep` agreeing) on the interpreter and the JVM, and the wasm-GC `type-of` of the `objc::%object`
layout reporting that name, is the likely shape. Check how `type-of` reaches a struct's name on
wasm-GC before choosing.
