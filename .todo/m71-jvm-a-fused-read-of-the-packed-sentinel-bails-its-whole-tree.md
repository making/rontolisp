# m71. JVM: a fused read of the packed sentinel bails its whole tree

Difficulty: Medium

A fused `aref` of a packed general array bails on `Long.MIN_VALUE` (the nil sentinel,
`.kb/adjustable-arrays.md`), so a `long[]` holding that integer recomputes every tree reading
it through the generic helpers, each Java `long` wrap through the 2^64 - 1 bignum of
`%mask-signed-field`'s arm. SHA-512 pads a message whose length is a multiple of 8 bytes with
that word (`w[8]` for 64 bytes): measured 2026-11-26 (Graal, a digest reused for 20,000
messages, median of rounds 6-11), 64-byte messages 130-175 ms against 54-90 for 63-byte ones;
JFR puts ~45% of the samples in `BigInteger` under `Sha512.block`'s fused methods.

- A read that meets the sentinel answers the list's `Long` raw where it holds one (bailing only
  for nil), measured on the same digest, the 4 MiB digest and the class sizes.
