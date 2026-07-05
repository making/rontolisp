package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.BuiltinFunctionWrappers;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.rontolisp.compiler.GlobalVarCollector;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Section;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Lisp expressions to WASM binary with wasm-GC and WASI Preview 1. All Lisp
 * values are represented as (ref eq) on the stack: integers use i31ref, nil uses ref.null
 * eq, strings use a string struct, cons cells use a cons struct, and closures use a
 * closure struct. Supports first-class functions via dispatch functions and closure
 * structs.
 */
public final class WasmLispCompiler implements LispCompiler {

	private final boolean dynamic;

	private final boolean component;

	private final boolean noWasi;

	private final boolean optimize;

	private final boolean serve;

	/** Creates a new WASM compiler. */
	public WasmLispCompiler() {
		this(false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic when {@code true}, unresolved function calls and variable references
	 * are not rejected at compile time but resolved at runtime against the embedded
	 * {@code eval} global environment (late binding), so a program that defines functions
	 * via {@code load} can compile without changes. This forces the {@code eval} runtime
	 * to be emitted.
	 */
	public WasmLispCompiler(boolean dynamic) {
		this(dynamic, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component when {@code true}, the output is a WASI 0.2 (Preview 2)
	 * <strong>component</strong> instead of a Preview 1 core module: the core module
	 * imports its linear memory and exports a {@code run} entry, and is wrapped by
	 * {@link WasmComponentBuilder} so it prints through {@code wasi:cli/stdout} and runs
	 * with {@code wasmtime run}. Reading and file I/O are not yet available in component
	 * mode.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component) {
		this(dynamic, component, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi when {@code true} (Preview 1 only; ignored in component mode), the
	 * output module imports <strong>no</strong> {@code wasi_snapshot_preview1} functions,
	 * so a host can instantiate it with no import object (a "reactor"/library module).
	 * The eight WASI import slots (function indices 0-7) are filled with internal
	 * {@code unreachable} trap stubs so every fixed {@code FUNC_*} index stays valid.
	 * Only pure-compute {@code (rontolisp:wasm-export ...)} functions work; any I/O
	 * (print/read/open/getenv/time/random) traps.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi) {
		this(dynamic, component, noWasi, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize when {@code true}, the emitted core module is run through
	 * {@link am.ik.wasm.WasmTreeShaker}: functions unreachable from the module's roots
	 * (its exports and {@code _start}) are dropped and the survivors renumbered. Combined
	 * with {@code noWasi} a pure-compute reactor module shrinks to a handful of
	 * functions. The pass is a no-op in {@code component} mode (the WASI 0.3 adapter
	 * wiring relies on the core's fixed import/index layout), so component output is
	 * unchanged.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, boolean optimize) {
		this(dynamic, component, noWasi, optimize, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see {@link #WasmLispCompiler(boolean, boolean, boolean, boolean)}
	 * @param serve when {@code true} (implies {@code component}), the program serves HTTP
	 * via {@code rontolisp:http-handler}: the {@code HttpHandlerInliner} has spliced in a
	 * {@code %http-dispatch} {@code wasm-export} wrapper, so the wasm-export memory-ABI
	 * machinery is enabled even in component mode, and the core is wrapped by
	 * {@link WasmComponentBuilder#buildServe} into a {@code wasi:http/incoming-handler}
	 * component (runnable under {@code wasmtime serve} or any {@code wasi:http} 0.2 host
	 * with wasm-GC enabled, e.g. jco or wasmCloud) instead of the {@code wasi:cli/run}
	 * component.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, boolean optimize, boolean serve) {
		this.dynamic = dynamic;
		this.component = component;
		// no-wasi is a Preview 1-only mode; a component has its own (lowered) import
		// story.
		this.noWasi = noWasi && !component;
		this.optimize = optimize;
		this.serve = serve && component;
	}

	// Function indices (imports come first). Defined functions are indexed relative to
	// IMPORT_FUNC_COUNT so adding an imported function only requires adding its constant
	// and bumping the count -- every defined function shifts automatically.
	static final int FUNC_FD_WRITE = 0; // imported

	static final int FUNC_FD_READ = 1; // imported

	static final int FUNC_PATH_OPEN = 2; // imported (for load/open)

	static final int FUNC_FD_CLOSE = 3; // imported (for close)

	// WASI entropy source imported in both modes: Preview 1 mode binds the real
	// wasi_snapshot_preview1 random_get; component mode binds the adapter's random_get,
	// which draws from wasi:random. Importing it in both modes keeps the import count --
	// and the defined function indices below -- identical across modes.
	static final int FUNC_RANDOM_GET = 4; // imported

	// WASI clock and environment imported in both modes: Preview 1 binds the real
	// wasi_snapshot_preview1 functions; component mode binds the adapter's
	// implementations on top of wasi:clocks / wasi:cli-environment.
	static final int FUNC_CLOCK_TIME_GET = 5; // imported

	static final int FUNC_ENVIRON_SIZES_GET = 6; // imported

	static final int FUNC_ENVIRON_GET = 7; // imported

	/** Number of preview1-style imported functions (fd_write..environ_get). */
	static final int IMPORT_FUNC_COUNT = 8;

	// Function indices 8-13 are reserved for the rontolisp:tcp-* built-ins (8-11) and
	// rontolisp:fetch / rontolisp:await (12-13) in BOTH modes so the defined-function
	// indices stay identical across modes. Imports always precede defined functions,
	// so a slot can be an import in one mode only if every EARLIER slot is an import in
	// that mode too. The combinations (fetch + sockets together is a compile error):
	// - sockets component: sock.* imported at 8-11, fetch trap stubs defined at 12-13.
	// - fetch component: sock.* imported at 8-11 FROM THE HTTP ADAPTER (four tiny
	// errno-returning stub exports in adapter-http.wat), http.* imported at 12-13.
	// - plain component / Preview 1 / no-wasi: all six are defined trap stubs.
	// This keeps FUNC_START at 14 uniformly, so all the static FUNC_* constants below
	// are stable. The tcp/fetch built-ins raise a compile error in Preview 1 mode, so
	// the stubs are never called.
	static final int FUNC_TCP_CONNECT = IMPORT_FUNC_COUNT; // 8

	static final int FUNC_TCP_LISTEN = FUNC_TCP_CONNECT + 1; // 9

	static final int FUNC_TCP_ACCEPT = FUNC_TCP_LISTEN + 1; // 10

	static final int FUNC_TCP_LOCAL_PORT = FUNC_TCP_ACCEPT + 1; // 11

	static final int FUNC_FETCH_START = FUNC_TCP_LOCAL_PORT + 1; // 12

	static final int FUNC_FETCH_AWAIT = FUNC_FETCH_START + 1; // 13

	static final int FUNC_START = FUNC_FETCH_AWAIT + 1; // 14

	static final int FUNC_PRINT_I32 = FUNC_START + 1;

	static final int FUNC_WRITE_STR = FUNC_PRINT_I32 + 1;

	static final int FUNC_PRINT_VAL = FUNC_WRITE_STR + 1;

	static final int FUNC_PRINT_I32_NO_NL = FUNC_PRINT_VAL + 1;

	static final int FUNC_PRINT_F64 = FUNC_PRINT_I32_NO_NL + 1;

	static final int FUNC_PRINT_F64_NO_NL = FUNC_PRINT_F64 + 1;

	static final int FUNC_APPEND = FUNC_PRINT_F64_NO_NL + 1;

	static final int FUNC_READ_LINE = FUNC_APPEND + 1;

	static final int FUNC_PRINC_VAL = FUNC_READ_LINE + 1;

	static final int FUNC_LOOKUP = FUNC_PRINC_VAL + 1;

	static final int FUNC_ENV_LOOKUP = FUNC_LOOKUP + 1;

	static final int FUNC_EVAL = FUNC_ENV_LOOKUP + 1;

	static final int FUNC_APPLY = FUNC_EVAL + 1;

	static final int FUNC_STORE = FUNC_APPLY + 1;

	// Reader runtime (for read/load); always present (stubs when unused) to keep indices
	// stable.
	static final int FUNC_INTERN = FUNC_STORE + 1;

	static final int FUNC_READ_EXPR = FUNC_INTERN + 1;

	static final int FUNC_READ_LIST = FUNC_READ_EXPR + 1;

	static final int FUNC_READ = FUNC_READ_LIST + 1;

	static final int FUNC_LOAD = FUNC_READ + 1;

	// Rational (ratio) runtime: always present. _rat_new normalizes and constructs,
	// _rat_num/_rat_den read components (treating an i31 integer as value/1), and the
	// arithmetic helpers dispatch between the i31 fast path and exact ratio arithmetic.
	static final int FUNC_RAT_NEW = FUNC_LOAD + 1;

	static final int FUNC_RAT_NUM = FUNC_RAT_NEW + 1;

	static final int FUNC_RAT_DEN = FUNC_RAT_NUM + 1;

	static final int FUNC_RAT_ADD = FUNC_RAT_DEN + 1;

	static final int FUNC_RAT_SUB = FUNC_RAT_ADD + 1;

	static final int FUNC_RAT_MUL = FUNC_RAT_SUB + 1;

	static final int FUNC_RAT_DIV = FUNC_RAT_MUL + 1;

	static final int FUNC_RAT_CMP = FUNC_RAT_DIV + 1;

	static final int FUNC_RAT_TRUNC = FUNC_RAT_CMP + 1;

	static final int FUNC_RAT_FLOOR = FUNC_RAT_TRUNC + 1;

	static final int FUNC_RAT_CEIL = FUNC_RAT_FLOOR + 1;

	static final int FUNC_RAT_ROUND = FUNC_RAT_CEIL + 1;

	// String runtime: render a value into the heap via the capture mode of _write_str
	// and return a new string struct (princ-to-string / prin1-to-string /
	// %string-concat).
	static final int FUNC_PRINC_TO_STR = FUNC_RAT_ROUND + 1;

	static final int FUNC_PRIN1_TO_STR = FUNC_PRINC_TO_STR + 1;

	static final int FUNC_STRING_CONCAT = FUNC_PRIN1_TO_STR + 1;

	// String runtime: produce/compare strings (string-upcase/downcase/capitalize, subseq,
	// string=/string-equal, string-trim family).
	static final int FUNC_STRING_UPCASE = FUNC_STRING_CONCAT + 1;

	static final int FUNC_STRING_DOWNCASE = FUNC_STRING_UPCASE + 1;

	static final int FUNC_STRING_CAPITALIZE = FUNC_STRING_DOWNCASE + 1;

	static final int FUNC_SUBSEQ = FUNC_STRING_CAPITALIZE + 1;

	static final int FUNC_STRING_EQ = FUNC_SUBSEQ + 1;

	static final int FUNC_STRING_EQUAL = FUNC_STRING_EQ + 1;

	static final int FUNC_STRING_TRIM = FUNC_STRING_EQUAL + 1;

	// File-stream runtime: open a file via path_open (the stream handle is the WASI
	// file descriptor boxed as an i31 integer), close it via fd_close, and write a
	// line to it via fd_write.
	static final int FUNC_OPEN = FUNC_STRING_TRIM + 1;

	static final int FUNC_CLOSE = FUNC_OPEN + 1;

	static final int FUNC_WRITE_LINE = FUNC_CLOSE + 1;

	// Structural equality (equal): recursively compares cons cells; always present.
	static final int FUNC_EQUAL = FUNC_WRITE_LINE + 1;

	// getenv: scans the WASI environ buffer for a variable; always present.
	static final int FUNC_GETENV = FUNC_EQUAL + 1;

	static final int FUNC_DISPATCH_BASE = FUNC_GETENV + 1;

	static final int MAX_CALLABLE_ARITY = 7;

	// fetch runtime helpers (always emitted, just after the dispatch functions): build a
	// heap string from raw bytes, look up a plist key by interned offset, serialize a
	// request-header alist to REQ_HDR_BUF, and rebuild a response-header alist from the
	// adapter's serialized buffer. Only the fetch compiler references them.
	static final int FUNC_FETCH_STR = FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1;

	static final int FUNC_FETCH_PLIST_GET = FUNC_FETCH_STR + 1;

	static final int FUNC_FETCH_SER_HDRS = FUNC_FETCH_PLIST_GET + 1;

	static final int FUNC_FETCH_DESER_HDRS = FUNC_FETCH_SER_HDRS + 1;

	// Structural hash (agrees with _equal): walks conses and folds i31 ints / interned
	// string offsets / char codes / float bits / ratio components into an i32. Always
	// present; only the hash-table compiler references it. Equal keys hash equal.
	static final int FUNC_HASH = FUNC_FETCH_DESER_HDRS + 1;

	// Rehash helper: grows a hash table's bucket array (doubling capacity) and
	// redistributes its entries. Always present; called by puthash when the load factor
	// is exceeded.
	static final int FUNC_HASH_RESIZE = FUNC_HASH + 1;

	// Modulo / remainder runtime: dispatch on operand type (i31 fast path, exact ratio,
	// or f64 for a float operand) so mod/rem work on floats reaching them through a
	// variable. Appended here (after the hash helpers, before FUNC_USER_BASE) so no
	// import/FUNC_START index shifts and the component blobs are unaffected. Always
	// present; only the mod/rem compiler references them.
	static final int FUNC_RAT_REM = FUNC_HASH_RESIZE + 1;

	static final int FUNC_RAT_MOD = FUNC_RAT_REM + 1;

	// gensym runtime: bumps the counter at GENSYM_CTR_ADDR and builds the fresh symbol
	// name (prefix bytes + decimal digits) as a new heap string. Appended before
	// FUNC_USER_BASE like the mod/rem helpers, so no import/FUNC_START index shifts and
	// the component blobs are unaffected. Always present; only the gensym compiler
	// references it.
	static final int FUNC_GENSYM = FUNC_RAT_MOD + 1;

	// _promise_await ((ref null eq)) -> (ref null eq): the generic rontolisp:await
	// resolver (WasmPromiseRuntimeBuilder). Recursive, so it is a real function rather
	// than inline code at each await site.
	static final int FUNC_PROMISE_AWAIT = FUNC_GENSYM + 1;

	// Binary stream runtime: read-byte / write-byte move one raw byte through the
	// BYTE_SCRATCH_ADDR scratch cell via fd_read / fd_write (no quote framing, no
	// newline). Appended before FUNC_USER_BASE like the mod/rem helpers, so no
	// import/FUNC_START index shifts and the component blobs are unaffected.
	static final int FUNC_READ_BYTE = FUNC_PROMISE_AWAIT + 1;

	static final int FUNC_WRITE_BYTE = FUNC_READ_BYTE + 1;

	// String-stream runtime (with-output-to-string / with-input-from-string): a string
	// stream is a NEGATIVE i31 handle whose absolute value is the address of a record in
	// linear memory (a real WASI fd is never negative). An output record heads a chunk
	// list referencing existing string bytes (the bump allocator never moves them); an
	// input record holds a cursor/end byte range. Appended before FUNC_USER_BASE like the
	// mod/rem helpers, so no import/FUNC_START index shifts and the component blobs are
	// unaffected. See WasmStringStreamRuntimeBuilder.
	static final int FUNC_WRITE_STREAM_STR = FUNC_WRITE_BYTE + 1;

	static final int FUNC_MAKE_STR_OSTREAM = FUNC_WRITE_STREAM_STR + 1;

	static final int FUNC_MAKE_STR_ISTREAM = FUNC_MAKE_STR_OSTREAM + 1;

	static final int FUNC_STR_STREAM_CONTENTS = FUNC_MAKE_STR_ISTREAM + 1;

	// User defuns start after the dispatch functions, the four fetch helpers, the two
	// hash-table runtime helpers, the two mod/rem helpers, the gensym helper, the
	// promise-await helper, the two binary stream helpers, and the four string-stream
	// helpers.
	static final int FUNC_USER_BASE = FUNC_STR_STREAM_CONTENTS + 1;

	// Type indices
	static final int TYPE_FD_WRITE = 0;

	static final int TYPE_START = 1;

	static final int TYPE_PRINT_I32 = 2; // also for _print_i32_no_nl

	static final int TYPE_CONS = 3; // in rec group

	static final int TYPE_STRING = 4; // in rec group

	static final int TYPE_CELL = 5; // in rec group - {(mut ref null eq)}

	static final int TYPE_CLOSURE = 6; // in rec group - {i32 funcId, (ref null eq) env}

	static final int TYPE_FLOAT = 7; // in rec group - {f64 value}

	static final int TYPE_WRITE_STR = 8; // (i32, i32) -> ()

	static final int TYPE_PRINT_VAL = 9; // ((ref null eq)) -> ()

	static final int TYPE_PRINT_F64 = 10; // (f64) -> ()

	// Callable types: arity N = (ref null eq)^(N+1) -> (ref null eq)
	// Used by dispatch functions and user functions (defuns/lambdas) alike
	static final int TYPE_CALLABLE_BASE = 11;

	// callable_arity_N type index = TYPE_CALLABLE_BASE + N (indices 11..18)

	// Callable types: arity N = (ref null eq)^(N+1) -> (ref null eq)
	// TYPE_READ_LINE: () -> (ref null eq)
	static final int TYPE_READ_LINE = TYPE_CALLABLE_BASE + MAX_CALLABLE_ARITY + 1; // 19

	// type index for _lookup: (i32) -> (i32); also used by the fd_close import
	static final int TYPE_LOOKUP = TYPE_READ_LINE + 1; // 20

	// type index for _env_lookup: (i32, (ref null eq)) -> (ref null eq)
	static final int TYPE_ENV_LOOKUP = TYPE_LOOKUP + 1; // 21

	// type index for _intern: (i32, i32) -> (i32)
	static final int TYPE_INTERN = TYPE_ENV_LOOKUP + 1; // 22

	// type index for path_open: (i32,i32,i32,i32,i32,i64,i64,i32,i32) -> (i32)
	static final int TYPE_PATH_OPEN = TYPE_ENV_LOOKUP + 2; // 23

	// Ratio struct {i32 numerator, i32 denominator}, always normalized (coprime,
	// denominator > 1, sign on the numerator). A rational whose denominator reduces to
	// one is represented as a plain i31 integer instead.
	static final int TYPE_RATIO = TYPE_PATH_OPEN + 1; // 24

	// type index for _rat_new: (i32, i32) -> (ref null eq)
	static final int TYPE_RAT_NEW = TYPE_RATIO + 1; // 25

	// type index for _rat_num/_rat_den: ((ref null eq)) -> (i32)
	static final int TYPE_RAT_GET = TYPE_RAT_NEW + 1; // 26

	// type index for _rat_cmp: ((ref null eq), (ref null eq)) -> (i32)
	static final int TYPE_RAT_CMP = TYPE_RAT_GET + 1; // 27

	// type index for _read_line: (i32 fd) -> (ref null eq)
	static final int TYPE_READ_LINE_FD = TYPE_RAT_CMP + 1; // 28

	// type index for _open: ((ref null eq) path, i32 mode) -> (ref null eq)
	static final int TYPE_OPEN = TYPE_READ_LINE_FD + 1; // 29

	// clock_time_get (i32 clock_id, i64 precision, i32 result_ptr) -> i32 errno
	static final int TYPE_CLOCK_TIME_GET = TYPE_OPEN + 1; // 30

	// fetch-start (8x i32) -> i32 errno: the adapter's http.fetch-start import /
	// Preview 1 stub type. Starts an outgoing request and writes the promise handle
	// (the wasi:http future-incoming-response handle) through the out pointer.
	static final int TYPE_FETCH_START = TYPE_CLOCK_TIME_GET + 1; // 31

	// fetch-await (6x i32) -> i32 errno: the adapter's http.fetch-await import /
	// Preview 1 stub type. Blocks on the promise handle and writes the response
	// status / headers / body back through the out pointers.
	static final int TYPE_FETCH_AWAIT = TYPE_FETCH_START + 1; // 32

	// Character struct {i32 code}: the runtime representation of a character, distinct
	// from
	// an i31 integer so characterp and the accessors can dispatch on it via ref.test.
	static final int TYPE_CHAR = TYPE_FETCH_AWAIT + 1; // 33

	// Hash-table bucket array: array (mut (ref null eq)). Each slot holds a bucket alist
	// (a cons chain of (key . value) entries) or null. Implicitly a subtype of eq, so a
	// bucket array can be stored in a cons/cell field and compared with ref.eq.
	static final int TYPE_HASH_BUCKETS = TYPE_CHAR + 1; // 34

	// Promise struct {mut i32 kind, mut (ref null eq) base, mut (ref null eq) fn}: the
	// runtime representation of a promise, distinct from every other value so promisep
	// and _promise_await dispatch on it via ref.test. kind 0 = a fetch root (base = the
	// i31-boxed wasi:http future handle), kind 1 = a rontolisp:then chain (base = the
	// chained value-or-promise, fn = the callback), kind 2 = settled (base = the
	// memoized result; _promise_await rewrites a promise in place after resolving it, so
	// the wasi:http response -- which future-incoming-response.get hands out only once
	// -- and a chain callback are each consumed exactly once however often the promise
	// is awaited).
	static final int TYPE_PROMISE = TYPE_HASH_BUCKETS + 1; // 35

	// Global (wasm global section) index holding the runtime eval top-level environment
	// (an association list of cons(name, value) bindings; ref.null eq when empty).
	static final int GLOBAL_ENV = 0;

	// Global (wasm global section) index holding the runtime eval function namespace
	// (Lisp-2): defuns evaluated at runtime (e.g. from load) are bound here, separate
	// from the variable environment above. (Await results are memoized inside each
	// TYPE_PROMISE struct, so no global cache is needed.)
	static final int GLOBAL_FENV = 1;

	// Memory layout
	static final int PRINT_BUF_OFFSET = 0;

	static final int IOV_OFFSET = 32;

	static final int NWRITTEN_OFFSET = 48;

	static final int OUT_BUF_OFFSET = 64;

	static final int HEAP_PTR_ADDR = 84;

	// Reader cursor/end (absolute byte offsets) used by the read/load runtime.
	static final int READ_CURSOR_ADDR = 88;

	static final int READ_END_ADDR = 92;

	// Scratch for path_open's output file descriptor (for load).
	static final int READ_FD_ADDR = 96;

	// Runtime intern table: a count cell plus a region of (offset,length) entries used by
	// _intern to give symbols parsed at runtime but absent from the compile-time table
	// (e.g. lambda parameters in loaded files) a stable offset across occurrences.
	static final int RT_INTERN_COUNT_ADDR = 100;

	// Capture mode for the string runtime: while CAPTURE_FLAG is non-zero, _write_str
	// appends bytes at CAPTURE_CUR (a heap cursor) instead of writing to stdout.
	static final int CAPTURE_FLAG_ADDR = 104;

	static final int CAPTURE_CUR_ADDR = 108;

	// Scratch for path_open's output file descriptor (for open).
	static final int OPEN_FD_ADDR = 112;

	// Tracks whether stdout is at the start of a line (0 = at line start, 1 = mid-line),
	// updated by _write_str on every stdout write so fresh-line (~&) can decide whether
	// to
	// emit a newline. Zero-initialized linear memory means we start at a line start.
	static final int LINE_START_ADDR = 116;

	// Scratch (8 bytes) where random_get writes its entropy bytes (Preview 1: the host's
	// wasi_snapshot_preview1 random_get; component: the adapter's wasi:random-backed
	// one).
	static final int RANDOM_SCRATCH_ADDR = 120;

	// Scratch (8 bytes) where clock_time_get writes the current time in nanoseconds.
	static final int TIME_SCRATCH_ADDR = 128;

	// getenv scratch: environ count / buffer-size words (low free region), and the
	// pointer
	// array + "KEY=VALUE\0" buffer placed in page 3 (the canonical realloc heap is page
	// 2).
	static final int ENV_COUNT_ADDR = 136;

	static final int ENV_BUFSIZE_ADDR = 140;

	// gensym counter word (zero-initialized linear memory, so the first symbol is
	// "#:g1").
	static final int GENSYM_CTR_ADDR = 144;

	// One-byte scratch cell moved through fd_read / fd_write by the read-byte /
	// write-byte runtime helpers (below the DATA_BASE_OFFSET=256 headroom, so no
	// interned string bytes are clobbered).
	static final int BYTE_SCRATCH_ADDR = 148;

	// Cell holding the runtime intern table's base address (see RT_INTERN_MIN_BASE);
	// seeded by an active data segment at instantiation from the program's actual
	// static-data size.
	static final int RT_INTERN_BASE_ADDR = 152;

	static final int ENV_PTRS_ADDR = 0x30000; // 196608, page 3

	static final int ENV_BUF_ADDR = 0x34000; // 212992, page 3 + 16 KiB

	// fetch scratch + request-header buffer in page 4 (0x40000), between the rontolisp
	// data/heap (pages 0-3) and the adapter scratch (page 5+). The adapter writes the
	// status and the addresses/lengths of the response-header and body buffers (which it
	// places in its own pages 6/7) through these out-pointers; REQ_HDR_BUF holds the
	// serialized request headers the adapter reads. Component mode only.
	static final int FETCH_STATUS_ADDR = 0x40000;

	static final int FETCH_RHDR_PTR_ADDR = 0x40004;

	static final int FETCH_RHDR_LEN_ADDR = 0x40008;

	static final int FETCH_BODY_PTR_ADDR = 0x4000C;

	static final int FETCH_BODY_LEN_ADDR = 0x40010;

	// fetch-start writes the promise handle (the wasi:http future-incoming-response
	// handle) here; the compiler boxes it as an i31 integer -- the promise value.
	static final int FETCH_HANDLE_ADDR = 0x40014;

	// sock.tcp-connect / tcp-listen / tcp-accept write the preview1-style socket fd
	// (>= 200, serviced by the sockets adapter's fd_read/fd_write/fd_close branches)
	// through this out-pointer; the compiler boxes it as an i31 integer -- the stream
	// handle. Component mode only.
	static final int SOCK_FD_ADDR = 0x40018;

	static final int REQ_HDR_BUF = 0x40100;

	// Minimum base address of the growable runtime intern table (8-byte (offset,len)
	// records appended by _intern for symbols first seen at runtime). The actual base
	// is computed per program -- max(this, 16-aligned end of the static string
	// segment) -- and seeded into the RT_INTERN_BASE_ADDR cell at instantiation, so
	// the records can never start inside the interned-string data (which outgrows a
	// fixed 8192 base on large programs; the old fixed base let runtime interning
	// silently corrupt static strings and the eval registry).
	static final int RT_INTERN_MIN_BASE = 8192;

	// Bytes reserved for the runtime intern table between its base and the heap base
	// (the historical 8192..16384 gap). The bump-allocator heap starts at
	// rtInternBase + this.
	static final int RT_INTERN_REGION_SIZE = 8192;

	// The interned-string data segment must start ABOVE every fixed scratch address
	// below,
	// or the host/adapter writes for getenv (ENV_COUNT_ADDR=136 .. ENV_BUFSIZE_ADDR=143)
	// and
	// the time built-ins (TIME_SCRATCH_ADDR=128 .. 135) would clobber shared string bytes
	// (notably the newline at the old base+9). The highest scratch byte
	// (BYTE_SCRATCH_ADDR=148) ends at 148, so 256
	// gives headroom; the next fixed region (RT_INTERN_BASE=8192) is far above realistic
	// string-segment sizes. Shifting this base does not move any function/import index,
	// so
	// the --component blobs are unaffected (see CLAUDE.md index-stability invariant).
	private static final int DATA_BASE_OFFSET = 256;

	@Override
	public byte[] compile(List<LispVal> program) {
		// Resolve packages (in-package directives, qualified symbols, *package*) up front
		// so
		// the rest of compilation sees canonical names.
		program = new PackageResolver().resolveProgram(program);
		// Splice top-level (progn ...)/(eval-when ...) so Pass 1 collects the defuns
		// nested in them (the CLI already flattens via UserMacroExpander; this keeps
		// direct compiler invocations equivalent).
		program = LispMacroExpander.flattenTopLevel(program);
		// Splice top-level defstructs into their generated defuns before lambda-list
		// desugaring (the generated constructor uses &key) so Pass 1 collects them as
		// ordinary functions; the registry makes accessors setf-able places.
		Map<String, Integer> structAccessors = new HashMap<>();
		program = LispMacroExpander.expandTopLevelDefstructs(program, structAccessors);
		// Desugar extended lambda lists (&optional/&key/&aux) into the native
		// "required + &rest" shape so the passes below only see that shape.
		program = LambdaLists.desugarProgram(program);
		// Detect whether the program uses (eval ...). When it does, a runtime
		// interpreter (_eval) and a function-name registry are emitted, and dispatch
		// functions are generated for every registered arity so _eval can apply them.
		// The reader runtime is emitted for read/load; load also evaluates each form, so
		// it pulls in the eval runtime as well.
		boolean usesLoad = programUsesSymbol(program, LispNames.LOAD);
		boolean usesRead = programUsesSymbol(program, LispNames.READ)
				|| programUsesSymbol(program, LispNames.READ_FROM_STRING) || usesLoad;
		// The apply built-in reuses the runtime _apply, so it forces the eval runtime.
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic
				|| programUsesSymbol(program, LispNames.APPLY);
		// rontolisp:fetch is component-only. In component mode it drives the
		// http.fetch-start / http.fetch-await imports (function indices
		// FUNC_FETCH_START / FUNC_FETCH_AWAIT); the component wrapper then imports
		// wasi:http. In Preview 1 mode those indices are unused trap stubs and fetch
		// raises a compile error (WasmFetchCompiler), so the imports/wasi:http are never
		// emitted. Only fetch itself needs the http machinery: await/then/promisep are
		// generic promise operations that compile in every mode (a root promise simply
		// cannot exist without fetch, so _promise_await's fetch-await call stays
		// unreachable).
		boolean usesFetch = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH));
		boolean emitHttpImport = this.component && usesFetch;
		// The rontolisp:tcp-* built-ins are component-only the same way: in component
		// mode they drive the sock.* imports (function indices FUNC_TCP_CONNECT ..
		// FUNC_TCP_LOCAL_PORT) implemented by the sockets adapter over
		// wasi:sockets@0.3.0; in Preview 1 mode those indices are unused trap stubs and
		// the built-ins raise a compile error (WasmTcpCompiler).
		boolean usesTcp = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_CONNECT))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LISTEN))
				|| programUsesSymbol(program, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_ACCEPT))
				|| programUsesSymbol(program,
						PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.TCP_LOCAL_PORT));
		boolean emitSockImport = this.component && usesTcp;
		// fetch and tcp sockets need different component blob variants (wasi:http 0.2
		// hybrid vs wasi:sockets 0.3); a combined variant does not exist yet.
		if (emitHttpImport && emitSockImport) {
			throw new UnsupportedOperationException(
					"rontolisp:fetch and rontolisp:tcp-* cannot be combined in one --component program yet");
		}
		// The serve component wires the wasi:http proxy world only; there is no serve
		// blob variant with wasi:sockets, so fail at compile time instead of emitting a
		// component that cannot instantiate.
		if (this.serve && emitSockImport) {
			throw new UnsupportedOperationException(
					"rontolisp:tcp-* cannot be used in a rontolisp:http-handler --component program yet");
		}
		// Pass 1: Collect defun declarations and top-level expressions. Lisp-2: only a
		// real (defun ...) form defines a function; a top-level (setq name (lambda ...))
		// binds a variable to a closure like any other setq.
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		// (rontolisp:wasm-export ...) directives: collected here and turned into
		// host-callable
		// export
		// wrappers below. They produce no code in the _start body (Preview 1 only;
		// ignored
		// in component mode).
		List<WasmExportCompiler.Decl> exportDecls = new ArrayList<>();
		// (rontolisp:wasm-import ...) directives: each becomes a Lisp-callable wrapper
		// registered like a top-level defun (Preview 1 only; rejected in component
		// mode), calling the imported host function through a placeholder index that
		// the WasmImportInjector post-pass resolves.
		List<WasmImportCompiler.Decl> importDecls = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons)));
			}
			else if (WasmExportCompiler.isExportForm(expr)) {
				exportDecls.add(WasmExportCompiler.parse((LispCons) expr));
			}
			else if (WasmImportCompiler.isImportForm(expr)) {
				importDecls.add(WasmImportCompiler.parse((LispCons) expr));
			}
			else {
				topLevelExprs.add(expr);
			}
		}
		if (!importDecls.isEmpty() && this.component) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-import is not supported with --component (Preview 1 core modules only)");
		}
		// Register each import as a synthetic defun so ordinary calls, #'name, funcall
		// and eval all reach it through the regular defun machinery; Pass 2a swaps in
		// the marshalling wrapper body instead of compiling the (empty) Lisp body.
		Map<String, WasmImportCompiler.Decl> importWrappers = new LinkedHashMap<>();
		for (WasmImportCompiler.Decl decl : importDecls) {
			boolean duplicate = importWrappers.containsKey(decl.name())
					|| defuns.stream().anyMatch(d -> d.name.equals(decl.name()));
			if (duplicate) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-import name collides with an existing function: " + decl.name());
			}
			List<String> paramNames = new ArrayList<>();
			for (int i = 0; i < decl.paramTypes().size(); i++) {
				paramNames.add("%wasm-import-p" + i);
			}
			importWrappers.put(decl.name(), decl);
			defuns.add(new DefunDecl(decl.name(), paramNames, false, List.of()));
		}
		// A :s-expr export parameter parses host-provided text with the embedded reader,
		// so
		// force the reader runtime on (FUNC_READ_EXPR must be a real body, not a stub).
		boolean exportNeedsReader = (!this.component)
				&& exportDecls.stream().anyMatch(d -> d.paramTypes().contains(WasmExportCompiler.T_S_EXPR));
		// An :s-expr import result likewise parses host-provided text at runtime.
		boolean importNeedsReader = importDecls.stream().anyMatch(WasmImportCompiler::needsReader);
		if (exportNeedsReader || importNeedsReader) {
			usesRead = true;
		}

		// Inject built-in function wrappers (user defuns take priority)
		Set<String> userDefinedNames = new HashSet<>();
		for (DefunDecl defun : defuns) {
			userDefinedNames.add(defun.name);
		}
		// parse-integer is inlined on WASM (no helper), but read-from-string pulls in the
		// reader runtime (FUNC_READ_EXPR), emitted only when usesRead. Exclude each
		// wrapper
		// unless the program references the symbol so the wrapper and its helper stay
		// gated
		// together (parse-integer gated for symmetry with the JVM backend).
		Set<String> wrapperExcludes = new HashSet<>(BuiltinFunctionWrappers.WASM_UNSUPPORTED);
		if (!programUsesSymbol(program, LispNames.PARSE_INTEGER)) {
			wrapperExcludes.add(LispNames.PARSE_INTEGER);
		}
		if (!usesRead) {
			wrapperExcludes.add(LispNames.READ_FROM_STRING);
		}
		// Hash-table wrappers compile inline hash code (and register maphash's arity-2
		// dispatch); only inject them when the program uses a hash table, gating the
		// whole
		// group together for symmetry with the JVM backend.
		if (!programUsesAnyHashOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		}
		for (LispVal wrapper : BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes)) {
			defuns.add(extractSetqLambda(wrapper));
		}

		// Collect top-level global variables and give each its own module-level wasm
		// global (mut (ref null eq)), placed after GLOBAL_ENV/GLOBAL_FENV (indices 2+).
		// A reference compiles to global.get from any function body, so a defun/lambda
		// can read a defvar/defparameter global. Indices follow declaration order.
		Set<String> globals = GlobalVarCollector.collect(topLevelExprs);
		Map<String, Integer> globalIndices = new HashMap<>();
		int nextGlobalIndex = GLOBAL_FENV + 1;
		for (String g : globals) {
			globalIndices.put(g, nextGlobalIndex++);
		}
		int globalCount = globals.size();

		// Create string table
		StringTable stringTable = new StringTable(DATA_BASE_OFFSET);

		// Assign funcIds and build function info map
		int[] nextFuncId = { 0 };
		Map<String, WasmFunctionInfo> functions = new HashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			DefunDecl defun = defuns.get(i);
			int funcId = nextFuncId[0]++;
			int arity = defun.paramNames.size();
			if (arity > MAX_CALLABLE_ARITY) {
				throw new UnsupportedOperationException("Cannot compile function '" + defun.name
						+ "': the WASM backend " + "supports at most " + MAX_CALLABLE_ARITY + " parameters, got "
						+ arity + " (bundle the extra arguments into a list)");
			}
			functions.put(defun.name, new WasmFunctionInfo(defun.name, arity, defun.variadic, funcId,
					TYPE_CALLABLE_BASE + arity, FUNC_USER_BASE + i));
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();

		// When eval is used, _eval applies any registered function via the dispatch
		// functions, so ensure a real dispatch body exists for every registered arity.
		// A variadic function is reachable from every dispatch arity >= its required
		// count, so all of those need real bodies.
		if (usesEval) {
			for (WasmFunctionInfo fi : functions.values()) {
				if (fi.paramCount() <= MAX_CALLABLE_ARITY) {
					if (fi.variadic()) {
						for (int a = fi.paramCount() - 1; a <= MAX_CALLABLE_ARITY; a++) {
							indirectCallArities.add(a);
						}
					}
					else {
						indirectCallArities.add(fi.paramCount());
					}
				}
			}
		}

		// Reusable builder template with shared constants and state
		Ctx.Builder ctxBuilder = Ctx.builder()
			.stringTable(stringTable)
			.functions(functions)
			.lambdaDecls(lambdaDecls)
			.indirectCallArities(indirectCallArities)
			.nextFuncId(nextFuncId)
			.dynamic(this.dynamic)
			.component(this.component)
			.userDefunNames(Set.copyOf(userDefinedNames))
			.structAccessors(structAccessors)
			.globals(globals)
			.globalIndices(globalIndices);

		// Pass 2a: Compile each defun body (with env param at slot 0)
		List<byte[]> userFunctionBodies = new ArrayList<>();
		// Import wrapper bodies are deferred until after the lambda pass: a :string
		// result calls the _str_from_mem helper, whose index follows the lambdas.
		Map<String, Integer> importBodySlots = new HashMap<>();
		for (DefunDecl defun : defuns) {
			if (importWrappers.containsKey(defun.name)) {
				importBodySlots.put(defun.name, userFunctionBodies.size());
				userFunctionBodies.add(null); // filled in below
				continue;
			}
			ByteArrayOutputStream funcBody = new ByteArrayOutputStream();
			WasmWriter funcWriter = new WasmWriter(funcBody);
			Ctx funcCtx = ctxBuilder.writer(funcWriter).bodyStream(funcBody).build();

			// Slot 0 = env (unused for defuns), params start at slot 1
			funcCtx.closureEnvSlot = 0;
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i + 1);
			}
			funcCtx.nextLocal = defun.paramNames.size() + 1;

			// Determine which params are captured by nested lambdas
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(defun.bodyExprs,
					new HashSet<>(defun.paramNames), functions.keySet());
			funcCtx.boxedVars = capturedVars;
			// Box captured params
			for (String paramName : defun.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = funcCtx.locals.get(paramName);
					if (slot != null) {
						WasmEmitHelper.emitBoxLocal(funcCtx, slot);
					}
				}
			}

			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcWriter.write(Instruction.DROP);
				}
				WasmExprCompiler.compileExpr(defun.bodyExprs.get(i), funcCtx);
			}
			funcWriter.write(Instruction.END);

			// Rebuild with correct local declarations (extra locals beyond env+params)
			ByteArrayOutputStream finalFuncBody = new ByteArrayOutputStream();
			WasmWriter finalFuncWriter = new WasmWriter(finalFuncBody);
			int extraLocals = funcCtx.nextLocal - (defun.paramNames.size() + 1);
			if (extraLocals > 0) {
				finalFuncWriter.write(1);
				finalFuncWriter.writeUnsignedLeb128(extraLocals);
				finalFuncWriter.write(Type.REFNULL.code());
				finalFuncWriter.writeHeapType(Type.EQ.code());
			}
			else {
				finalFuncWriter.write(0);
			}
			finalFuncWriter.write((Object) funcBody.toByteArray());
			userFunctionBodies.add(finalFuncBody.toByteArray());
		}

		// Pass 2b: Build _start function body
		ByteArrayOutputStream startBody = new ByteArrayOutputStream();
		WasmWriter startWriter = new WasmWriter(startBody);
		Ctx ctx = ctxBuilder.writer(startWriter).bodyStream(startBody).build();
		ctx.topLevel = true;
		ctx.usesEval = usesEval;

		// The heap pointer (HEAP_PTR_ADDR) is seeded by an active data segment at
		// instantiation (see writeDataSection below), not here: its value depends on
		// the final static-data size, which is unknown while this body is built.

		for (LispVal expr : topLevelExprs) {
			WasmExprCompiler.compileExpr(expr, ctx);
			startWriter.write(Instruction.DROP);
		}
		if (this.component) {
			// _start returns i32 (0 = ok) so it can be lifted as wasi:cli/run `run`
			startWriter.write(Instruction.I32_CONST);
			startWriter.writeSignedLeb128(0);
		}
		startWriter.write(Instruction.END);

		ByteArrayOutputStream finalStartBody = new ByteArrayOutputStream();
		WasmWriter finalStartWriter = new WasmWriter(finalStartBody);
		int numLocals = ctx.nextLocal;
		if (numLocals > 0) {
			finalStartWriter.write(1);
			finalStartWriter.writeUnsignedLeb128(numLocals);
			finalStartWriter.write(Type.REFNULL.code());
			finalStartWriter.writeHeapType(Type.EQ.code());
		}
		else {
			finalStartWriter.write(0);
		}
		finalStartWriter.write((Object) startBody.toByteArray());

		// Pass 2c: Compile lambda bodies iteratively
		List<byte[]> lambdaFunctionBodies = new ArrayList<>();
		int lambdaIdx = 0;
		while (lambdaIdx < lambdaDecls.size()) {
			LambdaInfo lambda = lambdaDecls.get(lambdaIdx);
			if (lambda.paramNames().size() > MAX_CALLABLE_ARITY) {
				throw new UnsupportedOperationException("Cannot compile lambda: the WASM backend supports at most "
						+ MAX_CALLABLE_ARITY + " parameters, got " + lambda.paramNames().size()
						+ " (bundle the extra arguments into a list)");
			}
			ByteArrayOutputStream lambdaBody = new ByteArrayOutputStream();
			WasmWriter lambdaWriter = new WasmWriter(lambdaBody);
			Ctx lambdaCtx = ctxBuilder.writer(lambdaWriter).bodyStream(lambdaBody).build();

			// Slot 0 = env (closure environment)
			lambdaCtx.closureEnvSlot = 0;
			// Lambda params start at slot 1
			for (int i = 0; i < lambda.paramNames.size(); i++) {
				lambdaCtx.locals.put(lambda.paramNames.get(i), i + 1);
			}
			lambdaCtx.nextLocal = lambda.paramNames.size() + 1;

			// Set up captures mapping (free vars accessed from env cons list)
			Map<String, Integer> captures = new HashMap<>();
			for (int i = 0; i < lambda.freeVarNames.size(); i++) {
				captures.put(lambda.freeVarNames.get(i), i);
			}
			lambdaCtx.captures = captures;

			// Determine which locals are captured by further nested lambdas
			Set<String> lambdaLocalVars = new HashSet<>(lambda.paramNames);
			Set<String> capturedVars = FreeVarAnalyzer.findCapturedVars(lambda.bodyExprs, lambdaLocalVars,
					functions.keySet());
			lambdaCtx.boxedVars = capturedVars;
			// Box captured params of this lambda
			for (String paramName : lambda.paramNames) {
				if (capturedVars.contains(paramName)) {
					Integer slot = lambdaCtx.locals.get(paramName);
					if (slot != null) {
						WasmEmitHelper.emitBoxLocal(lambdaCtx, slot);
					}
				}
			}

			for (int i = 0; i < lambda.bodyExprs.size(); i++) {
				if (i > 0) {
					lambdaWriter.write(Instruction.DROP);
				}
				WasmExprCompiler.compileExpr(lambda.bodyExprs.get(i), lambdaCtx);
			}
			lambdaWriter.write(Instruction.END);

			ByteArrayOutputStream finalLambdaBody = new ByteArrayOutputStream();
			WasmWriter finalLambdaWriter = new WasmWriter(finalLambdaBody);
			int extraLocals = lambdaCtx.nextLocal - (lambda.paramNames.size() + 1);
			if (extraLocals > 0) {
				finalLambdaWriter.write(1);
				finalLambdaWriter.writeUnsignedLeb128(extraLocals);
				finalLambdaWriter.write(Type.REFNULL.code());
				finalLambdaWriter.writeHeapType(Type.EQ.code());
			}
			else {
				finalLambdaWriter.write(0);
			}
			finalLambdaWriter.write((Object) lambdaBody.toByteArray());
			lambdaFunctionBodies.add(finalLambdaBody.toByteArray());
			lambdaIdx++;
		}

		// Build dispatch function bodies
		int numDefuns = defuns.size();
		int numLambdas = lambdaDecls.size();

		// Build host-callable export wrappers (Preview 1 only; ignored in component
		// mode).
		// Each (rontolisp:wasm-export ...) directive becomes a thin wrapper function
		// appended after
		// all user defuns and lambdas, exported under its Lisp name with a host-friendly
		// numeric / memory signature. Indices: wrapper function indices follow the
		// lambdas;
		// wrapper type indices follow TYPE_HASH_BUCKETS (the last fixed type).
		List<ExportPlan> exportPlans = new ArrayList<>();
		List<byte[]> exportBodies = new ArrayList<>();
		// Memory-backed exports (:string/:s-expr) need two appended helper functions: the
		// host-facing bump allocator __ronto_alloc and the _str_from_mem string builder.
		// They precede the wrappers so the fixed FUNC_* constants are unaffected.
		boolean exportUsesMemory = (!this.component || this.serve)
				&& exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory);
		int exportHelperBase = FUNC_USER_BASE + numDefuns + numLambdas;
		// A :string import result is written into linear memory by the host and boxed
		// with the same _str_from_mem helper, so it forces the helper pair on too.
		boolean importUsesStrFromMem = importDecls.stream().anyMatch(WasmImportCompiler::usesStrFromMem);
		boolean memoryHelpers = exportUsesMemory || importUsesStrFromMem;
		int allocFuncIndex = memoryHelpers ? exportHelperBase : -1;
		int strFromMemFuncIndex = memoryHelpers ? exportHelperBase + 1 : -1;
		// Fill in the deferred import wrapper bodies now that the helper indices are
		// known (their positions in userFunctionBodies were reserved in Pass 2a).
		{
			int ordinal = 0;
			for (WasmImportCompiler.Decl decl : importWrappers.values()) {
				byte[] body = WasmImportCompiler.buildWrapperBody(ctxBuilder, decl, ordinal++, strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(decl.name())), body);
			}
		}
		if ((!this.component || this.serve) && !exportDecls.isEmpty()) {
			int wrapperFuncIndex = exportHelperBase + (memoryHelpers ? 2 : 0);
			int wrapperTypeIndex = TYPE_PROMISE + 1;
			for (WasmExportCompiler.Decl decl : exportDecls) {
				WasmFunctionInfo target = functions.get(decl.name());
				if (target == null || !userDefinedNames.contains(decl.name())) {
					throw new UnsupportedOperationException(
							"rontolisp:wasm-export names an unknown function (must be a top-level defun): "
									+ decl.name());
				}
				if (decl.paramTypes().size() != target.paramCount()) {
					throw new UnsupportedOperationException("rontolisp:wasm-export arity mismatch for '" + decl.name()
							+ "': declared " + decl.paramTypes().size() + " params, but the function takes "
							+ target.paramCount());
				}
				ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
				WasmWriter bodyWriter = new WasmWriter(bodyStream);
				Ctx wrapperCtx = ctxBuilder.writer(bodyWriter).bodyStream(bodyStream).build();
				int paramSlots = WasmExportCompiler.paramSlotCount(decl);
				wrapperCtx.nextLocal = paramSlots;
				WasmExportCompiler.emitBody(wrapperCtx, decl, target.funcIndex(), strFromMemFuncIndex);
				// Prepend the local declarations (extra (ref null eq) locals beyond
				// params).
				ByteArrayOutputStream finalBody = new ByteArrayOutputStream();
				WasmWriter finalWriter = new WasmWriter(finalBody);
				int extraLocals = wrapperCtx.nextLocal - paramSlots;
				if (extraLocals > 0) {
					finalWriter.write(1);
					finalWriter.writeUnsignedLeb128(extraLocals);
					finalWriter.write(Type.REFNULL.code());
					finalWriter.writeHeapType(Type.EQ.code());
				}
				else {
					finalWriter.write(0);
				}
				finalWriter.write((Object) bodyStream.toByteArray());
				exportBodies.add(finalBody.toByteArray());
				exportPlans.add(new ExportPlan(decl, target.funcIndex(), wrapperTypeIndex++, wrapperFuncIndex++));
			}
		}

		// Host-ABI type entries for the imported functions follow the export wrapper
		// types; the WasmImportInjector post-pass prepends the matching import entries
		// and resolves the placeholder call indices after the module is assembled.
		List<am.ik.wasm.WasmImportInjector.HostImport> hostImports = new ArrayList<>();
		int importTypeIndex = TYPE_PROMISE + 1 + exportPlans.size();
		for (WasmImportCompiler.Decl decl : importWrappers.values()) {
			hostImports
				.add(new am.ik.wasm.WasmImportInjector.HostImport(decl.module(), decl.field(), importTypeIndex++));
		}

		List<byte[]> dispatchBodies = new ArrayList<>();
		for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
			if (indirectCallArities.contains(arity)) {
				dispatchBodies.add(WasmRuntimeBuilder.buildDispatchBody(arity, defuns, lambdaDecls, numDefuns,
						stringTable, usesEval));
			}
			else {
				// Unused arity: unreachable body
				ByteArrayOutputStream db = new ByteArrayOutputStream();
				WasmWriter dw = new WasmWriter(db);
				dw.write(0); // 0 locals
				dw.write(Instruction.UNREACHABLE);
				dw.write(Instruction.END);
				dispatchBodies.add(db.toByteArray());
			}
		}

		// Build helper function bodies
		byte[] printI32Body = WasmRuntimeBuilder.buildPrintI32Core(true);
		byte[] writeStrBody = WasmRuntimeBuilder.buildWriteStrBody();
		byte[] printValBody = WasmRuntimeBuilder.buildPrintValBody(stringTable);
		byte[] printI32NoNlBody = WasmRuntimeBuilder.buildPrintI32Core(false);
		byte[] printF64Body = WasmRuntimeBuilder.buildPrintF64Core(true, stringTable);
		byte[] printF64NoNlBody = WasmRuntimeBuilder.buildPrintF64Core(false, stringTable);
		byte[] appendBody = WasmRuntimeBuilder.buildAppendBody();
		byte[] readLineBody = WasmRuntimeBuilder.buildReadLineBody(stringTable);
		byte[] princValBody = WasmRuntimeBuilder.buildPrincValBody(stringTable);

		// Build the eval runtime (interpreter + function-name registry). The registry
		// maps a symbol-name string offset to (funcId, arity). Because the string table
		// deduplicates, a quoted symbol such as 'car compiles to the same offset as the
		// registry entry for "car", so lookup is a plain i32 offset comparison.
		final byte[] lookupBody;
		final byte[] envLookupBody;
		final byte[] evalBody;
		final byte[] applyBody;
		final byte[] storeBody;
		if (usesEval) {
			WasmEvalRuntimeBuilder.SpecialFormOffsets offsets = WasmEvalRuntimeBuilder.SpecialFormOffsets.builder()
				.add(stringTable, LispNames.QUOTE)
				.add(stringTable, LispNames.IF)
				.add(stringTable, LispNames.PROGN)
				.add(stringTable, LispNames.LET)
				.add(stringTable, LispNames.LAMBDA)
				.add(stringTable, LispNames.DEFUN)
				.add(stringTable, LispNames.COND)
				.add(stringTable, LispNames.AND)
				.add(stringTable, LispNames.OR)
				.add(stringTable, LispNames.WHEN)
				.add(stringTable, LispNames.UNLESS)
				.add(stringTable, LispNames.WHILE)
				.add(stringTable, LispNames.DOTIMES)
				.add(stringTable, LispNames.SETQ)
				.add(stringTable, LispNames.EVAL)
				.add(stringTable, LispNames.FUNCALL)
				.add(stringTable, LispNames.MAPCAR)
				.add(stringTable, LispNames.MAPC)
				.add(stringTable, LispNames.REDUCE)
				.add(stringTable, LispNames.LIST)
				.add(stringTable, LispNames.ADD)
				.add(stringTable, LispNames.SUB)
				.add(stringTable, LispNames.MUL)
				.add(stringTable, LispNames.DIV)
				.add(stringTable, "t")
				.add(stringTable, LispNames.FIRST)
				.add(stringTable, LispNames.REST)
				.add(stringTable, LispNames.SECOND)
				.add(stringTable, LispNames.THIRD)
				.add(stringTable, LispNames.FOURTH)
				.add(stringTable, LispNames.NTH)
				.add(stringTable, LispNames.SETF)
				.add(stringTable, LispNames.PUSH)
				.add(stringTable, LispNames.POP)
				.add(stringTable, LispNames.FUNCTION)
				.add(stringTable, LispNames.SYMBOL_FUNCTION)
				.build();
			ByteArrayOutputStream registry = new ByteArrayOutputStream();
			for (int i = 0; i < defuns.size(); i++) {
				DefunDecl defun = defuns.get(i);
				int nameOffset = stringTable.addString(defun.name).offset();
				writeLittleEndian32(registry, nameOffset);
				writeLittleEndian32(registry, i); // funcId == defun index
				// arity; a variadic function is encoded as -physicalParamCount so the
				// eval call path evaluates every argument instead of exactly arity
				writeLittleEndian32(registry, defun.variadic ? -defun.paramNames.size() : defun.paramNames.size());
			}
			int registryBase = stringTable.appendBlob(registry.toByteArray());
			lookupBody = WasmEvalRuntimeBuilder.buildLookupBody(registryBase, defuns.size());
			envLookupBody = WasmEvalRuntimeBuilder.buildEnvLookupBody();
			evalBody = WasmEvalRuntimeBuilder.buildEvalBody(offsets);
			applyBody = WasmEvalRuntimeBuilder.buildApplyBody();
			storeBody = WasmEvalRuntimeBuilder.buildStoreBody(offsets);
		}
		else {
			lookupBody = WasmEvalRuntimeBuilder.buildLookupStub();
			envLookupBody = WasmEvalRuntimeBuilder.buildEnvLookupStub();
			evalBody = WasmEvalRuntimeBuilder.buildEvalStub();
			applyBody = WasmEvalRuntimeBuilder.buildApplyStub();
			storeBody = WasmEvalRuntimeBuilder.buildStoreStub();
		}

		// Build the reader runtime (read/load). Symbols parsed at runtime are interned
		// against a compile-time table of (offset,length) so they match the offsets the
		// eval runtime compares against.
		final byte[] internBody;
		final byte[] readExprBody;
		final byte[] readListBody;
		final byte[] readBody;
		final byte[] loadBody;
		if (usesRead) {
			// Intern nil/t/quote/function before snapshotting so the runtime resolves
			// them to the same offsets the eval runtime uses.
			int nilOffset = stringTable.addString("nil").offset();
			int tOffset = stringTable.addString("t").offset();
			int quoteOffset = stringTable.addString(LispNames.QUOTE).offset();
			int functionOffset = stringTable.addString(LispNames.FUNCTION).offset();
			java.util.Collection<StringTable.StringEntry> internEntries = stringTable.entries();
			int internCount = internEntries.size();
			int internBase = stringTable.appendBlob(buildInternBlob(internEntries));
			internBody = WasmReadRuntimeBuilder.buildInternBody(internBase, internCount);
			readExprBody = WasmReadRuntimeBuilder.buildReadExprBody(nilOffset, tOffset, quoteOffset, functionOffset);
			readListBody = WasmReadRuntimeBuilder.buildReadListBody();
			readBody = WasmReadRuntimeBuilder.buildReadBody();
			loadBody = WasmReadRuntimeBuilder.buildLoadBody();
		}
		else {
			internBody = WasmReadRuntimeBuilder.buildInternStub();
			readExprBody = WasmReadRuntimeBuilder.buildReadExprStub();
			readListBody = WasmReadRuntimeBuilder.buildReadListStub();
			readBody = WasmReadRuntimeBuilder.buildReadStub();
			loadBody = WasmReadRuntimeBuilder.buildLoadStub();
		}

		// Final static-data layout. The string table is complete here (its last append
		// was the runtime-reader intern blob above), so the runtime intern table's base
		// and the bump-allocator heap base can be derived from its size; both are seeded
		// into fixed cells by active data segments below. This keeps runtime interning
		// and heap allocation above the static data no matter how large the program is.
		byte[] stringData = stringTable.toByteArray();
		int staticEnd = DATA_BASE_OFFSET + stringData.length;
		int rtInternBase = Math.max(RT_INTERN_MIN_BASE, (staticEnd + 15) & ~15);
		int heapBase = rtInternBase + RT_INTERN_REGION_SIZE;

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter mainWriter = new WasmWriter(out);
		mainWriter //
			.write("\0asm")
			.writeLittleEndian4(1)
			// Type section
			.writeTypeSection(types -> {
				// type 0: fd_write
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 1: _start -- in component mode it returns i32 (0 = ok) so it can
				// be
				// lifted as the wasi:cli/run `run` entry
				types.addFunc(new Type[] {}, this.component ? new Type[] { Type.I32 } : new Type[] {});
				// type 2: print_i32 / _print_i32_no_nl
				types.addFunc(new Type[] { Type.I32 }, new Type[] {});
				// types 3-7: struct types in rec group
				types.addRecGroup(rec -> {
					// type 3: cons struct
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 4: string struct
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
					});
					// type 5: cell struct {(mut ref null eq) value}
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 6: closure struct {i32 funcId, (ref null eq) env}
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 7: float struct {f64 value}
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.F64));
					});
				});
				// type 8: _write_str
				types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] {});
				// type 9: _print_val
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(0);
				});
				// type 10: _print_f64 / _print_f64_no_nl
				types.addFunc(new Type[] { Type.F64 }, new Type[] {});
				// types 11-18: callable types for arities 0-7
				// Each: (ref null eq)^(arity+1) -> (ref null eq)
				for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
					int paramCount = arity + 1; // env + args
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(paramCount);
						for (int i = 0; i < paramCount; i++) {
							w.write(Type.REFNULL.code());
							w.writeHeapType(Type.EQ.code());
						}
						w.write(1);
						w.write(Type.REFNULL.code());
						w.writeHeapType(Type.EQ.code());
					});
				}
				// type 19: _read_line () -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(0); // no params
					w.write(1); // 1 result
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
				});
				// type 20: _lookup (i32) -> (i32)
				types.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 });
				// type 21: _env_lookup (i32, (ref null eq)) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.I32);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
				});
				// type 22: _intern (i32, i32) -> (i32)
				types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 23: path_open (i32,i32,i32,i32,i32,i64,i64,i32,i32) -> (i32)
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I64, Type.I64,
						Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 24: ratio struct {i32 numerator, i32 denominator}
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(false, w -> w.write(Type.I32));
					fields.addField(false, w -> w.write(Type.I32));
				}));
				// type 25: _rat_new (i32, i32) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
				});
				// type 26: _rat_num/_rat_den ((ref null eq)) -> (i32)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(1);
					w.write(Type.I32);
				});
				// type 27: _rat_cmp ((ref null eq), (ref null eq)) -> (i32)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(1);
					w.write(Type.I32);
				});
				// type 28: _read_line (i32 fd) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1);
					w.write(Type.I32);
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
				});
				// type 29: _open ((ref null eq) path, i32 mode) -> (ref null eq)
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
				});
				// type 30: clock_time_get (i32, i64, i32) -> i32
				types.addFunc(new Type[] { Type.I32, Type.I64, Type.I32 }, new Type[] { Type.I32 });
				// type 31 (TYPE_FETCH_START): fetch-start (8x i32) -> i32 errno. The 8
				// params are method, urlPtr, urlLen, reqBodyPtr, reqBodyLen, reqHdrPtr,
				// reqHdrLen, handleOut.
				types.addFunc(
						new Type[] { Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32 },
						new Type[] { Type.I32 });
				// type 32 (TYPE_FETCH_AWAIT): fetch-await (6x i32) -> i32 errno. The 6
				// params are handle, statusPtr, rhdrPtrOut, rhdrLenOut, bodyPtrOut,
				// bodyLenOut.
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32 },
						new Type[] { Type.I32 });
				// type 33 (TYPE_CHAR): character struct {i32 code}
				types.addRecGroup(
						rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I32))));
				// type 34 (TYPE_HASH_BUCKETS): array (mut (ref null eq)) -- hash-table
				// buckets. Encoded as a bare array comptype (sugar for sub final),
				// implicitly
				// a subtype of eq so it stores in cons/cell fields and supports ref.eq.
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.writeRefType(true, Type.EQ.code());
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 35 (TYPE_PROMISE): promise struct {mut i32 kind, mut (ref null eq)
				// base, mut (ref null eq) fn} -- all fields mutable so _promise_await can
				// rewrite a promise to its settled state in place.
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(true, w -> w.write(Type.I32));
					fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
				}));
				// Export wrapper signatures (host-callable), appended after the last
				// fixed
				// type (TYPE_PROMISE). One per (rontolisp:wasm-export ...)
				// directive.
				for (ExportPlan p : exportPlans) {
					types.addFunc(WasmExportCompiler.paramWasmTypes(p.decl()),
							WasmExportCompiler.resultWasmTypes(p.decl()));
				}
				// Host-ABI signatures of the imported functions, one per
				// (rontolisp:wasm-import ...), in ordinal order after the export
				// wrapper types (matching the indices recorded in hostImports).
				for (WasmImportCompiler.Decl decl : importWrappers.values()) {
					types.addFunc(WasmImportCompiler.hostParamTypes(decl), WasmImportCompiler.hostResultTypes(decl));
				}
			})
			// Import section
			.writeImportSection(imports -> {
				// No-wasi (reactor) mode: emit no wasi_snapshot_preview1 imports so the
				// module instantiates with no import object. Function indices 0-7 are
				// filled
				// with internal trap stubs in the function/code sections below, keeping
				// every
				// FUNC_* constant valid.
				// Serve mode keeps the imports: the preview1 bridge
				// (adapter-serve-p1.wasm, instantiated before this core by
				// WasmServeComponentBuilder) provides them over the wasi:http proxy
				// world (random / clocks / stdout-stderr; graceful stubs for the rest).
				if (!this.noWasi) {
					imports.addImport("wasi_snapshot_preview1", "fd_write", ExternalKind.FUNCTION, TYPE_FD_WRITE)
						.addImport("wasi_snapshot_preview1", "fd_read", ExternalKind.FUNCTION, TYPE_FD_WRITE)
						.addImport("wasi_snapshot_preview1", "path_open", ExternalKind.FUNCTION, TYPE_PATH_OPEN)
						.addImport("wasi_snapshot_preview1", "fd_close", ExternalKind.FUNCTION, TYPE_LOOKUP)
						// random_get(buf, len) -> errno: (i32, i32) -> i32, same shape as
						// _intern. Preview 1 binds the real host function; component mode
						// binds
						// the adapter's wasi:random-backed implementation.
						.addImport("wasi_snapshot_preview1", "random_get", ExternalKind.FUNCTION, TYPE_INTERN)
						// clock_time_get / environ_sizes_get / environ_get for time and
						// getenv.
						// Component mode binds adapter implementations over wasi:clocks /
						// wasi:cli-environment; Preview 1 binds the real host functions.
						.addImport("wasi_snapshot_preview1", "clock_time_get", ExternalKind.FUNCTION,
								TYPE_CLOCK_TIME_GET)
						.addImport("wasi_snapshot_preview1", "environ_sizes_get", ExternalKind.FUNCTION, TYPE_INTERN)
						.addImport("wasi_snapshot_preview1", "environ_get", ExternalKind.FUNCTION, TYPE_INTERN);
				}
				// Function imports at indices FUNC_TCP_CONNECT .. FUNC_TCP_LOCAL_PORT
				// (8-11): the sockets adapter's exports over wasi:sockets@0.3.0 when the
				// program uses a tcp built-in in component mode, or the http adapter's
				// errno-returning stub exports when the program uses fetch (imports must
				// precede defined functions, so the sock slots cannot be defined stubs
				// while the http slots at 12-13 are imports). Otherwise (Preview 1, or a
				// component using neither) they are defined trap stubs.
				// tcp-connect/tcp-listen(hostPtr, hostLen, port, fdOut) -> errno share
				// fd_write's (4x i32) -> i32 shape; tcp-accept(fd, fdOut) -> errno and
				// tcp-local-port(fd, portOut) -> errno are (2x i32) -> i32 like _intern.
				if (emitSockImport || emitHttpImport) {
					imports.addImport("sock", "tcp-connect", ExternalKind.FUNCTION, TYPE_FD_WRITE);
					imports.addImport("sock", "tcp-listen", ExternalKind.FUNCTION, TYPE_FD_WRITE);
					imports.addImport("sock", "tcp-accept", ExternalKind.FUNCTION, TYPE_INTERN);
					imports.addImport("sock", "tcp-local-port", ExternalKind.FUNCTION, TYPE_INTERN);
				}
				// Function imports at indices FUNC_FETCH_START / FUNC_FETCH_AWAIT (12-13)
				// when the program calls fetch/await in component mode: the adapter's
				// exported http.fetch-start / http.fetch-await. In Preview 1 mode (or a
				// component without fetch) those indices are defined trap stubs instead.
				if (emitHttpImport) {
					imports.addImport("http", "fetch-start", ExternalKind.FUNCTION, TYPE_FETCH_START);
					imports.addImport("http", "fetch-await", ExternalKind.FUNCTION, TYPE_FETCH_AWAIT);
				}
				if (this.component) {
					// Import the linear memory from the shared canonical-memory module so
					// the
					// lowered WASI imports and this module share one memory.
					imports.add(w -> {
						w.write("mem".length(), "mem", "memory".length(), "memory");
						w.write(ExternalKind.MEMORY);
						w.write(0x00); // limits: min only
						w.writeUnsignedLeb128(4); // min 4 pages
					});
				}
			})
			// Function section
			.writeFunction(fnDef -> {
				// No-wasi mode: the eight wasi imports were omitted, so define eight trap
				// stubs at function indices 0-7 with the SAME type indices the imports
				// used
				// (fd_write, fd_read, path_open, fd_close, random_get, clock_time_get,
				// environ_sizes_get, environ_get). This keeps every FUNC_* constant
				// valid.
				if (this.noWasi) {
					fnDef.addFunction(TYPE_FD_WRITE) // 0: fd_write
						.addFunction(TYPE_FD_WRITE) // 1: fd_read
						.addFunction(TYPE_PATH_OPEN) // 2: path_open
						.addFunction(TYPE_LOOKUP) // 3: fd_close
						.addFunction(TYPE_INTERN) // 4: random_get
						.addFunction(TYPE_CLOCK_TIME_GET) // 5: clock_time_get
						.addFunction(TYPE_INTERN) // 6: environ_sizes_get
						.addFunction(TYPE_INTERN); // 7: environ_get
				}
				// Reserve function indices FUNC_TCP_CONNECT (8) .. FUNC_TCP_LOCAL_PORT
				// (11): sock.* imports when the program uses tcp OR fetch in component
				// mode (the fetch case binds the http adapter's errno stubs); otherwise
				// defined trap stubs so the following defined-function indices line up
				// with the FUNC_* constants.
				if (!(emitSockImport || emitHttpImport)) {
					fnDef.addFunction(TYPE_FD_WRITE); // index 8: unused tcp-connect stub
					fnDef.addFunction(TYPE_FD_WRITE); // index 9: unused tcp-listen stub
					fnDef.addFunction(TYPE_INTERN); // index 10: unused tcp-accept stub
					fnDef.addFunction(TYPE_INTERN); // index 11: unused tcp-local-port
													// stub
				}
				// Reserve function indices FUNC_FETCH_START (12) / FUNC_FETCH_AWAIT (13):
				// in component+fetch they are the http.fetch-start / http.fetch-await
				// imports (emitted above); otherwise defined trap stubs.
				if (!emitHttpImport) {
					fnDef.addFunction(TYPE_FETCH_START); // index 12: unused fetch-start
															// stub
					fnDef.addFunction(TYPE_FETCH_AWAIT); // index 13: unused fetch-await
															// stub
				}
				fnDef.addFunction(TYPE_START) // _start
					.addFunction(TYPE_PRINT_I32) // print_i32
					.addFunction(TYPE_WRITE_STR) // _write_str
					.addFunction(TYPE_PRINT_VAL) // _print_val
					.addFunction(TYPE_PRINT_I32) // _print_i32_no_nl
					.addFunction(TYPE_PRINT_F64) // _print_f64
					.addFunction(TYPE_PRINT_F64) // _print_f64_no_nl
					.addFunction(TYPE_CALLABLE_BASE + 1) // _append
					.addFunction(TYPE_READ_LINE_FD) // _read_line
					.addFunction(TYPE_PRINT_VAL); // _princ_val
				fnDef.addFunction(TYPE_LOOKUP); // _lookup
				fnDef.addFunction(TYPE_ENV_LOOKUP); // _env_lookup
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _eval (form, env) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _apply (fn, args) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _store (place, value, env)
															// -> value
				fnDef.addFunction(TYPE_INTERN); // _intern (off, len) -> canonicalOff
				fnDef.addFunction(TYPE_READ_LINE); // _read_expr () -> value
				fnDef.addFunction(TYPE_READ_LINE); // _read_list () -> value
				fnDef.addFunction(TYPE_READ_LINE_FD); // _read (i32 fd) -> value
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _load (path) -> value
				// Rational runtime
				fnDef.addFunction(TYPE_RAT_NEW); // _rat_new
				fnDef.addFunction(TYPE_RAT_GET); // _rat_num
				fnDef.addFunction(TYPE_RAT_GET); // _rat_den
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_add
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_sub
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_mul
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_div
				fnDef.addFunction(TYPE_RAT_CMP); // _rat_cmp
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_trunc
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_floor
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_ceil
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _rat_round
				// String runtime
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _princ_to_str
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _prin1_to_str
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _string_concat
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _string_upcase
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _string_downcase
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _string_capitalize
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _subseq
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _string_eq
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _string_equal
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _string_trim
				// File-stream runtime
				fnDef.addFunction(TYPE_OPEN); // _open
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _close
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _write_line
				// Structural equality runtime
				fnDef.addFunction(TYPE_RAT_CMP); // _equal ((ref null eq), (ref null eq))
													// -> i32
				// getenv runtime
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _getenv ((ref null eq)) ->
															// (ref null eq)
				// Dispatch functions (arities 0-7)
				for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + arity);
				}
				// fetch runtime helpers (FUNC_FETCH_STR..FUNC_FETCH_DESER_HDRS)
				fnDef.addFunction(TYPE_RAT_NEW); // _fetch_str (i32, i32) -> (ref null eq)
				fnDef.addFunction(TYPE_OPEN); // _fetch_plist_get ((ref null eq), i32) ->
												// (ref null eq)
				fnDef.addFunction(TYPE_RAT_GET); // _fetch_ser_headers ((ref null eq)) ->
													// i32
				fnDef.addFunction(TYPE_READ_LINE_FD); // _fetch_deser_headers (i32) ->
														// (ref null eq)
				// Hash-table runtime helpers
				fnDef.addFunction(TYPE_RAT_GET); // _hash ((ref null eq)) -> i32
				fnDef.addFunction(TYPE_PRINT_VAL); // _hash_resize ((ref null eq)) -> ()
				// Modulo / remainder runtime helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_rem
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _rat_mod
				// gensym runtime helper
				fnDef.addFunction(TYPE_RAT_NEW); // _gensym (i32, i32) -> (ref null eq)
				// promise-await runtime helper
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _promise_await
				// binary stream runtime helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _read_byte (stream,
															// eof-error-p, eof-value) ->
															// (ref null eq)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _write_byte (byte, stream)
															// -> (ref null eq)
				// string-stream runtime helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 1); // _write_stream_str (str,
															// stream) -> (ref null eq)
				fnDef.addFunction(TYPE_READ_LINE); // _make_str_ostream () -> (ref null
													// eq)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _make_str_istream (str)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _str_stream_contents
															// (stream)
				// User defun functions
				for (DefunDecl defun : defuns) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + defun.paramNames.size());
				}
				// Lambda functions
				for (LambdaInfo lambda : lambdaDecls) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + lambda.paramNames.size());
				}
				// Memory-export helpers: __ronto_alloc ((i32)->i32, reuses TYPE_LOOKUP)
				// and
				// _str_from_mem ((i32,i32)->(ref null eq), reuses TYPE_RAT_NEW).
				if (memoryHelpers) {
					fnDef.addFunction(TYPE_LOOKUP);
					fnDef.addFunction(TYPE_RAT_NEW);
				}
				// Export wrapper functions (host-callable), one per
				// (rontolisp:wasm-export ...).
				for (ExportPlan p : exportPlans) {
					fnDef.addFunction(p.typeIndex());
				}
			})
			// Memory section -- in component mode the memory is imported (above), so this
			// section is empty. 4 pages so getenv can place the environ buffer in page 3
			// (the canonical realloc heap is page 1+ in component mode).
			.writeMemory(memories -> {
				if (!this.component) {
					// At least 4 pages (getenv places the environ buffer in page 3); a
					// program whose computed heap base approaches that keeps the same
					// ~3.7 pages of heap headroom the fixed 16384 base used to leave.
					memories.addMemory(Math.max(4, (heapBase + 65535) / 65536 + 3));
				}
			})
			// Global section: the eval top-level variable environment (GLOBAL_ENV) and
			// the Lisp-2 function namespace (GLOBAL_FENV), both (mut (ref null eq)) =
			// null
			.writeGlobal(gs -> {
				gs.add(g -> {
					g.write(Type.REFNULL.code());
					g.writeHeapType(Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				}).add(g -> {
					g.write(Type.REFNULL.code());
					g.writeHeapType(Type.EQ.code());
					g.write(am.ik.wasm.Mutability.VAR.code());
					g.write(Instruction.REF_NULL);
					g.writeHeapType(Type.EQ.code());
					g.write(Instruction.END);
				});
				// One (mut (ref null eq)) = null per top-level global variable (indices
				// 2+).
				for (int i = 0; i < globalCount; i++) {
					gs.add(g -> {
						g.write(Type.REFNULL.code());
						g.writeHeapType(Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
				}
			})
			// Export section -- component mode exports `run` (the i32-returning _start)
			// for
			// the lifted wasi:cli/run entry; the memory is imported, not exported
			.writeExport(exports -> {
				if (this.component) {
					exports.addExport("run", ExternalKind.FUNCTION, FUNC_START);
					// Serve mode: the serve adapter calls the core's %http-dispatch (the
					// wasm-export wrapper) per request and __ronto_alloc for its scratch,
					// so
					// export them alongside `run` (which the adapter runs once as init).
					// The
					// memory stays imported (from the shared mem module).
					if (this.serve) {
						if (memoryHelpers) {
							exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, allocFuncIndex);
						}
						for (ExportPlan p : exportPlans) {
							exports.addExport(p.decl().exportName(), ExternalKind.FUNCTION, p.funcIndex());
						}
					}
				}
				else {
					// A no-wasi module is a reactor/library, not a WASI command, so name
					// its
					// top-level init entry `_initialize` (the reactor ABI convention: a
					// host
					// runs it once after instantiation) rather than `_start` (the command
					// entrypoint a no-WASI host never calls). Either way it runs the
					// program's
					// top-level initializers (defvar/defparameter/setq globals an export
					// reads)
					// and seeds the heap pointer, and stays a tree-shaker root.
					String initExport = this.noWasi ? "_initialize" : "_start";
					exports.addExport("memory", ExternalKind.MEMORY, 0)
						.addExport(initExport, ExternalKind.FUNCTION, FUNC_START);
					// The host-facing bump allocator, when any memory-typed export is
					// present (a host calls it to reserve a scratch buffer for
					// string/sexpr inputs) or an import returns a :string the host must
					// write into linear memory.
					if (memoryHelpers) {
						exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, allocFuncIndex);
					}
					// Host-callable Lisp functions requested via (rontolisp:wasm-export
					// ...), each under its :as alias (default: the Lisp name).
					for (ExportPlan p : exportPlans) {
						exports.addExport(p.decl().exportName(), ExternalKind.FUNCTION, p.funcIndex());
					}
				}
			})
			// Code section
			.writeCode(code -> {
				// No-wasi mode: bodies for the eight trap stubs at indices 0-7. Each is
				// `unreachable; end` (no locals); unreachable is stack-polymorphic so one
				// shape satisfies every WASI signature. Calling one (i.e. any I/O) traps.
				if (this.noWasi) {
					for (int i = 0; i < IMPORT_FUNC_COUNT; i++) {
						code.addFunction(new byte[] { 0x00, 0x00, 0x0b });
					}
				}
				// tcp-connect / tcp-listen / tcp-accept / tcp-local-port trap stubs at
				// function indices FUNC_TCP_CONNECT .. FUNC_TCP_LOCAL_PORT (only when
				// they are not the sock.* imports): no locals, unreachable, end. Never
				// called.
				if (!(emitSockImport || emitHttpImport)) {
					for (int i = 0; i < 4; i++) {
						code.addFunction(new byte[] { 0x00, 0x00, 0x0b });
					}
				}
				// fetch-start / fetch-await trap stubs at function indices
				// FUNC_FETCH_START / FUNC_FETCH_AWAIT (only when they are not the http
				// imports): no locals, unreachable, end. They are never called.
				if (!emitHttpImport) {
					code.addFunction(new byte[] { 0x00, 0x00, 0x0b });
					code.addFunction(new byte[] { 0x00, 0x00, 0x0b });
				}
				code.addFunction(finalStartBody.toByteArray())
					.addFunction(printI32Body)
					.addFunction(writeStrBody)
					.addFunction(printValBody)
					.addFunction(printI32NoNlBody)
					.addFunction(printF64Body)
					.addFunction(printF64NoNlBody)
					.addFunction(appendBody)
					.addFunction(readLineBody)
					.addFunction(princValBody)
					.addFunction(lookupBody)
					.addFunction(envLookupBody)
					.addFunction(evalBody)
					.addFunction(applyBody)
					.addFunction(storeBody)
					.addFunction(internBody)
					.addFunction(readExprBody)
					.addFunction(readListBody)
					.addFunction(readBody)
					.addFunction(loadBody)
					.addFunction(WasmRatioRuntimeBuilder.buildRatNewBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatNumBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatDenBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_ADD, Instruction.F64_ADD))
					.addFunction(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_SUB, Instruction.F64_SUB))
					.addFunction(WasmRatioRuntimeBuilder.buildRatBinaryBody(Instruction.I32_MUL, Instruction.F64_MUL))
					.addFunction(WasmRatioRuntimeBuilder.buildRatDivBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatCmpBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatTruncBody())
					.addFunction(WasmRatioRuntimeBuilder.buildRatFloorBody(false))
					.addFunction(WasmRatioRuntimeBuilder.buildRatFloorBody(true))
					.addFunction(WasmRatioRuntimeBuilder.buildRatRoundBody())
					.addFunction(WasmRuntimeBuilder.buildToStringBody(FUNC_PRINC_VAL, 1))
					.addFunction(WasmRuntimeBuilder.buildToStringBody(FUNC_PRINT_VAL, 1))
					.addFunction(WasmRuntimeBuilder.buildToStringBody(FUNC_PRINC_VAL, 2))
					.addFunction(WasmStringRuntimeBuilder.buildCaseConvertBody(true))
					.addFunction(WasmStringRuntimeBuilder.buildCaseConvertBody(false))
					.addFunction(WasmStringRuntimeBuilder.buildCapitalizeBody())
					.addFunction(WasmStringRuntimeBuilder.buildSubseqBody())
					.addFunction(WasmStringRuntimeBuilder.buildStringEqBody(false, stringTable))
					.addFunction(WasmStringRuntimeBuilder.buildStringEqBody(true, stringTable))
					.addFunction(WasmStringRuntimeBuilder.buildTrimBody())
					.addFunction(WasmIoRuntimeBuilder.buildOpenBody())
					.addFunction(WasmIoRuntimeBuilder.buildCloseBody(stringTable))
					.addFunction(WasmIoRuntimeBuilder.buildWriteLineBody(stringTable))
					.addFunction(WasmRuntimeBuilder.buildEqualBody())
					.addFunction(WasmGetenvRuntimeBuilder.build());
				// Dispatch function bodies
				for (byte[] body : dispatchBodies) {
					code.addFunction(body);
				}
				// fetch runtime helper bodies (FUNC_FETCH_STR..FUNC_FETCH_DESER_HDRS)
				code.addFunction(WasmFetchRuntimeBuilder.buildStr());
				code.addFunction(WasmFetchRuntimeBuilder.buildPlistGet());
				code.addFunction(WasmFetchRuntimeBuilder.buildSerHeaders());
				code.addFunction(WasmFetchRuntimeBuilder.buildDeserHeaders());
				// Hash-table runtime helper bodies (FUNC_HASH, FUNC_HASH_RESIZE)
				code.addFunction(WasmRuntimeBuilder.buildHashBody());
				code.addFunction(WasmRuntimeBuilder.buildHashResizeBody());
				// Modulo / remainder runtime helper bodies (FUNC_RAT_REM, FUNC_RAT_MOD)
				code.addFunction(WasmRatioRuntimeBuilder.buildRatRemBody(false));
				code.addFunction(WasmRatioRuntimeBuilder.buildRatRemBody(true));
				// gensym runtime helper body (FUNC_GENSYM)
				code.addFunction(WasmGensymRuntimeBuilder.build());
				// promise-await runtime helper body (FUNC_PROMISE_AWAIT)
				code.addFunction(WasmPromiseRuntimeBuilder.buildAwait(stringTable));
				// binary stream runtime helper bodies (FUNC_READ_BYTE, FUNC_WRITE_BYTE)
				code.addFunction(WasmIoRuntimeBuilder.buildReadByteBody());
				code.addFunction(WasmIoRuntimeBuilder.buildWriteByteBody());
				// string-stream runtime helper bodies (FUNC_WRITE_STREAM_STR,
				// FUNC_MAKE_STR_OSTREAM, FUNC_MAKE_STR_ISTREAM, FUNC_STR_STREAM_CONTENTS)
				code.addFunction(WasmStringStreamRuntimeBuilder.buildWriteStreamStrBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildMakeOutputStreamBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildMakeInputStreamBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildContentsBody());
				// User defun function bodies
				for (byte[] body : userFunctionBodies) {
					code.addFunction(body);
				}
				// Lambda function bodies
				for (byte[] body : lambdaFunctionBodies) {
					code.addFunction(body);
				}
				// Memory-export helper bodies (must precede the wrapper bodies to match
				// the
				// function-section order).
				if (memoryHelpers) {
					code.addFunction(WasmExportRuntimeBuilder.buildAllocBody());
					code.addFunction(WasmExportRuntimeBuilder.buildStrFromMemBody());
				}
				// Export wrapper bodies (host-callable), one per (rontolisp:wasm-export
				// ...).
				for (byte[] body : exportBodies) {
					code.addFunction(body);
				}
			})
			// Data section
			.writeDataSection(data -> {
				// The heap pointer and the runtime intern table's base are seeded at
				// instantiation (values computed above from the final static-data size).
				// Instantiation-time seeding also covers hosts that call exported
				// functions without running _start.
				data.addActiveData(0, HEAP_PTR_ADDR, littleEndian32(heapBase));
				data.addActiveData(0, RT_INTERN_BASE_ADDR, littleEndian32(rtInternBase));
				if (stringData.length > 0) {
					data.addActiveData(0, DATA_BASE_OFFSET, stringData);
				}
			});
		byte[] coreModule = out.toByteArray();
		// Resolve (rontolisp:wasm-import ...) directives: prepend the host imports and
		// renumber every function reference (incl. the placeholder call indices). Must
		// run before the tree shaker -- the module is not valid until then.
		if (!hostImports.isEmpty()) {
			coreModule = am.ik.wasm.WasmImportInjector.inject(coreModule, hostImports,
					WasmImportCompiler.PLACEHOLDER_FUNC_BASE);
		}
		if (this.component) {
			// The WASI 0.3 adapter binds the core's imports/exports by their fixed
			// layout,
			// so tree-shaking the core is unsafe here; leave the component path
			// untouched.
			if (this.serve) {
				// rontolisp:http-handler: wrap the core (which exports %http-dispatch)
				// into
				// a wasi:http/incoming-handler component (wasmtime serve). When the
				// program also uses fetch, the serve+fetch variant wires the outgoing
				// http machinery into the preview1 bridge (wasmtime serve -S http=y).
				return WasmComponentBuilder.buildServe(coreModule, emitHttpImport);
			}
			return WasmComponentBuilder.build(coreModule, emitHttpImport, emitSockImport);
		}
		return this.optimize ? am.ik.wasm.WasmTreeShaker.shake(coreModule) : coreModule;
	}

	static boolean hasDoubleLiteral(List<LispVal> args) {
		for (int i = 1; i < args.size(); i++) {
			if (containsDouble(args.get(i))) {
				return true;
			}
		}
		return false;
	}

	static boolean containsDouble(LispVal val) {
		if (val instanceof LispDouble) {
			return true;
		}
		if (val instanceof LispCons cons) {
			for (LispVal element : cons.toList()) {
				if (containsDouble(element)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean programUsesEval(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEval(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesEval(LispVal val) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && LispNames.EVAL.equals(sym.name())) {
			return true;
		}
		return usesEval(cons.car()) || usesEval(cons.cdr());
	}

	private static boolean programUsesSymbol(List<LispVal> program, String name) {
		for (LispVal expr : program) {
			if (usesSymbol(expr, name)) {
				return true;
			}
		}
		return false;
	}

	// True when the program references any hash-table operator (including (setf (gethash
	// ...)) which contains gethash). Gates the first-class hash wrappers.
	private static boolean programUsesAnyHashOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_HASH_TABLE) || programUsesSymbol(program, LispNames.GETHASH)
				|| programUsesSymbol(program, LispNames.REMHASH) || programUsesSymbol(program, LispNames.CLRHASH)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_COUNT)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_P) || programUsesSymbol(program, LispNames.MAPHASH);
	}

	private static boolean usesSymbol(LispVal val, String name) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym && name.equals(sym.name())) {
			return true;
		}
		return usesSymbol(cons.car(), name) || usesSymbol(cons.cdr(), name);
	}

	/**
	 * Builds the compile-time intern table: one {@code (offset i32, length i32)} pair per
	 * interned string. The runtime {@code _intern} scans it to map a freshly-parsed
	 * symbol's bytes to the canonical string-table offset that the eval runtime compares
	 * against.
	 * @param entries the interned string entries
	 * @return the little-endian blob
	 */
	private static byte[] buildInternBlob(java.util.Collection<StringTable.StringEntry> entries) {
		ByteArrayOutputStream blob = new ByteArrayOutputStream();
		for (StringTable.StringEntry e : entries) {
			writeLittleEndian32(blob, e.offset());
			writeLittleEndian32(blob, e.length());
		}
		return blob.toByteArray();
	}

	private static void writeLittleEndian32(ByteArrayOutputStream target, int value) {
		target.write(value & 0xFF);
		target.write((value >>> 8) & 0xFF);
		target.write((value >>> 16) & 0xFF);
		target.write((value >>> 24) & 0xFF);
	}

	private static byte[] littleEndian32(int value) {
		return new byte[] { (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24) };
	}

	private static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LambdaLists.NativeForm nf = LambdaLists.toNative(lambdaParts.get(1),
				lambdaParts.subList(2, lambdaParts.size()));
		return new DefunDecl(funcName, nf.paramNames(), nf.variadic(), nf.body());
	}

	/**
	 * A parsed defun. {@code paramNames} are the physical parameters (when
	 * {@code variadic}, the last one is the {@code &rest} parameter receiving the
	 * remaining arguments as a cons list).
	 */
	record DefunDecl(String name, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs) {
	}

	/**
	 * A planned host-callable export wrapper: the parsed directive plus the resolved
	 * target function index and the wrapper's own type and function indices.
	 *
	 * @param decl the parsed rontolisp:wasm-export directive
	 * @param targetFuncIndex the WASM function index of the exported defun
	 * @param typeIndex the wrapper's function type index
	 * @param funcIndex the wrapper's own function index
	 */
	record ExportPlan(WasmExportCompiler.Decl decl, int targetFuncIndex, int typeIndex, int funcIndex) {
	}

	/**
	 * Registry entry for a compiled function. {@code paramCount} is the physical WASM
	 * parameter count (excluding the closure env); when {@code variadic}, the last
	 * parameter is the rest list and the callable minimum is {@code paramCount - 1}
	 * arguments.
	 */
	record WasmFunctionInfo(String name, int paramCount, boolean variadic, int funcId, int typeIndex, int funcIndex) {
	}

	record LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs,
			List<String> freeVarNames, int funcIndex) {
	}

	static final class Ctx {

		final WasmWriter writer;

		final ByteArrayOutputStream bodyStream;

		final StringTable stringTable;

		Map<String, Integer> locals = new HashMap<>();

		Map<String, WasmFunctionInfo> functions;

		Map<String, Integer> captures = Map.of();

		Set<String> boxedVars = Set.of();

		int closureEnvSlot = -1;

		List<LambdaInfo> lambdaDecls;

		Set<Integer> indirectCallArities;

		int[] nextFuncId;

		int nextLocal = 0;

		boolean dynamic = false;

		boolean component = false;

		/**
		 * True for the single context that compiles top-level forms (the {@code _start}
		 * body), false for defun/lambda bodies. When {@link #usesEval} is also set, a
		 * top-level global variable binding is mirrored into the eval runtime's global
		 * environment ({@code GLOBAL_ENV}) so an eval'd expression can resolve it.
		 */
		boolean topLevel = false;

		/** True when the program uses the embedded {@code eval} runtime. */
		boolean usesEval = false;

		Set<String> userDefunNames = Set.of();

		/**
		 * {@code defstruct} accessor names to their 1-based slot position, collected by
		 * the pre-pass in {@link WasmLispCompiler#compile}; {@code setf} expansion treats
		 * these as places. Shared across every context.
		 */
		Map<String, Integer> structAccessors = Map.of();

		/**
		 * Names of top-level global variables; each has a wasm global in
		 * {@link #globalIndices}.
		 */
		Set<String> globals = Set.of();

		/**
		 * Maps a top-level global variable name to its module-level wasm global index.
		 */
		Map<String, Integer> globalIndices = Map.of();

		/**
		 * Top-level globals already initialized by a defvar/defparameter in this
		 * compilation, for defvar's compile-time idempotence. Only the top-level context
		 * mutates it.
		 */
		final Set<String> definedGlobals = new HashSet<>();

		/**
		 * The number of currently-open WASM control structures (block/loop/if) that
		 * lexically enclose the form being compiled. Tracked by the {@code if}, {@code
		 * while} and {@code %block} compilers so that {@code return} can compute the
		 * relative {@code br} depth to the nearest enclosing block.
		 */
		int wasmCtrlDepth = 0;

		/**
		 * Stack of {@code wasmCtrlDepth} values, one per active {@code %block}. The top
		 * is the depth at which the innermost block sits; {@code return} branches out
		 * {@code wasmCtrlDepth - marker} levels to reach it.
		 */
		final Deque<Integer> blockMarkers = new ArrayDeque<>();

		private Ctx(Builder builder) {
			this.writer = Objects.requireNonNull(builder.writer);
			this.bodyStream = Objects.requireNonNull(builder.bodyStream);
			this.stringTable = Objects.requireNonNull(builder.stringTable);
			this.functions = builder.functions;
			this.lambdaDecls = builder.lambdaDecls;
			this.indirectCallArities = builder.indirectCallArities;
			this.nextFuncId = builder.nextFuncId;
			this.dynamic = builder.dynamic;
			this.component = builder.component;
			this.userDefunNames = builder.userDefunNames;
			this.structAccessors = builder.structAccessors;
			this.globals = builder.globals;
			this.globalIndices = builder.globalIndices;
		}

		static Builder builder() {
			return new Builder();
		}

		static final class Builder {

			private @Nullable WasmWriter writer;

			private @Nullable ByteArrayOutputStream bodyStream;

			private @Nullable StringTable stringTable;

			private Map<String, WasmFunctionInfo> functions = Map.of();

			private List<LambdaInfo> lambdaDecls = new ArrayList<>();

			private Set<Integer> indirectCallArities = new HashSet<>();

			private int[] nextFuncId = new int[1];

			private boolean dynamic = false;

			private boolean component = false;

			private Set<String> userDefunNames = Set.of();

			private Map<String, Integer> structAccessors = Map.of();

			private Set<String> globals = Set.of();

			private Map<String, Integer> globalIndices = Map.of();

			Builder writer(WasmWriter writer) {
				this.writer = writer;
				return this;
			}

			Builder bodyStream(ByteArrayOutputStream bodyStream) {
				this.bodyStream = bodyStream;
				return this;
			}

			Builder stringTable(StringTable stringTable) {
				this.stringTable = stringTable;
				return this;
			}

			Builder functions(Map<String, WasmFunctionInfo> functions) {
				this.functions = functions;
				return this;
			}

			Builder lambdaDecls(List<LambdaInfo> lambdaDecls) {
				this.lambdaDecls = lambdaDecls;
				return this;
			}

			Builder indirectCallArities(Set<Integer> indirectCallArities) {
				this.indirectCallArities = indirectCallArities;
				return this;
			}

			Builder nextFuncId(int[] nextFuncId) {
				this.nextFuncId = nextFuncId;
				return this;
			}

			Builder dynamic(boolean dynamic) {
				this.dynamic = dynamic;
				return this;
			}

			Builder component(boolean component) {
				this.component = component;
				return this;
			}

			Builder userDefunNames(Set<String> userDefunNames) {
				this.userDefunNames = userDefunNames;
				return this;
			}

			Builder structAccessors(Map<String, Integer> structAccessors) {
				this.structAccessors = structAccessors;
				return this;
			}

			Builder globals(Set<String> globals) {
				this.globals = globals;
				return this;
			}

			Builder globalIndices(Map<String, Integer> globalIndices) {
				this.globalIndices = globalIndices;
				return this;
			}

			Ctx build() {
				return new Ctx(this);
			}

		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			return slot;
		}

		int allocTemp() {
			return this.nextLocal++;
		}

	}

	static final class StringTable {

		private final ByteArrayOutputStream data = new ByteArrayOutputStream();

		private final Map<String, StringEntry> cache = new HashMap<>();

		private int nextOffset;

		final StringEntry nil;

		final StringEntry lparen;

		final StringEntry rparen;

		final StringEntry space;

		final StringEntry dot;

		final StringEntry newline;

		final StringEntry funcStr;

		final StringEntry promiseStr;

		// Vector/array literal printing: the "#(" prefix for rank-1; a rank-n array
		// prints "#", the rank as an integer, then "A(".
		final StringEntry vecPrefix;

		final StringEntry hashPrefix;

		final StringEntry rankAOpen;

		final StringEntry minus;

		final StringEntry period;

		final StringEntry slash;

		// Character printing: the "#\" prefix and the standard names for the non-graphic
		// characters that prin1 spells out (see WasmRuntimeBuilder.emitPrintChar).
		final StringEntry charPrefix;

		final StringEntry charSpace;

		final StringEntry charNewline;

		final StringEntry charTab;

		final StringEntry charReturn;

		final StringEntry charPage;

		final StringEntry charBackspace;

		final StringEntry charNul;

		final StringEntry charRubout;

		StringTable(int baseOffset) {
			this.nextOffset = baseOffset;
			this.nil = addString("nil");
			this.lparen = addString("(");
			this.rparen = addString(")");
			this.space = addString(" ");
			this.dot = addString(" . ");
			this.newline = addString("\n");
			this.funcStr = addString("#<function>");
			this.promiseStr = addString("#<PROMISE>");
			this.vecPrefix = addString("#(");
			this.hashPrefix = addString("#");
			this.rankAOpen = addString("A(");
			this.minus = addString("-");
			this.period = addString(".");
			this.slash = addString("/");
			this.charPrefix = addString("#\\");
			this.charSpace = addString("Space");
			this.charNewline = addString("Newline");
			this.charTab = addString("Tab");
			this.charReturn = addString("Return");
			this.charPage = addString("Page");
			this.charBackspace = addString("Backspace");
			this.charNul = addString("Nul");
			this.charRubout = addString("Rubout");
		}

		StringEntry addString(String s) {
			StringEntry existing = this.cache.get(s);
			if (existing != null) {
				return existing;
			}
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			int offset = this.nextOffset;
			this.data.write(bytes, 0, bytes.length);
			this.nextOffset += bytes.length;
			StringEntry entry = new StringEntry(offset, bytes.length);
			this.cache.put(s, entry);
			return entry;
		}

		/**
		 * Appends a raw byte blob to the data segment (4-byte aligned) and returns its
		 * absolute memory offset. Used to place the eval function-name registry after the
		 * string data within the single active data segment.
		 * @param blob the bytes to append
		 * @return the absolute offset where the blob was placed
		 */
		int appendBlob(byte[] blob) {
			while (this.nextOffset % 4 != 0) {
				this.data.write(0);
				this.nextOffset++;
			}
			int offset = this.nextOffset;
			this.data.write(blob, 0, blob.length);
			this.nextOffset += blob.length;
			return offset;
		}

		byte[] toByteArray() {
			return this.data.toByteArray();
		}

		/**
		 * Returns a snapshot of all interned string entries. Used to build the
		 * compile-time intern table scanned by the runtime {@code _intern} so that
		 * symbols parsed by {@code read} resolve to the canonical offset the eval runtime
		 * compares against.
		 * @return the interned entries
		 */
		java.util.Collection<StringEntry> entries() {
			return new java.util.ArrayList<>(this.cache.values());
		}

		record StringEntry(int offset, int length) {
		}

	}

}
