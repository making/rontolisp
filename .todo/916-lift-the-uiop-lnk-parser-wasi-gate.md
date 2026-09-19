# Lift the `uiop` `.lnk` parser WASI gate

Difficulty: Low

`uiop/os:parse-windows-shortcut` and `uiop/os:parse-file-location-info` (`uiop-os.lisp`)
open with

```lisp
(if (uiop/os:featurep :rontolisp-wasm)
    (uiop/utility:not-implemented-error "UIOP/OS:PARSE-WINDOWS-SHORTCUT"
                                        "it seeks with file-position, which a WASI file stream does not support here")
    ...)
```

The stated reason is no longer true. `.todo/877` made `file-position` real on the
`--component` backend and `.todo/876` on Preview 1, so a binary file stream seeks on all
four backends (`.kb/read-load-streams.md`). The gate is now the only thing keeping the
upstream bodies from running on WASM.

## What it takes

- A `.lnk` FIXTURE the parser can be verified against, because the gate must not be
  replaced by a silent misread. The header the body checks is fixed and small: a
  little-endian `76`, then the 16 bytes `#(1 20 2 0 0 0 0 0 192 0 0 0 0 0 0 70)`, then a
  flags word, then a seek to 76. A test can WRITE one with `write-byte` rather than ship a
  binary -- that keeps it readable and lets the same program run on every backend.
- Delete the two `featurep :rontolisp-wasm` arms and run the fixture program on all four
  backends (`.kb/running-backends.md`).
- `WasmLispCompilerIntegrationTest#uiopOsHostIdentityAndGetenvOverride` currently asserts
  `:SHORTCUT-SIGNALS` / `:FLI-SIGNALS` for a `.lnk` that does not exist; with the gate gone
  that becomes a `file-error` instead, so the case has to say what it now means.
- `doc/en/reference/uiop/os.md` + `doc/ja` state the current reason in one paragraph each;
  both change with the gate.

## Watch out

The gate is the FIRST form of each function, so today no `.lnk` has to exist for the tests
to pass. Removing it makes the parsers actually open the path -- every existing caller in
the suite has to be checked for one that relied on the early signal.
