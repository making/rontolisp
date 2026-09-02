# The CFFI guide's cl+ssl row still says a rontolisp stream is an integer

Difficulty: Low

Reported 2026-09-02 while landing `.todo/632`, which had to read the same guide.

`doc/en/guides/cffi.md`'s bindings table (and its `doc/ja` twin) says of cl+ssl:

> It is still not a usable HTTPS client: cl+ssl picks its BIO with
> `(etypecase socket (integer ...) (stream ...))`, and a rontolisp stream **is**
> an integer, so it tells OpenSSL to use the stream handle as a socket descriptor.

`.kb/cffi.md` records that closed in two steps: `.todo/552` made a Gray stream
answer `streamp` / `(typep x 'stream)` and stopped the compile paths pruning such
an `etypecase`'s `stream` arm, and `.todo/553` made every OPEN stream a
self-describing value, so a rontolisp socket handed over DIRECTLY reaches the
Lisp BIO by dispatch -- no wrapper, no patching. The same section also records a
second fix the guide never learned about: an `(eql +constant+)` specializer
resolving through the `defconstant` table (2026-08-27), which is what makes
`x509.lisp`'s per-ASN.1-type methods apply.

## What to do

**Measure before rewriting the row.** The kb says the wall moved to the stream
model and that `.todo/553` carries what is left; it does not say cl+ssl is now a
usable HTTPS client. Run a real HTTPS request through cl+ssl on the native binary
and on `java -jar` and write down where it actually stops, then make the row say
that. If it does complete, the row's conclusion (the bundled `cl+ssl` shim stays
the default, because it needs no OpenSSL and works on the WASM component backend
where CFFI never will) may still be the right recommendation for a different
reason -- keep the recommendation, fix the reason.

While in that table, check the other rows against `.kb/cffi.md` for the same
kind of drift: every row is a measurement with a date behind it, and three
bindings' blockers were closed after the guide was written.

Both language trees change in the same commit (`doc/en` + `doc/ja`, byte-identical
code fences).
