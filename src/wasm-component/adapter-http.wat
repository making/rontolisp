;; preview1-to-WASI-0.2 adapter core module.
;;
;; Imports the shared memory and the lowered WASI 0.2 functions (under "w"); exports the
;; eight wasi_snapshot_preview1 functions rontolisp imports. fd_write/fd_read/path_open/
;; fd_close implement file I/O over wasi:filesystem + wasi:io/streams using a small fd
;; table; random_get/clock_time_get/environ_* bridge wasi:random / wasi:clocks /
;; wasi:cli/environment.
;;
;; Outgoing HTTP (fetch) is also implemented here over wasi:http + wasi:io/poll: it parses
;; the URL, builds an outgoing-request (method/scheme/authority/path + request headers),
;; sends it via outgoing-handler.handle, blocks on the response pollable, then serializes
;; the response status, headers and body back for the rontolisp core. The fetch result
;; scratch lives above the fd table in page 5; the serialized response-header buffer uses
;; page 6 (0x60000) and the response body uses pages 7+ (0x70000, capped at ~0x90000).
;;
;; All scratch lives in page 5 (0x50000+), clear of rontolisp's data/heap (which grow from
;; 128 / 16384 through pages 0-3), its environ scratch (page 3) and the canonical realloc
;; heap (from 65536). The shared memory module is 16 pages so pages 5-15 exist. Layout:
;;   0x50000 write result area (result<_, stream-error>)
;;   0x50010 wall-clock datetime scratch (seconds u64 @0x50010, nanoseconds u32 @0x50018)
;;   0x50020 environ list {ptr@0x50020, count@0x50024}
;;   0x50030 get-directories list {ptr@0x50030, count@0x50034}
;;   0x50040 preopen descriptor cache {flag@0x50040, descriptor@0x50044}
;;   0x50050 open-at result {disc@0x50050 byte, descriptor-or-errcode i32@0x50054}
;;   0x50060 read/write-via-stream result {disc@0x50060 byte, stream i32@0x50064}
;;   0x50070 blocking-read result {disc@0x50070 byte, list ptr@0x50074, len@0x50078}
;;   0x50080 stdin input-stream cache {flag@0x50080, stream@0x50084}
;;   0x50100 fd table: 64 slots x 16 bytes {descriptor@0, in-stream@4, out-stream@8, valid@12}
;; A preview1 file fd is 100 + slotIndex (so it never clashes with stdout=1 or dirfd=3).
(module
  (import "mem" "memory" (memory (;0;) 16))
  (import "w" "get-stdout" (func $get_stdout (result i32)))
  (import "w" "write" (func $write (param i32 i32 i32 i32)))
  (import "w" "read" (func $read (param i32 i64 i32)))
  (import "w" "drop-out" (func $drop_out (param i32)))
  (import "w" "drop-in" (func $drop_in (param i32)))
  (import "w" "drop-desc" (func $drop_desc (param i32)))
  (import "w" "get-random-u64" (func $rand_u64 (result i64)))
  (import "w" "wall-now" (func $wall_now (param i32)))
  (import "w" "mono-now" (func $mono_now (result i64)))
  (import "w" "get-environment" (func $getenviron (param i32)))
  (import "w" "open-at" (func $open_at (param i32 i32 i32 i32 i32 i32 i32)))
  (import "w" "read-via-stream" (func $read_via_stream (param i32 i64 i32)))
  (import "w" "write-via-stream" (func $write_via_stream (param i32 i64 i32)))
  (import "w" "get-directories" (func $get_directories (param i32)))
  (import "w" "get-stdin" (func $get_stdin (result i32)))
  ;; --- outgoing HTTP (rontolisp:fetch) ---
  (import "w" "poll-block" (func $poll_block (param i32)))
  (import "w" "fields-new" (func $fields_new (result i32)))
  (import "w" "fields-append" (func $fields_append (param i32 i32 i32 i32 i32 i32)))
  (import "w" "fields-entries" (func $fields_entries (param i32 i32)))
  (import "w" "req-new" (func $req_new (param i32) (result i32)))
  (import "w" "set-method" (func $set_method (param i32 i32 i32 i32) (result i32)))
  (import "w" "set-scheme" (func $set_scheme (param i32 i32 i32 i32 i32) (result i32)))
  (import "w" "set-authority" (func $set_authority (param i32 i32 i32 i32) (result i32)))
  (import "w" "set-path" (func $set_path (param i32 i32 i32 i32) (result i32)))
  (import "w" "handle" (func $handle (param i32 i32 i32 i32)))
  (import "w" "future-subscribe" (func $future_subscribe (param i32) (result i32)))
  (import "w" "future-get" (func $future_get (param i32 i32)))
  (import "w" "resp-status" (func $resp_status (param i32) (result i32)))
  (import "w" "resp-headers" (func $resp_headers (param i32) (result i32)))
  (import "w" "resp-consume" (func $resp_consume (param i32 i32)))
  (import "w" "body-stream" (func $body_stream (param i32 i32)))
  (import "w" "drop-pollable" (func $drop_pollable (param i32)))
  (import "w" "drop-fields" (func $drop_fields (param i32)))
  (import "w" "drop-future" (func $drop_future (param i32)))
  (import "w" "drop-resp" (func $drop_resp (param i32)))
  (import "w" "drop-body" (func $drop_body (param i32)))
  ;; --- request body (rontolisp:fetch :body) ---
  ;; req-body/body-write return result<own<...>> into a return pointer; body-finish takes
  ;; the body, an option<trailers> (disc, handle) and a result return pointer.
  (import "w" "req-body" (func $req_body (param i32 i32)))
  (import "w" "body-write" (func $body_write (param i32 i32)))
  (import "w" "body-finish" (func $body_finish (param i32 i32 i32 i32)))
  (import "w" "drop-outgoing-body" (func $drop_outgoing_body (param i32)))

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

  ;; fd_write(fd, iov, cnt, nwritten) -> errno. fd==1 is stdout; otherwise a file fd.
  (func $fd_write (param $fd i32) (param $iov i32) (param $cnt i32) (param $nw i32) (result i32)
    (local $os i32) (local $i i32) (local $ptr i32) (local $len i32) (local $total i32)
    (local $base i32) (local $sl i32) (local $isfile i32)
    (if (i32.eq (local.get $fd) (i32.const 1))
      (then
        (local.set $os (call $get_stdout)))
      (else
        (local.set $isfile (i32.const 1))
        (local.set $sl (call $slot (local.get $fd)))
        (local.set $os (i32.load offset=8 (local.get $sl)))
        (if (i32.eq (local.get $os) (i32.const -1))
          (then
            (call $write_via_stream (i32.load (local.get $sl)) (i64.const 0) (i32.const 0x50060))
            (local.set $os (i32.load offset=4 (i32.const 0x50060)))
            (i32.store offset=8 (local.get $sl) (local.get $os))))))
    (block $done
      (loop $l
        (br_if $done (i32.ge_u (local.get $i) (local.get $cnt)))
        (local.set $base (i32.add (local.get $iov) (i32.mul (local.get $i) (i32.const 8))))
        (local.set $ptr (i32.load (local.get $base)))
        (local.set $len (i32.load offset=4 (local.get $base)))
        (call $write (local.get $os) (local.get $ptr) (local.get $len) (i32.const 0x50000))
        (local.set $total (i32.add (local.get $total) (local.get $len)))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $l)))
    ;; stdout streams are fetched per call and dropped; file streams persist until close
    (if (i32.eqz (local.get $isfile)) (then (call $drop_out (local.get $os))))
    (i32.store (local.get $nw) (local.get $total))
    (i32.const 0))

  ;; fd_read(fd, iov, cnt, nread) -> errno. Single-iovec; nread==0 signals EOF. fd==0 is
  ;; stdin (a cached wasi:cli/stdin input-stream); otherwise a file fd.
  (func $fd_read (param $fd i32) (param $iov i32) (param $cnt i32) (param $nread i32) (result i32)
    (local $sl i32) (local $ins i32) (local $ptr i32) (local $len i32) (local $n i32) (local $src i32) (local $j i32)
    (if (i32.eqz (local.get $fd))
      (then
        ;; stdin: cache the input-stream from get-stdin (flag@0x50080, stream@0x50084)
        (if (i32.eqz (i32.load (i32.const 0x50080)))
          (then
            (i32.store (i32.const 0x50084) (call $get_stdin))
            (i32.store (i32.const 0x50080) (i32.const 1))))
        (local.set $ins (i32.load (i32.const 0x50084))))
      (else
        (local.set $sl (call $slot (local.get $fd)))
        (local.set $ins (i32.load offset=4 (local.get $sl)))
        (if (i32.eq (local.get $ins) (i32.const -1))
          (then
            (call $read_via_stream (i32.load (local.get $sl)) (i64.const 0) (i32.const 0x50060))
            (if (i32.load8_u (i32.const 0x50060))
              (then (i32.store (local.get $nread) (i32.const 0)) (return (i32.const 0))))
            (local.set $ins (i32.load offset=4 (i32.const 0x50060)))
            (i32.store offset=4 (local.get $sl) (local.get $ins))))))
    (local.set $ptr (i32.load (local.get $iov)))
    (local.set $len (i32.load offset=4 (local.get $iov)))
    (call $read (local.get $ins) (i64.extend_i32_u (local.get $len)) (i32.const 0x50070))
    ;; err (e.g. closed) => EOF
    (if (i32.load8_u (i32.const 0x50070))
      (then (i32.store (local.get $nread) (i32.const 0)) (return (i32.const 0))))
    (local.set $src (i32.load offset=4 (i32.const 0x50070)))
    (local.set $n (i32.load offset=8 (i32.const 0x50070)))
    (block $cd
      (loop $c
        (br_if $cd (i32.ge_u (local.get $j) (local.get $n)))
        (i32.store8 (i32.add (local.get $ptr) (local.get $j))
          (i32.load8_u (i32.add (local.get $src) (local.get $j))))
        (local.set $j (i32.add (local.get $j) (i32.const 1)))
        (br $c)))
    (i32.store (local.get $nread) (local.get $n))
    (i32.const 0))

  ;; path_open(dirfd, dirflags, pptr, plen, oflags, rights_base, rights_inh, fdflags, fdout)
  ;; -> errno. dirfd is ignored (the preopened dir is used). oflags 0 = read, 9 = write
  ;; (create|truncate, same bit values as WASI 0.2 open-flags).
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
    ;; find a free fd-table slot
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

  ;; fd_close(fd) -> errno. Drops the cached streams and the descriptor, frees the slot.
  (func $fd_close (param $fd i32) (result i32)
    (local $sl i32) (local $h i32)
    (if (i32.lt_u (local.get $fd) (i32.const 100)) (then (return (i32.const 0))))
    (local.set $sl (call $slot (local.get $fd)))
    (local.set $h (i32.load offset=4 (local.get $sl)))
    (if (i32.ne (local.get $h) (i32.const -1)) (then (call $drop_in (local.get $h))))
    (local.set $h (i32.load offset=8 (local.get $sl)))
    (if (i32.ne (local.get $h) (i32.const -1)) (then (call $drop_out (local.get $h))))
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

  ;; clock_time_get(clock_id, precision, resptr) -> errno. 0 = realtime (wall), else
  ;; monotonic. Writes nanoseconds as i64.
  (func $clock_time_get (param $clkid i32) (param $prec i64) (param $resptr i32) (result i32)
    (if (i32.eqz (local.get $clkid))
      (then
        (call $wall_now (i32.const 0x50010))
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

  ;; fetch(method, url_ptr, url_len, req_body_ptr, req_body_len, rhdr_ptr, rhdr_len, st_ptr,
  ;;       rhdr_out, rhdr_len_out, body_out, body_len_out) -> errno. Performs an outgoing
  ;; request over wasi:http and writes the status, a serialized response-header buffer (at
  ;; 0x60000) and the response body bytes (at 0x70000) back through the out pointers. method
  ;; is the WASI http method variant discriminant (0=get,1=head,2=post,3=put,4=delete,
  ;; 6=options,8=patch). When req_body_len > 0 the req_body_ptr..+len bytes (read straight
  ;; from the shared memory) are streamed as the request body. Request headers arrive as a
  ;; count-prefixed buffer:
  ;;   count:u32, then per header { name_len:u32, name bytes, value_len:u32, value bytes }.
  ;; The response-header buffer uses the same layout. Canonical result scratch lives above
  ;; the fd table in page 5; the header/body output buffers use pages 6 and 7+.
  (func $fetch
    (param $method i32) (param $url_ptr i32) (param $url_len i32)
    (param $req_body_ptr i32) (param $req_body_len i32)
    (param $rhdr_ptr i32) (param $rhdr_len i32)
    (param $st_ptr i32) (param $rhdr_out i32) (param $rhdr_len_out i32)
    (param $body_out i32) (param $body_len_out i32) (result i32)
    (local $fields i32) (local $req i32) (local $future i32) (local $poll i32)
    (local $resp i32) (local $rh i32) (local $body i32) (local $stream i32)
    (local $i i32) (local $colon i32) (local $isHttps i32)
    (local $authStart i32) (local $authLen i32) (local $pathStart i32) (local $pathLen i32)
    (local $p i32) (local $cnt i32) (local $k i32) (local $nl i32) (local $vl i32) (local $np i32) (local $vp i32)
    (local $ep i32) (local $ecnt i32) (local $hp i32) (local $e i32)
    (local $out i32) (local $total i32) (local $src i32) (local $n i32) (local $j i32)
    (local $obody i32) (local $bstream i32) (local $bi i32) (local $chunk i32)

    ;; --- parse URL: find "://" ---
    (local.set $colon (i32.const -1))
    (block $fc (loop $sc
      (br_if $fc (i32.gt_u (i32.add (local.get $i) (i32.const 3)) (local.get $url_len)))
      (if (i32.and
            (i32.eq (i32.load8_u (i32.add (local.get $url_ptr) (local.get $i))) (i32.const 58))
            (i32.and
              (i32.eq (i32.load8_u (i32.add (local.get $url_ptr) (i32.add (local.get $i) (i32.const 1)))) (i32.const 47))
              (i32.eq (i32.load8_u (i32.add (local.get $url_ptr) (i32.add (local.get $i) (i32.const 2)))) (i32.const 47))))
        (then (local.set $colon (local.get $i)) (br $fc)))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $sc)))
    (if (i32.eq (local.get $colon) (i32.const -1)) (then (return (i32.const 1))))
    (local.set $isHttps (i32.eq (local.get $colon) (i32.const 5)))
    (local.set $authStart (i32.add (local.get $colon) (i32.const 3)))
    (local.set $i (local.get $authStart))
    (local.set $authLen (i32.const -1))
    (block $fa (loop $sa
      (br_if $fa (i32.ge_u (local.get $i) (local.get $url_len)))
      (if (i32.eq (i32.load8_u (i32.add (local.get $url_ptr) (local.get $i))) (i32.const 47))
        (then (local.set $authLen (i32.sub (local.get $i) (local.get $authStart))) (br $fa)))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $sa)))
    (if (i32.eq (local.get $authLen) (i32.const -1))
      (then
        (local.set $authLen (i32.sub (local.get $url_len) (local.get $authStart)))
        (i32.store8 (i32.const 0x50890) (i32.const 47))
        (local.set $pathStart (i32.const 0x50890))
        (local.set $pathLen (i32.const 1)))
      (else
        (local.set $pathStart (i32.add (local.get $url_ptr) (i32.add (local.get $authStart) (local.get $authLen))))
        (local.set $pathLen (i32.sub (local.get $url_len) (i32.add (local.get $authStart) (local.get $authLen))))))

    ;; --- build request ---
    (local.set $fields (call $fields_new))
    (local.set $p (local.get $rhdr_ptr))
    (local.set $cnt (i32.load (local.get $p)))
    (local.set $p (i32.add (local.get $p) (i32.const 4)))
    (block $hd (loop $h
      (br_if $hd (i32.ge_u (local.get $k) (local.get $cnt)))
      (local.set $nl (i32.load (local.get $p)))
      (local.set $np (i32.add (local.get $p) (i32.const 4)))
      (local.set $p (i32.add (local.get $np) (local.get $nl)))
      (local.set $vl (i32.load (local.get $p)))
      (local.set $vp (i32.add (local.get $p) (i32.const 4)))
      (local.set $p (i32.add (local.get $vp) (local.get $vl)))
      (call $fields_append (local.get $fields) (local.get $np) (local.get $nl) (local.get $vp) (local.get $vl)
        (i32.const 0x50870))
      (local.set $k (i32.add (local.get $k) (i32.const 1)))
      (br $h)))
    (local.set $req (call $req_new (local.get $fields)))
    (drop (call $set_method (local.get $req) (local.get $method) (i32.const 0) (i32.const 0)))
    (drop (call $set_scheme (local.get $req) (i32.const 1) (local.get $isHttps) (i32.const 0) (i32.const 0)))
    (drop (call $set_authority (local.get $req) (i32.const 1)
      (i32.add (local.get $url_ptr) (local.get $authStart)) (local.get $authLen)))
    (drop (call $set_path (local.get $req) (i32.const 1) (local.get $pathStart) (local.get $pathLen)))

    ;; Obtain the outgoing-body BEFORE handle consumes the request (if a body was given).
    ;; req-body -> result<own<outgoing-body>>: disc@0x508A0, handle@0x508A4.
    (if (i32.gt_u (local.get $req_body_len) (i32.const 0))
      (then
        (call $req_body (local.get $req) (i32.const 0x508A0))
        (if (i32.load8_u (i32.const 0x508A0)) (then (return (i32.const 8))))
        (local.set $obody (i32.load (i32.const 0x508A4)))))

    ;; --- handle + await ---
    ;; handle -> result<future-incoming-response, error-code>. error-code is 8-byte
    ;; aligned (it has a u64-bearing case), so the ok payload (the future handle) sits at
    ;; offset 8, not 4.
    (call $handle (local.get $req) (i32.const 0) (i32.const 0) (i32.const 0x50800))
    (if (i32.load8_u (i32.const 0x50800)) (then (return (i32.const 2))))
    (local.set $future (i32.load (i32.const 0x50808)))

    ;; Stream the request body (if any), then finish it, before awaiting the response.
    (if (i32.gt_u (local.get $req_body_len) (i32.const 0))
      (then
        ;; stream = outgoing-body.write(obody) -> result<own<output-stream>>: handle@0x508B4
        (call $body_write (local.get $obody) (i32.const 0x508B0))
        (if (i32.load8_u (i32.const 0x508B0)) (then (return (i32.const 9))))
        (local.set $bstream (i32.load (i32.const 0x508B4)))
        ;; write in <=4096-byte chunks (blocking-write-and-flush's per-call limit)
        (local.set $bi (i32.const 0))
        (block $wd (loop $wl
          (br_if $wd (i32.ge_u (local.get $bi) (local.get $req_body_len)))
          (local.set $chunk (i32.sub (local.get $req_body_len) (local.get $bi)))
          (if (i32.gt_u (local.get $chunk) (i32.const 4096)) (then (local.set $chunk (i32.const 4096))))
          (call $write (local.get $bstream)
            (i32.add (local.get $req_body_ptr) (local.get $bi)) (local.get $chunk) (i32.const 0x508C0))
          (if (i32.load8_u (i32.const 0x508C0)) (then (return (i32.const 10))))
          (local.set $bi (i32.add (local.get $bi) (local.get $chunk)))
          (br $wl)))
        ;; the output-stream is a child of the outgoing-body: drop it before finish
        (call $drop_out (local.get $bstream))
        ;; finish(obody, none) -- trailers option = none (disc 0, handle 0); result ignored
        (call $body_finish (local.get $obody) (i32.const 0) (i32.const 0) (i32.const 0x508D0))))

    (local.set $poll (call $future_subscribe (local.get $future)))
    (call $poll_block (local.get $poll))
    (call $drop_pollable (local.get $poll))
    ;; get -> option<result<result<incoming-response, error-code>>>. Everything aligns to
    ;; 8 (error-code), so: option disc @0, inner discs @8 and @16, incoming-response @24.
    (call $future_get (local.get $future) (i32.const 0x50810))
    (if (i32.eqz (i32.load8_u (i32.const 0x50810))) (then (return (i32.const 3))))
    (if (i32.load8_u (i32.const 0x50818)) (then (return (i32.const 4))))
    (if (i32.load8_u (i32.const 0x50820)) (then (return (i32.const 5))))
    (local.set $resp (i32.load (i32.const 0x50828)))

    ;; --- status ---
    (i32.store (local.get $st_ptr) (call $resp_status (local.get $resp)))

    ;; --- response headers -> serialize to 0x60000 ---
    (local.set $rh (call $resp_headers (local.get $resp)))
    (call $fields_entries (local.get $rh) (i32.const 0x50860))
    (local.set $ep (i32.load (i32.const 0x50860)))
    (local.set $ecnt (i32.load (i32.const 0x50864)))
    (local.set $hp (i32.const 0x60000))
    (i32.store (local.get $hp) (local.get $ecnt))
    (local.set $hp (i32.add (local.get $hp) (i32.const 4)))
    (local.set $i (i32.const 0))
    (block $ed (loop $en
      (br_if $ed (i32.ge_u (local.get $i) (local.get $ecnt)))
      (local.set $e (i32.add (local.get $ep) (i32.mul (local.get $i) (i32.const 16))))
      (local.set $np (i32.load (local.get $e)))
      (local.set $nl (i32.load offset=4 (local.get $e)))
      (local.set $vp (i32.load offset=8 (local.get $e)))
      (local.set $vl (i32.load offset=12 (local.get $e)))
      (i32.store (local.get $hp) (local.get $nl))
      (local.set $hp (i32.add (local.get $hp) (i32.const 4)))
      (local.set $j (i32.const 0))
      (block $nd (loop $nc
        (br_if $nd (i32.ge_u (local.get $j) (local.get $nl)))
        (i32.store8 (local.get $hp) (i32.load8_u (i32.add (local.get $np) (local.get $j))))
        (local.set $hp (i32.add (local.get $hp) (i32.const 1)))
        (local.set $j (i32.add (local.get $j) (i32.const 1)))
        (br $nc)))
      (i32.store (local.get $hp) (local.get $vl))
      (local.set $hp (i32.add (local.get $hp) (i32.const 4)))
      (local.set $j (i32.const 0))
      (block $vd (loop $vc
        (br_if $vd (i32.ge_u (local.get $j) (local.get $vl)))
        (i32.store8 (local.get $hp) (i32.load8_u (i32.add (local.get $vp) (local.get $j))))
        (local.set $hp (i32.add (local.get $hp) (i32.const 1)))
        (local.set $j (i32.add (local.get $j) (i32.const 1)))
        (br $vc)))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $en)))
    (i32.store (local.get $rhdr_out) (i32.const 0x60000))
    (i32.store (local.get $rhdr_len_out) (i32.sub (local.get $hp) (i32.const 0x60000)))
    (call $drop_fields (local.get $rh))

    ;; --- body ---
    (call $resp_consume (local.get $resp) (i32.const 0x50830))
    (if (i32.load8_u (i32.const 0x50830)) (then (return (i32.const 6))))
    (local.set $body (i32.load (i32.const 0x50834)))
    (call $body_stream (local.get $body) (i32.const 0x50840))
    (if (i32.load8_u (i32.const 0x50840)) (then (return (i32.const 7))))
    (local.set $stream (i32.load (i32.const 0x50844)))
    (local.set $out (i32.const 0x70000))
    (local.set $total (i32.const 0))
    (block $rd (loop $r
      (call $read (local.get $stream) (i64.const 4096) (i32.const 0x50850))
      (br_if $rd (i32.load8_u (i32.const 0x50850)))
      (local.set $src (i32.load (i32.const 0x50854)))
      (local.set $n (i32.load (i32.const 0x50858)))
      (br_if $rd (i32.eqz (local.get $n)))
      (local.set $j (i32.const 0))
      (block $cd (loop $c
        (br_if $cd (i32.ge_u (local.get $j) (local.get $n)))
        (br_if $cd (i32.ge_u (local.get $total) (i32.const 0x90000)))
        (i32.store8 (i32.add (local.get $out) (local.get $total))
          (i32.load8_u (i32.add (local.get $src) (local.get $j))))
        (local.set $total (i32.add (local.get $total) (i32.const 1)))
        (local.set $j (i32.add (local.get $j) (i32.const 1)))
        (br $c)))
      (br $r)))
    (i32.store (local.get $body_out) (i32.const 0x70000))
    (i32.store (local.get $body_len_out) (local.get $total))

    ;; --- drops ---
    (call $drop_in (local.get $stream))
    (call $drop_body (local.get $body))
    (call $drop_resp (local.get $resp))
    (call $drop_future (local.get $future))
    (i32.const 0))

  (export "fd_write" (func $fd_write))
  (export "fd_read" (func $fd_read))
  (export "path_open" (func $path_open))
  (export "fd_close" (func $fd_close))
  (export "random_get" (func $random_get))
  (export "clock_time_get" (func $clock_time_get))
  (export "environ_sizes_get" (func $environ_sizes_get))
  (export "environ_get" (func $environ_get))
  (export "fetch" (func $fetch)))
