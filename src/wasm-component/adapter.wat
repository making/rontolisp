;; preview1-to-WASI-0.3 adapter core module.
;;
;; Imports the shared memory and the lowered WASI 0.3 functions plus the async canonical
;; built-ins (under "w"); exports the twelve wasi_snapshot_preview1 functions rontolisp
;; imports. In WASI 0.3 the wasi:io package is gone and all byte I/O flows through the
;; built-in stream<u8> / future<T> types, so fd_write/fd_read/path_open/fd_close/fd_readdir/
;; fd_prestat_*/fd_filestat_get
;; are implemented with stream.new/read/write/drop + future.read over wasi:cli + wasi:filesystem
;; 0.3; random_get/clock_time_get/environ_* bridge wasi:random / wasi:clocks
;; (system-clock, renamed from 0.2's wall-clock) / wasi:cli/environment. The environ_*
;; pair is UNREACHABLE from Lisp since todo 217: uiop:getenv under --component is
;; environment.lisp, which binds get-environment straight off this block's own
;; wasi:cli/environment instance (see ../../.kb/time-environment-builtins.md), so these
;; two stay only because the core's preview1 import slots are index-pinned.
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
;;   0x50040 preopen table header {flag@0x50040, count@0x50044}
;;   0x50050 open-at result {disc@0x50050 byte, descriptor-or-errcode i32@0x50054}
;;   0x50060 file read-via-stream tuple {stream@0x50060, future@0x50064}
;;   0x50070 stdin read-via-stream tuple {stream@0x50070, future@0x50074}
;;   0x50080 stdin cache {flag@0x50080, stream@0x50084}
;;   0x50090 waitable-set event scratch {waitable@0x50090, payload@0x50094}
;;   0x5009c cached waitable-set handle (0 = not yet created)
;;   0x500a0 read-directory result tuple {stream@0x500a0, future@0x500a4}
;;   0x500b0 one lowered directory-entry (24 bytes: type variant @0, name ptr@16 len@20)
;;   0x50100 fd table: 64 slots x 16 bytes {descriptor@0, read-stream@4, valid@12}
;;   0x50500 preopen table: 16 slots x 264 bytes {descriptor@0, name-len@4, name@8..}
;;   0x51600 descriptor.stat result scratch: result<descriptor-stat, error-code>, 112
;;           bytes -- disc byte @0, descriptor-stat @8 (type @8, link-count @24, size @32)
;; A preview1 file fd is 100 + slotIndex (so it never clashes with stdout=1 or a
;; preopen dirfd, which is 3 + preopen index).
;; The preopen table is a COPY, taken once at the first $ensure_preopens: the
;; get-directories list and its name strings are lifted through cabi_realloc, which
;; allocates at the CORE's HEAP_PTR -- and the core pops that cell back after every
;; path resolution, so anything still pointing into the lifted list would be handed
;; out again as heap. The descriptors are copied for the same reason.
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
  (import "w" "desc-stat" (func $desc_stat (param i32 i32)))
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

  ;; Cache the WHOLE preopen table (descriptor + name) once, so preview1's
  ;; fd_prestat_get / fd_prestat_dir_name can answer and dirfd can MEAN something.
  ;; It used to cache only the first descriptor, which made every preopen but the
  ;; first unreachable and -- since nothing could learn what a preopen is CALLED --
  ;; every absolute path unopenable.
  ;;
  ;; An empty get-directories list leaves count 0: reading the first element of an
  ;; empty list would hand open-at descriptor handle 0 and TRAP ("unknown handle
  ;; index 0"), which no handler-case can catch -- so $path_open turns "no such
  ;; preopen index" into a plain errno instead, and a program that probes for an
  ;; optional file under a component with no --dir simply sees "not there" like it
  ;; does on Preview 1.
  ;;
  ;; A name longer than 256 bytes is recorded with length 0 rather than truncated: a
  ;; truncated name would compare equal to a prefix that is not the directory it
  ;; names, and a preopen the core cannot see is merely unreachable.
  (func $ensure_preopens
    (local $i i32) (local $n i32) (local $el i32) (local $sl i32) (local $len i32) (local $j i32)
    (if (i32.load (i32.const 0x50040)) (then (return)))
    (i32.store (i32.const 0x50040) (i32.const 1))
    (call $get_directories (i32.const 0x50030))
    (local.set $n (i32.load (i32.const 0x50034)))
    (if (i32.gt_u (local.get $n) (i32.const 16)) (then (local.set $n (i32.const 16))))
    (i32.store (i32.const 0x50044) (local.get $n))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $n)))
        (local.set $el (i32.add (i32.load (i32.const 0x50030)) (i32.mul (local.get $i) (i32.const 12))))
        (local.set $sl (i32.add (i32.const 0x50500) (i32.mul (local.get $i) (i32.const 264))))
        (i32.store (local.get $sl) (i32.load (local.get $el)))
        (local.set $len (i32.load offset=8 (local.get $el)))
        (if (i32.gt_u (local.get $len) (i32.const 256)) (then (local.set $len (i32.const 0))))
        (i32.store offset=4 (local.get $sl) (local.get $len))
        (local.set $j (i32.const 0))
        (block $copied
          (loop $c
            (br_if $copied (i32.ge_u (local.get $j) (local.get $len)))
            (i32.store8 offset=8 (i32.add (local.get $sl) (local.get $j))
              (i32.load8_u (i32.add (i32.load offset=4 (local.get $el)) (local.get $j))))
            (local.set $j (i32.add (local.get $j) (i32.const 1)))
            (br $c)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l))))

  ;; The descriptor of preopen index `idx`, or -1 when there is no such preopen.
  (func $preopen_desc (param $idx i32) (result i32)
    (call $ensure_preopens)
    (if (i32.ge_u (local.get $idx) (i32.load (i32.const 0x50044))) (then (return (i32.const -1))))
    (i32.load (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 264)))))

  ;; fd_prestat_get(fd, buf) -> errno. buf takes preview1's `prestat`: the tag byte at
  ;; 0 (0 = directory, the only kind preview1 defines) and pr_name_len at 4. EBADF (8)
  ;; for an fd that is not preopened, which is what ends the core's preopen walk.
  (func $fd_prestat_get (param $fd i32) (param $buf i32) (result i32)
    (local $idx i32)
    (call $ensure_preopens)
    (local.set $idx (i32.sub (local.get $fd) (i32.const 3)))
    (if (i32.ge_u (local.get $idx) (i32.load (i32.const 0x50044))) (then (return (i32.const 8))))
    (i32.store (local.get $buf) (i32.const 0))
    (i32.store offset=4 (local.get $buf)
      (i32.load offset=4 (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 264)))))
    (i32.const 0))

  ;; fd_prestat_dir_name(fd, path, path_len) -> errno. Copies the preopen's name into
  ;; the caller's buffer; ENAMETOOLONG (37) when it does not fit, EBADF (8) when the fd
  ;; is not preopened.
  (func $fd_prestat_dir_name (param $fd i32) (param $path i32) (param $plen i32) (result i32)
    (local $idx i32) (local $sl i32) (local $n i32) (local $i i32)
    (call $ensure_preopens)
    (local.set $idx (i32.sub (local.get $fd) (i32.const 3)))
    (if (i32.ge_u (local.get $idx) (i32.load (i32.const 0x50044))) (then (return (i32.const 8))))
    (local.set $sl (i32.add (i32.const 0x50500) (i32.mul (local.get $idx) (i32.const 264))))
    (local.set $n (i32.load offset=4 (local.get $sl)))
    (if (i32.gt_u (local.get $n) (local.get $plen)) (then (return (i32.const 37))))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $n)))
        (i32.store8 (i32.add (local.get $path) (local.get $i))
          (i32.load8_u offset=8 (i32.add (local.get $sl) (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    (i32.const 0))

  ;; fd table slot address for a preview1 file fd.
  (func $slot (param $fd i32) (result i32)
    (i32.add (i32.const 0x50100)
      (i32.mul (i32.sub (local.get $fd) (i32.const 100)) (i32.const 16))))

  ;; Push every iovec through the writable end, signal EOF by dropping it, and answer the
  ;; total byte count. Shared by the stdio and the file half of fd_write.
  (func $push_iovs (param $tx i32) (param $iov i32) (param $cnt i32) (result i32)
    (local $i i32) (local $base i32) (local $ptr i32) (local $len i32) (local $total i32)
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
    (local.get $total))

  ;; The stdio half of fd_write: fd 1 is stdout, anything else stderr (only 1 and 2 ever
  ;; reach it). One full stream per call -- open it with write-via-stream, push every
  ;; iovec, then await the operation future through the wasi:cli built-ins, which is the
  ;; error-code stdout and stderr share.
  ;;
  ;; It is EXPORTED as an alternative implementation of fd_write, for a program whose core
  ;; module imports no path_open: path_open is the only writer of the fd table below, so
  ;; without it no file fd can exist and the file half is dead. Retaining this one under
  ;; the name `fd_write` is what lets the whole wasi:filesystem surface leave the component
  ;; (WasmComponentBuilder, WasmExports.retain).
  (func $fd_write_stdio (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $r64 i64) (local $rx i32) (local $tx i32) (local $fut i32)
    ;; Anything but fd 1/2 is out of this implementation's contract. The WIDE fd_write
    ;; never routes one here, and a core with no path_open has no file fd -- but it can
    ;; still hold a SOCKET fd (>= 200), which reaches fd_write when a write form escapes
    ;; WasmSocketsRewrite's dispatch table. Under the wide adapter that walked off the fd
    ;; table and trapped inside the host; trap here too, rather than quietly divert the
    ;; bytes to stderr and report success.
    (if (i32.gt_u (local.get $fd) (i32.const 2)) (then (unreachable)))
    (local.set $r64 (call $stream_new))
    (local.set $rx (i32.wrap_i64 (local.get $r64)))
    (local.set $tx (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (local.set $fut
      (if (result i32) (i32.eq (local.get $fd) (i32.const 1))
        (then (call $stdout_write (local.get $rx)))
        (else (call $stderr_write (local.get $rx)))))
    (i32.store (local.get $nw) (call $push_iovs (local.get $tx) (local.get $iov) (local.get $cnt)))
    (drop (call $future_read_cli (local.get $fut) (i32.const 0x50000)))
    (call $future_drop_cli (local.get $fut))
    (i32.const 0))

  ;; The STDOUT-ONLY half of fd_write, one narrowing further than $fd_write_stdio: it
  ;; never mentions $stderr_write, so retaining it lets the whole wasi:cli/stderr
  ;; interface -- its instance type, its import, its alias and its lowering -- leave the
  ;; component. Whether a program can present fd 2 at all is a question about its SOURCE
  ;; (2 is the reserved *error-output* handle, materialized by nothing but that variable
  ;; and warn), not about this module, so the choice is made in WasmComponentBuilder; here
  ;; we only make the narrower contract explicit and REJECT what it does not implement.
  ;;
  ;; The body repeats $fd_write_stdio's rather than sharing it. Delegating to that function
  ;; would keep it -- and with it $stderr_write -- reachable, which is the one thing this
  ;; half exists to avoid; and hoisting the common tail into a third function turned out to
  ;; cost more (its own signature, entry and five-argument call) than the duplicated
  ;; instructions, in BOTH shaken adapters -- measured, +21 B each. The two bodies are
  ;; deliberately adjacent so a change to the write/await discipline is made twice.
  (func $fd_write_stdout (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $r64 i64) (local $rx i32) (local $tx i32) (local $fut i32)
    (if (i32.ne (local.get $fd) (i32.const 1)) (then (unreachable)))
    (local.set $r64 (call $stream_new))
    (local.set $rx (i32.wrap_i64 (local.get $r64)))
    (local.set $tx (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (local.set $fut (call $stdout_write (local.get $rx)))
    (i32.store (local.get $nw) (call $push_iovs (local.get $tx) (local.get $iov) (local.get $cnt)))
    (drop (call $future_read_cli (local.get $fut) (i32.const 0x50000)))
    (call $future_drop_cli (local.get $fut))
    (i32.const 0))

  ;; The file half of fd_write: append-via-stream on the slot's descriptor, awaited through
  ;; the wasi:filesystem built-ins (its error-code is a different type from wasi:cli's).
  (func $fd_write_file (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $r64 i64) (local $rx i32) (local $tx i32) (local $fut i32) (local $sl i32)
    (local.set $r64 (call $stream_new))
    (local.set $rx (i32.wrap_i64 (local.get $r64)))
    (local.set $tx (i32.wrap_i64 (i64.shr_u (local.get $r64) (i64.const 32))))
    (local.set $sl (call $slot (local.get $fd)))
    (local.set $fut (call $file_append (i32.load (local.get $sl)) (local.get $rx)))
    (i32.store (local.get $nw) (call $push_iovs (local.get $tx) (local.get $iov) (local.get $cnt)))
    (drop (call $future_read_fs (local.get $fut) (i32.const 0x50000)))
    (call $future_drop_fs (local.get $fut))
    (i32.const 0))

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. fd==1 is stdout, fd==2 is stderr; otherwise
  ;; a file fd.
  (func $fd_write (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (if (i32.or (i32.eq (local.get $fd) (i32.const 1)) (i32.eq (local.get $fd) (i32.const 2)))
      (then (return (call $fd_write_stdio
        (local.get $fd) (local.get $iov) (local.get $cnt) (local.get $nw)))))
    (call $fd_write_file (local.get $fd) (local.get $iov) (local.get $cnt) (local.get $nw)))

  ;; Read one iovec out of a readable stream. stream.read writes straight into the
  ;; shared-memory destination; the return value is (count << 4) | status, with status
  ;; 0 = completed, 1 = dropped (EOF). count 0 = EOF.
  (func $read_iov (param $ins i32) (param $iov i32) (param $nread i32) (result i32)
    (i32.store (local.get $nread)
      (i32.shr_u
        (call $stream_read (local.get $ins) (i32.load (local.get $iov)) (i32.load offset=4 (local.get $iov)))
        (i32.const 4)))
    (i32.const 0))

  ;; The stdin half of fd_read: a cached wasi:cli/stdin readable stream. Exported as an
  ;; alternative implementation of fd_read for the same reason as $fd_write_stdio.
  (func $fd_read_stdin (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    ;; Same contract as $fd_write_stdio: only fd 0 belongs here, and answering a socket fd
    ;; with stdin's bytes would be a silent wrong answer where the wide adapter trapped.
    (if (local.get $fd) (then (unreachable)))
    (if (i32.eqz (i32.load (i32.const 0x50080)))
      (then
        (call $stdin_read (i32.const 0x50070))
        (i32.store (i32.const 0x50084) (i32.load (i32.const 0x50070)))
        (call $future_drop_cli (i32.load (i32.const 0x50074)))
        (i32.store (i32.const 0x50080) (i32.const 1))))
    (call $read_iov (i32.load (i32.const 0x50084)) (local.get $iov) (local.get $nread)))

  ;; The file half of fd_read: the slot's readable stream, opened on first use and left to
  ;; advance across calls.
  (func $fd_read_file (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    (local $sl i32) (local $ins i32)
    (local.set $sl (call $slot (local.get $fd)))
    (local.set $ins (i32.load offset=4 (local.get $sl)))
    (if (i32.eq (local.get $ins) (i32.const -1))
      (then
        (call $file_read (i32.load (local.get $sl)) (i64.const 0) (i32.const 0x50060))
        (local.set $ins (i32.load (i32.const 0x50060)))
        (i32.store offset=4 (local.get $sl) (local.get $ins))
        (call $future_drop_fs (i32.load (i32.const 0x50064)))))
    (call $read_iov (local.get $ins) (local.get $iov) (local.get $nread)))

  ;; fd_read(fd, iov, cnt, nread) -> errno. Single-iovec; nread==0 signals EOF. fd==0 is
  ;; stdin; otherwise a file fd.
  (func $fd_read (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    (if (i32.eqz (local.get $fd))
      (then (return (call $fd_read_stdin
        (local.get $fd) (local.get $iov) (local.get $cnt) (local.get $nread)))))
    (call $fd_read_file (local.get $fd) (local.get $iov) (local.get $cnt) (local.get $nread)))

  ;; path_open(dirfd, dirflags, pptr, plen, oflags, rights_base, rights_inh, fdflags, fdout)
  ;; -> errno. dirfd NAMES a preopen (3 + preopen index), which is what lets the core
  ;; resolve an absolute path against the preopen that covers it rather than against
  ;; the first one; it used to be ignored. oflags 0 = read, 9 = write
  ;; (create|truncate, same bit values as WASI 0.3 open-flags).
  (func $path_open
    (param $dirfd i32) (param $dirflags i32) (param $pptr i32) (param $plen i32)
    (param $oflags i32) (param $rb i64) (param $ri i64) (param $fdflags i32) (param $fdout i32) (result i32)
    (local $pre i32) (local $df i32) (local $idx i32) (local $sl i32)
    (local.set $pre (call $preopen_desc (i32.sub (local.get $dirfd) (i32.const 3))))
    ;; No such preopened directory: nothing can be opened, so report the failure as an
    ;; errno.
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

  ;; fd_filestat_get(fd, buf) -> errno. The preview1 shape over WASI 0.3's
  ;; descriptor.stat: the SYNC (blocking) lowering of an async func with no parameters,
  ;; so the call is (self, retptr) and the whole result lands in memory.
  ;;
  ;; result<descriptor-stat, error-code> at 0x51600 is 112 bytes, align 8: the result
  ;; discriminant byte at 0 (0 = ok), the payload at 8, and inside descriptor-stat the
  ;; `type` variant at +0 (its case index in the first byte), `link-count` u64 at +16
  ;; and `size` u64 at +24. So type is at 0x51608, link-count at 0x51618 and size at
  ;; 0x51620.
  ;;
  ;; The written preview1 `filestat` fills dev/ino with 0 and the three timestamps with
  ;; 0: the core reads only filetype and size (file-length), and inventing a device or a
  ;; time that is not the time is exactly what a stub may not do. A consumer of the
  ;; timestamps -- file-write-date, the day it stops answering nil here -- lifts them
  ;; from the two option<instant> fields that follow `size`.
  ;;
  ;; Only a real file fd (100 + slot) names a descriptor; anything else -- a standard
  ;; stream, a socket handle, an fd past the table -- is EBADF, which the core reads as
  ;; "the length cannot be determined" and answers nil for.
  (func $fd_filestat_get (param $fd i32) (param $buf i32) (result i32)
    (local $sl i32)
    (if (i32.lt_u (local.get $fd) (i32.const 100)) (then (return (i32.const 8))))
    (if (i32.ge_u (local.get $fd) (i32.const 164)) (then (return (i32.const 8))))
    (local.set $sl (call $slot (local.get $fd)))
    (if (i32.eqz (i32.load offset=12 (local.get $sl))) (then (return (i32.const 8))))
    (call $desc_stat (i32.load (local.get $sl)) (i32.const 0x51600))
    (if (i32.load8_u (i32.const 0x51600)) (then (return (i32.const 76))))
    (i64.store (local.get $buf) (i64.const 0))                                  ;; dev
    (i64.store offset=8 (local.get $buf) (i64.const 0))                         ;; ino
    (i32.store8 offset=16 (local.get $buf)
      (call $p1_filetype (i32.load8_u (i32.const 0x51608))))                    ;; filetype
    (i64.store offset=24 (local.get $buf) (i64.load (i32.const 0x51618)))       ;; nlink
    (i64.store offset=32 (local.get $buf) (i64.load (i32.const 0x51620)))       ;; size
    (i64.store offset=40 (local.get $buf) (i64.const 0))                        ;; atim
    (i64.store offset=48 (local.get $buf) (i64.const 0))                        ;; mtim
    (i64.store offset=56 (local.get $buf) (i64.const 0))                        ;; ctim
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
  ;; The stdio-only / stdout-only / stdin-only implementations, retained UNDER the names
  ;; above by WasmComponentBuilder when the core imports no path_open (see
  ;; $fd_write_stdio) and, for the stdout-only one, cannot present fd 2 either.
  (export "fd_write_stdio" (func $fd_write_stdio))
  (export "fd_write_stdout" (func $fd_write_stdout))
  (export "fd_read_stdin" (func $fd_read_stdin))
  (export "path_open" (func $path_open))
  (export "fd_readdir" (func $fd_readdir))
  (export "fd_close" (func $fd_close))
  (export "random_get" (func $random_get))
  (export "clock_time_get" (func $clock_time_get))
  (export "environ_sizes_get" (func $environ_sizes_get))
  (export "environ_get" (func $environ_get))
  (export "fd_prestat_get" (func $fd_prestat_get))
  (export "fd_prestat_dir_name" (func $fd_prestat_dir_name))
  (export "fd_filestat_get" (func $fd_filestat_get)))
