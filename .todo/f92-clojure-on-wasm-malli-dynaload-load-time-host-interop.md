# f92. Clojure on wasm: malli/dynaload load-time host interop past f84

Difficulty: Medium

Found closing `.todo/f83` (2026-10-13; the f83 worker claimed number f91 for this
but never filed it, so it is re-filed here — f91 stays a gap, reuse is not allowed).
With f83's wide-lambda routing the malli probe compiles on both wasm backends but
stops at load-time host interop: `(Object.)` behind `dynalock`, and `JAVA:FIELD`
while loading `malli.core`. f84 covered load-time `System/getProperty` and
`(HashMap. capacity load)` + `.putAll`; this is the remainder.

## Plan

1. Lower or refuse-with-oracle-words the load-time shapes the malli/dynaload load
   path names (`(Object.)` construction, the `JAVA:FIELD` sites in `malli.core` load),
   on both wasm backends; host backends untouched.
2. Re-run the full malli probe (validate, explain + humanize, decode, form) on all
   four backends; docs en + ja.
