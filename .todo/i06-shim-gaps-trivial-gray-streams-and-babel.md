# i06. Shim gaps: `trivial-gray-streams` and `babel` miss names real code uses

Difficulty: Low

Measured 2026-11-04. Both are built-in shims (`.kb/asdf.md`, Tier 1), so a missing name is a hard
stop where the real library would load. `.todo/147` weighs replacing shims; these are the names
needed meanwhile.

- **`trivial-gray-streams`**: `stream-read-char-no-hang`, `stream-peek-char`, `stream-clear-input`
  are absent. McCLIM's `clim-lisp` imports every Gray symbol from it (`Core/system/packages.lisp:192`)
  and stops with `:IMPORT-FROM: no symbol named STREAM-CLEAR-INPUT in CLIM-LISP`. Audit the shim's
  export list against the real library's whole list rather than these three; the generics have to
  reach rontolisp's Gray protocol (`.kb/gray-streams.md`).
- **`babel-encodings:enc-name`** is not external and, from the compile paths, not defined.
  closure-common's `ystream.lisp:68` and cxml's `xml/unparse.lisp:106` call it
  (`The symbol ENC-NAME is not external in the BABEL-ENCODINGS package`), so cxml does not load.
  "An encoding IS its name" in the shim, so `enc-name` is `identity` on that value. New exports need
  their `PackageRegistry` entry (`.kb/asdf.md`).
- **`babel:concatenate-strings-to-octets`** is absent; `cffi/uffi-compat/uffi-compat.lisp` names it,
  so `cffi-uffi-compat` (`.todo/i07`) does not load.

Audit `babel` and `babel-encodings` the same way, by the real package's externals, and report what
is left unimplemented in the guide's limitations.
