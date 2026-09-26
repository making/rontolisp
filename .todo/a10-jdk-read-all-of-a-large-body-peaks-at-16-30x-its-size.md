# JDK backends: `read-all` of a large body peaks at 16-30x its size

Difficulty: High

Measured 2026-09-26 (`.kb/fetch-http.md`, "Throughput", the binary `read-all` row): a 256 MiB
binary body read with `(rontolisp:await (rontolisp:read-all (getf res :body)))` peaks at 7.5 GB RSS
on the interpreter (13 s) and 4.4 GB on the JVM (6.2 s), against 1.1 GB on `--native` (3.7 s).

The suspected cause is the representation: a packed `(unsigned-byte 8)` vector is a `long[]` on
both JDK backends (`LispIntVector`; the JVM's `long[]{8, ...}`), eight bytes per octet, and the
chunks, the joined vector and the decoder's `byte[]` copy are all live at once. Measure first --
heap histogram at the peak -- before choosing between a byte-width storage for width-8 vectors
(wide blast radius: every packed-vector reader on both backends) and narrower fixes (decode
the chunks without joining them, drop the `byte[]` copy).
