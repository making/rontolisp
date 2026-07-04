;; Serve adapter core module for rontolisp:http-handler (WASI component output).
;;
;; Exports `serve(request, response_out)` which WasmComponentBuilder.buildServe lifts as
;; the component's `wasi:http/incoming-handler@0.2.0#handle`. Per request it reads the
;; incoming-request (method / path / body) over wasi:http@0.2 into shared linear-memory
;; scratch, calls the rontolisp core's `%http-dispatch` (a wasm-export :string*3 -> :string
;; wrapper that runs the Lisp handler and returns "<status>\n<body>"), parses the status and
;; body, and writes an outgoing-response back through response-outparam.set. This is the
;; incoming mirror of adapter-http.wat's fetch-start/fetch-await.
;;
;; Memory model (same as adapter-http.wat): the shared 16-page `mem` module holds the
;; canonical scratch; the rontolisp core's linear usage (interns / string content, from
;; ~0x2000) stays below the 0x50000+ scratch used here. Scratch layout:
;;   0x50000  general lowering out-params (path option, consume/stream results, io results)
;;   0x50200  request method string ("GET" etc.)
;;   0x50210  dispatch return (ptr,len) pair
;;   0x50300  init-once flag
;;   0x70000  request body bytes (read from the incoming-body stream)
(module
  (import "mem" "memory" (memory (;0;) 16))
  ;; The rontolisp core, instantiated first. `run` runs the top-level once (defun/intern
  ;; setup); `%http-dispatch` is the wasm-export wrapper running the Lisp handler.
  (import "core" "run" (func $core_init (result i32)))
  (import "core" "%http-dispatch" (func $dispatch
    (param i32 i32 i32 i32 i32 i32) (result i32 i32)))
  ;; Lowered wasi:http@0.2 / wasi:io@0.2 functions (grouped under "w" by buildServe).
  (import "w" "req-method" (func $req_method (param i32 i32)))
  (import "w" "req-path" (func $req_path (param i32 i32)))
  (import "w" "req-consume" (func $req_consume (param i32 i32)))
  (import "w" "body-stream" (func $body_stream (param i32 i32)))
  (import "w" "io-read" (func $io_read (param i32 i64 i32)))
  (import "w" "fields-new" (func $fields_new (result i32)))
  (import "w" "resp-new" (func $resp_new (param i32) (result i32)))
  (import "w" "set-status" (func $set_status (param i32 i32) (result i32)))
  (import "w" "resp-body" (func $resp_body (param i32 i32)))
  (import "w" "body-write" (func $body_write (param i32 i32)))
  (import "w" "io-write" (func $io_write (param i32 i32 i32 i32)))
  (import "w" "drop-out" (func $drop_out (param i32)))
  (import "w" "body-finish" (func $body_finish (param i32 i32 i32 i32)))
  (import "w" "resp-set" (func $resp_set (param i32 i32 i32 i32 i64 i32 i32 i32 i32)))
  (import "w" "drop-req" (func $drop_req (param i32)))
  (import "w" "drop-in" (func $drop_in (param i32)))
  (import "w" "drop-body" (func $drop_body (param i32)))

  ;; Write a fixed method string into scratch 0x50200 from the wasi method-variant
  ;; discriminant (0=get,1=head,2=post,3=put,4=delete,5=connect,6=options,7=trace,8=patch;
  ;; other/unknown -> "GET"). Returns the length; the pointer is always 0x50200.
  (func $method_str (param $disc i32) (result i32)
    (local $p i32) (local $l i32)
    (local.set $p (i32.const 0x50200))
    (block $done
      (block $b8 (block $b7 (block $b6 (block $b5 (block $b4 (block $b3 (block $b2 (block $b1 (block $b0
        (br_table $b0 $b1 $b2 $b3 $b4 $b5 $b6 $b7 $b8 $b0 (local.get $disc)))
        ;; 0 GET
        (i32.store8 (i32.const 0x50200) (i32.const 71)) (i32.store8 (i32.const 0x50201) (i32.const 69))
        (i32.store8 (i32.const 0x50202) (i32.const 84)) (local.set $l (i32.const 3)) (br $done))
        ;; 1 HEAD
        (i32.store (i32.const 0x50200) (i32.const 0x44414548)) (local.set $l (i32.const 4)) (br $done))
        ;; 2 POST
        (i32.store (i32.const 0x50200) (i32.const 0x54534F50)) (local.set $l (i32.const 4)) (br $done))
        ;; 3 PUT
        (i32.store8 (i32.const 0x50200) (i32.const 80)) (i32.store8 (i32.const 0x50201) (i32.const 85))
        (i32.store8 (i32.const 0x50202) (i32.const 84)) (local.set $l (i32.const 3)) (br $done))
        ;; 4 DELETE
        (i32.store (i32.const 0x50200) (i32.const 0x454C4544)) (i32.store8 (i32.const 0x50204) (i32.const 84))
        (i32.store8 (i32.const 0x50205) (i32.const 69)) (local.set $l (i32.const 6)) (br $done))
        ;; 5 CONNECT
        (i32.store (i32.const 0x50200) (i32.const 0x4E4E4F43)) (i32.store8 (i32.const 0x50204) (i32.const 69))
        (i32.store8 (i32.const 0x50205) (i32.const 67)) (i32.store8 (i32.const 0x50206) (i32.const 84))
        (local.set $l (i32.const 7)) (br $done))
        ;; 6 OPTIONS
        (i32.store (i32.const 0x50200) (i32.const 0x4954504F)) (i32.store8 (i32.const 0x50204) (i32.const 79))
        (i32.store8 (i32.const 0x50205) (i32.const 78)) (i32.store8 (i32.const 0x50206) (i32.const 83))
        (local.set $l (i32.const 7)) (br $done))
        ;; 7 TRACE
        (i32.store (i32.const 0x50200) (i32.const 0x43415254)) (i32.store8 (i32.const 0x50204) (i32.const 69))
        (local.set $l (i32.const 5)) (br $done))
        ;; 8 PATCH
        (i32.store (i32.const 0x50200) (i32.const 0x43544150)) (i32.store8 (i32.const 0x50204) (i32.const 72))
        (local.set $l (i32.const 5)) (br $done))
    (local.get $l))

  (func (export "serve") (param $request i32) (param $respout i32)
    (local $mlen i32) (local $pptr i32) (local $plen i32)
    (local $ibody i32) (local $stream i32) (local $bptr i32) (local $blen i32)
    (local $src i32) (local $n i32) (local $j i32)
    (local $ret_ptr i32) (local $ret_len i32) (local $i i32) (local $status i32) (local $c i32)
    (local $resp i32) (local $obody i32) (local $ostream i32)

    ;; --- init the rontolisp core once (top-level defun/intern setup) ---
    (if (i32.eqz (i32.load (i32.const 0x50300)))
      (then (drop (call $core_init)) (i32.store (i32.const 0x50300) (i32.const 1))))

    ;; --- method: variant disc @0x50000 -> fixed string @0x50200 ---
    (call $req_method (local.get $request) (i32.const 0x50000))
    (local.set $mlen (call $method_str (i32.load8_u (i32.const 0x50000))))

    ;; --- path: option<string> {disc@0, ptr@4, len@8} @0x50010 ---
    (call $req_path (local.get $request) (i32.const 0x50010))
    (if (i32.load8_u (i32.const 0x50010))
      (then
        (local.set $pptr (i32.load (i32.const 0x50014)))
        (local.set $plen (i32.load (i32.const 0x50018))))
      (else
        ;; no path -> "/"
        (i32.store8 (i32.const 0x50220) (i32.const 47))
        (local.set $pptr (i32.const 0x50220)) (local.set $plen (i32.const 1))))

    ;; --- body: consume -> incoming-body -> stream -> read loop into 0x70000 ---
    (local.set $bptr (i32.const 0x70000))
    (local.set $blen (i32.const 0))
    (call $req_consume (local.get $request) (i32.const 0x50020))
    (if (i32.eqz (i32.load8_u (i32.const 0x50020)))
      (then
        (local.set $ibody (i32.load (i32.const 0x50024)))
        (call $body_stream (local.get $ibody) (i32.const 0x50028))
        (if (i32.eqz (i32.load8_u (i32.const 0x50028)))
          (then
            (local.set $stream (i32.load (i32.const 0x5002C)))
            (block $rd (loop $r
              (call $io_read (local.get $stream) (i64.const 4096) (i32.const 0x50030))
              (br_if $rd (i32.load8_u (i32.const 0x50030)))
              (local.set $src (i32.load (i32.const 0x50034)))
              (local.set $n (i32.load (i32.const 0x50038)))
              (br_if $rd (i32.eqz (local.get $n)))
              (local.set $j (i32.const 0))
              (block $cd (loop $c2
                (br_if $cd (i32.ge_u (local.get $j) (local.get $n)))
                (br_if $cd (i32.ge_u (local.get $blen) (i32.const 0x10000)))
                (i32.store8 (i32.add (i32.const 0x70000) (local.get $blen))
                  (i32.load8_u (i32.add (local.get $src) (local.get $j))))
                (local.set $blen (i32.add (local.get $blen) (i32.const 1)))
                (local.set $j (i32.add (local.get $j) (i32.const 1)))
                (br $c2)))
              (br $r)))
            (call $drop_in (local.get $stream))))
        (call $drop_body (local.get $ibody))))
    (call $drop_req (local.get $request))

    ;; --- call the Lisp handler: "<status>\n<body>" ---
    (call $dispatch
      (i32.const 0x50200) (local.get $mlen) (local.get $pptr) (local.get $plen)
      (local.get $bptr) (local.get $blen))
    (local.set $ret_len)
    (local.set $ret_ptr)

    ;; --- parse status (decimal digits before the first '\n') ---
    (local.set $status (i32.const 0))
    (local.set $i (i32.const 0))
    (block $pd (loop $pl
      (br_if $pd (i32.ge_u (local.get $i) (local.get $ret_len)))
      (local.set $c (i32.load8_u (i32.add (local.get $ret_ptr) (local.get $i))))
      (br_if $pd (i32.eq (local.get $c) (i32.const 10)))
      (if (i32.and (i32.ge_u (local.get $c) (i32.const 48)) (i32.le_u (local.get $c) (i32.const 57)))
        (then (local.set $status
          (i32.add (i32.mul (local.get $status) (i32.const 10)) (i32.sub (local.get $c) (i32.const 48))))))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $pl)))
    (if (i32.eqz (local.get $status)) (then (local.set $status (i32.const 200))))
    ;; body starts just after the '\n'
    (local.set $i (i32.add (local.get $i) (i32.const 1)))

    ;; --- build the outgoing response ---
    (local.set $resp (call $resp_new (call $fields_new)))
    (drop (call $set_status (local.get $resp) (local.get $status)))
    (call $resp_body (local.get $resp) (i32.const 0x50040))
    (local.set $obody (i32.load (i32.const 0x50044)))
    (call $body_write (local.get $obody) (i32.const 0x50050))
    (local.set $ostream (i32.load (i32.const 0x50054)))
    (if (i32.lt_u (local.get $i) (local.get $ret_len))
      (then
        (call $io_write (local.get $ostream)
          (i32.add (local.get $ret_ptr) (local.get $i)) (i32.sub (local.get $ret_len) (local.get $i))
          (i32.const 0x50060))))
    (call $drop_out (local.get $ostream))
    (call $body_finish (local.get $obody) (i32.const 0) (i32.const 0) (i32.const 0x50070))
    (call $resp_set (local.get $respout) (i32.const 0) (local.get $resp)
      (i32.const 0) (i64.const 0) (i32.const 0) (i32.const 0) (i32.const 0) (i32.const 0))))
