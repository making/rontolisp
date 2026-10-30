# h48. The ironclad slice lacks SHA-1, MD5 and `ironclad/prngs`

Difficulty: Medium

Found 2026-10-30. The slice (`.kb/asdf.md`, "ironclad 0.61 slice") declares sha256/sha512/hmac/
pkcs5/rsa only.

- `uuid`: `make-v4-uuid` works. `make-v5-uuid` signals `Digest SHA1 is not a supported
  digest` (v3 needs MD5).
- `cl-smtp`: `system 'ironclad/prngs' not found`.

Widen the slice with `ironclad/digest/sha1`, `/digest/md5`, and `ironclad/prngs` (the
latter over the existing `random-data` leaf shim). Or measure whether the full ironclad
digest set now loads unpatched, and retire the slice for digests.
