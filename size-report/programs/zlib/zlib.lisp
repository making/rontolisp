;;;; zlib -- gunzip a stream from standard input with chipz, the real upstream
;;;; decompressor (BSD, https://github.com/froydnj/chipz), quickloaded from the
;;;; live Quicklisp dist and compiled in from its unmodified sources. Measures
;;;; what a whole third-party CL library costs in the artifact: an inflate state
;;;; machine over typed bit buffers, the Huffman code tables, a 32 KB window and
;;;; CRC32.
;;;;
;;;; The same PROGRAM as the upstream comparison -- read all of stdin, inflate,
;;;; write the octets to stdout -- down to the 8192-byte input buffer the C and
;;;; Zig versions use. Nothing is embedded and nothing is reported: the output IS
;;;; the decompressed stream.
;;;;
;;;; There is no --no-gc companion: that backend has no arrays at all.
;;;;
;;;; Run (the first run downloads chipz into ~/.rontolisp/quicklisp):
;;;;   rontolisp size-report/programs/zlib/zlib.lisp -o zlib.wasm --optimize=size
;;;;   wasmtime run zlib.wasm <input.gz >output

(ql:quickload "chipz")

;; Read all gzip data from stdin
(let* ((input (make-array 8192 :element-type '(unsigned-byte 8)))
       (input-len (read-sequence input *standard-input*)))
  ;; Decompress gzip and write to stdout
  (write-sequence (chipz:decompress nil 'chipz:gzip (subseq input 0 input-len))
                  *standard-output*))
