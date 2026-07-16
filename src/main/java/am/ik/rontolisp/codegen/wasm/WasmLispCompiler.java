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

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LambdaLists;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SpecialVarCollector;
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

	private final boolean simd;

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
		this(dynamic, component, noWasi, optimize, serve, false);
	}

	/**
	 * Creates a new WASM compiler.
	 * @param dynamic see {@link #WasmLispCompiler(boolean)}
	 * @param component see {@link #WasmLispCompiler(boolean, boolean)}
	 * @param noWasi see {@link #WasmLispCompiler(boolean, boolean, boolean)}
	 * @param optimize see {@link #WasmLispCompiler(boolean, boolean, boolean, boolean)}
	 * @param serve see
	 * {@link #WasmLispCompiler(boolean, boolean, boolean, boolean, boolean)}
	 * @param simd when {@code true} (the CLI's {@code --simd}), the vectorizable
	 * {@code vec:} kernels are intercepted at their call sites and routed to emitted v128
	 * runtime helpers ({@link WasmVecSimdRuntimeBuilder}) instead of the scalar
	 * {@code vec.lisp} defuns. This also switches the packed float-array representation:
	 * a {@code TYPE_FARRAY}'s data field then holds a {@code TYPE_VBLOCK} over an
	 * {@code (array (mut v128))} of lane groups instead of a {@code TYPE_F64ARR}/
	 * {@code TYPE_F32ARR}. Both are ordinary GC objects the engine collects; the data
	 * field is {@code (ref null eq)} either way, and the representation is fixed at
	 * compile time, so one module only ever holds one of the two. Without {@code simd}
	 * the output is byte-identical to a build of this compiler that never knew about the
	 * flag.
	 */
	public WasmLispCompiler(boolean dynamic, boolean component, boolean noWasi, boolean optimize, boolean serve,
			boolean simd) {
		this.dynamic = dynamic;
		this.component = component;
		// no-wasi is a Preview 1-only mode; a component has its own (lowered) import
		// story.
		this.noWasi = noWasi && !component;
		this.optimize = optimize;
		this.serve = serve && component;
		this.simd = simd;
	}

	/**
	 * The WIT text describing the component compiled by the last {@link #compile} call
	 * (the CLI's {@code --emit-wit} output), or {@code null} before a component compile.
	 * Semantically identical to {@code wasm-tools component wit} on the emitted bytes;
	 * see {@link WitEmitter}.
	 * @return the WIT text, or {@code null} when not compiling a component
	 */
	public @Nullable String componentWit() {
		return this.componentWit;
	}

	private @Nullable String componentWit;

	/**
	 * The index of the first user defun: the fixed runtime helpers, plus -- under
	 * {@code --simd} only -- the {@link WasmVecSimdRuntimeBuilder} block and the
	 * {@link WasmLinalgSimdRuntimeBuilder} one after it. Every fixed {@code FUNC_*}
	 * constant below {@link #FUNC_USER_BASE} keeps its value in both modes; only what
	 * follows shifts, and only when {@code simd} is set.
	 */
	int userFuncBase() {
		return asyncFuncBase() + (this.asyncMode ? WasmFutureRuntimeBuilder.FUNC_COUNT : 0);
	}

	/**
	 * The index of the first async-runtime function ({@code _future_new}), right after
	 * the {@code --simd} block. The block itself is emitted only in {@code asyncMode}
	 * (the program uses async-defun/async-lambda/await under {@code --component}); every
	 * other module is byte-identical to a build that never knew about it.
	 */
	private int asyncFuncBase() {
		return FUNC_VEC_BASE
				+ (this.simd ? WasmVecSimdRuntimeBuilder.FUNC_COUNT + WasmLinalgSimdRuntimeBuilder.FUNC_COUNT : 0);
	}

	/**
	 * The index of {@code TYPE_FUTURE} ({@code TYPE_ASYNC_FRAME} follows it), appended
	 * after the fixed and {@code --simd} types in {@code asyncMode} only.
	 */
	private int asyncTypeBase() {
		return TYPE_F32ARR + 1 + (this.simd ? SIMD_TYPE_COUNT : 0);
	}

	/**
	 * The index of the first {@code linalg:} kernel: right after the {@code vec:} block.
	 * Only meaningful under {@code --simd}.
	 */
	static int linalgFuncBase() {
		return FUNC_VEC_BASE + WasmVecSimdRuntimeBuilder.FUNC_COUNT;
	}

	/**
	 * The number of type entries this module emits before the export/import wrapper
	 * signatures: the fixed types through {@code TYPE_F32ARR}, plus -- under
	 * {@code --simd} only -- the {@link WasmVecSimdRuntimeBuilder} block's four. The same
	 * conditional-index trick as {@link #userFuncBase()}: without {@code --simd} the
	 * output is byte-identical to a build that never knew about the flag.
	 * @return the index of the first export wrapper type
	 */
	private int fixedTypeCount() {
		return asyncTypeBase() + (this.asyncMode ? ASYNC_TYPE_COUNT : 0);
	}

	/**
	 * Whether this compilation runs the {@code --component} async state machines: the
	 * program uses {@code rontolisp:async-defun}/{@code async-lambda}/{@code await}.
	 * Forces EH mode (the entry's reject path and the rejected-await re-signal throw on
	 * the {@code $lisp-cond} tag), so an async component needs
	 * {@code wasmtime -W exceptions=y}.
	 */
	private boolean asyncMode;

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

	// Function indices 8-11 are reserved for the rontolisp:tcp-* built-ins in BOTH
	// modes so the defined-function indices stay identical across modes. Imports always
	// precede defined functions, so the slots can be imports in one mode only because
	// every earlier slot is an import in that mode too:
	// - sockets component: sock.* imported at 8-11.
	// - plain component / Preview 1 / no-wasi: all four are defined trap stubs.
	// This keeps FUNC_START at 12 uniformly, so all the static FUNC_* constants below
	// are stable. The tcp built-ins raise a compile error in Preview 1 mode, so the
	// stubs are never called. (rontolisp:fetch needs no slots here: it is the http.lisp
	// library over canon-lowered wasi:http user imports.)
	static final int FUNC_TCP_CONNECT = IMPORT_FUNC_COUNT; // 8

	static final int FUNC_TCP_LISTEN = FUNC_TCP_CONNECT + 1; // 9

	static final int FUNC_TCP_ACCEPT = FUNC_TCP_LISTEN + 1; // 10

	static final int FUNC_TCP_LOCAL_PORT = FUNC_TCP_ACCEPT + 1; // 11

	static final int FUNC_START = FUNC_TCP_LOCAL_PORT + 1; // 12

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

	// The comparison as a bitmask (1 = lt, 2 = eq, 4 = gt, 0 = unordered): the numeric
	// comparison operators AND the mask they accept, so a NaN operand fails every one
	// of = < > <= >= -- which _rat_cmp's -1/0/1 signum cannot express (todo-108).
	static final int FUNC_RAT_CMP_BITS = FUNC_RAT_CMP + 1;

	static final int FUNC_RAT_TRUNC = FUNC_RAT_CMP_BITS + 1;

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

	// Plist runtime helper (always emitted, just after the dispatch functions): look up
	// a plist key by interned offset. The component import compiler uses it to lower a
	// record parameter written as a keyword plist.
	static final int FUNC_PLIST_GET = FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1;

	// Structural hash (agrees with _equal): walks conses and folds i31 ints / interned
	// string offsets / char codes / float bits / ratio components into an i32. Always
	// present; only the hash-table compiler references it. Equal keys hash equal.
	static final int FUNC_HASH = FUNC_PLIST_GET + 1;

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

	// Symbol runtime API helpers (WasmSymbolApiRuntimeBuilder), all unary
	// ((ref null eq)) -> (ref null eq): _make_symbol copies "#:" + the string content to
	// a fresh heap symbol, _intern_sym canonicalizes the content range through _intern so
	// the result's offset matches literals in env lookups, _boundp/_symbol_value probe
	// the eval global env mirror (GLOBAL_ENV), _fboundp probes GLOBAL_FENV then the
	// compiled-function registry (_lookup). Appended before FUNC_USER_BASE like the
	// mod/rem helpers, so no import/FUNC_START index shifts and the component blobs are
	// unaffected. Always present; boundp/fboundp/symbol-value force usesEval so the
	// _env_lookup/_lookup bodies they call are real, and intern forces the real _intern
	// body.
	static final int FUNC_MAKE_SYMBOL = FUNC_STR_STREAM_CONTENTS + 1;

	static final int FUNC_INTERN_SYM = FUNC_MAKE_SYMBOL + 1;

	static final int FUNC_BOUNDP = FUNC_INTERN_SYM + 1;

	static final int FUNC_SYMBOL_VALUE = FUNC_BOUNDP + 1;

	static final int FUNC_FBOUNDP = FUNC_SYMBOL_VALUE + 1;

	// read-char runtime helper (WasmIoRuntimeBuilder.buildReadCharBody): one byte from
	// stdin, a WASI fd or a string input stream, boxed as a character struct. Appended
	// before FUNC_USER_BASE like the mod/rem helpers, so no import/FUNC_START index
	// shifts and the component blobs are unaffected.
	static final int FUNC_READ_CHAR = FUNC_FBOUNDP + 1;

	// _str_build (off, len) -> (ref null eq): allocates a $str_bytes GC array of
	// length len, copies linear[off..off+len) into it, and returns a TYPE_STRING
	// {id=off, len, data=arr}. Every compiled string/symbol build calls this (it has
	// the same (i32,i32)->ref stack shape the old 2-field struct.new had), so string
	// bytes live on the GC heap. Fixed index just before FUNC_USER_BASE; the component
	// embeds the core opaquely and binds it by export name, so shifting the user/lambda
	// indices below is safe.
	static final int FUNC_STR_BUILD = FUNC_READ_CHAR + 1;

	// _str_fresh (off, len) -> (ref null eq): like _str_build, but stamps a fresh id
	// from the monotonic STRING_ID_CTR counter instead of id=off. Every RUNTIME string
	// build (concatenate/subseq/case/trim, read, gensym/make-symbol, getenv, the
	// capture path, string-stream contents, the host :string boundary) calls this. Its
	// scratch offset is reused across builds (HEAP_PTR is a stack pointer now), so a
	// counter id -- not the reused offset -- is what keeps distinct runtime strings and
	// uninterned symbols eq-distinct. Interned names (literals, _intern'd symbols, t)
	// keep calling _str_build (id=stable offset). Same (i32,i32)->ref shape.
	static final int FUNC_STR_FRESH = FUNC_STR_BUILD + 1;

	// _str_to_mem (str, ptr) -> i32 (TYPE_STR_TO_MEM): copies a string's GC byte array
	// into linear[ptr..) and returns its length. See the type comment.
	static final int FUNC_STR_TO_MEM = FUNC_STR_FRESH + 1;

	// _write_str_gc (str, from, to) -> () (TYPE_WRITE_STR_GC): prints bytes [from, to)
	// of a string value straight from its GC array. See the type comment.
	static final int FUNC_WRITE_STR_GC = FUNC_STR_TO_MEM + 1;

	// The vec: SIMD block (_v_new/_v_get/_v_set + the twelve v128 kernels), emitted ONLY
	// under --simd. Fixed indices relative to FUNC_WRITE_STR_GC, so every constant above
	// keeps
	// its value; the user defuns below shift by WasmVecSimdRuntimeBuilder.FUNC_COUNT when
	// the block is present. Read the base through userFuncBase(), never FUNC_USER_BASE.
	static final int FUNC_VEC_BASE = FUNC_WRITE_STR_GC + 1;

	// User defuns start after the dispatch functions, the plist helper, the two
	// hash-table runtime helpers, the two mod/rem helpers, the gensym helper, the
	// promise-await helper, the two binary stream helpers, the four string-stream
	// helpers, the five symbol-API helpers, the read-char helper, and the four string
	// GC helpers (_str_build, _str_fresh, _str_to_mem, _write_str_gc) -- plus, under
	// --simd, the vec: SIMD block. Use userFuncBase(), which adds that offset.
	static final int FUNC_USER_BASE = FUNC_WRITE_STR_GC + 1;

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

	// Character struct {i32 code}: the runtime representation of a character, distinct
	// from
	// an i31 integer so characterp and the accessors can dispatch on it via ref.test.
	static final int TYPE_CHAR = TYPE_CLOCK_TIME_GET + 1; // 31

	// Hash-table bucket array: array (mut (ref null eq)). Each slot holds a bucket alist
	// (a cons chain of (key . value) entries) or null. Implicitly a subtype of eq, so a
	// bucket array can be stored in a cons/cell field and compared with ref.eq.
	static final int TYPE_HASH_BUCKETS = TYPE_CHAR + 1; // 32

	// Promise struct {mut i32 kind, mut (ref null eq) base, mut (ref null eq) fn}: the
	// runtime representation of a promise, distinct from every other value so promisep
	// and _promise_await dispatch on it via ref.test. kind 1 = a rontolisp:then chain
	// (base = the chained value-or-promise, fn = the callback), kind 2 = settled (base
	// = the memoized result; _promise_await rewrites a promise in place after resolving
	// it, so a chain callback is consumed exactly once however often the promise is
	// awaited).
	static final int TYPE_PROMISE = TYPE_HASH_BUCKETS + 1; // 33

	// String byte array: array (mut i8) -- the GC-managed byte storage for a
	// TYPE_STRING's `data` field (field 2). A bare array comptype (implicitly sub
	// final), so it is a subtype of eq and stores in the (ref null eq) data field;
	// readers ref.cast it before array.get_u / array.len. Appended right after
	// TYPE_PROMISE so the export/import wrapper type indices shift by one (see
	// wrapperTypeIndex / importTypeIndex, both TYPE_WRITE_STR_GC + 1 based).
	static final int TYPE_STR_BYTES = TYPE_PROMISE + 1; // 34

	// _str_to_mem ((ref null eq) str, i32 ptr) -> i32: copies a string's $str_bytes
	// array (with its surrounding quotes) into linear[ptr..) and returns the byte
	// count. The array->linear bridge for the paths that still need a linear pointer
	// (WASI iovecs for write-line/open, the reader input scratch, the host :string
	// boundary, runtime intern). Appended after TYPE_STR_BYTES.
	static final int TYPE_STR_TO_MEM = TYPE_STR_BYTES + 1; // 35

	// _write_str_gc ((ref null eq) str, i32 from, i32 to) -> (): writes bytes
	// [from, to) of a string's $str_bytes array to the current print sink -- appended
	// directly to the capture buffer when capture mode is on (no linear staging, so it
	// never aliases the capture buffer), else staged into heap scratch and handed to
	// _write_str for stdout. The print path for string values now that their bytes live
	// on the GC heap. Appended after TYPE_STR_TO_MEM.
	static final int TYPE_WRITE_STR_GC = TYPE_STR_TO_MEM + 1; // 36

	// Packed float-array data storage: array (mut f64). A bare array comptype (implicitly
	// sub final), so a subtype of eq -- it stores in TYPE_FARRAY's (ref null eq) data
	// field and readers ref.cast it before array.get / array.len. The unboxed f64 storage
	// of a packed float array. Appended after TYPE_WRITE_STR_GC.
	static final int TYPE_F64ARR = TYPE_WRITE_STR_GC + 1; // 37

	// Packed float array: struct {(ref null eq) dims, (ref null eq) data} -- a rank-n
	// packed float array as a distinct first-class type (disjoint from TYPE_CELL, so
	// arrayp/print/length discriminate it with a plain ref.test). `dims` is a
	// TYPE_HASH_BUCKETS of i31 dimension sizes (as for a general array), `data` a
	// TYPE_F64ARR (double-float) or TYPE_F32ARR (single-float) of the row-major elements
	// -- the SAME struct serves both widths, distinguished by ref.test-ing the data field
	// (see WasmArrayCompiler; mirrors the JVM `double[]`/`float[]` inline dispatch). No
	// fill pointer / adjustable / displacement (a packed array is a pure compute buffer).
	// Appended after TYPE_F64ARR; the export/import wrapper type indices below shift past
	// it and TYPE_F32ARR.
	static final int TYPE_FARRAY = TYPE_F64ARR + 1; // 38

	// Packed single-float array data storage: array (mut f32). A bare array comptype
	// (implicitly sub final), a subtype of eq -- it stores in TYPE_FARRAY's (ref null eq)
	// data field alongside TYPE_F64ARR and readers pick the width with ref.test $f32arr
	// before array.get / array.set. The unboxed f32 storage of a single-float packed
	// array (#f(...) / make-array :element-type 'single-float). Reads widen f32->f64,
	// writes narrow f64->f32; scalars stay f64 (no single-float scalar type). Appended
	// after TYPE_FARRAY (last type of the DEFAULT module).
	static final int TYPE_F32ARR = TYPE_FARRAY + 1; // 39

	// --- the --simd block (see WasmVecSimdRuntimeBuilder) -------------------------
	//
	// Four types, emitted ONLY under --simd, appended after TYPE_F32ARR and before the
	// export/import wrapper signatures (which shift past them via fixedTypeCount()).
	// Declaring an (array (mut v128)) at all requires the SIMD proposal, so the default
	// module keeps validating on a runtime that has it turned off -- which is exactly the
	// dead-flag guard WasmLispCompilerIntegrationTest runs.

	// array (mut v128) -- the lane-group storage of a packed float array under --simd.
	// A bare array comptype (implicitly sub final), so a subtype of eq. array.new_default
	// zeroes every lane, which is what lets the kernels drop their scalar tails.
	static final int TYPE_V128ARR = TYPE_F32ARR + 1; // 40

	// struct {i32 count, i32 kind, (ref null eq) groups} -- the --simd replacement for
	// the
	// TYPE_F64ARR/TYPE_F32ARR data of a TYPE_FARRAY, stored in the SAME (ref null eq)
	// data
	// field. `count` is the logical element count, `kind` the width tag (0 =
	// double-float,
	// 1 = single-float) that replaces `ref.test $f32arr` now both widths share one
	// TYPE_V128ARR, and `groups` holds ceil(count / lanes) + 1 groups -- the trailing one
	// a
	// zero sentinel so matvec's shuffle window can always read one group past its last.
	static final int TYPE_VBLOCK = TYPE_F32ARR + 2; // 41

	// _v_get ((ref null eq) vblock, i32 index) -> f64
	static final int TYPE_V_GET = TYPE_F32ARR + 3; // 42

	// _v_set ((ref null eq) vblock, i32 index, f64 value) -> f64 (the value AS STORED)
	static final int TYPE_V_SET = TYPE_F32ARR + 4; // 43

	// How many type entries the --simd block appends.
	static final int SIMD_TYPE_COUNT = 4;

	// --- the async block (asyncMode only; see WasmAsyncEmit) ----------------------
	//
	// Two struct types in ONE rec group (they are structurally identical, and rec-group
	// membership is what keeps them distinct under wasm-GC's structural type
	// canonicalization): TYPE_FUTURE {mut i32 state, mut value, mut waiters, mut source}
	// at asyncTypeBase(), TYPE_ASYNC_FRAME {mut i32 state, mut spill, mut future,
	// mut env} right after it. Appended before the export/import wrapper signatures
	// (which shift past them via fixedTypeCount()).
	static final int ASYNC_TYPE_COUNT = 3;

	// The exception tag index of the one Lisp condition tag ($lisp-cond), emitted only
	// in EH mode (the program uses handler-case/ignore-errors/unwind-protect). Its
	// payload is a cons (condition-instance . message-string) over TYPE_CONS, and its
	// function type reuses TYPE_PRINT_VAL (((ref null eq)) -> ()), so no type-section
	// entry is added. Tags have their own index space; this is always the only tag.
	static final int TAG_LISP_COND = 0;

	// The empty block type (0x40) for block/try_table instructions without a result.
	static final int BLOCKTYPE_EMPTY = 0x40;

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

	// Monotonic counter cell handing out the id (field 0) of every RUNTIME-built string
	// and uninterned symbol via _str_fresh. Seeded at instantiation to heapBase, so
	// runtime ids are always >= heapBase > every interned/rt-intern offset (identity
	// with literals/interned symbols is preserved) and never repeat even though the
	// bump scratch offset they were assembled at is reused (HEAP_PTR is a stack pointer
	// -- see FUNC_STR_FRESH). This is what retires the linear string heap leak.
	static final int STRING_ID_CTR_ADDR = 156;

	// Per-call snapshot cells for the --component canonical string ABI (todo 92 Tier 2):
	// the appended cabi_realloc saves HEAP_PTR + the runtime intern count on its first
	// call of an export invocation (ACTIVE flag), and the cabi_post_* post-return
	// restores HEAP_PTR when the intern count is unchanged (interned tokens are permanent
	// heap copies that must survive -- same guard as the serve adapter's per-request
	// reset). Zero-initialized memory means "no mark active"; no data segment needed.
	static final int CABI_MARK_ACTIVE_ADDR = 160;

	static final int CABI_MARK_HEAP_ADDR = 164;

	static final int CABI_MARK_INTERN_ADDR = 168;

	// High-water mark of the interned-symbol byte pool: the HEAP_PTR value just after
	// _intern's last PERMANENT advance. Only written when the host arena API is emitted
	// (a memory-exporting non-component module), so every other module's _intern body is
	// byte-identical to before. Zero-initialized memory means "nothing
	// permanent above heapBase yet", which is exactly what __ronto_alloc_reset's
	// max(mark, high-water) pop wants.
	static final int RT_INTERN_HEAP_ADDR = 172;

	// The serve memory module's (mem-http-client.wat) canonical-ABI bump-pointer CELL,
	// and
	// the allocation base just above its 8 bytes. cabi_realloc keeps its pointer in this
	// linear-memory cell rather than a wasm global so the core -- which shares this
	// memory
	// -- can reset it: cabi_realloc is where the host writes an incoming request's result
	// buffers (path / headers / body), and it only grows, so an instance-reusing host
	// (jco /
	// wasmCloud) that calls handle many times on one instance would grow linear memory by
	// ~one request per call. WasmExportCompiler emits `mem[CELL] = BASE` at the top of
	// the
	// serve `handle` wrapper (the core HEAP_PTR needs no reset -- the %component-import
	// wrapper's pop-back already keeps it at the intern high-water). A memory cell needs
	// no
	// global import (which would shift the core's whole global index space) and no
	// adapter.
	// Serve only: a non-serve component's memory module is mem.wasm, unchanged.
	static final int CABI_HP_CELL_ADDR = 0x10000; // 65536, start of page 1 (serve
													// scratch)

	static final int CABI_HP_BASE = 0x10008; // 65544, just above the 8-byte cell

	static final int ENV_PTRS_ADDR = 0x30000; // 196608, page 3

	static final int ENV_BUF_ADDR = 0x34000; // 212992, page 3 + 16 KiB

	// Socket scratch cell in page 4 (0x40000), between the rontolisp data/heap (pages
	// 0-3) and the adapter scratch (page 5+). sock.tcp-connect / tcp-listen /
	// tcp-accept write the preview1-style socket fd (>= 200, serviced by the sockets
	// adapter's fd_read/fd_write/fd_close branches) through this out-pointer; the
	// compiler boxes it as an i31 integer -- the stream handle. Component mode only.
	// (The address is historical: the cells below it held the deleted 0.2 fetch
	// adapter's scratch.)
	static final int SOCK_FD_ADDR = 0x40018;

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
		// rontolisp:await placement is checked on the raw forms; then every
		// async-defun/async-lambda lowers to an ordinary defun/lambda over the
		// %async-run primitive. On this backend %async-run runs the body immediately
		// and wraps the result in a settled future: Preview 1 has no asynchronous host
		// I/O (everything settles), and under --component the body's awaits block the
		// stackful task exactly like the rest of the module's I/O.
		try {
			am.ik.rontolisp.LispAsync.checkTopLevel(program);
		}
		catch (IllegalArgumentException ex) {
			throw new UnsupportedOperationException(ex.getMessage());
		}
		// --component compiles the raw async forms as entry+resume state machines
		// (WasmAsyncEmit): a top-level async-defun becomes a plain defun whose name is
		// remembered, and lowerProgram is skipped. Everything else (Preview 1, and a
		// component with no async surface -- where lowering is a no-op) keeps the
		// degenerate %async-run lowering.
		// %subtask-future counts too: a wit-import binding of an async func member
		// returns a first-class future through it, which needs the async runtime even
		// when the program itself never awaits (the caller may hand the future around).
		boolean usesAsync = programUsesSymbol(program, LispNames.ASYNC_DEFUN_QUALIFIED)
				|| programUsesSymbol(program, LispNames.ASYNC_LAMBDA_QUALIFIED)
				|| programUsesSymbol(program, LispNames.AWAIT_QUALIFIED)
				|| programUsesSymbol(program, LispNames.SUBTASK_FUTURE_INTERNAL_QUALIFIED);
		this.asyncMode = this.component && usesAsync;
		Set<String> asyncDefunNames = new HashSet<>();
		if (this.asyncMode) {
			program = rewriteTopLevelAsyncDefuns(program, asyncDefunNames);
		}
		else {
			program = am.ik.rontolisp.LispAsync.lowerProgram(program);
		}
		// Splice top-level defstructs/defclasses/defgenerics/defmethods into their
		// generated defuns before lambda-list desugaring (the generated constructors
		// use &key) so Pass 1 collects them as ordinary functions; the registries make
		// accessors setf-able places and resolve make-instance/slot-value/dispatch.
		Map<String, Integer> structAccessors = new HashMap<>();
		ClosRegistry closRegistry = new ClosRegistry();
		program = LispMacroExpander.expandTopLevelDefinitions(program, structAccessors, closRegistry);
		// Desugar extended lambda lists (&optional/&key/&aux) into the native
		// "required + &rest" shape so the passes below only see that shape.
		program = LambdaLists.desugarProgram(program);
		// Create the %mv-spill global (a top-level setq) when the program uses a
		// multiple-value operator: the expansions read/write it across functions.
		program = LispMacroExpander.injectMvSpillGlobal(program);
		// Bundle the surplus parameters of too-wide fixed-arity defuns into a list
		// (and rewrite their direct call sites) so real-library signatures compile
		// despite the MAX_CALLABLE_ARITY type limit.
		program = WasmArityBundler.bundle(program);
		// Detect whether the program uses (eval ...). When it does, a runtime
		// interpreter (_eval) and a function-name registry are emitted, and dispatch
		// functions are generated for every registered arity so _eval can apply them.
		// The reader runtime is emitted for read/load; load also evaluates each form, so
		// it pulls in the eval runtime as well.
		boolean usesLoad = programUsesSymbol(program, LispNames.LOAD);
		boolean usesRead = programUsesSymbol(program, LispNames.READ)
				|| programUsesSymbol(program, LispNames.READ_FROM_STRING) || usesLoad;
		// The apply built-in reuses the runtime _apply, so it forces the eval runtime.
		// boundp/symbol-value/fboundp probe the eval global envs through
		// _env_lookup/_lookup, so they force it too; intern needs the real _intern body
		// (canonical offsets) which lives in the reader runtime.
		// multiple-value-call forces apply too: its expansion spreads a spill
		// producer's dynamic value count with (apply fn (append ...)).
		boolean usesEval = programUsesEval(program) || usesLoad || this.dynamic
				|| programUsesSymbol(program, LispNames.APPLY) || programUsesSymbol(program, LispNames.BOUNDP)
				|| programUsesSymbol(program, LispNames.SYMBOL_VALUE) || programUsesSymbol(program, LispNames.FBOUNDP)
				|| programUsesSymbol(program, LispNames.MULTIPLE_VALUE_CALL);
		// rontolisp:fetch is component-only: it is the spliced http.lisp defun over the
		// canon-lowered wasi:http user import. In Preview 1 mode it raises a compile
		// error (WasmFetchCompiler). await/then/promisep are generic promise operations
		// that compile in every mode.
		// EH mode: the program uses a catching/cleanup form, so the module carries the
		// $lisp-cond exception tag, %error/%error-cond throw it (instead of a bare
		// unreachable) and every entry function converts an uncaught throw back into a
		// trap with a catch_all wrapper. A program without these forms is byte-identical
		// to a build that never knew about EH (the usesStringOp gating precedent). The
		// with-* macros and the usocket guard/with-* family count as triggers too (the
		// todo-129 step-7 retrofit): their expansions ride unwind-protect /
		// handler-case on WASM now, so a program using them needs the EH machinery --
		// and the `wasmtime -W exceptions=y` run flag.
		// asyncMode implies EH mode: the async entry's reject path and the
		// rejected-await re-signal throw on the $lisp-cond tag.
		boolean ehMode = programUsesEhForm(program) || this.asyncMode;
		boolean usesFetch = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH));
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
		// fetch and tcp sockets need different component blob variants (wasi:http vs
		// wasi:sockets); a combined variant does not exist yet.
		if (this.component && usesFetch && usesTcp) {
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
		// wrappers below. They produce no code in the _start body. In component mode the
		// scalar wrappers are additionally lifted into component-model exports
		// (WasmComponentBuilder).
		List<WasmExportCompiler.Decl> exportDecls = new ArrayList<>();
		// (rontolisp:wasm-import ...) directives: each becomes a Lisp-callable wrapper
		// registered like a top-level defun (Preview 1 only; rejected in component
		// mode), calling the imported host function through a placeholder index that
		// the WasmImportInjector post-pass resolves.
		List<WasmImportCompiler.Decl> importDecls = new ArrayList<>();
		// (rontolisp::%component-import ...) forms (the --component lowering of
		// rontolisp:wit-import): each bound WIT function becomes a synthetic defun whose
		// body marshals through the canonical ABI, and the interface becomes a
		// component-level import wired by WasmComponentBuilder (canon lower).
		List<WasmComponentImportCompiler.Import> componentImports = new ArrayList<>();
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
			else if (WasmComponentImportCompiler.isComponentImportForm(expr)) {
				componentImports.add(WasmComponentImportCompiler.parse((LispCons) expr));
			}
			else {
				topLevelExprs.add(expr);
			}
		}
		// Two independently spliced libraries can bind the SAME interface (each in its
		// own %-package). Merge them into one import per
		// interface so the component-level wiring sees a single instance -- the two
		// packages'
		// wrappers stay distinct defuns (their duplicate core imports of the same host
		// function are legal and both resolve to the one lowered instance export). A
		// program
		// with no such overlap is unchanged (merge is a no-op), so every existing
		// component
		// stays byte-identical.
		componentImports = WasmComponentImportCompiler.mergeByIface(componentImports);
		// An interface whose resources another one uses must be imported first (its
		// instance is what the dependent's instance type aliases the resource out of).
		// Sorted HERE, once, so the component wiring, the synthesized core instances, the
		// core module's import fields and its instantiation arguments cannot disagree.
		componentImports = WasmComponentImportCompiler.inDependencyOrder(componentImports);
		if (!importDecls.isEmpty() && this.component) {
			throw new UnsupportedOperationException(
					"rontolisp:wasm-import is not supported with --component (Preview 1 core modules only)");
		}
		if (!componentImports.isEmpty() && !this.component) {
			throw new UnsupportedOperationException("the canonical-ABI import lowering requires --component");
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
		// Component-import bindings register the same way: a synthetic defun per bound
		// WIT function (Pass 2a reserves the body slot; the canonical-ABI marshalling
		// body is filled in once the memory-helper indices are known).
		Map<String, WasmComponentImportCompiler.Decl> componentImportWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.Decl decl : imported.decls()) {
				boolean duplicate = componentImportWrappers.containsKey(decl.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(decl.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + decl.lispName());
				}
				List<String> paramNames = new ArrayList<>();
				for (int i = 0; i < WasmComponentImportCompiler.lispArity(decl); i++) {
					paramNames.add("%component-import-p" + i);
				}
				componentImportWrappers.put(decl.lispName(), decl);
				defuns.add(new DefunDecl(decl.lispName(), paramNames, false, List.of()));
			}
		}
		// A resource drop binds the same way, and takes the handle as its one parameter.
		// It follows the function bindings in the placeholder ordinal space, so the two
		// maps are walked in this order everywhere below.
		Map<String, WasmComponentImportCompiler.Drop> componentDropWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.Drop drop : imported.drops()) {
				boolean duplicate = componentDropWrappers.containsKey(drop.lispName())
						|| componentImportWrappers.containsKey(drop.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(drop.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + drop.lispName());
				}
				componentDropWrappers.put(drop.lispName(), drop);
				defuns.add(new DefunDecl(drop.lispName(), List.of("%component-import-p0"), false, List.of()));
			}
		}
		// An async built-in binds the same way. It follows the drops in the placeholder
		// ordinal space, so the three maps are walked in this order everywhere below.
		Map<String, WasmComponentImportCompiler.Async> componentAsyncWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.Async async : imported.asyncs()) {
				boolean duplicate = componentAsyncWrappers.containsKey(async.lispName())
						|| componentDropWrappers.containsKey(async.lispName())
						|| componentImportWrappers.containsKey(async.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(async.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + async.lispName());
				}
				List<String> paramNames = new ArrayList<>();
				for (int i = 0; i < WasmComponentImportCompiler.lispArity(async); i++) {
					paramNames.add("%component-import-p" + i);
				}
				componentAsyncWrappers.put(async.lispName(), async);
				defuns.add(new DefunDecl(async.lispName(), paramNames, false, List.of()));
			}
		}
		// An async func member binds TWO wrappers -- the start (async-lowered call,
		// returning the (packed . retptr) token) and the lift (result lift out of the
		// return area, called by _subtask_future / the scheduler once the subtask has
		// returned) -- registered like the other synthetic defuns, after the async
		// built-ins in the placeholder ordinal space.
		Map<String, WasmComponentImportCompiler.AsyncCall> componentCallStartWrappers = new LinkedHashMap<>();
		Map<String, WasmComponentImportCompiler.AsyncCall> componentCallLiftWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.AsyncCall call : imported.calls()) {
				for (String wrapperName : List.of(call.startName(), call.liftName())) {
					boolean duplicate = componentCallStartWrappers.containsKey(wrapperName)
							|| componentCallLiftWrappers.containsKey(wrapperName)
							|| componentAsyncWrappers.containsKey(wrapperName)
							|| componentDropWrappers.containsKey(wrapperName)
							|| componentImportWrappers.containsKey(wrapperName)
							|| defuns.stream().anyMatch(d -> d.name.equals(wrapperName));
					if (duplicate) {
						throw new UnsupportedOperationException(
								"rontolisp:wit-import name collides with an existing function: " + wrapperName);
					}
				}
				List<String> startParams = new ArrayList<>();
				for (int i = 0; i < WasmComponentImportCompiler.lispArity(call); i++) {
					startParams.add("%component-import-p" + i);
				}
				componentCallStartWrappers.put(call.startName(), call);
				defuns.add(new DefunDecl(call.startName(), startParams, false, List.of()));
				componentCallLiftWrappers.put(call.liftName(), call);
				defuns.add(new DefunDecl(call.liftName(), List.of("%component-import-p0"), false, List.of()));
			}
		}
		// A task-return built-in binds the same way (one parameter: the result value).
		Map<String, WasmComponentImportCompiler.TaskReturn> componentTaskReturnWrappers = new LinkedHashMap<>();
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			for (WasmComponentImportCompiler.TaskReturn tr : imported.taskReturns()) {
				boolean duplicate = componentTaskReturnWrappers.containsKey(tr.lispName())
						|| componentCallStartWrappers.containsKey(tr.lispName())
						|| componentCallLiftWrappers.containsKey(tr.lispName())
						|| componentAsyncWrappers.containsKey(tr.lispName())
						|| componentDropWrappers.containsKey(tr.lispName())
						|| componentImportWrappers.containsKey(tr.lispName())
						|| defuns.stream().anyMatch(d -> d.name.equals(tr.lispName()));
				if (duplicate) {
					throw new UnsupportedOperationException(
							"rontolisp:wit-import name collides with an existing function: " + tr.lispName());
				}
				componentTaskReturnWrappers.put(tr.lispName(), tr);
				defuns.add(new DefunDecl(tr.lispName(), List.of("%component-import-p0"), false, List.of()));
			}
		}
		// A :s-expr export parameter parses host-provided text with the embedded reader,
		// so
		// force the reader runtime on (FUNC_READ_EXPR must be a real body, not a stub).
		// Applies to Preview 1 / no-wasi and (since todo 92 Tier 2) component non-serve;
		// serve mode's synthetic %http-dispatch export is :string-only.
		boolean exportNeedsReader = !(this.component && this.serve)
				&& exportDecls.stream().anyMatch(d -> d.paramTypes().contains(WasmExportCompiler.T_S_EXPR));
		// An :s-expr import result likewise parses host-provided text at runtime.
		boolean importNeedsReader = importDecls.stream().anyMatch(WasmImportCompiler::needsReader);
		if (exportNeedsReader || importNeedsReader) {
			usesRead = true;
		}
		// The intern built-in canonicalizes through the reader runtime's _intern (so a
		// runtime-interned symbol's offset matches literals in env lookups); it forces
		// the real _intern body without pulling in the rest of the reader.
		boolean usesIntern = usesRead || programUsesSymbol(program, LispNames.INTERN);

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
		// The intern wrapper body calls _intern_sym -> _intern, which is a stub unless
		// the program itself calls intern (usesIntern), so gate the wrapper the same way
		// as read-from-string.
		if (!usesIntern) {
			wrapperExcludes.add(LispNames.INTERN);
		}
		// Hash-table wrappers compile inline hash code (and register maphash's arity-2
		// dispatch); only inject them when the program uses a hash table, gating the
		// whole
		// group together for symmetry with the JVM backend.
		if (!programUsesAnyHashOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.HASH_FUNCTIONS);
		}
		// Fill-pointer array wrappers likewise: inject them only for array-using
		// programs, for symmetry with the JVM backend (where they reference the gated
		// array runtime helpers).
		if (!programUsesAnyArrayOp(program)) {
			wrapperExcludes.addAll(BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS);
		}
		for (LispVal wrapper : BuiltinFunctionWrappers.generate(userDefinedNames, wrapperExcludes)) {
			defuns.add(extractSetqLambda(wrapper));
		}

		// Collect top-level global variables and give each its own module-level wasm
		// global (mut (ref null eq)), placed after GLOBAL_ENV/GLOBAL_FENV (indices 2+).
		// A reference compiles to global.get from any function body, so a defun/lambda
		// can read a defvar/defparameter global. Indices follow declaration order.
		Set<String> globals = GlobalVarCollector.collect(topLevelExprs);
		// Special (dynamically bound) variables need the same module-global backing store
		// (a
		// let of a special save/restores over it), so union them in before indices are
		// assigned; a let/let* of one of these names becomes a dynamic binding
		// (WasmLetCompiler).
		Set<String> specialVars = SpecialVarCollector.collect(topLevelExprs);
		globals.addAll(specialVars);
		Map<String, Integer> globalIndices = new HashMap<>();
		int nextGlobalIndex = GLOBAL_FENV + 1;
		for (String g : globals) {
			globalIndices.put(g, nextGlobalIndex++);
		}
		int globalCount = globals.size();
		// EH mode: the handler-depth counter global goes AFTER the user-variable
		// globals, so their indices are unchanged whether or not EH mode is on.
		int ehDepthGlobalIndex = ehMode ? GLOBAL_FENV + 1 + globalCount : -1;
		// asyncMode: the scheduler registry (a cons list of (subtask . (future .
		// (lift . token))) entries) and the task waitable-set handle, after the EH
		// depth counter (asyncMode implies ehMode). Every non-async module is
		// byte-identical to a build that never knew about them.
		int schedRegistryGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 1 : -1;
		int schedSetGlobalIndex = this.asyncMode ? ehDepthGlobalIndex + 2 : -1;

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
					TYPE_CALLABLE_BASE + arity, userFuncBase() + i));
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();
		// The async waiter wake-up goes through the arity-1 dispatch (the resume
		// functions are arity-1 lambdas), and the wasi-stream read/close thunks
		// through the arity-0 one, so both bodies must be real.
		if (this.asyncMode) {
			indirectCallArities.add(0);
			indirectCallArities.add(1);
		}

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
			.ehMode(ehMode)
			.ehDepthGlobalIndex(ehDepthGlobalIndex)
			.functions(functions)
			.lambdaDecls(lambdaDecls)
			.indirectCallArities(indirectCallArities)
			.nextFuncId(nextFuncId)
			.dynamic(this.dynamic)
			.component(this.component)
			.serve(this.serve)
			.simd(this.simd)
			.userFuncBase(userFuncBase())
			.userDefunNames(Set.copyOf(userDefinedNames))
			.structAccessors(structAccessors)
			.closRegistry(closRegistry)
			.globals(globals)
			.specialVars(specialVars)
			.globalIndices(globalIndices)
			.futureTypeIndex(this.asyncMode ? asyncTypeBase() : -1)
			.frameTypeIndex(this.asyncMode ? asyncTypeBase() + 1 : -1)
			.wasiStreamTypeIndex(this.asyncMode ? asyncTypeBase() + 2 : -1)
			.asyncFuncBase(this.asyncMode ? asyncFuncBase() : -1)
			.asyncDefunNames(Set.copyOf(asyncDefunNames));

		// Pass 2a: Compile each defun body (with env param at slot 0)
		List<byte[]> userFunctionBodies = new ArrayList<>();
		// Import wrapper bodies are deferred until after the lambda pass: a :string
		// result calls the _str_from_mem helper, whose index follows the lambdas.
		Map<String, Integer> importBodySlots = new HashMap<>();
		for (DefunDecl defun : defuns) {
			if (importWrappers.containsKey(defun.name) || componentImportWrappers.containsKey(defun.name)
					|| componentDropWrappers.containsKey(defun.name) || componentAsyncWrappers.containsKey(defun.name)
					|| componentCallStartWrappers.containsKey(defun.name)
					|| componentCallLiftWrappers.containsKey(defun.name)
					|| componentTaskReturnWrappers.containsKey(defun.name)) {
				importBodySlots.put(defun.name, userFunctionBodies.size());
				userFunctionBodies.add(null); // filled in below
				continue;
			}
			if (this.asyncMode && asyncDefunNames.contains(defun.name)) {
				// entry + resume state machine (WasmAsyncEmit): the resume registers
				// itself in the lambda table; the entry is the defun's own function.
				ByteArrayOutputStream protoBuf = new ByteArrayOutputStream();
				Ctx protoCtx = ctxBuilder.writer(new WasmWriter(protoBuf)).bodyStream(protoBuf).build();
				WasmAsyncEmit.Resume resume = WasmAsyncEmit.compileResume(protoCtx, defun.paramNames, defun.bodyExprs,
						List.of(), false, false);
				userFunctionBodies.add(WasmAsyncEmit.buildEntryBody(protoCtx, defun.paramNames.size(), false, resume));
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

		// EH mode: an uncaught $lisp-cond throw escaping the top level must keep
		// today's trap shape (host-visible exit class), so the whole body runs inside
		// a catch_all whose landing pad is an unreachable. The normal path returns
		// from inside the try_table, which sidesteps needing a result blocktype.
		if (ehMode) {
			WasmEmitHelper.emitCatchAllPrologue(ctx);
		}
		int topLevelAwaits = 0;
		if (this.asyncMode) {
			for (LispVal expr : topLevelExprs) {
				topLevelAwaits += WasmAwaitAnalysis.countAwaits(expr);
			}
		}
		if (topLevelAwaits > 0) {
			// The top level is implicitly asynchronous: it compiles as an entry+resume
			// pair like any async-defun. An uncaught condition (incl. a rejected
			// top-level await's re-signal) escapes to the catch-all prologue -- the
			// same trap an uncaught error produces today.
			WasmAsyncEmit.Resume topResume = WasmAsyncEmit.compileResume(ctx, List.of(), topLevelExprs, List.of(), true,
					usesEval);
			WasmAsyncEmit.emitStartEntry(ctx, topResume);
		}
		else {
			for (LispVal expr : topLevelExprs) {
				WasmExprCompiler.compileExpr(expr, ctx);
				startWriter.write(Instruction.DROP);
			}
		}
		if (this.component) {
			// _start returns i32 (0 = ok) so it can be lifted as wasi:cli/run `run`
			startWriter.write(Instruction.I32_CONST);
			startWriter.writeSignedLeb128(0);
		}
		if (ehMode) {
			WasmEmitHelper.emitCatchAllEpilogue(ctx);
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
			if (lambda.precompiled() != null) {
				// an async entry/resume half, compiled out of line by WasmAsyncEmit
				lambdaFunctionBodies.add(lambda.precompiled());
				lambdaIdx++;
				continue;
			}
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

		// Build host-callable export wrappers (all modes; component mode restricts the
		// types to Tier-1 scalars and lifts each wrapper into a component-model export).
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
		// They precede the wrappers so the fixed FUNC_* constants are unaffected. In
		// component (non-serve) mode the host reaches __ronto_alloc through the appended
		// cabi_realloc instead of calling it directly (todo 92 Tier 2).
		boolean exportUsesMemory = exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory);
		int exportHelperBase = userFuncBase() + numDefuns + numLambdas;
		// A :string import result is written into linear memory by the host and boxed
		// with the same _str_from_mem helper, so it forces the helper pair on too.
		boolean importUsesStrFromMem = importDecls.stream().anyMatch(WasmImportCompiler::usesStrFromMem);
		// A component-import wrapper stages arguments and lifts string results through
		// the same helper pair (__ronto_alloc for the return area, _str_from_mem for
		// host-written bytes), so any bound interface forces the helpers on.
		boolean memoryHelpers = exportUsesMemory || importUsesStrFromMem || !componentImportWrappers.isEmpty()
				|| !componentAsyncWrappers.isEmpty() || !componentCallStartWrappers.isEmpty()
				|| !componentTaskReturnWrappers.isEmpty();
		int allocFuncIndex = memoryHelpers ? exportHelperBase : -1;
		int strFromMemFuncIndex = memoryHelpers ? exportHelperBase + 1 : -1;
		// Host arena API: __ronto_alloc_mark / __ronto_alloc_reset, appended right after
		// the two memory helpers. Emitted only when the module EXPORTS its memory -- i.e.
		// never under --component, where the memory is imported and the host reaches the
		// heap through the canonical cabi_realloc / cabi_post_* pair instead (which does
		// the same intern-guarded pop for itself). So every component module stays
		// byte-identical.
		boolean hostArena = memoryHelpers && !this.component;
		int helperFuncCount = memoryHelpers ? (hostArena ? 4 : 2) : 0;
		int allocMarkFuncIndex = hostArena ? exportHelperBase + 2 : -1;
		int allocResetFuncIndex = hostArena ? exportHelperBase + 3 : -1;
		// Unique host-import slots. Two Lisp wrappers can bind the SAME host function --
		// a serve+fetch program's two spliced halves may each call
		// wasi:http/types.fields-append, body-stream-read, ... in their own
		// %-package -- and the component model forbids a core module from importing one
		// (module, field) twice. So the wrappers stay distinct defuns but SHARE one core
		// import: this list has one entry per unique (module, field), and every wrapper
		// of
		// that function calls its slot's placeholder ordinal. Order = wasm-import decls,
		// then
		// component-import decls, then drops (the hostImports / import-type order below).
		// A
		// program with no overlap gets one slot per wrapper, exactly as before, so its
		// bytes
		// do not change.
		record ImportSlot(String module, String field, Type[] params, Type[] results) {
		}
		LinkedHashMap<String, Integer> importSlotIndex = new LinkedHashMap<>();
		List<ImportSlot> importSlots = new ArrayList<>();
		for (WasmImportCompiler.Decl decl : importWrappers.values()) {
			if (importSlotIndex.putIfAbsent(decl.module() + " " + decl.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(decl.module(), decl.field(), WasmImportCompiler.hostParamTypes(decl),
						WasmImportCompiler.hostResultTypes(decl)));
			}
		}
		for (WasmComponentImportCompiler.Decl decl : componentImportWrappers.values()) {
			if (importSlotIndex.putIfAbsent(decl.module() + " " + decl.field(), importSlots.size()) == null) {
				importSlots
					.add(new ImportSlot(decl.module(), decl.field(), WasmComponentImportCompiler.hostParamTypes(decl),
							WasmComponentImportCompiler.hostResultTypes(decl)));
			}
		}
		for (WasmComponentImportCompiler.Drop drop : componentDropWrappers.values()) {
			if (importSlotIndex.putIfAbsent(drop.module() + " " + drop.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(drop.module(), drop.field(),
						WasmComponentImportCompiler.dropParamTypes(), new Type[] {}));
			}
		}
		for (WasmComponentImportCompiler.Async async : componentAsyncWrappers.values()) {
			if (importSlotIndex.putIfAbsent(async.module() + " " + async.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(async.module(), async.field(),
						WasmComponentImportCompiler.asyncParamTypes(async),
						WasmComponentImportCompiler.asyncResultTypes(async)));
			}
		}
		// Async-lowered calls, then the waitable builtins each calling interface shares
		// (one set per interface, driven by every await wrapper of that interface), then
		// the task-return built-ins.
		for (WasmComponentImportCompiler.AsyncCall call : componentCallStartWrappers.values()) {
			if (importSlotIndex.putIfAbsent(call.module() + " " + call.field(), importSlots.size()) == null) {
				importSlots
					.add(new ImportSlot(call.module(), call.field(), WasmComponentImportCompiler.hostParamTypes(call),
							WasmComponentImportCompiler.hostResultTypes(call)));
			}
		}
		for (WasmComponentImportCompiler.Import imported : componentImports) {
			if (imported.calls().isEmpty() && imported.asyncs().isEmpty()) {
				continue;
			}
			// async calls await through the waitable-set, and the async (non-blocking)
			// stream/future built-in wrappers park on it when BLOCKED -- so any
			// interface with either binds the trio.
			for (String field : WasmComponentImportCompiler.WAITABLE_FIELDS) {
				if (importSlotIndex.putIfAbsent(imported.ifaceId() + " " + field, importSlots.size()) == null) {
					importSlots.add(new ImportSlot(imported.ifaceId(), field,
							WasmComponentImportCompiler.waitableParamTypes(field),
							WasmComponentImportCompiler.waitableResultTypes(field)));
				}
			}
		}
		for (WasmComponentImportCompiler.TaskReturn tr : componentTaskReturnWrappers.values()) {
			if (importSlotIndex.putIfAbsent(tr.module() + " " + tr.field(), importSlots.size()) == null) {
				importSlots.add(new ImportSlot(tr.module(), tr.field(),
						WasmComponentImportCompiler.taskReturnParamTypes(tr), new Type[] {}));
			}
		}
		// The scheduler's waitable trio: any async-calling interface's builtins work
		// (they alias the same canonical built-ins as every other interface's), so the
		// first one's ordinals are wired into the async runtime (_subtask_future /
		// _sched_loop). Null when the program binds no async-calling interface --
		// nothing can produce a host-backed pending future there, and the two
		// scheduler members become unreachable stubs.
		WasmFutureRuntimeBuilder.Sched schedWiring = null;
		if (this.asyncMode) {
			for (WasmComponentImportCompiler.Import imported : componentImports) {
				if (imported.calls().isEmpty() && imported.asyncs().isEmpty()) {
					continue;
				}
				String schedIface = imported.ifaceId();
				schedWiring = new WasmFutureRuntimeBuilder.Sched(
						new WasmComponentImportCompiler.WaitOrdinals(
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_NEW)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_WAIT)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_DROP)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_JOIN)),
								Objects.requireNonNull(importSlotIndex
									.get(schedIface + "\0" + WasmComponentImportCompiler.FIELD_SUBTASK_DROP))),
						schedRegistryGlobalIndex, schedSetGlobalIndex, allocFuncIndex);
				break;
			}
		}
		final WasmFutureRuntimeBuilder.Sched sched = schedWiring;
		// Fill in the deferred import wrapper bodies now that the helper indices are
		// known (their positions in userFunctionBodies were reserved in Pass 2a). Each
		// wrapper calls its (module, field) slot's ordinal, so duplicate bindings
		// collapse
		// onto one import.
		{
			for (WasmImportCompiler.Decl decl : importWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(decl.module() + " " + decl.field()));
				byte[] body = WasmImportCompiler.buildWrapperBody(ctxBuilder, decl, ordinal, strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(decl.name())), body);
			}
			for (WasmComponentImportCompiler.Decl decl : componentImportWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(decl.module() + " " + decl.field()));
				byte[] body = WasmComponentImportCompiler.buildWrapperBody(ctxBuilder, decl, ordinal, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(decl.lispName())), body);
			}
			for (WasmComponentImportCompiler.Drop drop : componentDropWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(drop.module() + " " + drop.field()));
				byte[] body = WasmComponentImportCompiler.buildDropBody(ctxBuilder, drop, ordinal);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(drop.lispName())), body);
			}
			for (WasmComponentImportCompiler.Async async : componentAsyncWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(async.module() + " " + async.field()));
				WasmComponentImportCompiler.WaitOrdinals asyncWaitOrdinals = new WasmComponentImportCompiler.WaitOrdinals(
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_NEW)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_WAIT)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_SET_DROP)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_WAITABLE_JOIN)),
						Objects.requireNonNull(importSlotIndex
							.get(async.module() + "\0" + WasmComponentImportCompiler.FIELD_SUBTASK_DROP)));
				byte[] body = WasmComponentImportCompiler.buildAsyncBody(ctxBuilder, async, ordinal, asyncWaitOrdinals,
						allocFuncIndex, strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(async.lispName())), body);
			}
			for (WasmComponentImportCompiler.AsyncCall call : componentCallStartWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(call.module() + " " + call.field()));
				byte[] body = WasmComponentImportCompiler.buildAsyncStartBody(ctxBuilder, call, ordinal, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(call.startName())), body);
			}
			for (WasmComponentImportCompiler.AsyncCall call : componentCallLiftWrappers.values()) {
				byte[] body = WasmComponentImportCompiler.buildAsyncLiftBody(ctxBuilder, call, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(call.liftName())), body);
			}
			for (WasmComponentImportCompiler.TaskReturn tr : componentTaskReturnWrappers.values()) {
				int ordinal = Objects.requireNonNull(importSlotIndex.get(tr.module() + " " + tr.field()));
				byte[] body = WasmComponentImportCompiler.buildTaskReturnBody(ctxBuilder, tr, ordinal, allocFuncIndex,
						strFromMemFuncIndex);
				userFunctionBodies.set(Objects.requireNonNull(importBodySlots.get(tr.lispName())), body);
			}
		}
		if (!exportDecls.isEmpty()) {
			int wrapperFuncIndex = exportHelperBase + helperFuncCount;
			int wrapperTypeIndex = fixedTypeCount();
			for (WasmExportCompiler.Decl decl : exportDecls) {
				if (decl.paramTypes().contains(WasmExportCompiler.T_LONG)
						|| WasmExportCompiler.T_LONG.equals(decl.returnType())) {
					throw new UnsupportedOperationException("rontolisp:wasm-export :long requires --no-gc for '"
							+ decl.name() + "' (the GC backend represents integers as i31ref, not i64; use :int)");
				}
				// Component-model exports (non-serve --component): scalars lift
				// synchronously with no canonical options; :string/:s-expr lift through
				// the canonical string ABI over the appended cabi_realloc / post-return
				// / retptr-shim helpers (todo 92 Tier 2).
				if (this.component && !this.serve) {
					if (!WasmExportCompiler.COMPONENT_EXPORT_NAME.matcher(decl.exportName()).matches()) {
						throw new UnsupportedOperationException("rontolisp:wasm-export name '" + decl.exportName()
								+ "' is not a valid component-model export name (lower-kebab-case words, e.g."
								+ " \"sum-squared\"); rename it with :as \"kebab-name\"");
					}
					// The core module already exports "run" (the lifted wasi:cli/run
					// entry); a second core export under the same name would make the
					// module invalid.
					if ("run".equals(decl.exportName())) {
						throw new UnsupportedOperationException("rontolisp:wasm-export name 'run' collides with the"
								+ " component's wasi:cli/run entry; rename it with :as");
					}
				}
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

		// Canonical string ABI for --component :string/:s-expr exports (todo 92 Tier 2):
		// cabi_realloc (the host lowers string arguments through it; delegates to
		// __ronto_alloc and snapshots the CABI_MARK_* cells), one retptr shim per
		// :string/:s-expr-RETURNING export (MAX_FLAT_RESULTS = 1, so the lifted core
		// function returns a single i32 pointing at an 8-byte (ptr,len) record instead of
		// the wrapper's two values), and one cabi_post_* post-return per flat-result
		// signature (pops the bump heap to the snapshot once the host has copied the
		// results out, intern-count-guarded). All appended after every existing function
		// -- no index shifts -- and emitted ONLY under component (non-serve) with a
		// memory-typed export, so scalar-only components stay byte-identical to Tier 1.
		boolean componentStringAbi = this.component && !this.serve
				&& exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory);
		List<ExportPlan> retptrShimPlans = new ArrayList<>();
		// kind -> the post-return's single flat-result core type (null = void result).
		LinkedHashMap<String, @org.jspecify.annotations.Nullable Type> componentPostKinds = new LinkedHashMap<>();
		if (componentStringAbi) {
			for (ExportPlan p : exportPlans) {
				if (WasmExportCompiler.returnsMemory(p.decl())) {
					retptrShimPlans.add(p);
				}
				if (WasmExportCompiler.usesMemory(p.decl())) {
					componentPostKinds.putIfAbsent(WasmExportCompiler.componentPostReturnKind(p.decl()),
							switch (WasmExportCompiler.componentPostReturnKind(p.decl())) {
								case "f64" -> Type.F64;
								case "void" -> null;
								default -> Type.I32;
							});
				}
			}
		}
		// The type entries appended after the fixed types, the export wrappers and the
		// import signatures: the component string-ABI block, or (mutually exclusive,
		// since
		// the host arena is non-component only) the two arena signatures.
		int abiTypeBase = fixedTypeCount() + exportPlans.size() + importSlots.size();
		int abiFuncBase = exportHelperBase + helperFuncCount + exportPlans.size();
		int cabiReallocFuncIndex = abiFuncBase;
		int cabiPostFuncBase = cabiReallocFuncIndex + 1;
		int retptrShimFuncBase = cabiPostFuncBase + componentPostKinds.size();

		// Host-ABI type entries for the imported functions follow the export wrapper
		// types; the WasmImportInjector post-pass prepends the matching import entries
		// and resolves the placeholder call indices after the module is assembled. One
		// entry
		// per unique (module, field) slot -- a wasm-import, a component-import call
		// (module =
		// the interface's canonical id, field = the canonical-ABI function name), or a
		// resource drop (field = "[resource-drop]<resource>") -- so a host function bound
		// by
		// two Lisp wrappers is imported once, which the component model requires.
		List<am.ik.wasm.WasmImportInjector.HostImport> hostImports = new ArrayList<>();
		int importTypeIndex = fixedTypeCount() + exportPlans.size();
		for (ImportSlot slot : importSlots) {
			hostImports
				.add(new am.ik.wasm.WasmImportInjector.HostImport(slot.module(), slot.field(), importTypeIndex++));
		}

		List<byte[]> dispatchBodies = new ArrayList<>();
		for (int arity = 0; arity <= MAX_CALLABLE_ARITY; arity++) {
			if (indirectCallArities.contains(arity)) {
				dispatchBodies.add(WasmRuntimeBuilder.buildDispatchBody(arity, defuns, lambdaDecls, numDefuns,
						stringTable, usesEval, userFuncBase()));
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
		byte[] printValBody = WasmRuntimeBuilder.buildPrintValBody(stringTable, this.simd,
				this.asyncMode ? asyncTypeBase() : -1);
		byte[] printI32NoNlBody = WasmRuntimeBuilder.buildPrintI32Core(false);
		byte[] printF64Body = WasmRuntimeBuilder.buildPrintF64Core(true, stringTable);
		byte[] printF64NoNlBody = WasmRuntimeBuilder.buildPrintF64Core(false, stringTable);
		byte[] appendBody = WasmRuntimeBuilder.buildAppendBody();
		byte[] readLineBody = WasmRuntimeBuilder.buildReadLineBody(stringTable);
		byte[] princValBody = WasmRuntimeBuilder.buildPrincValBody(stringTable, this.simd,
				this.asyncMode ? asyncTypeBase() : -1);

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

		// The symbol-API helper bodies (always emitted) embed the offset of the symbol
		// t; intern it before the runtime intern blob below is snapshotted so a runtime
		// (intern "t") canonicalizes to the same offset literals use.
		int symbolTOffset = stringTable.addString("t").offset();
		// Build the reader runtime (read/load). Symbols parsed at runtime are interned
		// against a compile-time table of (offset,length) so they match the offsets the
		// eval runtime compares against. The intern built-in reuses _intern for the same
		// canonicalization (usesIntern), without the rest of the reader.
		final byte[] internBody;
		final byte[] readExprBody;
		final byte[] readListBody;
		final byte[] readBody;
		final byte[] loadBody;
		if (usesIntern) {
			// Intern nil/t/quote/function before snapshotting so the runtime resolves
			// them to the same offsets the eval runtime uses.
			int nilOffset = stringTable.addString("nil").offset();
			int tOffset = stringTable.addString("t").offset();
			int quoteOffset = stringTable.addString(LispNames.QUOTE).offset();
			int functionOffset = stringTable.addString(LispNames.FUNCTION).offset();
			java.util.Collection<StringTable.StringEntry> internEntries = stringTable.entries();
			int internCount = internEntries.size();
			int internBase = stringTable.appendBlob(buildInternBlob(internEntries));
			internBody = WasmReadRuntimeBuilder.buildInternBody(internBase, internCount, hostArena);
			if (usesRead) {
				readExprBody = WasmReadRuntimeBuilder.buildReadExprBody(nilOffset, tOffset, quoteOffset,
						functionOffset);
				readListBody = WasmReadRuntimeBuilder.buildReadListBody();
				readBody = WasmReadRuntimeBuilder.buildReadBody();
				loadBody = WasmReadRuntimeBuilder.buildLoadBody();
			}
			else {
				readExprBody = WasmReadRuntimeBuilder.buildReadExprStub();
				readListBody = WasmReadRuntimeBuilder.buildReadListStub();
				readBody = WasmReadRuntimeBuilder.buildReadStub();
				loadBody = WasmReadRuntimeBuilder.buildLoadStub();
			}
		}
		else {
			internBody = WasmReadRuntimeBuilder.buildInternStub();
			readExprBody = WasmReadRuntimeBuilder.buildReadExprStub();
			readListBody = WasmReadRuntimeBuilder.buildReadListStub();
			readBody = WasmReadRuntimeBuilder.buildReadStub();
			loadBody = WasmReadRuntimeBuilder.buildLoadStub();
		}
		// Symbol-API helper bodies (FUNC_MAKE_SYMBOL .. FUNC_FBOUNDP), built before the
		// string table is serialized because they embed the offset of the symbol t.
		final byte[] makeSymbolBody = WasmSymbolApiRuntimeBuilder.buildMakeSymbol();
		final byte[] internSymBody = WasmSymbolApiRuntimeBuilder.buildInternSym();
		final byte[] boundpBody = WasmSymbolApiRuntimeBuilder.buildBoundp(symbolTOffset);
		final byte[] symbolValueBody = WasmSymbolApiRuntimeBuilder.buildSymbolValue(symbolTOffset);
		final byte[] fboundpBody = WasmSymbolApiRuntimeBuilder.buildFboundp(symbolTOffset);

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
					// type 4: string struct {i32 id, i32 len, (ref null eq) data}. id is
					// the
					// canonical integer identity (interned offset or runtime id, compared
					// with i32.eq); len is the byte length; data is the $str_bytes GC
					// array
					// holding the quote-framed bytes (nil until a builder fills it -- see
					// FUNC_STR_BUILD).
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
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
				// type 31 (TYPE_CHAR): character struct {i32 code}
				types.addRecGroup(
						rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I32))));
				// type 32 (TYPE_HASH_BUCKETS): array (mut (ref null eq)) -- hash-table
				// buckets. Encoded as a bare array comptype (sugar for sub final),
				// implicitly
				// a subtype of eq so it stores in cons/cell fields and supports ref.eq.
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.writeRefType(true, Type.EQ.code());
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 33 (TYPE_PROMISE): promise struct {mut i32 kind, mut (ref null eq)
				// base, mut (ref null eq) fn} -- all fields mutable so _promise_await can
				// rewrite a promise to its settled state in place.
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(true, w -> w.write(Type.I32));
					fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
				}));
				// type 34 (TYPE_STR_BYTES): array (mut i8) -- a string's byte storage.
				// A bare array comptype (implicitly sub final), so a subtype of eq: it
				// stores in TYPE_STRING's (ref null eq) data field and readers ref.cast
				// it.
				// The element is the packed storage type i8 (0x78; no Type constant).
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.write(0x78); // i8 packed storage type
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 35 (TYPE_STR_TO_MEM): _str_to_mem ((ref null eq), i32) -> i32
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(2);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(Type.I32);
					w.write(1);
					w.write(Type.I32);
				});
				// type 36 (TYPE_WRITE_STR_GC): _write_str_gc ((ref null eq), i32, i32) ->
				// ()
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(3);
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(Type.I32);
					w.write(Type.I32);
					w.write(0);
				});
				// type 37 (TYPE_F64ARR): array (mut f64) -- packed float-array data
				// storage.
				// A bare array comptype (implicitly sub final), a subtype of eq.
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.write(Type.F64);
					w.write(am.ik.wasm.Mutability.VAR);
				});
				// type 38 (TYPE_FARRAY): struct {(ref null eq) dims, (ref null eq) data}
				// --
				// a packed rank-n double-float array (dims = a TYPE_HASH_BUCKETS of i31
				// sizes, data = a TYPE_F64ARR). Both fields immutable (aset mutates the
				// f64 array contents, not the struct).
				types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
					fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
				}));
				// type 39 (TYPE_F32ARR): array (mut f32) -- packed single-float array
				// data
				// storage. A bare array comptype (implicitly sub final), a subtype of eq;
				// stored in TYPE_FARRAY's data field alongside TYPE_F64ARR (the width is
				// told apart by ref.test $f32arr).
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.write(Type.F32);
					w.write(am.ik.wasm.Mutability.VAR);
				});
				if (this.simd) {
					// type 40 (TYPE_V128ARR): array (mut v128) -- the lane-group storage
					// of a packed float array. Declaring it at all requires the SIMD
					// proposal, which is why it is gated on --simd.
					types.add(w -> {
						w.write(Type.ARRAY_TYPE);
						w.write(Type.V128);
						w.write(am.ik.wasm.Mutability.VAR);
					});
					// type 41 (TYPE_VBLOCK): struct {i32 count, i32 kind, (ref null eq)
					// groups} -- what TYPE_FARRAY's data field holds under --simd. All
					// fields immutable (an aset mutates a v128 group, not the struct).
					types.addRecGroup(rec -> rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.writeRefType(true, Type.EQ.code()));
					}));
					// type 42 (TYPE_V_GET): _v_get ((ref null eq), i32) -> f64
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(2);
						w.write(Type.REFNULL.code());
						w.writeHeapType(Type.EQ.code());
						w.write(Type.I32);
						w.write(1);
						w.write(Type.F64);
					});
					// type 43 (TYPE_V_SET): _v_set ((ref null eq), i32, f64) -> f64
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(3);
						w.write(Type.REFNULL.code());
						w.writeHeapType(Type.EQ.code());
						w.write(Type.I32);
						w.write(Type.F64);
						w.write(1);
						w.write(Type.F64);
					});
				}
				if (this.asyncMode) {
					// TYPE_FUTURE + TYPE_ASYNC_FRAME + TYPE_WASI_STREAM in ONE rec
					// group: the first two are structurally identical, and same-group
					// membership is what keeps identical shapes distinct under wasm-GC's
					// structural type canonicalization (a separate group would
					// canonicalize them EQUAL and ref.test could no longer tell them
					// apart). TYPE_WASI_STREAM joins the group for the same reason (its
					// shape is one field away from colliding with a future refactor).
					types.addRecGroup(rec -> {
						// TYPE_FUTURE {mut i32 state, mut value, mut waiters, mut src}
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
						// TYPE_ASYNC_FRAME {mut i32 state, mut spill, mut future,
						// mut env}
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
						// TYPE_WASI_STREAM {mut i32 eof, mut readFn, mut closeFn}: a
						// first-class stream value over a wasi-backed byte stream. The
						// two closures (arity 0, dispatched through dispatch_0) carry
						// the handle and the close protocol; eof flips once and makes
						// close idempotent.
						rec.addSubFinalStruct(fields -> {
							fields.addField(true, w -> w.write(Type.I32));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
							fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						});
					});
				}
				// Export wrapper signatures (host-callable), appended after the last
				// fixed type (TYPE_F32ARR, or the --simd block's TYPE_V_SET). One per
				// (rontolisp:wasm-export ...) directive.
				for (ExportPlan p : exportPlans) {
					// the serve-mode handle is CALLBACK-lifted: its core signature
					// carries the trailing i32 packed callback code
					types.addFunc(WasmExportCompiler.paramWasmTypes(p.decl()),
							WasmExportCompiler.isServeHandle(this.serve, p.decl()) ? new Type[] { Type.I32 }
									: WasmExportCompiler.resultWasmTypes(p.decl()));
				}
				// Host-ABI signatures of the imported functions, one per unique (module,
				// field) slot, in the same order as the hostImports entries above -- a
				// host
				// import's declared type index is positional. A slot is a wasm-import
				// signature, a component-import binding's canonical-ABI flat signature,
				// or a
				// resource drop's (func (param i32)); duplicate bindings of one host
				// function
				// share a slot, so its type is written once.
				for (ImportSlot slot : importSlots) {
					types.addFunc(slot.params(), slot.results());
				}
				// Component string-ABI signatures (todo 92 Tier 2), from abiTypeBase:
				// cabi_realloc, one cabi_post_* per flat-result signature, then one
				// retptr shim per :string/:s-expr-returning export (the wrapper's
				// params, a single i32 return pointer).
				if (componentStringAbi) {
					types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
					for (Type flat : componentPostKinds.values()) {
						types.addFunc(flat == null ? new Type[0] : new Type[] { flat }, new Type[0]);
					}
					for (ExportPlan p : retptrShimPlans) {
						types.addFunc(WasmExportCompiler.paramWasmTypes(p.decl()), new Type[] { Type.I32 });
					}
				}
				// Host arena signatures, from abiTypeBase (the component string-ABI block
				// above is mutually exclusive with them, so the base is shared):
				// __ronto_alloc_mark () -> i32, __ronto_alloc_reset (i32) -> ().
				if (hostArena) {
					types.addFunc(new Type[0], new Type[] { Type.I32 });
					types.addFunc(new Type[] { Type.I32 }, new Type[0]);
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
				// (adapter-http-server-p1.wasm, instantiated before this core by
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
				// program uses a tcp built-in in component mode. Otherwise (Preview 1, or
				// a component not using tcp) they are defined trap stubs.
				// tcp-connect/tcp-listen(hostPtr, hostLen, port, fdOut) -> errno share
				// fd_write's (4x i32) -> i32 shape; tcp-accept(fd, fdOut) -> errno and
				// tcp-local-port(fd, portOut) -> errno are (2x i32) -> i32 like _intern.
				if (emitSockImport) {
					imports.addImport("sock", "tcp-connect", ExternalKind.FUNCTION, TYPE_FD_WRITE);
					imports.addImport("sock", "tcp-listen", ExternalKind.FUNCTION, TYPE_FD_WRITE);
					imports.addImport("sock", "tcp-accept", ExternalKind.FUNCTION, TYPE_INTERN);
					imports.addImport("sock", "tcp-local-port", ExternalKind.FUNCTION, TYPE_INTERN);
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
				// (11): sock.* imports when the program uses tcp in component mode;
				// otherwise defined trap stubs so the following defined-function indices
				// line up with the FUNC_* constants.
				if (!emitSockImport) {
					fnDef.addFunction(TYPE_FD_WRITE); // index 8: unused tcp-connect stub
					fnDef.addFunction(TYPE_FD_WRITE); // index 9: unused tcp-listen stub
					fnDef.addFunction(TYPE_INTERN); // index 10: unused tcp-accept stub
					fnDef.addFunction(TYPE_INTERN); // index 11: unused tcp-local-port
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
				fnDef.addFunction(TYPE_RAT_CMP); // _rat_cmp_bits
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
				// plist runtime helper (FUNC_PLIST_GET)
				fnDef.addFunction(TYPE_OPEN); // _plist_get ((ref null eq), i32) ->
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
				// symbol runtime API helpers
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _make_symbol (str)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _intern_sym (str)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _boundp (sym)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _symbol_value (sym)
				fnDef.addFunction(TYPE_CALLABLE_BASE + 0); // _fboundp (sym)
				// read-char runtime helper
				fnDef.addFunction(TYPE_CALLABLE_BASE + 2); // _read_char (stream,
															// eof-error-p, eof-value) ->
															// (ref null eq)
				// _str_build (off, len) -> (ref null eq): reuses the (i32,i32)->ref shape
				fnDef.addFunction(TYPE_RAT_NEW); // _str_build (FUNC_STR_BUILD)
				fnDef.addFunction(TYPE_RAT_NEW); // _str_fresh (FUNC_STR_FRESH)
				fnDef.addFunction(TYPE_STR_TO_MEM); // _str_to_mem (FUNC_STR_TO_MEM)
				fnDef.addFunction(TYPE_WRITE_STR_GC); // _write_str_gc (FUNC_WRITE_STR_GC)
				// vec: SIMD block (--simd only): the three element helpers + twelve
				// kernels
				if (this.simd) {
					for (int i = 0; i < WasmVecSimdRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmVecSimdRuntimeBuilder.typeIndexOf(i));
					}
					// linalg: SIMD block (--simd only): fifteen kernels, right after.
					for (int i = 0; i < WasmLinalgSimdRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmLinalgSimdRuntimeBuilder.typeIndexOf(i));
					}
				}
				// Async future runtime (asyncMode only), right after the --simd block.
				if (this.asyncMode) {
					for (int i = 0; i < WasmFutureRuntimeBuilder.FUNC_COUNT; i++) {
						fnDef.addFunction(WasmFutureRuntimeBuilder.typeIndexOf(i));
					}
				}
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
					// Host arena API: __ronto_alloc_mark / __ronto_alloc_reset, with the
					// two signatures appended at abiTypeBase.
					if (hostArena) {
						fnDef.addFunction(abiTypeBase);
						fnDef.addFunction(abiTypeBase + 1);
					}
				}
				// Export wrapper functions (host-callable), one per
				// (rontolisp:wasm-export ...).
				for (ExportPlan p : exportPlans) {
					fnDef.addFunction(p.typeIndex());
				}
				// Component string-ABI functions (todo 92 Tier 2): cabi_realloc, the
				// cabi_post_* post-returns, then the retptr shims, matching abiTypeBase.
				if (componentStringAbi) {
					int abiType = abiTypeBase;
					fnDef.addFunction(abiType++);
					for (int k = 0; k < componentPostKinds.size(); k++) {
						fnDef.addFunction(abiType++);
					}
					for (int k = 0; k < retptrShimPlans.size(); k++) {
						fnDef.addFunction(abiType++);
					}
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
			});
		// Tag section (EH mode only): the one $lisp-cond exception tag, whose payload
		// type reuses TYPE_PRINT_VAL (((ref null eq)) -> ()). Belongs between the memory
		// and global sections. Emitting it at all requires the host to enable the
		// exception-handling proposal, which is why it is gated.
		if (ehMode) {
			mainWriter.writeTagSection(tags -> tags.addTag(TYPE_PRINT_VAL));
		}
		mainWriter
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
				// EH mode: the handler-depth counter, a (mut i32) = 0 at
				// ehDepthGlobalIndex (after every user global).
				if (ehMode) {
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
						g.write(Instruction.END);
					});
				}
				// asyncMode: the scheduler registry, a (mut (ref null eq)) = null, and
				// the task waitable-set handle, a (mut i32) = 0 (created lazily).
				if (this.asyncMode) {
					gs.add(g -> {
						g.write(Type.REFNULL.code());
						g.writeHeapType(Type.EQ.code());
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.REF_NULL);
						g.writeHeapType(Type.EQ.code());
						g.write(Instruction.END);
					});
					gs.add(g -> {
						g.write(Type.I32);
						g.write(am.ik.wasm.Mutability.VAR.code());
						g.write(Instruction.I32_CONST);
						g.writeSignedLeb128(0);
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
					}
					// Core-export each (rontolisp:wasm-export ...) wrapper: in serve mode
					// the serve adapter calls %http-dispatch through it; otherwise the
					// component wrapper (WasmComponentBuilder) aliases it and lifts it
					// into a host-callable component-model export. A :string/:s-expr-
					// returning export is exported as its retptr shim (the two-value
					// wrapper stays reachable through the shim's call but is not itself
					// an export).
					for (ExportPlan p : exportPlans) {
						int shim = retptrShimPlans.indexOf(p);
						exports.addExport(p.decl().exportName(), ExternalKind.FUNCTION,
								shim >= 0 ? retptrShimFuncBase + shim : p.funcIndex());
					}
					// The canonical string ABI helpers the component wrap aliases.
					if (componentStringAbi) {
						exports.addExport(WasmExportCompiler.CABI_REALLOC, ExternalKind.FUNCTION, cabiReallocFuncIndex);
						int k = 0;
						for (String kind : componentPostKinds.keySet()) {
							exports.addExport(WasmExportCompiler.cabiPostExportName(kind), ExternalKind.FUNCTION,
									cabiPostFuncBase + k++);
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
						// The host arena API: snapshot the bump-heap top before the host
						// allocates its input buffer, pop back to it after the call. The
						// engine reclaims the Lisp side; this reclaims the linear-memory
						// buffer at the boundary, which it cannot see.
						exports.addExport("__ronto_alloc_mark", ExternalKind.FUNCTION, allocMarkFuncIndex);
						exports.addExport("__ronto_alloc_reset", ExternalKind.FUNCTION, allocResetFuncIndex);
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
				if (!emitSockImport) {
					for (int i = 0; i < 4; i++) {
						code.addFunction(new byte[] { 0x00, 0x00, 0x0b });
					}
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
					.addFunction(WasmRatioRuntimeBuilder.buildRatCmpBitsBody())
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
				// plist runtime helper body (FUNC_PLIST_GET)
				code.addFunction(WasmPlistRuntimeBuilder.buildPlistGet());
				// Hash-table runtime helper bodies (FUNC_HASH, FUNC_HASH_RESIZE)
				code.addFunction(WasmRuntimeBuilder.buildHashBody());
				code.addFunction(WasmRuntimeBuilder.buildHashResizeBody());
				// Modulo / remainder runtime helper bodies (FUNC_RAT_REM, FUNC_RAT_MOD)
				code.addFunction(WasmRatioRuntimeBuilder.buildRatRemBody(false));
				code.addFunction(WasmRatioRuntimeBuilder.buildRatRemBody(true));
				// gensym runtime helper body (FUNC_GENSYM)
				code.addFunction(WasmGensymRuntimeBuilder.build());
				// promise-await runtime helper body (FUNC_PROMISE_AWAIT)
				code.addFunction(WasmPromiseRuntimeBuilder.buildAwait());
				// binary stream runtime helper bodies (FUNC_READ_BYTE, FUNC_WRITE_BYTE)
				code.addFunction(WasmIoRuntimeBuilder.buildReadByteBody());
				code.addFunction(WasmIoRuntimeBuilder.buildWriteByteBody());
				// string-stream runtime helper bodies (FUNC_WRITE_STREAM_STR,
				// FUNC_MAKE_STR_OSTREAM, FUNC_MAKE_STR_ISTREAM, FUNC_STR_STREAM_CONTENTS)
				code.addFunction(WasmStringStreamRuntimeBuilder.buildWriteStreamStrBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildMakeOutputStreamBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildMakeInputStreamBody());
				code.addFunction(WasmStringStreamRuntimeBuilder.buildContentsBody());
				// symbol-API helper bodies (FUNC_MAKE_SYMBOL .. FUNC_FBOUNDP)
				code.addFunction(makeSymbolBody);
				code.addFunction(internSymBody);
				code.addFunction(boundpBody);
				code.addFunction(symbolValueBody);
				code.addFunction(fboundpBody);
				// read-char runtime helper body (FUNC_READ_CHAR)
				code.addFunction(WasmIoRuntimeBuilder.buildReadCharBody());
				// _str_build helper body (FUNC_STR_BUILD): linear[off..off+len) -> a
				// TYPE_STRING backed by a $str_bytes GC array.
				code.addFunction(WasmStringRuntimeBuilder.buildStrBuildBody());
				// _str_fresh (FUNC_STR_FRESH): like _str_build but id = STRING_ID_CTR++.
				code.addFunction(WasmStringRuntimeBuilder.buildStrFreshBody());
				// _str_to_mem (FUNC_STR_TO_MEM): copy a string's GC array into
				// linear[ptr..).
				code.addFunction(WasmStringRuntimeBuilder.buildStrToMemBody());
				// _write_str_gc (FUNC_WRITE_STR_GC): print a string value from its GC
				// array.
				code.addFunction(WasmStringRuntimeBuilder.buildWriteStrGcBody());
				// vec: SIMD block bodies (--simd only), in FUNC_VEC_BASE index order.
				if (this.simd) {
					for (int i = 0; i < WasmVecSimdRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmVecSimdRuntimeBuilder.build(i, FUNC_VEC_BASE));
					}
					// linalg: SIMD block bodies, in linalgFuncBase() index order.
					for (int i = 0; i < WasmLinalgSimdRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmLinalgSimdRuntimeBuilder.build(i, FUNC_VEC_BASE));
					}
				}
				// Async future runtime bodies (asyncMode only), in asyncFuncBase() order.
				if (this.asyncMode) {
					for (int i = 0; i < WasmFutureRuntimeBuilder.FUNC_COUNT; i++) {
						code.addFunction(WasmFutureRuntimeBuilder.build(i, asyncFuncBase(), asyncTypeBase(),
								asyncTypeBase() + 1, asyncTypeBase() + 2, sched));
					}
				}
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
					if (hostArena) {
						code.addFunction(WasmExportRuntimeBuilder.buildAllocMarkBody());
						code.addFunction(WasmExportRuntimeBuilder.buildAllocResetBody());
					}
				}
				// Export wrapper bodies (host-callable), one per (rontolisp:wasm-export
				// ...).
				for (byte[] body : exportBodies) {
					code.addFunction(body);
				}
				// Component string-ABI bodies (todo 92 Tier 2), matching the function
				// section: cabi_realloc, one cabi_post_* per flat-result signature
				// (identical bodies; the flat params are ignored), then the retptr
				// shims.
				if (componentStringAbi) {
					code.addFunction(WasmExportRuntimeBuilder.buildCabiReallocBody(allocFuncIndex));
					for (int k = 0; k < componentPostKinds.size(); k++) {
						code.addFunction(WasmExportRuntimeBuilder.buildCabiPostReturnBody());
					}
					for (ExportPlan p : retptrShimPlans) {
						code.addFunction(WasmExportRuntimeBuilder.buildRetptrShimBody(p.funcIndex(),
								cabiReallocFuncIndex, WasmExportCompiler.paramSlotCount(p.decl())));
					}
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
				// Runtime string ids start at heapBase (above every interned/rt-intern
				// offset) and only increase, so they never collide with an interned id
				// nor
				// with each other even as the assembly scratch offset is reused.
				data.addActiveData(0, STRING_ID_CTR_ADDR, littleEndian32(heapBase));
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
				// into a wasi:http/handler@0.3 component (wasmtime serve). A program that
				// also fetches reaches http.lisp's client half through the same block --
				// serve and serve+fetch are ONE component shape.
				// A rontolisp:wit-import joins the fixed wasi:http surface as an extra
				// instance import (canon lower), so a handler's state can live in a real
				// store.
				// http.lisp's own wasi:http imports are part of the
				// FIXED
				// surface (the import block), so they must NOT be re-emitted as user
				// imports
				// (that double-declares their packages in the emitted WIT) -- only the
				// ADDITIONAL rontolisp:wit-import interfaces are (a served handler whose
				// state
				// lives in a real store: wasi:keyvalue joins as an extra instance
				// import).
				List<WasmComponentImportCompiler.Import> serveUserImports = WasmServeComponentBuilder
					.additionalImports(componentImports);
				this.componentWit = WitEmitter.emit(WitEmitter.VARIANT_HTTP_SERVER, List.of(), serveUserImports);
				return WasmComponentBuilder.buildServe(coreModule, componentImports);
			}
			// Lift each wasm-export into a host-callable component-model export
			// (synchronous canon lift; WAVE-invokable) alongside wasi:cli/run. Scalar
			// exports lift with no canonical options; :string/:s-expr ones with the
			// canonical string options over the appended ABI helpers (todo 92 Tier 2).
			List<WasmExportCompiler.Decl> componentExportDecls = new ArrayList<>();
			for (ExportPlan p : exportPlans) {
				componentExportDecls.add(p.decl());
			}
			// This is the non-serve component path (serve returned above), where
			// emitHttpImport is always false: rontolisp:fetch here is the http.lisp
			// library
			// over canon-lowered wasi:http user imports (the base variant), not the WAT
			// http-client blob.
			this.componentWit = WitEmitter.emit(emitSockImport ? WitEmitter.VARIANT_SOCKETS : WitEmitter.VARIANT_BASE,
					componentExportDecls, componentImports);
			return WasmComponentBuilder.build(coreModule, emitSockImport, componentExportDecls, componentImports);
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

	/** The forms whose value is an integer whatever their argument types. */
	private static final java.util.Set<String> INTEGER_VALUED_FORMS = java.util.Set.of(LispNames.ROUND,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING);

	static boolean containsDouble(LispVal val) {
		if (val instanceof LispDouble) {
			return true;
		}
		if (val instanceof LispCons cons) {
			// A rounding form yields an integer whatever its argument types, so a
			// double literal inside it must not drag the ENCLOSING arithmetic onto
			// the double path: (- 0 (round (* v 100.0))) is integer work.
			if (cons.car() instanceof LispSymbol head && INTEGER_VALUED_FORMS.contains(head.name())) {
				return false;
			}
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

	/**
	 * asyncMode: rewrites every top-level {@code (rontolisp:async-defun name (ll)
	 * body...)} into the equivalent plain {@code (defun name (ll) body...)} and records
	 * the name -- Pass 2a compiles those defuns as entry+resume state machines
	 * ({@code WasmAsyncEmit}) instead of plain bodies. A nested async-defun is rejected
	 * (define it at top level; a first-class async function value is
	 * {@code rontolisp:async-lambda}).
	 * @param program the resolved, flattened top-level forms
	 * @param asyncDefunNames receives the rewritten names
	 * @return the rewritten program
	 */
	private static List<LispVal> rewriteTopLevelAsyncDefuns(List<LispVal> program, Set<String> asyncDefunNames) {
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
					&& LispNames.ASYNC_DEFUN_QUALIFIED.equals(head.name())) {
				if (!(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol name)) {
					throw new UnsupportedOperationException(LispNames.ASYNC_DEFUN_QUALIFIED
							+ " expects (async-defun name (params) body...): " + form.print());
				}
				asyncDefunNames.add(name.name());
				out.add(new LispCons(new LispSymbol(LispNames.DEFUN), cons.cdr()));
			}
			else {
				out.add(form);
			}
		}
		return out;
	}

	// True when the program contains a form that flips the module into EH mode: a
	// catching/cleanup form, one of the with-* macros whose expansion rides
	// unwind-protect on WASM (todo-129 step 7), or the usocket guard/with-* family
	// (the guard sits in the spliced usocket.lisp defun bodies, so any
	// usocket-using program qualifies).
	private static boolean programUsesEhForm(List<LispVal> program) {
		for (LispVal expr : program) {
			if (usesEhForm(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean usesEhForm(LispVal val) {
		if (!(val instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym) {
			switch (sym.name()) {
				case LispNames.HANDLER_CASE, LispNames.IGNORE_ERRORS, LispNames.UNWIND_PROTECT,
						LispNames.WITH_OPEN_FILE, LispNames.WITH_OUTPUT_TO_STRING, LispNames.WITH_INPUT_FROM_STRING -> {
					return true;
				}
				default -> {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					if (qn != null && LispNames.USOCKET_PKG.equals(qn.pkg())) {
						switch (qn.member()) {
							case LispNames.USOCKET_WITH_CLIENT_SOCKET, LispNames.USOCKET_WITH_CONNECTED_SOCKET,
									LispNames.USOCKET_WITH_SERVER_SOCKET, LispNames.USOCKET_WITH_SOCKET_LISTENER,
									LispNames.USOCKET_GUARD -> {
								return true;
							}
							default -> {
							}
						}
					}
				}
			}
		}
		return usesEhForm(cons.car()) || usesEhForm(cons.cdr());
	}

	// True when the program references any hash-table operator (including (setf (gethash
	// ...)) which contains gethash). Gates the first-class hash wrappers.
	private static boolean programUsesAnyHashOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_HASH_TABLE) || programUsesSymbol(program, LispNames.GETHASH)
				|| programUsesSymbol(program, LispNames.REMHASH) || programUsesSymbol(program, LispNames.CLRHASH)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_COUNT)
				|| programUsesSymbol(program, LispNames.HASH_TABLE_P) || programUsesSymbol(program, LispNames.MAPHASH);
	}

	// True when the program references any array operator (or contains an array
	// literal), matching the JVM backend's predicate. Gates the first-class
	// fill-pointer wrappers so they are injected only for array-using programs.
	private static boolean programUsesAnyArrayOp(List<LispVal> program) {
		return programUsesSymbol(program, LispNames.MAKE_ARRAY) || programUsesSymbol(program, LispNames.AREF)
				|| programUsesSymbol(program, LispNames.ASET) || programUsesSymbol(program, LispNames.ARRAY_DIMENSIONS)
				|| programUsesSymbol(program, LispNames.VECTOR) || programUsesSymbol(program, LispNames.SVREF)
				|| programUsesSymbol(program, LispNames.ARRAY_RANK)
				|| programUsesSymbol(program, LispNames.ARRAY_DIMENSION)
				|| programUsesSymbol(program, LispNames.ARRAY_TOTAL_SIZE)
				|| programUsesSymbol(program, LispNames.ROW_MAJOR_AREF)
				|| programUsesSymbol(program, LispNames.ROW_MAJOR_ASET)
				|| programUsesSymbol(program, LispNames.ARRAY_ROW_MAJOR_INDEX)
				|| programUsesSymbol(program, LispNames.FILL_POINTER)
				|| programUsesSymbol(program, LispNames.SET_FILL_POINTER)
				|| programUsesSymbol(program, LispNames.ARRAY_HAS_FILL_POINTER_P)
				|| programUsesSymbol(program, LispNames.ADJUSTABLE_ARRAY_P)
				|| programUsesSymbol(program, LispNames.ARRAY_ELEMENT_TYPE)
				|| programUsesSymbol(program, LispNames.VECTOR_PUSH) || programUsesSymbol(program, LispNames.VECTOR_POP)
				|| programUsesSymbol(program, LispNames.VECTOR_PUSH_EXTEND)
				|| programUsesSymbol(program, LispNames.ADJUST_ARRAY)
				|| programUsesSymbol(program, LispNames.ARRAY_BECOME)
				|| programUsesSymbol(program, LispNames.ARRAY_DISPLACEMENT)
				|| programUsesSymbol(program, LispNames.ARRAY_DISP_TARGET)
				|| programUsesSymbol(program, LispNames.ARRAY_DISP_OFFSET)
				|| programUsesSymbol(program, LispNames.COERCE) || programContainsArrayLiteral(program);
	}

	// True when a self-evaluating array literal (#(...)) appears anywhere in the
	// program.
	private static boolean programContainsArrayLiteral(List<LispVal> program) {
		for (LispVal expr : program) {
			if (containsArrayLiteral(expr)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsArrayLiteral(LispVal val) {
		if (val instanceof am.ik.rontolisp.LispArray) {
			return true;
		}
		if (val instanceof LispCons cons) {
			return containsArrayLiteral(cons.car()) || containsArrayLiteral(cons.cdr());
		}
		return false;
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

	/**
	 * A function compiled in Pass 2c. {@code precompiled} is non-null for an async
	 * entry/resume half ({@code WasmAsyncEmit}), whose body was compiled out of line;
	 * Pass 2c then emits those bytes verbatim.
	 */
	record LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs,
			List<String> freeVarNames, int funcIndex, byte @Nullable [] precompiled) {

		LambdaInfo(int funcId, String methodName, List<String> paramNames, boolean variadic, List<LispVal> bodyExprs,
				List<String> freeVarNames, int funcIndex) {
			this(funcId, methodName, paramNames, variadic, bodyExprs, freeVarNames, funcIndex, null);
		}
	}

	/**
	 * Per-resume emission state of the {@code --component} async state machines: the
	 * resume function's own funcId (each suspension registers a
	 * {@code TYPE_CLOSURE{resumeFuncId, frame}} waiter) and the monotonically assigned
	 * suspend-state counter ({@code $rt} dispatch values; 0 = initial entry).
	 */
	static final class AsyncResume {

		final int resumeFuncId;

		int nextState = 1;

		/**
		 * Whether the implicit top-level resume's OUTERMOST body has already been
		 * chunk-outlined ({@code WasmAsyncEmit.compileTopLevelChunkedProgn}); nested
		 * bodies (a top-level {@code let}'s, whose statements may reference its locals)
		 * must never be outlined.
		 */
		boolean topLevelChunked;

		AsyncResume(int resumeFuncId) {
			this.resumeFuncId = resumeFuncId;
		}

	}

	/**
	 * An active unwind scope (EH mode): the protected region of an {@code unwind-protect}
	 * (cleanup forms) or a {@code handler-case} (the {@code %hc-depth-dec} form).
	 * {@code blockDepth} is the {@code %block}-stack size when the scope was entered -- a
	 * {@code return} escapes the scope when {@code blockDepth >= blockMarkers.size()} at
	 * the return site (the scope was entered inside the return's target block).
	 * {@code trampolineDepth} is the {@code wasmCtrlDepth} marker of the scope's
	 * exit-trampoline block, lexically outside its try_table (so a throw from a cleanup
	 * cannot re-enter the scope's own handler); -1 when the scope has no trampoline (no
	 * enclosing {@code %block}, so no {@code return} can escape it).
	 *
	 * @param cleanupForms the cleanup forms to run when a {@code return} exits the scope
	 * @param blockDepth the {@code %block}-stack size at scope entry
	 * @param trampolineDepth the {@code wasmCtrlDepth} marker of the exit trampoline, or
	 * -1
	 */
	record UnwindScope(List<LispVal> cleanupForms, int blockDepth, int trampolineDepth) {
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

		// True in a rontolisp:http-handler (serve-mode) component.
		boolean serve = false;

		/**
		 * True when the program uses {@code handler-case}/{@code ignore-errors}/
		 * {@code unwind-protect}: the module carries the {@code $lisp-cond} exception tag
		 * and {@code %error}/{@code %error-cond} throw it instead of trapping. Requires
		 * the host to enable the exception-handling proposal ({@code -W exceptions=y},
		 * wasmtime 37+).
		 */
		boolean ehMode = false;

		/**
		 * The wasm global index of the handler-depth counter (a {@code (mut i32)} = 0
		 * appended after the user-variable globals), or -1 outside EH mode. The
		 * {@code handler-case} region increments/decrements it (the JVM
		 * {@code _hcDepthTl} parity) so {@code %signal-cond} raises only under an
		 * established handler.
		 */
		int ehDepthGlobalIndex = -1;

		/**
		 * True under {@code --simd}: the vectorizable {@code vec:} kernels are routed to
		 * the {@link WasmVecSimdRuntimeBuilder} v128 helpers at their call sites, and a
		 * packed float array's {@code TYPE_FARRAY} data field holds a {@code TYPE_VBLOCK}
		 * over an {@code (array (mut v128))} of lane groups rather than a
		 * {@code TYPE_F64ARR}/{@code TYPE_F32ARR}. One module only ever uses one
		 * representation, so every packed reader/writer branches on this flag at COMPILE
		 * time.
		 */
		boolean simd = false;

		/**
		 * {@link WasmLispCompiler#userFuncBase()} -- the index of the first user defun.
		 */
		int userFuncBase = FUNC_USER_BASE;

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
		 * The CLOS registry (classes, generics, slot positions), collected by the
		 * pre-pass in {@link WasmLispCompiler#compile}; {@code make-instance}/
		 * {@code slot-value} expansion resolves through it. Shared across every context.
		 */
		ClosRegistry closRegistry = new ClosRegistry();

		/**
		 * Names of top-level global variables; each has a wasm global in
		 * {@link #globalIndices}.
		 */
		Set<String> globals = Set.of();

		/**
		 * Names of special (dynamically bound) variables (a subset of {@link #globals}).
		 * A {@code let}/{@code let*} of one of these names saves its module-level wasm
		 * global, assigns the init value, and restores the global on normal exit -- a
		 * dynamic binding -- instead of allocating a fresh lexical local.
		 */
		Set<String> specialVars = Set.of();

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

		/**
		 * The {@code TYPE_FUTURE} type index, or -1 outside asyncMode.
		 */
		int futureTypeIndex = -1;

		/**
		 * The {@code TYPE_ASYNC_FRAME} type index, or -1 outside asyncMode.
		 */
		int frameTypeIndex = -1;

		/**
		 * The {@code TYPE_WASI_STREAM} type index, or -1 outside asyncMode.
		 */
		int wasiStreamTypeIndex = -1;

		/**
		 * The async runtime block's base function index ({@code _future_new}), or -1
		 * outside asyncMode.
		 */
		int asyncFuncBase = -1;

		/**
		 * Names of the top-level {@code rontolisp:async-defun}s (asyncMode): their defuns
		 * compile as entry+resume pairs, and an export wrapper targeting one polls the
		 * returned future.
		 */
		Set<String> asyncDefunNames = Set.of();

		/**
		 * Non-null while compiling an async resume body (asyncMode): the state-machine
		 * emission mode of the form compilers.
		 */
		@Nullable AsyncResume asyncResume;

		/**
		 * Transient marker that the NEXT {@code compileExpr} call compiles a spine child
		 * (empty operand stack, so an await there may suspend). Set by the async-aware
		 * form compilers, consumed at {@code compileExpr} entry.
		 */
		boolean asyncSpine;

		/**
		 * Whether the form currently being compiled sits at a spine position (the
		 * consumed {@link #asyncSpine} of the enclosing {@code compileExpr} call).
		 */
		boolean asyncSpineCurrent;

		/**
		 * Monotonic counter for the {@code %await$N} hoist bindings
		 * ({@code WasmAwaitNormalizer}); per body, purely for name uniqueness.
		 */
		int asyncHoistCounter;

		/**
		 * Stack of active unwind scopes (EH mode): one per {@code unwind-protect}
		 * protected region (cleanup forms) and per {@code handler-case} protected region
		 * (the {@code %hc-depth-dec} form), innermost on top. A {@code return} whose
		 * target {@code %block} lies outside the innermost scope branches to that scope's
		 * exit trampoline instead of the block (see {@code WasmReturnCompiler}); the
		 * trampolines cascade outward, running each escaped scope's cleanups in unwinding
		 * order.
		 */
		final Deque<UnwindScope> unwindScopes = new ArrayDeque<>();

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
			this.serve = builder.serve;
			this.ehMode = builder.ehMode;
			this.ehDepthGlobalIndex = builder.ehDepthGlobalIndex;
			this.simd = builder.simd;
			this.userFuncBase = builder.userFuncBase;
			this.userDefunNames = builder.userDefunNames;
			this.structAccessors = builder.structAccessors;
			this.closRegistry = builder.closRegistry;
			this.globals = builder.globals;
			this.specialVars = builder.specialVars;
			this.globalIndices = builder.globalIndices;
			this.futureTypeIndex = builder.futureTypeIndex;
			this.frameTypeIndex = builder.frameTypeIndex;
			this.wasiStreamTypeIndex = builder.wasiStreamTypeIndex;
			this.asyncFuncBase = builder.asyncFuncBase;
			this.asyncDefunNames = builder.asyncDefunNames;
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

			private boolean serve = false;

			private boolean ehMode = false;

			private int ehDepthGlobalIndex = -1;

			private boolean simd = false;

			private int userFuncBase = FUNC_USER_BASE;

			private Set<String> userDefunNames = Set.of();

			private Map<String, Integer> structAccessors = Map.of();

			private ClosRegistry closRegistry = new ClosRegistry();

			private Set<String> globals = Set.of();

			private Set<String> specialVars = Set.of();

			private Map<String, Integer> globalIndices = Map.of();

			private int futureTypeIndex = -1;

			private int frameTypeIndex = -1;

			private int wasiStreamTypeIndex = -1;

			private int asyncFuncBase = -1;

			private Set<String> asyncDefunNames = Set.of();

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

			Builder serve(boolean serve) {
				this.serve = serve;
				return this;
			}

			Builder ehMode(boolean ehMode) {
				this.ehMode = ehMode;
				return this;
			}

			Builder ehDepthGlobalIndex(int ehDepthGlobalIndex) {
				this.ehDepthGlobalIndex = ehDepthGlobalIndex;
				return this;
			}

			Builder simd(boolean simd) {
				this.simd = simd;
				return this;
			}

			Builder userFuncBase(int userFuncBase) {
				this.userFuncBase = userFuncBase;
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

			Builder closRegistry(ClosRegistry closRegistry) {
				this.closRegistry = closRegistry;
				return this;
			}

			Builder globals(Set<String> globals) {
				this.globals = globals;
				return this;
			}

			Builder specialVars(Set<String> specialVars) {
				this.specialVars = specialVars;
				return this;
			}

			Builder globalIndices(Map<String, Integer> globalIndices) {
				this.globalIndices = globalIndices;
				return this;
			}

			Builder futureTypeIndex(int futureTypeIndex) {
				this.futureTypeIndex = futureTypeIndex;
				return this;
			}

			Builder frameTypeIndex(int frameTypeIndex) {
				this.frameTypeIndex = frameTypeIndex;
				return this;
			}

			Builder wasiStreamTypeIndex(int wasiStreamTypeIndex) {
				this.wasiStreamTypeIndex = wasiStreamTypeIndex;
				return this;
			}

			Builder asyncFuncBase(int asyncFuncBase) {
				this.asyncFuncBase = asyncFuncBase;
				return this;
			}

			Builder asyncDefunNames(Set<String> asyncDefunNames) {
				this.asyncDefunNames = asyncDefunNames;
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
		// prints "#", the rank as an integer, then "A(". A packed float array
		// (TYPE_FARRAY) prints "#d(" (double) or "#f(" (single) at every rank instead, so
		// its printed form round-trips to a packed array of the same width -- the printer
		// picks fPrefix / sfPrefix by ref.test-ing the data array's width.
		final StringEntry vecPrefix;

		final StringEntry hashPrefix;

		final StringEntry rankAOpen;

		final StringEntry fPrefix;

		final StringEntry sfPrefix;

		final StringEntry minus;

		final StringEntry period;

		final StringEntry slash;

		// Float printing: the IEEE specials and the exponent marker (todo-108 group C).
		final StringEntry nanStr;

		final StringEntry infinityStr;

		final StringEntry expE;

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
			this.promiseStr = addString("#<FUTURE>");
			this.vecPrefix = addString("#(");
			this.hashPrefix = addString("#");
			this.rankAOpen = addString("A(");
			this.fPrefix = addString("#d(");
			this.sfPrefix = addString("#f(");
			this.minus = addString("-");
			this.period = addString(".");
			this.slash = addString("/");
			this.nanStr = addString("NaN");
			this.infinityStr = addString("Infinity");
			this.expE = addString("E");
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
