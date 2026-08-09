# rontolisp:random-bytes

`(rontolisp:random-bytes count)`

Returns a vector of `count` cryptographically strong random bytes (each an integer 0-255). Unlike [`random`](random.md), which is an ordinary pseudo-random generator, every byte comes from the platform's cryptographic entropy source: `java.security.SecureRandom` on the interpreter and the JVM, the WASI `random_get` host function on both WASM backends (`wasi:random` under `--component`). That is what makes it suitable for nonces, salts and session identifiers. A `--no-wasi` module has no entropy source, so the call signals there unless the build passes `--host-random` ([randomness guide](../../guides/random.md)).

```lisp
(length (rontolisp:random-bytes 16)) ; => 16
```

The values differ on every call, so only the length is shown here.
