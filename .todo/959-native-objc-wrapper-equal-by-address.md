# `--native`: two wrappers of one Objective-C object are not `equal`

Difficulty: Medium

On the interpreter and in a compiled class an `objc:` object is a record compared by address
(`LispObjcObject`), so `(equal (objc:send w "self") w)` is T. In a `--native` executable the
wrapper is a defstruct (`objc-native.lisp`, `objc::%object`: address + the `:extern` handle
whose death releases the reference, `.kb/objc.md`, "--native"), and `equal` on structures is
`eq`: the same form answers NIL. An `equal` hash table keyed by objects splits one object into
several keys. `appkit.lisp` keys by `objc:address`, so nothing shipped depends on it; a user
program can.

Interning one wrapper per address is not the fix: the table would hold every wrapper alive
and nothing would ever be released (the reason the handle exists).

## Direction

The comparison has to be the backend's, not the library's: a wasm-GC value kind whose `equal`
and `eql` compare a key field (the address) and whose printer defers to a hook -- e.g. make
the `:extern` box `(struct (field externref) (field i64))` the wrapper ITSELF, with `equal`/
`eql`/`sxhash` arms for it and a print arm calling the library's `#<objc Class>` text. Pin it
with the corpus in `NativeObjcE2eTest` (an `equal` case, and an `equal` hash table keyed by two
wrappers of one object) against the interpreter.
