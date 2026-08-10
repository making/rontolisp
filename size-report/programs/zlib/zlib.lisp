;;;; zlib -- gunzip a 64 KB stream with chipz, the real upstream decompressor
;;;; (BSD, https://github.com/froydnj/chipz), quickloaded from the live
;;;; Quicklisp dist and compiled in from its unmodified sources. Measures what a
;;;; whole third-party CL library costs in the artifact: an inflate state
;;;; machine over typed bit buffers, the Huffman code tables, a 32 KB window and
;;;; CRC32.
;;;;
;;;; The same WORK as the upstream comparison program -- gzip in, decompressed
;;;; bytes out -- but not yet the same program: the input is embedded instead of
;;;; read from stdin, and the result is reported instead of written to stdout,
;;;; because rontolisp has no binary stdin/stdout yet (read-byte/write-byte need
;;;; a stream from `open`). Measured: replacing the summary line with a bare
;;;; (princ (length raw)) saves 2,008 bytes of 432,134 -- chipz's own condition
;;;; reports already pull the format renderer in -- so it is the missing I/O,
;;;; not the reporting, that keeps the row from being like-for-like.
;;;;
;;;; The stream inflates to 65536 octets: a 512-byte pseudo-text block repeated
;;;; 128 times, so the first block runs the literal and short-match paths and
;;;; the rest runs long back-references through the window. chipz verifies the
;;;; gzip CRC32 itself and signals on a mismatch; the FNV-1a below is the
;;;; independent check, printed so a backend that drifts fails the run.
;;;;
;;;; There is no --no-gc companion: that backend has no arrays at all.
;;;;
;;;; Run (the first run downloads chipz into ~/.rontolisp/quicklisp):
;;;;   rontolisp size-report/programs/zlib/zlib.lisp -o zlib.wasm --optimize=size
;;;;   wasmtime run -W gc -W exceptions=y zlib.wasm

(ql:quickload "chipz")

(defparameter *gzipped*
  (make-array 507
              :element-type '(unsigned-byte 8)
              :initial-contents '(31 139 8 0 0 0 0 0 2 255 237 209 109 114 131
                                  32 16 0 208 171 112 53 146 152 106 131 33 99
                                  109 166 199 111 187 136 141 211 43 188 63 140
                                  194 178 31 188 121 186 13 105 56 143 53 93 235
                                  215 186 212 53 221 166 82 211 35 63 114 186
                                  215 231 48 159 134 229 239 35 206 98 233 209
                                  243 239 253 50 205 57 213 143 115 94 218 197
                                  203 80 214 156 114 121 140 57 189 127 150 105
                                  88 183 192 61 81 59 139 224 30 16 63 109 127
                                  15 139 198 230 189 197 125 255 60 230 229 231
                                  86 43 252 86 203 53 77 247 203 212 11 71 131
                                  61 36 210 198 210 26 28 235 58 148 45 188 151
                                  62 45 249 89 183 243 190 215 58 137 2 45 184
                                  79 124 152 188 71 31 51 69 185 104 43 150 222
                                  202 203 11 29 46 247 140 251 120 81 53 38 110
                                  165 99 160 195 67 198 97 155 246 152 61 10 190
                                  32 196 213 61 241 255 167 156 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 159 63 127 254 252 249 243 231 207
                                  159 63 127 254 252 249 243 231 207 159 63 127
                                  254 252 249 243 231 207 159 63 127 254 252 249
                                  243 231 207 127 219 250 6 188 210 21 89 0 0 1
                                  0)))

(defun fnv1a (octets)
  "The 32-bit FNV-1a hash of OCTETS -- masked at every step, so every backend
computes it in the same fixnum range."
  (let ((hash 2166136261))
    (dotimes (i (length octets) hash)
      (setq hash
            (logand #xFFFFFFFF (* (logxor hash (aref octets i)) 16777619))))))

(let ((raw (chipz:decompress nil 'chipz:gzip *gzipped*)))
  (format t "gunzip ~a -> ~a bytes, fnv1a ~8,'0X~%" (length *gzipped*)
          (length raw) (fnv1a raw)))
