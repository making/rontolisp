;; Preview-1 bridge core module for serve components (rontolisp:http-handler +
;; --component), on WASI 0.3. Instantiated between the shared memory and the rontolisp
;; core, it exports the eight preview1 functions the core imports, implemented over the
;; interfaces the wasi:http@0.3 service world provides:
;;
;;   random_get       -> wasi:random/random@0.3.0 get-random-u64 (8 bytes at a time)
;;   clock_time_get   -> wasi:clocks/system-clock@0.3.0 now (clock id 0 = realtime) or
;;                       wasi:clocks/monotonic-clock@0.3.0 now (other ids); nanoseconds
;;   fd_write         -> wasi:cli/{stdout,stderr}@0.3.0 write-via-stream over the
;;                       stream<u8> built-ins: one stream per call, every iovec pushed
;;                       through it, EOF by dropping the writable end, then the write
;;                       future awaited (the base adapter.wat's cli path).
;;   environ_sizes_get / environ_get -> a zero-entry environment, so getenv returns nil
;;                       (the service world has no wasi:cli/environment)
;;   fd_read          -> immediate EOF (nread 0, errno 0; a served handler has no stdin)
;;   path_open        -> errno 76 (file streams stay unavailable; the core traps on a
;;                       nonzero open errno, matching the run-variant failure mode)
;;   fd_close         -> errno 0
;;
;; Scratch: 0x50000-0x5001F, TRANSIENT only -- each cell is written and read back within
;; a single export call.
;;   0x50000  future-read-cli result (result<_, error-code>)
;;   0x50010  system-clock now instant (seconds s64 @0x50010, nanoseconds u32 @0x50018)
(module
  (import "mem" "memory" (memory (;0;) 16))
  ;; Lowered wasi 0.3 functions + async built-ins (grouped under "w" by the builder).
  (import "w" "rand-u64" (func $rand_u64 (result i64)))
  (import "w" "sys-now" (func $sys_now (param i32)))
  (import "w" "mono-now" (func $mono_now (result i64)))
  (import "w" "stdout-write" (func $stdout_write (param i32) (result i32)))
  (import "w" "stderr-write" (func $stderr_write (param i32) (result i32)))
  (import "w" "stream-new" (func $stream_new (result i64)))
  (import "w" "stream-write" (func $stream_write (param i32 i32 i32) (result i32)))
  (import "w" "stream-drop-w" (func $stream_drop_w (param i32)))
  (import "w" "future-read-cli" (func $future_read_cli (param i32 i32) (result i32)))
  (import "w" "future-drop-cli" (func $future_drop_cli (param i32)))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. fd 1/2 only; one stream per call, every
  ;; iovec pushed through it, EOF by dropping the writable end, then the write future
  ;; awaited (its readable end dropped afterwards). Other fds return errno 8 (badf).
  (func $fd_write (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $r64 i64) (local $rx i32) (local $tx i32) (local $fut i32)
    (local $i i32) (local $base i32) (local $ptr i32) (local $len i32) (local $total i32)
    (if (i32.eqz (i32.or (i32.eq (local.get $fd) (i32.const 1)) (i32.eq (local.get $fd) (i32.const 2))))
      (then (return (i32.const 8))))
    (local.set $r64 (call $stream_new))
    (local.set $rx (i32.wrap_i64 (local.get $r64)))
    (local.set $tx (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (if (i32.eq (local.get $fd) (i32.const 1))
      (then (local.set $fut (call $stdout_write (local.get $rx))))
      (else (local.set $fut (call $stderr_write (local.get $rx)))))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $cnt)))
        (local.set $base (i32.add (local.get $iov) (i32.mul (local.get $i) (i32.const 8))))
        (local.set $ptr (i32.load (local.get $base)))
        (local.set $len (i32.load offset=4 (local.get $base)))
        (if (local.get $len)
          (then (drop (call $stream_write (local.get $tx) (local.get $ptr) (local.get $len)))))
        (local.set $total (i32.add (local.get $total) (local.get $len)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (call $stream_drop_w (local.get $tx))
    (drop (call $future_read_cli (local.get $fut) (i32.const 0x50000)))
    (call $future_drop_cli (local.get $fut))
    (i32.store (local.get $nw) (local.get $total))
    (i32.const 0))

  ;; fd_read(fd, iov, cnt, nread) -> errno. Always EOF: a served handler has no stdin,
  ;; and the core reads a file fd only after a successful path_open (which fails here).
  (func $fd_read (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    (i32.store (local.get $nread) (i32.const 0))
    (i32.const 0))

  ;; path_open(...) -> errno 76: no filesystem in the service world. The core traps on
  ;; the nonzero errno, so file streams fail loudly instead of returning a garbage
  ;; handle.
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

  ;; clock_time_get(clock_id, precision, resptr) -> errno. 0 = realtime (system-clock),
  ;; else monotonic. Writes nanoseconds as i64 (mirrors adapter.wat).
  (func $clock_time_get (param $clkid i32) (param $prec i64) (param $resptr i32) (result i32)
    (if (i32.eqz (local.get $clkid))
      (then
        (call $sys_now (i32.const 0x50010))
        (i64.store (local.get $resptr)
          (i64.add (i64.mul (i64.load (i32.const 0x50010)) (i64.const 1000000000))
            (i64.extend_i32_u (i32.load (i32.const 0x50018))))))
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
