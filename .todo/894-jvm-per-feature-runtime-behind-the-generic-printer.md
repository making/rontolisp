# The JVM backend's per-feature runtime behind the Scheme generic printer

Difficulty: Medium

Split off from `.todo/826` ("Not a language feature, same area"). The Scheme generic
printer (`%scheme-print`) was measured at 58.8 KB of `.class` against 7.1 KB of wasm, with
pieces on the JVM of `aref` 14 KB, a `do` loop 9 KB, `char` 7 KB, `write-char` 6 KB. That
is the JVM backend's per-feature runtime, not the printer's shape: look there, not in
`scheme.lisp`.

Measurement first:

- Re-measure on current develop (`-o P.class` / `-o p.wasm`): the generic printer, and
  each piece alone in a minimal Common Lisp program.
- For each dominant piece, find what it pulls in (which runtime helpers the JVM backend
  emits for it, and why) and whether a fix at that point exists that benefits every
  program using the feature.
- Output stays identical on all four backends; no performance regression on a path
  touched.
- If the cost is not worth the blast radius, record the numbers and the date in the
  `.kb/` file that owns the mechanism and close on the measurement.
