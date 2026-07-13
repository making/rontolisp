;; Print micro-adapter for --no-gc --component (todo 93 remaining task 1): a minuscule
;; core module implementing the rontolisp core's single wasi_snapshot_preview1.fd_write
;; import over WASI 0.2 stdio (the adapter-http-server-p1.wat pattern in miniature), so
;; print/princ/terpri work inside the compact reactor component's sync-lifted exports --
;; output-stream.blocking-write-and-flush is a plain synchronous host function,
;; default-provided by wasmtime with ZERO extra flags.
;;
;; The imported memory is the CORE's own exported memory (the --no-gc module defines it);
;; the shim/fixup pair (shim-nogc-print.wat / fixup-nogc-print.wat) breaks the resulting
;; instantiation cycle, so this module is instantiated AFTER the core.
;;
;; Contract with the sole caller, the core's __write_stdout funnel (the todo-110 seam):
;;   fd_write(1, iovAddr, 1, iovAddr + 8) -- fd 1, ONE iovec, inside the core's reserved
;;   16-byte fd_write scratch. This lets the bridge reuse the iovec cell itself
;;   (iov..iov+12, 4-aligned) as the result<_, stream-error> retptr area once ptr/len are
;;   in locals: nothing else lives in that scratch during the call, and nwritten (iov+8)
;;   is stored only after the last result has been read. Any other fd returns errno 8
;;   (badf) -- --no-gc rejects every non-print I/O op at compile time, so fd 2 is
;;   unreachable and wasi:cli/stderr stays out of the import block.
(module
  (import "mem" "memory" (memory 0))
  ;; Lowered WASI 0.2 functions (grouped under "w" by NoGcWasmComponentBuilder).
  (import "w" "get-stdout" (func $get_stdout (result i32)))
  (import "w" "io-write" (func $io_write (param i32 i32 i32 i32)))

  ;; Cached wasi:cli stdout output-stream handle (-1 = not fetched yet).
  (global $stdout (mut i32) (i32.const -1))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. The single iovec is pushed through
  ;; blocking-write-and-flush in <=4096-byte chunks (its per-call cap). A stream error
  ;; stops early with errno 29 (EIO) and the bytes written so far.
  (func (export "fd_write") (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $ptr i32) (local $len i32) (local $n i32) (local $total i32)
    (if (i32.ne (local.get $fd) (i32.const 1))
      (then (return (i32.const 8))))
    (if (i32.eq (global.get $stdout) (i32.const -1))
      (then (global.set $stdout (call $get_stdout))))
    (local.set $ptr (i32.load (local.get $iov)))
    (local.set $len (i32.load offset=4 (local.get $iov)))
    (block $done
      (loop $wl
        (br_if $done (i32.eqz (local.get $len)))
        (local.set $n (select (i32.const 4096) (local.get $len)
          (i32.gt_u (local.get $len) (i32.const 4096))))
        (call $io_write (global.get $stdout) (local.get $ptr) (local.get $n) (local.get $iov))
        (if (i32.load8_u (local.get $iov))
          (then
            (i32.store (local.get $nw) (local.get $total))
            (return (i32.const 29))))
        (local.set $ptr (i32.add (local.get $ptr) (local.get $n)))
        (local.set $len (i32.sub (local.get $len) (local.get $n)))
        (local.set $total (i32.add (local.get $total) (local.get $n)))
        (br $wl)))
    (i32.store (local.get $nw) (local.get $total))
    (i32.const 0)))
