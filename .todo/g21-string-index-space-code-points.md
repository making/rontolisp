# g21. String index space: pin the code-point policy past the BMP

Difficulty: Small

Strings are indexed by code point, not UTF-16 units, but the policy is only
implied today. It is scattered across deviation rows: `compare` orders by code
point
(oracle: UTF-16 units, differs past U+FFFF), `lower-case` of a word holding
an astral character diverges from the JDK (`lower-case "Α𝐀Σ"`), and
`index-of`'s start is clamped into `[0, length]` before CL's `search`. No one
row states the index space, and there is no astral-character pin for
`count`/`subs`/`.substring`/`.charAt`/`.indexOf` today.

## Definition of done

- An astral-character sweep (`"a🌍b"` and friends) over `count`, `subs`,
  `.substring`, `.charAt`, `.indexOf`/`.lastIndexOf`, `nth`/`get` pinned in
  `clojure-spec.yaml`, identical on all four backends, verified against clj
  1.12.6 (recording where the oracle's UTF-16 answer differs -- that
  difference is the point).
- `doc/en/clojure/deviations.md` (+ `doc/ja/`) gains one explicit row: the
  index space is code points everywhere including the interop names, with the
  boundary-conversion note (UTF-16 offsets from JS/Java/LSP convert at the
  boundary; index-from-one-op-to-another code keeps working).
- The `lower-case` astral-word edge is either fixed or named in the same row
  (no longer discoverable only via the Turkish-locale paragraph).

## Non-goals

- Matching the oracle's UTF-16 indexing: the code-point space is the honest
  surface on wasm (no UTF-16 there); this item pins
  and documents it, not reverses it.
- Full-width/emoji grapheme clusters (one "character" of several code
  points) -- code points are the unit, documented as such.
