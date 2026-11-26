# m59. Java: an unsigned shift by a computed count goes through a bignum

Difficulty: Medium

`x >>> n` of a `long` with `n` no literal lowers to `(ash (logand x 18446744073709551615) (- ..))`:
the 2^64 - 1 literal declines integer fusion on both compilers, so the `logand` is the generic
one over a `BigInteger` for every negative `x`. Measured 2026-11-26 (JFR, C2): a SHA-512 digest
reused for 20,000 messages of 64 bytes spends most of its time in `BigInteger.getInt` under
`_logand`, from `Sha512.output`'s `state[i >>> 3] >>> (56 - 8 * (i & 7))`; a message there costs
6-10x what one compression of a 4 MiB digest does. The literal-count form is already
`(logand (ash x -k) mask)` and fuses.

- A lowering whose every operation stays within 64 bits (a computed mask, or an operation of
  the fused tree for the logical right shift, on both compilers), measured on the reused digest
  and the Java corpus's sizes.
