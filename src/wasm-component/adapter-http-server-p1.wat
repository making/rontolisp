;; Preview-1 bridge core module for serve components (rontolisp:http-handler +
;; --component). The serve adapter (adapter-http-server.wat) imports the rontolisp core's
;; %http-dispatch, so it is instantiated AFTER the core and cannot also provide the core's
;; wasi_snapshot_preview1 imports the way adapter.wat does for `wasmtime run` components.
;; This module fills that gap: instantiated between the shared memory and the core, it
;; exports the eight preview1 functions the core imports, implemented over the interfaces
;; the wasi:http proxy world provides:
;;
;;   random_get       -> wasi:random/random@0.2.0 get-random-u64 (8 bytes at a time)
;;   clock_time_get   -> wasi:clocks/wall-clock@0.2.0 now (clock id 0 = realtime) or
;;                       wasi:clocks/monotonic-clock@0.2.0 now (other ids); nanoseconds
;;   fd_write         -> wasi:cli/stdout@0.2.0 / stderr@0.2.0 output-streams via
;;                       blocking-write-and-flush in <=4096-byte chunks (its per-call
;;                       cap); the stream handles are fetched once and cached in globals.
;;                       Other fds return errno 8 (badf).
;;   environ_sizes_get / environ_get -> a zero-entry environment, so getenv returns nil
;;                       (the proxy world has no wasi:cli/environment)
;;   fd_read          -> immediate EOF (nread 0, errno 0; a served handler has no stdin)
;;   path_open        -> errno 76 (file streams stay unavailable; the core traps on a
;;                       nonzero open errno, matching the run-variant failure mode)
;;   fd_close         -> errno 0
;;
;; Scratch: 0x50380-0x5039F, TRANSIENT only -- each cell is written and read back within
;; a single export call, so the core's bump-heap sweep across the 0x50000 page (see
;; adapter-http-server.wat's header) can never interleave with a live value.
;;   0x50380  blocking-write-and-flush result<_, stream-error> lowering
;;   0x50390  wall-clock now datetime (seconds u64 @0x50390, nanoseconds u32 @0x50398)
(module
  (import "mem" "memory" (memory (;0;) 16))
  ;; Lowered wasi 0.2 functions (grouped under "w" by buildServe).
  (import "w" "rand-u64" (func $rand_u64 (result i64)))
  (import "w" "wall-now" (func $wall_now (param i32)))
  (import "w" "mono-now" (func $mono_now (result i64)))
  (import "w" "get-stdout" (func $get_stdout (result i32)))
  (import "w" "get-stderr" (func $get_stderr (result i32)))
  (import "w" "io-write" (func $io_write (param i32 i32 i32 i32)))

  ;; Cached wasi:cli stdout/stderr output-stream handles (-1 = not fetched yet). Globals,
  ;; not linear memory, for the same clobbering reason as adapter-http-server.wat's snapshots.
  (global $stdout (mut i32) (i32.const -1))
  (global $stderr (mut i32) (i32.const -1))

  ;; The output-stream for preview1 fd 1/2, fetched on first use.
  (func $out_stream (param $fd i32) (result i32)
    (if (i32.eq (local.get $fd) (i32.const 1))
      (then
        (if (i32.eq (global.get $stdout) (i32.const -1))
          (then (global.set $stdout (call $get_stdout))))
        (return (global.get $stdout))))
    (if (i32.eq (global.get $stderr) (i32.const -1))
      (then (global.set $stderr (call $get_stderr))))
    (global.get $stderr))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. fd 1/2 only; each iovec is pushed through
  ;; blocking-write-and-flush in <=4096-byte chunks. A stream error stops early with
  ;; errno 29 (EIO) and the bytes written so far.
  (func $fd_write (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $s i32) (local $i i32) (local $base i32) (local $ptr i32) (local $len i32)
    (local $n i32) (local $total i32)
    (if (i32.eqz (i32.or (i32.eq (local.get $fd) (i32.const 1)) (i32.eq (local.get $fd) (i32.const 2))))
      (then (return (i32.const 8))))
    (local.set $s (call $out_stream (local.get $fd)))
    (block $done
      (loop $iv
        (br_if $done (i32.ge_u (local.get $i) (local.get $cnt)))
        (local.set $base (i32.add (local.get $iov) (i32.mul (local.get $i) (i32.const 8))))
        (local.set $ptr (i32.load (local.get $base)))
        (local.set $len (i32.load offset=4 (local.get $base)))
        (block $wd
          (loop $wl
            (br_if $wd (i32.eqz (local.get $len)))
            (local.set $n (select (i32.const 4096) (local.get $len)
              (i32.gt_u (local.get $len) (i32.const 4096))))
            (call $io_write (local.get $s) (local.get $ptr) (local.get $n) (i32.const 0x50380))
            (if (i32.load8_u (i32.const 0x50380))
              (then
                (i32.store (local.get $nw) (local.get $total))
                (return (i32.const 29))))
            (local.set $ptr (i32.add (local.get $ptr) (local.get $n)))
            (local.set $len (i32.sub (local.get $len) (local.get $n)))
            (local.set $total (i32.add (local.get $total) (local.get $n)))
            (br $wl)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $iv)))
    (i32.store (local.get $nw) (local.get $total))
    (i32.const 0))

  ;; fd_read(fd, iov, cnt, nread) -> errno. Always EOF: a served handler has no stdin,
  ;; and the core reads a file fd only after a successful path_open (which fails here).
  (func $fd_read (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    (i32.store (local.get $nread) (i32.const 0))
    (i32.const 0))

  ;; path_open(...) -> errno 76: no filesystem in the proxy world. The core traps on the
  ;; nonzero errno, so file streams fail loudly instead of returning a garbage handle.
  (func $path_open
    (param $dirfd i32) (param $dirflags i32) (param $pptr i32) (param $plen i32)
    (param $oflags i32) (param $rb i64) (param $ri i64) (param $fdflags i32) (param $fdout i32) (result i32)
    (i32.const 76))

  ;; fd_close(fd) -> errno 0 (nothing to close; sockets/files never open here).
  (func $fd_close (param $fd i32) (result i32)
    (i32.const 0))

  ;; random_get(buf, len) -> errno. Fills buf with wasi:random bytes (8 at a time, like
  ;; adapter.wat).
  (func $random_get (param $buf i32) (param $len i32) (result i32)
    (local $i i32)
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $len)))
        (i64.store (i32.add (local.get $buf) (local.get $i)) (call $rand_u64))
        (local.set $i (i32.add (local.get $i) (i32.const 8)))
        (br $l)))
    (i32.const 0))

  ;; clock_time_get(clock_id, precision, resptr) -> errno. 0 = realtime (wall-clock),
  ;; else monotonic. Writes nanoseconds as i64 (mirrors adapter.wat).
  (func $clock_time_get (param $clkid i32) (param $prec i64) (param $resptr i32) (result i32)
    (if (i32.eqz (local.get $clkid))
      (then
        (call $wall_now (i32.const 0x50390))
        (i64.store (local.get $resptr)
          (i64.add (i64.mul (i64.load (i32.const 0x50390)) (i64.const 1000000000))
            (i64.extend_i32_u (i32.load (i32.const 0x50398))))))
      (else
        (i64.store (local.get $resptr) (call $mono_now))))
    (i32.const 0))

  ;; environ_sizes_get(count_ptr, bufsize_ptr) -> errno. Zero environment.
  (func $environ_sizes_get (param $cp i32) (param $bp i32) (result i32)
    (i32.store (local.get $cp) (i32.const 0))
    (i32.store (local.get $bp) (i32.const 0))
    (i32.const 0))

  ;; environ_get(env_ptrs, env_buf) -> errno. Nothing to write (count is 0).
  (func $environ_get (param $ep i32) (param $eb i32) (result i32)
    (i32.const 0))

  (export "fd_write" (func $fd_write))
  (export "fd_read" (func $fd_read))
  (export "path_open" (func $path_open))
  (export "fd_close" (func $fd_close))
  (export "random_get" (func $random_get))
  (export "clock_time_get" (func $clock_time_get))
  (export "environ_sizes_get" (func $environ_sizes_get))
  (export "environ_get" (func $environ_get)))
