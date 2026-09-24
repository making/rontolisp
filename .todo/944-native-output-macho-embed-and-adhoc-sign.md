# `--native` on macOS: embed the payload in the Mach-O and re-sign ad-hoc in Java

Difficulty: High

Depends on .todo/943. Appending the precompiled module after the stub leaves the
linker's ad-hoc signature covering the stub only: `codesign -v` reports "main executable
failed strict validation", `spctl -a` rejects it. It still runs from a shell today
(checked 2026-09-24, with and without `com.apple.quarantine`), but that is not a contract
to build on, and any distribution or notarization path needs a valid signature.

## What is needed

- Place the payload INSIDE the image: a dedicated segment/section in the stub (reserved
  at link time, or a new `LC_SEGMENT_64` inserted before `__LINKEDIT`), updating file
  offsets, `__LINKEDIT` and the load-command sizes.
- Re-compute the ad-hoc code signature in pure Java (no `codesign` on the host): a
  `CodeDirectory` of SHA-256 page hashes (4 KiB pages on arm64) plus the special slots,
  in an embedded `SuperBlob` at `LC_CODE_SIGNATURE`, flags `adhoc | linker-signed`. Go's
  linker does exactly this (`cmd/internal/codesign`) and is a size reference.
- The runner reads the payload from its section instead of the file tail (no
  self-`read` of the whole executable).
- Test: `codesign -v --strict` passes on the output (skipped off macOS); the output runs.
