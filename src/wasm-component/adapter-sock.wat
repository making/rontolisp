;; preview1-to-WASI-0.3 adapter core module, SOCKETS VARIANT (adapter.wat + the
;; rontolisp:tcp-* plumbing over wasi:sockets@0.3.0).
;;
;; Imports the shared memory and the lowered WASI 0.3 functions plus the async canonical
;; built-ins (under "w"); exports the eight wasi_snapshot_preview1 functions rontolisp
;; imports PLUS tcp-connect / tcp-listen / tcp-accept / tcp-local-port (the "sock" seam).
;; In WASI 0.3 the wasi:io package is gone and all byte I/O flows through the
;; built-in stream<u8> / future<T> types, so fd_write/fd_read/path_open/fd_close are
;; implemented with stream.new/read/write/drop + future.read over wasi:cli + wasi:filesystem
;; 0.3; random_get/clock_time_get/environ_* bridge wasi:random / wasi:clocks
;; (system-clock, renamed from 0.2's wall-clock) / wasi:cli/environment.
;;
;; A connected TCP socket is a slot in the socket table below; its preview1 fd is
;; 200 + slotIndex, so fd_write / fd_read / fd_close dispatch on fd >= 200 and the
;; rontolisp core's stream built-ins work on sockets unchanged. At connect/accept time the
;; slot is "plumbed" eagerly: receive() yields the recv stream (its future is dropped
;; immediately -- EOF is signalled by the stream status, like the file read futures), and
;; a fresh stream pair is created with stream.new whose read end is passed to send() (send
;; is callable at most once per socket) while the write end stays in the slot for
;; fd_write; the send future is likewise dropped immediately (stream.write blocks until
;; the bytes are accepted). fd_close drops the write end (sending FIN), the recv stream
;; and the tcp-socket resource. A listener slot holds the stream<tcp-socket> returned by
;; listen(); tcp-accept performs a (cooperatively blocking) stream.read of one handle on
;; it. Hosts run socket components with -S tcp=y -S inherit-network=y; without the flags
;; the sockets host functions return errors and the built-ins yield nil.
;;
;; Straight-line synchronous code is fine: the whole component's `run` is lifted as a
;; STACKFUL async export, so the synchronous stream.write / stream.read / future.read
;; built-ins block cooperatively (they require the "more async builtins" feature) without
;; the adapter needing a callback state machine.
;;
;; All scratch lives in page 5 (0x50000+), clear of rontolisp's data/heap (pages 0-3), its
;; environ scratch (page 3) and the canonical realloc heap (from 65536). The shared memory
;; module is 6 pages so page 5 exists. Layout:
;;   0x50000 future.read result scratch (result<_, error-code>)
;;   0x50010 system-clock instant scratch (seconds s64 @0x50010, nanoseconds u32 @0x50018)
;;   0x50020 environ list {ptr@0x50020, count@0x50024}
;;   0x50030 get-directories list {ptr@0x50030, count@0x50034}
;;   0x50040 preopen descriptor cache {flag@0x50040, descriptor@0x50044}
;;   0x50050 open-at result {disc@0x50050 byte, descriptor-or-errcode i32@0x50054}
;;   0x50060 file read-via-stream tuple {stream@0x50060, future@0x50064}
;;   0x50070 stdin read-via-stream tuple {stream@0x50070, future@0x50074}
;;   0x50080 stdin cache {flag@0x50080, stream@0x50084}
;;   0x50090 sockets result scratch (create/bind/connect/listen/get-local-address retptr)
;;   0x500A0 tcp receive tuple {stream@0x500A0, future@0x500A4}
;;   0x500B0 accepted tcp-socket handle scratch
;;   0x500C0 parsed IPv4 address {a@0x500C0, b@0x500C4, c@0x500C8, d@0x500CC}
;;   0x50100 fd table: 64 slots x 16 bytes {descriptor@0, read-stream@4, valid@12}
;;   0x50500 socket table: 32 slots x 16 bytes
;;           {tcp-socket@0, recv-or-listen-stream@4, send-tx@8, kind@12}
;;           kind: 0 = free, 1 = connected socket, 2 = listener
;; A preview1 file fd is 100 + slotIndex (so it never clashes with stdout=1 or dirfd=3);
;; a socket fd is 200 + slotIndex (above the 64-slot file range).
;; Writes use append-via-stream (each fd_write is a full append cycle, so no per-fd write
;; offset needs tracking) and await the write future; reads cache the readable stream per fd
;; and let it advance, dropping the read future immediately (EOF is signalled by the stream
;; status, not the future). wasi:cli, wasi:filesystem and wasi:sockets expose DISTINCT
;; error-code enums, so their future<result<_, error-code>> are distinct types needing
;; separate built-ins (suffix -cli / -fs / -sock); stream<u8> is structural and shared.
(module
  (import "mem" "memory" (memory (;0;) 6))
  ;; lowered WASI 0.3 functions
  (import "w" "stdout-write" (func $stdout_write (param i32) (result i32)))
  (import "w" "stderr-write" (func $stderr_write (param i32) (result i32)))
  (import "w" "stdin-read" (func $stdin_read (param i32)))
  (import "w" "get-environment" (func $getenviron (param i32)))
  (import "w" "sys-now" (func $sys_now (param i32)))
  (import "w" "mono-now" (func $mono_now (result i64)))
  (import "w" "file-read" (func $file_read (param i32 i64 i32)))
  (import "w" "file-append" (func $file_append (param i32 i32) (result i32)))
  (import "w" "open-at" (func $open_at (param i32 i32 i32 i32 i32 i32 i32)))
  (import "w" "get-directories" (func $get_directories (param i32)))
  (import "w" "get-random-u64" (func $rand_u64 (result i64)))
  (import "w" "drop-desc" (func $drop_desc (param i32)))
  ;; async canonical built-ins. stream<u8> is structural (one set); future built-ins are
  ;; per-error-code-type: -cli for wasi:cli futures (stdout/stdin), -fs for wasi:filesystem
  ;; futures (file read/append).
  (import "w" "stream-new" (func $stream_new (result i64)))
  (import "w" "stream-read" (func $stream_read (param i32 i32 i32) (result i32)))
  (import "w" "stream-write" (func $stream_write (param i32 i32 i32) (result i32)))
  (import "w" "stream-drop-r" (func $stream_drop_r (param i32)))
  (import "w" "stream-drop-w" (func $stream_drop_w (param i32)))
  (import "w" "future-read-cli" (func $future_read_cli (param i32 i32) (result i32)))
  (import "w" "future-drop-cli" (func $future_drop_cli (param i32)))
  (import "w" "future-read-fs" (func $future_read_fs (param i32 i32) (result i32)))
  (import "w" "future-drop-fs" (func $future_drop_fs (param i32)))
  ;; lowered wasi:sockets@0.3.0 tcp-socket functions. bind/connect take the flattened
  ;; ip-socket-address variant (disc + the 11-slot ipv6 arm = 12 i32s) plus self and the
  ;; retptr; send returns the future handle directly; receive/listen/create/local-addr
  ;; write their results through the retptr (last param).
  (import "w" "tcp-create" (func $tcp_create (param i32 i32)))
  (import "w" "tcp-bind" (func $tcp_bind (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
  (import "w" "tcp-connect-raw" (func $tcp_connect_raw (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
  (import "w" "tcp-listen-raw" (func $tcp_listen_raw (param i32 i32)))
  (import "w" "tcp-send" (func $tcp_send (param i32 i32) (result i32)))
  (import "w" "tcp-receive" (func $tcp_receive (param i32 i32)))
  (import "w" "tcp-local-addr" (func $tcp_local_addr (param i32 i32)))
  ;; sockets-specific built-ins: the tcp-socket resource drop, stream.read /
  ;; stream.drop-readable of the stream<tcp-socket> accept stream (4-byte handles), and
  ;; future.drop-readable of the sockets future<result<_, error-code>>.
  (import "w" "drop-tcp" (func $drop_tcp (param i32)))
  (import "w" "accept-read" (func $accept_read (param i32 i32 i32) (result i32)))
  (import "w" "accept-drop-r" (func $accept_drop_r (param i32)))
  (import "w" "future-drop-sock" (func $future_drop_sock (param i32)))

  ;; Return the first preopened directory descriptor (cached).
  (func $ensure_preopen (result i32)
    (if (i32.eqz (i32.load (i32.const 0x50040)))
      (then
        (call $get_directories (i32.const 0x50030))
        (i32.store (i32.const 0x50044) (i32.load (i32.load (i32.const 0x50030))))
        (i32.store (i32.const 0x50040) (i32.const 1))))
    (i32.load (i32.const 0x50044)))

  ;; fd table slot address for a preview1 file fd.
  (func $slot (param $fd i32) (result i32)
    (i32.add (i32.const 0x50100)
      (i32.mul (i32.sub (local.get $fd) (i32.const 100)) (i32.const 16))))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. fd==1 is stdout, fd==2 is stderr; fd>=200 a
  ;; socket; otherwise a file fd. One full stream is created per call (except sockets): open
  ;; it (write-via-stream for stdout/stderr, append-via-stream for a file), push every iovec
  ;; through it, signal EOF by dropping the writable end, then await the operation future.
  ;; stdout and stderr share the wasi:cli error-code (future -cli built-ins); a file uses
  ;; -fs.
  (func $fd_write (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $r64 i64) (local $rx i32) (local $tx i32) (local $fut i32)
    (local $i i32) (local $base i32) (local $ptr i32) (local $len i32) (local $total i32) (local $sl i32)
    ;; socket fd: push every iovec through the slot's persistent send writer (no
    ;; per-call stream/future -- stream.write blocks until the bytes are accepted).
    (if (i32.ge_u (local.get $fd) (i32.const 200))
      (then
        (local.set $sl (call $sock_slot (local.get $fd)))
        (local.set $tx (i32.load offset=8 (local.get $sl)))
        (block $sdone
          (loop $sl2
            (br_if $sdone (i32.ge_u (local.get $i) (local.get $cnt)))
            (local.set $base (i32.add (local.get $iov) (i32.mul (local.get $i) (i32.const 8))))
            (local.set $ptr (i32.load (local.get $base)))
            (local.set $len (i32.load offset=4 (local.get $base)))
            (if (local.get $len)
              (then (drop (call $stream_write (local.get $tx) (local.get $ptr) (local.get $len)))))
            (local.set $total (i32.add (local.get $total) (local.get $len)))
            (local.set $i (i32.add (local.get $i) (i32.const 1)))
            (br $sl2)))
        (i32.store (local.get $nw) (local.get $total))
        (return (i32.const 0))))
    (local.set $r64 (call $stream_new))
    (local.set $rx (i32.wrap_i64 (local.get $r64)))
    (local.set $tx (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (if (i32.eq (local.get $fd) (i32.const 1))
      (then
        (local.set $fut (call $stdout_write (local.get $rx))))
      (else
        (if (i32.eq (local.get $fd) (i32.const 2))
          (then
            (local.set $fut (call $stderr_write (local.get $rx))))
          (else
            (local.set $sl (call $slot (local.get $fd)))
            (local.set $fut (call $file_append (i32.load (local.get $sl)) (local.get $rx)))))))
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
    ;; await + drop the write future with the matching error-code type (fd 1/2 = cli).
    (if (i32.or (i32.eq (local.get $fd) (i32.const 1)) (i32.eq (local.get $fd) (i32.const 2)))
      (then
        (drop (call $future_read_cli (local.get $fut) (i32.const 0x50000)))
        (call $future_drop_cli (local.get $fut)))
      (else
        (drop (call $future_read_fs (local.get $fut) (i32.const 0x50000)))
        (call $future_drop_fs (local.get $fut))))
    (i32.store (local.get $nw) (local.get $total))
    (i32.const 0))

  ;; fd_read(fd, iov, cnt, nread) -> errno. Single-iovec; nread==0 signals EOF. fd==0 is
  ;; stdin (a cached wasi:cli/stdin readable stream); otherwise a file fd whose readable
  ;; stream is cached in the slot and advances across calls.
  (func $fd_read (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    (local $ins i32) (local $sl i32) (local $ptr i32) (local $len i32) (local $ret i32) (local $n i32)
    ;; socket fd: read straight from the slot's recv stream (blocks cooperatively;
    ;; count 0 = EOF, i.e. the peer sent FIN).
    (if (i32.ge_u (local.get $fd) (i32.const 200))
      (then
        (local.set $sl (call $sock_slot (local.get $fd)))
        (local.set $ptr (i32.load (local.get $iov)))
        (local.set $len (i32.load offset=4 (local.get $iov)))
        (local.set $ret (call $stream_read (i32.load offset=4 (local.get $sl)) (local.get $ptr) (local.get $len)))
        (i32.store (local.get $nread) (i32.shr_u (local.get $ret) (i32.const 4)))
        (return (i32.const 0))))
    (if (i32.eqz (local.get $fd))
      (then
        (if (i32.eqz (i32.load (i32.const 0x50080)))
          (then
            (call $stdin_read (i32.const 0x50070))
            (i32.store (i32.const 0x50084) (i32.load (i32.const 0x50070)))
            (call $future_drop_cli (i32.load (i32.const 0x50074)))
            (i32.store (i32.const 0x50080) (i32.const 1))))
        (local.set $ins (i32.load (i32.const 0x50084))))
      (else
        (local.set $sl (call $slot (local.get $fd)))
        (local.set $ins (i32.load offset=4 (local.get $sl)))
        (if (i32.eq (local.get $ins) (i32.const -1))
          (then
            (call $file_read (i32.load (local.get $sl)) (i64.const 0) (i32.const 0x50060))
            (local.set $ins (i32.load (i32.const 0x50060)))
            (i32.store offset=4 (local.get $sl) (local.get $ins))
            (call $future_drop_fs (i32.load (i32.const 0x50064)))))))
    (local.set $ptr (i32.load (local.get $iov)))
    (local.set $len (i32.load offset=4 (local.get $iov)))
    ;; stream.read writes straight into the shared-memory destination; the return value is
    ;; (count << 4) | status, with status 0 = completed, 1 = dropped (EOF). count 0 = EOF.
    (local.set $ret (call $stream_read (local.get $ins) (local.get $ptr) (local.get $len)))
    (local.set $n (i32.shr_u (local.get $ret) (i32.const 4)))
    (i32.store (local.get $nread) (local.get $n))
    (i32.const 0))

  ;; path_open(dirfd, dirflags, pptr, plen, oflags, rights_base, rights_inh, fdflags, fdout)
  ;; -> errno. dirfd is ignored (the preopened dir is used). oflags 0 = read, 9 = write
  ;; (create|truncate, same bit values as WASI 0.3 open-flags).
  (func $path_open
    (param $dirfd i32) (param $dirflags i32) (param $pptr i32) (param $plen i32)
    (param $oflags i32) (param $rb i64) (param $ri i64) (param $fdflags i32) (param $fdout i32) (result i32)
    (local $pre i32) (local $df i32) (local $idx i32) (local $sl i32)
    (local.set $pre (call $ensure_preopen))
    (local.set $df (if (result i32) (i32.eqz (local.get $oflags))
      (then (i32.const 1)) (else (i32.const 2))))
    (call $open_at (local.get $pre) (i32.const 0) (local.get $pptr) (local.get $plen)
      (local.get $oflags) (local.get $df) (i32.const 0x50050))
    (if (i32.load8_u (i32.const 0x50050)) (then (return (i32.const 76))))
    (block $found
      (loop $fl
        (local.set $sl (i32.add (i32.const 0x50100) (i32.mul (local.get $idx) (i32.const 16))))
        (br_if $found (i32.eqz (i32.load offset=12 (local.get $sl))))
        (local.set $idx (i32.add (local.get $idx) (i32.const 1)))
        (br_if $fl (i32.lt_u (local.get $idx) (i32.const 64)))))
    (if (i32.eq (local.get $idx) (i32.const 64)) (then (return (i32.const 76))))
    (i32.store offset=12 (local.get $sl) (i32.const 1))
    (i32.store (local.get $sl) (i32.load offset=4 (i32.const 0x50050)))
    (i32.store offset=4 (local.get $sl) (i32.const -1))
    (i32.store offset=8 (local.get $sl) (i32.const -1))
    (i32.store (local.get $fdout) (i32.add (i32.const 100) (local.get $idx)))
    (i32.const 0))

  ;; fd_close(fd) -> errno. Drops the cached readable stream (if any) and the descriptor,
  ;; frees the slot. (The read future was dropped at open/first-read; writes await and drop
  ;; their future per call.)
  (func $fd_close (param $fd i32) (result i32)
    (local $sl i32) (local $h i32)
    ;; socket fd: kind 1 (connected) drops the send writer (FIN), the recv stream and
    ;; the tcp-socket; kind 2 (listener) drops the accept stream and the tcp-socket.
    (if (i32.ge_u (local.get $fd) (i32.const 200))
      (then
        (local.set $sl (call $sock_slot (local.get $fd)))
        (if (i32.eq (i32.load offset=12 (local.get $sl)) (i32.const 1))
          (then
            (call $stream_drop_w (i32.load offset=8 (local.get $sl)))
            (call $stream_drop_r (i32.load offset=4 (local.get $sl)))
            (call $drop_tcp (i32.load (local.get $sl))))
          (else
            (if (i32.eq (i32.load offset=12 (local.get $sl)) (i32.const 2))
              (then
                (call $accept_drop_r (i32.load offset=4 (local.get $sl)))
                (call $drop_tcp (i32.load (local.get $sl)))))))
        (i32.store offset=12 (local.get $sl) (i32.const 0))
        (return (i32.const 0))))
    (if (i32.lt_u (local.get $fd) (i32.const 100)) (then (return (i32.const 0))))
    (local.set $sl (call $slot (local.get $fd)))
    (local.set $h (i32.load offset=4 (local.get $sl)))
    (if (i32.ne (local.get $h) (i32.const -1))
      (then (call $stream_drop_r (local.get $h))))
    (call $drop_desc (i32.load (local.get $sl)))
    (i32.store offset=12 (local.get $sl) (i32.const 0))
    (i32.const 0))

  ;; random_get(buf, len) -> errno. Fills buf with wasi:random bytes (8 at a time).
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
  ;; else monotonic. Writes nanoseconds as i64.
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

  ;; environ_sizes_get(count_ptr, bufsize_ptr) -> errno.
  (func $environ_sizes_get (param $cp i32) (param $bp i32) (result i32)
    (local $base i32) (local $count i32) (local $i i32) (local $sz i32) (local $e i32)
    (call $getenviron (i32.const 0x50020))
    (local.set $base (i32.load (i32.const 0x50020)))
    (local.set $count (i32.load (i32.const 0x50024)))
    (block $d
      (loop $l
        (br_if $d (i32.ge_u (local.get $i) (local.get $count)))
        (local.set $e (i32.add (local.get $base) (i32.mul (local.get $i) (i32.const 16))))
        (local.set $sz (i32.add (local.get $sz)
          (i32.add (i32.add (i32.load offset=4 (local.get $e)) (i32.load offset=12 (local.get $e)))
            (i32.const 2))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.store (local.get $cp) (local.get $count))
    (i32.store (local.get $bp) (local.get $sz))
    (i32.const 0))

  ;; environ_get(ptrs, buf) -> errno. Writes a preview1-style "KEY=VALUE\0" buffer plus the
  ;; pointer array, decoded from wasi:cli/environment's list<tuple<string,string>>.
  (func $environ_get (param $pp i32) (param $bufp i32) (result i32)
    (local $base i32) (local $count i32) (local $i i32) (local $out i32) (local $e i32)
    (local $kp i32) (local $kl i32) (local $vp i32) (local $vl i32) (local $j i32)
    (call $getenviron (i32.const 0x50020))
    (local.set $base (i32.load (i32.const 0x50020)))
    (local.set $count (i32.load (i32.const 0x50024)))
    (local.set $out (local.get $bufp))
    (block $d
      (loop $l
        (br_if $d (i32.ge_u (local.get $i) (local.get $count)))
        (local.set $e (i32.add (local.get $base) (i32.mul (local.get $i) (i32.const 16))))
        (local.set $kp (i32.load (local.get $e)))
        (local.set $kl (i32.load offset=4 (local.get $e)))
        (local.set $vp (i32.load offset=8 (local.get $e)))
        (local.set $vl (i32.load offset=12 (local.get $e)))
        (i32.store (i32.add (local.get $pp) (i32.mul (local.get $i) (i32.const 4))) (local.get $out))
        (local.set $j (i32.const 0))
        (block $kd
          (loop $k
            (br_if $kd (i32.ge_u (local.get $j) (local.get $kl)))
            (i32.store8 (local.get $out) (i32.load8_u (i32.add (local.get $kp) (local.get $j))))
            (local.set $out (i32.add (local.get $out) (i32.const 1)))
            (local.set $j (i32.add (local.get $j) (i32.const 1)))
            (br $k)))
        (i32.store8 (local.get $out) (i32.const 61))
        (local.set $out (i32.add (local.get $out) (i32.const 1)))
        (local.set $j (i32.const 0))
        (block $vd
          (loop $v
            (br_if $vd (i32.ge_u (local.get $j) (local.get $vl)))
            (i32.store8 (local.get $out) (i32.load8_u (i32.add (local.get $vp) (local.get $j))))
            (local.set $out (i32.add (local.get $out) (i32.const 1)))
            (local.set $j (i32.add (local.get $j) (i32.const 1)))
            (br $v)))
        (i32.store8 (local.get $out) (i32.const 0))
        (local.set $out (i32.add (local.get $out) (i32.const 1)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.const 0))

  ;; socket table slot address for a preview1 socket fd.
  (func $sock_slot (param $fd i32) (result i32)
    (i32.add (i32.const 0x50500)
      (i32.mul (i32.sub (local.get $fd) (i32.const 200)) (i32.const 16))))

  ;; Find a free socket slot; returns the slot index, or -1 when the table is full.
  (func $sock_alloc (result i32)
    (local $idx i32)
    (block $found
      (loop $l
        (br_if $found (i32.eqz (i32.load offset=12
          (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 16))))))
        (local.set $idx (i32.add (local.get $idx) (i32.const 1)))
        (br_if $l (i32.lt_u (local.get $idx) (i32.const 32))))
      (return (i32.const -1)))
    (local.get $idx))

  ;; Parse a dotted-quad IPv4 literal from (ptr, len) into 0x500C0..0x500CC.
  ;; Returns 1 on success, 0 on a malformed literal (hostnames are not resolved;
  ;; wasi:sockets/ip-name-lookup is not wired yet).
  (func $parse_ipv4 (param $ptr i32) (param $len i32) (result i32)
    (local $i i32) (local $n i32) (local $val i32) (local $digits i32) (local $c i32)
    (block $fail
      (loop $comp
        (local.set $val (i32.const 0))
        (local.set $digits (i32.const 0))
        (block $compdone
          (loop $d
            (br_if $compdone (i32.ge_u (local.get $i) (local.get $len)))
            (local.set $c (i32.load8_u (i32.add (local.get $ptr) (local.get $i))))
            (br_if $compdone (i32.eq (local.get $c) (i32.const 46)))
            (br_if $fail (i32.or (i32.lt_u (local.get $c) (i32.const 48))
              (i32.gt_u (local.get $c) (i32.const 57))))
            (local.set $val (i32.add (i32.mul (local.get $val) (i32.const 10))
              (i32.sub (local.get $c) (i32.const 48))))
            (br_if $fail (i32.gt_u (local.get $val) (i32.const 255)))
            (local.set $digits (i32.add (local.get $digits) (i32.const 1)))
            (local.set $i (i32.add (local.get $i) (i32.const 1)))
            (br $d)))
        (br_if $fail (i32.eqz (local.get $digits)))
        (i32.store (i32.add (i32.const 0x500C0) (i32.mul (local.get $n) (i32.const 4))) (local.get $val))
        (local.set $n (i32.add (local.get $n) (i32.const 1)))
        (if (i32.eq (local.get $n) (i32.const 4))
          (then
            (br_if $fail (i32.ne (local.get $i) (local.get $len)))
            (return (i32.const 1))))
        ;; expect a '.' separator before the next component
        (br_if $fail (i32.ge_u (local.get $i) (local.get $len)))
        (br_if $fail (i32.ne (i32.load8_u (i32.add (local.get $ptr) (local.get $i))) (i32.const 46)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $comp)))
    (i32.const 0))

  ;; Plumb a connected tcp-socket into a slot: receive() yields the recv stream (its
  ;; future is dropped immediately, like the file read futures); a fresh stream pair is
  ;; created and its read end passed to send() -- callable at most once per socket --
  ;; while the write end stays in the slot for fd_write (its future is likewise dropped:
  ;; stream.write blocks until the bytes are accepted).
  (func $plumb (param $sock i32) (param $sl i32)
    (local $r64 i64)
    (call $tcp_receive (local.get $sock) (i32.const 0x500A0))
    (i32.store offset=4 (local.get $sl) (i32.load (i32.const 0x500A0)))
    (call $future_drop_sock (i32.load (i32.const 0x500A4)))
    (local.set $r64 (call $stream_new))
    (i32.store offset=8 (local.get $sl) (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (call $future_drop_sock (call $tcp_send (local.get $sock) (i32.wrap_i64 (local.get $r64))))
    (i32.store (local.get $sl) (local.get $sock))
    (i32.store offset=12 (local.get $sl) (i32.const 1)))

  ;; tcp-connect(hostPtr, hostLen, port, fdOut) -> errno. Connects to an IPv4 literal
  ;; (blocking: the sync-lowered async connect suspends cooperatively) and plumbs the
  ;; socket into a fresh slot.
  (func $tcp_connect (param $hp i32) (param $hl i32) (param $port i32) (param $fdout i32) (result i32)
    (local $idx i32) (local $sock i32)
    (local.set $idx (call $sock_alloc))
    (if (i32.eq (local.get $idx) (i32.const -1)) (then (return (i32.const 76))))
    (if (i32.eqz (call $parse_ipv4 (local.get $hp) (local.get $hl))) (then (return (i32.const 28))))
    (call $tcp_create (i32.const 0) (i32.const 0x50090))
    (if (i32.load8_u (i32.const 0x50090)) (then (return (i32.const 76))))
    (local.set $sock (i32.load offset=4 (i32.const 0x50090)))
    (call $tcp_connect_raw (local.get $sock) (i32.const 0)
      (local.get $port)
      (i32.load (i32.const 0x500C0)) (i32.load (i32.const 0x500C4))
      (i32.load (i32.const 0x500C8)) (i32.load (i32.const 0x500CC))
      (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0)
      (i32.const 0x50090))
    (if (i32.load8_u (i32.const 0x50090))
      (then
        (call $drop_tcp (local.get $sock))
        (return (i32.const 61))))
    (call $plumb (local.get $sock)
      (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 16))))
    (i32.store (local.get $fdout) (i32.add (i32.const 200) (local.get $idx)))
    (i32.const 0))

  ;; tcp-listen(hostPtr, hostLen, port, fdOut) -> errno. hostLen 0 binds all interfaces
  ;; (0.0.0.0); port 0 picks an ephemeral port (see tcp-local-port).
  (func $tcp_listen (param $hp i32) (param $hl i32) (param $port i32) (param $fdout i32) (result i32)
    (local $idx i32) (local $sock i32) (local $sl i32)
    (local.set $idx (call $sock_alloc))
    (if (i32.eq (local.get $idx) (i32.const -1)) (then (return (i32.const 76))))
    (if (i32.eqz (local.get $hl))
      (then
        (i32.store (i32.const 0x500C0) (i32.const 0))
        (i32.store (i32.const 0x500C4) (i32.const 0))
        (i32.store (i32.const 0x500C8) (i32.const 0))
        (i32.store (i32.const 0x500CC) (i32.const 0)))
      (else
        (if (i32.eqz (call $parse_ipv4 (local.get $hp) (local.get $hl)))
          (then (return (i32.const 28))))))
    (call $tcp_create (i32.const 0) (i32.const 0x50090))
    (if (i32.load8_u (i32.const 0x50090)) (then (return (i32.const 76))))
    (local.set $sock (i32.load offset=4 (i32.const 0x50090)))
    (call $tcp_bind (local.get $sock) (i32.const 0)
      (local.get $port)
      (i32.load (i32.const 0x500C0)) (i32.load (i32.const 0x500C4))
      (i32.load (i32.const 0x500C8)) (i32.load (i32.const 0x500CC))
      (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0)
      (i32.const 0x50090))
    (if (i32.load8_u (i32.const 0x50090))
      (then
        (call $drop_tcp (local.get $sock))
        (return (i32.const 48))))
    (call $tcp_listen_raw (local.get $sock) (i32.const 0x50090))
    (if (i32.load8_u (i32.const 0x50090))
      (then
        (call $drop_tcp (local.get $sock))
        (return (i32.const 48))))
    (local.set $sl (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 16))))
    (i32.store (local.get $sl) (local.get $sock))
    (i32.store offset=4 (local.get $sl) (i32.load offset=4 (i32.const 0x50090)))
    (i32.store offset=8 (local.get $sl) (i32.const 0))
    (i32.store offset=12 (local.get $sl) (i32.const 2))
    (i32.store (local.get $fdout) (i32.add (i32.const 200) (local.get $idx)))
    (i32.const 0))

  ;; tcp-accept(fd, fdOut) -> errno. Blocks (cooperatively) in a stream.read of one
  ;; tcp-socket handle on the listener's accept stream, then plumbs the connection.
  (func $tcp_accept (param $fd i32) (param $fdout i32) (result i32)
    (local $sl i32) (local $ret i32) (local $csock i32) (local $idx i32)
    (if (i32.lt_u (local.get $fd) (i32.const 200)) (then (return (i32.const 8))))
    (local.set $sl (call $sock_slot (local.get $fd)))
    (if (i32.ne (i32.load offset=12 (local.get $sl)) (i32.const 2)) (then (return (i32.const 8))))
    (local.set $ret (call $accept_read (i32.load offset=4 (local.get $sl)) (i32.const 0x500B0) (i32.const 1)))
    (if (i32.ne (i32.shr_u (local.get $ret) (i32.const 4)) (i32.const 1)) (then (return (i32.const 6))))
    (local.set $csock (i32.load (i32.const 0x500B0)))
    (local.set $idx (call $sock_alloc))
    (if (i32.eq (local.get $idx) (i32.const -1))
      (then
        (call $drop_tcp (local.get $csock))
        (return (i32.const 76))))
    (call $plumb (local.get $csock)
      (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 16))))
    (i32.store (local.get $fdout) (i32.add (i32.const 200) (local.get $idx)))
    (i32.const 0))

  ;; tcp-local-port(fd, portOut) -> errno. Reads the bound local port of a socket or
  ;; listener slot via get-local-address (result ok payload: the ip-socket-address
  ;; variant disc at +4, the ipv4 record's port u16 at +8).
  (func $tcp_local_port (param $fd i32) (param $portout i32) (result i32)
    (local $sl i32)
    (if (i32.lt_u (local.get $fd) (i32.const 200)) (then (return (i32.const 8))))
    (local.set $sl (call $sock_slot (local.get $fd)))
    (if (i32.eqz (i32.load offset=12 (local.get $sl))) (then (return (i32.const 8))))
    (call $tcp_local_addr (i32.load (local.get $sl)) (i32.const 0x50090))
    (if (i32.load8_u (i32.const 0x50090)) (then (return (i32.const 8))))
    (i32.store (local.get $portout) (i32.load16_u (i32.const 0x50098)))
    (i32.const 0))

  (export "fd_write" (func $fd_write))
  (export "fd_read" (func $fd_read))
  (export "path_open" (func $path_open))
  (export "fd_close" (func $fd_close))
  (export "random_get" (func $random_get))
  (export "clock_time_get" (func $clock_time_get))
  (export "environ_sizes_get" (func $environ_sizes_get))
  (export "environ_get" (func $environ_get))
  (export "tcp-connect" (func $tcp_connect))
  (export "tcp-listen" (func $tcp_listen))
  (export "tcp-accept" (func $tcp_accept))
  (export "tcp-local-port" (func $tcp_local_port)))
