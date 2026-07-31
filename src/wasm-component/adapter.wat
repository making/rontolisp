;; preview1-to-WASI-0.3 adapter core module.
;;
;; Imports the shared memory and the lowered WASI 0.3 functions plus the async canonical
;; built-ins (under "w"); exports the nine wasi_snapshot_preview1 functions rontolisp
;; imports. In WASI 0.3 the wasi:io package is gone and all byte I/O flows through the
;; built-in stream<u8> / future<T> types, so fd_write/fd_read/path_open/fd_close/fd_readdir
;; are implemented with stream.new/read/write/drop + future.read over wasi:cli + wasi:filesystem
;; 0.3; random_get/clock_time_get/environ_* bridge wasi:random / wasi:clocks
;; (system-clock, renamed from 0.2's wall-clock) / wasi:cli/environment. The environ_*
;; pair is UNREACHABLE from Lisp since todo 217: uiop:getenv under --component is
;; environment.lisp, which binds get-environment straight off this block's own
;; wasi:cli/environment instance (see ../../.kb/time-environment-builtins.md), so these
;; two stay only because the core's eight preview1 import slots are index-pinned.
;;
;; Straight-line synchronous code is fine WITHOUT any gated feature: the stream/future
;; built-ins are the ASYNC (non-blocking) variants of base component-model-async, and a
;; BLOCKED (-1) result parks the task on a blocking waitable-set.wait until the handle's
;; completion event arrives (legal from any async-typed task under the base feature; only
;; the synchronous built-in variants would need "more async builtins"). Each blocking
;; wrapper below keeps the sync-looking signature, so the preview1 logic is unchanged.
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
;;   0x50090 waitable-set event scratch {waitable@0x50090, payload@0x50094}
;;   0x5009c cached waitable-set handle (0 = not yet created)
;;   0x500a0 read-directory result tuple {stream@0x500a0, future@0x500a4}
;;   0x500b0 one lowered directory-entry (24 bytes: type variant @0, name ptr@16 len@20)
;;   0x50100 fd table: 64 slots x 16 bytes {descriptor@0, read-stream@4, valid@12}
;; A preview1 file fd is 100 + slotIndex (so it never clashes with stdout=1 or dirfd=3).
;; Writes use append-via-stream (each fd_write is a full append cycle, so no per-fd write
;; offset needs tracking) and await the write future; reads cache the readable stream per fd
;; and let it advance, dropping the read future immediately (EOF is signalled by the stream
;; status, not the future). wasi:cli and wasi:filesystem expose DISTINCT error-code enums,
;; so their future<result<_, error-code>> are distinct types needing separate built-ins
;; (suffix -cli vs -fs); stream<u8> is structural and shared.
;;
;; SINGLE-TASK BY DESIGN: this adapter keeps ONE cached waitable-set and fixed per-call
;; scratch cells. Its ops run only from synchronous boundaries (the run task, blocking
;; export wrappers), and $await_waitable's park is legal from a callback task too, so do
;; NOT generalize it to the core's per-task waitable-sets / context slots / doorbells --
;; that machinery lives in the generated core module (WasmFutureRuntimeBuilder).
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
  (import "w" "read-dir" (func $read_dir (param i32 i32)))
  (import "w" "get-random-u64" (func $rand_u64 (result i64)))
  (import "w" "drop-desc" (func $drop_desc (param i32)))
  ;; async canonical built-ins (the non-blocking variants; BLOCKED completes through
  ;; the waitable-set below). stream<u8> is structural (one set); future built-ins are
  ;; per-error-code-type: -cli for wasi:cli futures (stdout/stdin), -fs for
  ;; wasi:filesystem futures (file read/append).
  (import "w" "stream-new" (func $stream_new (result i64)))
  (import "w" "stream-read" (func $stream_read_a (param i32 i32 i32) (result i32)))
  (import "w" "stream-write" (func $stream_write_a (param i32 i32 i32) (result i32)))
  (import "w" "stream-drop-r" (func $stream_drop_r (param i32)))
  (import "w" "stream-drop-w" (func $stream_drop_w (param i32)))
  (import "w" "future-read-cli" (func $future_read_cli_a (param i32 i32) (result i32)))
  (import "w" "future-drop-cli" (func $future_drop_cli (param i32)))
  (import "w" "future-read-fs" (func $future_read_fs_a (param i32 i32) (result i32)))
  (import "w" "future-drop-fs" (func $future_drop_fs (param i32)))
  (import "w" "waitable-set-new" (func $ws_new (result i32)))
  (import "w" "waitable-join" (func $w_join (param i32 i32)))
  (import "w" "waitable-set-wait" (func $ws_wait (param i32 i32) (result i32)))
  ;; the directory-entry stream is a DISTINCT stream type from stream<u8> (elements own
  ;; a string), so it gets its own read / drop built-ins.
  (import "w" "stream-read-de" (func $stream_read_de_a (param i32 i32 i32) (result i32)))
  (import "w" "stream-drop-r-de" (func $stream_drop_r_de (param i32)))

  ;; The adapter's one waitable-set, created on first blocking completion.
  (func $ensure_ws (result i32)
    (if (i32.eqz (i32.load (i32.const 0x5009c)))
      (then (i32.store (i32.const 0x5009c) (call $ws_new))))
    (i32.load (i32.const 0x5009c)))

  ;; Parks the task until the given handle reports its completion event; returns the
  ;; event payload (the packed (count << 4) | status of the finished operation).
  (func $await_waitable (param $h i32) (result i32)
    (call $w_join (local.get $h) (call $ensure_ws))
    (block $got
      (loop $l
        (drop (call $ws_wait (call $ensure_ws) (i32.const 0x50090)))
        (br_if $got (i32.eq (i32.load (i32.const 0x50090)) (local.get $h)))
        (br $l)))
    (i32.load (i32.const 0x50094)))

  ;; Blocking wrappers with the sync-looking signatures the preview1 logic uses.
  (func $stream_read (param $h i32) (param $ptr i32) (param $len i32) (result i32)
    (local $ret i32)
    (local.set $ret (call $stream_read_a (local.get $h) (local.get $ptr) (local.get $len)))
    (if (i32.eq (local.get $ret) (i32.const -1))
      (then (local.set $ret (call $await_waitable (local.get $h)))))
    (local.get $ret))
  (func $stream_read_de (param $h i32) (param $ptr i32) (param $len i32) (result i32)
    (local $ret i32)
    (local.set $ret (call $stream_read_de_a (local.get $h) (local.get $ptr) (local.get $len)))
    (if (i32.eq (local.get $ret) (i32.const -1))
      (then (local.set $ret (call $await_waitable (local.get $h)))))
    (local.get $ret))
  (func $stream_write (param $h i32) (param $ptr i32) (param $len i32) (result i32)
    (local $ret i32)
    (local.set $ret (call $stream_write_a (local.get $h) (local.get $ptr) (local.get $len)))
    (if (i32.eq (local.get $ret) (i32.const -1))
      (then (local.set $ret (call $await_waitable (local.get $h)))))
    (local.get $ret))
  (func $future_read_cli (param $f i32) (param $ptr i32) (result i32)
    (local $ret i32)
    (local.set $ret (call $future_read_cli_a (local.get $f) (local.get $ptr)))
    (if (i32.eq (local.get $ret) (i32.const -1))
      (then (local.set $ret (call $await_waitable (local.get $f)))))
    (local.get $ret))
  (func $future_read_fs (param $f i32) (param $ptr i32) (result i32)
    (local $ret i32)
    (local.set $ret (call $future_read_fs_a (local.get $f) (local.get $ptr)))
    (if (i32.eq (local.get $ret) (i32.const -1))
      (then (local.set $ret (call $await_waitable (local.get $f)))))
    (local.get $ret))

  ;; Return the first preopened directory descriptor (cached).
  ;; The preopened directory descriptor, cached. -1 when the host granted NO preopen
  ;; (an empty get-directories list): reading the first element of an empty list would
  ;; hand open-at descriptor handle 0 and TRAP ("unknown handle index 0"), which no
  ;; handler-case can catch -- so the caller turns -1 into a plain errno instead, and a
  ;; program that probes for an optional file under a component with no --dir simply
  ;; sees "not there" like it does on Preview 1.
  (func $ensure_preopen (result i32)
    (if (i32.eqz (i32.load (i32.const 0x50040)))
      (then
        (call $get_directories (i32.const 0x50030))
        (i32.store (i32.const 0x50044)
          (if (result i32) (i32.load (i32.const 0x50034))
            (then (i32.load (i32.load (i32.const 0x50030))))
            (else (i32.const -1))))
        (i32.store (i32.const 0x50040) (i32.const 1))))
    (i32.load (i32.const 0x50044)))

  ;; fd table slot address for a preview1 file fd.
  (func $slot (param $fd i32) (result i32)
    (i32.add (i32.const 0x50100)
      (i32.mul (i32.sub (local.get $fd) (i32.const 100)) (i32.const 16))))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. fd==1 is stdout, fd==2 is stderr; otherwise
  ;; a file fd. One full stream is created per call: open it (write-via-stream for
  ;; stdout/stderr, append-via-stream for a file), push every iovec through it, signal EOF
  ;; by dropping the writable end, then await the operation future. stdout and stderr share
  ;; the wasi:cli error-code, so their future uses the -cli built-ins; a file uses -fs.
  (func $fd_write (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $r64 i64) (local $rx i32) (local $tx i32) (local $fut i32)
    (local $i i32) (local $base i32) (local $ptr i32) (local $len i32) (local $total i32) (local $sl i32)
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
    ;; No preopened directory: nothing can be opened, so report the failure as an errno.
    (if (i32.eq (local.get $pre) (i32.const -1)) (then (return (i32.const 76))))
    ;; descriptor-flags: write only when the caller asked to create or truncate
    ;; (oflags 9). A plain read is 1, and so is a DIRECTORY open (oflags 2) -- asking
    ;; for write on a directory fails, which is what an `(i32.eqz oflags)` test used to
    ;; do the moment %list-directory started opening one.
    (local.set $df (if (result i32) (i32.and (local.get $oflags) (i32.const 9))
      (then (i32.const 2)) (else (i32.const 1))))
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
    (if (i32.lt_u (local.get $fd) (i32.const 100)) (then (return (i32.const 0))))
    (local.set $sl (call $slot (local.get $fd)))
    (local.set $h (i32.load offset=4 (local.get $sl)))
    (if (i32.ne (local.get $h) (i32.const -1))
      (then (call $stream_drop_r (local.get $h))))
    (call $drop_desc (i32.load (local.get $sl)))
    (i32.store offset=12 (local.get $sl) (i32.const 0))
    (i32.const 0))

  ;; fd_readdir(fd, buf, buflen, cookie, used) -> errno. The preview1 shape over WASI
  ;; 0.3's read-directory, which always hands back a stream positioned at the START of
  ;; the directory -- so the cookie is simply "how many entries to skip", and each
  ;; emitted dirent's d_next is its 1-based index. Entries are read one at a time into
  ;; the 0x500b0 element scratch (the canonical ABI allocates each name through
  ;; cabi_realloc, which bumps the core's own HEAP_PTR -- the core advances HEAP_PTR
  ;; over its listing buffer for exactly that reason) and re-encoded into the caller's
  ;; buffer as {d_next u64, d_ino u64, d_namlen u32, d_type u8, pad} + the name bytes.
  ;; The walk stops when the next record would not fit whole, which is the signal the
  ;; core resumes on. "." and ".." never appear: read-directory omits them by contract.
  (func $fd_readdir (param $fd i32) (param $buf i32) (param $buflen i32) (param $cookie i64)
    (param $used i32) (result i32)
    (local $sl i32) (local $sr i32) (local $fut i32) (local $ret i32)
    (local $idx i64) (local $out i32) (local $np i32) (local $nl i32) (local $i i32)
    (i32.store (local.get $used) (i32.const 0))
    ;; only a real file fd names a descriptor (100 + slot); anything else is EBADF.
    (if (i32.lt_u (local.get $fd) (i32.const 100)) (then (return (i32.const 8))))
    (local.set $sl (call $slot (local.get $fd)))
    (call $read_dir (i32.load (local.get $sl)) (i32.const 0x500a0))
    (local.set $sr (i32.load (i32.const 0x500a0)))
    (local.set $fut (i32.load offset=4 (i32.const 0x500a0)))
    (local.set $out (local.get $buf))
    (block $done
      (loop $l
        (local.set $ret (call $stream_read_de (local.get $sr) (i32.const 0x500b0) (i32.const 1)))
        ;; (count << 4) | status; count 0 = the directory is exhausted
        (br_if $done (i32.eqz (i32.shr_u (local.get $ret) (i32.const 4))))
        (local.set $idx (i64.add (local.get $idx) (i64.const 1)))
        ;; skip everything the caller has already seen
        (if (i64.le_u (local.get $idx) (local.get $cookie)) (then (br $l)))
        (local.set $np (i32.load offset=16 (i32.const 0x500b0)))
        (local.set $nl (i32.load offset=20 (i32.const 0x500b0)))
        ;; a record must fit WHOLE or the caller could not decode it
        (br_if $done (i32.gt_u
          (i32.add (i32.sub (local.get $out) (local.get $buf)) (i32.add (i32.const 24) (local.get $nl)))
          (local.get $buflen)))
        (i64.store (local.get $out) (local.get $idx))
        (i64.store offset=8 (local.get $out) (i64.const 0))
        (i32.store offset=16 (local.get $out) (local.get $nl))
        (i32.store offset=20 (local.get $out) (call $p1_filetype (i32.load8_u (i32.const 0x500b0))))
        (local.set $i (i32.const 0))
        (block $copied
          (loop $cl
            (br_if $copied (i32.ge_u (local.get $i) (local.get $nl)))
            (i32.store8 (i32.add (i32.add (local.get $out) (i32.const 24)) (local.get $i))
              (i32.load8_u (i32.add (local.get $np) (local.get $i))))
            (local.set $i (i32.add (local.get $i) (i32.const 1)))
            (br $cl)))
        (local.set $out (i32.add (local.get $out) (i32.add (i32.const 24) (local.get $nl))))
        (br $l)))
    (call $stream_drop_r_de (local.get $sr))
    (drop (call $future_read_fs (local.get $fut) (i32.const 0x50000)))
    (call $future_drop_fs (local.get $fut))
    (i32.store (local.get $used) (i32.sub (local.get $out) (local.get $buf)))
    (i32.const 0))

  ;; wasi:filesystem descriptor-type case index -> preview1 filetype. Only "directory"
  ;; (case 2 -> 3) is load-bearing for %list-directory, but the whole table is mapped so
  ;; a caller reading d_type gets the preview1 answer it expects.
  (func $p1_filetype (param $t i32) (result i32)
    (if (i32.eq (local.get $t) (i32.const 0)) (then (return (i32.const 1))))  ;; block-device
    (if (i32.eq (local.get $t) (i32.const 1)) (then (return (i32.const 2))))  ;; character-device
    (if (i32.eq (local.get $t) (i32.const 2)) (then (return (i32.const 3))))  ;; directory
    (if (i32.eq (local.get $t) (i32.const 4)) (then (return (i32.const 7))))  ;; symbolic-link
    (if (i32.eq (local.get $t) (i32.const 5)) (then (return (i32.const 4))))  ;; regular-file
    (if (i32.eq (local.get $t) (i32.const 6)) (then (return (i32.const 6))))  ;; socket
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

  (export "fd_write" (func $fd_write))
  (export "fd_read" (func $fd_read))
  (export "path_open" (func $path_open))
  (export "fd_readdir" (func $fd_readdir))
  (export "fd_close" (func $fd_close))
  (export "random_get" (func $random_get))
  (export "clock_time_get" (func $clock_time_get))
  (export "environ_sizes_get" (func $environ_sizes_get))
  (export "environ_get" (func $environ_get)))
