;; Print micro-adapter for --no-gc --component: a minuscule core module implementing the
;; rontolisp core's single wasi_snapshot_preview1.fd_write import over WASI 0.3
;; (the base adapter.wat cli path in miniature), so print/princ/terpri work inside the
;; compact reactor component's exports. WASI 0.3 has no synchronous write: fd_write is a
;; full stream cycle -- stream.new, wasi:cli/stdout.write-via-stream(readable), the ASYNC
;; stream.write built-in pushing the bytes, drop the writable end, then await + drop the
;; operation future. The async built-ins are the non-blocking variants of base
;; component-model-async; a BLOCKED (-1) result parks the task on a blocking
;; waitable-set.wait until the handle's completion event arrives -- legal because the
;; exports of a printing component are ASYNC lifts (only an async-typed task may block),
;; and gated behind no wasmtime feature (default-on in 46+), so the zero-flag property
;; survives the 0.2 purge.
;;
;; The imported memory is the CORE's own exported memory (the --no-gc module defines it);
;; the shim/fixup pair (shim-nogc-print.wat / fixup-nogc-print.wat) breaks the resulting
;; instantiation cycle, so this module is instantiated AFTER the core.
;;
;; Contract with the sole caller, the core's __write_stdout funnel:
;;   fd_write(1, iovAddr, 1, iovAddr + 8) -- fd 1, ONE iovec, inside the core's reserved
;;   16-byte fd_write scratch. This lets the bridge reuse the scratch itself once ptr/len
;;   are in locals: the waitable-set event pair {waitable, payload} lands at iov..iov+8
;;   (4-aligned) and the future.read result<_, error-code> retptr at iov+8 -- nothing
;;   else lives in that scratch during the call, and nwritten (iov+8) is stored only
;;   after the last result has been read. The cached waitable-set handle needs no memory
;;   at all (a module global). Any other fd returns errno 8 (badf) -- --no-gc rejects
;;   every non-print I/O op at compile time, so fd 2 is unreachable and wasi:cli/stderr
;;   stays out of the import block.
(module
  (import "mem" "memory" (memory 0))
  ;; Lowered WASI 0.3 function + async canonical built-ins (grouped under "w" by
  ;; NoGcWasmComponentBuilder). stream-write / future-read-cli are the ASYNC
  ;; (non-blocking) variants; BLOCKED completes through the waitable-set below.
  (import "w" "stdout-write" (func $stdout_write (param i32) (result i32)))
  (import "w" "stream-new" (func $stream_new (result i64)))
  (import "w" "stream-write" (func $stream_write_a (param i32 i32 i32) (result i32)))
  (import "w" "stream-drop-w" (func $stream_drop_w (param i32)))
  (import "w" "future-read-cli" (func $future_read_cli_a (param i32 i32) (result i32)))
  (import "w" "future-drop-cli" (func $future_drop_cli (param i32)))
  (import "w" "waitable-set-new" (func $ws_new (result i32)))
  (import "w" "waitable-join" (func $w_join (param i32 i32)))
  (import "w" "waitable-set-wait" (func $ws_wait (param i32 i32) (result i32)))

  ;; The bridge's one waitable-set, created on first blocking completion (0 = not yet).
  (global $ws (mut i32) (i32.const 0))
  (func $ensure_ws (result i32)
    (if (i32.eqz (global.get $ws))
      (then (global.set $ws (call $ws_new))))
    (global.get $ws))

  ;; Parks the task until the given handle reports its completion event; the event pair
  ;; {waitable, payload} is written at $ev (the reused iovec cell). Returns the payload
  ;; (the packed (count << 4) | status of the finished operation).
  (func $await_waitable (param $h i32) (param $ev i32) (result i32)
    (call $w_join (local.get $h) (call $ensure_ws))
    (block $got
      (loop $l
        (drop (call $ws_wait (call $ensure_ws) (local.get $ev)))
        (br_if $got (i32.eq (i32.load (local.get $ev)) (local.get $h)))
        (br $l)))
    (i32.load offset=4 (local.get $ev)))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. One full stream cycle per call: create
  ;; the stream, hand its readable end to stdout, push the single iovec through the
  ;; writable end (parking on BLOCKED), signal EOF by dropping it, then await + drop the
  ;; operation future (its result<_, error-code> lands at iov+8, read before nwritten
  ;; overwrites the cell).
  (func (export "fd_write") (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $ptr i32) (local $len i32) (local $r64 i64) (local $rx i32) (local $tx i32)
    (local $fut i32) (local $ret i32)
    (if (i32.ne (local.get $fd) (i32.const 1))
      (then (return (i32.const 8))))
    (local.set $ptr (i32.load (local.get $iov)))
    (local.set $len (i32.load offset=4 (local.get $iov)))
    (local.set $r64 (call $stream_new))
    (local.set $rx (i32.wrap_i64 (local.get $r64)))
    (local.set $tx (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (local.set $fut (call $stdout_write (local.get $rx)))
    (if (local.get $len)
      (then
        (local.set $ret (call $stream_write_a (local.get $tx) (local.get $ptr) (local.get $len)))
        (if (i32.eq (local.get $ret) (i32.const -1))
          (then (drop (call $await_waitable (local.get $tx) (local.get $iov)))))))
    (call $stream_drop_w (local.get $tx))
    (local.set $ret (call $future_read_cli_a (local.get $fut) (i32.add (local.get $iov) (i32.const 8))))
    (if (i32.eq (local.get $ret) (i32.const -1))
      (then (drop (call $await_waitable (local.get $fut) (local.get $iov)))))
    (call $future_drop_cli (local.get $fut))
    (i32.store (local.get $nw) (local.get $len))
    (i32.const 0)))
