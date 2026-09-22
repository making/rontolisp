;; Stub core whose only purpose is to make `wasm-tools component new` emit the WASI 0.3
;; imports for import-block.bin. Imports every lowered 0.3 function rontolisp's component
;; uses (the adapter binds these names), exports a memory + cabi_realloc + run.
(module
  (import "wasi:cli/stdout@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (import "wasi:cli/stderr@0.3.0" "write-via-stream" (func (param i32) (result i32)))
  (import "wasi:cli/stdin@0.3.0" "read-via-stream" (func (param i32)))
  (import "wasi:cli/environment@0.3.0" "get-environment" (func (param i32)))
  ;; get-arguments is bound by environment.lisp (%host-argv), not by the adapter --
  ;; the block declares it so a program that reads its command line can bind it FROM
  ;; the block, a second import of this interface being invalid.
  (import "wasi:cli/environment@0.3.0" "get-arguments" (func (param i32)))
  (import "wasi:clocks/system-clock@0.3.0" "now" (func (param i32)))
  (import "wasi:clocks/monotonic-clock@0.3.0" "now" (func (result i64)))
  (import "wasi:clocks/monotonic-clock@0.3.0" "[async-lower]wait-for" (func (param i64) (result i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.read-via-stream" (func (param i32 i64 i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.append-via-stream" (func (param i32 i32) (result i32)))
  ;; write-via-stream is what an :io / :if-exists :overwrite stream writes through:
  ;; it takes the byte OFFSET, which append-via-stream cannot express, and the
  ;; adapter tracks that offset per fd anyway for the read side.
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.write-via-stream" (func (param i32 i32 i64) (result i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.open-at" (func (param i32 i32 i32 i32 i32 i32 i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.read-directory" (func (param i32 i32)))
  ;; descriptor.stat is an async func with no params; the SYNC (blocking) lowering
  ;; flattens to (self, retptr) and the adapter reads descriptor-stat out of the retptr.
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.stat" (func (param i32 i32)))
  ;; create-directory-at / unlink-file-at are async funcs taking one string; the SYNC
  ;; lowering flattens to (self, path_ptr, path_len, retptr) and the adapter reads the
  ;; result<_, error-code> discriminant out of the retptr.
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.create-directory-at" (func (param i32 i32 i32 i32)))
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.unlink-file-at" (func (param i32 i32 i32 i32)))
  ;; rename-at is an async func taking two strings and a borrowed descriptor; the SYNC
  ;; lowering flattens to (self, old_ptr, old_len, new_desc, new_ptr, new_len, retptr).
  (import "wasi:filesystem/types@0.3.0" "[method]descriptor.rename-at" (func (param i32 i32 i32 i32 i32 i32 i32)))
  (import "wasi:filesystem/preopens@0.3.0" "get-directories" (func (param i32)))
  (import "wasi:random/random@0.3.0" "get-random-u64" (func (result i64)))
  (memory (export "memory") 6)
  (global $hp (mut i32) (i32.const 65536))
  (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (global.get $hp))
  (func (export "run") (result i32) (i32.const 0)))
