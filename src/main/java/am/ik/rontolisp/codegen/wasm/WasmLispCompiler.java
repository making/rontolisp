package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
		this.dynamic = dynamic;
		this.component = component;
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

	// Function index 8 is reserved for rontolisp:fetch in BOTH modes so the
	// defined-function
	// indices stay identical across modes: in component mode it is the imported
	// http.fetch;
	// in Preview 1 mode it is an unused trap stub (fetch raises a compile error there).
	// This
	// keeps FUNC_START at 9 uniformly, so all the static FUNC_* constants below are
	// stable.
	static final int FUNC_FETCH = IMPORT_FUNC_COUNT; // 8

	static final int FUNC_START = FUNC_FETCH + 1; // 9

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

	// User defuns start after the dispatch functions, the four fetch helpers, and the two
	// hash-table runtime helpers.
	static final int FUNC_USER_BASE = FUNC_HASH_RESIZE + 1;

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

	// fetch (10x i32) -> i32 errno: the adapter's http.fetch import / Preview 1 stub
	// type.
	static final int TYPE_FETCH = TYPE_CLOCK_TIME_GET + 1; // 31

	// Character struct {i32 code}: the runtime representation of a character, distinct
	// from
	// an i31 integer so characterp and the accessors can dispatch on it via ref.test.
	static final int TYPE_CHAR = TYPE_FETCH + 1; // 32

	// Hash-table bucket array: array (mut (ref null eq)). Each slot holds a bucket alist
	// (a cons chain of (key . value) entries) or null. Implicitly a subtype of eq, so a
	// bucket array can be stored in a cons/cell field and compared with ref.eq.
	static final int TYPE_HASH_BUCKETS = TYPE_CHAR + 1; // 33

	// Global (wasm global section) index holding the runtime eval top-level environment
	// (an association list of cons(name, value) bindings; ref.null eq when empty).
	static final int GLOBAL_ENV = 0;

	// Global (wasm global section) index holding the runtime eval function namespace
	// (Lisp-2): defuns evaluated at runtime (e.g. from load) are bound here, separate
	// from the variable environment above.
	static final int GLOBAL_FENV = 1;

	// Global (wasm global section) index holding the mutable i32 seed for the random
	// number generator. WASM has no entropy source, so random is a deterministic
	// linear-congruential generator seeded from this fixed value (see
	// WasmRandomCompiler).
	static final int GLOBAL_SEED = 2;

	// Initial seed for GLOBAL_SEED (the classic glibc LCG starting value).
	static final int RANDOM_SEED_INIT = 12345;

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

	// Scratch (8 bytes) where the adapter's random_get writes a wasi:random u64 in
	// component mode.
	static final int RANDOM_SCRATCH_ADDR = 120;

	// Scratch (8 bytes) where clock_time_get writes the current time in nanoseconds.
	static final int TIME_SCRATCH_ADDR = 128;

	// getenv scratch: environ count / buffer-size words (low free region), and the
	// pointer
	// array + "KEY=VALUE\0" buffer placed in page 3 (the canonical realloc heap is page
	// 2).
	static final int ENV_COUNT_ADDR = 136;

	static final int ENV_BUFSIZE_ADDR = 140;

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

	static final int REQ_HDR_BUF = 0x40100;

	static final int RT_INTERN_BASE = 8192;

	static final int READ_LINE_BUF = 16384;

	private static final int DATA_BASE_OFFSET = 128;

	@Override
	public byte[] compile(List<LispVal> program) {
		// Resolve packages (in-package directives, qualified symbols, *package*) up front
		// so
		// the rest of compilation sees canonical names.
		program = new PackageResolver().resolveProgram(program);
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
		// rontolisp:fetch is component-only. In component mode it is the http.fetch
		// import
		// (function index FUNC_FETCH); the component wrapper then imports wasi:http. In
		// Preview 1 mode index FUNC_FETCH is an unused trap stub and fetch raises a
		// compile
		// error (WasmFetchCompiler), so the import/wasi:http are never emitted.
		boolean usesFetch = programUsesSymbol(program,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FETCH));
		boolean emitHttpImport = this.component && usesFetch;
		// Pass 1: Collect defun declarations and top-level expressions. Lisp-2: only a
		// real (defun ...) form defines a function; a top-level (setq name (lambda ...))
		// binds a variable to a closure like any other setq.
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				defuns.add(extractSetqLambda(LispMacroExpander.expandDefun(cons)));
			}
			else {
				topLevelExprs.add(expr);
			}
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
			functions.put(defun.name,
					new WasmFunctionInfo(defun.name, arity, funcId, TYPE_CALLABLE_BASE + arity, FUNC_USER_BASE + i));
		}

		// Shared state for lambda discovery
		List<LambdaInfo> lambdaDecls = new ArrayList<>();
		Set<Integer> indirectCallArities = new HashSet<>();

		// When eval is used, _eval applies any registered function via the dispatch
		// functions, so ensure a real dispatch body exists for every registered arity.
		if (usesEval) {
			for (WasmFunctionInfo fi : functions.values()) {
				if (fi.paramCount() <= MAX_CALLABLE_ARITY) {
					indirectCallArities.add(fi.paramCount());
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
			.userDefunNames(Set.copyOf(userDefinedNames));

		// Pass 2a: Compile each defun body (with env param at slot 0)
		List<byte[]> userFunctionBodies = new ArrayList<>();
		for (DefunDecl defun : defuns) {
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

		// Initialize heap pointer for read-line buffer
		startWriter.write(Instruction.I32_CONST);
		startWriter.writeSignedLeb128(HEAP_PTR_ADDR);
		startWriter.write(Instruction.I32_CONST);
		startWriter.writeSignedLeb128(READ_LINE_BUF);
		startWriter.write(Instruction.I32_STORE, 0x02, 0x00);

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
				writeLittleEndian32(registry, defun.paramNames.size()); // arity
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
				// type 31 (TYPE_FETCH): fetch (12x i32) -> i32 errno. The 12 params are
				// method, urlPtr, urlLen, reqBodyPtr, reqBodyLen, reqHdrPtr, reqHdrLen,
				// statusPtr, rhdrPtrOut, rhdrLenOut, bodyPtrOut, bodyLenOut.
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32,
						Type.I32, Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 32 (TYPE_CHAR): character struct {i32 code}
				types.addRecGroup(
						rec -> rec.addSubFinalStruct(fields -> fields.addField(false, w -> w.write(Type.I32))));
				// type 33 (TYPE_HASH_BUCKETS): array (mut (ref null eq)) -- hash-table
				// buckets. Encoded as a bare array comptype (sugar for sub final),
				// implicitly
				// a subtype of eq so it stores in cons/cell fields and supports ref.eq.
				types.add(w -> {
					w.write(Type.ARRAY_TYPE);
					w.writeRefType(true, Type.EQ.code());
					w.write(am.ik.wasm.Mutability.VAR);
				});
			})
			// Import section
			.writeImportSection(imports -> {
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
					.addImport("wasi_snapshot_preview1", "clock_time_get", ExternalKind.FUNCTION, TYPE_CLOCK_TIME_GET)
					.addImport("wasi_snapshot_preview1", "environ_sizes_get", ExternalKind.FUNCTION, TYPE_INTERN)
					.addImport("wasi_snapshot_preview1", "environ_get", ExternalKind.FUNCTION, TYPE_INTERN);
				// 9th function import (index FUNC_FETCH) when the program calls fetch in
				// component mode: the adapter's exported http.fetch. In Preview 1 mode
				// (or a
				// component without fetch) index 8 is a defined trap stub instead.
				if (emitHttpImport) {
					imports.addImport("http", "fetch", ExternalKind.FUNCTION, TYPE_FETCH);
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
				// Reserve function index FUNC_FETCH (8): in component+fetch it is the
				// http.fetch import (emitted above); otherwise a defined trap stub so the
				// following defined-function indices line up with the FUNC_* constants.
				if (!emitHttpImport) {
					fnDef.addFunction(TYPE_FETCH); // index 8: unused fetch trap stub
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
				// User defun functions
				for (DefunDecl defun : defuns) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + defun.paramNames.size());
				}
				// Lambda functions
				for (LambdaInfo lambda : lambdaDecls) {
					fnDef.addFunction(TYPE_CALLABLE_BASE + lambda.paramNames.size());
				}
			})
			// Memory section -- in component mode the memory is imported (above), so this
			// section is empty. 4 pages so getenv can place the environ buffer in page 3
			// (the canonical realloc heap is page 1+ in component mode).
			.writeMemory(memories -> {
				if (!this.component) {
					memories.addMemory(4);
				}
			})
			// Global section: the eval top-level variable environment (GLOBAL_ENV) and
			// the Lisp-2 function namespace (GLOBAL_FENV), both (mut (ref null eq)) =
			// null
			.writeGlobal(globals -> globals.add(g -> {
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
			}).add(g -> {
				// GLOBAL_SEED: a mutable i32 random seed (deterministic LCG state).
				g.write(Type.I32.code());
				g.write(am.ik.wasm.Mutability.VAR.code());
				g.write(Instruction.I32_CONST);
				g.writeSignedLeb128(RANDOM_SEED_INIT);
				g.write(Instruction.END);
			}))
			// Export section -- component mode exports `run` (the i32-returning _start)
			// for
			// the lifted wasi:cli/run entry; the memory is imported, not exported
			.writeExport(exports -> {
				if (this.component) {
					exports.addExport("run", ExternalKind.FUNCTION, FUNC_START);
				}
				else {
					exports.addExport("memory", ExternalKind.MEMORY, 0)
						.addExport("_start", ExternalKind.FUNCTION, FUNC_START);
				}
			})
			// Code section
			.writeCode(code -> {
				// fetch trap stub at function index FUNC_FETCH (only when it is not the
				// http.fetch import): no locals, unreachable, end. It is never called.
				if (!emitHttpImport) {
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
				// User defun function bodies
				for (byte[] body : userFunctionBodies) {
					code.addFunction(body);
				}
				// Lambda function bodies
				for (byte[] body : lambdaFunctionBodies) {
					code.addFunction(body);
				}
			})
			// Data section
			.writeDataSection(data -> {
				byte[] stringData = stringTable.toByteArray();
				if (stringData.length > 0) {
					data.addActiveData(0, DATA_BASE_OFFSET, stringData);
				}
			});
		byte[] coreModule = out.toByteArray();
		return this.component ? WasmComponentBuilder.build(coreModule, emitHttpImport) : coreModule;
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

	private static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		return new DefunDecl(funcName, paramNames, lambdaParts.subList(2, lambdaParts.size()));
	}

	record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	record WasmFunctionInfo(String name, int paramCount, int funcId, int typeIndex, int funcIndex) {
	}

	record LambdaInfo(int funcId, String methodName, List<String> paramNames, List<LispVal> bodyExprs,
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

		Set<String> userDefunNames = Set.of();

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
