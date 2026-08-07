// wasi-shim.js
//
// A tiny, dependency-free WASI Preview 1 shim, just large enough to run a
// program compiled by rontolisp (`rontolisp prog.lisp -o prog.wasm`) in a
// browser. rontolisp's WASM output imports exactly nine functions from the
// "wasi_snapshot_preview1" module:
//
//   fd_write, fd_read, path_open, fd_close, fd_readdir,
//   random_get, clock_time_get, environ_sizes_get, environ_get
//
// A module built with --optimize imports only the ones it actually reaches, so
// the shim provides all nine and lets the link pick.
//
// This shim implements them over plain JavaScript:
//   - stdout/stderr (fd 1/2) are captured into strings instead of a real tty
//   - stdin (fd 0) is fed from a string you provide
//   - files (path_open) are not supported and report "no entry"
//   - directories (fd_readdir) are not supported and report "not supported"
//   - randomness uses crypto.getRandomValues, the clock uses Date.now()
//   - environment variables come from the `env` option
//
// It is intentionally minimal and easy to read; it is NOT a complete WASI
// implementation. For a fuller one, use a package such as
// `@bjorn3/browser_wasi_shim`.

const WASI_ESUCCESS = 0;
const WASI_EBADF = 8; // bad file descriptor
const WASI_ENOENT = 44; // no such file or directory
const WASI_ENOSYS = 52; // function not supported

/**
 * Create a WASI Preview 1 import object plus helpers for one run of a module.
 *
 * @param {Object}  [opts]
 * @param {string}  [opts.stdin]  text delivered to the program's stdin (fd 0)
 * @param {Object}  [opts.env]    environment variables, e.g. { NAME: "Ada" }
 * @returns {{ imports: object, setMemory: (m: WebAssembly.Memory) => void,
 *             getStdout: () => string, getStderr: () => string }}
 */
export function createWasi({ stdin = "", env = {} } = {}) {
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  let memory = null; // set after instantiation via setMemory()
  let stdoutText = "";
  let stderrText = "";

  const stdinBytes = encoder.encode(stdin);
  let stdinPos = 0;

  // environ entries are "KEY=VALUE\0" byte arrays (WASI layout).
  const envEntries = Object.entries(env).map(([k, v]) =>
    encoder.encode(`${k}=${v}\0`),
  );

  const view = () => new DataView(memory.buffer);
  const bytes = () => new Uint8Array(memory.buffer);

  const imports = {
    // fd_write(fd, iovs, iovs_len, nwritten) -> errno
    // Concatenate the iovec slices and route fd 1 -> stdout, fd 2 -> stderr.
    fd_write(fd, iovs, iovsLen, nwritten) {
      let written = 0;
      let chunk = "";
      for (let i = 0; i < iovsLen; i++) {
        const base = iovs + i * 8;
        const ptr = view().getUint32(base, true);
        const len = view().getUint32(base + 4, true);
        chunk += decoder.decode(new Uint8Array(memory.buffer, ptr, len));
        written += len;
      }
      if (fd === 1) stdoutText += chunk;
      else if (fd === 2) stderrText += chunk;
      view().setUint32(nwritten, written, true);
      return WASI_ESUCCESS;
    },

    // fd_read(fd, iovs, iovs_len, nread) -> errno
    // Serve bytes from the provided stdin string; EOF reads zero bytes.
    fd_read(fd, iovs, iovsLen, nread) {
      if (fd !== 0) return WASI_EBADF;
      let total = 0;
      const mem = bytes();
      for (let i = 0; i < iovsLen; i++) {
        const base = iovs + i * 8;
        const ptr = view().getUint32(base, true);
        const len = view().getUint32(base + 4, true);
        let j = 0;
        for (; j < len && stdinPos < stdinBytes.length; j++) {
          mem[ptr + j] = stdinBytes[stdinPos++];
        }
        total += j;
        if (j < len) break; // ran out of input
      }
      view().setUint32(nread, total, true);
      return WASI_ESUCCESS;
    },

    // Files are not backed by anything in the browser.
    path_open() {
      return WASI_ENOENT;
    },
    fd_close() {
      return WASI_ESUCCESS;
    },

    // fd_readdir(fd, buf, buf_len, cookie, bufused_out) -> errno
    // There are no directories here, so a program that lists one gets "not
    // supported" rather than an empty listing it would read as "no files".
    fd_readdir() {
      return WASI_ENOSYS;
    },

    // random_get(ptr, len) -> errno
    random_get(ptr, len) {
      crypto.getRandomValues(new Uint8Array(memory.buffer, ptr, len));
      return WASI_ESUCCESS;
    },

    // clock_time_get(id, precision, out) -> errno ; out is an i64 of nanoseconds
    clock_time_get(_id, _precision, out) {
      const nanos = BigInt(Date.now()) * 1_000_000n;
      view().setBigUint64(out, nanos, true);
      return WASI_ESUCCESS;
    },

    // environ_sizes_get(count_out, bufsize_out) -> errno
    environ_sizes_get(countOut, bufsizeOut) {
      const bufSize = envEntries.reduce((sum, e) => sum + e.length, 0);
      view().setUint32(countOut, envEntries.length, true);
      view().setUint32(bufsizeOut, bufSize, true);
      return WASI_ESUCCESS;
    },

    // environ_get(ptrs_out, buf_out) -> errno
    environ_get(ptrsOut, bufOut) {
      const mem = bytes();
      let bufPtr = bufOut;
      for (let i = 0; i < envEntries.length; i++) {
        view().setUint32(ptrsOut + i * 4, bufPtr, true);
        mem.set(envEntries[i], bufPtr);
        bufPtr += envEntries[i].length;
      }
      return WASI_ESUCCESS;
    },

    // Programs that exit explicitly would call this. rontolisp's output never
    // imports proc_exit, but we provide it so the shim also works with modules
    // that do (a thrown sentinel ends _start cleanly).
    proc_exit(code) {
      throw new WasiExit(code);
    },
  };

  return {
    imports: { wasi_snapshot_preview1: imports },
    setMemory: (m) => {
      memory = m;
    },
    getStdout: () => stdoutText,
    getStderr: () => stderrText,
  };
}

