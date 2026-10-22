# g45: lispresen phase 3 -- deck templates and embedded images

Difficulty: Medium

lispresen (`lispresen/`) renders an S-expression deck to one HTML file;
`lispresen/DESIGN.md` is its spec; phases 1 and 2 are done. Phase 3 of `DESIGN.md`
"Phases":

- **`define` templates**: `(define name (params...) body)` in a deck, expanded by
  substitution in the parser. Not Lisp macros: the deck is read, never evaluated
  (`DESIGN.md` "Deck format"), because compiled `eval` cannot see `defmacro`.
- **`--embed`**: images inlined as `data:` URIs so the HTML file stands alone. Needs a
  binary file read and base64 on every backend; check what `read-byte` /
  `:element-type '(unsigned-byte 8)` do on both WASM backends before designing around
  them. The MIME type comes from the extension.

Done when `lispresen/check.sh` passes with the golden deck exercising both (a small
image beside the deck for `--embed`).
