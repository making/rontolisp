# The small builders and `JvmLispCompiler`'s own code: the extra tools

The recipe is `.kb/jvm-method-size-limits.md`, "How a slice moves", with a86's raw-list tools.

- `prep.py FILE...` -- run before a86's `raw.py` on a file that spells the helpers qualified
  (`JvmRuntimeBuilder.emitU2(...)`, `JvmRuntimeBuilder.patchBranch(...)`,
  `new java.util.ArrayList<>()`): `raw.py` only knows the unqualified idiom.
- `gates/*.lisp` -- programs reaching `%random-byte` (SecureRandom), `tls-connect :insecure`
  (the trust-all stubs and the instance `<init>`), and a `<clinit>` with every piece (the
  `_hasComplex` probe, the condition and `_d$` ThreadLocals, the stream table, a bignum, the
  layouts, the reader's struct directory) plus the quote pool, UNSUPPLIED and the mutex.
  Compare with a85's `Cmp.java` over the directory.

A builder whose exported references feed code still written as bytes over the pool wrappers
(the `ffi:` bridge and `JvmRuntimeBuilder.BridgePrint`) skips a86's `pool.py`: convert the code
only and put `.entry()` at the boundary (a85's `fix.py`).