/** Thrown by proc_exit to unwind out of _start. */
export class WasiExit extends Error {
  constructor(code) {
    super(`WASI exit ${code}`);
    this.code = code;
  }
}

/**
 * Instantiate and run an already-loaded rontolisp-compiled `.wasm` command
 * module (a `BufferSource`: `ArrayBuffer` or typed array), returning whatever
 * it wrote to stdout/stderr. Use this when the bytes are already in memory —
 * e.g. compiled in the browser by the playground's `rontoCompileWasm`, with no
 * `.wasm` file to fetch.
 *
 * @param {BufferSource} wasmBytes  the module bytes
 * @param {Object} [opts]           same options as createWasi()
 * @returns {Promise<{ stdout: string, stderr: string, exitCode: number }>}
 */
export async function runWasmModule(wasmBytes, opts = {}) {
  const wasi = createWasi(opts);
  const { instance } = await WebAssembly.instantiate(wasmBytes, wasi.imports);

  wasi.setMemory(instance.exports.memory);

  let exitCode = 0;
  try {
    instance.exports._start();
  } catch (e) {
    if (e instanceof WasiExit) exitCode = e.code;
    else throw e;
  }
  return {
    stdout: wasi.getStdout(),
    stderr: wasi.getStderr(),
    exitCode,
  };
}

/**
 * Fetch, instantiate and run a rontolisp-compiled `.wasm` command module,
 * returning whatever it wrote to stdout/stderr.
 *
 * @param {string} url            URL of the .wasm file
 * @param {Object} [opts]         same options as createWasi()
 * @returns {Promise<{ stdout: string, stderr: string, exitCode: number }>}
 */
export async function runWasm(url, opts = {}) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`failed to fetch ${url}: ${response.status}`);
  }
  // instantiateStreaming needs the correct application/wasm MIME type; fall
  // back to ArrayBuffer instantiation when the server doesn't send it.
  const wasmBytes = await response.arrayBuffer();
  return runWasmModule(wasmBytes, opts);
}
