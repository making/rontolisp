# `read-sequence` / `write-sequence`: a character FILE stream picks the element too

Difficulty: Medium

Split out of `.todo/920` (2026-09-22), which landed "the stream picks the element" for STRING
streams only (`.kb/read-load-streams.md`, "The stream picks the element").

## What is left

A general vector or a list read from / written to a file opened `character` still follows the
buffer: bytes. sbcl moves characters (`(read-sequence (make-array 3) char-file)` ->
`#(#\h #\e #\l)`). On the interpreter it signals `READ-BYTE expects a binary input stream`; on
wasm it reads the file's octets. No ANSI test in the `streams` chapter exercises it.

## Why it was not landed

Measured 2026-09-22: telling a character file stream from a binary one on the compile paths
needs the element-type registry (`%file-stream-entry`), spliced today only for a program that
asks `stream-element-type` or opens a wide stream. Splicing it for every program naming
`read-sequence`/`write-sequence` and `open` cost a binary loader with a parameter buffer
+1.7 KB wasm / +3.6 KB JVM for the registry alone (gguf and geom are such programs), for zero
tests.

## A cheaper shape to measure first

The open stream value already carries a KIND slot (`LispLayout.STREAM`, `:FILE`). A literal
binary `open` leaf knows its element type at compile time, so the fact could ride in the value
itself -- a distinct kind keyword for a binary file stream, or a third slot -- with
`%character-stream-p` reading it and `typep 'file-stream` / `makeStreamKindTest` accepting both
kinds. Measure: the byte delta on programs that open binary files (every such program changes),
and whether a computed `:element-type` open (uiop wrappers, `#'open`) can set it at call time.
If the measurement says no, record it in `.kb/read-load-streams.md` and close this.
