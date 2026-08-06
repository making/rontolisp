package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.compiler.ConcatenateForms;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.rontolisp.compiler.OptimizeLevel;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Mutability;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * The non-GC WASM lowering (the {@code --no-gc} backend) for pure-numeric
 * {@code rontolisp:wasm-export} functions. Values are unboxed native wasm scalars
 * ({@code i64}/{@code f64}/linear-memory pointers) rather than GC heap objects; this
 * "scalar" is the <em>value model</em> and is orthogonal to hardware SIMD -- the
 * vectorizable {@code vec:} kernels lower to plain scalar loops by default and to native
 * v128 ({@code f64x2}/{@code f32x4}) under {@code --simd}.
 *
 * <p>
 * Unlike {@link WasmLispCompiler}, whose value model <em>is</em> wasm-GC (integers are
 * {@code i31ref}, cons cells / strings / arrays are GC heap objects, the runtime is
 * written against {@code eqref}), this compiler emits a plain MVP module: there is no rec
 * group, no {@code struct}/{@code array}/i31 type, no {@code eqref}, no linear memory and
 * no import. The result instantiates with no import object and runs on any MVP-class
 * runtime with no {@code -W gc}.
 *
 * <p>
 * It is viable only because, if an exported function's entire transitive call graph
 * touches numbers only, the whole computation closes over scalars and never needs a heap.
 * Eligibility is enforced: a function reachable from a {@code --no-gc} export may use
 * only numeric literals / {@code t} / {@code nil}, arithmetic / comparison / bitwise
 * operators, the boolean/numeric predicates, {@code if}/{@code let}/{@code progn},
 * <em>iteration</em> ({@code dotimes}/{@code do}/{@code do*}, the underlying
 * {@code while}/{@code setq}/{@code return}) and the macros that expand into them,
 * float/int conversions, recursion and calls to other eligible functions. Anything else
 * (cons/list/string/char/symbol/vector/hash, {@code eval}, I/O, a free variable) is
 * rejected with a compile error naming the offending operation so the boundary is
 * explicit, never a silent miscompile.
 *
 * <p>
 * <strong>Numeric model.</strong> Each value is represented by a native wasm scalar
 * chosen by static type inference: integers use {@code i64} (exact to 2^63, far wider
 * than the GC backend's i31 fixnums) and floats use {@code f64}. Types are inferred with
 * a fixpoint over the call graph seeded by the export boundary designators; where an
 * integer and a float meet (e.g. {@code (* 3.14 n)}) the integer operand is promoted to
 * {@code f64}. Local variables (let/{@code do} bindings) that are mutated by {@code setq}
 * take the join of their initializer and every assigned value, so an integer accumulator
 * summed with floats widens to {@code f64}; the fixpoint is monotone (INT only ever
 * widens to FLOAT) so it terminates. There is no rational type, so two things differ from
 * full Common Lisp and from the GC backend: {@code /} is floating-point division (no
 * {@code 1/3} ratios), and a value is treated as false in a boolean context exactly when
 * it is zero ({@code nil} is the only false value in Common Lisp). Both are documented
 * limitations of {@code --no-gc}.
 *
 * <p>
 * Only scalar boundary designators are supported: {@code :int} ({@code i32}),
 * {@code :float} ({@code f64}), {@code :bool} ({@code i32}, 0 = false) and {@code :void}
 * / omitted. Memory-backed {@code :string}/{@code :s-expr} would need a second
 * linear-memory string runtime and are deferred (Phase 2).
 */
public final class NoGcWasmCompiler implements LispCompiler {

	/** The native representation of a value. */
	private enum Ty {

		/** A 64-bit integer ({@code i64}); also the domain of booleans (0/1). */
		INT,
		/** A 64-bit float ({@code f64}). */
		FLOAT,
		/**
		 * A string: an {@code i32} pointer to a linear-memory header
		 * {@code [len:i32 little-endian][len UTF-8 bytes]}.
		 */
		STRING,
		/**
		 * A packed {@code f64} vector (the {@code double-float} array element type): an
		 * {@code i32} pointer to a linear-memory header {@code [count:i32 little-endian]
		 * [count f64 little-endian]}. A distinct reference kind from {@code STRING} even
		 * though both are {@code i32} pointers -- types are static, so no runtime
		 * discriminator is needed. Rank-1 only; a rank-2 array is the separate
		 * {@link #F64MAT} kind (rank >= 3 stays a clear compile error).
		 */
		F64VEC,
		/**
		 * A packed {@code f32} vector (the {@code single-float} array element type): an
		 * {@code i32} pointer to a linear-memory header {@code [count:i32 little-endian]
		 * [count f32 little-endian]}. Same shape as {@link #F64VEC} but a 4-byte element
		 * stride (half the width), so half the memory and twice the SIMD lanes
		 * ({@code f32x4} vs {@code f64x2}). Scalars stay {@code f64}: a read widens
		 * {@code f32 -> f64} ({@code f64.promote_f32}), a write narrows
		 * {@code f64 -> f32} ({@code f32.demote_f64}). A distinct kind from
		 * {@code F64VEC} -- a value cannot be both widths -- so mixing the two is a type
		 * error (like the other reference kinds).
		 */
		F32VEC,
		/**
		 * A packed rank-2 {@code f64} matrix: an {@code i32} pointer to a linear-memory
		 * header {@code [rows:i32 little-endian][cols:i32 little-endian][rows*cols f64
		 * row-major]}. The dims live in the block header because this backend has no GC
		 * struct to carry them (the wasm-GC {@code $farray} keeps {@code dims} on the GC
		 * heap). A distinct kind from {@link #F64VEC}: the rank is static, so a rank-1
		 * vector keeps its {@code [count][data]} layout byte-identical and no runtime
		 * rank discriminator is needed. Built only by a rank-2 {@code make-array}; read
		 * by two-subscript {@code aref}/{@code aset}, flat {@code row-major-aref}/
		 * {@code %row-major-aset} and the {@code vec:matvec} GEMV kernel (the reason
		 * rank-2 exists here at all).
		 */
		F64MAT,
		/**
		 * A packed rank-2 {@code f32} matrix: same {@code [rows][cols][data]} shape as
		 * {@link #F64MAT} with the 4-byte {@code f32} stride, the matrix analog of
		 * {@link #F32VEC}.
		 */
		F32MAT;

		/**
		 * The result type when this and another type are combined. {@code INT} doubles as
		 * the inference bottom, so a not-yet-seen slot yields to whatever concrete kind
		 * it first meets. {@code FLOAT}, {@code STRING} and {@code F64VEC} are mutually
		 * incompatible (a value cannot be more than one of number / string /
		 * float-vector), which is a genuine type error.
		 */
		Ty join(Ty other) {
			if (this == other) {
				return this;
			}
			if (this == INT) {
				return other;
			}
			if (other == INT) {
				return this;
			}
			throw new UnsupportedOperationException("--no-gc: incompatible types " + this + " and " + other
					+ " (a value cannot be more than one of number / string / float-vector)");
		}

		/**
		 * The wasm value type byte (also the {@code if}/{@code block} result blocktype).
		 * Both reference kinds ({@code STRING}, {@code F64VEC}) are linear-memory
		 * pointers, so both are {@code i32}.
		 */
		int valType() {
			return switch (this) {
				case INT -> Type.I64.code();
				case FLOAT -> Type.F64.code();
				case STRING, F64VEC, F32VEC, F64MAT, F32MAT -> Type.I32.code();
			};
		}

	}

	/**
	 * Operators handled directly as primitive numeric/boolean/bitwise/string operations.
	 * Characters have no separate runtime type here: a character IS its code point (an
	 * INT), so {@code char} returns the code, {@code char-code}/{@code code-char} are
	 * identities and {@code char=} is a numeric comparison -- the portable
	 * {@code (char= (char s i) #\x)} idiom behaves exactly like the other backends.
	 */
	private static final Set<String> BUILTINS = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV,
			LispNames.MOD, LispNames.REM, LispNames.ABS, LispNames.MIN, LispNames.MAX, LispNames.FLOAT,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND, LispNames.EQ, LispNames.LT,
			LispNames.LE, LispNames.GT, LispNames.GE, LispNames.NOT, LispNames.SQRT, LispNames.LOGAND, LispNames.LOGIOR,
			LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.CONCATENATE, LispNames.LENGTH,
			LispNames.SUBSEQ, LispNames.STRING_EQ, LispNames.CHAR, LispNames.CHAR_CODE, LispNames.CODE_CHAR,
			LispNames.CHAR_EQ, LispNames.PRINC_TO_STRING);

	/**
	 * The packed double-float array operators (F64VEC). Like {@link #BUILTINS} they
	 * evaluate all their arguments, but they operate on / produce a linear-memory vector
	 * rather than a scalar; kept separate only for readability.
	 */
	private static final Set<String> ARRAY_OPS = Set.of(LispNames.AREF, LispNames.ROW_MAJOR_AREF, LispNames.ASET,
			LispNames.ROW_MAJOR_ASET);

	private final OptimizeLevel optimize;

	/**
	 * Whether to accelerate the vectorizable {@code vec:} kernels with WASM fixed-width
	 * SIMD (v128 {@code f64x2}/{@code f32x4}). When {@code false} (the default) the
	 * kernels lower to plain scalar linear-memory loops that run on any MVP runtime
	 * <em>without</em> the SIMD proposal; when {@code true} (the CLI's
	 * {@code --no-gc --simd}) they lower to native v128. The {@code [count][data]} block
	 * layout is byte-identical either way -- only the loop body differs -- so a scalar
	 * and a v128 module compute the same result over the same memory (element-wise ops
	 * bit-for-bit; reductions modulo summation order). This SIMD switch is the one
	 * orthogonal acceleration flag shared by every backend, independent of the non-GC
	 * value model. See {@code .kb/vec.md}.
	 */
	private final boolean simd;

	/**
	 * Whether to wrap the finished MVP core module as a reactor-style WASM component (the
	 * CLI's {@code --no-gc --component}): every scalar export is additionally exposed as
	 * a typed component-model export via a synchronous {@code canon lift}
	 * ({@link NoGcWasmComponentBuilder}), callable with
	 * {@code wasmtime run --invoke 'name(args)'} and no extra flags. The wrap is a pure
	 * post stage -- the core module inside the component is byte-identical to the
	 * non-component output (a printing program's single {@code fd_write} import is
	 * satisfied by the fixed print micro-adapter modules the wrap wires in) -- but it
	 * narrows the surface: export names must match the component-model {@code label}
	 * grammar and {@code :async} is rejected.
	 */
	private final boolean component;

	/** Creates a new non-GC WASM compiler (no optimize, scalar {@code vec:} kernels). */
	public NoGcWasmCompiler() {
		this(OptimizeLevel.NONE, false);
	}

	/**
	 * Creates a new non-GC WASM compiler with scalar (non-SIMD) {@code vec:} kernels.
	 * @param optimize what to optimize the module FOR (the CLI's {@code --optimize}).
	 * Every level but {@link OptimizeLevel#NONE} runs the finished module through
	 * {@link am.ik.wasm.WasmTreeShaker} so anything unreachable from the exports is
	 * dropped and the survivors renumbered. The shaker is GC-agnostic, so it composes
	 * with the non-GC module shape for free. {@link OptimizeLevel#SIZE} is accepted and
	 * equals {@link OptimizeLevel#DEFAULT} here: this lowering is i64-native, so it never
	 * emits the boxed/unboxed pair the level declines on wasm-GC.
	 */
	public NoGcWasmCompiler(OptimizeLevel optimize) {
		this(optimize, false);
	}

	/**
	 * Creates a new non-GC WASM compiler.
	 * @param optimize see {@link #NoGcWasmCompiler(OptimizeLevel)}
	 * @param simd when {@code true}, the vectorizable {@code vec:} kernels lower to
	 * native WASM v128 SIMD ({@code f64x2}/{@code f32x4}); when {@code false} they lower
	 * to scalar linear-memory loops that need no SIMD proposal. This is the
	 * {@code --simd} switch wired on the {@code --no-gc} backend, orthogonal to the
	 * memory model.
	 */
	public NoGcWasmCompiler(OptimizeLevel optimize, boolean simd) {
		this(optimize, simd, false);
	}

	/**
	 * Creates a new non-GC WASM compiler.
	 * @param optimize see {@link #NoGcWasmCompiler(OptimizeLevel)}
	 * @param simd when {@code true}, the vectorizable {@code vec:} kernels lower to
	 * native WASM v128 SIMD ({@code f64x2}/{@code f32x4}); when {@code false} they lower
	 * to scalar linear-memory loops that need no SIMD proposal.
	 * @param component when {@code true}, wrap the module as a reactor-style WASM
	 * component whose scalar exports are typed component-model exports (the CLI's
	 * {@code --no-gc --component}); export names must be lower-kebab-case and
	 * {@code :async} is rejected
	 */
	public NoGcWasmCompiler(OptimizeLevel optimize, boolean simd, boolean component) {
		this.optimize = optimize;
		this.simd = simd;
		this.component = component;
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

	@Override
	public byte[] compile(List<LispVal> program) {
		// Resolve packages first, like the other backends, so qualified names
		// (rontolisp:wasm-export) and in-package directives are canonical.
		program = new PackageResolver().resolveProgram(program);
		// Splice top-level (progn ...)/(eval-when ...) so nested defuns are collected,
		// like the other backends.
		program = LispMacroExpander.flattenTopLevel(program);

		// The async/await surface never: this backend has no futures, no suspension
		// and no boxed values to represent them, so each name gets the clear error.
		// The future-as-value combinators (then/then*/catch/finally) come FIRST so a
		// user's rl:then program reports the combinator by name, not the async-lambda
		// the prelude splice would have injected downstream.
		for (String asyncName : List.of(LispNames.THEN_QUALIFIED, LispNames.THEN_STAR_QUALIFIED,
				LispNames.CATCH_QUALIFIED, LispNames.FINALLY_QUALIFIED, LispNames.ASYNC_QUALIFIED,
				LispNames.ASYNC_DEFUN_QUALIFIED, LispNames.ASYNC_LAMBDA_QUALIFIED, LispNames.AWAIT_QUALIFIED,
				LispNames.ASYNC_RUN_QUALIFIED, PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.FUTUREP),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.ASYNC_STREAMP),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_STREAM),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_READ),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_WRITE),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.STREAM_CLOSE),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.READ_ALL),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WAIT_FOR))) {
			if (referencesSymbol(program, asyncName)) {
				throw new UnsupportedOperationException(
						asyncName + " is not supported with --no-gc (use the default GC backend)");
			}
		}

		// Collect defuns and export directives. A --no-gc module is a pure-compute
		// reactor, so only function definitions and export directives are allowed at top
		// level; a stray expression would need a top-level init body (and most likely
		// I/O), which scalar mode does not support.
		Map<String, Defun> defuns = new HashMap<>();
		List<WasmExportCompiler.Decl> exportDecls = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				Defun d = extractDefun(LispMacroExpander.expandDefun(cons));
				defuns.put(d.name(), d);
			}
			else if (WasmExportCompiler.isExportForm(expr)) {
				exportDecls.add(WasmExportCompiler.parse((LispCons) expr));
			}
			else if (WasmImportCompiler.isImportForm(expr)) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-import is not supported with --no-gc (use the default GC backend)");
			}
			else {
				throw new UnsupportedOperationException("--no-gc supports only (defun ...) and "
						+ "(rontolisp:wasm-export ...) at top level, got: " + expr.print());
			}
		}
		if (exportDecls.isEmpty()) {
			throw new UnsupportedOperationException(
					"--no-gc requires at least one (rontolisp:wasm-export ...) directive (there is nothing to export)");
		}

		// Validate every export against its defun: scalar boundary types only, the named
		// function must be a top-level defun, and the arity must match.
		for (WasmExportCompiler.Decl decl : exportDecls) {
			validateScalarTypes(decl);
			if (this.component) {
				validateComponentExport(decl);
			}
			Defun target = defuns.get(decl.name());
			if (target == null) {
				throw new UnsupportedOperationException("rontolisp:wasm-export names an unknown function "
						+ "(must be a top-level defun): " + decl.name());
			}
			if (target.params().size() != decl.paramTypes().size()) {
				throw new UnsupportedOperationException("rontolisp:wasm-export arity mismatch for '" + decl.name()
						+ "': defun takes " + target.params().size() + " parameter(s) but :params declares "
						+ decl.paramTypes().size());
			}
		}

		// Determine the reachable, eligible functions and assign each a stable index in
		// discovery (BFS) order. collectCalls both validates eligibility (throwing on an
		// unsupported op / free variable) and reports the callees, so an unreached
		// ineligible defun is simply never visited.
		LinkedHashMap<String, Integer> index = new LinkedHashMap<>();
		Deque<String> work = new ArrayDeque<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			enqueue(decl.name(), index, work);
		}
		List<String> reachable = new ArrayList<>();
		while (!work.isEmpty()) {
			String name = work.poll();
			reachable.add(name);
			Defun defun = Objects.requireNonNull(defuns.get(name));
			Set<String> callees = new LinkedHashSet<>();
			collectCalls(progn(defun.body()), new HashSet<>(defun.params()), defuns, callees, name);
			for (String callee : callees) {
				if (!defuns.containsKey(callee)) {
					throw new UnsupportedOperationException(
							"--no-gc: call to undefined function '" + callee + "' in '" + name + "'");
				}
				enqueue(callee, index, work);
			}
		}

		// Infer the i64/f64/i32 type of every parameter, local and return value.
		Types types = inferTypes(reachable, defuns, exportDecls);

		// Lay out string literals in linear memory and decide whether the module needs
		// the
		// memory/allocator machinery at all (only when a string literal or a :string
		// boundary type is present).
		int internalCount = reachable.size();
		Mem mem = planMemory(reachable, defuns, exportDecls, internalCount, types);

		// Internal functions occupy indices 0..N-1; the emitted wrappers follow in
		// export-directive order; the memory helpers (when present) come after the
		// wrappers. An export whose host signature already matches the internal
		// function exactly and needs no heap reset is a pure pass-through: no wrapper
		// is emitted and the export names the internal function directly.
		List<byte[]> internalBodies = new ArrayList<>();
		for (String name : reachable) {
			internalBodies.add(compileDefunBody(Objects.requireNonNull(defuns.get(name)), name, types, index, mem));
		}
		List<byte[]> wrapperBodies = new ArrayList<>();
		int[] wrapperOrdinals = new int[exportDecls.size()];
		int[] exportOrdinals = new int[exportDecls.size()];
		for (int j = 0; j < exportDecls.size(); j++) {
			WasmExportCompiler.Decl decl = exportDecls.get(j);
			int target = Objects.requireNonNull(index.get(decl.name()));
			if (isPassThroughExport(decl, types, mem)) {
				wrapperOrdinals[j] = -1;
				exportOrdinals[j] = target;
			}
			else {
				wrapperOrdinals[j] = wrapperBodies.size();
				exportOrdinals[j] = internalCount + wrapperBodies.size();
				wrapperBodies.add(compileWrapperBody(decl, target, types, mem));
			}
		}

		byte[] module = assemble(reachable, internalBodies, exportDecls, wrapperBodies, wrapperOrdinals, exportOrdinals,
				internalCount, types, mem);
		if (this.optimize.eliminatesDeadCode()) {
			module = am.ik.wasm.WasmTreeShaker.shake(module);
		}
		if (this.component) {
			// Post-stage wrap: the core module is byte-identical to the non-component
			// output and the component just aliases + lifts its exports; a :string
			// boundary additionally aliases the canonical string ABI helpers appended by
			// assemble() above, and a printing program (the print micro-adapter) wires
			// the fixed shim/bridge/fixup modules implementing the fd_write import over
			// WASI 0.3 (async lifts + the blocking waitable-set park).
			this.componentWit = WitEmitter
				.emit(mem.printUsed() ? WitEmitter.VARIANT_NOGC_PRINT : WitEmitter.VARIANT_NOGC, exportDecls);
			return NoGcWasmComponentBuilder.build(module, exportDecls, mem.printUsed());
		}
		return module;
	}

	/**
	 * Validates an export directive against the {@code --component} constraints: a
	 * lower-kebab-case export name (the component-model {@code label} grammar).
	 * {@code :string} lifts through the canonical string ABI; {@code :s-expr} is already
	 * rejected for every {@code --no-gc} output by {@link #validateScalarTypes}.
	 * @param decl the parsed export directive
	 */
	private static void validateComponentExport(WasmExportCompiler.Decl decl) {
		if (!WasmExportCompiler.COMPONENT_EXPORT_NAME.matcher(decl.exportName()).matches()) {
			throw new UnsupportedOperationException("rontolisp:wasm-export name '" + decl.exportName()
					+ "' is not a valid component-model export name (lower-kebab-case words, e.g."
					+ " \"sum-squared\"); rename it with :as \"kebab-name\"");
		}
		// :async is the GC component's opt-in for I/O inside an export; here it is not a
		// user-level knob: a printing program's exports become async lifts automatically
		// (the print bridge's blocking park needs an async-typed task) and every other
		// I/O op is a compile error, so an explicit :async request is a clear error
		// rather than a silently-ignored option.
		if (decl.async()) {
			throw new UnsupportedOperationException("rontolisp:wasm-export :async is not supported with --no-gc"
					+ " --component for '" + decl.name() + "' (a printing program's exports are lifted async"
					+ " automatically; every other I/O op is rejected at compile time)");
		}
	}

	private static void enqueue(String name, Map<String, Integer> index, Deque<String> work) {
		if (!index.containsKey(name)) {
			index.put(name, index.size());
			work.add(name);
		}
	}

	// --- Type inference ----------------------------------------------------------------

	/**
	 * Inferred types of every reachable function: the {@code i64}/{@code f64} type of
	 * each parameter, each local ({@code let}/{@code do} binding, by name within the
	 * function) and the return value.
	 */
	private record Types(Map<String, Ty[]> params, Map<String, Ty> returns, Map<String, Map<String, Ty>> locals) {
	}

	// Receives the callee name and its argument types at a call site, so a fixpoint pass
	// can widen the callee's parameter types.
	private interface CallSink {

		void record(String callee, Ty[] argTypes);

	}

	// Threads the read-only context of a type walk: which function it is in (so locals
	// can
	// be looked up / widened), its parameter names (params keep a fixed type; locals
	// widen), the running Types, the optional call sink, whether the walk may widen the
	// inferred local/return types (inference vs a frozen compile-time query), a shared
	// "something widened" flag and the stack of %block result-type accumulators (each a
	// one-element mutable box joined by every enclosing return).
	private final class TC {

		final String fn;

		final Set<String> params;

		final Types types;

		final @Nullable CallSink sink;

		final boolean widen;

		final boolean[] changed;

		final Deque<Ty[]> blockReturns = new ArrayDeque<>();

		TC(String fn, Set<String> params, Types types, @Nullable CallSink sink, boolean widen, boolean[] changed) {
			this.fn = fn;
			this.params = params;
			this.types = types;
			this.sink = sink;
			this.widen = widen;
			this.changed = changed;
		}

		Map<String, Ty> locals() {
			return Objects.requireNonNull(this.types.locals().get(this.fn));
		}

	}

	private Types inferTypes(List<String> reachable, Map<String, Defun> defuns,
			List<WasmExportCompiler.Decl> exportDecls) {
		// Parameters of an exported function are pinned to the boundary designator (the
		// host passes them in); every other parameter type, every local type and every
		// return type starts at INT (bottom) and is only ever widened to FLOAT, so the
		// fixpoint is monotone and terminates.
		Map<String, Ty[]> boundary = new HashMap<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			Ty[] pinned = new Ty[decl.paramTypes().size()];
			for (int i = 0; i < pinned.length; i++) {
				pinned[i] = boundaryTy(decl.paramTypes().get(i));
			}
			boundary.put(decl.name(), pinned);
		}

		Map<String, Ty[]> params = new HashMap<>();
		Map<String, Ty> returns = new HashMap<>();
		Map<String, Map<String, Ty>> locals = new HashMap<>();
		for (String name : reachable) {
			Defun d = Objects.requireNonNull(defuns.get(name));
			params.put(name, boundary.containsKey(name) ? boundary.get(name).clone() : filled(d.params().size()));
			returns.put(name, Ty.INT);
			locals.put(name, new HashMap<>());
		}
		Types types = new Types(params, returns, locals);

		boolean[] changed = { true };
		while (changed[0]) {
			changed[0] = false;
			// Re-derive non-pinned parameter types from scratch each pass (seeded INT, or
			// pinned for exports), accumulating the join of every call site's argument
			// types; local and return types accumulate in place (they only widen).
			Map<String, Ty[]> nextParams = new HashMap<>();
			for (String name : reachable) {
				int arity = Objects.requireNonNull(defuns.get(name)).params().size();
				nextParams.put(name, boundary.containsKey(name) ? boundary.get(name).clone() : filled(arity));
			}
			CallSink sink = (callee, argTypes) -> {
				if (boundary.containsKey(callee)) {
					return; // pinned by the boundary; the caller coerces instead
				}
				Ty[] pt = nextParams.get(callee);
				if (pt != null) {
					for (int i = 0; i < pt.length && i < argTypes.length; i++) {
						pt[i] = pt[i].join(argTypes[i]);
					}
				}
			};
			for (String name : reachable) {
				Defun d = Objects.requireNonNull(defuns.get(name));
				TC tc = new TC(name, new HashSet<>(d.params()), types, sink, true, changed);
				Ty rt = typeOf(progn(d.body()), paramEnv(d, params), tc);
				Ty merged = Objects.requireNonNull(returns.get(name)).join(rt);
				if (merged != returns.get(name)) {
					returns.put(name, merged);
					changed[0] = true;
				}
			}
			for (String name : reachable) {
				if (!Arrays.equals(nextParams.get(name), params.get(name))) {
					params.put(name, Objects.requireNonNull(nextParams.get(name)));
					changed[0] = true;
				}
			}
		}
		return types;
	}

	private static Ty[] filled(int n) {
		Ty[] arr = new Ty[n];
		Arrays.fill(arr, Ty.INT);
		return arr;
	}

	// The internal value type a boundary designator pins a parameter to: :float -> FLOAT,
	// :string -> STRING, every integer designator and :bool -> INT (the house i64).
	private static Ty boundaryTy(BoundaryType designator) {
		return switch (designator) {
			case FLOAT -> Ty.FLOAT;
			case STRING -> Ty.STRING;
			default -> Ty.INT;
		};
	}

	private static Map<String, Ty> paramEnv(Defun d, Map<String, Ty[]> params) {
		Map<String, Ty> env = new HashMap<>();
		Ty[] pt = Objects.requireNonNull(params.get(d.name()));
		for (int i = 0; i < d.params().size(); i++) {
			env.put(d.params().get(i), pt[i]);
		}
		return env;
	}

	// Widens m[k] by joining in t; records on the shared flag when the type actually
	// grows. Returns the (possibly widened) current type.
	private static Ty widenLocal(Map<String, Ty> m, String k, Ty t, boolean[] changed) {
		Ty old = m.get(k);
		Ty next = old == null ? t : old.join(t);
		if (next != old) {
			m.put(k, next);
			changed[0] = true;
		}
		return next;
	}

	// Infers the type of an expression. The env maps every currently-visible local (by
	// name) to its type; binding forms thread a child env. In inference mode (tc.widen)
	// let/do bindings and setq targets widen the function's persisted local-type map and
	// call sites are reported to the sink; in a frozen compile-time query both are
	// read-only. Assumes the expression already passed collectCalls (well-formed,
	// eligible).
	private Ty typeOf(LispVal expr, Map<String, Ty> env, TC tc) {
		return switch (expr) {
			case LispInteger ignored -> Ty.INT;
			case LispDouble ignored -> Ty.FLOAT;
			case LispString ignored -> Ty.STRING;
			case am.ik.rontolisp.LispSingleFloatArray ignored -> Ty.F32VEC;
			case LispFloatArray ignored -> Ty.F64VEC;
			case LispChar ignored -> Ty.INT;
			case LispTrue ignored -> Ty.INT;
			case LispNil ignored -> Ty.INT;
			case LispSymbol sym -> env.getOrDefault(sym.name(), Ty.INT);
			case LispCons cons -> typeOfCall(cons, env, tc);
			default -> Ty.INT;
		};
	}

	private Ty typeOfCall(LispCons cons, Map<String, Ty> env, TC tc) {
		String name = ((LispSymbol) cons.car()).name();
		List<LispVal> args = cons.toList();
		int argc = args.size() - 1;

		LispVal expanded = expandMacro(name, cons, argc);
		if (expanded != null) {
			return typeOf(expanded, env, tc);
		}

		if (isSimdCall(name)) {
			return typeOfSimd(name, args, env, tc);
		}

		switch (name) {
			case LispNames.IF -> {
				typeOf(args.get(1), env, tc);
				Ty thenTy = typeOf(args.get(2), env, tc);
				Ty elseTy = args.size() > 3 ? typeOf(args.get(3), env, tc) : Ty.INT;
				return thenTy.join(elseTy);
			}
			case LispNames.PROGN -> {
				Ty last = Ty.INT;
				for (int i = 1; i < args.size(); i++) {
					last = typeOf(args.get(i), env, tc);
				}
				return last;
			}
			case LispNames.LET -> {
				return typeOfLet(cons, env, tc);
			}
			case LispNames.SETQ -> {
				return typeOfSetq(args, env, tc);
			}
			case LispNames.WHILE -> {
				// while always yields nil (INT 0); still walk the test/body/steps so
				// their
				// call sites and local mutations are recorded.
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.INT;
			}
			case LispNames.BLOCK_INTERNAL -> {
				// %block result = join(normal completion, every (return v) inside it).
				Ty[] box = { Ty.INT };
				tc.blockReturns.push(box);
				Ty bodyTy = Ty.INT;
				for (int i = 1; i < args.size(); i++) {
					bodyTy = typeOf(args.get(i), env, tc);
				}
				tc.blockReturns.pop();
				return bodyTy.join(box[0]);
			}
			case LispNames.RETURN -> {
				Ty vt = args.size() > 1 ? typeOf(args.get(1), env, tc) : Ty.INT;
				Ty[] box = tc.blockReturns.peek();
				if (box != null) {
					box[0] = box[0].join(vt);
				}
				return vt;
			}
			// Float division, (float x) and sqrt are always FLOAT.
			case LispNames.DIV, LispNames.FLOAT, LispNames.SQRT -> {
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.FLOAT;
			}
			// (concatenate 'string ...) is always a STRING; walk the string operands (the
			// first argument is the 'string result-type designator).
			case LispNames.CONCATENATE -> {
				for (int i = 2; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.STRING;
			}
			// subseq and princ-to-string always yield a string.
			case LispNames.SUBSEQ, LispNames.PRINC_TO_STRING -> {
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.STRING;
			}
			// print/princ return their argument (like the interpreter); terpri yields
			// nil.
			case LispNames.PRINT, LispNames.PRINC -> {
				return typeOf(args.get(1), env, tc);
			}
			case LispNames.TERPRI -> {
				return Ty.INT;
			}
			// with-arena yields its body's value (a progn with a reclamation boundary).
			case LispNames.WITH_ARENA_QUALIFIED -> {
				Ty last = Ty.INT;
				for (int i = 2; i < args.size(); i++) {
					last = typeOf(args.get(i), env, tc);
				}
				return last;
			}
			// A quoted datum (e.g. a make-array dimension list '(2 3) or an :element-type
			// 'double-float designator) is not a runtime value on the scalar backend, so
			// it
			// carries no meaningful type; return the INT bottom WITHOUT recursing into
			// the
			// quoted structure (walking '(2 3) as a call would treat 2 as an operator).
			case LispNames.QUOTE -> {
				return Ty.INT;
			}
			// A packed float array literal or (make-array ... :element-type ...) is a
			// F64VEC (double-float) or F32VEC (single-float), keyed off the :element-type
			// designator -- or the matrix kind of the same width for a rank-2 dimension
			// spec. The dimension argument is walked per dimension EXPRESSION (a (list d
			// n) form must not be walked as a call to the unsupported `list`). aref /
			// row-major-aref read a f64 element (a f32 element is widened on read); %aset
			// / %row-major-aset return the (coerced) f64 value they stored.
			case LispNames.MAKE_ARRAY -> {
				List<LispVal> dims = args.size() > 1 ? dimExprs(args.get(1)) : List.of();
				for (LispVal dim : dims) {
					typeOf(dim, env, tc);
				}
				for (int i = 2; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				boolean single = isSingleFloatElementType(findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD));
				if (dims.size() == 2) {
					return single ? Ty.F32MAT : Ty.F64MAT;
				}
				return single ? Ty.F32VEC : Ty.F64VEC;
			}
			case LispNames.AREF, LispNames.ROW_MAJOR_AREF, LispNames.ASET, LispNames.ROW_MAJOR_ASET -> {
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.FLOAT;
			}
			// Comparisons, predicates, not, the bitwise operators and the string/char
			// accessors (a character is its code point) yield an integer.
			case LispNames.EQ, LispNames.LT, LispNames.LE, LispNames.GT, LispNames.GE, LispNames.NOT, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.LENGTH,
					LispNames.STRING_EQ, LispNames.CHAR, LispNames.CHAR_CODE, LispNames.CODE_CHAR,
					LispNames.CHAR_EQ -> {
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.INT;
			}
			// The rounding conversions return an integer.
			case LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND -> {
				typeOf(args.get(1), env, tc);
				return Ty.INT;
			}
			// +,-,*,mod,rem,abs,min,max are FLOAT iff any operand is FLOAT.
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM, LispNames.ABS,
					LispNames.MIN, LispNames.MAX -> {
				Ty t = Ty.INT;
				for (int i = 1; i < args.size(); i++) {
					t = t.join(typeOf(args.get(i), env, tc));
				}
				return t;
			}
			default -> {
				// A call to another eligible function.
				Ty[] argTypes = new Ty[argc];
				for (int i = 0; i < argc; i++) {
					argTypes[i] = typeOf(args.get(i + 1), env, tc);
				}
				if (tc.sink != null) {
					tc.sink.record(name, argTypes);
				}
				Ty rt = tc.types.returns().get(name);
				return rt == null ? Ty.INT : rt;
			}
		}
	}

	private Ty typeOfLet(LispCons cons, Map<String, Ty> env, TC tc) {
		List<LispVal> parts = cons.toList();
		List<LispVal> bindings = parts.get(1) instanceof LispCons bc ? bc.toList() : List.of();
		Map<String, Ty> inner = new HashMap<>(env);
		for (LispVal binding : bindings) {
			String varName;
			LispVal init;
			if (binding instanceof LispSymbol s) {
				varName = s.name();
				init = LispNil.INSTANCE;
			}
			else {
				List<LispVal> bp = ((LispCons) binding).toList();
				varName = ((LispSymbol) bp.get(0)).name();
				init = bp.size() > 1 ? bp.get(1) : LispNil.INSTANCE;
			}
			// Parallel `let`: initializers see the outer scope only.
			Ty initTy = typeOf(init, env, tc);
			Ty bindTy = tc.widen ? widenLocal(tc.locals(), varName, initTy, tc.changed)
					: tc.locals().getOrDefault(varName, initTy).join(initTy);
			inner.put(varName, bindTy);
		}
		Ty last = Ty.INT;
		for (int i = 2; i < parts.size(); i++) {
			last = typeOf(parts.get(i), inner, tc);
		}
		return last;
	}

	private Ty typeOfSetq(List<LispVal> args, Map<String, Ty> env, TC tc) {
		Ty last = Ty.INT;
		int pairs = (args.size() - 1) / 2;
		for (int p = 0; p < pairs; p++) {
			String var = ((LispSymbol) args.get(1 + 2 * p)).name();
			Ty rhsTy = typeOf(args.get(2 + 2 * p), env, tc);
			if (tc.params.contains(var)) {
				// A parameter has a fixed wasm type; the assignment coerces to it.
				last = env.getOrDefault(var, Ty.INT);
			}
			else {
				Ty next = tc.widen ? widenLocal(tc.locals(), var, rhsTy, tc.changed)
						: tc.locals().getOrDefault(var, rhsTy).join(rhsTy);
				env.put(var, next);
				last = next;
			}
		}
		return last;
	}

	// A frozen, read-only type query used during code generation. fn.localTypes already
	// holds the final inferred type of every in-scope local, so a fresh env copy is
	// enough.
	private Ty staticType(LispVal expr, Fn fn) {
		TC tc = new TC(fn.fnName, fn.paramNames, fn.types, null, false, new boolean[1]);
		return typeOf(expr, new HashMap<>(fn.localTypes), tc);
	}

	// --- Linear-memory layout (strings) ------------------------------------------------

	/**
	 * The linear-memory plan for a module. {@code used} is false for a pure-numeric
	 * program (no strings), in which case no memory/global/data section and no helper
	 * functions are emitted (so the module stays byte-identical to the original scalar
	 * output). {@code printUsed} is true only when a reachable body calls a printing op
	 * ({@code print}/{@code princ}/{@code terpri}); only then does the module import
	 * {@code wasi_snapshot_preview1.fd_write} (shifting every function index by
	 * {@code funcBase} = 1) and emit the {@code __write_stdout} funnel -- a print-free
	 * program keeps zero imports and stays byte-identical, so the {@code --component}
	 * wrap needs no adapter for it.
	 *
	 * @param literals string-literal content to its header address in the data segment
	 * @param data the static data-segment bytes (laid out from {@code dataBase})
	 * @param dataBase the memory address at which {@code data} is placed
	 * @param heapBase the initial bump-allocator pointer (just past the static data)
	 * @param iovAddr the address of the 16-byte fd_write scratch (iovec at
	 * {@code iovAddr}, nwritten cell at {@code iovAddr + 8}); 0 when print is unused
	 * @param funcBase the offset the fd_write import adds to every function index (1 when
	 * {@code printUsed}, else 0); all stored helper indices already include it
	 * @param allocIndex the function index of the {@code __alloc} bump allocator
	 * @param memcpyIndex the function index of the {@code __memcpy} byte-copy helper
	 * @param streqIndex the function index of the {@code __streq} string-compare helper
	 * @param itoaIndex the function index of the {@code __itoa} integer-to-string helper
	 * @param markIndex the function index of the {@code __ronto_alloc_mark}
	 * arena-snapshot export
	 * @param resetIndex the function index of the {@code __ronto_alloc_reset}
	 * arena-restore export
	 * @param ftoaIndex the function index of the {@code __ftoa} float-to-string helper
	 * (-1 when no float is rendered)
	 * @param writeStdoutIndex the function index of the {@code __write_stdout} funnel,
	 * the sole caller of the fd_write import (-1 when print is unused)
	 * @param used whether the module uses linear memory at all
	 * @param printUsed whether the module prints (fd_write import + __write_stdout)
	 * @param ftoaUsed whether the module renders a float to text (__ftoa)
	 */
	private record Mem(Map<String, Integer> literals, byte[] data, int dataBase, int heapBase, int iovAddr,
			int funcBase, int allocIndex, int memcpyIndex, int streqIndex, int itoaIndex, int markIndex, int resetIndex,
			int ftoaIndex, int writeStdoutIndex, boolean used, boolean printUsed, boolean ftoaUsed) {

		/**
		 * The absolute function index of an internal function / export wrapper given its
		 * local ordinal (BFS discovery order; wrappers follow the internals). Keeps the
		 * fd_write import's index shift behind one accessor so no call site hardcodes it.
		 */
		int funcIndex(int localOrdinal) {
			return this.funcBase + localOrdinal;
		}

		/** The function index of the imported {@code fd_write} (valid iff printUsed). */
		int fdWriteIndex() {
			return 0;
		}

	}

	private static final int STR_DATA_BASE = 8;

	private Mem planMemory(List<String> reachable, Map<String, Defun> defuns, List<WasmExportCompiler.Decl> exportDecls,
			int internalCount, Types types) {
		// Gather every string literal in every reachable body (deterministic order), then
		// lay each out as a 4-byte-aligned [len:i32 LE][bytes] header.
		LinkedHashSet<String> literals = new LinkedHashSet<>();
		for (String name : reachable) {
			collectLiterals(progn(Objects.requireNonNull(defuns.get(name)).body()), literals);
		}
		// Printing: print/princ/terpri gate the fd_write import and the __write_stdout
		// funnel; a rendered FLOAT (print/princ/princ-to-string of a f64) additionally
		// gates the __ftoa helper. Both add their fixed text fragments to the literal
		// pool ONLY when used, so a print-free module keeps its exact bytes.
		boolean printUsed = false;
		for (String name : reachable) {
			if (usesPrintOp(progn(Objects.requireNonNull(defuns.get(name)).body()))) {
				printUsed = true;
				break;
			}
		}
		boolean ftoaUsed = false;
		for (String name : reachable) {
			if (rendersFloat(name, Objects.requireNonNull(defuns.get(name)), types)) {
				ftoaUsed = true;
				break;
			}
		}
		if (printUsed) {
			literals.add("\n");
			literals.add("\"");
			// The single-escape byte print emits before an embedded " / \ (todo 216).
			literals.add("\\");
			literals.add("T");
			literals.add("NIL");
		}
		if (ftoaUsed) {
			// __ftoa returns these header pointers directly for the IEEE specials.
			literals.add("NaN");
			literals.add("Infinity");
			literals.add("-Infinity");
		}
		LinkedHashMap<String, Integer> offsets = new LinkedHashMap<>();
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		int cursor = STR_DATA_BASE;
		for (String s : literals) {
			while ((cursor & 3) != 0) {
				data.write(0);
				cursor++;
			}
			offsets.put(s, cursor);
			byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			writeI32LE(data, bytes.length);
			data.write(bytes, 0, bytes.length);
			cursor += 4 + bytes.length;
		}
		int heapBase = (cursor + 7) & ~7;
		// The fd_write scratch (iovec + nwritten) sits between the static data and the
		// bump heap, present only when the module prints.
		int iovAddr = 0;
		if (printUsed) {
			iovAddr = heapBase;
			heapBase += 16;
		}

		boolean boundaryString = false;
		for (WasmExportCompiler.Decl decl : exportDecls) {
			if (decl.returnType() == BoundaryType.STRING || decl.paramTypes().contains(BoundaryType.STRING)) {
				boundaryString = true;
			}
		}
		// A body can produce a string without any literal or :string boundary (e.g.
		// (length (princ-to-string n)) on an :int export), so string-producing ops also
		// flag the memory as used.
		boolean stringOp = false;
		for (String name : reachable) {
			if (usesStringOp(progn(Objects.requireNonNull(defuns.get(name)).body()))) {
				stringOp = true;
				break;
			}
		}
		// A #d(...) literal or (make-array ... :element-type 'double-float) materializes
		// a
		// packed f64 vector in linear memory via the bump allocator, so it also flags the
		// memory as used even in an otherwise pure-numeric program.
		boolean floatVec = false;
		for (String name : reachable) {
			if (usesFloatArray(progn(Objects.requireNonNull(defuns.get(name)).body()))) {
				floatVec = true;
				break;
			}
		}
		// printUsed implies non-empty literals (the "\n" entry), so `used` follows.
		boolean used = !literals.isEmpty() || boundaryString || stringOp || floatVec;
		int funcBase = printUsed ? 1 : 0;
		// Helper indices assume one wrapper per export directive. That holds whenever
		// the helpers exist: pass-through wrapper elision requires !used, and a module
		// with used == false emits none of these helpers.
		int allocIndex = funcBase + internalCount + exportDecls.size();
		int memcpyIndex = allocIndex + 1;
		int streqIndex = memcpyIndex + 1;
		int itoaIndex = streqIndex + 1;
		// The host arena API __ronto_alloc_mark/_reset: two more exported functions over
		// the same heap-pointer global, appended after the four string helpers. --no-gc
		// has no fixed-index invariant, so appending is free (nothing renumbers).
		int markIndex = itoaIndex + 1;
		int resetIndex = markIndex + 1;
		// The printing helpers append after the arena pair, again gated.
		int ftoaIndex = ftoaUsed ? resetIndex + 1 : -1;
		int writeStdoutIndex = printUsed ? (ftoaUsed ? ftoaIndex + 1 : resetIndex + 1) : -1;
		return new Mem(offsets, data.toByteArray(), STR_DATA_BASE, heapBase, iovAddr, funcBase, allocIndex, memcpyIndex,
				streqIndex, itoaIndex, markIndex, resetIndex, ftoaIndex, writeStdoutIndex, used, printUsed, ftoaUsed);
	}

	/** The printing operators that gate the fd_write import. */
	private static final Set<String> PRINT_OPS = Set.of(LispNames.PRINT, LispNames.PRINC, LispNames.TERPRI);

	private static boolean usesPrintOp(LispVal v) {
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol s && PRINT_OPS.contains(s.name())) {
				return true;
			}
			return usesPrintOp(c.car()) || usesPrintOp(c.cdr());
		}
		return false;
	}

	/**
	 * Whether the function renders a float to text: a {@code print}/{@code princ}/
	 * {@code princ-to-string} whose argument's inferred static type is FLOAT. Queried
	 * against the final (frozen) inference result, so the gate agrees with the type the
	 * code generator will see at the call site.
	 */
	private boolean rendersFloat(String fnName, Defun d, Types types) {
		Map<String, Ty> env = paramEnv(d, types.params());
		env.putAll(Objects.requireNonNull(types.locals().get(fnName)));
		TC tc = new TC(fnName, new HashSet<>(d.params()), types, null, false, new boolean[1]);
		return rendersFloatWalk(progn(d.body()), env, tc);
	}

	private boolean rendersFloatWalk(LispVal v, Map<String, Ty> env, TC tc) {
		if (!(v instanceof LispCons c)) {
			return false;
		}
		if (c.car() instanceof LispSymbol s
				&& (PRINT_OPS.contains(s.name()) || LispNames.PRINC_TO_STRING.equals(s.name()))) {
			List<LispVal> args = c.toList();
			if (args.size() == 2 && typeOf(args.get(1), new HashMap<>(env), tc) == Ty.FLOAT) {
				return true;
			}
		}
		return rendersFloatWalk(c.car(), env, tc) || rendersFloatWalk(c.cdr(), env, tc);
	}

	/** String-producing operators that require linear memory even with no literal. */
	private static final Set<String> STRING_PRODUCING_OPS = Set.of(LispNames.CONCATENATE, LispNames.SUBSEQ,
			LispNames.PRINC_TO_STRING);

	private static boolean usesStringOp(LispVal v) {
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol s && STRING_PRODUCING_OPS.contains(s.name())) {
				return true;
			}
			return usesStringOp(c.car()) || usesStringOp(c.cdr());
		}
		return false;
	}

	/**
	 * Whether a body touches a packed f64 vector (a {@code #d} literal, a
	 * {@code make-array} call, or any {@code vec:} kernel), all of which read or
	 * bump-allocate the vector in linear memory and so require the memory section even in
	 * an otherwise pure-numeric program.
	 */
	private static boolean usesFloatArray(LispVal v) {
		if (v instanceof LispFloatArray) {
			return true;
		}
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol s && (LispNames.MAKE_ARRAY.equals(s.name()) || isSimdCall(s.name()))) {
				return true;
			}
			return usesFloatArray(c.car()) || usesFloatArray(c.cdr());
		}
		return false;
	}

	private static void collectLiterals(LispVal v, Set<String> out) {
		if (v instanceof LispString s) {
			out.add(s.value());
		}
		else if (v instanceof LispCons c) {
			collectLiterals(c.car(), out);
			collectLiterals(c.cdr(), out);
		}
	}

	private static void writeI32LE(ByteArrayOutputStream o, int v) {
		o.write(v & 0xff);
		o.write((v >> 8) & 0xff);
		o.write((v >> 16) & 0xff);
		o.write((v >> 24) & 0xff);
	}

	// --- Module assembly ---------------------------------------------------------------

	private byte[] assemble(List<String> reachable, List<byte[]> internalBodies,
			List<WasmExportCompiler.Decl> exportDecls, List<byte[]> wrapperBodies, int[] wrapperOrdinals,
			int[] exportOrdinals, int internalCount, Types types, Mem mem) {
		// The local (non-imported) function count: internals, the emitted wrappers
		// (pass-through exports have none and name their internal function directly),
		// then the six memory helpers (when memory is used), then __ftoa /
		// __write_stdout (when a float is rendered / when printing is used).
		// A memory-using module emits a wrapper for EVERY export (the heap reset /
		// marshalling), so the helper indices planned in planMemory over
		// exportDecls.size() stay correct.
		int localFuncCount = internalCount + wrapperBodies.size() + (mem.used() ? 6 : 0) + (mem.ftoaUsed() ? 1 : 0)
				+ (mem.printUsed() ? 1 : 0);
		// Canonical string ABI for --component :string exports:
		// cabi_realloc (the host lowers string arguments through it), one retptr shim
		// per :string-RETURNING export (MAX_FLAT_RESULTS = 1, so the lifted core
		// function returns a single i32 pointing at an 8-byte (ptr,len) record instead
		// of the wrapper's two values), and one cabi_post_* post-return per flat-result
		// signature (pops the bump heap once the host has copied the results out). All
		// appended after every existing function -- no index shifts -- and emitted ONLY
		// under --component with a :string boundary, so both the non-component output
		// and a scalar-only component's core module stay byte-identical.
		boolean componentStringAbi = this.component && exportDecls.stream().anyMatch(WasmExportCompiler::usesMemory);
		List<Integer> stringReturnDecls = new ArrayList<>();
		LinkedHashMap<String, @Nullable Type> postKinds = new LinkedHashMap<>();
		if (componentStringAbi) {
			for (int j = 0; j < exportDecls.size(); j++) {
				WasmExportCompiler.Decl decl = exportDecls.get(j);
				if (decl.returnType() == BoundaryType.STRING) {
					stringReturnDecls.add(j);
				}
				if (WasmExportCompiler.usesMemory(decl)) {
					postKinds.putIfAbsent(NoGcWasmComponentBuilder.postReturnKind(decl), postReturnParamType(decl));
				}
			}
		}
		int reallocIndex = mem.funcBase() + localFuncCount;
		int postBase = reallocIndex + 1;
		int shimBase = postBase + postKinds.size();
		int totalFuncCount = localFuncCount
				+ (componentStringAbi ? 1 + postKinds.size() + stringReturnDecls.size() : 0);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write("\0asm")
			.writeLittleEndian4(1)
			// Type section: one function type per function (no dedup needed). When the
			// module prints, type 0 is the imported fd_write's (i32,i32,i32,i32)->i32 and
			// every local function's type index shifts by funcBase, keeping the function
			// index = type index correspondence. Internal function k: its inferred
			// (i64|f64|i32 ...) -> (i64|f64|i32). Wrapper j: the host signature. Then,
			// when memory is used, the helper types.
			.writeTypeSection(typeSec -> {
				if (mem.printUsed()) {
					typeSec.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				}
				for (String name : reachable) {
					typeSec.addFunc(wasmParamTypes(name, types), new Type[] { wasmType(returnTy(name, types)) });
				}
				for (int j = 0; j < exportDecls.size(); j++) {
					if (wrapperOrdinals[j] < 0) {
						continue; // pass-through: no wrapper, no host type
					}
					WasmExportCompiler.Decl decl = exportDecls.get(j);
					typeSec.addFunc(WasmExportCompiler.paramWasmTypes(decl), WasmExportCompiler.resultWasmTypes(decl));
				}
				if (mem.used()) {
					typeSec.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 });
					typeSec.addFunc(new Type[] { Type.I32, Type.I32, Type.I32 }, new Type[0]);
					typeSec.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 });
					typeSec.addFunc(new Type[] { Type.I64 }, new Type[] { Type.I32 });
					// __ronto_alloc_mark () -> i32 and __ronto_alloc_reset (i32) -> ()
					// (the host arena API).
					typeSec.addFunc(new Type[0], new Type[] { Type.I32 });
					typeSec.addFunc(new Type[] { Type.I32 }, new Type[0]);
				}
				if (mem.ftoaUsed()) {
					// __ftoa (f64) -> i32 (a fresh [len][bytes] string pointer).
					typeSec.addFunc(new Type[] { Type.F64 }, new Type[] { Type.I32 });
				}
				if (mem.printUsed()) {
					// __write_stdout (ptr i32, len i32) -> (): the sole fd_write caller.
					typeSec.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[0]);
				}
				if (componentStringAbi) {
					// cabi_realloc (old, old-size, align, new-size) -> i32.
					typeSec.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
					// cabi_post_* (flat results) -> (), one per signature.
					for (Type paramType : postKinds.values()) {
						typeSec.addFunc(paramType == null ? new Type[0] : new Type[] { paramType }, new Type[0]);
					}
					// Retptr shims: the wrapper's host parameters, a single i32 result.
					for (int j : stringReturnDecls) {
						typeSec.addFunc(WasmExportCompiler.paramWasmTypes(exportDecls.get(j)), new Type[] { Type.I32 });
					}
				}
			});
		// Import section: exactly the one fd_write, and only when the program prints.
		// A print-free module keeps zero imports, so its --component wrap needs no
		// adapter.
		if (mem.printUsed()) {
			// Type index 0: the fd_write signature was added first above.
			w.writeImportSection(
					imports -> imports.addImport("wasi_snapshot_preview1", "fd_write", ExternalKind.FUNCTION, 0));
		}
		// Function section: local function index (funcBase + k) uses type index
		// (funcBase + k), mirroring the type-section shift above.
		w.writeFunction(func -> {
			for (int k = 0; k < totalFuncCount; k++) {
				func.addFunction(k + mem.funcBase());
			}
		});
		// Memory + global (the bump-allocator heap pointer): emitted only when the module
		// uses linear memory, so a pure-numeric module stays byte-identical to the
		// original
		// import-free, memoryless scalar output.
		if (mem.used()) {
			w.writeMemory(memories -> memories.addMemory(Math.max(1, (mem.heapBase() + 0xffff) >>> 16)))
				.writeGlobal(gs -> gs.addGlobal(Type.I32, Mutability.VAR,
						init -> init.write(Instruction.I32_CONST).writeSignedLeb128(mem.heapBase())));
		}
		w
			// Export section: each directive exports its wrapper (or, for a
			// pass-through, the internal function itself) under its :as alias
			// (default: the function name). When memory is used, also export the linear
			// memory and the bump allocator so a host can write :string inputs and read
			// :string results.
			.writeExport(exports -> {
				for (int j = 0; j < exportDecls.size(); j++) {
					// A :string-returning export in component mode is exported as its
					// retptr shim; the two-value wrapper stays reachable through the
					// shim's call but is not itself an export.
					int ordinal = stringReturnDecls.indexOf(j);
					exports.addExport(exportDecls.get(j).exportName(), ExternalKind.FUNCTION,
							ordinal >= 0 ? shimBase + ordinal : mem.funcIndex(exportOrdinals[j]));
				}
				if (mem.used()) {
					exports.addExport("memory", ExternalKind.MEMORY, 0);
					exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, mem.allocIndex());
					// The host arena API: snapshot the bump-heap top before the host
					// allocates its own input buffer, then restore it after the call so a
					// resident instance stays flat regardless of how many times it is
					// called.
					exports.addExport("__ronto_alloc_mark", ExternalKind.FUNCTION, mem.markIndex());
					exports.addExport("__ronto_alloc_reset", ExternalKind.FUNCTION, mem.resetIndex());
				}
				if (componentStringAbi) {
					// The canonical string ABI helpers the component wrap aliases.
					exports.addExport(NoGcWasmComponentBuilder.CABI_REALLOC, ExternalKind.FUNCTION, reallocIndex);
					int p = 0;
					for (String kind : postKinds.keySet()) {
						exports.addExport(NoGcWasmComponentBuilder.postReturnExportName(kind), ExternalKind.FUNCTION,
								postBase + p++);
					}
				}
			})
			// Code section: internal bodies, wrappers, then the memory helpers, matching
			// the
			// function section order.
			.writeCode(code -> {
				for (byte[] body : internalBodies) {
					code.addFunction(body);
				}
				for (byte[] body : wrapperBodies) {
					code.addFunction(body);
				}
				if (mem.used()) {
					code.addFunction(allocBody());
					code.addFunction(memcpyBody());
					code.addFunction(streqBody());
					code.addFunction(itoaBody(mem.allocIndex()));
					code.addFunction(markBody());
					code.addFunction(resetBody());
				}
				if (mem.ftoaUsed()) {
					code.addFunction(ftoaBody(mem));
				}
				if (mem.printUsed()) {
					code.addFunction(writeStdoutBody(mem));
				}
				if (componentStringAbi) {
					code.addFunction(cabiReallocBody(mem.allocIndex()));
					for (int p = 0; p < postKinds.size(); p++) {
						code.addFunction(postReturnBody(mem.heapBase()));
					}
					for (int j : stringReturnDecls) {
						code.addFunction(retptrShimBody(mem.funcIndex(internalCount + wrapperOrdinals[j]),
								mem.allocIndex(), WasmExportCompiler.paramSlotCount(exportDecls.get(j))));
					}
				}
			});
		// Data section: the string-literal headers, only when present.
		if (mem.used() && mem.data().length > 0) {
			w.writeDataSection(data -> data.addActiveData(0, mem.dataBase(), mem.data()));
		}
		return out.toByteArray();
	}

	// __alloc(size i32) -> i32: a bump allocator over the heap-pointer global (index 0).
	// It
	// 4-byte-aligns the bump, grows linear memory by whole pages when the new top exceeds
	// the current size, and returns the old pointer. Locals: 1=old, 2=end, 3=need(pages).
	private static byte[] allocBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		// old = heap
		w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeSignedLeb128(1);
		// end = (old + size + 3) & -4
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3).write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(-4).write(Instruction.I32_AND);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(2);
		// heap = end
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2).write(Instruction.SET_GLOBAL, 0x00);
		// need = (end + 65535) >> 16
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xffff).write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16).write(Instruction.I32_SHR_U);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(3);
		// if need > memory.size: grow(need - memory.size); drop
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GROW_MEMORY, 0x00);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
		// return old
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING, Ty.STRING));
	}

	// __memcpy(dst i32, src i32, n i32): copy n bytes one at a time (no bulk-memory
	// dependency, so the module stays plain MVP). Params 0=dst, 1=src, 2=n.
	private static byte[] memcpyBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// if n == 0 break out of the block
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2).write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// mem[dst] = mem[src] (one byte)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1).write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// dst++, src++, n--
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_SUB).write(Instruction.SET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of());
	}

	// __streq(a i32, b i32) -> i32: 1 iff the two [len][bytes] strings have identical
	// content. Params 0=a, 1=b; locals 2=la (length of a), 3=i.
	private static byte[] streqBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		// if a == b return 1 (same header address, e.g. the same interned literal)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// la = len(a); if la != len(b) return 0
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0).write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1).write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// byte loop
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// if a[4+i] != b[4+i] return 0
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING));
	}

	// __itoa(v i64) -> i32: render the integer as a fresh [len][bytes] decimal string
	// (with a leading '-' when negative). Param 0=v; locals 1=t (i64 magnitude),
	// 2=count (i32), 3=p (i32 result), 4=idx (i32 write cursor). Note: Long.MIN_VALUE
	// negation wraps, matching the backend's documented 2^63 integer range.
	private static byte[] itoaBody(int allocIndex) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		Runnable loadMagnitude = () -> {
			// t = v < 0 ? -v : v
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
			w.write(Instruction.I64_CONST).writeSignedLeb128(0);
			w.write(Instruction.I64_LT_S);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.I64_CONST).writeSignedLeb128(0);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
			w.write(Instruction.I64_SUB);
			w.write(Instruction.SET_LOCAL).writeSignedLeb128(1);
			w.write(Instruction.ELSE);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
			w.write(Instruction.SET_LOCAL).writeSignedLeb128(1);
			w.write(Instruction.END);
		};
		loadMagnitude.run();
		// count the digits (a do-while, so 0 renders as "0")
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_S);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// the sign takes one more byte
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.END);
		// p = __alloc(4 + count); store the length header
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeSignedLeb128(allocIndex);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// idx = p + 3 + count (the last content byte)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(4);
		// write digits backwards (again a do-while)
		loadMagnitude.run();
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_REM_S);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST).writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(4).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_SUB).write(Instruction.SET_LOCAL).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_S);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// the '-' sign
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x04);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of(Ty.INT, Ty.STRING, Ty.STRING, Ty.STRING));
	}

	// __ronto_alloc_mark() -> i32: the host arena API. Returns the current bump-heap top
	// (heap-pointer global 0). A resident host snapshots this BEFORE it allocates its own
	// input buffer with __ronto_alloc, then pops back to it with __ronto_alloc_reset
	// after the call, so a repeatedly-called instance stays flat (the automatic
	// scalar-return reset already reclaims the wrapper's own internal scratch; this
	// reclaims the host's pre-call buffer too). See .kb/wasm-export-no-wasi.md. No
	// locals.
	private static byte[] markBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_GLOBAL, 0x00);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of());
	}

	// __ronto_alloc_reset(mark i32) -> (): the host arena API. Restores the bump-heap
	// top to a value previously returned by __ronto_alloc_mark -- an
	// absolute-restore of a stack/arena, not a per-block free. Popping to a mark taken
	// AFTER live data (or reading a :string result whose bytes sit above the mark) is
	// caller error; see the --no-gc docs. Param 0 = mark, no locals.
	private static byte[] resetBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of());
	}

	// --- Canonical string ABI helpers for --component :string exports

	// The core parameter type of the shared cabi_post_* post-return function a
	// string-involving export's lift references: the lifted core function's single flat
	// result (a :string result flattens to an i32 return pointer), or none for :void.
	private static @Nullable Type postReturnParamType(WasmExportCompiler.Decl decl) {
		return switch (NoGcWasmComponentBuilder.postReturnKind(decl)) {
			case "i64" -> Type.I64;
			case "f64" -> Type.F64;
			case "void" -> null;
			default -> Type.I32;
		};
	}

	// cabi_realloc(old i32, old-size i32, align i32, new-size i32) -> i32: the canonical
	// ABI reallocation entry point the host calls to lower string arguments into this
	// module's memory. Over a bump allocator it is just __alloc(new-size): old is never
	// a live block to preserve (the host lowers each string with old = 0 and an exact
	// size), and __alloc's 4-byte alignment satisfies the string encoding's align 1.
	private static byte[] cabiReallocBody(int allocIndex) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(3);
		w.write(Instruction.CALL).writeSignedLeb128(allocIndex);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of());
	}

	// cabi_post_*(flat results) -> (): the canonical ABI post-return function, called by
	// the host after it has copied the results out of memory. Nothing in a --no-gc
	// instance outlives one export call (there are no Lisp globals and the host arena
	// API is not part of the component ABI), so it pops the bump heap all the way back
	// to its base -- freeing the host-lowered argument strings, the wrapper's internal
	// copies and the result string -- and a resident instance stays flat. The flat-result
	// parameters are ignored.
	private static byte[] postReturnBody(int heapBase) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128(heapBase);
		w.write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of());
	}

	// The retptr shim for a :string-returning export: the canonical ABI caps flat
	// results at one, so the lifted core function must return a single i32 pointing at
	// an 8-byte (ptr,len) record instead of the wrapper's two values. The shim forwards
	// its parameters to the untouched wrapper, allocates the record with __alloc (freed
	// by the post-return above) and stores the pair. Locals: ptr, len, ret (i32 each,
	// after the host parameter slots).
	private static byte[] retptrShimBody(int wrapperIndex, int allocIndex, int paramSlots) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		int ptr = paramSlots;
		int len = paramSlots + 1;
		int ret = paramSlots + 2;
		for (int s = 0; s < paramSlots; s++) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(s);
		}
		w.write(Instruction.CALL).writeSignedLeb128(wrapperIndex);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(ptr);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.CALL).writeSignedLeb128(allocIndex);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(ret);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(ret);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(ptr);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(ret);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(ret);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING, Ty.STRING));
	}

	// __ftoa(v f64) -> i32: render the float as a fresh [len][bytes] string, the exact
	// digit-extraction algorithm of the GC backend's FUNC_PRINT_F64_NO_NL (hardened for
	// the IEEE edges: NaN/Infinity/-Infinity return their static literal header
	// directly, the sign is taken from the sign BIT so -0.0 prints "-0.0", magnitudes
	// >= 2^63 divide into [1, 10) and append an E<exp> suffix) so print/princ-to-string
	// output matches the GC backend byte for byte. Integer digits go MSD-first over a
	// descending power of ten (10^18 covers everything below 2^63); the fractional part
	// prints at least 1 and at most 6 digits, stopping once the residue drops below 1e-7.
	// Param 0=v; locals 1=p (i32 result), 2=c (i32 write cursor), 3=int64 (i64),
	// 4=pow (i64), 5=digit (i32), 6=started (i32), 7=frac (f64), 8=exp (i32),
	// 9=digit_count (i32).
	private static byte[] ftoaBody(Mem mem) {
		final int P = 1, C = 2, INT64 = 3, POW = 4, DIGIT = 5, STARTED = 6, FRAC = 7, EXP = 8, COUNT = 9;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		Runnable getV = () -> w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		Runnable getC = () -> w.write(Instruction.GET_LOCAL).writeSignedLeb128(C);
		Runnable incC = () -> {
			getC.run();
			w.write(Instruction.I32_CONST).writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL).writeSignedLeb128(C);
		};
		// NaN: value != value -> the static "NaN" literal (no allocation).
		getV.run();
		getV.run();
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(Objects.requireNonNull(mem.literals().get("NaN")));
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// +Infinity / -Infinity: the static literals.
		getV.run();
		w.write(Instruction.F64_CONST).writeF64(Double.POSITIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(Objects.requireNonNull(mem.literals().get("Infinity")));
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		getV.run();
		w.write(Instruction.F64_CONST).writeF64(Double.NEGATIVE_INFINITY);
		w.write(Instruction.F64_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(Objects.requireNonNull(mem.literals().get("-Infinity")));
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// p = __alloc(48): 4 header + at most 31 content bytes ('-' + 19 integer digits
		// + '.' + 6 fraction digits + 'E' + 3 exponent digits); c = p + 4.
		w.write(Instruction.I32_CONST).writeSignedLeb128(48);
		w.write(Instruction.CALL).writeSignedLeb128(mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(P);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(P);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(C);
		// Negative by the sign BIT (so -0.0 keeps its '-'): write it, then negate.
		getV.run();
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		getC.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128('-');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		getV.run();
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.END);
		// value >= 2^63 cannot go through integer digit extraction: divide into [1, 10)
		// and remember the decimal exponent (appended as E<exp> at the end).
		getV.run();
		w.write(Instruction.F64_CONST).writeF64(0x1p63);
		w.write(Instruction.F64_GE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getV.run();
		w.write(Instruction.F64_CONST).writeF64(10.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.BR_IF, 1);
		getV.run();
		w.write(Instruction.F64_CONST).writeF64(10.0);
		w.write(Instruction.F64_DIV);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(EXP);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(EXP);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.END); // if >= 2^63
		// Integer digits MSD-first: int64 = i64(floor(v)); pow = 10^18; emit once a
		// non-zero digit started (or at the final power, so 0 renders "0").
		getV.run();
		w.write(Instruction.F64_FLOOR);
		w.write(Instruction.I64_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(INT64);
		w.write(Instruction.I64_CONST).writeSignedLeb128(1000000000);
		w.write(Instruction.I64_CONST).writeSignedLeb128(1000000000);
		w.write(Instruction.I64_MUL);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(POW);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(INT64);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(POW);
		w.write(Instruction.I64_DIV_U);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_REM_U);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(STARTED);
		w.write(Instruction.I32_OR);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(POW);
		w.write(Instruction.I64_CONST).writeSignedLeb128(1);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		getC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.I32_CONST).writeSignedLeb128('0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(STARTED);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(POW);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_U);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(POW);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(POW);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_NE);
		w.write(Instruction.BR_IF, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// frac = v - f64(int64); '.'
		getV.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(INT64);
		w.write(Instruction.F64_CONVERT_S_I64);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(FRAC);
		getC.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128('.');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		// Fraction digits: at least 1, at most 6, stop when the residue < 1e-7.
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(FRAC);
		w.write(Instruction.F64_CONST).writeF64(10.0);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(FRAC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(FRAC);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(DIGIT);
		getC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.I32_CONST).writeSignedLeb128('0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(FRAC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(FRAC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(COUNT);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(COUNT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(COUNT);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(FRAC);
		w.write(Instruction.F64_CONST).writeF64(0.0000001);
		w.write(Instruction.F64_GT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(COUNT);
		w.write(Instruction.I32_CONST).writeSignedLeb128(6);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.BR_IF, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// Exponent suffix from the >= 2^63 normalization: 'E' then up to three decimal
		// digits with leading-zero suppression (exp <= 291 for finite doubles).
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(EXP);
		w.write(Instruction.IF, 0x40);
		getC.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128('E');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(STARTED);
		// hundreds
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(EXP);
		w.write(Instruction.I32_CONST).writeSignedLeb128(100);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.IF, 0x40);
		getC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.I32_CONST).writeSignedLeb128('0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(STARTED);
		w.write(Instruction.END);
		// tens
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(EXP);
		w.write(Instruction.I32_CONST).writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.I32_CONST).writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(STARTED);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		getC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(DIGIT);
		w.write(Instruction.I32_CONST).writeSignedLeb128('0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		w.write(Instruction.END);
		// ones (always written)
		getC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(EXP);
		w.write(Instruction.I32_CONST).writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.I32_CONST).writeSignedLeb128('0');
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		incC.run();
		w.write(Instruction.END); // if exp
		// Store the length header: p.len = c - p - 4; return p.
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(P);
		getC.run();
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(P);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(P);
		w.write(Instruction.END);
		// Locals 1..9: p, c (i32), int64, pow (i64), digit, started (i32), frac (f64),
		// exp, digit_count (i32).
		return withLocalsRaw(b.toByteArray(), List.of(Type.I32.code(), Type.I32.code(), Type.I64.code(),
				Type.I64.code(), Type.I32.code(), Type.I32.code(), Type.F64.code(), Type.I32.code(), Type.I32.code()));
	}

	// __write_stdout(ptr i32, len i32): the ONE funnel through which every printing op
	// writes -- the sole caller of the imported fd_write (also the --component seam: a
	// component variant swaps this implementation, never the print ops). Fills the iov
	// scratch (iovAddr = ptr/len, iovAddr+8 = nwritten) and writes to fd 1 (stdout).
	private static byte[] writeStdoutBody(Mem mem) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr());
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr() + 4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(1, iov, 1, nwritten); drop errno
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr());
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr() + 8);
		w.write(Instruction.CALL).writeSignedLeb128(mem.fdWriteIndex());
		w.write(Instruction.DROP);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of());
	}

	private static Type[] wasmParamTypes(String name, Types types) {
		Ty[] pt = Objects.requireNonNull(types.params().get(name));
		Type[] out = new Type[pt.length];
		for (int i = 0; i < pt.length; i++) {
			out[i] = wasmType(pt[i]);
		}
		return out;
	}

	private static Type wasmType(Ty ty) {
		return switch (ty) {
			case INT -> Type.I64;
			case FLOAT -> Type.F64;
			case STRING, F64VEC, F32VEC, F64MAT, F32MAT -> Type.I32;
		};
	}

	private static Ty returnTy(String name, Types types) {
		Ty t = types.returns().get(name);
		return t == null ? Ty.INT : t;
	}

	// --- Function bodies ---------------------------------------------------------------

	private byte[] compileDefunBody(Defun defun, String name, Types types, Map<String, Integer> index, Mem mem) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(bodyStream);
		Ty[] paramTypes = Objects.requireNonNull(types.params().get(name));
		Fn fn = new Fn(w, types, index, name, new HashSet<>(defun.params()), mem);
		for (int i = 0; i < defun.params().size(); i++) {
			fn.bind(defun.params().get(i), i, paramTypes[i]);
		}
		fn.nextLocal = defun.params().size();

		Ty bodyTy = compileExpr(progn(defun.body()), fn);
		coerce(w, bodyTy, returnTy(name, types));
		w.write(Instruction.END);
		return withLocalsRaw(bodyStream.toByteArray(), fn.extraLocalTypes);
	}

	/**
	 * Whether an export needs no wrapper at all: every parameter and the return value
	 * cross the host boundary in the internal representation unchanged ({@code :long}
	 * over an inferred i64, {@code :float} over an inferred f64) and the module has no
	 * linear memory (a memory-using module's scalar-return wrapper must reset the bump
	 * heap, and its {@code :string} boundaries marshal). Such an export names the
	 * internal function directly instead of an identity wrapper that would only forward
	 * its arguments.
	 * @param decl the parsed export directive
	 * @param types the inferred internal types
	 * @param mem the memory plan
	 * @return true when the export can name the internal function directly
	 */
	private static boolean isPassThroughExport(WasmExportCompiler.Decl decl, Types types, Mem mem) {
		if (mem.used()) {
			return false;
		}
		Ty[] internalParams = Objects.requireNonNull(types.params().get(decl.name()));
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			BoundaryType hostType = decl.paramTypes().get(p);
			Ty internal = internalParams[p];
			boolean identity = (hostType == BoundaryType.S64 && internal == Ty.INT)
					|| (hostType == BoundaryType.FLOAT && internal == Ty.FLOAT);
			if (!identity) {
				return false;
			}
		}
		Ty ret = returnTy(decl.name(), types);
		return (decl.returnType() == BoundaryType.S64 && ret == Ty.INT)
				|| (decl.returnType() == BoundaryType.FLOAT && ret == Ty.FLOAT);
	}

	private byte[] compileWrapperBody(WasmExportCompiler.Decl decl, int targetIndex, Types types, Mem mem) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(bodyStream);
		String name = decl.name();
		Ty[] internalParams = Objects.requireNonNull(types.params().get(name));
		// Wrapper locals start past the host parameter slots (a :string parameter
		// occupies
		// two slots). The string boxing/unboxing helpers allocate i32 scratch locals
		// here.
		List<Ty> wrapperLocals = new ArrayList<>();
		int nextLocal = WasmExportCompiler.paramSlotCount(decl);

		// Auto-reset the bump heap for scalar-return exports. Anything the exported
		// function allocates during the call (the internal :string copy below,
		// plus any concatenate/subseq/... scratch) is dead the moment a non-memory scalar
		// is returned -- no heap pointer escapes to the host. Snapshot the heap-pointer
		// global (index 0) at wrapper entry and restore it at exit so a long-lived,
		// repeatedly-called instance stops growing. Gated on the return type NOT being a
		// memory designator (:string/:s-expr, whose result pointer must stay live) and on
		// mem.used() (a pure-numeric export has no heap global at all).
		boolean resetHeap = mem.used() && decl.returnType() != BoundaryType.STRING
				&& decl.returnType() != BoundaryType.S_EXPR;
		int mark = -1;
		if (resetHeap) {
			mark = nextLocal++;
			wrapperLocals.add(Ty.STRING);
			w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeSignedLeb128(mark);
		}

		// Box each host argument into the internal value of the inferred parameter type,
		// in
		// order, then call the internal function.
		int slot = 0;
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			BoundaryType hostType = decl.paramTypes().get(p);
			Ty internal = internalParams[p];
			if (hostType == BoundaryType.STRING) {
				// (ptr,len) -> a fresh internal [len][bytes] string copied out of the
				// host
				// buffer. Scratch locals: hp(host ptr), len, dst.
				int hp = nextLocal++;
				int len = nextLocal++;
				int dst = nextLocal++;
				wrapperLocals.add(Ty.STRING);
				wrapperLocals.add(Ty.STRING);
				wrapperLocals.add(Ty.STRING);
				w.write(Instruction.GET_LOCAL)
					.writeSignedLeb128(slot)
					.write(Instruction.SET_LOCAL)
					.writeSignedLeb128(hp);
				w.write(Instruction.GET_LOCAL)
					.writeSignedLeb128(slot + 1)
					.write(Instruction.SET_LOCAL)
					.writeSignedLeb128(len);
				w.write(Instruction.I32_CONST).writeSignedLeb128(4);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(len).write(Instruction.I32_ADD);
				w.write(Instruction.CALL).writeSignedLeb128(mem.allocIndex());
				w.write(Instruction.SET_LOCAL).writeSignedLeb128(dst);
				// store the length header
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(len).write(Instruction.I32_STORE, 0x02, 0x00);
				// __memcpy(dst+4, hp, len)
				w.write(Instruction.GET_LOCAL)
					.writeSignedLeb128(dst)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_ADD);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(hp);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(len);
				w.write(Instruction.CALL).writeSignedLeb128(mem.memcpyIndex());
				// leave the internal string pointer for the call
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
				slot += 2;
			}
			else {
				// A u64 above 2^63 has no exact place in the house i64: refuse it here
				// rather than let it arrive as a negative Lisp integer.
				if (hostType == BoundaryType.U64) {
					w.write(Instruction.GET_LOCAL).writeSignedLeb128(slot);
					i64Const(w, 0);
					w.write(Instruction.I64_LT_S);
					w.write(Instruction.IF, 0x40);
					w.write(Instruction.UNREACHABLE);
					w.write(Instruction.END);
				}
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(slot);
				switch (hostType) {
					// host f64 -> internal (always FLOAT, since :float pins the param)
					case FLOAT -> {
						if (internal == Ty.INT) {
							w.write(Instruction.I64_TRUNC_S_F64);
						}
					}
					// host i64 -> internal i64 (INT): identity, no conversion. A 64-bit
					// designator pins the parameter to INT, so the FLOAT branch is
					// defensive only.
					case S64 -> {
						if (internal == Ty.FLOAT) {
							w.write(Instruction.F64_CONVERT_S_I64);
						}
					}
					case U64 -> {
						if (internal == Ty.FLOAT) {
							w.write(Instruction.F64_CONVERT_U_I64);
						}
					}
					// host i32 -> internal, widened in the declared type's own
					// signedness:
					// zero-extending a u32 is what makes its top half arrive as the
					// positive integer the WIT type says it is.
					default -> {
						boolean signed = !hostType.isInteger() || hostType.signed();
						if (internal == Ty.INT) {
							w.write(signed ? Instruction.I64_EXTEND_S_I32 : Instruction.I64_EXTEND_U_I32);
						}
						else {
							w.write(signed ? Instruction.F64_CONVERT_S_I32 : Instruction.F64_CONVERT_U_I32);
						}
					}
				}
				slot += 1;
			}
		}
		w.write(Instruction.CALL).writeSignedLeb128(mem.funcIndex(targetIndex));
		// Unbox the internal result (its inferred return type) back to the host type.
		Ty ret = returnTy(name, types);
		switch (decl.returnType()) {
			// Every integer result is normalized to an i64 in the declared type's own
			// signedness (a FLOAT body truncates through the trapping i64.trunc, which is
			// also what rejects a negative for an unsigned type), range-checked against
			// the
			// declared type, and only then narrowed. A value the type cannot state stops
			// the call instead of arriving silently wrapped; :s64 needs neither a check
			// nor
			// a narrowing, so it stays the identity it always was.
			case S8, S16, S32, S64, U8, U16, U32, U64 -> {
				BoundaryType type = decl.returnType();
				if (ret == Ty.FLOAT) {
					w.write(type.signed() ? Instruction.I64_TRUNC_S_F64 : Instruction.I64_TRUNC_U_F64);
				}
				nextLocal += emitBoundaryRangeGuard(w, type, ret == Ty.FLOAT, nextLocal, wrapperLocals);
				if (type.bits() < 64) {
					w.write(Instruction.I32_WRAP_I64);
				}
			}
			case FLOAT -> {
				if (ret == Ty.INT) {
					w.write(Instruction.F64_CONVERT_S_I64);
				}
			}
			case BOOL -> {
				// non-zero -> 1, zero -> 0
				if (ret == Ty.INT) {
					i64Const(w, 0);
					w.write(Instruction.I64_NE);
				}
				else {
					w.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_NE);
				}
			}
			case STRING -> {
				// internal [len][bytes] pointer -> (content ptr, len) host pair.
				int r = nextLocal++;
				wrapperLocals.add(Ty.STRING);
				w.write(Instruction.SET_LOCAL).writeSignedLeb128(r);
				w.write(Instruction.GET_LOCAL)
					.writeSignedLeb128(r)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_ADD);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(r).write(Instruction.I32_LOAD, 0x02, 0x00);
			}
			case VOID -> w.write(Instruction.DROP);
			case S_EXPR -> throw new UnsupportedOperationException("--no-gc does not support the export return type "
					+ decl.returnType().designator() + " (it needs a cons/reader/printer runtime)");
		}
		// Restore the heap pointer for scalar returns. local.get pushes mark above the
		// host result already on the stack, global.set pops it -- the result stays on top
		// (for :void the stack is empty and this is still valid).
		if (resetHeap) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
		}
		w.write(Instruction.END);
		return withLocals(bodyStream.toByteArray(), wrapperLocals);
	}

	/**
	 * Traps unless the i64 on the stack is a value the declared boundary type can state,
	 * and leaves it there. The check is derived from two intervals -- the type's range
	 * and the house integer's -- so no case is special: only the bounds the house integer
	 * can actually cross are emitted, which is why {@code :s64} keeps the plain identity
	 * wrapper it always had.
	 * @param w the wrapper's writer
	 * @param type the declared boundary type (an integer)
	 * @param fromFloat whether the value came through a trapping {@code i64.trunc}, which
	 * has already enforced the full 64-bit range in the type's own signedness
	 * @param slot the next free wrapper local slot
	 * @param wrapperLocals the wrapper's local-type list, appended to when a scratch is
	 * used
	 * @return the number of local slots consumed (0 when the type needs no check)
	 */
	private static int emitBoundaryRangeGuard(WasmWriter w, BoundaryType type, boolean fromFloat, int slot,
			List<Ty> wrapperLocals) {
		boolean narrowSigned = type.signed() && type.bits() < 64;
		boolean narrowUnsigned = !type.signed() && type.bits() < 64;
		// A u64 result is only at risk coming out of the signed house i64: a negative
		// Lisp
		// integer is not a u64. Out of i64.trunc_u_f64 the whole 0..2^64-1 range is
		// already
		// exact, so nothing is left to check.
		boolean unsignedFromSignedHouse = !type.signed() && type.bits() == 64 && !fromFloat;
		if (!narrowSigned && !narrowUnsigned && !unsignedFromSignedHouse) {
			return 0;
		}
		wrapperLocals.add(Ty.INT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(slot);
		BoundaryType.Range range = Objects.requireNonNull(type.range());
		if (narrowSigned) {
			emitTrapIf(w, slot, Instruction.I64_LT_S, range.min().longValueExact());
			emitTrapIf(w, slot, Instruction.I64_GT_S, range.max().longValueExact());
		}
		else if (narrowUnsigned) {
			// One unsigned comparison covers both ends: a negative i64 read as an
			// unsigned
			// 64-bit value is larger than any sub-64-bit unsigned maximum.
			emitTrapIf(w, slot, Instruction.I64_GT_U, range.max().longValueExact());
		}
		else {
			emitTrapIf(w, slot, Instruction.I64_LT_S, 0);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(slot);
		return 1;
	}

	// `if (local[slot] <op> bound) unreachable` -- the boundary refusing a value it
	// cannot
	// state. A trap is the shape a host already sees for a failure inside an export.
	private static void emitTrapIf(WasmWriter w, int slot, int comparison, long bound) {
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(slot);
		i64Const(w, bound);
		w.write(comparison);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// Prepends the locals declaration to a function body. Each extra local is emitted as
	// its own run (count 1) so the declaration order matches the allocation order
	// regardless of the i64/f64 mix.
	private static byte[] withLocals(byte[] code, List<Ty> extraLocals) {
		List<Integer> raw = new ArrayList<>(extraLocals.size());
		for (Ty ty : extraLocals) {
			raw.add(ty.valType());
		}
		return withLocalsRaw(code, raw);
	}

	// The raw-wasm-type-byte variant (a defun body may hold a v128 local, which has no Ty
	// value-model kind), taking the already-lowered value-type bytes directly.
	private static byte[] withLocalsRaw(byte[] code, List<Integer> extraLocals) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.writeUnsignedLeb128(extraLocals.size());
		for (int t : extraLocals) {
			w.write(1);
			w.write(t);
		}
		w.write((Object) code);
		return out.toByteArray();
	}

	// --- Expression code generation ----------------------------------------------------

	// Emits the expression and returns the wasm type it left on the stack.
	private Ty compileExpr(LispVal expr, Fn fn) {
		switch (expr) {
			case LispInteger i -> {
				i64Const(fn.writer, i.value());
				return Ty.INT;
			}
			case LispDouble d -> {
				fn.writer.write(Instruction.F64_CONST).writeF64(d.value());
				return Ty.FLOAT;
			}
			case LispString s -> {
				// A string literal is the i32 address of its [len][bytes] header in the
				// static data segment.
				Integer off = fn.mem.literals().get(s.value());
				if (off == null) {
					throw new UnsupportedOperationException(
							"--no-gc: string literal not laid out in '" + fn.fnName + "': " + s.print());
				}
				fn.writer.write(Instruction.I32_CONST).writeSignedLeb128(off);
				return Ty.STRING;
			}
			case LispFloatArray fa -> {
				return compileFloatArrayLiteral(fa, fn);
			}
			case LispChar c -> {
				// A character is its code point (see the BUILTINS note).
				i64Const(fn.writer, c.codePoint());
				return Ty.INT;
			}
			case LispTrue ignored -> {
				i64Const(fn.writer, 1);
				return Ty.INT;
			}
			case LispNil ignored -> {
				i64Const(fn.writer, 0);
				return Ty.INT;
			}
			case LispSymbol sym -> {
				Integer slot = fn.locals.get(sym.name());
				if (slot == null) {
					throw new UnsupportedOperationException("--no-gc: '" + sym.name() + "' in function '" + fn.fnName
							+ "' is not a parameter or let binding (scalar mode has no globals or heap values)");
				}
				fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(slot);
				return Objects.requireNonNull(fn.localTypes.get(sym.name()));
			}
			case LispCons cons -> {
				return compileCall(cons, fn);
			}
			default -> throw new UnsupportedOperationException(
					"--no-gc: unsupported value in function '" + fn.fnName + "': " + expr.print());
		}
	}

	// Emits the expression, coercing its value to the requested type.
	private void compileCoerced(LispVal expr, Fn fn, Ty target) {
		// nil in a string context is the empty string. Address 0 is a valid zero-length
		// string header (the first 8 bytes of linear memory are always zero and the bump
		// allocator never hands them out), so an absent if/cond branch or a nil fallback
		// yields "" rather than a string/number type clash. This is what makes the
		// cond-with-a-`t`-clause expansion (which threads an explicit nil else)
		// type-check.
		if (isRefKind(target) && expr instanceof LispNil) {
			fn.writer.write(Instruction.I32_CONST).writeSignedLeb128(0);
			return;
		}
		coerce(fn.writer, compileExpr(expr, fn), target);
	}

	private static void coerce(WasmWriter w, Ty from, Ty to) {
		if (from == to) {
			return;
		}
		// STRING, F64VEC and F32VEC are reference kinds; the only valid non-identity
		// coercions are between the two numeric kinds (INT <-> FLOAT). A reference kind
		// can
		// only coerce to itself (that identity case already returned above), so any
		// reference kind reaching here -- including a f64-vector / f32-vector mismatch --
		// is
		// a genuine type error.
		if (isRefKind(from) || isRefKind(to)) {
			throw new UnsupportedOperationException("--no-gc: incompatible types " + from + " and " + to
					+ " (a value cannot be more than one of number / string / float-vector)");
		}
		if (from == Ty.INT) {
			w.write(Instruction.F64_CONVERT_S_I64); // i64 -> f64
		}
		else {
			w.write(Instruction.I64_TRUNC_S_F64); // f64 -> i64 (truncate toward zero)
		}
	}

	// A reference kind is an i32 pointer into linear memory (a string or a packed float
	// vector/matrix), never an immediate scalar. It can only coerce to itself, so any
	// mismatch involving one is a type error.
	private static boolean isRefKind(Ty ty) {
		return ty == Ty.STRING || ty == Ty.F64VEC || ty == Ty.F32VEC || ty == Ty.F64MAT || ty == Ty.F32MAT;
	}

	// Whether a packed kind uses the 4-byte f32 element width (vector or matrix).
	private static boolean isSingleWidth(Ty ty) {
		return ty == Ty.F32VEC || ty == Ty.F32MAT;
	}

	// The element byte-shift for a packed float vector/matrix: f64 = 3 (8-byte stride),
	// f32 = 2 (4-byte stride). Used everywhere the packed layout is indexed (allocVec /
	// emitElementAddr / literals / make-array).
	private static int elemShift(Ty vecTy) {
		return isSingleWidth(vecTy) ? 2 : 3;
	}

	private Ty compileCall(LispCons cons, Fn fn) {
		if (!(cons.car() instanceof LispSymbol head)) {
			throw new UnsupportedOperationException(
					"--no-gc: cannot call a non-symbol / first-class function in '" + fn.fnName + "': " + cons.print());
		}
		String name = head.name();
		List<LispVal> args = cons.toList();

		LispVal expanded = expandMacro(name, cons, args.size() - 1);
		if (expanded != null) {
			return compileExpr(expanded, fn);
		}

		if (isSimdCall(name)) {
			return compileSimd(name, args, fn);
		}

		return switch (name) {
			case LispNames.IF -> compileIf(args, fn);
			case LispNames.PROGN -> compileProgn(args.subList(1, args.size()), fn);
			case LispNames.LET -> compileLet(cons, fn);
			case LispNames.SETQ -> compileSetq(args, fn);
			case LispNames.WHILE -> compileWhile(args, fn);
			case LispNames.BLOCK_INTERNAL -> compileBlock(cons, args, fn);
			case LispNames.BLOCK -> compileExpr(LispMacroExpander.expandBlock(cons), fn);
			case LispNames.RETURN -> compileReturn(args, fn);
			case LispNames.UNWIND_PROTECT ->
				// The wasm-GC backends catch via the exception-handling proposal (todo
				// 129), but --no-gc stays a rejection by design: condition objects are
				// cons/CLOS-subset values its unboxed value model rejects, and its
				// contract is a zero-flag plain MVP module.
				throw new UnsupportedOperationException(LispNames.UNWIND_PROTECT
						+ " is not supported under --no-gc (its contract is a zero-flag MVP module without condition"
						+ " objects); use the default wasm-GC backend, the interpreter or the JVM backend");
			case LispNames.CATCH, LispNames.THROW ->
				// Same rejection, same reason: the dynamic exit rides the
				// exception-handling
				// proposal (a tagged throw whose payload is a cons), which --no-gc has
				// neither the tag section nor the heap values for.
				throw new UnsupportedOperationException(name
						+ " is not supported under --no-gc (its contract is a zero-flag MVP module, and a dynamic"
						+ " non-local exit needs the exception-handling proposal); use the default wasm-GC backend,"
						+ " the interpreter or the JVM backend");
			case LispNames.ADD -> compileVariadic(cons, args, fn, 0, Instruction.I64_ADD, Instruction.F64_ADD);
			case LispNames.MUL -> compileVariadic(cons, args, fn, 1, Instruction.I64_MUL, Instruction.F64_MUL);
			case LispNames.SUB -> compileSub(cons, args, fn);
			case LispNames.DIV -> compileDiv(args, fn);
			case LispNames.MIN -> compileMinMax(cons, args, fn, true);
			case LispNames.MAX -> compileMinMax(cons, args, fn, false);
			case LispNames.MOD -> compileModRem(cons, args, fn, true);
			case LispNames.REM -> compileModRem(cons, args, fn, false);
			case LispNames.ABS -> compileAbs(cons, args, fn);
			case LispNames.FLOAT -> compileFloat(args, fn);
			case LispNames.SQRT -> compileSqrt(args, fn);
			case LispNames.CONCATENATE -> compileConcatenate(args, fn);
			case LispNames.LENGTH -> compileLength(args, fn);
			case LispNames.MAKE_ARRAY -> compileMakeArray(args, fn);
			case LispNames.AREF, LispNames.ROW_MAJOR_AREF -> compileAref(name, args, fn);
			case LispNames.ASET, LispNames.ROW_MAJOR_ASET -> compileAset(name, args, fn);
			case LispNames.SUBSEQ -> compileSubseq(args, fn);
			case LispNames.STRING_EQ -> compileStringEq(args, fn);
			case LispNames.CHAR -> compileCharAt(args, fn);
			case LispNames.CHAR_CODE, LispNames.CODE_CHAR -> compileCharIdentity(name, args, fn);
			case LispNames.CHAR_EQ -> compileComparison(cons, args, fn, Instruction.I64_EQ, Instruction.F64_EQ);
			case LispNames.PRINC_TO_STRING -> compilePrincToString(args, fn);
			case LispNames.PRINT, LispNames.PRINC -> compilePrintOp(name, args, fn);
			case LispNames.TERPRI -> compileTerpri(args, fn);
			case LispNames.WITH_ARENA_QUALIFIED -> compileWithArena(args, fn);
			case LispNames.LOGAND -> compileBitwise(args, fn, -1L, Instruction.I64_AND);
			case LispNames.LOGIOR -> compileBitwise(args, fn, 0L, Instruction.I64_OR);
			case LispNames.LOGXOR -> compileBitwise(args, fn, 0L, Instruction.I64_XOR);
			case LispNames.LOGNOT -> compileLognot(args, fn);
			case LispNames.ASH -> compileAsh(args, fn);
			case LispNames.TRUNCATE -> compileRounding(args, fn, -1);
			case LispNames.FLOOR -> compileRounding(args, fn, Instruction.F64_FLOOR);
			case LispNames.CEILING -> compileRounding(args, fn, Instruction.F64_CEIL);
			case LispNames.ROUND -> compileRounding(args, fn, Instruction.F64_NEAREST);
			case LispNames.EQ -> compileComparison(cons, args, fn, Instruction.I64_EQ, Instruction.F64_EQ);
			case LispNames.LT -> compileComparison(cons, args, fn, Instruction.I64_LT_S, Instruction.F64_LT);
			case LispNames.LE -> compileComparison(cons, args, fn, Instruction.I64_LE_S, Instruction.F64_LE);
			case LispNames.GT -> compileComparison(cons, args, fn, Instruction.I64_GT_S, Instruction.F64_GT);
			case LispNames.GE -> compileComparison(cons, args, fn, Instruction.I64_GE_S, Instruction.F64_GE);
			case LispNames.NOT -> compileNot(args, fn);
			default -> compileUserCall(name, args, fn);
		};
	}

	private Ty compileUserCall(String name, List<LispVal> args, Fn fn) {
		Ty[] paramTypes = fn.types.params().get(name);
		Integer funcIndex = fn.index.get(name);
		if (paramTypes == null || funcIndex == null) {
			throw new UnsupportedOperationException("--no-gc: unsupported operation '" + name + "' in function '"
					+ fn.fnName + "' (not a numeric primitive or an eligible function)");
		}
		int argc = args.size() - 1;
		if (argc != paramTypes.length) {
			throw new UnsupportedOperationException("--no-gc: call to '" + name + "' in '" + fn.fnName + "' passes "
					+ argc + " argument(s) but it takes " + paramTypes.length);
		}
		for (int i = 1; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, paramTypes[i - 1]);
		}
		fn.writer.write(Instruction.CALL).writeSignedLeb128(fn.mem.funcIndex(funcIndex));
		return returnTy(name, fn.types);
	}

	private Ty compileIf(List<LispVal> args, Fn fn) {
		if (args.size() < 3) {
			throw new UnsupportedOperationException("--no-gc: malformed if in '" + fn.fnName + "': " + args);
		}
		LispVal test = args.get(1);
		LispVal then = args.get(2);
		LispVal els = args.size() > 3 ? args.get(3) : LispNil.INSTANCE;
		Ty result = staticType(then, fn).join(staticType(els, fn));
		emitTruthy(compileExpr(test, fn), fn.writer); // -> i32 (1 if non-zero)
		fn.writer.write(Instruction.IF).write(result.valType());
		// The branches may contain a `return`, whose br depth counts this `if`.
		fn.ctrlDepth++;
		compileCoerced(then, fn, result);
		fn.writer.write(Instruction.ELSE);
		compileCoerced(els, fn, result);
		fn.writer.write(Instruction.END);
		fn.ctrlDepth--;
		return result;
	}

	private Ty compileProgn(List<LispVal> body, Fn fn) {
		if (body.isEmpty()) {
			i64Const(fn.writer, 0);
			return Ty.INT;
		}
		Ty last = Ty.INT;
		for (int i = 0; i < body.size(); i++) {
			if (i > 0) {
				fn.writer.write(Instruction.DROP);
			}
			last = compileExpr(body.get(i), fn);
		}
		return last;
	}

	private Ty compileLet(LispCons cons, Fn fn) {
		List<LispVal> parts = cons.toList();
		List<LispVal> bindings = parts.get(1) instanceof LispCons bc ? bc.toList() : List.of();
		List<LispVal> body = parts.subList(2, parts.size());

		// Evaluate all initializers under the OUTER scope (parallel `let` semantics),
		// each
		// into a fresh local of the variable's inferred type (which already accounts for
		// any later setq), coercing the initializer to it; only bind the names
		// afterwards.
		List<String> names = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		List<Ty> tys = new ArrayList<>();
		for (LispVal binding : bindings) {
			String varName;
			LispVal init;
			if (binding instanceof LispSymbol s) {
				varName = s.name();
				init = LispNil.INSTANCE;
			}
			else if (binding instanceof LispCons b) {
				List<LispVal> bp = b.toList();
				varName = ((LispSymbol) bp.get(0)).name();
				init = bp.size() > 1 ? bp.get(1) : LispNil.INSTANCE;
			}
			else {
				throw new UnsupportedOperationException(
						"--no-gc: malformed let binding in '" + fn.fnName + "': " + binding.print());
			}
			Ty ty = localType(fn, varName, init);
			compileCoerced(init, fn, ty);
			int slot = fn.allocLocal(ty);
			fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(slot);
			names.add(varName);
			slots.add(slot);
			tys.add(ty);
		}
		Map<String, Integer> shadowedSlots = new HashMap<>();
		Map<String, Ty> shadowedTypes = new HashMap<>();
		for (int i = 0; i < names.size(); i++) {
			shadowedSlots.put(names.get(i), fn.locals.get(names.get(i)));
			shadowedTypes.put(names.get(i), fn.localTypes.get(names.get(i)));
			fn.bind(names.get(i), slots.get(i), tys.get(i));
		}
		Ty result = compileProgn(body, fn);
		for (String n : names) {
			fn.restore(n, shadowedSlots.get(n), shadowedTypes.get(n));
		}
		return result;
	}

	// The inferred type of a let/do-bound local, falling back to the initializer's static
	// type when inference never saw it (defensive; the inference walk covers every body).
	private Ty localType(Fn fn, String varName, LispVal init) {
		Ty inferred = Objects.requireNonNull(fn.types.locals().get(fn.fnName)).get(varName);
		return inferred != null ? inferred : staticType(init, fn);
	}

	// (setq v1 e1 v2 e2 ...): assign each value (coerced to the variable's wasm type)
	// into
	// its local, leaving the last assigned value on the stack via tee.
	private Ty compileSetq(List<LispVal> args, Fn fn) {
		if ((args.size() - 1) % 2 != 0) {
			throw new UnsupportedOperationException(
					"--no-gc: setq needs an even number of arguments in '" + fn.fnName + "': " + args.size());
		}
		int pairs = (args.size() - 1) / 2;
		if (pairs == 0) {
			i64Const(fn.writer, 0);
			return Ty.INT;
		}
		Ty last = Ty.INT;
		for (int p = 0; p < pairs; p++) {
			if (p > 0) {
				fn.writer.write(Instruction.DROP);
			}
			String var = ((LispSymbol) args.get(1 + 2 * p)).name();
			Integer slot = fn.locals.get(var);
			if (slot == null) {
				throw new UnsupportedOperationException("--no-gc: setq target '" + var + "' in function '" + fn.fnName
						+ "' is not a parameter or let binding (scalar mode has no globals)");
			}
			Ty ty = Objects.requireNonNull(fn.localTypes.get(var));
			compileCoerced(args.get(2 + 2 * p), fn, ty);
			fn.writer.write(Instruction.TEE_LOCAL).writeSignedLeb128(slot);
			last = ty;
		}
		return last;
	}

	// (while test body...): a block/loop pair. The test is re-evaluated at the top; when
	// it is falsy (zero) br exits the block, otherwise the body runs (each value dropped)
	// and br jumps back. The form's value is nil (INT 0), matching the GC backend.
	private Ty compileWhile(List<LispVal> args, Fn fn) {
		WasmWriter w = fn.writer;
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		fn.ctrlDepth += 2;
		Ty testTy = compileExpr(args.get(1), fn);
		emitFalsy(testTy, w); // -> i32 (1 if the test is zero/false)
		w.write(Instruction.BR_IF, 1);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), fn);
			w.write(Instruction.DROP);
		}
		w.write(Instruction.BR, 0);
		fn.ctrlDepth -= 2;
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		i64Const(w, 0);
		return Ty.INT;
	}

	// The internal %block return boundary the loop macros wrap their expansion in: a
	// typed
	// wasm block whose result is the join of normal completion and every (return v)
	// inside
	// it. return (compileReturn) branches out carrying its value coerced to this type.
	private Ty compileBlock(LispCons cons, List<LispVal> args, Fn fn) {
		Ty result = staticType(cons, fn);
		WasmWriter w = fn.writer;
		w.write(Instruction.BLOCK, result.valType());
		fn.ctrlDepth++;
		fn.blockMarkers.push(fn.ctrlDepth);
		fn.blockResultTypes.push(result);
		if (args.size() <= 1) {
			i64Const(w, 0);
			coerce(w, Ty.INT, result);
		}
		else {
			for (int i = 1; i < args.size(); i++) {
				if (i > 1) {
					w.write(Instruction.DROP);
				}
				if (i == args.size() - 1) {
					compileCoerced(args.get(i), fn, result);
				}
				else {
					compileExpr(args.get(i), fn);
				}
			}
		}
		fn.blockResultTypes.pop();
		fn.blockMarkers.pop();
		fn.ctrlDepth--;
		w.write(Instruction.END);
		return result;
	}

	private Ty compileReturn(List<LispVal> args, Fn fn) {
		Integer marker = fn.blockMarkers.peek();
		Ty result = fn.blockResultTypes.peek();
		if (marker == null || result == null) {
			throw new UnsupportedOperationException(
					"--no-gc: return outside of a loop block in function '" + fn.fnName + "'");
		}
		if (args.size() > 1) {
			compileCoerced(args.get(1), fn, result);
		}
		else {
			i64Const(fn.writer, 0);
			coerce(fn.writer, Ty.INT, result);
		}
		fn.writer.write(Instruction.BR, fn.ctrlDepth - marker);
		return result;
	}

	// (+ ...) / (* ...): fold over the inferred result type, with an identity for the
	// empty case (always an integer).
	private Ty compileVariadic(LispCons cons, List<LispVal> args, Fn fn, long identity, int intOp, int floatOp) {
		if (args.size() == 1) {
			i64Const(fn.writer, identity);
			return Ty.INT;
		}
		Ty target = staticType(cons, fn);
		compileCoerced(args.get(1), fn, target);
		for (int i = 2; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, target);
			fn.writer.write(target == Ty.INT ? intOp : floatOp);
		}
		return target;
	}

	// (- x) negates; (- a b ...) is a left fold.
	private Ty compileSub(LispCons cons, List<LispVal> args, Fn fn) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException("--no-gc: - needs at least one argument in '" + fn.fnName + "'");
		}
		Ty target = staticType(cons, fn);
		if (args.size() == 2) {
			if (target == Ty.INT) {
				// 0 - x (wasm has no i64.neg)
				i64Const(fn.writer, 0);
				compileCoerced(args.get(1), fn, Ty.INT);
				fn.writer.write(Instruction.I64_SUB);
			}
			else {
				compileCoerced(args.get(1), fn, Ty.FLOAT);
				fn.writer.write(Instruction.F64_NEG);
			}
			return target;
		}
		compileCoerced(args.get(1), fn, target);
		for (int i = 2; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, target);
			fn.writer.write(target == Ty.INT ? Instruction.I64_SUB : Instruction.F64_SUB);
		}
		return target;
	}

	// (/ x) is 1.0/x; (/ a b ...) is a left fold. Always floating-point division.
	private Ty compileDiv(List<LispVal> args, Fn fn) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException("--no-gc: / needs at least one argument in '" + fn.fnName + "'");
		}
		if (args.size() == 2) {
			fn.writer.write(Instruction.F64_CONST).writeF64(1.0);
			compileCoerced(args.get(1), fn, Ty.FLOAT);
			fn.writer.write(Instruction.F64_DIV);
			return Ty.FLOAT;
		}
		compileCoerced(args.get(1), fn, Ty.FLOAT);
		for (int i = 2; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, Ty.FLOAT);
			fn.writer.write(Instruction.F64_DIV);
		}
		return Ty.FLOAT;
	}

	// (min ...) / (max ...). Floats use the native f64.min/max; integers fold via select
	// (wasm has no i64.min/max).
	private Ty compileMinMax(LispCons cons, List<LispVal> args, Fn fn, boolean min) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException("--no-gc: " + ((LispSymbol) args.get(0)).name()
					+ " needs at least one argument in '" + fn.fnName + "'");
		}
		Ty target = staticType(cons, fn);
		if (target == Ty.FLOAT) {
			compileCoerced(args.get(1), fn, Ty.FLOAT);
			for (int i = 2; i < args.size(); i++) {
				compileCoerced(args.get(i), fn, Ty.FLOAT);
				fn.writer.write(min ? Instruction.F64_MIN : Instruction.F64_MAX);
			}
			return Ty.FLOAT;
		}
		int acc = fn.allocLocal(Ty.INT);
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(acc);
		for (int i = 2; i < args.size(); i++) {
			int t = fn.allocLocal(Ty.INT);
			compileCoerced(args.get(i), fn, Ty.INT);
			fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(t);
			// select acc if (min ? acc<t : acc>t) else t
			fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(acc);
			fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(t);
			fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(acc);
			fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(t);
			fn.writer.write(min ? Instruction.I64_LT_S : Instruction.I64_GT_S);
			fn.writer.write(Instruction.SELECT);
			fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(acc);
		}
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(acc);
		return Ty.INT;
	}

	// (mod a b) takes the sign of the divisor; (rem a b) the sign of the dividend. For
	// integers: rem = i64.rem_s, mod = ((a rem b) + b) rem b. For floats: a -
	// b*round(a/b).
	private Ty compileModRem(LispCons cons, List<LispVal> args, Fn fn, boolean mod) {
		if (args.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: " + ((LispSymbol) args.get(0)).name()
					+ " takes exactly two arguments in '" + fn.fnName + "'");
		}
		Ty target = staticType(cons, fn);
		if (target == Ty.INT) {
			int a = fn.allocLocal(Ty.INT);
			int b = fn.allocLocal(Ty.INT);
			compileCoerced(args.get(1), fn, Ty.INT);
			fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(a);
			compileCoerced(args.get(2), fn, Ty.INT);
			fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(b);
			fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(a);
			fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
			fn.writer.write(Instruction.I64_REM_S);
			if (mod) {
				fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
				fn.writer.write(Instruction.I64_ADD);
				fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
				fn.writer.write(Instruction.I64_REM_S);
			}
			return Ty.INT;
		}
		int a = fn.allocLocal(Ty.FLOAT);
		int b = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(1), fn, Ty.FLOAT);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(a);
		compileCoerced(args.get(2), fn, Ty.FLOAT);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(b);
		// a - b * round(a / b)
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(a);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(a);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
		fn.writer.write(Instruction.F64_DIV);
		fn.writer.write(mod ? Instruction.F64_FLOOR : Instruction.F64_TRUNC);
		fn.writer.write(Instruction.F64_MUL);
		fn.writer.write(Instruction.F64_SUB);
		return Ty.FLOAT;
	}

	private Ty compileAbs(LispCons cons, List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: abs takes exactly one argument in '" + fn.fnName + "'");
		}
		Ty target = staticType(cons, fn);
		if (target == Ty.FLOAT) {
			compileCoerced(args.get(1), fn, Ty.FLOAT);
			fn.writer.write(Instruction.F64_ABS);
			return Ty.FLOAT;
		}
		int t = fn.allocLocal(Ty.INT);
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(t);
		// x < 0 ? 0 - x : x
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(t);
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.I64_LT_S);
		fn.writer.write(Instruction.IF).write(Type.I64.code());
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(t);
		fn.writer.write(Instruction.I64_SUB);
		fn.writer.write(Instruction.ELSE);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(t);
		fn.writer.write(Instruction.END);
		return Ty.INT;
	}

	// (float x): coerce to f64.
	private Ty compileFloat(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: float takes exactly one argument in '" + fn.fnName + "'");
		}
		compileCoerced(args.get(1), fn, Ty.FLOAT);
		return Ty.FLOAT;
	}

	// (sqrt x): f64.sqrt, always a float.
	private Ty compileSqrt(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: sqrt takes exactly one argument in '" + fn.fnName + "'");
		}
		compileCoerced(args.get(1), fn, Ty.FLOAT);
		fn.writer.write(Instruction.F64_SQRT);
		return Ty.FLOAT;
	}

	// (concatenate 'string s1 s2 ...): allocate a fresh [len][bytes] string holding the
	// concatenation of the operand strings. The first argument is the result-type
	// designator ('string); the rest are string values. Each operand is materialized into
	// an i32 local, the total content length is summed, a destination buffer is bump
	// allocated, and every operand's bytes are copied in via the __memcpy helper.
	private Ty compileConcatenate(List<LispVal> args, Fn fn) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException(
					"--no-gc: concatenate needs a result-type and operands in '" + fn.fnName + "'");
		}
		// This backend has no cons cells and no general array type, so only the string
		// family is buildable here (the GC backends take the list / vector families too).
		if (ConcatenateForms.literalResultFamily(args.get(1)) != ConcatenateForms.ResultFamily.STRING) {
			throw new UnsupportedOperationException(
					"--no-gc: concatenate supports only the literal 'string result type " + "in '" + fn.fnName
							+ "', got: " + args.get(1).print());
		}
		WasmWriter w = fn.writer;
		// Evaluate each operand string into its own i32 (STRING) local.
		List<Integer> operands = new ArrayList<>();
		for (int i = 2; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, Ty.STRING);
			int slot = fn.allocLocal(Ty.STRING); // i32 pointer
			w.write(Instruction.SET_LOCAL).writeSignedLeb128(slot);
			operands.add(slot);
		}
		// total = sum of each operand's stored length.
		int total = fn.allocLocal(Ty.STRING); // i32 scratch
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(total);
		for (int s : operands) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(total);
			emitStrLen(w, s);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL).writeSignedLeb128(total);
		}
		// dst = __alloc(4 + total); store the length header.
		int dst = fn.allocLocal(Ty.STRING); // i32 pointer
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(total);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(total);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// off = dst + 4; copy each operand's content bytes, advancing off.
		int off = fn.allocLocal(Ty.STRING); // i32 scratch
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(off);
		for (int s : operands) {
			// __memcpy(off, s + 4, len(s))
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(off);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(s);
			w.write(Instruction.I32_CONST).writeSignedLeb128(4);
			w.write(Instruction.I32_ADD);
			emitStrLen(w, s);
			w.write(Instruction.CALL).writeSignedLeb128(fn.mem.memcpyIndex());
			// off += len(s)
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(off);
			emitStrLen(w, s);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL).writeSignedLeb128(off);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return Ty.STRING;
	}

	// (length s): the stored i32 count header, widened to the i64 integer type. A string
	// [len:i32][bytes] and a float-vector [count:i32][f64...] both keep their element
	// count as the leading i32 word, so length reads either identically.
	private Ty compileLength(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: length takes one argument in '" + fn.fnName + "'");
		}
		if (args.get(1) instanceof LispNil) {
			// (length nil) == 0.
			i64Const(fn.writer, 0);
			return Ty.INT;
		}
		Ty t = compileExpr(args.get(1), fn);
		if (t != Ty.STRING && t != Ty.F64VEC && t != Ty.F32VEC) {
			throw new UnsupportedOperationException(
					"--no-gc: length expects a string or a float-vector in '" + fn.fnName + "'");
		}
		fn.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		fn.writer.write(Instruction.I64_EXTEND_U_I32);
		return Ty.INT;
	}

	// --- packed double-float vectors (F64VEC) ------------------------------------------
	//
	// A F64VEC is an i32 pointer to a linear-memory header [count:i32 LE][count f64 LE].
	// #d(...) literals and (make-array n :element-type 'double-float) materialize one;
	// the
	// generic aref/%aset/length operate on it. A rank-2 make-array builds the separate
	// F64MAT/F32MAT [rows:i32][cols:i32][data] matrix layout instead (for vec:matvec);
	// rank >= 3 stays a clear compile error, as does a rank-2 literal. The vectorizable
	// vec: kernels (v128) build on this layer -- see .kb/vec.md.

	private static void requireArgc(List<LispVal> args, int expected, String op, Fn fn) {
		if (args.size() != expected) {
			throw new UnsupportedOperationException(
					"--no-gc: " + op + " takes " + (expected - 1) + " argument(s) in '" + fn.fnName + "'");
		}
	}

	// dst = __alloc(4 + width*count); mem[dst] = count (the element-count header).
	// Returns
	// the i32 base-pointer local; count is read from countLocal. The default width is f64
	// (the F64VEC layout); allocVec(fn, count, vecTy) picks the stride from the element
	// width (f32 = 4-byte, f64 = 8-byte).
	private int allocVec(Fn fn, int countLocal) {
		return allocVec(fn, countLocal, Ty.F64VEC);
	}

	private int allocVec(Fn fn, int countLocal, Ty vecTy) {
		WasmWriter w = fn.writer;
		int dst = fn.allocLocal(vecTy);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(countLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL); // width*count
		w.write(Instruction.I32_ADD); // 4 + width*count
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(countLocal);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		return dst;
	}

	// Evaluates a vector-valued argument into a fresh local holding its base pointer (the
	// [count][data] block). Factored out because the -into kernels evaluate one more
	// vector
	// argument (the destination) than their allocating siblings, in argument order.
	private int compileVecArg(LispVal arg, Fn fn, Ty vecTy) {
		int slot = fn.allocLocal(vecTy);
		compileCoerced(arg, fn, vecTy);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(slot);
		return slot;
	}

	// Reads the [count] header word of the vector whose base pointer is in vecLocal into
	// a
	// fresh i32 local.
	private int loadVecCount(Fn fn, int vecLocal) {
		int count = fn.allocLocal(Ty.F64VEC);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(vecLocal);
		fn.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		return count;
	}

	// Emits base + 4 + (i << shift): the i32 address of element i, given the vector arg,
	// the index arg and the vector's element width (f64 = <<3, f32 = <<2).
	private void emitElementAddr(LispVal vec, LispVal idx, Fn fn, Ty vecTy) {
		WasmWriter w = fn.writer;
		compileCoerced(vec, fn, vecTy);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		compileCoerced(idx, fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
	}

	// A #d(...)/#f(...) packed float literal -> a fresh packed vector materialized in
	// linear memory. Only a rank-1 literal packs to a vector; a rank>=2 literal has no
	// packed rank-n layout on the scalar backend, so it is a clear compile error. The
	// count is known at read time, so each constant is stored with a straight-line store
	// (f64.store for #d, f32.store for #f). A #f element is emitted with the widening
	// f64.const + f32.demote_f64 trick (WasmWriter has no writeF32, and the demote is an
	// exact round-trip of the stored float).
	private Ty compileFloatArrayLiteral(LispFloatArray fa, Fn fn) {
		boolean single = fa instanceof am.ik.rontolisp.LispSingleFloatArray;
		if (fa.rank() != 1) {
			throw new UnsupportedOperationException("--no-gc: a multi-dimensional " + (single ? "#f" : "#d")
					+ "(...) literal (rank " + fa.rank() + ") in function '" + fn.fnName
					+ "' is not supported; only a rank-1 literal packs to a float vector");
		}
		Ty vecTy = single ? Ty.F32VEC : Ty.F64VEC;
		int width = single ? 4 : 8;
		WasmWriter w = fn.writer;
		int n = fa.totalSize();
		int count = fn.allocLocal(Ty.F64VEC); // i32 scratch
		w.write(Instruction.I32_CONST).writeSignedLeb128(n);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int dst = allocVec(fn, count, vecTy);
		for (int i = 0; i < n; i++) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
			w.write(Instruction.I32_CONST).writeSignedLeb128(4 + width * i);
			w.write(Instruction.I32_ADD);
			if (single) {
				f32Const(w, (float) fa.elementAt(i));
				w.write(Instruction.F32_STORE, 0x00, 0x00);
			}
			else {
				w.write(Instruction.F64_CONST).writeF64(fa.elementAt(i));
				w.write(Instruction.F64_STORE, 0x00, 0x00);
			}
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// Pushes an f32 constant via the widening f64.const + f32.demote_f64 trick
	// (WasmWriter
	// has no writeF32). (double) value is the exact widening and demote narrows back to
	// the
	// same f32 bits, so the round-trip is lossless.
	private static void f32Const(WasmWriter w, float value) {
		WasmVecLoops.f32Const(w, value);
	}

	// The packed width of a vector operand at code-gen time: F32VEC if it statically
	// infers
	// to a single-float vector, else F64VEC. Defaulting the bottom/unknown case to F64VEC
	// keeps every existing double-only program byte-identical (only a genuine #f /
	// single-float operand takes the f32 path).
	private Ty packedVecType(LispVal vec, Fn fn) {
		return staticType(vec, fn) == Ty.F32VEC ? Ty.F32VEC : Ty.F64VEC;
	}

	// (aref v i) / (row-major-aref v i) -> the i-th element as a scalar f64 (a f32
	// element
	// is widened on read). No bounds check, like the rest of the backend. On a rank-1
	// vector exactly one subscript is allowed; on a rank-2 matrix aref takes two
	// subscripts (row-major (i*cols + j)) and row-major-aref one flat index.
	private Ty compileAref(String name, List<LispVal> args, Fn fn) {
		if (args.size() < 3) {
			requireArgc(args, 3, name, fn);
		}
		Ty t = staticType(args.get(1), fn);
		boolean single;
		if (t == Ty.F64MAT || t == Ty.F32MAT) {
			single = t == Ty.F32MAT;
			if (LispNames.AREF.equals(name)) {
				requireArgc(args, 4, name + " on a rank-2 matrix", fn);
				emitMatElementAddr(args.get(1), args.get(2), args.get(3), fn, t);
			}
			else {
				requireArgc(args, 3, name + " on a rank-2 matrix", fn);
				emitMatElementAddr(args.get(1), args.get(2), null, fn, t);
			}
		}
		else {
			requireArgc(args, 3, name, fn);
			Ty vecTy = packedVecType(args.get(1), fn);
			single = vecTy == Ty.F32VEC;
			emitElementAddr(args.get(1), args.get(2), fn, vecTy);
		}
		if (single) {
			fn.writer.write(Instruction.F32_LOAD, 0x00, 0x00);
			fn.writer.write(Instruction.F64_PROMOTE_F32);
		}
		else {
			fn.writer.write(Instruction.F64_LOAD, 0x00, 0x00);
		}
		return Ty.FLOAT;
	}

	// Emits the i32 address of matrix element (i, j) -- base + 8 + ((i*cols + j) <<
	// shift), reading cols out of the block header -- or of flat row-major element i
	// when jExpr is null. The matrix, row and column expressions are evaluated in
	// argument order.
	private void emitMatElementAddr(LispVal mat, LispVal iExpr, @Nullable LispVal jExpr, Fn fn, Ty matTy) {
		WasmWriter w = fn.writer;
		int base = compileVecArg(mat, fn, matTy);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(base);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		compileCoerced(iExpr, fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		if (jExpr != null) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(base);
			w.write(Instruction.I32_LOAD, 0x02, 0x04); // cols
			w.write(Instruction.I32_MUL);
			compileCoerced(jExpr, fn, Ty.INT);
			w.write(Instruction.I32_WRAP_I64);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(matTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
	}

	// (%aset v i x) / (%row-major-aset v i x) -> store x at element i, returning the
	// stored
	// value so a setf place reads back the assigned value. On a f32 vector the value is
	// narrowed (f32.demote_f64) before the store, and the returned value is the same
	// f32-round-tripped double (promote(demote(x))) so the read-back matches the other
	// backends' aset return across widths. On a rank-2 matrix %aset takes two subscripts
	// and %row-major-aset a flat index, like compileAref.
	private Ty compileAset(String name, List<LispVal> args, Fn fn) {
		if (args.size() < 4) {
			requireArgc(args, 4, name, fn);
		}
		WasmWriter w = fn.writer;
		Ty t = staticType(args.get(1), fn);
		boolean mat = t == Ty.F64MAT || t == Ty.F32MAT;
		boolean single;
		int addr = fn.allocLocal(Ty.F64VEC); // i32 element address
		int val = fn.allocLocal(Ty.FLOAT);
		LispVal valueExpr;
		if (mat) {
			single = t == Ty.F32MAT;
			if (LispNames.ASET.equals(name)) {
				requireArgc(args, 5, name + " on a rank-2 matrix", fn);
				emitMatElementAddr(args.get(1), args.get(2), args.get(3), fn, t);
				valueExpr = args.get(4);
			}
			else {
				requireArgc(args, 4, name + " on a rank-2 matrix", fn);
				emitMatElementAddr(args.get(1), args.get(2), null, fn, t);
				valueExpr = args.get(3);
			}
		}
		else {
			requireArgc(args, 4, name, fn);
			Ty vecTy = packedVecType(args.get(1), fn);
			single = vecTy == Ty.F32VEC;
			emitElementAddr(args.get(1), args.get(2), fn, vecTy);
			valueExpr = args.get(3);
		}
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(addr);
		compileCoerced(valueExpr, fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(val);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(addr);
		if (single) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(val);
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
			// return promote(demote(val)) -- the value as actually stored (f32-rounded).
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(val);
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F64_PROMOTE_F32);
		}
		else {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(val);
			w.write(Instruction.F64_STORE, 0x00, 0x00);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(val);
		}
		return Ty.FLOAT;
	}

	// (make-array n :element-type 'double-float | 'single-float [:initial-element x]) ->
	// a
	// fresh packed vector of n elements, filled with x (default 0.0). An :element-type is
	// required (the scalar backend has no general array type); the
	// fill-pointer/adjustable/displaced options a packed vector cannot represent are hard
	// errors. A single-float array uses a 4-byte f32 stride and narrows the fill on
	// store. A rank-2 dimension spec ((list d n) or '(d n)) builds the packed matrix
	// layout instead (compileMakeMatrix); rank >= 3 stays a clear compile error.
	private Ty compileMakeArray(List<LispVal> args, Fn fn) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException("--no-gc: make-array needs a dimension in '" + fn.fnName + "'");
		}
		LispVal elementType = findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD);
		boolean single = isSingleFloatElementType(elementType);
		if (!single && !isDoubleFloatElementType(elementType)) {
			throw new UnsupportedOperationException(
					"--no-gc: make-array is only supported with :element-type " + "'double-float or 'single-float in '"
							+ fn.fnName + "' (the scalar backend has no general array " + "type)");
		}
		if (findKeywordValue(args, LispNames.FILL_POINTER_KEYWORD) != null
				|| findKeywordValue(args, LispNames.ADJUSTABLE_KEYWORD) != null
				|| findKeywordValue(args, LispNames.DISPLACED_TO_KEYWORD) != null) {
			throw new UnsupportedOperationException("--no-gc: make-array :fill-pointer / :adjustable / :displaced-to "
					+ "is not supported on a packed float-vector in '" + fn.fnName + "'");
		}
		List<LispVal> dims = dimExprs(args.get(1));
		if (dims.size() == 2) {
			return compileMakeMatrix(dims, args, fn, single);
		}
		if (dims.size() != 1) {
			throw new UnsupportedOperationException("--no-gc: a rank-" + dims.size() + " make-array in '" + fn.fnName
					+ "' is not supported; a packed float array is rank-1 (a vector) or rank-2 (a matrix) here");
		}
		Ty vecTy = single ? Ty.F32VEC : Ty.F64VEC;
		LispVal lengthExpr = dims.get(0);
		WasmWriter w = fn.writer;
		int count = fn.allocLocal(Ty.F64VEC); // i32 element count
		compileCoerced(lengthExpr, fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int dst = allocVec(fn, count, vecTy);
		// Evaluate the fill value once into a f64 local (:initial-element, default 0.0).
		LispVal init = findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD);
		int fill = fn.allocLocal(Ty.FLOAT);
		if (init == null) {
			w.write(Instruction.F64_CONST).writeF64(0.0);
		}
		else {
			compileCoerced(init, fn, Ty.FLOAT);
		}
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(fill);
		// for (i = 0; i < count; i++) mem[dst + 4 + (i << shift)] = fill (narrowed for
		// f32)
		int i = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(fill);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// The per-dimension expressions of a make-array dimension spec. Accepts an integer /
	// runtime expression (a rank-1 length directly), a quoted literal list '(d n) (its
	// elements are plain data), or a (list d n) form (its elements are runtime
	// expressions). The returned size is the rank; compileMakeArray keys the layout off
	// it (1 = packed vector, 2 = packed matrix, else a clear compile error).
	private static List<LispVal> dimExprs(LispVal dimsArg) {
		LispVal spec = dimsArg;
		if (spec instanceof LispCons c && c.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& c.cdr() instanceof LispCons rest) {
			spec = rest.car();
			if (spec instanceof LispCons list) {
				return list.toList();
			}
			return List.of(spec);
		}
		if (spec instanceof LispCons c && c.car() instanceof LispSymbol head && LispNames.LIST.equals(head.name())) {
			List<LispVal> parts = c.toList();
			return parts.subList(1, parts.size());
		}
		return List.of(dimsArg);
	}

	// The rank-2 branch of compileMakeArray: (make-array (list d n) :element-type ...)
	// -> a fresh packed matrix [rows:i32][cols:i32][rows*cols f... row-major], filled
	// with :initial-element (default 0.0) like the rank-1 path. The 8-byte two-word
	// header replaces the vector's [count] word; everything below it is the same packed
	// data the vector layout uses, at the same stride.
	private Ty compileMakeMatrix(List<LispVal> dims, List<LispVal> args, Fn fn, boolean single) {
		Ty matTy = single ? Ty.F32MAT : Ty.F64MAT;
		WasmWriter w = fn.writer;
		int rows = fn.allocLocal(Ty.F64VEC); // i32 row count
		compileCoerced(dims.get(0), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(rows);
		int cols = fn.allocLocal(Ty.F64VEC); // i32 column count
		compileCoerced(dims.get(1), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(cols);
		int total = fn.allocLocal(Ty.F64VEC); // i32 element count rows*cols
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(rows);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(cols);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(total);
		// dst = __alloc(8 + (total << shift)); mem[dst] = rows; mem[dst+4] = cols
		int dst = fn.allocLocal(matTy);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(total);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(matTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(rows);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(cols);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		// Evaluate the fill value once into a f64 local (:initial-element, default 0.0).
		LispVal init = findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD);
		int fill = fn.allocLocal(Ty.FLOAT);
		if (init == null) {
			w.write(Instruction.F64_CONST).writeF64(0.0);
		}
		else {
			compileCoerced(init, fn, Ty.FLOAT);
		}
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(fill);
		// for (i = 0; i < total; i++) mem[dst + 8 + (i << shift)] = fill (narrowed for
		// f32)
		int i = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(total);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(matTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(fill);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return matTy;
	}

	// The value following a :keyword in a flat argument list (scanning the keyword pairs
	// after the single positional dimension), or null if absent. Mirrors the wasm-GC/JVM
	// WasmArrayCompiler.findKeywordValue.
	private static @Nullable LispVal findKeywordValue(List<LispVal> args, String keyword) {
		for (int i = 2; i + 1 < args.size(); i += 2) {
			if (args.get(i) instanceof LispSymbol kw && keyword.equals(kw.name())) {
				return args.get(i + 1);
			}
		}
		return null;
	}

	// Whether an :element-type value designates double-float (the only packed element
	// type
	// on this backend), unwrapping a (quote double-float) and ignoring any package
	// qualifier. Mirrors WasmArrayCompiler.isDoubleFloatElementType.
	private static boolean isDoubleFloatElementType(@Nullable LispVal elementType) {
		return elementTypeNameIs(elementType, LispNames.DOUBLE_FLOAT);
	}

	// Whether an :element-type value designates single-float (the f32 packed width),
	// unwrapping a (quote single-float) and ignoring any package qualifier. Mirrors
	// isDoubleFloatElementType / WasmArrayCompiler.isSingleFloatElementType.
	private static boolean isSingleFloatElementType(@Nullable LispVal elementType) {
		return elementTypeNameIs(elementType, LispNames.SINGLE_FLOAT);
	}

	// Shared unwrap for an :element-type designator: strips a (quote <sym>) wrapper and
	// any
	// package qualifier, then compares the bare name.
	private static boolean elementTypeNameIs(@Nullable LispVal elementType, String expected) {
		LispVal sym = elementType;
		if (sym instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			sym = rest.car();
		}
		if (sym instanceof LispSymbol s) {
			String name = s.name();
			int colon = name.lastIndexOf(':');
			return (colon >= 0 ? name.substring(colon + 1) : name).equals(expected);
		}
		return false;
	}

	// --- vec: package (native WASM SIMD over the packed f64 vector)
	// --------------------
	//
	// On this backend a simd vector is the same packed [count:i32][count f64] block that
	// a
	// #d(...) literal and (make-array :element-type 'double-float) produce. The vec:
	// kernels are lowered here to real fixed-width SIMD: the element-wise ops walk the
	// block
	// two lanes at a time with v128 / f64x2.* and a scalar tail, and the reductions
	// accumulate in a v128 lane pair then fold horizontally. The scalar vec.lisp
	// reference
	// is NOT spliced on --no-gc (it needs a general array type); every vec: member is
	// intercepted here instead. Construction / access verbs delegate to the shared packed
	// helpers (allocVec / emitElementAddr / compileAref / compileAset / compileLength).

	// The vec: members this backend lowers natively.
	private static final Set<String> SIMD_MEMBERS = Set.of(LispNames.VEC_ZEROS, LispNames.VEC_ONES,
			LispNames.VEC_ARANGE, LispNames.VEC_AREF, LispNames.VEC_ASET, LispNames.VEC_LENGTH, LispNames.VEC_ADD,
			LispNames.VEC_SUB, LispNames.VEC_MUL, LispNames.VEC_SCALE, LispNames.VEC_SUM, LispNames.VEC_MEAN,
			LispNames.VEC_DOT, LispNames.VEC_NORM, LispNames.VEC_ADD_INTO, LispNames.VEC_SUB_INTO,
			LispNames.VEC_MUL_INTO, LispNames.VEC_SCALE_INTO, LispNames.VEC_SQRT, LispNames.VEC_ABS,
			LispNames.VEC_SQUARE, LispNames.VEC_NEGATIVE, LispNames.VEC_RECIPROCAL, LispNames.VEC_EXP,
			LispNames.VEC_LOG, LispNames.VEC_TANH, LispNames.VEC_SIN, LispNames.VEC_COS, LispNames.VEC_TAN,
			LispNames.VEC_ASIN, LispNames.VEC_ACOS, LispNames.VEC_ATAN, LispNames.VEC_SINH, LispNames.VEC_COSH,
			LispNames.VEC_SIGN, LispNames.VEC_SQRT_INTO, LispNames.VEC_ABS_INTO, LispNames.VEC_SQUARE_INTO,
			LispNames.VEC_NEGATIVE_INTO, LispNames.VEC_RECIPROCAL_INTO, LispNames.VEC_EXP_INTO, LispNames.VEC_LOG_INTO,
			LispNames.VEC_TANH_INTO, LispNames.VEC_SIN_INTO, LispNames.VEC_COS_INTO, LispNames.VEC_TAN_INTO,
			LispNames.VEC_ASIN_INTO, LispNames.VEC_ACOS_INTO, LispNames.VEC_ATAN_INTO, LispNames.VEC_SINH_INTO,
			LispNames.VEC_COSH_INTO, LispNames.VEC_SIGN_INTO, LispNames.VEC_MAXIMUM, LispNames.VEC_MINIMUM,
			LispNames.VEC_RELU, LispNames.VEC_CLIP, LispNames.VEC_MAXIMUM_INTO, LispNames.VEC_MINIMUM_INTO,
			LispNames.VEC_RELU_INTO, LispNames.VEC_CLIP_INTO, LispNames.VEC_MATVEC, LispNames.VEC_MATVEC_INTO);

	// simd members that exist in the package but need cons lists (which --no-gc lacks),
	// so
	// they run only on the portable backends via vec.lisp.
	private static final Set<String> SIMD_PORTABLE_ONLY = Set.of(LispNames.VEC_FROM_LIST, LispNames.VEC_TO_LIST);

	// Whether a (resolved) symbol name is a vec: package member, e.g. "vec:dot". vec:
	// names are always qualified with the package prefix, so a prefix test suffices.
	private static boolean isSimdCall(String name) {
		return name.startsWith(LispNames.VEC_PKG + ":");
	}

	// The member part of a vec: qualified name ("vec:dot" -> "dot").
	private static String simdMember(String name) {
		return name.substring(name.lastIndexOf(':') + 1);
	}

	private void requireKnownSimd(String name, String fnName) {
		String member = simdMember(name);
		if (SIMD_MEMBERS.contains(member)) {
			return;
		}
		if (SIMD_PORTABLE_ONLY.contains(member)) {
			throw new UnsupportedOperationException("--no-gc: '" + name + "' in function '" + fnName
					+ "' needs Lisp lists and runs on the portable backends only, not --no-gc");
		}
		throw new UnsupportedOperationException(
				"--no-gc: unknown simd operation '" + name + "' in function '" + fnName + "'");
	}

	// The inferred result type of a simd kernel: the constructors and element-wise
	// kernels
	// yield a vector, length an integer, and element access / reductions a float. The
	// argument expressions are still walked so their call sites and local mutations are
	// recorded during inference.
	private Ty typeOfSimd(String name, List<LispVal> args, Map<String, Ty> env, TC tc) {
		// Walk the argument expressions (recording call sites / local mutations) and note
		// the width of the first vector operand -- an element-wise / scale result
		// preserves
		// the operand width (a f32 vector in yields a f32 vector out), while a
		// constructor's width comes from its optional literal element-type
		// (constructorVecType: 'single-float -> F32VEC, else F64VEC).
		Ty firstVecWidth = null;
		Ty[] argTys = new Ty[args.size()];
		for (int i = 1; i < args.size(); i++) {
			argTys[i] = typeOf(args.get(i), env, tc);
			if (firstVecWidth == null && (argTys[i] == Ty.F64VEC || argTys[i] == Ty.F32VEC)) {
				firstVecWidth = argTys[i];
			}
		}
		Ty operandWidth = firstVecWidth == null ? Ty.F64VEC : firstVecWidth;
		return switch (simdMember(name)) {
			case LispNames.VEC_ZEROS, LispNames.VEC_ONES, LispNames.VEC_ARANGE -> constructorVecType(args);
			// An -into kernel returns its destination, which is argument 1 -- the same
			// slot
			// the allocating kernels take their first operand from, so firstVecWidth
			// already
			// holds the right width for both shapes.
			case LispNames.VEC_ADD, LispNames.VEC_SUB, LispNames.VEC_MUL, LispNames.VEC_SCALE, LispNames.VEC_ADD_INTO,
					LispNames.VEC_SUB_INTO, LispNames.VEC_MUL_INTO, LispNames.VEC_SCALE_INTO, LispNames.VEC_SQRT,
					LispNames.VEC_ABS, LispNames.VEC_SQUARE, LispNames.VEC_NEGATIVE, LispNames.VEC_RECIPROCAL,
					LispNames.VEC_EXP, LispNames.VEC_LOG, LispNames.VEC_TANH, LispNames.VEC_SIN, LispNames.VEC_COS,
					LispNames.VEC_TAN, LispNames.VEC_ASIN, LispNames.VEC_ACOS, LispNames.VEC_ATAN, LispNames.VEC_SINH,
					LispNames.VEC_COSH, LispNames.VEC_SIGN, LispNames.VEC_SQRT_INTO, LispNames.VEC_ABS_INTO,
					LispNames.VEC_SQUARE_INTO, LispNames.VEC_NEGATIVE_INTO, LispNames.VEC_RECIPROCAL_INTO,
					LispNames.VEC_EXP_INTO, LispNames.VEC_LOG_INTO, LispNames.VEC_TANH_INTO, LispNames.VEC_SIN_INTO,
					LispNames.VEC_COS_INTO, LispNames.VEC_TAN_INTO, LispNames.VEC_ASIN_INTO, LispNames.VEC_ACOS_INTO,
					LispNames.VEC_ATAN_INTO, LispNames.VEC_SINH_INTO, LispNames.VEC_COSH_INTO, LispNames.VEC_SIGN_INTO,
					LispNames.VEC_MAXIMUM, LispNames.VEC_MINIMUM, LispNames.VEC_RELU, LispNames.VEC_CLIP,
					LispNames.VEC_MAXIMUM_INTO, LispNames.VEC_MINIMUM_INTO, LispNames.VEC_RELU_INTO,
					LispNames.VEC_CLIP_INTO ->
				operandWidth;
			// matvec's result is a rank-1 vector following x's width (W is a matrix, so
			// firstVecWidth would miss it); matvec-into returns its destination (arg 1).
			case LispNames.VEC_MATVEC -> args.size() > 2 && argTys[2] == Ty.F32VEC ? Ty.F32VEC : Ty.F64VEC;
			case LispNames.VEC_MATVEC_INTO -> args.size() > 1 && argTys[1] == Ty.F32VEC ? Ty.F32VEC : Ty.F64VEC;
			case LispNames.VEC_LENGTH -> Ty.INT;
			default -> Ty.FLOAT; // aref, aset, sum, mean, dot, norm
		};
	}

	// Fill modes for the vector constructors.
	private static final int FILL_ZERO = 0;

	private static final int FILL_ONE = 1;

	private static final int FILL_ARANGE = 2;

	private Ty compileSimd(String name, List<LispVal> args, Fn fn) {
		return switch (simdMember(name)) {
			case LispNames.VEC_ZEROS -> compileSimdConstruct(args, fn, FILL_ZERO);
			case LispNames.VEC_ONES -> compileSimdConstruct(args, fn, FILL_ONE);
			case LispNames.VEC_ARANGE -> compileSimdConstruct(args, fn, FILL_ARANGE);
			// aref / aset / length are the generic packed ops over the same block.
			case LispNames.VEC_LENGTH -> compileLength(args, fn);
			case LispNames.VEC_AREF -> compileAref(name, args, fn);
			case LispNames.VEC_ASET -> compileAset(name, args, fn);
			case LispNames.VEC_ADD ->
				compileSimdElementwise(args, fn, Instruction.F64X2_ADD, Instruction.F64_ADD, false);
			case LispNames.VEC_SUB ->
				compileSimdElementwise(args, fn, Instruction.F64X2_SUB, Instruction.F64_SUB, false);
			case LispNames.VEC_MUL ->
				compileSimdElementwise(args, fn, Instruction.F64X2_MUL, Instruction.F64_MUL, false);
			case LispNames.VEC_SCALE -> compileSimdScale(args, fn, false);
			// The destination-passing kernels: same loops, but the destination is the
			// caller's vector instead of a fresh allocVec block -- so a loop over them
			// never advances the bump allocator.
			case LispNames.VEC_ADD_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_ADD, Instruction.F64_ADD, true);
			case LispNames.VEC_SUB_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_SUB, Instruction.F64_SUB, true);
			case LispNames.VEC_MUL_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_MUL, Instruction.F64_MUL, true);
			case LispNames.VEC_SCALE_INTO -> compileSimdScale(args, fn, true);
			// The arithmetic unary ufuncs: NATIVE IEEE per-element semantics (this
			// backend has no vec.lisp defun to mirror; see WasmVecLoops.simdMap1). The
			// transcendentals -- exp / log / tanh / sin / cos / tan / asin / acos / atan
			// / sinh / cosh / sign -- reuse the GC backend's raw-f64 emitters instead
			// (see compileSimdUnaryF64).
			case LispNames.VEC_SQRT -> compileSimdUnary(args, fn, WasmVecLoops.U_SQRT, false, "vec:sqrt");
			case LispNames.VEC_ABS -> compileSimdUnary(args, fn, WasmVecLoops.U_ABS, false, "vec:abs");
			case LispNames.VEC_SQUARE -> compileSimdUnary(args, fn, WasmVecLoops.U_SQUARE, false, "vec:square");
			case LispNames.VEC_NEGATIVE -> compileSimdUnary(args, fn, WasmVecLoops.U_NEG, false, "vec:negative");
			case LispNames.VEC_RECIPROCAL -> compileSimdUnary(args, fn, WasmVecLoops.U_RECIP, false, "vec:reciprocal");
			case LispNames.VEC_SQRT_INTO -> compileSimdUnary(args, fn, WasmVecLoops.U_SQRT, true, "vec:sqrt-into");
			case LispNames.VEC_ABS_INTO -> compileSimdUnary(args, fn, WasmVecLoops.U_ABS, true, "vec:abs-into");
			case LispNames.VEC_SQUARE_INTO ->
				compileSimdUnary(args, fn, WasmVecLoops.U_SQUARE, true, "vec:square-into");
			case LispNames.VEC_NEGATIVE_INTO ->
				compileSimdUnary(args, fn, WasmVecLoops.U_NEG, true, "vec:negative-into");
			case LispNames.VEC_RECIPROCAL_INTO ->
				compileSimdUnary(args, fn, WasmVecLoops.U_RECIP, true, "vec:reciprocal-into");
			case LispNames.VEC_EXP ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_EXP, false, "vec:exp");
			case LispNames.VEC_LOG ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_LOG, false, "vec:log");
			case LispNames.VEC_TANH ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_TANH, false, "vec:tanh");
			case LispNames.VEC_SIN ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_SIN, false, "vec:sin");
			case LispNames.VEC_COS ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_COS, false, "vec:cos");
			case LispNames.VEC_TAN ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_TAN, false, "vec:tan");
			case LispNames.VEC_ASIN ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_ASIN, false, "vec:asin");
			case LispNames.VEC_ACOS ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_ACOS, false, "vec:acos");
			case LispNames.VEC_ATAN ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_ATAN, false, "vec:atan");
			case LispNames.VEC_SINH ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_SINH, false, "vec:sinh");
			case LispNames.VEC_COSH ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_COSH, false, "vec:cosh");
			case LispNames.VEC_SIGN ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_SIGN, false, "vec:sign");
			case LispNames.VEC_EXP_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_EXP, true, "vec:exp-into");
			case LispNames.VEC_LOG_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_LOG, true, "vec:log-into");
			case LispNames.VEC_TANH_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_TANH, true, "vec:tanh-into");
			case LispNames.VEC_SIN_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_SIN, true, "vec:sin-into");
			case LispNames.VEC_COS_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_COS, true, "vec:cos-into");
			case LispNames.VEC_TAN_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_TAN, true, "vec:tan-into");
			case LispNames.VEC_ASIN_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_ASIN, true, "vec:asin-into");
			case LispNames.VEC_ACOS_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_ACOS, true, "vec:acos-into");
			case LispNames.VEC_ATAN_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_ATAN, true, "vec:atan-into");
			case LispNames.VEC_SINH_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_SINH, true, "vec:sinh-into");
			case LispNames.VEC_COSH_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_COSH, true, "vec:cosh-into");
			case LispNames.VEC_SIGN_INTO ->
				compileSimdUnaryF64(args, fn, WasmVecSimdRuntimeBuilder.SCALAR_OP_SIGN, true, "vec:sign-into");
			// The comparison-select ufuncs: the strict-comparison selects every other
			// backend's defun states ((if (> x y) x y) and its mirrors), never the IEEE
			// min/max instructions -- the second operand or the bound wins any false
			// comparison (NaN and the -0.0/0.0 tie included), so
			// this backend agrees with the cross-backend contract. relu rides the U_RELU
			// map1 form (v128 under --simd, scalar loop otherwise); clip is an element
			// loop comparing the widened element against the two FULL f64 bounds in both
			// --simd modes, like exp.
			case LispNames.VEC_MAXIMUM -> compileSimdSelectElementwise(args, fn, true, false, "vec:maximum");
			case LispNames.VEC_MINIMUM -> compileSimdSelectElementwise(args, fn, false, false, "vec:minimum");
			case LispNames.VEC_RELU -> compileSimdUnary(args, fn, WasmVecLoops.U_RELU, false, "vec:relu");
			case LispNames.VEC_CLIP -> compileSimdClip(args, fn, false, "vec:clip");
			case LispNames.VEC_MAXIMUM_INTO -> compileSimdSelectElementwise(args, fn, true, true, "vec:maximum-into");
			case LispNames.VEC_MINIMUM_INTO -> compileSimdSelectElementwise(args, fn, false, true, "vec:minimum-into");
			case LispNames.VEC_RELU_INTO -> compileSimdUnary(args, fn, WasmVecLoops.U_RELU, true, "vec:relu-into");
			case LispNames.VEC_CLIP_INTO -> compileSimdClip(args, fn, true, "vec:clip-into");
			case LispNames.VEC_SUM -> compileSimdSum(args, fn);
			case LispNames.VEC_DOT -> compileSimdDot(args, fn);
			case LispNames.VEC_MATVEC -> compileSimdMatvec(args, fn, false);
			case LispNames.VEC_MATVEC_INTO -> compileSimdMatvec(args, fn, true);
			// mean and norm are composites over sum/dot/length -- expand and recompile so
			// there is one definition (also matching what the portable vec.lisp does).
			case LispNames.VEC_MEAN -> compileExpr(simdMeanExpansion(args, fn), fn);
			case LispNames.VEC_NORM -> compileExpr(simdNormExpansion(args, fn), fn);
			default -> throw new UnsupportedOperationException(
					"--no-gc: unknown simd operation '" + name + "' in '" + fn.fnName + "'");
		};
	}

	// The synthetic local name the mean/norm expansions bind the argument to (a
	// %-prefixed
	// internal name, so it never collides with a user local; nested uses shadow
	// correctly).
	private static final String SIMD_REDUCE_TMP = "%simd-reduce-arg";

	// (vec:mean v) => (let ((%v v)) (/ (vec:sum %v) (vec:length %v)))
	private LispVal simdMeanExpansion(List<LispVal> args, Fn fn) {
		requireArgc(args, 2, "vec:mean", fn);
		LispVal body = list(sym(LispNames.DIV), list(simdSym(LispNames.VEC_SUM), sym(SIMD_REDUCE_TMP)),
				list(simdSym(LispNames.VEC_LENGTH), sym(SIMD_REDUCE_TMP)));
		return simdLetOverArg(args.get(1), body);
	}

	// (vec:norm v) => (let ((%v v)) (sqrt (vec:dot %v %v)))
	private LispVal simdNormExpansion(List<LispVal> args, Fn fn) {
		requireArgc(args, 2, "vec:norm", fn);
		LispVal body = list(sym(LispNames.SQRT),
				list(simdSym(LispNames.VEC_DOT), sym(SIMD_REDUCE_TMP), sym(SIMD_REDUCE_TMP)));
		return simdLetOverArg(args.get(1), body);
	}

	private static LispVal simdLetOverArg(LispVal arg, LispVal body) {
		LispVal bindings = list(list(sym(SIMD_REDUCE_TMP), arg));
		return list(sym(LispNames.LET), bindings, body);
	}

	private static LispSymbol sym(String name) {
		return new LispSymbol(name);
	}

	private static LispSymbol simdSym(String member) {
		return new LispSymbol(LispNames.VEC_PKG + ":" + member);
	}

	private static LispVal list(LispVal... items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			tail = new LispCons(items[i], tail);
		}
		return tail;
	}

	// Emits the SIMD prefix (0xFD) then the u32-LEB sub-opcode. Sub-opcodes above 127
	// (e.g.
	// f64x2.add = 0xF0) MUST use the LEB path, so this never uses the single-byte writer.
	private static void simd(WasmWriter w, int subOpcode) {
		WasmVecLoops.simd(w, subOpcode);
	}

	// out = base + 4 (skip the count header to reach the packed f64 data).
	private static void dataPtr(WasmWriter w, int baseLocal, int outLocal) {
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(baseLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(outLocal);
	}

	// count = i32.wrap(the length argument) into a fresh i32 local.
	private int compileCountArg(LispVal arg, Fn fn) {
		WasmWriter w = fn.writer;
		int count = fn.allocLocal(Ty.F64VEC);
		compileCoerced(arg, fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		return count;
	}

	private static String fillModeName(int fillMode) {
		return switch (fillMode) {
			case FILL_ONE -> LispNames.VEC_ONES;
			case FILL_ARANGE -> LispNames.VEC_ARANGE;
			default -> LispNames.VEC_ZEROS;
		};
	}

	// The width a vec:zeros/ones/arange call constructs: F32VEC when a literal
	// 'single-float is passed as the optional second argument, else F64VEC (the double
	// default). Mirrors make-array's :element-type keying and vec::%make in vec.lisp.
	private static Ty constructorVecType(List<LispVal> args) {
		return args.size() >= 3 && isSingleFloatElementType(args.get(2)) ? Ty.F32VEC : Ty.F64VEC;
	}

	// (vec:zeros n [et]) / (vec:ones n [et]) -> a fresh constant-filled vector;
	// (vec:arange n [et]) -> [0.0, 1.0, ..., n-1]. A literal 'single-float second
	// argument builds an F32VEC (f32 stride + a narrowing store); the default F64VEC path
	// is byte-identical to before.
	private Ty compileSimdConstruct(List<LispVal> args, Fn fn, int fillMode) {
		if (args.size() != 2 && args.size() != 3) {
			throw new UnsupportedOperationException(
					"--no-gc: vec:" + fillModeName(fillMode) + " takes 1 or 2 arguments in '" + fn.fnName + "'");
		}
		Ty vecTy = constructorVecType(args);
		boolean single = vecTy == Ty.F32VEC;
		WasmWriter w = fn.writer;
		int count = compileCountArg(args.get(1), fn);
		int dst = allocVec(fn, count, vecTy);
		// for (i = 0; i < count; i++) mem[dst+4+width*i] = <fill>
		int i = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// addr = dst + 4 + (i << shift)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		if (fillMode == FILL_ARANGE) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
			w.write(Instruction.F64_CONVERT_S_I32);
			if (single) {
				w.write(Instruction.F32_DEMOTE_F64);
			}
		}
		else if (single) {
			f32Const(w, fillMode == FILL_ONE ? 1.0f : 0.0f);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(fillMode == FILL_ONE ? 1.0 : 0.0);
		}
		w.write(single ? Instruction.F32_STORE : Instruction.F64_STORE, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// (vec:add a b) / (vec:sub a b) / (vec:mul a b): element-wise into a fresh vector.
	// Two f64 lanes per iteration via v128.load + f64x2.<op> + v128.store, then a
	// one-element scalar tail when the length is odd. The loop itself is
	// WasmVecLoops.simdMap2 (shared with the wasm-GC --simd kernels); only the argument
	// evaluation + block allocation below are --no-gc-specific.
	private Ty compileSimdElementwise(List<LispVal> args, Fn fn, int simdOp, int scalarOp, boolean into) {
		requireArgc(args, into ? 4 : 3, into ? "a simd element-wise -into kernel" : "a simd element-wise kernel", fn);
		Ty vecTy = packedVecType(args.get(1), fn);
		if (!this.simd) {
			return compileScalarElementwise(args, fn, scalarOp, into);
		}
		if (vecTy == Ty.F32VEC) {
			return compileSimdElementwiseF32(args, fn, simdOp, scalarOp, into);
		}
		WasmWriter w = fn.writer;
		// -into evaluates the destination first (it is argument 1), then the two
		// operands;
		// the plain kernel allocates the destination after sizing it from operand a.
		int dstL = into ? compileVecArg(args.get(1), fn, Ty.F64VEC) : -1;
		int aL = compileVecArg(args.get(into ? 2 : 1), fn, Ty.F64VEC);
		int bL = compileVecArg(args.get(into ? 3 : 2), fn, Ty.F64VEC);
		int count = loadVecCount(fn, aL);
		int dst = into ? dstL : allocVec(fn, count);
		int ap = fn.allocLocal(Ty.F64VEC);
		int bp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdMap2(w, dp, ap, bp, count, rem, -1, false, simdOp, scalarOp);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return Ty.F64VEC;
	}

	// (vec:sqrt v) / (vec:abs v) / (vec:square v) / (vec:negative v) /
	// (vec:reciprocal v) and their -into siblings: element-wise unary into a fresh
	// vector (or the caller's destination, which MAY alias v -- element i depends only
	// on element i, the add-into rule). Native IEEE semantics at the operand's own
	// width; under --simd whole v128 groups plus the usual scalar tail, otherwise a
	// plain one-element-per-iteration loop -- the two lowerings compute identical
	// results (every op is exact or correctly rounded per element).
	private Ty compileSimdUnary(List<LispVal> args, Fn fn, int uop, boolean into, String what) {
		requireArgc(args, into ? 3 : 2, what, fn);
		Ty vecTy = packedVecType(args.get(1), fn);
		boolean single = vecTy == Ty.F32VEC;
		WasmWriter w = fn.writer;
		// -into evaluates the destination first (argument 1), then the operand; the
		// plain kernel allocates the destination after sizing it from the operand.
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int vL = compileVecArg(args.get(into ? 2 : 1), fn, vecTy);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int vp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		if (this.simd) {
			int trem = single ? fn.allocLocal(Ty.F64VEC) : -1;
			WasmVecLoops.simdMap1(w, dp, vp, count, rem, trem, single, uop);
		}
		else {
			WasmVecLoops.scalarMap1(w, dp, vp, count, rem, single, uop);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// (vec:maximum a b) / (vec:minimum a b) and their -into siblings: dst[i] =
	// (if (> a[i] b[i]) a[i] b[i]) (or <). Both widths are handled in one
	// body -- a select only copies input bits and a native-width compare equals the
	// widened compare, so the f32 path needs no separate widening shape. v128 gt/lt
	// mask + bitselect under --simd, a scalar compare + select loop otherwise; the two
	// lowerings compute identical results. The destination MAY alias an operand (the
	// add-into rule).
	private Ty compileSimdSelectElementwise(List<LispVal> args, Fn fn, boolean greater, boolean into, String what) {
		requireArgc(args, into ? 4 : 3, what, fn);
		Ty vecTy = packedVecType(args.get(1), fn);
		boolean single = vecTy == Ty.F32VEC;
		WasmWriter w = fn.writer;
		// -into evaluates the destination first (it is argument 1), then the two
		// operands; the plain kernel allocates the destination after sizing it from a.
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int aL = compileVecArg(args.get(into ? 2 : 1), fn, vecTy);
		int bL = compileVecArg(args.get(into ? 3 : 2), fn, vecTy);
		int count = loadVecCount(fn, aL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int ap = fn.allocLocal(Ty.F64VEC);
		int bp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		if (this.simd) {
			int trem = single ? fn.allocLocal(Ty.F64VEC) : -1;
			WasmVecLoops.simdMap2Select(w, dp, ap, bp, count, rem, trem, single, greater);
		}
		else {
			WasmVecLoops.scalarMap2Select(w, dp, ap, bp, count, rem, single, greater);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// (vec:clip v lo hi) / (vec:clip-into out v lo hi): each element is widened to f64
	// and run through the composition selects the other backends'
	// defun states -- t = (if (> x lo) x lo), then (if (< t hi) t hi) -- against the
	// two FULL f64 bounds, then narrowed on store (the emap / array-vs-scalar rule; a
	// narrowed f32 bound would not widen). BOTH --simd modes drive this same
	// one-element-per-iteration loop, like exp. The destination MAY alias v.
	private Ty compileSimdClip(List<LispVal> args, Fn fn, boolean into, String what) {
		requireArgc(args, into ? 5 : 4, what, fn);
		Ty vecTy = packedVecType(args.get(1), fn);
		boolean single = vecTy == Ty.F32VEC;
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int vL = compileVecArg(args.get(into ? 2 : 1), fn, vecTy);
		int lo = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(into ? 3 : 2), fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(lo);
		int hi = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(into ? 4 : 3), fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(hi);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int vp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int t = fn.allocLocal(Ty.FLOAT);
		int stride = single ? 4 : 8;
		WasmVecLoops.openScalarCountLoop(w, count, rem);
		WasmVecLoops.get(w, dp);
		WasmVecLoops.get(w, vp);
		w.write(single ? Instruction.F32_LOAD : Instruction.F64_LOAD, 0x00, 0x00);
		if (single) {
			w.write(Instruction.F64_PROMOTE_F32);
		}
		WasmVecLoops.set(w, t);
		// t = select(x, lo, x > lo)
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, lo);
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, lo);
		w.write(Instruction.F64_GT);
		w.write(Instruction.SELECT);
		WasmVecLoops.set(w, t);
		// select(t, hi, t < hi)
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, hi);
		WasmVecLoops.get(w, t);
		WasmVecLoops.get(w, hi);
		w.write(Instruction.F64_LT);
		w.write(Instruction.SELECT);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
		}
		w.write(single ? Instruction.F32_STORE : Instruction.F64_STORE, 0x00, 0x00);
		WasmVecLoops.advancePtr(w, vp, stride);
		WasmVecLoops.advancePtr(w, dp, stride);
		WasmVecLoops.closeLoop(w, rem);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// (vec:exp v) / (vec:sign v) and their -into siblings: the operator exists only as
	// an f64 instruction sequence (WasmExpCompiler's software
	// approximation / WasmSignumCompiler's (x>0)-(x<0)), reused via the GC backend's
	// raw-f64 emitters -- so the values match the wasm-GC backend's exactly, and
	// diverge from the interpreter/JVM at the same edges the wasm scalar builtins
	// already do (exp low-order digits; sign maps -0.0/NaN to 0.0). exp has no lane
	// form anywhere and sign's is not worth one, so BOTH --simd modes drive the same
	// one-element-per-iteration loop: an f32 element widens on read and narrows on
	// store (the emap rule). The destination MAY alias v (the add-into rule).
	private Ty compileSimdUnaryF64(List<LispVal> args, Fn fn, int scalarOp, boolean into, String what) {
		requireArgc(args, into ? 3 : 2, what, fn);
		Ty vecTy = packedVecType(args.get(1), fn);
		boolean single = vecTy == Ty.F32VEC;
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int vL = compileVecArg(args.get(into ? 2 : 1), fn, vecTy);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int vp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int f64Base = fn.allocLocal(Ty.FLOAT);
		for (int k = 1; k < WasmVecSimdRuntimeBuilder.scalarOpF64Locals(scalarOp); k++) {
			fn.allocLocal(Ty.FLOAT);
		}
		int stride = single ? 4 : 8;
		WasmVecLoops.openScalarCountLoop(w, count, rem);
		WasmVecLoops.get(w, dp);
		WasmVecLoops.get(w, vp);
		w.write(single ? Instruction.F32_LOAD : Instruction.F64_LOAD, 0x00, 0x00);
		if (single) {
			w.write(Instruction.F64_PROMOTE_F32);
		}
		WasmVecSimdRuntimeBuilder.emitScalarUnaryF64(w, scalarOp, f64Base);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
		}
		w.write(single ? Instruction.F32_STORE : Instruction.F64_STORE, 0x00, 0x00);
		WasmVecLoops.advancePtr(w, vp, stride);
		WasmVecLoops.advancePtr(w, dp, stride);
		WasmVecLoops.closeLoop(w, rem);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// (vec:scale v s): v * s (scalar broadcast) into a fresh vector. The scalar is
	// splatted
	// into both lanes with f64x2.splat, so the multiply is a single f64x2.mul per pair.
	private Ty compileSimdScale(List<LispVal> args, Fn fn, boolean into) {
		requireArgc(args, into ? 4 : 3, into ? "vec:scale-into" : "vec:scale", fn);
		Ty vecTy = packedVecType(args.get(1), fn);
		if (!this.simd) {
			return compileScalarScale(args, fn, into);
		}
		if (vecTy == Ty.F32VEC) {
			return compileSimdScaleF32(args, fn, into);
		}
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, Ty.F64VEC) : -1;
		int vL = compileVecArg(args.get(into ? 2 : 1), fn, Ty.F64VEC);
		int s = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(into ? 3 : 2), fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(s);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count);
		int vp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdScale(w, dp, vp, count, rem, -1, s, false);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return Ty.F64VEC;
	}

	// (vec:sum v) -> horizontal sum. Accumulates two lane sums in a v128, folds them
	// with
	// f64x2.extract_lane, and adds the odd tail element.
	private Ty compileSimdSum(List<LispVal> args, Fn fn) {
		requireArgc(args, 2, "vec:sum", fn);
		if (!this.simd) {
			return compileScalarSum(args, fn);
		}
		if (packedVecType(args.get(1), fn) == Ty.F32VEC) {
			return compileSimdSumF32(args, fn);
		}
		WasmWriter w = fn.writer;
		int vL = fn.allocLocal(Ty.F64VEC);
		compileCoerced(args.get(1), fn, Ty.F64VEC);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(vL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(vL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int acc = fn.allocV128Local();
		WasmVecLoops.splatZero(w, acc);
		int vp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, vL, vp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int sum = fn.allocLocal(Ty.FLOAT);
		WasmVecLoops.simdSum(w, vp, count, rem, -1, acc, sum, false);
		return Ty.FLOAT;
	}

	// (vec:dot a b) -> sum of a_i*b_i. Lane-wise multiply-accumulate into a v128, folded
	// horizontally, plus the odd tail product.
	private Ty compileSimdDot(List<LispVal> args, Fn fn) {
		requireArgc(args, 3, "vec:dot", fn);
		if (!this.simd) {
			return compileScalarDot(args, fn);
		}
		if (packedVecType(args.get(1), fn) == Ty.F32VEC) {
			return compileSimdDotF32(args, fn);
		}
		WasmWriter w = fn.writer;
		int aL = fn.allocLocal(Ty.F64VEC);
		int bL = fn.allocLocal(Ty.F64VEC);
		compileCoerced(args.get(1), fn, Ty.F64VEC);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(aL);
		compileCoerced(args.get(2), fn, Ty.F64VEC);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(bL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(aL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int acc = fn.allocV128Local();
		WasmVecLoops.splatZero(w, acc);
		int ap = fn.allocLocal(Ty.F64VEC);
		int bp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int sum = fn.allocLocal(Ty.FLOAT);
		WasmVecLoops.simdDot(w, ap, bp, count, rem, -1, acc, sum, false);
		return Ty.FLOAT;
	}

	// (vec:matvec w x) / (vec:matvec-into out w x): GEMV -- y[i] = dot(row i of W, x)
	// over a rank-2 packed matrix (F64MAT/F32MAT) into a rank-1 vector of length d
	// (fresh, or the caller's destination). One dot kernel run per row: the row
	// cursor ap restarts at the row pointer rp and the x cursor bp at x's data base each
	// iteration (the dot emitters clobber their pointer locals), rp advancing by cols
	// elements per row. Under --simd the per-row dot is the same f64x2/f32x4 lane loop
	// vec:dot uses (WasmVecLoops.simdDot: an f32 row accumulates in f32 lanes and
	// promotes once -- the --simd single-precision reduction contract); without it the
	// v128-free scalar loop (WasmVecLoops.scalarDot), so the module stays MVP-clean.
	// Widths may not mix: x (and out) must be the same width as W, the vec: fail-fast
	// rule (compileCoerced turns a mismatch into the incompatible-types error). Like the
	// other backends' matvec-into, out must alias NEITHER x nor w (each output element
	// folds over all of x) -- checked at runtime by pointer equality, trapping with
	// `unreachable` (this backend has no error channel), the analog of wasm-GC's ref.eq
	// trap.
	private Ty compileSimdMatvec(List<LispVal> args, Fn fn, boolean into) {
		String what = into ? "vec:matvec-into" : "vec:matvec";
		requireArgc(args, into ? 4 : 3, what, fn);
		LispVal wArg = args.get(into ? 2 : 1);
		Ty matTy = staticType(wArg, fn);
		if (matTy != Ty.F64MAT && matTy != Ty.F32MAT) {
			throw new UnsupportedOperationException("--no-gc: " + what + " in function '" + fn.fnName
					+ "' needs a rank-2 packed matrix W; build it with (make-array (list d n) :element-type ...)");
		}
		boolean single = matTy == Ty.F32MAT;
		Ty vecTy = single ? Ty.F32VEC : Ty.F64VEC;
		WasmWriter w = fn.writer;
		// -into evaluates the destination first (it is argument 1), then W and x.
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int wl = compileVecArg(wArg, fn, matTy);
		int xl = compileVecArg(args.get(into ? 3 : 2), fn, vecTy);
		int d = fn.allocLocal(Ty.F64VEC); // i32 row count (rows header word)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(wl);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(d);
		int cols = fn.allocLocal(Ty.F64VEC); // i32 column count (cols header word)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(wl);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(cols);
		if (into) {
			// if (out == x || out == w) trap: the aliasing the other backends reject with
			// an error would silently corrupt the GEMV here.
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(dstL);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(xl);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(dstL);
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(wl);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.I32_OR);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		}
		int dst = into ? dstL : allocVec(fn, d, vecTy);
		int rp = fn.allocLocal(Ty.F64VEC); // current row's data pointer
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(wl);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(rp);
		int xp = fn.allocLocal(Ty.F64VEC); // x's data base (never advanced)
		dataPtr(w, xl, xp);
		int dp = fn.allocLocal(Ty.F64VEC); // output element pointer
		dataPtr(w, dst, dp);
		int ap = fn.allocLocal(Ty.F64VEC); // per-row cursors the dot emitters clobber
		int bp = fn.allocLocal(Ty.F64VEC);
		int rem = fn.allocLocal(Ty.F64VEC);
		int trem = -1;
		int acc = -1;
		int sum;
		if (this.simd) {
			trem = single ? fn.allocLocal(Ty.F64VEC) : -1;
			acc = fn.allocV128Local();
			sum = single ? fn.allocF32Local() : fn.allocLocal(Ty.FLOAT);
		}
		else {
			sum = single ? fn.allocF32Local() : fn.allocLocal(Ty.FLOAT);
		}
		int rowVal = fn.allocLocal(Ty.FLOAT); // the row's dot result (f64 boundary)
		int i = fn.allocLocal(Ty.F64VEC); // i32 row index
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(d);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(rp);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(ap);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(xp);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(bp);
		if (this.simd) {
			WasmVecLoops.splatZero(w, acc, single);
			WasmVecLoops.simdDot(w, ap, bp, cols, rem, trem, acc, sum, single);
		}
		else {
			WasmVecLoops.scalarDot(w, ap, bp, cols, rem, sum, single);
		}
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(rowVal);
		// dst[i] = rowVal (narrowed on a f32 output, like aset)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dp);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(rowVal);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		// rp += cols << shift (the next row); dp += one element
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(rp);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(cols);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(rp);
		WasmVecLoops.advancePtr(w, dp, single ? 4 : 8);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// --- f32x4 (single-float) SIMD kernels ---------------------------------------------
	//
	// The single-float analog of the f64x2 kernels above. Same 16-byte v128 SIMD word,
	// but
	// FOUR f32 lanes per iteration (count >> 2 quads) instead of two f64 lanes, and a
	// scalar remainder LOOP over the last (count & 3) elements (0..3 leftover) instead of
	// a
	// single odd-element guard. The value boundary stays f64 (a scalar in/out is f64),
	// but
	// the vector DATA is f32 and every kernel computes ENTIRELY in f32 -- native f32x4
	// arithmetic + an f32 scalar tail, the final reduction promoted to f64 on return.
	// This
	// matches llama2.c / a FloatVector's f32-throughout semantics (each --no-gc width
	// computes in its own native precision, exactly as the f64x2 path computes in f64);
	// it
	// diverges from the interpreter/JVM-scalar vec.lisp oracle (which widens to f64) only
	// for non-f32-exact operands, the same class of divergence as SIMD reduction
	// associativity -- so cross-backend / --no-gc tests use f32-exact (integer /
	// power-of-two) inputs. The f64x2 kernels above are left byte-identical; only an #f /
	// single-float operand reaches here.

	// (vec:add a b) / (vec:sub a b) / (vec:mul a b) on f32 vectors: element-wise into a
	// fresh f32 vector. Four f32 lanes per iteration via v128.load + f32x4.<op> +
	// v128.store, then a scalar remainder loop over the last count & 3 elements. The op
	// arguments are the f64 ones; WasmVecLoops maps them to their f32 siblings.
	private Ty compileSimdElementwiseF32(List<LispVal> args, Fn fn, int simdOp, int scalarOp, boolean into) {
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, Ty.F32VEC) : -1;
		int aL = compileVecArg(args.get(into ? 2 : 1), fn, Ty.F32VEC);
		int bL = compileVecArg(args.get(into ? 3 : 2), fn, Ty.F32VEC);
		int count = loadVecCount(fn, aL);
		int dst = into ? dstL : allocVec(fn, count, Ty.F32VEC);
		int ap = fn.allocLocal(Ty.F32VEC);
		int bp = fn.allocLocal(Ty.F32VEC);
		int dp = fn.allocLocal(Ty.F32VEC);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int trem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdMap2(w, dp, ap, bp, count, rem, trem, true, simdOp, scalarOp);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return Ty.F32VEC;
	}

	// (vec:scale v s) on a f32 vector: v * s into a fresh f32 vector, computed in f32
	// (the
	// scalar s is narrowed to f32 and broadcast with f32x4.splat, matching the f32
	// lanes).
	private Ty compileSimdScaleF32(List<LispVal> args, Fn fn, boolean into) {
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, Ty.F32VEC) : -1;
		int vL = compileVecArg(args.get(into ? 2 : 1), fn, Ty.F32VEC);
		int s = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(into ? 3 : 2), fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(s);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, Ty.F32VEC);
		int vp = fn.allocLocal(Ty.F32VEC);
		int dp = fn.allocLocal(Ty.F32VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int trem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdScale(w, dp, vp, count, rem, trem, s, true);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return Ty.F32VEC;
	}

	// (vec:sum v) on a f32 vector -> horizontal sum. Accumulates four lane sums in a
	// v128,
	// folds them (four f32 lanes), adds the count & 3 tail elements, then promotes to
	// f64.
	private Ty compileSimdSumF32(List<LispVal> args, Fn fn) {
		WasmWriter w = fn.writer;
		int vL = fn.allocLocal(Ty.F32VEC);
		compileCoerced(args.get(1), fn, Ty.F32VEC);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(vL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(vL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int acc = fn.allocV128Local();
		WasmVecLoops.splatZeroF32(w, acc);
		int vp = fn.allocLocal(Ty.F32VEC);
		dataPtr(w, vL, vp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int sum = fn.allocF32Local();
		int trem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdSum(w, vp, count, rem, trem, acc, sum, true);
		return Ty.FLOAT;
	}

	// (vec:dot a b) on f32 vectors -> sum of a_i*b_i. Lane-wise multiply-accumulate in a
	// v128 (four f32 lanes), folded horizontally, plus the count & 3 tail products, then
	// promoted to f64.
	private Ty compileSimdDotF32(List<LispVal> args, Fn fn) {
		WasmWriter w = fn.writer;
		int aL = fn.allocLocal(Ty.F32VEC);
		int bL = fn.allocLocal(Ty.F32VEC);
		compileCoerced(args.get(1), fn, Ty.F32VEC);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(aL);
		compileCoerced(args.get(2), fn, Ty.F32VEC);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(bL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(aL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int acc = fn.allocV128Local();
		WasmVecLoops.splatZeroF32(w, acc);
		int ap = fn.allocLocal(Ty.F32VEC);
		int bp = fn.allocLocal(Ty.F32VEC);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int sum = fn.allocF32Local();
		int trem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdDot(w, ap, bp, count, rem, trem, acc, sum, true);
		return Ty.FLOAT;
	}

	// --- vec: package scalar (v128-free) kernels (--no-gc without --simd)
	// ---------------
	//
	// The plain-loop lowering of the vectorizable vec: kernels, selected when --simd is
	// off
	// (this.simd == false). The packed [count][data] block is the SAME layout the v128
	// path
	// uses (byte-identical), so a scalar module computes the same result over the same
	// memory -- element-wise ops bit-for-bit, reductions modulo summation order (tests
	// use
	// exact inputs). The module carries NO 0xFD SIMD opcode, so it runs on an MVP runtime
	// that lacks the SIMD proposal -- a portability win over the always-v128 behavior.
	//
	// SHARED SEAM: each emitScalar*Loop below is expressed over a linear-memory block
	// addressed by raw i32 locals (a data pointer past the [count] header + the element
	// count + the element width) rather than the Lisp arg forms, and lives in
	// WasmVecLoops. The compileScalar* wrappers are the --no-gc-specific part (arg
	// evaluation + block allocation); the emitScalar*Loop helpers are the reusable core.
	// The wasm-GC --simd kernels sit alongside them in that class (over GC lane groups
	// rather than a pointer), which is why this backend must allocate its locals in the
	// original order: its output stays byte-identical to before the extraction. Here
	// "scalar" means non-SIMD (one element per iteration), distinct from the non-GC value
	// model the compiler is named for.

	// (vec:add / vec:sub / vec:mul a b) without --simd: dst[i] = op(a[i], b[i]) over a
	// plain
	// scalar loop into a fresh vector. The result preserves the operand width (a #f in
	// gives
	// a #f out), matching the v128 path and typeOfSimd.
	private Ty compileScalarElementwise(List<LispVal> args, Fn fn, int scalarOp, boolean into) {
		Ty vecTy = packedVecType(args.get(1), fn);
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int aL = compileVecArg(args.get(into ? 2 : 1), fn, vecTy);
		int bL = compileVecArg(args.get(into ? 3 : 2), fn, vecTy);
		int count = loadVecCount(fn, aL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int ap = fn.allocLocal(vecTy);
		int bp = fn.allocLocal(vecTy);
		int dp = fn.allocLocal(vecTy);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		dataPtr(w, dst, dp);
		emitScalarMap2Loop(fn, ap, bp, dp, count, vecTy, scalarOp);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	// The reusable core of vec:add/sub/mul scalar lowering: for each of `count` elements,
	// mem[dp] = op(mem[ap], mem[bp]); advance all three pointers by one element width.
	// f64
	// stays f64 (f64.load/op/store), f32 stays f32 (f32.load/op/store) so a single-float
	// vector computes entirely in f32, matching the v128 f32 path's precision.
	// `scalarF64Op`
	// is the f64 arithmetic op (F64_ADD/SUB/MUL); the f32 sibling is derived via
	// f32ScalarOf.
	private void emitScalarMap2Loop(Fn fn, int ap, int bp, int dp, int count, Ty vecTy, int scalarF64Op) {
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.scalarMap2(fn.writer, dp, ap, bp, count, rem, vecTy == Ty.F32VEC, scalarF64Op);
	}

	// (vec:scale v s) without --simd: dst[i] = v[i] * s into a fresh vector. The scalar
	// boundary s is f64; on a f32 vector it is narrowed per element (f32.demote_f64) so
	// the
	// product is computed in f32, matching the v128 f32 path.
	private Ty compileScalarScale(List<LispVal> args, Fn fn, boolean into) {
		Ty vecTy = packedVecType(args.get(1), fn);
		WasmWriter w = fn.writer;
		int dstL = into ? compileVecArg(args.get(1), fn, vecTy) : -1;
		int vL = compileVecArg(args.get(into ? 2 : 1), fn, vecTy);
		int s = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(into ? 3 : 2), fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(s);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int vp = fn.allocLocal(vecTy);
		int dp = fn.allocLocal(vecTy);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		emitScalarScaleLoop(fn, vp, dp, count, s, vecTy);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return vecTy;
	}

	private void emitScalarScaleLoop(Fn fn, int vp, int dp, int count, int sLocal, Ty vecTy) {
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.scalarScale(fn.writer, dp, vp, count, rem, sLocal, vecTy == Ty.F32VEC);
	}

	// (vec:sum v) without --simd: a left-to-right scalar sum, leaving the f64 total on
	// the
	// stack. A f32 vector accumulates in f32 then promotes to the f64 boundary (matching
	// the
	// v128 f32 path).
	private Ty compileScalarSum(List<LispVal> args, Fn fn) {
		Ty vecTy = packedVecType(args.get(1), fn);
		WasmWriter w = fn.writer;
		int vL = fn.allocLocal(vecTy);
		compileCoerced(args.get(1), fn, vecTy);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(vL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(vL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int vp = fn.allocLocal(vecTy);
		dataPtr(w, vL, vp);
		emitScalarSumLoop(fn, vp, count, vecTy);
		return Ty.FLOAT;
	}

	private void emitScalarSumLoop(Fn fn, int vp, int count, Ty vecTy) {
		boolean single = vecTy == Ty.F32VEC;
		int sum = single ? fn.allocF32Local() : fn.allocLocal(Ty.FLOAT);
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.scalarSum(fn.writer, vp, count, rem, sum, single);
	}

	// (vec:dot a b) without --simd: a left-to-right scalar sum of a[i]*b[i], leaving the
	// f64
	// total on the stack. A f32 vector multiplies and accumulates in f32 then promotes.
	private Ty compileScalarDot(List<LispVal> args, Fn fn) {
		Ty vecTy = packedVecType(args.get(1), fn);
		WasmWriter w = fn.writer;
		int aL = fn.allocLocal(vecTy);
		int bL = fn.allocLocal(vecTy);
		compileCoerced(args.get(1), fn, vecTy);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(aL);
		compileCoerced(args.get(2), fn, vecTy);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(bL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(aL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(count);
		int ap = fn.allocLocal(vecTy);
		int bp = fn.allocLocal(vecTy);
		dataPtr(w, aL, ap);
		dataPtr(w, bL, bp);
		emitScalarDotLoop(fn, ap, bp, count, vecTy);
		return Ty.FLOAT;
	}

	private void emitScalarDotLoop(Fn fn, int ap, int bp, int count, Ty vecTy) {
		boolean single = vecTy == Ty.F32VEC;
		int acc = single ? fn.allocF32Local() : fn.allocLocal(Ty.FLOAT);
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.scalarDot(fn.writer, ap, bp, count, rem, acc, single);
	}

	// (char s i): the byte at content offset i, as its code point (a character IS its
	// code here). No bounds check, like the rest of the backend's lean lowering.
	private Ty compileCharAt(List<LispVal> args, Fn fn) {
		if (args.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: char takes a string and an index in '" + fn.fnName + "'");
		}
		WasmWriter w = fn.writer;
		compileCoerced(args.get(1), fn, Ty.STRING);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		compileCoerced(args.get(2), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I64_EXTEND_U_I32);
		return Ty.INT;
	}

	// char-code / code-char: identities, since a character is its code point.
	private Ty compileCharIdentity(String name, List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: " + name + " takes one argument in '" + fn.fnName + "'");
		}
		compileCoerced(args.get(1), fn, Ty.INT);
		return Ty.INT;
	}

	// (string= a b): byte-wise content comparison via the __streq helper.
	private Ty compileStringEq(List<LispVal> args, Fn fn) {
		if (args.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: string= takes two arguments in '" + fn.fnName + "'");
		}
		compileCoerced(args.get(1), fn, Ty.STRING);
		compileCoerced(args.get(2), fn, Ty.STRING);
		fn.writer.write(Instruction.CALL).writeSignedLeb128(fn.mem.streqIndex());
		fn.writer.write(Instruction.I64_EXTEND_U_I32);
		return Ty.INT;
	}

	// (subseq s start [end]): allocate a fresh [len][bytes] header holding the content
	// slice [start, end) (end defaults to the length). No bounds check.
	private Ty compileSubseq(List<LispVal> args, Fn fn) {
		if (args.size() != 3 && args.size() != 4) {
			throw new UnsupportedOperationException(
					"--no-gc: subseq takes a string, a start and an optional end in '" + fn.fnName + "'");
		}
		WasmWriter w = fn.writer;
		compileCoerced(args.get(1), fn, Ty.STRING);
		int s = fn.allocLocal(Ty.STRING);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(s);
		compileCoerced(args.get(2), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		int start = fn.allocLocal(Ty.STRING); // i32 scratch
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(start);
		int len = fn.allocLocal(Ty.STRING); // i32 scratch: end - start
		if (args.size() > 3) {
			compileCoerced(args.get(3), fn, Ty.INT);
			w.write(Instruction.I32_WRAP_I64);
		}
		else {
			emitStrLen(w, s);
		}
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(start);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(len);
		// dst = __alloc(4 + len); store the length header.
		int dst = fn.allocLocal(Ty.STRING);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// __memcpy(dst + 4, s + 4 + start, len)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(s);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(start);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.memcpyIndex());
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(dst);
		return Ty.STRING;
	}

	// (princ-to-string x): an integer renders via the __itoa helper, a float via the
	// __ftoa helper (the GC backend's digit-extraction algorithm); a string passes
	// through unchanged.
	private Ty compilePrincToString(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					"--no-gc: princ-to-string takes one argument in '" + fn.fnName + "'");
		}
		Ty argTy = staticType(args.get(1), fn);
		if (argTy == Ty.STRING) {
			compileCoerced(args.get(1), fn, Ty.STRING);
			return Ty.STRING;
		}
		if (argTy == Ty.FLOAT) {
			compileCoerced(args.get(1), fn, Ty.FLOAT);
			fn.writer.write(Instruction.CALL).writeSignedLeb128(fn.mem.ftoaIndex());
			return Ty.STRING;
		}
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.CALL).writeSignedLeb128(fn.mem.itoaIndex());
		return Ty.STRING;
	}

	// (print x) / (princ x): write the rendered text through the __write_stdout funnel
	// and return the argument, exactly matching the interpreter's semantics (print =
	// prin1 text + a trailing newline, so strings are quoted; princ = display text, no
	// quotes, no newline). Rendering an int/float allocates a transient string, so the
	// emission is bracketed with a heap-pointer mark/reset -- a print loop stays flat by
	// construction. The value model has no runtime boolean: literal t/nil render by
	// name like the other backends, but a COMPUTED boolean prints as its 0/1 integer (a
	// documented --no-gc limitation).
	private Ty compilePrintOp(String name, List<LispVal> args, Fn fn) {
		requireArgc(args, 2, name, fn);
		WasmWriter w = fn.writer;
		LispVal arg = args.get(1);
		boolean print = LispNames.PRINT.equals(name);
		if (arg instanceof LispTrue || arg instanceof LispNil) {
			emitWriteLiteral(fn, arg instanceof LispTrue ? "T" : "NIL");
			if (print) {
				emitWriteLiteral(fn, "\n");
			}
			i64Const(w, arg instanceof LispTrue ? 1 : 0);
			return Ty.INT;
		}
		Ty t = staticType(arg, fn);
		switch (t) {
			case INT, FLOAT -> {
				int v = fn.allocLocal(t);
				compileCoerced(arg, fn, t);
				w.write(Instruction.SET_LOCAL).writeSignedLeb128(v);
				// mark / render / write / reset: the transient digits are reclaimed.
				int mark = fn.allocLocal(Ty.STRING);
				w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeSignedLeb128(mark);
				int s = fn.allocLocal(Ty.STRING);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
				w.write(Instruction.CALL).writeSignedLeb128(t == Ty.INT ? fn.mem.itoaIndex() : fn.mem.ftoaIndex());
				w.write(Instruction.SET_LOCAL).writeSignedLeb128(s);
				emitWriteString(fn, s);
				if (print) {
					emitWriteLiteral(fn, "\n");
				}
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
				return t;
			}
			case STRING -> {
				// A string argument is passthrough -- nothing allocated, no bracket.
				// print (prin1 semantics) frames it in quotes and escapes the embedded
				// " / \ so the text reads back; princ writes it bare.
				int s = fn.allocLocal(Ty.STRING);
				compileCoerced(arg, fn, Ty.STRING);
				w.write(Instruction.SET_LOCAL).writeSignedLeb128(s);
				if (print) {
					emitWriteLiteral(fn, "\"");
					emitWriteStringEscaped(fn, s);
					emitWriteLiteral(fn, "\"");
					emitWriteLiteral(fn, "\n");
				}
				else {
					emitWriteString(fn, s);
				}
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(s);
				return Ty.STRING;
			}
			default -> throw new UnsupportedOperationException("--no-gc: " + name
					+ " of a packed float array is not supported in '" + fn.fnName + "' (print scalars or strings)");
		}
	}

	// (terpri): write a newline, yield nil.
	private Ty compileTerpri(List<LispVal> args, Fn fn) {
		requireArgc(args, 1, LispNames.TERPRI, fn);
		emitWriteLiteral(fn, "\n");
		i64Const(fn.writer, 0);
		return Ty.INT;
	}

	// Writes a static literal's content bytes to stdout via the __write_stdout funnel.
	private void emitWriteLiteral(Fn fn, String content) {
		Integer off = fn.mem.literals().get(content);
		if (off == null) {
			throw new IllegalStateException("--no-gc: print literal not laid out in '" + fn.fnName + "': " + content);
		}
		fn.writer.write(Instruction.I32_CONST).writeSignedLeb128(off + 4);
		fn.writer.write(Instruction.I32_CONST)
			.writeSignedLeb128(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
		fn.writer.write(Instruction.CALL).writeSignedLeb128(fn.mem.writeStdoutIndex());
	}

	// Writes the [len][bytes] string held in the given local to stdout with the
	// *print-escape* escaping the readable renderer owes the reader: every embedded '"'
	// and '\' is preceded by a '\' (CLHS 22.1.3.4; a newline stays literal). The
	// surrounding frame quotes are the caller's -- they must NOT be escaped.
	//
	// --no-gc has no allocation here by design (a print must not move the bump heap), so
	// the escaped text is written as RUNS: the unescaped stretch since the last escape
	// goes out in one __write_stdout, then the single '\' literal. A string with no
	// escapable byte therefore costs exactly one write, as before.
	private void emitWriteStringEscaped(Fn fn, int strLocal) {
		WasmWriter w = fn.writer;
		int len = fn.allocLocal(Ty.STRING);
		int i = fn.allocLocal(Ty.STRING);
		int run = fn.allocLocal(Ty.STRING);
		int b = fn.allocLocal(Ty.STRING);
		emitStrLen(w, strLocal);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(run);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(len);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		// b = mem[strLocal + 4 + i]
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(strLocal);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(b);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128('"');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128('\\');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		emitWriteRun(fn, strLocal, run, i);
		emitWriteLiteral(fn, "\\");
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(run);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		emitWriteRun(fn, strLocal, run, len);
	}

	// __write_stdout(strLocal + 4 + from, to - from): one unescaped stretch of a string's
	// bytes.
	private void emitWriteRun(Fn fn, int strLocal, int fromLocal, int toLocal) {
		WasmWriter w = fn.writer;
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(strLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(fromLocal);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(toLocal);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(fromLocal);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.writeStdoutIndex());
	}

	// Writes the [len][bytes] string held in the given local to stdout via the
	// __write_stdout funnel.
	private void emitWriteString(Fn fn, int strLocal) {
		WasmWriter w = fn.writer;
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(strLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		emitStrLen(w, strLocal);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.writeStdoutIndex());
	}

	// (rontolisp:with-arena () body...): the intra-call free. Snapshot the bump heap
	// pointer, run the body, then pop everything allocated inside. A scalar
	// result rides the stack across the pop; a reference result (string / packed float
	// vector / matrix) is copied DOWN to the mark first (dst <= src, so the
	// byte-forward __memcpy is a safe memmove) and the heap resumes just past the copy.
	// Escape contract: nothing allocated inside the body may be reachable after it,
	// except the body's own value. On a module with no linear memory there is no
	// allocator, so the body is a plain progn.
	private Ty compileWithArena(List<LispVal> args, Fn fn) {
		List<LispVal> body = args.subList(2, args.size());
		if (!fn.mem.used()) {
			return compileProgn(body, fn);
		}
		WasmWriter w = fn.writer;
		int mark = fn.allocLocal(Ty.STRING);
		w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeSignedLeb128(mark);
		Ty t = compileProgn(body, fn);
		if (!isRefKind(t)) {
			// A scalar result: pop the whole arena, the value rides the stack.
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
			return t;
		}
		int v = fn.allocLocal(Ty.STRING);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(v);
		int size = fn.allocLocal(Ty.STRING);
		emitRefByteSize(fn, v, t, size);
		// v >= mark: allocated inside the arena -- copy down and keep just the value.
		// v < mark: predates the arena (e.g. a literal or an outer value passed
		// through) -- pop everything, the pointer stays valid as-is.
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(size);
		w.write(Instruction.CALL).writeSignedLeb128(fn.mem.memcpyIndex());
		// heap = (mark + size + 3) & -4 (the same 4-byte rounding as __alloc)
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(size);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(-4);
		w.write(Instruction.I32_AND);
		w.write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(v);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
		return t;
	}

	// Computes the total byte size (header + data) of the reference value in local v
	// into the given i32 scratch local: a string is 4 + len, a packed vector 4 +
	// (count << shift), a packed matrix 8 + (rows * cols << shift).
	private void emitRefByteSize(Fn fn, int v, Ty t, int size) {
		WasmWriter w = fn.writer;
		boolean mat = t == Ty.F64MAT || t == Ty.F32MAT;
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		if (mat) {
			w.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
			w.write(Instruction.I32_LOAD, 0x02, 0x04);
			w.write(Instruction.I32_MUL);
		}
		if (t != Ty.STRING) {
			w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(t));
			w.write(Instruction.I32_SHL);
		}
		w.write(Instruction.I32_CONST).writeSignedLeb128(mat ? 8 : 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeSignedLeb128(size);
	}

	// Pushes the stored length (the i32 header word) of the string whose pointer is in
	// the
	// given local.
	private static void emitStrLen(WasmWriter w, int strLocal) {
		w.write(Instruction.GET_LOCAL).writeSignedLeb128(strLocal);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	// (logand ...) / (logior ...) / (logxor ...): integer bitwise fold with an identity
	// for
	// the empty case.
	private Ty compileBitwise(List<LispVal> args, Fn fn, long identity, int op) {
		if (args.size() == 1) {
			i64Const(fn.writer, identity);
			return Ty.INT;
		}
		compileCoerced(args.get(1), fn, Ty.INT);
		for (int i = 2; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, Ty.INT);
			fn.writer.write(op);
		}
		return Ty.INT;
	}

	// (lognot x): bitwise complement = x XOR -1.
	private Ty compileLognot(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException(
					"--no-gc: lognot takes exactly one argument in '" + fn.fnName + "'");
		}
		compileCoerced(args.get(1), fn, Ty.INT);
		i64Const(fn.writer, -1);
		fn.writer.write(Instruction.I64_XOR);
		return Ty.INT;
	}

	// (ash value count): arithmetic shift, left for count>=0 and right (sign-extending)
	// for
	// count<0. Both shifts are computed and `select` picks the right one on the sign of
	// count, avoiding a branch (the wasm shift amount is taken mod 64, so the unused side
	// is harmless).
	private Ty compileAsh(List<LispVal> args, Fn fn) {
		if (args.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: ash takes exactly two arguments in '" + fn.fnName + "'");
		}
		int v = fn.allocLocal(Ty.INT);
		int c = fn.allocLocal(Ty.INT);
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(v);
		compileCoerced(args.get(2), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeSignedLeb128(c);
		// left = v << c
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(c);
		fn.writer.write(Instruction.I64_SHL);
		// right = v >> (0 - c)
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(v);
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(c);
		fn.writer.write(Instruction.I64_SUB);
		fn.writer.write(Instruction.I64_SHR_S);
		// select left when c >= 0, else right
		fn.writer.write(Instruction.GET_LOCAL).writeSignedLeb128(c);
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.I64_GE_S);
		fn.writer.write(Instruction.SELECT);
		return Ty.INT;
	}

	// (truncate|floor|ceiling|round x): always yields an integer. On an integer argument
	// it
	// is the identity; on a float it applies the rounding (none for truncate) then
	// converts
	// to i64.
	private Ty compileRounding(List<LispVal> args, Fn fn, int roundOp) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: " + ((LispSymbol) args.get(0)).name()
					+ " takes exactly one argument in '" + fn.fnName + "'");
		}
		Ty argTy = compileExpr(args.get(1), fn);
		if (argTy == Ty.INT) {
			return Ty.INT;
		}
		if (roundOp >= 0) {
			fn.writer.write(roundOp);
		}
		fn.writer.write(Instruction.I64_TRUNC_S_F64);
		return Ty.INT;
	}

	// Numeric comparison. Binary is emitted directly; one or 3+ args expand into nested
	// binary comparisons combined with and (all supported core forms).
	private Ty compileComparison(LispCons cons, List<LispVal> args, Fn fn, int intOp, int floatOp) {
		if (args.size() != 3) {
			return compileExpr(LispMacroExpander.expandComparison(cons), fn);
		}
		Ty operand = staticType(args.get(1), fn).join(staticType(args.get(2), fn));
		compileCoerced(args.get(1), fn, operand);
		compileCoerced(args.get(2), fn, operand);
		fn.writer.write(operand == Ty.INT ? intOp : floatOp); // -> i32 (0/1)
		fn.writer.write(Instruction.I64_EXTEND_S_I32); // booleans live in the INT domain
		return Ty.INT;
	}

	// (not x): logical negation -> (x == 0) as an i64 0/1.
	private Ty compileNot(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: not takes exactly one argument in '" + fn.fnName + "'");
		}
		Ty argTy = compileExpr(args.get(1), fn);
		if (argTy == Ty.INT) {
			fn.writer.write(Instruction.I64_EQZ); // i32: 1 if the value is 0
		}
		else {
			fn.writer.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_EQ);
		}
		fn.writer.write(Instruction.I64_EXTEND_S_I32);
		return Ty.INT;
	}

	// Converts the top-of-stack value into an i32 truthiness flag: true (1) iff it is not
	// zero. (Scalar mode treats numeric 0 as false; see the class doc.)
	private static void emitTruthy(Ty ty, WasmWriter w) {
		if (ty == Ty.INT) {
			i64Const(w, 0);
			w.write(Instruction.I64_NE);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_NE);
		}
	}

	// Converts the top-of-stack value into an i32 "is false" flag: 1 iff it is zero. Used
	// by while to br out of the loop when the test fails.
	private static void emitFalsy(Ty ty, WasmWriter w) {
		if (ty == Ty.INT) {
			w.write(Instruction.I64_EQZ);
		}
		else {
			w.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_EQ);
		}
	}

	// --- Eligibility / reachability ----------------------------------------------------

	// Validates that an expression is eligible for the scalar backend and records the
	// names of every eligible function it calls. Throws (naming the op + the function) on
	// anything unsupported, so the boundary is explicit.
	private void collectCalls(LispVal expr, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName) {
		switch (expr) {
			case LispInteger ignored -> {
			}
			case LispDouble ignored -> {
			}
			case LispString ignored -> {
			}
			case LispFloatArray ignored -> {
			}
			case LispChar ignored -> {
			}
			case LispTrue ignored -> {
			}
			case LispNil ignored -> {
			}
			case LispSymbol sym -> {
				if (!bound.contains(sym.name())) {
					throw new UnsupportedOperationException("--no-gc: '" + sym.name() + "' in function '" + fnName
							+ "' is not a parameter or let binding (scalar mode has no globals or heap values)");
				}
			}
			case LispCons cons -> collectCallsCons(cons, bound, defuns, callees, fnName);
			default -> throw new UnsupportedOperationException(
					"--no-gc: unsupported value in function '" + fnName + "': " + expr.print());
		}
	}

	private void collectCallsCons(LispCons cons, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName) {
		if (!(cons.car() instanceof LispSymbol head)) {
			throw new UnsupportedOperationException(
					"--no-gc: cannot call a non-symbol / first-class function in '" + fnName + "': " + cons.print());
		}
		String name = head.name();
		List<LispVal> args = cons.toList();
		LispVal expanded = expandMacro(name, cons, args.size() - 1);
		if (expanded != null) {
			collectCalls(expanded, bound, defuns, callees, fnName);
			return;
		}
		if (LispNames.LET.equals(name)) {
			collectLet(cons, bound, defuns, callees, fnName);
			return;
		}
		if (LispNames.SETQ.equals(name)) {
			collectSetq(args, bound, defuns, callees, fnName);
			return;
		}
		if (LispNames.CONCATENATE.equals(name)) {
			collectConcatenate(args, bound, defuns, callees, fnName);
			return;
		}
		if (LispNames.PRINT.equals(name) || LispNames.PRINC.equals(name)) {
			if (args.size() != 2) {
				throw new UnsupportedOperationException("--no-gc: " + name + " takes exactly one argument in '" + fnName
						+ "' (the optional stream argument is not supported with --no-gc)");
			}
			collectCalls(args.get(1), bound, defuns, callees, fnName);
			return;
		}
		if (LispNames.TERPRI.equals(name)) {
			if (args.size() != 1) {
				throw new UnsupportedOperationException("--no-gc: terpri takes no arguments in '" + fnName
						+ "' (the optional stream argument is not supported with --no-gc)");
			}
			return;
		}
		if (LispNames.WITH_ARENA_QUALIFIED.equals(name)) {
			if (args.size() < 2 || !(args.get(1) instanceof LispNil)) {
				throw new UnsupportedOperationException("--no-gc: " + LispNames.WITH_ARENA_QUALIFIED
						+ " expects an empty option list in '" + fnName + "': (rontolisp:with-arena () body...)");
			}
			for (int i = 2; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName);
			}
			return;
		}
		if (LispNames.MAKE_ARRAY.equals(name)) {
			collectMakeArray(args, bound, defuns, callees, fnName);
			return;
		}
		if (isSimdCall(name)) {
			// A vec: kernel is lowered inline (no callee edge); validate the member is
			// one
			// this backend supports, then walk the argument expressions.
			requireKnownSimd(name, fnName);
			// A vec constructor's optional element-type is a literal quoted symbol
			// ('single-float / 'double-float) -- a compile-time designator, not a runtime
			// value -- so skip a quote form, as collectMakeArray does for :element-type.
			for (int i = 1; i < args.size(); i++) {
				LispVal a = args.get(i);
				if (a instanceof LispCons c && c.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())) {
					continue;
				}
				collectCalls(a, bound, defuns, callees, fnName);
			}
			return;
		}
		if (LispNames.IF.equals(name) || LispNames.PROGN.equals(name) || LispNames.WHILE.equals(name)
				|| LispNames.BLOCK_INTERNAL.equals(name) || LispNames.RETURN.equals(name) || BUILTINS.contains(name)
				|| ARRAY_OPS.contains(name)) {
			for (int i = 1; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName);
			}
			return;
		}
		if (defuns.containsKey(name)) {
			callees.add(name);
			for (int i = 1; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName);
			}
			return;
		}
		throw new UnsupportedOperationException("--no-gc: unsupported operation '" + name + "' in function '" + fnName
				+ "' (not a numeric primitive or an eligible function)");
	}

	private void collectSetq(List<LispVal> args, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName) {
		if ((args.size() - 1) % 2 != 0) {
			throw new UnsupportedOperationException(
					"--no-gc: setq needs an even number of arguments in '" + fnName + "'");
		}
		for (int p = 0; p < (args.size() - 1) / 2; p++) {
			LispVal target = args.get(1 + 2 * p);
			if (!(target instanceof LispSymbol s)) {
				throw new UnsupportedOperationException(
						"--no-gc: setq target must be a symbol in '" + fnName + "': " + target.print());
			}
			if (!bound.contains(s.name())) {
				throw new UnsupportedOperationException("--no-gc: setq target '" + s.name() + "' in function '" + fnName
						+ "' is not a parameter or let binding (scalar mode has no globals)");
			}
			collectCalls(args.get(2 + 2 * p), bound, defuns, callees, fnName);
		}
	}

	// (concatenate 'string s1 s2 ...): the first argument must be the literal result-type
	// designator 'string (only string concatenation is supported in scalar mode); the
	// rest
	// are ordinary string-valued expressions.
	private void collectConcatenate(List<LispVal> args, Set<String> bound, Map<String, Defun> defuns,
			Set<String> callees, String fnName) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException(
					"--no-gc: concatenate needs a result-type designator in '" + fnName + "'");
		}
		if (!isQuotedSymbol(args.get(1), "STRING")) {
			throw new UnsupportedOperationException(
					"--no-gc: concatenate only supports 'string in '" + fnName + "' (got " + args.get(1).print() + ")");
		}
		for (int i = 2; i < args.size(); i++) {
			collectCalls(args.get(i), bound, defuns, callees, fnName);
		}
	}

	// make-array's argument list mixes a dimension expression with :keyword literals (the
	// :element-type quote, keyword symbols), so only the runtime sub-expressions are
	// collected: the per-dimension expressions (unless the spec is a quoted '(d n)
	// literal, whose elements are plain data; a (list d n) form's elements ARE walked,
	// but never the `list` head itself) and the :initial-element value. The other
	// keywords are validated later in compileMakeArray.
	private void collectMakeArray(List<LispVal> args, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName) {
		if (args.size() >= 2) {
			LispVal dims = args.get(1);
			if (!(dims instanceof LispCons c && c.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name()))) {
				for (LispVal dim : dimExprs(dims)) {
					collectCalls(dim, bound, defuns, callees, fnName);
				}
			}
		}
		LispVal init = findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD);
		if (init != null) {
			collectCalls(init, bound, defuns, callees, fnName);
		}
	}

	// Whether value is (quote name) or the bare symbol name.
	private static boolean isQuotedSymbol(LispVal value, String name) {
		if (value instanceof LispSymbol s) {
			return name.equals(s.name());
		}
		return value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol n && name.equals(n.name());
	}

	private void collectLet(LispCons cons, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName) {
		List<LispVal> parts = cons.toList();
		List<LispVal> bindings = parts.get(1) instanceof LispCons bc ? bc.toList() : List.of();
		Set<String> inner = new HashSet<>(bound);
		for (LispVal binding : bindings) {
			if (binding instanceof LispSymbol s) {
				inner.add(s.name());
			}
			else if (binding instanceof LispCons b) {
				List<LispVal> bp = b.toList();
				// Parallel let: initializers see the outer scope only.
				if (bp.size() > 1) {
					collectCalls(bp.get(1), bound, defuns, callees, fnName);
				}
				inner.add(((LispSymbol) bp.get(0)).name());
			}
			else {
				throw new UnsupportedOperationException(
						"--no-gc: malformed let binding in '" + fnName + "': " + binding.print());
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			collectCalls(parts.get(i), inner, defuns, callees, fnName);
		}
	}

	// --- Macro / name helpers ----------------------------------------------------------

	// Expands the macros that reduce to the supported core (if/let/progn/while/%block +
	// primitives), or returns null when name is not such a macro. Mirrors the dispatch
	// the
	// other backends perform via LispMacroExpander.
	private static @Nullable LispVal expandMacro(String name, LispCons cons, int argc) {
		return switch (name) {
			case LispNames.COND -> LispMacroExpander.expandCond(cons);
			case LispNames.AND -> LispMacroExpander.expandAnd(cons);
			case LispNames.OR -> LispMacroExpander.expandOr(cons);
			case LispNames.WHEN -> LispMacroExpander.expandWhen(cons);
			case LispNames.UNLESS -> LispMacroExpander.expandUnless(cons);
			case LispNames.LET_STAR -> LispMacroExpander.expandLetStar(cons);
			// setf of a variable -> setq; setf of an (aref v i) place -> %aset. The
			// scalar
			// backend has no structs/CLOS, so the no-registry expansion is exactly right.
			case LispNames.SETF -> LispMacroExpander.expandSetf(cons);
			case LispNames.ONE_PLUS -> LispMacroExpander.expandOnePlus(cons);
			case LispNames.ONE_MINUS -> LispMacroExpander.expandOneMinus(cons);
			case LispNames.ZEROP -> LispMacroExpander.expandZerop(cons);
			case LispNames.PLUSP -> LispMacroExpander.expandPlusp(cons);
			case LispNames.MINUSP -> LispMacroExpander.expandMinusp(cons);
			case LispNames.EVENP -> LispMacroExpander.expandEvenp(cons);
			case LispNames.ODDP -> LispMacroExpander.expandOddp(cons);
			case LispNames.DOTIMES -> LispMacroExpander.expandDotimes(cons);
			case LispNames.DO -> LispMacroExpander.expandDo(cons);
			case LispNames.DO_STAR -> LispMacroExpander.expandDoStar(cons);
			case LispNames.LOOP -> LispMacroExpander.expandLoop(cons);
			case LispNames.CHECK_TYPE -> LispMacroExpander.expandCheckType(cons);
			case LispNames.ASSERT -> LispMacroExpander.expandAssert(cons);
			case LispNames.DECLARE -> LispMacroExpander.expandDeclare(cons);
			case LispNames.DECLAIM -> LispMacroExpander.expandDeclaim(cons);
			case LispNames.PROCLAIM -> LispMacroExpander.expandProclaim(cons);
			case LispNames.THE -> LispMacroExpander.expandThe(cons);
			case LispNames.EVAL_WHEN -> LispMacroExpander.expandEvalWhen(cons);
			case LispNames.FLET -> LispMacroExpander.expandFlet(cons);
			case LispNames.LABELS -> LispMacroExpander.expandLabels(cons);
			// The scalar backend has no reference globals, so values stays the pure
			// primary-value expansion (no %mv-spill publication).
			case LispNames.VALUES -> LispMacroExpander.expandValuesPrimary(cons);
			case LispNames.MULTIPLE_VALUE_BIND -> LispMacroExpander.expandMultipleValueBind(cons);
			case LispNames.MULTIPLE_VALUE_LIST -> LispMacroExpander.expandMultipleValueList(cons);
			case LispNames.MULTIPLE_VALUE_CALL -> LispMacroExpander.expandMultipleValueCall(cons);
			case LispNames.NTH_VALUE -> LispMacroExpander.expandNthValue(cons);
			case LispNames.MULTIPLE_VALUE_SETQ -> LispMacroExpander.expandMultipleValueSetq(cons);
			case LispNames.ROTATEF -> LispMacroExpander.expandRotatef(cons);
			case LispNames.DESTRUCTURING_BIND -> LispMacroExpander.expandDestructuringBind(cons);
			case LispNames.PUSHNEW -> LispMacroExpander.expandPushnew(cons);
			case LispNames.DEFTYPE -> LispMacroExpander.expandDeftype(cons);
			case LispNames.DEFINE_CONDITION -> LispMacroExpander.expandDefineCondition(cons);
			case LispNames.DEFINE_SETF_EXPANDER -> LispMacroExpander.expandDefineSetfExpander(cons);
			case LispNames.DEFINE_COMPILER_MACRO -> LispMacroExpander.expandDefineCompilerMacro(cons);
			// The numeric subset does not run the whole-program hoist, so a
			// load-time-value here keeps the re-evaluating lowering (nothing in this
			// backend's value model is expensive enough to compute once).
			case LispNames.LOAD_TIME_VALUE -> LispMacroExpander.expandLoadTimeValue(cons);
			// --no-gc keeps the historical primary-form-only lowering: its value model
			// has no condition objects (the catching forms are rejected outright), so
			// restart records cannot exist and nothing can invoke a clause -- the lite
			// lowering is behavior-identical here. Every other backend expands the real
			// restart system (LispMacroExpander.expandRestartCase).
			case LispNames.RESTART_CASE -> LispMacroExpander.expandRestartCaseLite(cons);
			case LispNames.MAKE_CONDITION -> LispMacroExpander.expandMakeCondition(cons);
			case LispNames.DOCUMENTATION -> LispMacroExpander.expandDocumentation(cons);
			// Two-argument (floor a b) -> (floor (/ a b)); null (the one-argument
			// form) falls through to the native rounding conversion.
			case LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND ->
				LispMacroExpander.expandFloorFamilyDivisor(cons);
			default -> null;
		};
	}

	private static void validateScalarTypes(WasmExportCompiler.Decl decl) {
		for (BoundaryType t : decl.paramTypes()) {
			requireSupported(t, decl);
		}
		if (decl.returnType() != BoundaryType.VOID) {
			requireSupported(decl.returnType(), decl);
		}
	}

	// This backend carries every boundary type except :s-expr, whose printed-text
	// crossing
	// needs a cons/reader/printer runtime it does not have. Its house integer is i64, so
	// the whole fixed-width integer family -- including the 64-bit types the wasm-GC
	// backends refuse -- crosses here.
	private static void requireSupported(BoundaryType type, WasmExportCompiler.Decl decl) {
		if (type == BoundaryType.S_EXPR) {
			throw new UnsupportedOperationException("--no-gc does not support the :s-expr export type for '"
					+ decl.name() + "' (it needs a cons/reader/printer runtime)");
		}
	}

	private static LispVal progn(List<LispVal> body) {
		LispCons head = new LispCons(new LispSymbol(LispNames.PROGN), LispNil.INSTANCE);
		LispCons tail = head;
		for (LispVal e : body) {
			LispCons cell = new LispCons(e, LispNil.INSTANCE);
			tail.setCdr(cell);
			tail = cell;
		}
		return head;
	}

	// Walks the whole program for a symbol occurrence (head or argument position),
	// used by the async-surface rejection above.
	private static boolean referencesSymbol(List<LispVal> program, String name) {
		for (LispVal form : program) {
			if (referencesSymbol(form, name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesSymbol(LispVal form, String name) {
		return switch (form) {
			case LispSymbol sym -> name.equals(sym.name());
			case LispCons cons -> referencesSymbol(cons.car(), name) || referencesSymbol(cons.cdr(), name);
			default -> false;
		};
	}

	private static Defun extractDefun(LispVal setqLambda) {
		// (setq name (lambda (params...) body...))
		List<LispVal> parts = ((LispCons) setqLambda).toList();
		String name = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LispVal paramsVal = lambdaParts.get(1);
		// Lambda-list keywords need cons lists at runtime, which the scalar (non-GC)
		// lowering does not have; reject them with a clear error.
		if (am.ik.rontolisp.LambdaLists.usesLambdaListKeywords(paramsVal)) {
			throw new UnsupportedOperationException("Cannot compile function '" + name
					+ "': lambda-list keywords (&optional/&rest/&key) are not supported with --no-gc");
		}
		List<String> params = paramsVal instanceof LispNil ? List.of()
				: ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		return new Defun(name, params, lambdaParts.subList(2, lambdaParts.size()));
	}

	/** A collected top-level function definition. */
	private record Defun(String name, List<String> params, List<LispVal> body) {
	}

	// Per-function compilation state.
	private static final class Fn {

		final WasmWriter writer;

		final Types types;

		final Map<String, Integer> index;

		final String fnName;

		final Set<String> paramNames;

		final Mem mem;

		final Map<String, Integer> locals = new HashMap<>();

		final Map<String, Ty> localTypes = new HashMap<>();

		// The declared type of each allocated body local, in allocation order. Held as
		// raw
		// wasm value-type bytes (not Ty) because the simd reductions allocate a v128
		// accumulator, which has no Ty value-model kind (a v128 is a transient lowering
		// detail, never a rontolisp value).
		final List<Integer> extraLocalTypes = new ArrayList<>();

		// The wasm control depth (each enclosing if = +1, each while = +2, each %block =
		// +1) and the stack of %block boundaries: blockMarkers holds the control depth at
		// each block so a return computes its br depth, blockResultTypes the matching
		// result types so a returned value is coerced to the block's type.
		int ctrlDepth;

		final Deque<Integer> blockMarkers = new ArrayDeque<>();

		final Deque<Ty> blockResultTypes = new ArrayDeque<>();

		int nextLocal;

		Fn(WasmWriter writer, Types types, Map<String, Integer> index, String fnName, Set<String> paramNames, Mem mem) {
			this.writer = writer;
			this.types = types;
			this.index = index;
			this.fnName = fnName;
			this.paramNames = paramNames;
			this.mem = mem;
		}

		void bind(String name, int slot, Ty ty) {
			this.locals.put(name, slot);
			this.localTypes.put(name, ty);
		}

		void restore(String name, @Nullable Integer slot, @Nullable Ty ty) {
			if (slot == null) {
				this.locals.remove(name);
				this.localTypes.remove(name);
			}
			else {
				this.locals.put(name, slot);
				this.localTypes.put(name, Objects.requireNonNull(ty));
			}
		}

		int allocLocal(Ty ty) {
			this.extraLocalTypes.add(ty.valType());
			return this.nextLocal++;
		}

		// Allocates a v128 (0x7B) local -- used by the simd reductions to carry the
		// two-lane accumulator across the loop. Has no Ty (v128 is not a rontolisp
		// value).
		int allocV128Local() {
			this.extraLocalTypes.add(Type.V128.code());
			return this.nextLocal++;
		}

		// Allocates a raw f32 (0x7D) local -- used by the f32 simd reductions to carry
		// the
		// running scalar sum (an f32 running value) across the horizontal fold and the
		// scalar tail before it is promoted to the f64 boundary. Has no Ty (a bare f32 is
		// not a rontolisp value; scalars are f64).
		int allocF32Local() {
			this.extraLocalTypes.add(Type.F32.code());
			return this.nextLocal++;
		}

	}

	// Emits an i64.const with a full 64-bit signed LEB128 immediate (WasmWriter only has
	// a
	// 32-bit signed LEB writer, and integer literals span the i64 range).
	private static void i64Const(WasmWriter w, long value) {
		w.write(Instruction.I64_CONST);
		long v = value;
		while (true) {
			int b = (int) (v & 0x7f);
			v >>= 7;
			if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
				w.write(b);
				return;
			}
			w.write(b | 0x80);
		}
	}

}
