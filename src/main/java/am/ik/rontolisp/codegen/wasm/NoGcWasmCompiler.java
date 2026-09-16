package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import am.ik.rontolisp.ClConstants;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.compiler.ClRedefinitionWarnings;
import am.ik.rontolisp.compiler.CompileWarnings;
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
 * runtime, with no wasm-GC requirement.
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
 * linear-memory string runtime and are deferred.
 */
public final class NoGcWasmCompiler implements LispCompiler {

	/** The native representation of a value. */
	private enum Ty {

		/**
		 * No value at all -- a form evaluated purely for its effect, which leaves the
		 * stack untouched. It is the lattice BOTTOM (joining with anything yields the
		 * other side), so it never meets {@code INT}/{@code FLOAT} in an expression:
		 * where a void form is used for its value, {@link #coerce} materializes the nil
		 * it stands for in the consumer's own representation. Four things are void and
		 * nothing else is -- a call to a {@code :void} host import, {@code while},
		 * {@code terpri} and an empty {@code progn} -- and it spreads from there through
		 * the return fixpoint, so a function all of whose paths are void is itself
		 * {@code (...) -> ()} and a {@code :void} export of it needs no wrapper at all.
		 */
		VOID,
		/**
		 * A boolean (an {@code i64} 0/1, the same representation as {@code INT}). Below
		 * {@code INT} in the lattice: the predicates and the {@code t}/{@code nil}
		 * literals produce it, arithmetic widens it to {@code INT} on contact, and
		 * {@code princ}/{@code print} of it writes {@code T}/{@code NIL} where
		 * {@code INT} renders decimal digits. Joining it with {@code INT} therefore
		 * answers {@code INT} -- which is what makes {@code (princ (if p t 1))} print
		 * {@code 1} where the interpreter prints {@code T}, a stated residual, not a
		 * silent agreement.
		 */
		BOOL,
		/** A 64-bit integer ({@code i64}). */
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
		 * The result type when this and another type are combined. {@code BOOL} is the
		 * value bottom below {@code INT} (a not-yet-seen slot yields to whatever concrete
		 * kind it first meets); {@code INT} in turn yields to {@code FLOAT},
		 * {@code STRING} and the packed kinds. {@code FLOAT}, {@code STRING} and
		 * {@code F64VEC} are mutually incompatible (a value cannot be more than one of
		 * number / string / float-vector), which is a genuine type error.
		 */
		Ty join(Ty other) {
			if (this == other) {
				return this;
			}
			// VOID is the true bottom: a branch that produces nothing yields to one that
			// does, and the void side then materializes nil at the join.
			if (this == VOID) {
				return other;
			}
			if (other == VOID) {
				return this;
			}
			// BOOL is the value bottom below INT: a boolean widens to whatever it meets.
			if (this == BOOL) {
				return other;
			}
			if (other == BOOL) {
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
				case VOID -> throw new IllegalStateException("--no-gc: VOID has no value type (it is not a value)");
				case BOOL, INT -> Type.I64.code();
				case FLOAT -> Type.F64.code();
				case STRING, F64VEC, F32VEC, F64MAT, F32MAT -> Type.I32.code();
			};
		}

		/**
		 * The blocktype byte for an {@code if}/{@code block} whose result is this type:
		 * the value type, or the empty blocktype {@code 0x40} for {@code VOID} (a
		 * construct that leaves nothing behind).
		 */
		int blockType() {
			return this == VOID ? 0x40 : valType();
		}

	}

	/**
	 * Operators handled directly as primitive numeric/boolean/bitwise/string operations.
	 * Characters have no separate runtime type here: a character IS its code point (an
	 * INT), so {@code char} returns the code, {@code char-code}/{@code code-char} are
	 * identities and {@code char=} is a numeric comparison -- the portable
	 * {@code (char= (char s i) #\x)} idiom behaves exactly like the other backends, on
	 * ASCII and beyond ({@code char} decodes the i-th code point through the
	 * {@code __char_at} helper, {@code length} counts code points through
	 * {@code __strlen_cp}, {@code subseq} converts through {@code __byte_offset}).
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

	/**
	 * Whether to honor the {@code --no-wasi} contract: a printing program's
	 * {@code fd_write} import becomes an internal discarding sink, so the module (and its
	 * component wrap) imports nothing. See {@link Builder#noWasi}.
	 */
	private final boolean noWasi;

	/**
	 * Creates a new non-GC WASM compiler, every option at its default: scalar
	 * {@code vec:} kernels in a plain core module at {@link OptimizeLevel#DEFAULT}.
	 * {@link #builder()} sets the others.
	 */
	public NoGcWasmCompiler() {
		this(builder());
	}

	private NoGcWasmCompiler(Builder builder) {
		this.optimize = builder.optimize;
		this.simd = builder.simd;
		this.component = builder.component;
		this.noWasi = builder.noWasi;
	}

	/**
	 * Creates a builder for a non-GC WASM compiler. Every option defaults to what the CLI
	 * selects when its flag is absent.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link NoGcWasmCompiler}.
	 */
	public static final class Builder {

		private OptimizeLevel optimize = OptimizeLevel.DEFAULT;

		private boolean simd;

		private boolean component;

		private boolean noWasi;

		private Builder() {
		}

		/**
		 * Sets what to optimize the module FOR (the CLI's {@code --optimize}). Every
		 * level but {@link OptimizeLevel#NONE} runs the finished module through
		 * {@link am.ik.wasm.WasmTreeShaker} so anything unreachable from the exports is
		 * dropped and the survivors renumbered. The shaker is GC-agnostic, so it composes
		 * with the non-GC module shape for free. {@link OptimizeLevel#SIZE} is accepted
		 * and equals {@link OptimizeLevel#DEFAULT} here: this lowering is i64-native, so
		 * it never emits the boxed/unboxed pair the level declines on wasm-GC.
		 * <p>
		 * Defaults to {@link OptimizeLevel#DEFAULT} -- the level an absent
		 * {@code --optimize} selects, so an embedder that names no level gets what this
		 * project's own frontend gives. Declining the optimizer is asked for by name:
		 * {@link OptimizeLevel#NONE}.
		 * @param optimize the optimization level
		 * @return this builder
		 */
		public Builder optimize(OptimizeLevel optimize) {
			this.optimize = optimize;
			return this;
		}

		/**
		 * Selects {@code --simd} on the {@code --no-gc} backend, orthogonal to the memory
		 * model. When {@code true}, the vectorizable {@code vec:} kernels lower to native
		 * WASM v128 SIMD ({@code f64x2}/{@code f32x4}); when {@code false} they lower to
		 * scalar linear-memory loops that need no SIMD proposal.
		 * @param simd whether to lower the vectorizable kernels to v128
		 * @return this builder
		 */
		public Builder simd(boolean simd) {
			this.simd = simd;
			return this;
		}

		/**
		 * Selects {@code --no-gc --component}. When {@code true}, the module is wrapped
		 * as a reactor-style WASM component whose scalar exports are typed
		 * component-model exports; export names must be lower-kebab-case and
		 * {@code :async} is rejected.
		 * @param component whether to wrap the module as a component
		 * @return this builder
		 */
		public Builder component(boolean component) {
			this.component = component;
			return this;
		}

		/**
		 * Selects {@code --no-wasi}. When {@code true}, a PRINTING program's single
		 * {@code wasi_snapshot_preview1.fd_write} import is replaced by an internal
		 * discarding sink (the GC backend's {@code --no-wasi} contract: the whole iovec
		 * is reported written, errno 0, output lost -- nothing traps), keeping the module
		 * at zero imports. Function index 0 stays the sink, so every planned index holds.
		 * A print-free program never had the import, so the flag is a byte-exact no-op
		 * there. Under {@link #component} the wrap then never needs the print
		 * micro-adapter: the component has ONE core module, no imports, and its exports
		 * lift <strong>sync</strong> again -- a printing program collapses back onto the
		 * print-free shape instead of merely losing its imports. Output-only, like the GC
		 * backend: {@code --no-gc} rejects every other I/O at compile time already.
		 * @param noWasi whether a printing program's output sink stays internal
		 * @return this builder
		 */
		public Builder noWasi(boolean noWasi) {
			this.noWasi = noWasi;
			return this;
		}

		/**
		 * Builds the compiler.
		 * @return a new non-GC WASM compiler
		 */
		public NoGcWasmCompiler build() {
			return new NoGcWasmCompiler(this);
		}

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
		// The spliced files' load-context brackets, like the other backends: this
		// backend has no dynamic variables at all, so the pass only ever DROPS them
		// here (a program reading *load-pathname* fails on the symbol either way).
		program = LispMacroExpander.lowerLoadContextMarkers(program);
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
				LispNames.STREAM_NEW_INTERNAL_QUALIFIED,
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.READ_ALL),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WAIT_FOR))) {
			if (referencesSymbol(program, asyncName)) {
				throw new UnsupportedOperationException(
						asyncName + " is not supported with --no-gc (use the default GC backend)");
			}
		}

		// The block-quantized weight matrix (.kb/quantized-matrix.md): the interpreter
		// and the JVM only, refused by name like the bfloat16 width.
		for (String quantizedName : List.of(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZE),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.DEQUANTIZE),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.MAKE_QUANTIZED_MATRIX),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_ROWS),
				PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.QUANTIZED_MATRIX_P))) {
			if (referencesSymbol(program, quantizedName)) {
				throw am.ik.rontolisp.compiler.UnsupportedFloatWidth.refuseQuantized("the --no-gc backend");
			}
		}

		// Collect defuns and export directives. A --no-gc module is a pure-compute
		// reactor, so only function definitions and export directives are allowed at top
		// level; a stray expression would need a top-level init body (and most likely
		// I/O), which scalar mode does not support.
		Map<String, Defun> defuns = new HashMap<>();
		List<WasmExportCompiler.Decl> exportDecls = new ArrayList<>();
		LinkedHashMap<String, WasmImportCompiler.Decl> importDecls = new LinkedHashMap<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym
					&& LispNames.DEFUN.equals(sym.name())) {
				Defun d = extractDefun(LispMacroExpander.expandDefun(cons));
				defuns.put(d.name(), d);
			}
			else if (WasmExportCompiler.isExportForm(expr)) {
				exportDecls.add(WasmExportCompiler.parse((LispCons) expr));
			}
			else if (am.ik.rontolisp.compiler.JvmExportDirective.isExportForm(expr)) {
				// rontolisp:jvm-export declares a typed Java entry point for the JVM
				// backend; on WASM it is a no-op, exactly as wasm-export is on the JVM.
			}
			else if (WasmImportCompiler.isImportForm(expr)) {
				// A host function, callable from Lisp like a top-level defun: the
				// directive is parsed by the shared backend-independent front end and
				// becomes a synthetic internal function whose body marshals the scalar
				// boundary and calls a PLACEHOLDER index the WasmImportInjector resolves
				// once the module is assembled -- the same mechanism the wasm-GC backend
				// uses, over this backend's unboxed value model.
				WasmImportCompiler.Decl decl = WasmImportCompiler.parse((LispCons) expr,
						WasmImportCompiler.SCALAR_PARAM_TYPES, "--no-gc");
				validateImport(decl);
				if (importDecls.put(decl.name(), decl) != null) {
					throw new UnsupportedOperationException(
							"rontolisp:wasm-import declares '" + decl.name() + "' twice");
				}
			}
			else if (isConsumedPackageResidue(expr)) {
				// What PackageResolver leaves where a (defpackage ...) / (in-package ...)
				// stood: the package name as a quoted symbol, and the runtime half of the
				// switch as (setq *package* :P). Both are consumed declarations by the
				// time this backend sees them, and it has no top-level init body -- nor
				// any *package* to assign -- so both are dropped. A program that READS
				// *package* still fails, at the read, naming the symbol. This is what
				// lets a user defpackage (and therefore rontolisp:wit-import, whose
				// lowering writes one) reach the scalar backend at all.
			}
			else {
				throw new UnsupportedOperationException(unsupportedTopLevel(expr));
			}
		}
		for (String name : importDecls.keySet()) {
			if (defuns.containsKey(name)) {
				throw new UnsupportedOperationException("rontolisp:wasm-import '" + name
						+ "' has the same name as a top-level defun (one name, one function)");
			}
		}
		if (this.component) {
			// Two declarations of one (module, field) would put one function name twice
			// into the imported instance's type, which a component cannot express; on the
			// core module they are two import entries the host satisfies from one slot.
			Map<String, String> fieldOwner = new HashMap<>();
			for (WasmImportCompiler.Decl decl : importDecls.values()) {
				String previous = fieldOwner.put(decl.module() + "\u0000" + decl.field(), decl.name());
				if (previous != null) {
					throw new UnsupportedOperationException("rontolisp:wasm-import '" + decl.name() + "' and '"
							+ previous + "' both bind \"" + decl.module() + "\".\"" + decl.field()
							+ "\", which --no-gc --component cannot import twice (the imported instance declares"
							+ " each function once); call the one binding from both places");
				}
			}
		}
		this.imports = importDecls;
		this.importCallSites = new LinkedHashMap<>();
		this.literalOccurrences = new HashMap<>();
		this.printLiteralSites = new HashMap<>();
		this.foldedImports = Set.of();
		this.foldTargets = Map.of();
		this.foldOrdinals = Map.of();
		this.forwarders = Map.of();
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

		// The thin Lisp helpers over the raw imports: a call to one is the call to the
		// import, so it is where the literal-argument fold below looks for its
		// arguments. Computed before the BFS, which records the call sites against it.
		this.forwarders = findForwarders(defuns, importDecls, exportDecls);

		// Every name the program DEFINES, which is not the same as the reachable set
		// below: a (defun sqrt ...) is never enqueued, because every (sqrt ...) call
		// site compiles to the built-in -- that is exactly the override the dispatcher
		// warns about.
		LinkedHashSet<String> defined = new LinkedHashSet<>(defuns.keySet());
		defined.addAll(importDecls.keySet());
		this.definedNames = Set.copyOf(defined);

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
			if (importDecls.containsKey(name)) {
				// A host import has no body to walk: its parameter and result types are
				// DECLARED, and its only out-edge is the host call itself.
				continue;
			}
			Defun defun = Objects.requireNonNull(defuns.get(name));
			Set<String> callees = new LinkedHashSet<>();
			collectCalls(progn(defun.body()), new HashSet<>(defun.params()), defuns, callees, name, false);
			for (String callee : callees) {
				if (!defuns.containsKey(callee) && !importDecls.containsKey(callee)) {
					throw new UnsupportedOperationException(
							"--no-gc: call to undefined function '" + callee + "' in '" + name + "'");
				}
				enqueue(callee, index, work);
			}
		}
		// The import ordinals the placeholder call indices are emitted against, in
		// DECLARATION order (what the import section then reads as, like the wasm-GC
		// backend) restricted to the REACHED ones: a declared-but-uncalled import is
		// never enqueued, so it costs the module neither an import entry nor a byte --
		// the same "only what the exports reach" rule every other function here follows.
		LinkedHashMap<String, Integer> importOrdinals = new LinkedHashMap<>();
		for (String name : importDecls.keySet()) {
			if (index.containsKey(name)) {
				importOrdinals.put(name, importOrdinals.size());
			}
		}

		// Infer the i64/f64/i32 type of every parameter, local and return value.
		Types types = inferTypes(reachable, defuns, importDecls, exportDecls);

		// Lay out string literals in linear memory and decide whether the module needs
		// the
		// memory/allocator machinery at all (only when a string literal or a :string
		// boundary type is present).
		MemLayout layout = planMemory(reachable, defuns, importDecls, exportDecls, types);

		// An import every reached call site hands literals to loses its wrapper: those
		// sites call the host function themselves, and with nothing left that can name
		// the wrapper (this backend has no first-class functions) it is never emitted.
		// The type and memory plans above are unaffected -- the declaration still pins
		// the same boundary, and the literals are laid out by the bodies that hold them
		// -- so only the EMITTED function list narrows, which is why the decision sits
		// between the two.
		this.foldedImports = chooseFoldedImports(importDecls, importOrdinals, index.keySet(), layout);
		// A literal whose every occurrence is a folded site's :string argument is never
		// read as a string value, so it loses its [len] header. The two decisions are
		// circular -- the fold sizes each site's address constants against a layout, and
		// the layout needs the fold -- and are resolved in this order: the fold is sized
		// against the all-headered plan, then the SAME literal order is re-laid with the
		// header-free set applied. Dropping headers only lowers addresses, so a constant
		// the sizing measured can only get shorter, never flip a fold into a loss; and
		// nothing has been emitted yet, so every body compiles against the final layout.
		layout = layout.withHeaderFree(headerFreeLiterals(importDecls, layout));
		List<String> emitted = reachable;
		if (!this.foldedImports.isEmpty()) {
			Map<String, WasmImportCompiler.Decl> targets = new LinkedHashMap<>();
			Map<String, Integer> ordinals = new LinkedHashMap<>();
			for (String name : this.foldedImports) {
				targets.put(name, Objects.requireNonNull(importDecls.get(name)));
				ordinals.put(name, Objects.requireNonNull(importOrdinals.get(name)));
			}
			for (Map.Entry<String, String> forwarder : this.forwarders.entrySet()) {
				if (this.foldedImports.contains(forwarder.getValue())) {
					targets.put(forwarder.getKey(), Objects.requireNonNull(importDecls.get(forwarder.getValue())));
					ordinals.put(forwarder.getKey(), Objects.requireNonNull(importOrdinals.get(forwarder.getValue())));
				}
			}
			this.foldTargets = targets;
			this.foldOrdinals = ordinals;
			// Every name that folds is now unreachable: a folded import's wrapper has no
			// caller left, and a forwarder to it had no other body than that call. A
			// forwarder's own callee IS the import, so nothing cascades and the rest of
			// the reachable set is untouched -- only the numbering closes up.
			emitted = new ArrayList<>();
			LinkedHashMap<String, Integer> kept = new LinkedHashMap<>();
			for (String name : reachable) {
				if (targets.containsKey(name)) {
					continue;
				}
				kept.put(name, kept.size());
				emitted.add(name);
			}
			index = kept;
		}
		int internalCount = emitted.size();

		// Internal functions occupy indices 0..N-1; the emitted wrappers follow in
		// export-directive order; the memory helpers (when present) come after the
		// wrappers. An export whose host signature already matches the internal
		// function exactly and needs no heap reset is a pure pass-through: no wrapper
		// is emitted and the export names the internal function directly.
		//
		// Deciding this BEFORE the helper indices are placed is what lets any wrapper be
		// elided: the helpers sit after the wrappers, so their indices are a function of
		// how many wrappers there ARE, never of how many exports were declared. The
		// decision itself needs only the export directive, the inferred types and
		// whether the module uses memory -- all of which the layout already answers.
		int[] wrapperOrdinals = new int[exportDecls.size()];
		int[] exportOrdinals = new int[exportDecls.size()];
		int wrapperCount = 0;
		for (int j = 0; j < exportDecls.size(); j++) {
			WasmExportCompiler.Decl decl = exportDecls.get(j);
			if (isPassThroughExport(decl, types, layout.allocates())) {
				wrapperOrdinals[j] = -1;
				exportOrdinals[j] = Objects.requireNonNull(index.get(decl.name()));
			}
			else {
				wrapperOrdinals[j] = wrapperCount;
				exportOrdinals[j] = internalCount + wrapperCount;
				wrapperCount++;
			}
		}
		Mem mem = placeFunctions(layout, internalCount, wrapperCount);

		List<byte[]> internalBodies = new ArrayList<>();
		for (String name : emitted) {
			WasmImportCompiler.Decl imported = importDecls.get(name);
			internalBodies.add(imported != null
					? compileImportWrapperBody(imported, Objects.requireNonNull(importOrdinals.get(name)), mem)
					: compileDefunBody(Objects.requireNonNull(defuns.get(name)), name, types, index, mem));
		}
		List<byte[]> wrapperBodies = new ArrayList<>();
		for (int j = 0; j < exportDecls.size(); j++) {
			if (wrapperOrdinals[j] >= 0) {
				WasmExportCompiler.Decl decl = exportDecls.get(j);
				wrapperBodies.add(compileWrapperBody(decl, Objects.requireNonNull(index.get(decl.name())), types, mem));
			}
		}

		// The reached imports in ordinal order: assemble() appends their host-ABI type
		// entries after every other type and resolves the placeholder call indices
		// (WasmImportInjector), so the module handed to the tree shaker below is valid.
		List<WasmImportCompiler.Decl> hostImports = new ArrayList<>();
		for (String name : importOrdinals.keySet()) {
			hostImports.add(Objects.requireNonNull(importDecls.get(name)));
		}
		byte[] module = assemble(emitted, internalBodies, exportDecls, wrapperBodies, wrapperOrdinals, exportOrdinals,
				internalCount, types, mem, hostImports);
		if (this.optimize.eliminatesDeadCode()) {
			// The adjacent-instruction peepholes first, so a body they shrink can still
			// fit the move's budget; then the single-call-site move, which unreferences
			// the callee rather than deleting it, so the shake behind it is what collects
			// the body, the function entry and the type only that entry named; then the
			// single-use local sink, over the residue the move's argument hand-over and
			// the emitter's own temporaries leave, in front of the shake so the
			// renumbering LAST sees the frames it has left.
			module = am.ik.wasm.WasmLocalOrder.reorder(am.ik.wasm.WasmTreeShaker.shake(am.ik.wasm.WasmLocalSink
				.sink(am.ik.wasm.WasmInliner.inline(am.ik.wasm.WasmPeephole.rewrite(module)))));
		}
		if (this.component) {
			// Post-stage wrap: the core module is byte-identical to the non-component
			// output and the component just aliases + lifts its exports; a :string
			// boundary additionally aliases the canonical string ABI helpers appended by
			// assemble() above, and a printing program (the print micro-adapter) wires
			// the fixed shim/bridge/fixup modules implementing the fd_write import over
			// WASI 0.3 (async lifts + the blocking waitable-set park). Under --no-wasi
			// the core carries the fd_write sink instead of the import, so a printing
			// program takes the print-FREE shape: one core module, no imports, SYNC
			// lifts -- and the nogc (empty-world) WIT.
			final boolean printAdapter = mem.printUsed() && !this.noWasi;
			this.componentWit = WitEmitter.emitNoGc(
					printAdapter ? WitEmitter.VARIANT_NOGC_PRINT : WitEmitter.VARIANT_NOGC, exportDecls, hostImports);
			return NoGcWasmComponentBuilder.build(module, exportDecls, printAdapter, hostImports);
		}
		return module;
	}

	// The core signature the module imports a host function with. On the core-module
	// output it is the flat host ABI (a :string result answers two values); under
	// --component the import is a canon-lowered component function, whose :string result
	// arrives through a trailing return pointer instead (the canonical ABI caps flat
	// results at one), so the wrapper allocates the 8-byte record and reads the pair
	// back.
	private Type[] importParamTypes(WasmImportCompiler.Decl decl) {
		return this.component ? NoGcWasmComponentBuilder.coreParamTypes(decl) : WasmImportCompiler.hostParamTypes(decl);
	}

	private Type[] importResultTypes(WasmImportCompiler.Decl decl) {
		return this.component ? NoGcWasmComponentBuilder.coreResultTypes(decl)
				: WasmImportCompiler.hostResultTypes(decl);
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

	/**
	 * Validates a {@code rontolisp:wasm-import} directive beyond the type vocabulary
	 * ({@link WasmImportCompiler#SCALAR_PARAM_TYPES}) the parse has already enforced:
	 * both refusals here are about the SHAPE of the call, not about a value crossing it.
	 * The types themselves cross for free -- an integer designator, {@code :float} and
	 * {@code :bool} ARE the internal representation, and a {@code :string} already IS a
	 * {@code (ptr,len)} region of the module's own linear memory.
	 * @param decl the parsed declaration
	 */
	private void validateImport(WasmImportCompiler.Decl decl) {
		if (this.component) {
			validateComponentImport(decl);
		}
		if (decl.async()) {
			throw new UnsupportedOperationException("rontolisp:wasm-import :async is not supported with --no-gc for '"
					+ decl.name() + "': a started-equals-settled future is still a future, and this backend has no"
					+ " value to represent one (the whole async surface is rejected here). Drop :async t -- the host"
					+ " call is synchronous");
		}
	}

	/**
	 * Validates a {@code rontolisp:wasm-import} directive against the {@code --component}
	 * constraints: the import becomes a component-model instance import, so its module
	 * name is the instance's import name (a lower-kebab-case label, or a fully-qualified
	 * WIT interface id) and its field and parameter names are labels of the instance's
	 * type. The types themselves need no further check -- every designator this backend
	 * takes has a component value type -- and {@code :async} is refused after this like
	 * everywhere else on {@code --no-gc}.
	 * @param decl the parsed declaration
	 */
	private static void validateComponentImport(WasmImportCompiler.Decl decl) {
		if (!WasmExportCompiler.COMPONENT_EXPORT_NAME.matcher(decl.module()).matches()
				&& !NoGcWasmComponentBuilder.INTERFACE_ID.matcher(decl.module()).matches()) {
			throw new UnsupportedOperationException("rontolisp:wasm-import '" + decl.name() + "' :from \""
					+ decl.module() + "\" is not a valid component-model import name under --no-gc --component:"
					+ " the module becomes an imported instance, named either by a lower-kebab-case label (e.g."
					+ " \"env\") or by a WIT interface id (e.g. \"docs:host/env@0.1.0\")");
		}
		if (!WasmExportCompiler.COMPONENT_EXPORT_NAME.matcher(decl.field()).matches()) {
			throw new UnsupportedOperationException("rontolisp:wasm-import '" + decl.name() + "' :as \"" + decl.field()
					+ "\" is not a valid component-model function name under --no-gc --component"
					+ " (lower-kebab-case words, e.g. \"host-log\"); rename it with :as \"kebab-name\"");
		}
		for (String paramName : decl.paramNames()) {
			if (!WasmExportCompiler.COMPONENT_EXPORT_NAME.matcher(paramName).matches()) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-import '" + decl.name() + "' :param-names entry '" + paramName
								+ "' is not a valid component-model parameter name (lower-kebab-case words)");
			}
		}
	}

	/**
	 * The message a top-level form this backend does not accept is refused with. It names
	 * the SUBSET rather than only the offending form: a program is usually one form away
	 * from fitting, and "use the default GC backend" answers a question the user did not
	 * ask (that backend costs about ten times the bytes for the shape this one is for).
	 * @param expr the refused top-level form
	 * @return the message
	 */
	private static String unsupportedTopLevel(LispVal expr) {
		return "--no-gc supports only (defun ...), (rontolisp:wasm-export ...) and (rontolisp:wasm-import ...) at top"
				+ " level, got: " + expr.print() + " -- the scalar backend is a pure-compute reactor over unboxed"
				+ " i64/f64 values and linear-memory strings: no top-level init body, no cons or list, no symbol or"
				+ " hash value, no format, no eval, and packed float arrays only at rank 1 and 2. A program that"
				+ " needs any of those compiles on the default GC backend (drop --no-gc)";
	}

	/**
	 * Whether the top-level form is the residue a consumed package declaration leaves
	 * behind: the quoted package name a {@code defpackage} resolves to, or the
	 * {@code (setq *package* :P)} an {@code in-package} resolves to.
	 * @param expr the top-level form
	 * @return whether it can be dropped
	 */
	private static boolean isConsumedPackageResidue(LispVal expr) {
		if (!(expr instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& cons.cdr() instanceof LispCons rest)) {
			return false;
		}
		if (LispNames.QUOTE.equals(op.name())) {
			return rest.car() instanceof LispSymbol && rest.cdr() instanceof LispNil;
		}
		return LispNames.SETQ.equals(op.name()) && rest.car() instanceof LispSymbol name
				&& LispNames.PACKAGE_VAR.equals(name.name()) && rest.cdr() instanceof LispCons valueCell
				&& valueCell.car() instanceof LispSymbol value && value.isKeyword()
				&& valueCell.cdr() instanceof LispNil;
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
			Map<String, WasmImportCompiler.Decl> imports, List<WasmExportCompiler.Decl> exportDecls) {
		// Parameters of an exported function are pinned to the boundary designator (the
		// host passes them in); every other parameter type, every local type and every
		// return type starts at INT (bottom) and is only ever widened to FLOAT, so the
		// fixpoint is monotone and terminates.
		//
		// A host IMPORT is pinned on BOTH sides and never walked: it has no body to
		// infer from, and its declared designators are the whole contract -- so its
		// parameters join the pinned `boundary` map (the sink then leaves them alone and
		// every caller coerces to them, exactly as it does for an export) and its return
		// type is set once, below.
		Map<String, Ty[]> boundary = new HashMap<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			Ty[] pinned = new Ty[decl.paramTypes().size()];
			for (int i = 0; i < pinned.length; i++) {
				pinned[i] = boundaryTy(decl.paramTypes().get(i));
			}
			boundary.put(decl.name(), pinned);
		}
		for (WasmImportCompiler.Decl decl : imports.values()) {
			Ty[] pinned = new Ty[decl.paramTypes().size()];
			for (int i = 0; i < pinned.length; i++) {
				pinned[i] = boundaryTy(decl.paramTypes().get(i));
			}
			boundary.put(decl.name(), pinned);
		}

		// A function exported under a VALUE-returning designator owes the host one value,
		// so its return type seeds at BOOL -- the value bottom below INT -- and a body
		// that is itself void (a while, a void import call) materializes the nil it
		// stands for inside the function rather than leaving the wrapper with an empty
		// stack. Every other function seeds at VOID, the true bottom, so "this answers
		// nothing" survives the fixpoint instead of being invented.
		Set<String> valueExports = new HashSet<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			if (decl.returnType() != BoundaryType.VOID) {
				valueExports.add(decl.name());
			}
		}

		Map<String, Ty[]> params = new HashMap<>();
		Map<String, Ty> returns = new HashMap<>();
		Map<String, Map<String, Ty>> locals = new HashMap<>();
		for (String name : reachable) {
			WasmImportCompiler.Decl imported = imports.get(name);
			if (imported != null) {
				params.put(name, Objects.requireNonNull(boundary.get(name)).clone());
				returns.put(name, importReturnTy(imported));
				locals.put(name, new HashMap<>());
				continue;
			}
			Defun d = Objects.requireNonNull(defuns.get(name));
			params.put(name, boundary.containsKey(name) ? boundary.get(name).clone() : filled(d.params().size()));
			returns.put(name, valueExports.contains(name) ? Ty.BOOL : Ty.VOID);
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
				if (imports.containsKey(name)) {
					nextParams.put(name, Objects.requireNonNull(boundary.get(name)).clone());
					continue;
				}
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
				if (imports.containsKey(name)) {
					continue; // declared on both sides; nothing to walk
				}
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

	/**
	 * The internal value type an imported host function's result arrives as: the
	 * designator's own internal kind, and VOID for a {@code :void} import -- nothing came
	 * back, so nothing is pushed and nobody has to drop it.
	 * @param decl the parsed import declaration
	 * @return the internal type of the wrapper's result
	 */
	private static Ty importReturnTy(WasmImportCompiler.Decl decl) {
		return decl.returnType() == BoundaryType.VOID ? Ty.VOID : boundaryTy(decl.returnType());
	}

	private static Ty[] filled(int n) {
		Ty[] arr = new Ty[n];
		Arrays.fill(arr, Ty.BOOL);
		return arr;
	}

	// The internal value type a boundary designator pins a parameter to: :float -> FLOAT,
	// :string -> STRING, :bool -> BOOL, every integer designator -> INT (the house i64).
	private static Ty boundaryTy(BoundaryType designator) {
		return switch (designator) {
			case FLOAT -> Ty.FLOAT;
			case STRING -> Ty.STRING;
			case BOOL -> Ty.BOOL;
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

	// The type a LOCAL SLOT holds. A slot is storage, so it is never VOID: binding the
	// value of a void form stores the nil it stands for, which is the i64 zero. (The
	// materialization itself is coerce's, at the initializer.)
	private static Ty slotTy(Ty t) {
		return t == Ty.VOID ? Ty.BOOL : t;
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
	// A standard INTEGER/DOUBLE constant's literal for a code-position reference,
	// or null when the spelling names no scalar constant. The reader binds these
	// names as symbols (even under quote), and scalar mode has no globals, so the
	// reference answers the literal directly -- with the WASM values, the ones the
	// --no-gc frontend read the program with. A list-valued constant
	// (lambda-list-keywords) has no scalar spelling and stays unsupported here.
	// @param spelling the symbol name as written (upcased, package prefix intact)
	// @return the literal, or null
	private static @Nullable LispVal scalarConstant(String spelling) {
		LispVal constant = ClConstants.value(ClConstants.memberOf(spelling), true);
		return (constant instanceof LispDouble || constant instanceof LispInteger) ? constant : null;
	}

	private Ty typeOf(LispVal expr, Map<String, Ty> env, TC tc) {
		return switch (expr) {
			case LispInteger ignored -> Ty.INT;
			case LispDouble ignored -> Ty.FLOAT;
			case LispString ignored -> Ty.STRING;
			case am.ik.rontolisp.LispSingleFloatArray ignored -> Ty.F32VEC;
			case am.ik.rontolisp.LispDoubleFloatArray ignored -> Ty.F64VEC;
			// Named rather than left to a `case LispFloatArray` arm over the SEALED
			// umbrella, which matched this width too and tagged it F64VEC -- the silent
			// fall into the double arm LispFloatArray's own javadoc warns against. The
			// scalar backend has an F32VEC and an F64VEC and nothing else.
			case am.ik.rontolisp.LispBFloat16Array ignored -> throw am.ik.rontolisp.compiler.UnsupportedFloatWidth
				.refuse(am.ik.rontolisp.FloatWidth.BFLOAT16, "the --no-gc backend");
			case LispChar ignored -> Ty.INT;
			case LispTrue ignored -> Ty.BOOL;
			case LispNil ignored -> Ty.BOOL;
			case LispSymbol sym -> {
				Ty local = env.get(sym.name());
				if (local != null) {
					yield local;
				}
				// A standard scalar constant in code position has its literal's type
				// (the reader no longer substitutes the value; see
				// .kb/read-time-constants.md).
				LispVal sc = scalarConstant(sym.name());
				if (sc instanceof LispDouble) {
					yield Ty.FLOAT;
				}
				if (sc instanceof LispInteger) {
					yield Ty.INT;
				}
				yield Ty.BOOL;
			}
			case LispCons cons -> typeOfCall(cons, env, tc);
			default -> Ty.BOOL;
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
				// Both arms are walked whatever the test says -- the walk is what records
				// call sites and widens locals -- but a CONSTANT test takes only the
				// branch it selects into the result. The macro expander ends every cond
				// chain in `(if t ... nil)`, so without this the dead nil would drag an
				// all-void chain back up to INT and put the zero it stands for into every
				// arm.
				Ty thenTy = typeOf(args.get(2), env, tc);
				// An ABSENT else produces nothing at all -- not the nil an explicit one
				// would -- so it contributes VOID rather than the INT zero.
				Ty elseTy = args.size() > 3 ? typeOf(args.get(3), env, tc) : Ty.VOID;
				if (args.get(1) instanceof LispTrue) {
					return thenTy;
				}
				if (args.get(1) instanceof LispNil) {
					return elseTy;
				}
				return thenTy.join(elseTy);
			}
			case LispNames.PROGN -> {
				// An EMPTY progn produces nothing, which is VOID, not the nil zero.
				Ty last = Ty.VOID;
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
				// while is VOID -- it pushes nothing; still walk the test/body/steps so
				// their
				// call sites and local mutations are recorded.
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.VOID;
			}
			case LispNames.BLOCK_INTERNAL -> {
				// %block result = join(normal completion, every (return v) inside it).
				Ty[] box = { Ty.VOID };
				tc.blockReturns.push(box);
				Ty bodyTy = Ty.VOID;
				for (int i = 1; i < args.size(); i++) {
					bodyTy = typeOf(args.get(i), env, tc);
				}
				tc.blockReturns.pop();
				return bodyTy.join(box[0]);
			}
			case LispNames.RETURN -> {
				Ty vt = args.size() > 1 ? typeOf(args.get(1), env, tc) : Ty.VOID;
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
				return Ty.VOID;
			}
			// with-arena yields its body's value (a progn with a reclamation boundary).
			case LispNames.WITH_ARENA_QUALIFIED -> {
				Ty last = Ty.VOID;
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
				LispVal makeElementType = findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD);
				// Refused in the TYPE pass as well as in the codegen one below, so the
				// two agree: this pass used to answer F64VEC for a width the emitter
				// then refused, which is a guess standing in for an error.
				refuseUnsupportedWidth(makeElementType);
				boolean single = switch (LispFloatArray.prototypeFor(makeElementType)) {
					case null -> false;
					case am.ik.rontolisp.LispDoubleFloatArray ignored -> false;
					case am.ik.rontolisp.LispSingleFloatArray ignored -> true;
					// Unreachable -- refuseUnsupportedWidth threw first; the arm keeps
					// this switch exhaustive over the permits, so the type pass and the
					// emitter cannot disagree about the next width.
					case am.ik.rontolisp.LispBFloat16Array ignored ->
						throw am.ik.rontolisp.compiler.UnsupportedFloatWidth.refuse(am.ik.rontolisp.FloatWidth.BFLOAT16,
								"the --no-gc backend");
				};
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
			// The boolean producers: comparisons, not and the string/char equalities
			// answer BOOL (an i64 0/1, like INT on the stack). Everything else here --
			// the bitwise operators, length and the character accessors -- answers a
			// genuine integer.
			case LispNames.EQ, LispNames.LT, LispNames.LE, LispNames.GT, LispNames.GE, LispNames.NOT,
					LispNames.STRING_EQ, LispNames.CHAR_EQ -> {
				for (int i = 1; i < args.size(); i++) {
					typeOf(args.get(i), env, tc);
				}
				return Ty.BOOL;
			}
			case LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH,
					LispNames.LENGTH, LispNames.CHAR, LispNames.CHAR_CODE, LispNames.CODE_CHAR -> {
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
			// +,-,*,mod,rem,abs,min,max are FLOAT iff any operand is FLOAT, INT as soon
			// as
			// any operand is INT, and BOOL only when every operand is BOOL (an all-BOOL
			// sum is still an integer value, so the BOOL bottom keeps the arithmetic
			// shapes from ever answering BOOL: the seed below is INT, and BOOL yields
			// to it on contact).
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
				return rt == null ? Ty.BOOL : rt;
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
			Ty initTy = slotTy(typeOf(init, env, tc));
			Ty bindTy = tc.widen ? widenLocal(tc.locals(), varName, initTy, tc.changed)
					: tc.locals().getOrDefault(varName, initTy).join(initTy);
			inner.put(varName, bindTy);
		}
		Ty last = Ty.BOOL;
		for (int i = 2; i < parts.size(); i++) {
			last = typeOf(parts.get(i), inner, tc);
		}
		return last;
	}

	private Ty typeOfSetq(List<LispVal> args, Map<String, Ty> env, TC tc) {
		Ty last = Ty.BOOL;
		int pairs = (args.size() - 1) / 2;
		for (int p = 0; p < pairs; p++) {
			String var = ((LispSymbol) args.get(1 + 2 * p)).name();
			Ty rhsTy = slotTy(typeOf(args.get(2 + 2 * p), env, tc));
			if (tc.params.contains(var)) {
				// A parameter has a fixed wasm type; the assignment coerces to it.
				last = env.getOrDefault(var, Ty.BOOL);
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
	 * @param literals string-literal content to its header address in the data segment,
	 * for the HEADERED literals only (see {@link MemLayout})
	 * @param regions every literal's content to its content address, headered or not
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
	 * @param strlenIndex the function index of the {@code __strlen_cp} code-point-count
	 * helper (-1 when no reachable body takes the {@code length} of a string)
	 * @param byteOffsetIndex the function index of the {@code __byte_offset}
	 * code-point-to-byte-offset helper (-1 when no reachable body calls {@code subseq} or
	 * {@code char}, the two operators that convert)
	 * @param charAtIndex the function index of the {@code __char_at} code-point-index
	 * helper (-1 when no reachable body calls {@code char})
	 * @param markIndex the function index of the {@code __ronto_alloc_mark}
	 * arena-snapshot export (-1 when the boundary gives no host a use for it)
	 * @param resetIndex the function index of the {@code __ronto_alloc_reset}
	 * arena-restore export (-1 when the boundary gives no host a use for it)
	 * @param rontoAllocIndex the function index of the exported {@code __ronto_alloc}
	 * host allocator (-1 when the boundary gives no host a use for it). It is NOT the
	 * internal {@code __alloc}: it reserves four bytes ahead of the pointer it returns
	 * for the {@code [len]} header an export wrapper's {@code :string} parameter writes
	 * at {@code ptr - 4}, and is a one-call wrapper over {@code __alloc}
	 * @param ftoaIndex the function index of the {@code __ftoa} float-to-string helper
	 * (-1 when no float is rendered)
	 * @param writeStdoutIndex the function index of the {@code __write_stdout} funnel,
	 * the sole caller of the fd_write import (-1 when print is unused)
	 * @param used whether the module uses linear memory at all
	 * @param printUsed whether the module prints (fd_write import + __write_stdout)
	 * @param ftoaUsed whether the module renders a float to text (__ftoa)
	 * @param hostArena whether the arena API (the {@code __ronto_alloc} family of exports
	 * and the mark/reset bodies behind two of them) is emitted at all
	 * @param allocates whether anything in the module can bump the heap during a call
	 */
	private record Mem(Map<String, Integer> literals, Map<String, Integer> regions, byte[] data, int dataBase,
			int heapBase, int iovAddr, int funcBase, int allocIndex, int memcpyIndex, int streqIndex, int itoaIndex,
			int strlenIndex, int byteOffsetIndex, int charAtIndex, int markIndex, int resetIndex, int rontoAllocIndex,
			int ftoaIndex, int schubBase, int writeStdoutIndex, boolean used, boolean printUsed, boolean ftoaUsed,
			boolean hostArena, boolean allocates) {

		// The five Schubfach helpers behind __ftoa, appended right after it
		// in this order; bodies come from WasmSchubfachRuntimeBuilder.
		int schubUmulhiIndex() {
			return this.ftoaIndex + 1;
		}

		int schubGIndex() {
			return this.ftoaIndex + 2;
		}

		int schubRopIndex() {
			return this.ftoaIndex + 3;
		}

		int schubF64DecIndex() {
			return this.ftoaIndex + 4;
		}

		int schubDecFmtIndex() {
			return this.ftoaIndex + 5;
		}

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

	/**
	 * What the module needs from linear memory, decided before any function index exists:
	 * the literal layout and the three gates ({@code used}, {@code printUsed},
	 * {@code ftoaUsed}) that say which helper functions will be emitted at all.
	 *
	 * <p>
	 * This half is deliberately index-FREE. The helpers sit after the export wrappers, so
	 * their indices depend on how many wrappers are emitted, which in turn depends on
	 * {@code used} -- placing them here would mean assuming a wrapper count before the
	 * pass-through decision that determines it. {@link #placeFunctions} assigns the
	 * indices once that count is known.
	 *
	 * <p>
	 * A literal is laid out in one of two shapes. HEADERED: {@code [len:i32 LE][bytes]},
	 * the string value a Lisp-level use reads, its header address in {@code literals}.
	 * HEADER-FREE: the bytes alone, for a literal whose every occurrence is a
	 * {@code :string} argument of a folded import call site
	 * ({@link #chooseFoldedImports}) -- such a site pushes the content address and the
	 * byte length as two constants and never reads a header. {@code regions} maps every
	 * literal, in either shape, to its content address. A header-free literal has no
	 * {@code literals} entry, so a value use of one fails loudly instead of reading a
	 * neighbour's bytes as a length.
	 *
	 * @param literals string-literal content to its header address, headered ones only
	 * @param regions every literal's content to its content address
	 * @param data the static data-segment bytes (laid out from {@code STR_DATA_BASE})
	 * @param heapBase the initial bump-allocator pointer (just past the static data)
	 * @param iovAddr the address of the 16-byte fd_write scratch; 0 when print is unused
	 * @param schubBase the address of the Schubfach tables (0 when no float is rendered)
	 * @param used whether the module uses linear memory at all
	 * @param printUsed whether the module prints (fd_write import + __write_stdout)
	 * @param ftoaUsed whether the module renders a float to text (__ftoa)
	 * @param strlenUsed whether a reachable body takes the {@code length} of a string
	 * (the {@code __strlen_cp} code-point-count helper). A {@code length} over a packed
	 * float vector alone does not set this: that still reads the element-count header.
	 * @param byteOffsetUsed whether a reachable body calls {@code subseq} or {@code char}
	 * (the {@code __byte_offset} code-point-to-byte-offset helper; {@code __char_at} is
	 * built on it, so a {@code char} sets this too)
	 * @param charAtUsed whether a reachable body calls {@code char} (the
	 * {@code __char_at} code-point-index helper)
	 * @param hostArena whether the boundary gives the HOST a reason to touch the bump
	 * heap, which is what the {@code __ronto_alloc} / {@code __ronto_alloc_mark} /
	 * {@code __ronto_alloc_reset} exports and the mark/reset bodies are for
	 * @param allocates whether anything in the module can bump the heap during a call,
	 * which is what an export wrapper's save/restore bracket exists to undo
	 */
	private record MemLayout(Map<String, Integer> literals, Map<String, Integer> regions, byte[] data, int heapBase,
			int iovAddr, int schubBase, boolean used, boolean printUsed, boolean ftoaUsed, boolean strlenUsed,
			boolean byteOffsetUsed, boolean charAtUsed, boolean hostArena, boolean allocates) {

		/**
		 * The same plan with the given literals laid out header-free, in the same order.
		 * Every gate is unchanged -- each is a property of the bodies and the boundary,
		 * not of the layout -- so only the data bytes and the addresses derived from them
		 * move.
		 */
		MemLayout withHeaderFree(Set<String> headerFree) {
			if (headerFree.isEmpty()) {
				return this;
			}
			DataPlan plan = layoutData(this.regions.keySet(), headerFree, this.ftoaUsed, this.printUsed);
			return new MemLayout(plan.literals(), plan.regions(), plan.data(), plan.heapBase(), plan.iovAddr(),
					plan.schubBase(), this.used, this.printUsed, this.ftoaUsed, this.strlenUsed, this.byteOffsetUsed,
					this.charAtUsed, this.hostArena, this.allocates);
		}
	}

	/**
	 * The data-segment half of a {@link MemLayout}, as {@link #layoutData} answers it.
	 */
	private record DataPlan(Map<String, Integer> literals, Map<String, Integer> regions, byte[] data, int heapBase,
			int iovAddr, int schubBase) {
	}

	private static final int STR_DATA_BASE = 8;

	/**
	 * Lays the literals out from {@link #STR_DATA_BASE} in the given order -- each
	 * headered, or its bytes alone when named in {@code headerFree} -- then the Schubfach
	 * tables and the fd_write scratch.
	 */
	private static DataPlan layoutData(Collection<String> literals, Set<String> headerFree, boolean ftoaUsed,
			boolean printUsed) {
		LinkedHashMap<String, Integer> offsets = new LinkedHashMap<>();
		LinkedHashMap<String, Integer> regions = new LinkedHashMap<>();
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		int cursor = STR_DATA_BASE;
		for (String s : literals) {
			// Blocks are packed, not 4-byte aligned. The only aligned access into one is
			// the `i32.load align=2` that reads the [len] header, and in wasm the
			// alignment immediate is a HINT: an unaligned address is legal and every
			// engine serves it. Padding to it cost a byte per odd-length literal and
			// bought nothing. (The Schubfach tables below keep their alignment: those
			// are i64/f64 table reads in the float renderer's inner loop, where the hint
			// is worth honouring.)
			byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			if (!headerFree.contains(s)) {
				offsets.put(s, cursor);
				writeI32LE(data, bytes.length);
				cursor += 4;
			}
			regions.put(s, cursor);
			data.write(bytes, 0, bytes.length);
			cursor += bytes.length;
		}
		// The Schubfach tables behind __ftoa: raw bytes after the literals.
		int schubBase = 0;
		if (ftoaUsed) {
			while ((cursor & 3) != 0) {
				data.write(0);
				cursor++;
			}
			schubBase = cursor;
			byte[] blob = SchubfachTables.blob();
			data.write(blob, 0, blob.length);
			cursor += blob.length;
		}
		int heapBase = (cursor + 7) & ~7;
		// The fd_write scratch (iovec + nwritten) sits between the static data and the
		// bump heap, present only when the module prints.
		int iovAddr = 0;
		if (printUsed) {
			iovAddr = heapBase;
			heapBase += 16;
		}
		return new DataPlan(offsets, regions, data.toByteArray(), heapBase, iovAddr, schubBase);
	}

	/**
	 * Which of the print-support text fragments a module actually reads. Each of the five
	 * print pool entries is emitted on first use, the way a program literal already is:
	 * {@code newline} for {@code terpri} or any {@code print} (which always trails one),
	 * {@code quote} for a {@code print} of a string (its framing quotes and the escape
	 * scan's backslash share that one gate), {@code boolT}/{@code boolNil} for a boolean
	 * print ({@code print}/{@code princ} of a {@code BOOL}, or the literal it names -- a
	 * computed boolean needs both, a lone literal only its own), {@code boolToString} for
	 * a {@code princ-to-string} of a {@code BOOL} (which answers the static header, so it
	 * pins both spellings headered), and {@code intFloat} for a
	 * {@code print}/{@code princ} of an {@code INT}/{@code FLOAT} (which renders through
	 * {@code __itoa}/{@code __ftoa} and is therefore the only printing that allocates).
	 */
	private record PrintUse(boolean newline, boolean quote, boolean boolT, boolean boolNil, boolean boolToString,
			boolean intFloat) {
	}

	/**
	 * The fixed text fragments the runtime helpers hand out -- the printer's five, and
	 * {@code __ftoa}'s IEEE specials -- pooled only when used. The print five arrive one
	 * by one on the {@link PrintUse} gates above; the float specials still arrive whole
	 * on {@code ftoaUsed} (any f64 can be a NaN). Order is the old whole-pool order, so a
	 * module that uses everything keeps its exact bytes.
	 */
	private static List<String> runtimeLiterals(PrintUse use, boolean ftoaUsed) {
		List<String> out = new ArrayList<>();
		if (use.newline()) {
			out.add("\n");
		}
		if (use.quote()) {
			out.add("\"");
			// The single-escape byte print emits before an embedded " / \ (todo 216).
			out.add("\\");
		}
		if (use.boolT() || use.boolToString()) {
			out.add("T");
		}
		if (use.boolNil() || use.boolToString()) {
			out.add("NIL");
		}
		if (ftoaUsed) {
			out.add("NaN");
			out.add("Infinity");
			out.add("-Infinity");
		}
		return out;
	}

	/**
	 * The literals the runtime helpers hand out as HEADER pointers and therefore pin
	 * headered: {@code __ftoa}'s IEEE specials, and {@code T}/{@code NIL} while a
	 * {@code princ-to-string} of a boolean answers them. Every other print fragment is
	 * written as a region (two constants) and may drop its header like a folded program
	 * literal.
	 */
	private static List<String> headerPinnedLiterals(PrintUse use, boolean ftoaUsed) {
		List<String> out = new ArrayList<>();
		if (use.boolToString()) {
			out.add("T");
			out.add("NIL");
		}
		if (ftoaUsed) {
			out.add("NaN");
			out.add("Infinity");
			out.add("-Infinity");
		}
		return out;
	}

	/** The print-pool fragments laid out only as regions (never pinned headered). */
	private static List<String> regionOnlyLiterals(PrintUse use) {
		List<String> out = new ArrayList<>();
		if (use.newline()) {
			out.add("\n");
		}
		if (use.quote()) {
			out.add("\"");
			out.add("\\");
		}
		if (!use.boolToString()) {
			if (use.boolT()) {
				out.add("T");
			}
			if (use.boolNil()) {
				out.add("NIL");
			}
		}
		return out;
	}

	private MemLayout planMemory(List<String> reachable, Map<String, Defun> defuns,
			Map<String, WasmImportCompiler.Decl> imports, List<WasmExportCompiler.Decl> exportDecls, Types types) {
		// Gather every string literal in every reachable body (deterministic order); they
		// are laid out below (layoutData). A host import has no body: everything below
		// that walks one skips it.
		LinkedHashSet<String> literals = new LinkedHashSet<>();
		for (String name : reachable) {
			if (imports.containsKey(name)) {
				continue;
			}
			collectLiterals(progn(Objects.requireNonNull(defuns.get(name)).body()), literals);
		}
		// Printing: print/princ/terpri gate the fd_write import and the __write_stdout
		// funnel; a rendered FLOAT (print/princ/princ-to-string of a f64) additionally
		// gates the __ftoa helper. Both add their fixed text fragments to the literal
		// pool ONLY when used, so a print-free module keeps its exact bytes.
		boolean printUsed = false;
		for (String name : reachable) {
			if (!imports.containsKey(name) && usesPrintOp(progn(Objects.requireNonNull(defuns.get(name)).body()))) {
				printUsed = true;
				break;
			}
		}
		boolean ftoaUsed = false;
		for (String name : reachable) {
			if (!imports.containsKey(name) && rendersFloat(name, Objects.requireNonNull(defuns.get(name)), types)) {
				ftoaUsed = true;
				break;
			}
		}
		PrintUse printUse = collectPrintUse(reachable, defuns, imports, types);
		this.printUse = printUse;
		literals.addAll(runtimeLiterals(printUse, ftoaUsed));
		// Every literal headered for now. Which ones can drop the header depends on the
		// import fold, and the fold is sized against THIS plan (chooseFoldedImports), so
		// the caller re-lays the same order once the fold is decided (withHeaderFree).
		DataPlan plan = layoutData(literals, Set.of(), ftoaUsed, printUsed);

		boolean boundaryString = false;
		// Whether the HOST has anything to do with the bump heap, which is what the
		// __ronto_alloc / __ronto_alloc_mark / __ronto_alloc_reset exports are for. It
		// is a property of the boundary DECLARATIONS alone, in three shapes:
		//
		// - an export takes a :string -- the host allocates the input buffer in here;
		// - a reached import RETURNS a :string -- the host writes the result bytes in
		// here, through the same allocator, before handing back (ptr,len);
		// - an export RETURNS a :string -- the pointer escapes to the host, so that
		// wrapper cannot auto-reset the heap and only the host can pop it.
		//
		// Every other module allocates only inside a call whose wrapper restores the
		// heap pointer on the way out (compileWrapperBody's scalar auto-reset), so a
		// host that called the allocator would have nothing to pass the block to and
		// nothing to reclaim. A :string ARGUMENT to an import is not on this list: it
		// hands the host the (ptr,len) of a block this module already owns.
		boolean hostArena = false;
		// Whether anything can bump the heap DURING a call, which is what the export
		// wrappers' auto-reset bracket exists to undo. Every __alloc call site in the
		// emitted code is one of: a :string import result copied in (below), a
		// string-producing op, a packed vector, or the int/float renderers behind
		// printing. A :string export PARAMETER is not on this list: the host's block
		// becomes the string in place (the wrapper only writes the [len] header at
		// ptr - 4 into the four bytes __ronto_alloc reserved ahead of it), so nothing
		// bumps. A module whose only use of
		// memory is reading its own literals -- the shape a host-facing reactor that
		// only passes text OUT takes -- never calls it, and every one of its wrappers
		// would save and restore a heap pointer that cannot move.
		boolean allocates = false;
		for (WasmExportCompiler.Decl decl : exportDecls) {
			if (decl.returnType() == BoundaryType.STRING || decl.paramTypes().contains(BoundaryType.STRING)) {
				boundaryString = true;
				hostArena = true;
			}
		}
		// A host import's :string boundary is the same linear-memory crossing an
		// export's is, in the other direction: an argument is handed over as the
		// (content ptr, len) of a block this module already holds, and a result is bytes
		// the host wrote here (through the exported memory + __ronto_alloc) that the
		// wrapper copies into a fresh [len][bytes] block. Either way the module needs
		// the memory and the allocator.
		for (String name : reachable) {
			WasmImportCompiler.Decl decl = imports.get(name);
			if (decl != null
					&& (decl.returnType() == BoundaryType.STRING || decl.paramTypes().contains(BoundaryType.STRING))) {
				boundaryString = true;
				hostArena |= decl.returnType() == BoundaryType.STRING;
				allocates |= decl.returnType() == BoundaryType.STRING;
			}
		}
		// A body can produce a string without any literal or :string boundary (e.g.
		// (length (princ-to-string n)) on an :int export), so string-producing ops also
		// flag the memory as used.
		boolean stringOp = false;
		for (String name : reachable) {
			if (!imports.containsKey(name) && usesStringOp(progn(Objects.requireNonNull(defuns.get(name)).body()))) {
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
			if (!imports.containsKey(name) && usesFloatArray(progn(Objects.requireNonNull(defuns.get(name)).body()))) {
				floatVec = true;
				break;
			}
		}
		// A nonempty literal pool implies `used`, so `used` follows.
		boolean used = !literals.isEmpty() || boundaryString || stringOp || floatVec;
		// Only printing an INT/FLOAT renders through __itoa / __ftoa, both of which
		// allocate the text they return; a folded literal write or a string passthrough
		// moves no heap, so a literal-only printing module bumps nothing. A
		// string-producing op and a packed vector allocate by definition.
		allocates |= stringOp || floatVec || printUse.intFloat();
		// The UTF-8 code-point helpers behind length/char/subseq, each gated on the
		// operator that calls it -- the same per-use gating printUsed/ftoaUsed give the
		// print and float-render helpers, so a module that only moves text across the
		// boundary (literals, concatenate, :string params) pays nothing for indexing
		// it never does. __char_at is built on __byte_offset, so a char sets both.
		boolean charAtUsed = false;
		boolean byteOffsetUsed = false;
		for (String name : reachable) {
			if (imports.containsKey(name)) {
				continue;
			}
			LispVal body = progn(Objects.requireNonNull(defuns.get(name)).body());
			if (!charAtUsed && usesOp(body, LispNames.CHAR)) {
				charAtUsed = true;
				byteOffsetUsed = true;
			}
			if (!byteOffsetUsed && usesOp(body, LispNames.SUBSEQ)) {
				byteOffsetUsed = true;
			}
			if (charAtUsed && byteOffsetUsed) {
				break;
			}
		}
		boolean strlenUsed = usesStringLength(reachable, defuns, imports, types);
		return new MemLayout(plan.literals(), plan.regions(), plan.data(), plan.heapBase(), plan.iovAddr(),
				plan.schubBase(), used, printUsed, ftoaUsed, strlenUsed, byteOffsetUsed, charAtUsed, hostArena,
				allocates);
	}

	/**
	 * Assign a function index to every helper the layout calls for. The helpers follow
	 * the internal functions and the EMITTED export wrappers, so the caller passes the
	 * wrapper count it actually arrived at -- never the export-directive count, which is
	 * the same number only while no wrapper is elided.
	 * @param layout the index-free memory plan
	 * @param internalCount the number of internal functions (indices 0..N-1)
	 * @param wrapperCount the number of export wrappers actually emitted
	 * @return the complete memory plan
	 */
	private static Mem placeFunctions(MemLayout layout, int internalCount, int wrapperCount) {
		int funcBase = layout.printUsed() ? 1 : 0;
		int allocIndex = funcBase + internalCount + wrapperCount;
		int memcpyIndex = allocIndex + 1;
		int streqIndex = memcpyIndex + 1;
		int itoaIndex = streqIndex + 1;
		int next = itoaIndex + 1;
		// The UTF-8 code-point helpers, each gated on the operator that calls it
		// (planMemory's strlenUsed/byteOffsetUsed/charAtUsed): a module that never
		// indexes a string emits none of them and keeps its exact bytes.
		// __char_at is built on __byte_offset, so charAtUsed implies byteOffsetUsed.
		int strlenIndex = layout.strlenUsed() ? next++ : -1;
		int byteOffsetIndex = layout.byteOffsetUsed() ? next++ : -1;
		int charAtIndex = layout.charAtUsed() ? next++ : -1;
		// The host arena API __ronto_alloc_mark/_reset plus the exported __ronto_alloc
		// host allocator: three more exported functions over
		// the same heap-pointer global, appended after the string helpers. --no-gc
		// has no fixed-index invariant, so appending is free (nothing renumbers) -- and
		// so is leaving them out when the boundary gives no host a use for them.
		int markIndex = layout.hostArena() ? next++ : -1;
		int resetIndex = layout.hostArena() ? next++ : -1;
		int rontoAllocIndex = layout.hostArena() ? next++ : -1;
		// The printing helpers append after the arena pair, again gated.
		int ftoaIndex = layout.ftoaUsed() ? next : -1;
		// __ftoa is followed by its five Schubfach helpers (see Mem.schub*Index).
		if (layout.ftoaUsed()) {
			next += 6;
		}
		int writeStdoutIndex = layout.printUsed() ? next : -1;
		return new Mem(layout.literals(), layout.regions(), layout.data(), STR_DATA_BASE, layout.heapBase(),
				layout.iovAddr(), funcBase, allocIndex, memcpyIndex, streqIndex, itoaIndex, strlenIndex,
				byteOffsetIndex, charAtIndex, markIndex, resetIndex, rontoAllocIndex, ftoaIndex, layout.schubBase(),
				writeStdoutIndex, layout.used(), layout.printUsed(), layout.ftoaUsed(), layout.hostArena(),
				layout.allocates());
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

	/**
	 * Which print-support fragments the reachable bodies read, settled against the frozen
	 * inference result so each gate agrees with the type the code generator sees at the
	 * site. A {@code print} always trails a newline; only a {@code print} of a string
	 * needs the framing quotes and the escape backslash; only a boolean print needs
	 * {@code T}/{@code NIL} (a computed one both, a lone literal its own); only a
	 * {@code princ-to-string} of a boolean pins them headered; and only an
	 * {@code INT}/{@code FLOAT} print renders through the allocating helpers.
	 */
	private PrintUse collectPrintUse(List<String> reachable, Map<String, Defun> defuns,
			Map<String, WasmImportCompiler.Decl> imports, Types types) {
		boolean newline = false;
		boolean quote = false;
		boolean boolT = false;
		boolean boolNil = false;
		boolean boolToString = false;
		boolean intFloat = false;
		for (String name : reachable) {
			if (imports.containsKey(name)) {
				continue;
			}
			Defun d = Objects.requireNonNull(defuns.get(name));
			Map<String, Ty> env = paramEnv(d, types.params());
			env.putAll(Objects.requireNonNull(types.locals().get(name)));
			TC tc = new TC(name, new HashSet<>(d.params()), types, null, false, new boolean[1]);
			List<LispVal> forms = new ArrayList<>();
			collectPrintForms(progn(d.body()), forms);
			for (LispVal form : forms) {
				List<LispVal> args = ((LispCons) form).toList();
				String op = ((LispSymbol) args.get(0)).name();
				if (LispNames.TERPRI.equals(op)) {
					newline = true;
					continue;
				}
				if (LispNames.PRINC_TO_STRING.equals(op)) {
					if (args.size() == 2 && typeOf(args.get(1), new HashMap<>(env), tc) == Ty.BOOL) {
						boolToString = true;
					}
					continue;
				}
				boolean print = LispNames.PRINT.equals(op);
				if (print) {
					newline = true;
				}
				if (args.size() != 2) {
					continue;
				}
				LispVal arg = args.get(1);
				if (arg instanceof LispTrue) {
					boolT = true;
					continue;
				}
				if (arg instanceof LispNil) {
					boolNil = true;
					continue;
				}
				Ty at;
				try {
					at = typeOf(arg, new HashMap<>(env), tc);
				}
				catch (RuntimeException e) {
					// A program that will not compile gates everything on, so the
					// helpers the error path expects are there when it gets there.
					newline = true;
					quote = true;
					boolT = true;
					boolNil = true;
					intFloat = true;
					continue;
				}
				if (at == Ty.BOOL) {
					boolT = true;
					boolNil = true;
				}
				else if (at == Ty.STRING) {
					if (print) {
						quote = true;
					}
				}
				else if (at == Ty.INT || at == Ty.FLOAT) {
					intFloat = true;
				}
				else if (at == Ty.VOID) {
					// A void form used for its value stands for nil.
					boolNil = true;
				}
			}
			if (newline && quote && boolT && boolNil && intFloat) {
				break;
			}
		}
		return new PrintUse(newline, quote, boolT, boolNil, boolToString, intFloat);
	}

	/** Every {@code print}/{@code princ}/{@code terpri}/{@code princ-to-string} form. */
	private static void collectPrintForms(LispVal v, List<LispVal> out) {
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol s
					&& (PRINT_OPS.contains(s.name()) || LispNames.PRINC_TO_STRING.equals(s.name()))) {
				out.add(c);
			}
			collectPrintForms(c.car(), out);
			collectPrintForms(c.cdr(), out);
		}
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

	/** Whether a body calls the named operator (a {@code (name ...)} form) anywhere. */
	private static boolean usesOp(LispVal v, String op) {
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol s && op.equals(s.name())) {
				return true;
			}
			return usesOp(c.car(), op) || usesOp(c.cdr(), op);
		}
		return false;
	}

	/**
	 * Whether a reachable body takes the {@code length} of a string -- what gates the
	 * {@code __strlen_cp} helper. A {@code length} over a packed float vector (or over
	 * nil) still reads the header / answers 0 inline and needs no helper, so each
	 * {@code length} argument is settled against the frozen inference result first, the
	 * way {@link #rendersFloat} settles its print arguments. An argument {@link #typeOf}
	 * cannot answer belongs to a program that will not compile; it gates the helper on,
	 * so a compilation that does get there finds it.
	 */
	private boolean usesStringLength(List<String> reachable, Map<String, Defun> defuns,
			Map<String, WasmImportCompiler.Decl> imports, Types types) {
		for (String name : reachable) {
			if (imports.containsKey(name)) {
				continue;
			}
			Defun d = Objects.requireNonNull(defuns.get(name));
			List<LispVal> lengthArgs = new ArrayList<>();
			collectLengthArgs(progn(d.body()), lengthArgs);
			if (lengthArgs.isEmpty()) {
				continue;
			}
			Map<String, Ty> env = paramEnv(d, types.params());
			env.putAll(Objects.requireNonNull(types.locals().get(name)));
			TC tc = new TC(name, new HashSet<>(d.params()), types, null, false, new boolean[1]);
			for (LispVal arg : lengthArgs) {
				try {
					Ty t = typeOf(arg, new HashMap<>(env), tc);
					if (t != Ty.F64VEC && t != Ty.F32VEC) {
						return true;
					}
				}
				catch (RuntimeException e) {
					return true;
				}
			}
		}
		return false;
	}

	/** Collects the single argument of every {@code (length arg)} form in the body. */
	private static void collectLengthArgs(LispVal v, List<LispVal> out) {
		if (v instanceof LispCons c) {
			if (c.car() instanceof LispSymbol s && LispNames.LENGTH.equals(s.name())) {
				List<LispVal> args = c.toList();
				if (args.size() == 2 && !(args.get(1) instanceof LispNil)) {
					out.add(args.get(1));
				}
			}
			else {
				collectLengthArgs(c.car(), out);
			}
			collectLengthArgs(c.cdr(), out);
		}
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

	/**
	 * The module's function types, each written once. A wasm function type is structural:
	 * two functions of the same shape are the same type, and a `func` section entry (like
	 * an import entry) names a type by INDEX, so folding the duplicates renumbers nothing
	 * else in the module. Entries keep first-use order, which is what makes the emitted
	 * section deterministic.
	 */
	private static final class TypeTable {

		private record Sig(List<Type> params, List<Type> results) {
		}

		private final LinkedHashMap<Sig, Integer> indices = new LinkedHashMap<>();

		/**
		 * The index of this signature's entry, adding it if it is new.
		 * @param params the parameter types
		 * @param results the result types
		 * @return the type index to name this signature by
		 */
		int intern(Type[] params, Type[] results) {
			return this.indices.computeIfAbsent(new Sig(List.of(params), List.of(results)),
					unused -> this.indices.size());
		}

		/**
		 * Write every interned signature, in order.
		 * @param typeSec the type section being built
		 */
		void writeTo(am.ik.wasm.TypeDef typeSec) {
			for (Sig sig : this.indices.keySet()) {
				typeSec.addFunc(sig.params().toArray(Type[]::new), sig.results().toArray(Type[]::new));
			}
		}

	}

	private byte[] assemble(List<String> emitted, List<byte[]> internalBodies,
			List<WasmExportCompiler.Decl> exportDecls, List<byte[]> wrapperBodies, int[] wrapperOrdinals,
			int[] exportOrdinals, int internalCount, Types types, Mem mem, List<WasmImportCompiler.Decl> hostImports) {
		// The local (non-imported) function count: internals, the emitted wrappers
		// (pass-through exports have none and name their internal function directly),
		// then the four memory helpers (when memory is used) plus the UTF-8 code-point
		// helpers the module's operators call for (each gated, so an indexing-free
		// module counts none), then __ftoa /
		// __write_stdout (when a float is rendered / when printing is used). The helper
		// indices came from placeFunctions over this same wrapper count, so an elided
		// wrapper moves them all down together.
		int utf8Helpers = (mem.strlenIndex() >= 0 ? 1 : 0) + (mem.byteOffsetIndex() >= 0 ? 1 : 0)
				+ (mem.charAtIndex() >= 0 ? 1 : 0);
		int localFuncCount = internalCount + wrapperBodies.size() + (mem.used() ? 4 : 0) + utf8Helpers
				+ (mem.hostArena() ? 3 : 0) + (mem.ftoaUsed() ? 6 : 0) + (mem.printUsed() ? 1 : 0);
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
		// cabi_realloc alone is also what a :string-RETURNING import's canon lower names
		// (the host lowers the result into this memory through it), so it rides that
		// too; the post-returns and retptr shims stay the exports' own.
		boolean componentRealloc = this.component && NoGcWasmComponentBuilder.needsRealloc(exportDecls, hostImports);
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
		int postBase = reallocIndex + (componentRealloc ? 1 : 0);
		int shimBase = postBase + postKinds.size();
		int totalFuncCount = localFuncCount + (componentRealloc ? 1 : 0) + postKinds.size() + stringReturnDecls.size();
		// The type table: every signature the module needs, each written ONCE. A wasm
		// function type is structural, so two functions of the same shape share one
		// entry -- a `func` section holds type INDICES, and nothing else in the module
		// names a function's type. Signatures are interned in emission order (the
		// printing module's fd_write first, so it stays type 0 for the import entry
		// below), which keeps the section deterministic and keeps the FIRST function of
		// each shape at the index it always had.
		TypeTable typeTable = new TypeTable();
		if (mem.printUsed()) {
			typeTable.intern(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
		}
		// One entry per local function, in function-section order: internal function k's
		// inferred (i64|f64|i32 ...) -> (i64|f64|i32), then each emitted wrapper's host
		// signature, then the helpers.
		int[] funcTypes = new int[totalFuncCount];
		int nextFunc = 0;
		for (String name : emitted) {
			funcTypes[nextFunc++] = typeTable.intern(wasmParamTypes(name, types),
					wasmResultTypes(returnTy(name, types)));
		}
		for (int j = 0; j < exportDecls.size(); j++) {
			if (wrapperOrdinals[j] < 0) {
				continue; // pass-through: no wrapper, no host type
			}
			WasmExportCompiler.Decl decl = exportDecls.get(j);
			funcTypes[nextFunc++] = typeTable.intern(WasmExportCompiler.paramWasmTypes(decl),
					WasmExportCompiler.resultWasmTypes(decl));
		}
		if (mem.used()) {
			// __alloc, __memcpy, __streq, __itoa.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32 }, new Type[] { Type.I32 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32, Type.I32, Type.I32 }, new Type[0]);
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I64 }, new Type[] { Type.I32 });
		}
		// The UTF-8 code-point helpers, in placeFunctions order. Each reuses an
		// already-interned shape -- (i32) -> i32 is __alloc's, (i32, i32) -> i32 is
		// __streq's -- so indexing operators cost code bytes but no type entries.
		if (mem.strlenIndex() >= 0) {
			// __strlen_cp (i32) -> i32.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32 }, new Type[] { Type.I32 });
		}
		if (mem.byteOffsetIndex() >= 0) {
			// __byte_offset (i32, i32) -> i32.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 });
		}
		if (mem.charAtIndex() >= 0) {
			// __char_at (i32, i32) -> i32.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32, Type.I32 }, new Type[] { Type.I32 });
		}
		if (mem.hostArena()) {
			// The host arena API: __ronto_alloc_mark () -> i32 and
			// __ronto_alloc_reset (i32) -> (), plus the exported __ronto_alloc
			// (i32) -> i32 host allocator (a one-call wrapper over __alloc, so its
			// shape is __alloc's and no type entry is added for it).
			funcTypes[nextFunc++] = typeTable.intern(new Type[0], new Type[] { Type.I32 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32 }, new Type[0]);
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32 }, new Type[] { Type.I32 });
		}
		if (mem.ftoaUsed()) {
			// __ftoa (f64) -> i32 (a fresh [len][bytes] string pointer), then its five
			// Schubfach helpers in Mem.schub*Index order: __schub_umulhi (i64, i64) ->
			// i64, __schub_g (i32) -> (i64, i64), __schub_rop (i64, i64, i64) -> i64,
			// __schub_f64_dec (f64) -> (i64, i32), __schub_dec_fmt (i64, i32, i32, i32)
			// -> i32.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.F64 }, new Type[] { Type.I32 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I64, Type.I64 }, new Type[] { Type.I64 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32 }, new Type[] { Type.I64, Type.I64 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I64, Type.I64, Type.I64 },
					new Type[] { Type.I64 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.F64 }, new Type[] { Type.I64, Type.I32 });
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I64, Type.I32, Type.I32, Type.I32 },
					new Type[] { Type.I32 });
		}
		if (mem.printUsed()) {
			// __write_stdout (ptr i32, len i32) -> (): the sole fd_write caller.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32, Type.I32 }, new Type[0]);
		}
		if (componentRealloc) {
			// cabi_realloc (old, old-size, align, new-size) -> i32.
			funcTypes[nextFunc++] = typeTable.intern(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 },
					new Type[] { Type.I32 });
		}
		if (componentStringAbi) {
			// cabi_post_* (flat results) -> (), one per signature.
			for (Type paramType : postKinds.values()) {
				funcTypes[nextFunc++] = typeTable.intern(paramType == null ? new Type[0] : new Type[] { paramType },
						new Type[0]);
			}
			// Retptr shims: the wrapper's host parameters, a single i32 result.
			for (int j : stringReturnDecls) {
				funcTypes[nextFunc++] = typeTable.intern(WasmExportCompiler.paramWasmTypes(exportDecls.get(j)),
						new Type[] { Type.I32 });
			}
		}
		// The host-ABI signature of each reached rontolisp:wasm-import, LAST -- an import
		// entry names a type index but no function index, so interning them here
		// renumbers nothing. Under --component a :string result takes the canonical
		// retptr shape (NoGcWasmComponentBuilder.coreParamTypes).
		int[] importTypes = new int[hostImports.size()];
		for (int i = 0; i < hostImports.size(); i++) {
			WasmImportCompiler.Decl decl = hostImports.get(i);
			importTypes[i] = typeTable.intern(importParamTypes(decl), importResultTypes(decl));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write("\0asm").writeLittleEndian4(1).writeTypeSection(typeTable::writeTo);
		// Import section: exactly the one fd_write, and only when the program prints.
		// A print-free module keeps zero imports, so its --component wrap needs no
		// adapter. Under --no-wasi the import is replaced by an internal discarding
		// sink DEFINED at the same function index 0 (below), so the module keeps zero
		// imports while every planned index (funcBase stays 1) holds.
		final boolean fdWriteSink = mem.printUsed() && this.noWasi;
		if (mem.printUsed() && !this.noWasi) {
			// Type index 0: the fd_write signature was added first above.
			w.writeImportSection(
					imports -> imports.addImport("wasi_snapshot_preview1", "fd_write", ExternalKind.FUNCTION, 0));
		}
		// Function section: local function k names the table entry its signature was
		// interned at. The --no-wasi sink occupies index 0 with the fd_write type.
		w.writeFunction(func -> {
			if (fdWriteSink) {
				func.addFunction(0);
			}
			for (int k = 0; k < totalFuncCount; k++) {
				func.addFunction(funcTypes[k]);
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
			// memory so a host can read a :string result; when the boundary asks the
			// host to manage memory itself, the allocator and the arena API with it.
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
					// The memory itself is exported for every memory-using module: a
					// :string crossing in EITHER direction is read through it, and an
					// import's :string argument points into it.
					exports.addExport("memory", ExternalKind.MEMORY, 0);
				}
				if (mem.hostArena()) {
					// The allocator the host reserves its input buffers with -- NOT the
					// internal __alloc: it holds four bytes back for the [len] header
					// the export wrapper writes at ptr - 4 -- and the arena API over
					// it: snapshot the bump-heap
					// top before the host allocates its own input buffer, then restore it
					// after the call so a resident instance stays flat regardless of how
					// many times it is called. Emitted only where the boundary gives the
					// host something to allocate or something to reclaim -- otherwise the
					// wrappers' own auto-reset already keeps the heap flat and this is an
					// API nothing can use (mem.hostArena()).
					exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, mem.rontoAllocIndex());
					exports.addExport("__ronto_alloc_mark", ExternalKind.FUNCTION, mem.markIndex());
					exports.addExport("__ronto_alloc_reset", ExternalKind.FUNCTION, mem.resetIndex());
				}
				if (componentRealloc) {
					// The canonical string ABI helper the component wrap aliases for the
					// host to lower strings into this memory through.
					exports.addExport(NoGcWasmComponentBuilder.CABI_REALLOC, ExternalKind.FUNCTION, reallocIndex);
				}
				if (componentStringAbi) {
					// The post-returns the string-involving exports' lifts name.
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
				if (fdWriteSink) {
					// The --no-wasi fd_write sink at index 0: *nwritten = iovs[0].len,
					// errno 0 -- output is discarded, nothing traps (the GC backend's
					// contract, .kb/wasm-export-no-wasi.md). __write_stdout's call
					// target is unchanged.
					code.addFunction(WasmIoRuntimeBuilder.buildNoWasiFdWriteSinkBody());
				}
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
				}
				if (mem.strlenIndex() >= 0) {
					code.addFunction(strlenCpBody());
				}
				if (mem.byteOffsetIndex() >= 0) {
					code.addFunction(byteOffsetBody());
				}
				if (mem.charAtIndex() >= 0) {
					code.addFunction(charAtBody(mem.byteOffsetIndex()));
				}
				if (mem.hostArena()) {
					code.addFunction(markBody());
					code.addFunction(resetBody());
					code.addFunction(rontoAllocBody(mem.allocIndex()));
				}
				if (mem.ftoaUsed()) {
					code.addFunction(ftoaBody(mem));
					code.addFunction(WasmSchubfachRuntimeBuilder.buildUmulhiBody());
					code.addFunction(WasmSchubfachRuntimeBuilder.buildGBody(mem.schubUmulhiIndex(), mem.schubBase()));
					code.addFunction(WasmSchubfachRuntimeBuilder.buildRopBody(mem.schubUmulhiIndex()));
					code.addFunction(
							WasmSchubfachRuntimeBuilder.buildF64DecBody(mem.schubGIndex(), mem.schubRopIndex()));
					code.addFunction(WasmSchubfachRuntimeBuilder.buildDecFmtBody());
				}
				if (mem.printUsed()) {
					code.addFunction(writeStdoutBody(mem));
				}
				if (componentRealloc) {
					code.addFunction(cabiReallocBody(mem.allocIndex()));
				}
				if (componentStringAbi) {
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
		byte[] module = out.toByteArray();
		if (hostImports.isEmpty()) {
			return module;
		}
		// Resolve the placeholder call indices the import wrappers emitted: the entries
		// are prepended to the import section (ahead of a printing program's fd_write,
		// which shifts along with every other function reference) and the whole module is
		// renumbered in one sweep. The pre-injection module calls 2^27 and is NOT valid,
		// so nothing may validate or emit it; the injector runs BEFORE the tree shaker,
		// which renumbers what survives.
		List<am.ik.wasm.WasmImportInjector.HostImport> entries = new ArrayList<>();
		for (int i = 0; i < hostImports.size(); i++) {
			WasmImportCompiler.Decl decl = hostImports.get(i);
			entries.add(new am.ik.wasm.WasmImportInjector.HostImport(decl.module(), decl.field(), importTypes[i]));
		}
		return am.ik.wasm.WasmImportInjector.inject(module, entries, WasmImportCompiler.PLACEHOLDER_FUNC_BASE);
	}

	// __alloc(size i32) -> i32: a bump allocator over the heap-pointer global (index 0).
	// It
	// 4-byte-aligns the bump, grows linear memory by whole pages when the new top exceeds
	// the current size, and returns the old pointer. Locals: 1=old, 2=end, 3=need(pages).
	private static byte[] allocBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		// old = heap
		w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
		// end = (old + size + 3) & -4
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3).write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(-4).write(Instruction.I32_AND);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		// heap = end
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2).write(Instruction.SET_GLOBAL, 0x00);
		// need = (end + 65535) >> 16
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xffff).write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(16).write(Instruction.I32_SHR_U);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		// if need > memory.size: grow(need - memory.size); drop
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.CURRENT_MEMORY, 0x00);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GROW_MEMORY, 0x00);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
		// return old
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2).write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);
		// mem[dst] = mem[src] (one byte)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1).write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// dst++, src++, n--
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_SUB).write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// la = len(a); if la != len(b) return 0
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0).write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1).write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// byte loop
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// if a[4+i] != b[4+i] return 0
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING));
	}

	// __strlen_cp(s i32) -> i32: the CHARACTER count of the [len][bytes] string -- the
	// number of UTF-8 lead bytes, i.e. bytes b with (b & 0xC0) != 0x80. The header
	// stays the BYTE count (allocation, copies, printing and the host ABI all move
	// bytes); only length derives characters from it. Params 0=s; locals 1=len (byte
	// count), 2=i, 3=n, 4=b.
	private static byte[] strlenCpBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// b = s[4+i]; n += ((b & 0xC0) != 0x80)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xC0);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0x80);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING, Ty.STRING, Ty.STRING));
	}

	// __byte_offset(s i32, target i32) -> i32: the BYTE offset of the target-th
	// character in the [len][bytes] string -- what subseq's character indices and
	// __char_at's index convert through. Counts target lead bytes, then skips the
	// rest of the character the count stopped inside of, so the answer always lands
	// on a character boundary (a too-large index answers the byte length; a negative
	// one answers 0). Params 0=s, 1=target; locals 2=len, 3=pos, 4=cp, 5=b.
	private static byte[] byteOffsetBody() {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// b = s[4+pos]; pos++; cp += ((b & 0xC0) != 0x80)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(5);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(5);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xC0);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0x80);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// The count stops one byte past the target lead, mid-character: skip to the
		// next lead (or the end) so the answer is a boundary.
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xC0);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0x80);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING, Ty.STRING, Ty.STRING));
	}

	// __char_at(s i32, idx i32) -> i32: the CODE POINT of the idx-th character --
	// the UTF-8 sequence at __byte_offset(s, idx), decoded by dispatching on the lead
	// byte's high bits (< 0x80 / < 0xE0 / < 0xF0 / else, the 1- to 4-byte ladder).
	// Decoding is total over well-formed strings: every in-bounds character index
	// lands on a lead byte and every continuation byte it names is in bounds, so
	// nothing here traps that the old byte load did not. A truncated tail handed in
	// through a host :string reads what its bits assemble, the same leniency the
	// octets-to-string pair documents (.kb/characters-code-points.md). Params 0=s,
	// 1=idx; locals 2=pos (byte offset), 3=b0.
	private static byte[] charAtBody(int byteOffsetIndex) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		// pos = __byte_offset(s, idx)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.CALL).writeUnsignedLeb128(byteOffsetIndex);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		// b0 = s[4+pos]
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		// if (b0 < 0x80) b0 ...
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x7F);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.ELSE);
		// ... else if (b0 < 0xE0) ((b0 & 0x1F) << 6) | (b1 & 0x3F) ...
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xE0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x7F);
		emitUtf8Lead(w, 0x1F, 6);
		emitUtf8Cont(w, 1, 0);
		w.write(Instruction.ELSE);
		// ... else if (b0 < 0xF0) ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 &
		// 0x3F) ...
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0xF0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x7F);
		emitUtf8Lead(w, 0x0F, 12);
		emitUtf8Cont(w, 1, 6);
		emitUtf8Cont(w, 2, 0);
		w.write(Instruction.ELSE);
		// ... else ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) |
		// (b3 & 0x3F).
		emitUtf8Lead(w, 0x07, 18);
		emitUtf8Cont(w, 1, 12);
		emitUtf8Cont(w, 2, 6);
		emitUtf8Cont(w, 3, 0);
		w.write(Instruction.END); // 4-byte else
		w.write(Instruction.END); // 3-byte else
		w.write(Instruction.END); // 2-byte else
		w.write(Instruction.END); // function
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING));
	}

	/**
	 * Pushes {@code ((b0 & mask) << shift)} -- the lead-byte term of a multi-byte UTF-8
	 * decode in {@link #charAtBody}, where local 3 holds the lead byte.
	 */
	private static void emitUtf8Lead(WasmWriter w, int mask, int shift) {
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mask);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_CONST).writeSignedLeb128(shift);
		w.write(Instruction.I32_SHL);
	}

	/**
	 * ORs {@code ((s[pos+k] & 0x3F) << shift)} into the running decode in
	 * {@link #charAtBody}, where local 0 is the string pointer and local 2 the byte
	 * offset. The static load offset folds the [len] header (4) and the term index (k)
	 * into one immediate; a zero shift (the last term) emits no shift.
	 */
	private static void emitUtf8Cont(WasmWriter w, int k, int shift) {
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04 + k);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0x3F);
		w.write(Instruction.I32_AND);
		if (shift != 0) {
			w.write(Instruction.I32_CONST).writeSignedLeb128(shift);
			w.write(Instruction.I32_SHL);
		}
		w.write(Instruction.I32_OR);
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
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
			w.write(Instruction.I64_CONST).writeSignedLeb128(0);
			w.write(Instruction.I64_LT_S);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.I64_CONST).writeSignedLeb128(0);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
			w.write(Instruction.I64_SUB);
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
			w.write(Instruction.ELSE);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
			w.write(Instruction.END);
		};
		loadMagnitude.run();
		// count the digits (a do-while, so 0 renders as "0")
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_S);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// the sign takes one more byte
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD).write(Instruction.SET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.END);
		// p = __alloc(4 + count); store the length header
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeUnsignedLeb128(allocIndex);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// idx = p + 3 + count (the last content byte)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(4);
		// write digits backwards (again a do-while)
		loadMagnitude.run();
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_REM_S);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_CONST).writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(4).write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_SUB).write(Instruction.SET_LOCAL).writeUnsignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I64_CONST).writeSignedLeb128(10);
		w.write(Instruction.I64_DIV_S);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// the '-' sign
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x04);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of());
	}

	// __ronto_alloc(size i32) -> i32: the host allocator, the entry point a host
	// reserves a :string export parameter's (or a :string import result's) bytes
	// with. It is __alloc(size + 4) + 4: the four bytes ahead of the returned
	// pointer are the [len] header the export wrapper stores at ptr - 4, turning
	// the host's block into the internal string in place -- no second allocation,
	// no copy. The pointer is 4-aligned exactly when the block is (__alloc
	// 4-aligns the bump, and old + 4 keeps it), so the header load stays on the
	// alignment the block had. Param 0 = size, no locals.
	private static byte[] rontoAllocBody(int allocIndex) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeUnsignedLeb128(allocIndex);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
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
	// module's memory. Over a bump allocator it is just the __ronto_alloc shape over
	// __alloc: __alloc(new-size + 4) + 4, so the export wrapper can store the [len]
	// header at ptr - 4 exactly as for a __ronto_alloc'd buffer. Old is never
	// a live block to preserve (the host lowers each string with old = 0 and an exact
	// size), and the returned pointer keeps __alloc's 4-byte alignment, which
	// satisfies the string encoding's align 1.
	private static byte[] cabiReallocBody(int allocIndex) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(3);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeUnsignedLeb128(allocIndex);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
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
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(s);
		}
		w.write(Instruction.CALL).writeUnsignedLeb128(wrapperIndex);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(ptr);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.CALL).writeUnsignedLeb128(allocIndex);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(ret);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(ret);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(ptr);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(ret);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.I32_STORE, 0x02, 0x04);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(ret);
		w.write(Instruction.END);
		return withLocals(b.toByteArray(), List.of(Ty.STRING, Ty.STRING, Ty.STRING));
	}

	// __ftoa(v f64) -> i32: render the float as a fresh [len][bytes] string carrying
	// the FloatText spelling -- the Schubfach shortest decimal (__schub_f64_dec) laid
	// out by __schub_dec_fmt -- so print/princ-to-string output matches the other
	// three backends byte for byte. The IEEE specials return their static
	// literal header directly and the sign comes from the sign BIT so -0.0 prints
	// "-0.0". Param 0=v; locals 1=p (i32 result), 2=c (i32 write cursor),
	// 3=len (i32), 4=digits (i64), 5=k (i32).
	private static byte[] ftoaBody(Mem mem) {
		final int P = 1, C = 2, LEN = 3, DIGITS = 4, K = 5;
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		Runnable getV = () -> w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
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
		// p = __alloc(48): 4 header + at most 25 text bytes ('-' + 24) + digit scratch
		// at p + 28 (17 bytes); c = p + 4.
		w.write(Instruction.I32_CONST).writeSignedLeb128(48);
		w.write(Instruction.CALL).writeUnsignedLeb128(mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(P);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(P);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(C);
		// Negative by the sign BIT (so -0.0 keeps its '-'): write it, then negate.
		getV.run();
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.I64_CONST).writeSignedLeb128(0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(C);
		w.write(Instruction.I32_CONST).writeSignedLeb128('-');
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(C);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(C);
		getV.run();
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.END);
		// Nonzero: (digits, k) = __schub_f64_dec(v); zero keeps the locals (0, 0),
		// which the formatter renders as "0.0".
		getV.run();
		w.write(Instruction.F64_CONST).writeF64(0.0);
		w.write(Instruction.F64_NE);
		w.write(Instruction.IF, 0x40);
		getV.run();
		w.write(Instruction.CALL).writeUnsignedLeb128(mem.schubF64DecIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(K);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(DIGITS);
		w.write(Instruction.END);
		// len = __schub_dec_fmt(digits, k, p + 28, c)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(DIGITS);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(K);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(P);
		w.write(Instruction.I32_CONST).writeSignedLeb128(28);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(C);
		w.write(Instruction.CALL).writeUnsignedLeb128(mem.schubDecFmtIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(LEN);
		// Store the length header: p.len = (c + len) - p - 4; return p.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(P);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(C);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(LEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(P);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(P);
		w.write(Instruction.END);
		return withLocalsRaw(b.toByteArray(),
				List.of(Type.I32.code(), Type.I32.code(), Type.I32.code(), Type.I64.code(), Type.I32.code()));
	}

	// __write_stdout(ptr i32, len i32): the ONE funnel through which every printing op
	// writes -- the sole caller of the imported fd_write (also the --component seam: a
	// component variant swaps this implementation, never the print ops). Fills the iov
	// scratch (iovAddr = ptr/len, iovAddr+8 = nwritten) and writes to fd 1 (stdout).
	private static byte[] writeStdoutBody(Mem mem) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr());
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr() + 4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(1, iov, 1, nwritten); drop errno
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr());
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_CONST).writeSignedLeb128(mem.iovAddr() + 8);
		w.write(Instruction.CALL).writeUnsignedLeb128(mem.fdWriteIndex());
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
			case VOID -> throw new IllegalStateException("--no-gc: VOID has no value type (it is not a value)");
			case BOOL, INT -> Type.I64;
			case FLOAT -> Type.F64;
			case STRING, F64VEC, F32VEC, F64MAT, F32MAT -> Type.I32;
		};
	}

	// The wasm result list of a function returning this type: empty for VOID, which is
	// what makes a void function's signature `(...) -> ()` and lets a :void export name
	// it directly.
	private static Type[] wasmResultTypes(Ty ty) {
		return ty == Ty.VOID ? new Type[0] : new Type[] { wasmType(ty) };
	}

	private static Ty returnTy(String name, Types types) {
		Ty t = types.returns().get(name);
		return t == null ? Ty.BOOL : t;
	}

	// --- Function bodies ---------------------------------------------------------------

	private byte[] compileDefunBody(Defun defun, String name, Types types, Map<String, Integer> index, Mem mem) {
		Body bodyStream = new Body();
		Ty[] paramTypes = Objects.requireNonNull(types.params().get(name));
		Fn fn = new Fn(bodyStream, types, index, name, new HashSet<>(defun.params()), mem);
		WasmWriter w = fn.writer;
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
	 * over an inferred i64, {@code :float} over an inferred f64, {@code :void} over a
	 * body that answers nothing) and nothing in the module can bump the heap during the
	 * call, so there is no allocation for a wrapper to reset. (A {@code :string} boundary
	 * still marshals -- the host's block becomes the string in place, but the header
	 * store is a conversion all the same -- so it fails this.) Such an export names the
	 * internal function directly instead of an identity wrapper that would only forward
	 * its arguments.
	 * @param decl the parsed export directive
	 * @param types the inferred internal types
	 * @param allocates whether anything in the module can bump the heap during a call
	 * @return true when the export can name the internal function directly
	 */
	private static boolean isPassThroughExport(WasmExportCompiler.Decl decl, Types types, boolean allocates) {
		if (allocates) {
			return false;
		}
		Ty[] internalParams = Objects.requireNonNull(types.params().get(decl.name()));
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			BoundaryType hostType = decl.paramTypes().get(p);
			Ty internal = internalParams[p];
			boolean identity = (hostType == BoundaryType.S64 && (internal == Ty.INT || internal == Ty.BOOL))
					|| (hostType == BoundaryType.FLOAT && internal == Ty.FLOAT);
			if (!identity) {
				return false;
			}
		}
		Ty ret = returnTy(decl.name(), types);
		return (decl.returnType() == BoundaryType.S64 && (ret == Ty.INT || ret == Ty.BOOL))
				|| (decl.returnType() == BoundaryType.FLOAT && ret == Ty.FLOAT)
				|| (decl.returnType() == BoundaryType.VOID && ret == Ty.VOID);
	}

	/**
	 * The body of the synthetic internal function a {@code rontolisp:wasm-import} becomes
	 * -- the mirror image of {@link #compileWrapperBody}, which is what an EXPORT gets.
	 * It has the ordinary internal calling convention (one unboxed parameter per declared
	 * type, one result), marshals each argument out to the host ABI, calls the imported
	 * function through its placeholder index, and marshals the result back.
	 *
	 * <p>
	 * The marshalling is nearly empty, which is the whole point of this backend's value
	 * model: {@code :s64}/{@code :float} are the internal representation and cross
	 * untouched, a narrower integer is a {@code i32.wrap_i64} behind the boundary's range
	 * guard, {@code :bool} is one comparison, and a {@code :string} argument is the
	 * {@code (ptr+4, [ptr])} pair of a block the module already holds -- no encode, no
	 * copy. Only a {@code :string} RESULT copies, because the bytes the host wrote need
	 * an internal {@code [len][bytes]} header in front of them.
	 *
	 * <p>
	 * The boundary's rule is the export wrapper's, with the directions swapped: an
	 * ARGUMENT leaves the house {@code i64} (so a narrow declared type is range-guarded,
	 * exactly as an export's RESULT is), and a RESULT arrives into it (so only
	 * {@code :u64}, the one type the signed house integer cannot state in full, is
	 * guarded).
	 * @param decl the parsed import declaration
	 * @param ordinal the import's ordinal among the reached imports
	 * @param mem the memory plan (the allocator and copy helpers a {@code :string} result
	 * calls)
	 * @return the code entry bytes
	 */
	private byte[] compileImportWrapperBody(WasmImportCompiler.Decl decl, int ordinal, Mem mem) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(bodyStream);
		List<Ty> locals = new ArrayList<>();
		int nextLocal = decl.paramTypes().size();
		// Under --component a :string result comes back through a return pointer the
		// caller supplies as a trailing argument: an 8-byte (ptr,len) record off the bump
		// heap, allocated before the arguments are marshalled (they are locals, so
		// nothing moves) and read back after the call.
		boolean retptr = this.component && NoGcWasmComponentBuilder.returnsString(decl);
		int retptrLocal = -1;
		if (retptr) {
			retptrLocal = nextLocal++;
			locals.add(Ty.STRING);
			w.write(Instruction.I32_CONST).writeSignedLeb128(8);
			w.write(Instruction.CALL).writeUnsignedLeb128(mem.allocIndex());
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(retptrLocal);
		}
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			BoundaryType hostType = decl.paramTypes().get(p);
			if (hostType == BoundaryType.STRING) {
				// The internal [len][bytes] pointer IS the region the host reads: hand
				// over (content ptr, len) without moving a byte.
				w.write(Instruction.GET_LOCAL)
					.writeUnsignedLeb128(p)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_ADD);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p).write(Instruction.I32_LOAD, 0x02, 0x00);
				continue;
			}
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
			int[] scratch = { nextLocal };
			emitImportArgUnbox(w, hostType, () -> {
				locals.add(Ty.INT);
				return scratch[0]++;
			});
			nextLocal = scratch[0];
		}
		if (retptr) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(retptrLocal);
		}
		w.write(Instruction.CALL).writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal);
		if (retptr) {
			// The host filled the record: push (ptr, len) the way the two-value host
			// ABI below expects them on the stack.
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(retptrLocal).write(Instruction.I32_LOAD, 0x02, 0x00);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(retptrLocal).write(Instruction.I32_LOAD, 0x02, 0x04);
		}
		switch (decl.returnType()) {
			// (ptr,len) the host wrote into this module's linear memory -> a fresh
			// internal [len][bytes] block.
			case STRING -> {
				int hp = nextLocal++;
				int len = nextLocal++;
				int dst = nextLocal++;
				locals.add(Ty.STRING);
				locals.add(Ty.STRING);
				locals.add(Ty.STRING);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(len);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(hp);
				w.write(Instruction.I32_CONST).writeSignedLeb128(4);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len).write(Instruction.I32_ADD);
				w.write(Instruction.CALL).writeUnsignedLeb128(mem.allocIndex());
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(dst);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len).write(Instruction.I32_STORE, 0x02, 0x00);
				w.write(Instruction.GET_LOCAL)
					.writeUnsignedLeb128(dst)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_ADD);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(hp);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len);
				w.write(Instruction.CALL).writeUnsignedLeb128(mem.memcpyIndex());
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
			}
			default -> {
				int[] scratch = { nextLocal };
				emitImportResultBox(w, decl.returnType(), () -> {
					locals.add(Ty.INT);
					return scratch[0]++;
				});
				nextLocal = scratch[0];
			}
		}
		w.write(Instruction.END);
		return withLocals(bodyStream.toByteArray(), locals);
	}

	/**
	 * Converts the internal value on the stack -- already of the parameter's inferred
	 * {@link Ty} -- into the host-ABI value the boundary designator takes. Shared by the
	 * import wrapper and the folded call site ({@link #compileFoldedImportCall}), so the
	 * two cannot marshal a designator differently; {@code :string} is NOT here, because
	 * that is exactly where they differ (the wrapper reads its parameter local twice, a
	 * folded site knows both halves as constants).
	 * @param w the writer
	 * @param hostType the boundary designator
	 * @param allocScratch allocates an i64 scratch local, for the range guard
	 */
	private static void emitImportArgUnbox(WasmWriter w, BoundaryType hostType,
			java.util.function.IntSupplier allocScratch) {
		switch (hostType) {
			// The internal f64 is the host f64 (:float pins the parameter to FLOAT).
			case FLOAT -> {
			}
			// nil (0) -> 0, anything else -> 1.
			case BOOL -> {
				i64Const(w, 0);
				w.write(Instruction.I64_NE);
			}
			default -> {
				if (needsBoundaryRangeGuard(hostType, false)) {
					emitRangeChecks(w, hostType, false, allocScratch.getAsInt());
				}
				if (hostType.bits() < 64) {
					w.write(Instruction.I32_WRAP_I64);
				}
			}
		}
	}

	/**
	 * Boxes the host's answer into the internal value the caller reads -- the other
	 * shared half of the wrapper and a folded call site. A {@code :string} result is not
	 * here: it copies through the allocator into a fresh block, which is the reason such
	 * an import is never folded.
	 * @param w the writer
	 * @param returnType the boundary designator
	 * @param allocScratch allocates an i64 scratch local, for the {@code :u64} guard
	 */
	private static void emitImportResultBox(WasmWriter w, BoundaryType returnType,
			java.util.function.IntSupplier allocScratch) {
		switch (returnType) {
			// Nothing came back, and nothing is invented: the call site's type is VOID,
			// so no caller has a value to drop and the wrapper's own signature is
			// (...) -> ().
			case VOID -> {
			}
			case FLOAT -> {
			}
			// Normalize to the 0/1 the rest of the backend reads as nil/t, so (eq r t)
			// holds for a host that answers any non-zero i32.
			case BOOL -> {
				w.write(Instruction.I32_EQZ);
				w.write(Instruction.I32_EQZ);
				w.write(Instruction.I64_EXTEND_U_I32);
			}
			// An integer the house i64 states exactly needs only the widening in its own
			// signedness; :s64 is the identity and :u64 is the one value range the
			// SIGNED house integer cannot hold, so it is the one guarded here.
			default -> {
				if (returnType.bits() < 64) {
					w.write(returnType.signed() ? Instruction.I64_EXTEND_S_I32 : Instruction.I64_EXTEND_U_I32);
				}
				else if (needsBoundaryRangeGuard(returnType, false)) {
					emitRangeChecks(w, returnType, false, allocScratch.getAsInt());
				}
			}
		}
	}

	/**
	 * Every transparent forwarder in the program: a {@code defun} whose whole body is one
	 * call handing its own parameters, in order and unchanged, to a host import -- the
	 * thin Lisp helper a host-facing module is written with -- mapped to the import at
	 * the end of the chain.
	 *
	 * <p>
	 * A call to one IS the call to the import, one frame up, which is where a literal
	 * argument is written; the forwarder itself only names the boundary. So the fold
	 * reads its arguments there, and when it takes every site the forwarder has nothing
	 * left to do and is not emitted either. A forwarder an EXPORT names is not one of
	 * these: the host can call it, so it stays, and so does the wrapper behind it.
	 * @param defuns every top-level function
	 * @param importDecls every import declaration, by Lisp name
	 * @param exportDecls the export directives
	 * @return each forwarder's name mapped to the import it forwards to
	 */
	private static Map<String, String> findForwarders(Map<String, Defun> defuns,
			Map<String, WasmImportCompiler.Decl> importDecls, List<WasmExportCompiler.Decl> exportDecls) {
		Set<String> exported = new HashSet<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			exported.add(decl.name());
		}
		Map<String, String> direct = new LinkedHashMap<>();
		for (Defun defun : defuns.values()) {
			String target = forwardTarget(defun);
			if (target != null && !exported.contains(defun.name())) {
				direct.put(defun.name(), target);
			}
		}
		// Resolve each chain to the import at its end; a chain that reaches something
		// else (or loops) is not a forwarder to an import and is dropped.
		Map<String, String> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : direct.entrySet()) {
			String target = entry.getValue();
			for (int hop = 0; hop <= direct.size() && !importDecls.containsKey(target); hop++) {
				String next = direct.get(target);
				if (next == null) {
					break;
				}
				target = next;
			}
			if (importDecls.containsKey(target)) {
				resolved.put(entry.getKey(), target);
			}
		}
		return resolved;
	}

	// The single call a forwarder's body is, or null when the body is anything else: a
	// name in call position handed this function's own parameters, in order, with nothing
	// before or after it.
	private static @Nullable String forwardTarget(Defun defun) {
		if (defun.body().size() != 1 || !(defun.body().get(0) instanceof LispCons call)
				|| !(call.car() instanceof LispSymbol head)) {
			return null;
		}
		List<LispVal> parts = call.toList();
		if (parts.size() - 1 != defun.params().size()) {
			return null;
		}
		for (int p = 0; p < defun.params().size(); p++) {
			if (!(parts.get(p + 1) instanceof LispSymbol arg) || !arg.name().equals(defun.params().get(p))) {
				return null;
			}
		}
		return head.name();
	}

	/**
	 * Decides which host imports lose their wrapper entirely: those whose every REACHED
	 * call site hands a LITERAL to every {@code :string} parameter, and whose sites' own
	 * bytes then come to less than the wrapper's (and any forwarder's that goes with it).
	 *
	 * <p>
	 * The wrapper's whole body is generic address arithmetic over its parameter locals:
	 * {@code local.get p; i32.const 4; i32.add; local.get p; i32.load} turns a
	 * {@code [len][bytes]} header pointer into the {@code (content ptr, len)} pair the
	 * host reads. For a LITERAL both halves are compile-time constants -- the header sits
	 * at a fixed address in the static data segment -- so a site that knows its argument
	 * pushes two constants and calls the host function itself. Nothing is staged and
	 * nothing is copied: the pointer handed over is the module's own permanent literal
	 * block, the same pointer the wrapper would have computed, under the same contract
	 * ({@code .kb/no-gc-scalar-wasm.md}, "Host imports").
	 *
	 * <p>
	 * The decision is per IMPORT and whole-program rather than per site, for two reasons.
	 * A site that folds does not remove the wrapper by itself -- only the LAST one does
	 * -- so a mixed import would pay the constants at some sites and keep the wrapper for
	 * the others, which is a pure loss. And this backend has no first-class functions at
	 * all ({@code #'name} and {@code funcall} are compile errors), so the reached call
	 * sites ARE every reference a wrapper can have: when they all fold, nothing can reach
	 * it and it is never emitted -- at every optimize level, without waiting for the tree
	 * shaker.
	 *
	 * <p>
	 * The arithmetic is what keeps it a win rather than a habit. Each folded site costs
	 * the length constant per {@code :string} argument plus the marshalling the wrapper
	 * used to hold once (a {@code :bool} comparison, a narrow integer's range guard, the
	 * result boxing); against that stand the wrapper's body and function-section entry
	 * and every forwarder's. Many sites of a wide import therefore do NOT fold, and the
	 * crossing point is measured here rather than guessed. Two small terms are left out,
	 * each worth about a byte and pulling in opposite directions: the wrapper's type
	 * entry (which may be another function's too, so it is not counted as saved) and a
	 * folded site's scratch local (counted at a one-byte index).
	 * @param importDecls every declaration, by Lisp name
	 * @param importOrdinals the reached imports and their ordinals
	 * @param reached every reached function, which is what makes a forwarder's body a
	 * cost the fold actually removes
	 * @param layout the memory plan, which is where a literal's address comes from
	 * @return the names whose wrapper is not emitted
	 */
	private Set<String> chooseFoldedImports(Map<String, WasmImportCompiler.Decl> importDecls,
			Map<String, Integer> importOrdinals, Set<String> reached, MemLayout layout) {
		// A wrapper considered here has no :string result and no return pointer, so its
		// body names no helper index and any placement answers the same bytes.
		Mem sizingMem = placeFunctions(layout, 0, 0);
		Set<String> folded = new LinkedHashSet<>();
		for (Map.Entry<String, Integer> entry : importOrdinals.entrySet()) {
			String name = entry.getKey();
			WasmImportCompiler.Decl decl = Objects.requireNonNull(importDecls.get(name));
			// Nothing to fold without a :string parameter (the scalars marshal the same
			// either way), and a :string RESULT copies the host's bytes into a fresh
			// block through the allocator -- a wrapper's worth of code that a call site
			// would only repeat.
			if (!decl.paramTypes().contains(BoundaryType.STRING) || decl.returnType() == BoundaryType.STRING) {
				continue;
			}
			List<ImportSite> sites = this.importCallSites.get(name);
			if (sites == null || sites.isEmpty()) {
				continue;
			}
			int cost = 0;
			for (ImportSite site : sites) {
				Integer overhead = foldedSiteOverhead(decl, site.form(), layout);
				if (overhead == null) {
					cost = Integer.MAX_VALUE;
					break;
				}
				cost += overhead;
			}
			int saved = compileImportWrapperBody(decl, entry.getValue(), sizingMem).length + 1;
			for (Map.Entry<String, String> forwarder : this.forwarders.entrySet()) {
				if (forwarder.getValue().equals(name) && reached.contains(forwarder.getKey())) {
					// Its body is the locals vector, one local.get per parameter, the
					// call and the end byte, plus its function-section entry.
					saved += 2 * decl.paramTypes().size() + 5;
				}
			}
			if (cost < saved) {
				folded.add(name);
			}
		}
		return folded;
	}

	/**
	 * The literals whose EVERY occurrence in a reached body is a region-only use -- a
	 * folded {@code :string} argument of a folded import's call site, a folded
	 * {@code princ} statement, or a runtime helper's own region write ({@code terpri}'s
	 * {@code "\n"}, a boolean print's {@code T}/{@code NIL}, a {@code print} of a
	 * string's framing quotes): those are pushed as (content address, byte length)
	 * constants and never read through a header, so they are laid out without one. A
	 * spelling used any other way as well -- read by {@code length}, printed by
	 * {@code print}, a value-position {@code princ}, handed to an unfolded import's
	 * wrapper -- keeps its header, and a folded site then points past it. Only the
	 * literals the helpers hand out as HEADER pointers stay pinned
	 * ({@link #headerPinnedLiterals}); every other runtime fragment drops its header like
	 * a folded program literal.
	 *
	 * <p>
	 * Counted, not matched: the folded sites' literals per spelling against
	 * {@link #literalOccurrences}. All three tallies come from the one walk
	 * ({@link #collectCalls}) over the same expanded forms -- a site's arguments are
	 * walked right after the site is recorded, and a statement {@code princ} is tallied
	 * where the emitter's {@code compileStatement} folds it -- so they agree occurrence
	 * for occurrence, and a macro that duplicates a literal into a value position raises
	 * only the second.
	 * @param importDecls every import declaration, by Lisp name
	 * @param layout the all-headered plan the fold was sized against
	 * @return the spellings to lay out header-free
	 */
	private Set<String> headerFreeLiterals(Map<String, WasmImportCompiler.Decl> importDecls, MemLayout layout) {
		Map<String, Integer> folded = new HashMap<>();
		for (String name : this.foldedImports) {
			List<BoundaryType> paramTypes = Objects.requireNonNull(importDecls.get(name)).paramTypes();
			for (ImportSite site : this.importCallSites.getOrDefault(name, List.of())) {
				List<LispVal> args = site.form().toList();
				for (int p = 0; p < paramTypes.size(); p++) {
					if (paramTypes.get(p) == BoundaryType.STRING && args.get(p + 1) instanceof LispString literal) {
						folded.merge(literal.value(), 1, Integer::sum);
					}
				}
			}
		}
		// A statement (princ <literal>) writes its region as two constants, so its
		// occurrences are region-only uses the same way a folded import site's are.
		// The fold is a win at every site (no wrapper to remove), so every such site
		// counts -- there is no per-import gate to pass first.
		for (Map.Entry<String, Integer> entry : this.printLiteralSites.entrySet()) {
			folded.merge(entry.getKey(), entry.getValue(), Integer::sum);
		}
		Set<String> headerFree = new LinkedHashSet<>();
		for (Map.Entry<String, Integer> entry : folded.entrySet()) {
			if (entry.getValue().equals(this.literalOccurrences.get(entry.getKey()))) {
				headerFree.add(entry.getKey());
			}
		}
		// A runtime fragment written only as a region drops its header exactly like a
		// folded program literal: its helper uses never load a length, so the header is
		// needed only when the program itself reads the same spelling as a value (a
		// non-folded occurrence), which is the same occurrence comparison. A spelling
		// the program never mentions has neither and is header-free.
		for (String s : regionOnlyLiterals(this.printUse)) {
			if (!layout.regions().containsKey(s)) {
				continue;
			}
			int occ = this.literalOccurrences.getOrDefault(s, 0);
			int fold = folded.getOrDefault(s, 0);
			if (occ == fold) {
				headerFree.add(s);
			}
		}
		headerFree.removeAll(headerPinnedLiterals(this.printUse, layout.ftoaUsed()));
		return headerFree;
	}

	/**
	 * What folding ONE call site costs in bytes, or {@code null} when the site cannot
	 * fold at all (a {@code :string} argument that is not a literal, a literal the layout
	 * has no address for, or an arity the generic path is about to report).
	 *
	 * <p>
	 * Both sides are measured by emitting them, not modelled: the only difference between
	 * the two paths is what a {@code :string} argument pushes (two constants against the
	 * header pointer) plus the marshalling that moves out of the wrapper and into the
	 * site. Every other argument compiles identically -- the declaration pins the same
	 * {@link Ty} either way -- and both paths end in one {@code call}.
	 * @param decl the import
	 * @param site the call form
	 * @param layout the memory plan
	 * @return the extra bytes this site pays, or null if it cannot fold
	 */
	private @Nullable Integer foldedSiteOverhead(WasmImportCompiler.Decl decl, LispCons site, MemLayout layout) {
		List<LispVal> args = site.toList();
		if (args.size() - 1 != decl.paramTypes().size()) {
			return null;
		}
		ByteArrayOutputStream loweredBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream wrappedBytes = new ByteArrayOutputStream();
		WasmWriter lowered = new WasmWriter(loweredBytes);
		WasmWriter wrapped = new WasmWriter(wrappedBytes);
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			BoundaryType hostType = decl.paramTypes().get(p);
			if (hostType != BoundaryType.STRING) {
				emitImportArgUnbox(lowered, hostType, () -> 0);
				continue;
			}
			if (!(args.get(p + 1) instanceof LispString literal)) {
				return null;
			}
			// Sized on the all-headered plan, where every literal has a header address.
			Integer offset = layout.literals().get(literal.value());
			if (offset == null) {
				return null;
			}
			emitLiteralRegion(lowered, offset + 4, literal.value());
			wrapped.write(Instruction.I32_CONST).writeSignedLeb128(offset);
		}
		emitImportResultBox(lowered, decl.returnType(), () -> 0);
		return loweredBytes.size() - wrappedBytes.size();
	}

	/**
	 * Compiles one call to a folded import: the {@code :string} arguments as the
	 * constants their literals are, everything else marshalled exactly as the wrapper
	 * would have, and the host function called directly through its placeholder index.
	 * @param decl the import
	 * @param ordinal the import's ordinal among the reached imports
	 * @param args the call form's parts, operator first
	 * @param fn the body being compiled
	 * @return the internal type the call leaves on the stack
	 */
	private Ty compileFoldedImportCall(WasmImportCompiler.Decl decl, int ordinal, List<LispVal> args, Fn fn) {
		List<BoundaryType> paramTypes = decl.paramTypes();
		if (args.size() - 1 != paramTypes.size()) {
			throw new UnsupportedOperationException("--no-gc: call to '" + decl.name() + "' in '" + fn.fnName
					+ "' passes " + (args.size() - 1) + " argument(s) but it takes " + paramTypes.size());
		}
		for (int p = 0; p < paramTypes.size(); p++) {
			BoundaryType hostType = paramTypes.get(p);
			LispVal arg = args.get(p + 1);
			if (hostType == BoundaryType.STRING) {
				// Guaranteed by chooseFoldedImports, which walked these same sites.
				String content = ((LispString) arg).value();
				emitLiteralRegion(fn.writer,
						Objects.requireNonNull(fn.mem.regions().get(content),
								() -> "--no-gc: import literal not laid out in '" + fn.fnName + "': " + content),
						content);
				continue;
			}
			// A literal argument evaluates to nothing observable, so pushing the regions
			// before a later argument is evaluated preserves source order -- and, unlike
			// the wasm-GC side, there is nothing to preserve it AGAINST: the regions are
			// the module's own static data, which no allocation can land on.
			compileCoerced(arg, fn, boundaryTy(hostType));
			emitImportArgUnbox(fn.writer, hostType, () -> fn.allocLocal(Ty.INT));
		}
		fn.writer.write(Instruction.CALL).writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal);
		emitImportResultBox(fn.writer, decl.returnType(), () -> fn.allocLocal(Ty.INT));
		return importReturnTy(decl);
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
		// function allocates during the call (any concatenate/subseq/... scratch)
		// is dead the moment a non-memory scalar
		// is returned -- no heap pointer escapes to the host. Snapshot the heap-pointer
		// global (index 0) at wrapper entry and restore it at exit so a long-lived,
		// repeatedly-called instance stops growing. Gated on the return type NOT being a
		// memory designator (:string/:s-expr, whose result pointer must stay live), on
		// mem.used() (a pure-numeric export has no heap global at all) and on
		// mem.allocates() -- a module that only reads its own literals (or only takes
		// :string parameters, whose blocks the host already allocated) has no call
		// site that can move the pointer, and the bracket would restore what never
		// changed.
		boolean resetHeap = mem.used() && mem.allocates() && decl.returnType() != BoundaryType.STRING
				&& decl.returnType() != BoundaryType.S_EXPR;
		int mark = -1;
		if (resetHeap) {
			mark = nextLocal++;
			wrapperLocals.add(Ty.STRING);
			w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeUnsignedLeb128(mark);
		}

		// Box each host argument into the internal value of the inferred parameter type,
		// in
		// order, then call the internal function.
		int slot = 0;
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			BoundaryType hostType = decl.paramTypes().get(p);
			Ty internal = internalParams[p];
			if (hostType == BoundaryType.STRING) {
				// (ptr,len) -> the internal [len][bytes] string, in place. The host
				// reserved the block with __ronto_alloc, which holds four bytes back
				// ahead of the pointer it returns, so the wrapper only stores the
				// length at ptr - 4 and hands ptr - 4 to the internal function: the
				// block the host filled BECOMES the string, with no second allocation
				// and no copy. No scratch locals, no heap bump, and therefore nothing
				// for the scalar auto-reset bracket above to reclaim on this path.
				//
				// Boundary contract: the pointer MUST come from __ronto_alloc (or, under
				// --component, from the canonical lowering through cabi_realloc, which
				// reserves the same four bytes). Any other pointer -- a literal's
				// address, an interior pointer, a :string result handed back -- has no
				// header room in front of it, and the store below overwrites whatever
				// four bytes sit there.
				w.write(Instruction.GET_LOCAL)
					.writeUnsignedLeb128(slot)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_SUB);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot + 1);
				w.write(Instruction.I32_STORE, 0x02, 0x00);
				// leave the internal string pointer for the call
				w.write(Instruction.GET_LOCAL)
					.writeUnsignedLeb128(slot)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_SUB);
				slot += 2;
			}
			else {
				// A u64 above 2^63 has no exact place in the house i64: refuse it here
				// rather than let it arrive as a negative Lisp integer.
				if (hostType == BoundaryType.U64) {
					w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
					i64Const(w, 0);
					w.write(Instruction.I64_LT_S);
					w.write(Instruction.IF, 0x40);
					w.write(Instruction.UNREACHABLE);
					w.write(Instruction.END);
				}
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
				switch (hostType) {
					// host f64 -> internal (always FLOAT, since :float pins the param)
					case FLOAT -> {
						if (internal == Ty.INT || internal == Ty.BOOL) {
							w.write(Instruction.I64_TRUNC_S_F64);
						}
					}
					// host i64 -> internal i64 (INT/BOOL): identity, no conversion. A
					// 64-bit
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
						if (internal == Ty.INT || internal == Ty.BOOL) {
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
		w.write(Instruction.CALL).writeUnsignedLeb128(mem.funcIndex(targetIndex));
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
				if (ret == Ty.INT || ret == Ty.BOOL) {
					w.write(Instruction.F64_CONVERT_S_I64);
				}
			}
			case BOOL -> {
				// The host takes an i32 0/1. An INT needs the non-zero test; a BOOL is
				// already 0/1, so narrowing is exact; a FLOAT compares against 0.0.
				if (ret == Ty.BOOL) {
					w.write(Instruction.I32_WRAP_I64);
				}
				else if (ret == Ty.INT) {
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
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(r);
				w.write(Instruction.GET_LOCAL)
					.writeUnsignedLeb128(r)
					.write(Instruction.I32_CONST)
					.writeSignedLeb128(4)
					.write(Instruction.I32_ADD);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(r).write(Instruction.I32_LOAD, 0x02, 0x00);
			}
			// The host takes nothing. A body that answers a value still has to have it
			// dropped; one that is itself VOID left nothing behind, which is what lets
			// such an export reach isPassThroughExport at all.
			case VOID -> coerce(w, ret, Ty.VOID);
			case S_EXPR -> throw new UnsupportedOperationException("--no-gc does not support the export return type "
					+ decl.returnType().designator() + " (it needs a cons/reader/printer runtime)");
		}
		// Restore the heap pointer for scalar returns. local.get pushes mark above the
		// host result already on the stack, global.set pops it -- the result stays on top
		// (for :void the stack is empty and this is still valid).
		if (resetHeap) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
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
		if (!needsBoundaryRangeGuard(type, fromFloat)) {
			return 0;
		}
		wrapperLocals.add(Ty.INT);
		emitRangeChecks(w, type, fromFloat, slot);
		return 1;
	}

	/**
	 * Whether {@link #emitRangeChecks} has anything to emit for this crossing -- the
	 * scratch local's allocation is the caller's (a wrapper appends to its own list, a
	 * folded call site asks the body for one), so the question has to be answerable
	 * before the emission.
	 * @param type the boundary designator
	 * @param fromFloat whether the value arrives out of a float truncation
	 * @return whether the value needs a range check
	 */
	private static boolean needsBoundaryRangeGuard(BoundaryType type, boolean fromFloat) {
		// A u64 result is only at risk coming out of the signed house i64: a negative
		// Lisp integer is not a u64. Out of i64.trunc_u_f64 the whole 0..2^64-1 range is
		// already exact, so nothing is left to check.
		return type.bits() < 64 || (!type.signed() && !fromFloat);
	}

	// The checks themselves, over a scratch local the caller has already allocated.
	//
	// For :s8, :s16, :s32 and :u32 this is the canon-compare shape the wasm-GC lowering
	// (WasmExportCompiler.emitNarrowIntResult) already uses: in range exactly when
	// narrowing to the declared width and widening back is the identity, so
	// `v != canon(v)` traps with one compare regardless of width, and the bound itself
	// never needs a constant. :u8 and :u16 keep their single bound compare -- the bound
	// there is a two- or three-byte constant, cheaper than the mask the canon form would
	// need -- and :u64 keeps the plain sign check (only the sign can be wrong). Measured
	// 2026-09-14 against c972efa5d, .todo/811.
	private static void emitRangeChecks(WasmWriter w, BoundaryType type, boolean fromFloat, int slot) {
		boolean narrowSigned = type.signed() && type.bits() < 64;
		boolean narrowUnsigned = !type.signed() && type.bits() < 64;
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(slot);
		if (narrowSigned || type == BoundaryType.U32) {
			emitCanonCompareTrap(w, type, slot);
		}
		else if (narrowUnsigned) {
			// One unsigned comparison covers both ends: a negative i64 read as an
			// unsigned
			// 64-bit value is larger than any sub-64-bit unsigned maximum.
			BoundaryType.Range range = Objects.requireNonNull(type.range());
			emitTrapIf(w, slot, Instruction.I64_GT_U, range.max().longValueExact());
		}
		else {
			emitTrapIf(w, slot, Instruction.I64_LT_S, 0);
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
	}

	// `if (local[slot] != canon(local[slot])) unreachable` -- traps unless narrowing to
	// the declared width and widening back is the identity. One compare regardless of
	// width, and no bound constant.
	private static void emitCanonCompareTrap(WasmWriter w, BoundaryType type, int slot) {
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
		switch (type) {
			case S8 -> w.write(Instruction.I64_EXTEND8_S);
			case S16 -> w.write(Instruction.I64_EXTEND16_S);
			case S32 -> w.write(Instruction.I32_WRAP_I64, Instruction.I64_EXTEND_S_I32);
			case U32 -> w.write(Instruction.I32_WRAP_I64, Instruction.I64_EXTEND_U_I32);
			default -> throw new IllegalArgumentException("not a canon-compare boundary type: " + type);
		}
		w.write(Instruction.I64_NE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// `if (local[slot] <op> bound) unreachable` -- the boundary refusing a value it
	// cannot
	// state. A trap is the shape a host already sees for a failure inside an export.
	private static void emitTrapIf(WasmWriter w, int slot, int comparison, long bound) {
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
		i64Const(w, bound);
		w.write(comparison);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// Prepends the locals declaration to a function body. The locals keep their
	// ALLOCATION order whatever the i64/f64/v128 mix, so a run can only cover a maximal
	// stretch of consecutive locals that share a type -- which is exactly what the
	// shortest legal encoding is (.kb/wasm-shortest-encoding.md): two adjacent runs of
	// the same type are one run, and each avoided run is two bytes.
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
		// [count, valtype] per maximal run of consecutive same-typed locals.
		List<int[]> runs = new ArrayList<>();
		for (int t : extraLocals) {
			if (!runs.isEmpty() && runs.getLast()[1] == t) {
				runs.getLast()[0]++;
			}
			else {
				runs.add(new int[] { 1, t });
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.writeUnsignedLeb128(runs.size());
		for (int[] run : runs) {
			w.writeUnsignedLeb128(run[0]);
			w.write(run[1]);
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
				return Ty.BOOL;
			}
			case LispNil ignored -> {
				i64Const(fn.writer, 0);
				return Ty.BOOL;
			}
			case LispSymbol sym -> {
				Integer slot = fn.locals.get(sym.name());
				if (slot != null) {
					fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(slot);
					return Objects.requireNonNull(fn.localTypes.get(sym.name()));
				}
				// A standard scalar constant in code position answers its literal (the
				// reader no longer substitutes the value, and scalar mode has no
				// globals to read it from; see .kb/read-time-constants.md). A lexical
				// binding still wins, checked above.
				LispVal constant = scalarConstant(sym.name());
				if (constant instanceof LispDouble d) {
					fn.writer.write(Instruction.F64_CONST).writeF64(d.value());
					return Ty.FLOAT;
				}
				if (constant instanceof LispInteger i) {
					i64Const(fn.writer, i.value());
					return Ty.INT;
				}
				throw new UnsupportedOperationException("--no-gc: '" + sym.name() + "' in function '" + fn.fnName
						+ "' is not a parameter or let binding (scalar mode has no globals or heap values)");
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
		// A void form used for its value stands for nil, which is the zero of whatever
		// representation the consumer reads -- so the materialization happens HERE, at
		// the one join where a void meets a value, and nowhere along the way.
		if (from == Ty.VOID) {
			pushNil(w, to);
			return;
		}
		// A value used for its effect is dropped. This is the only place a DROP is
		// decided, which is why a void statement costs nothing.
		if (to == Ty.VOID) {
			w.write(Instruction.DROP);
			return;
		}
		// STRING, F64VEC and F32VEC are reference kinds; the only valid non-identity
		// coercions are between the numeric kinds (BOOL <-> INT <-> FLOAT). BOOL and
		// INT share the i64 representation, so they coerce to each other for free. A
		// reference kind can only coerce to itself (that identity case already returned
		// above), so any reference kind reaching here -- including a f64-vector /
		// f32-vector mismatch -- is a genuine type error.
		if (isRefKind(from) || isRefKind(to)) {
			throw new UnsupportedOperationException("--no-gc: incompatible types " + from + " and " + to
					+ " (a value cannot be more than one of number / string / float-vector)");
		}
		if ((from == Ty.BOOL && to == Ty.INT) || (from == Ty.INT && to == Ty.BOOL)) {
			return;
		}
		if (from == Ty.BOOL) {
			from = Ty.INT;
		}
		if (to == Ty.BOOL) {
			to = Ty.INT;
		}
		if (from == Ty.INT) {
			w.write(Instruction.F64_CONVERT_S_I64); // i64 -> f64
		}
		else {
			w.write(Instruction.I64_TRUNC_S_F64); // f64 -> i64 (truncate toward zero)
		}
	}

	// Pushes the nil of a representation: the i64/f64 zero, or the address-0 header that
	// is this backend's empty string (and the null pointer a reference kind reads as
	// nil).
	private static void pushNil(WasmWriter w, Ty to) {
		switch (to) {
			case BOOL, INT -> i64Const(w, 0);
			case FLOAT -> w.write(Instruction.F64_CONST).writeF64(0.0);
			default -> w.write(Instruction.I32_CONST).writeSignedLeb128(0);
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

		// A program that defines its own function on a cl name loses every call site
		// the operator dispatch below claims. Armed here and disarmed in the default
		// arm (compileUserCall, which DOES resolve the defun), like both wasm-GC
		// dispatchers -- see compiler/ClRedefinitionWarnings.
		boolean redefinedClFunction = ClRedefinitionWarnings.redefinesClFunction(name, this.definedNames);
		Ty result = switch (name) {
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
			default -> {
				redefinedClFunction = false;
				yield compileUserCall(name, args, fn);
			}
		};
		if (redefinedClFunction && this.warnedClRedefinitions.add(name)) {
			CompileWarnings.warn(SourceProvenance.prefix(cons) + ClRedefinitionWarnings.message(name));
		}
		return result;
	}

	// The cl function names this compile has already warned about, so an override that
	// happens at fifty call sites reports once, and every top-level defun name (the
	// warning's other half -- see compileCall).
	private final java.util.Set<String> warnedClRedefinitions = new java.util.HashSet<>();

	private Set<String> definedNames = Set.of();

	/**
	 * The {@code rontolisp:wasm-import} declarations of the compile in flight, by Lisp
	 * name -- what makes a call to a host function an eligible callee in
	 * {@link #collectCallsCons}, exactly like a top-level defun.
	 */
	private Map<String, WasmImportCompiler.Decl> imports = Map.of();

	/**
	 * Every reached call site of every host import, keyed by the IMPORT's Lisp name --
	 * filled by {@link #collectCallsCons} during the same BFS that decides reachability,
	 * and read only by {@link #chooseFoldedImports}. A call to a transparent forwarder is
	 * recorded here as a call to the import it forwards to, which is the whole reason
	 * {@link #forwarders} exists.
	 */
	/**
	 * Which print-support fragments the module reads, settled in {@link #planMemory} from
	 * the frozen inference result and read back by {@link #headerFreeLiterals} to tell a
	 * region-only runtime literal from a header-pinned one.
	 */
	private PrintUse printUse = new PrintUse(false, false, false, false, false, false);

	private Map<String, List<ImportSite>> importCallSites = new LinkedHashMap<>();

	/**
	 * How many times each string literal occurs in the reached bodies, counted by
	 * {@link #collectCalls} over the same macro-expanded forms that fill
	 * {@link #importCallSites} -- the other half of {@link #headerFreeLiterals}'s
	 * comparison.
	 */
	private Map<String, Integer> literalOccurrences = new HashMap<>();

	/**
	 * How many times each string literal is the argument of a {@code princ} in STATEMENT
	 * position in the reached bodies -- the print fold's half of
	 * {@link #headerFreeLiterals}'s comparison. Counted by {@link #collectCalls} over the
	 * same expanded forms as {@link #literalOccurrences}, with the statement-position
	 * flag carried down the walk the way the emitter's {@code compileStatement} decides
	 * it, so the two agree occurrence for occurrence.
	 */
	private Map<String, Integer> printLiteralSites = new HashMap<>();

	/**
	 * A reached call to a host import: the name actually in call position -- the import
	 * itself, or a transparent forwarder to it -- and the form.
	 */
	private record ImportSite(String callee, LispCons form) {
	}

	/**
	 * Every transparent forwarder, by Lisp name, to the host import it forwards to: a
	 * {@code defun} whose whole body is one call passing its own parameters, in order, to
	 * an import (possibly through another such forwarder), and which no export names. A
	 * call to one IS a call to the import, so it is where the fold looks for its
	 * arguments -- see {@link #findForwarders}.
	 */
	private Map<String, String> forwarders = Map.of();

	/**
	 * The host imports whose every reached call site hands nothing but literals to the
	 * {@code :string} parameters, and for which the sites' own bytes come to less than
	 * the wrapper's. Those sites call the host function DIRECTLY, and neither the wrapper
	 * nor any forwarder to it is emitted -- see {@link #chooseFoldedImports}.
	 */
	private Set<String> foldedImports = Set.of();

	/**
	 * Every Lisp name a folded call site may stand under -- a folded import and every
	 * transparent forwarder to it -- mapped to the import's declaration and its ordinal.
	 * {@link #compileUserCall} reads this and nothing else to decide.
	 */
	private Map<String, WasmImportCompiler.Decl> foldTargets = Map.of();

	private Map<String, Integer> foldOrdinals = Map.of();

	private Ty compileUserCall(String name, List<LispVal> args, Fn fn) {
		WasmImportCompiler.Decl folded = this.foldTargets.get(name);
		if (folded != null) {
			return compileFoldedImportCall(folded, Objects.requireNonNull(this.foldOrdinals.get(name)), args, fn);
		}
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
		fn.writer.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.funcIndex(funcIndex));
		return returnTy(name, fn.types);
	}

	private Ty compileIf(List<LispVal> args, Fn fn) {
		if (args.size() < 3) {
			throw new UnsupportedOperationException("--no-gc: malformed if in '" + fn.fnName + "': " + args);
		}
		LispVal test = args.get(1);
		LispVal then = args.get(2);
		// An ABSENT else is not an explicit nil: it produces nothing, which is VOID.
		LispVal els = args.size() > 3 ? args.get(3) : null;
		Ty thenTy = staticType(then, fn);
		Ty elseTy = els == null ? Ty.VOID : staticType(els, fn);
		// A constant test decides the branch here rather than at run time, and the
		// branch it does not take contributes nothing to the type either. The macro
		// expander's `cond` ends every chain in one (`(t ...)` becomes `(if t ... nil)`),
		// so this is not a hand-written rarity -- and that trailing nil is exactly what
		// would otherwise force an all-void chain to carry a zero in every arm.
		if (test instanceof LispTrue) {
			compileCoerced(then, fn, thenTy);
			return thenTy;
		}
		if (test instanceof LispNil) {
			compileElse(els, fn, elseTy);
			return elseTy;
		}
		Ty result = thenTy.join(elseTy);
		Ty testTy = compileExpr(test, fn);
		if (!takeFlag(fn)) {
			emitTruthy(testTy, fn.writer); // -> i32 (1 if non-zero)
		}
		fn.writer.write(Instruction.IF).write(result.blockType());
		// The branches may contain a `return`, whose br depth counts this `if`.
		fn.ctrlDepth++;
		compileCoerced(then, fn, result);
		fn.writer.write(Instruction.ELSE);
		compileElse(els, fn, result);
		fn.writer.write(Instruction.END);
		fn.ctrlDepth--;
		return result;
	}

	// The else arm of an if: the written form, or -- when there is none -- the absent
	// value itself, which costs nothing unless the join asked for one.
	private void compileElse(@Nullable LispVal els, Fn fn, Ty result) {
		if (els == null) {
			coerce(fn.writer, Ty.VOID, result);
			return;
		}
		compileCoerced(els, fn, result);
	}

	private Ty compileProgn(List<LispVal> body, Fn fn) {
		if (body.isEmpty()) {
			return Ty.VOID;
		}
		for (int i = 0; i < body.size() - 1; i++) {
			compileStatement(body.get(i), fn);
		}
		return compileExpr(body.getLast(), fn);
	}

	// Compiles a form in STATEMENT position: evaluated for its effect, its value
	// discarded. A VOID form leaves nothing on the stack and therefore costs no DROP --
	// which is the whole point of the fourth lattice point.
	//
	// A statement (princ <literal>) writes its region as two constants
	// (emitWriteLiteral, the same lowering terpri's "\n" uses) instead of computing
	// the (ptr, len) pair from the header at run time. Legal only here: princ answers
	// its argument, so a value-position site must still leave it behind. print keeps
	// the generic path -- its quotes, escapes and trailing newline are not one
	// literal. The walk tallies exactly these sites (printLiteralSites), so a
	// princ-only spelling is laid out header-free and this points past the header
	// wherever one is still needed.
	private void compileStatement(LispVal expr, Fn fn) {
		LispVal target = expr;
		if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head) {
			List<LispVal> raw = cons.toList();
			LispVal expanded = expandMacro(head.name(), cons, raw.size() - 1);
			if (expanded != null) {
				target = expanded;
			}
		}
		if (target instanceof LispCons call && call.car() instanceof LispSymbol op
				&& LispNames.PRINC.equals(op.name())) {
			List<LispVal> callArgs = call.toList();
			if (callArgs.size() == 2 && callArgs.get(1) instanceof LispString lit) {
				emitWriteLiteral(fn, lit.value());
				return;
			}
		}
		coerce(fn.writer, compileExpr(expr, fn), Ty.VOID);
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
			fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(slot);
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
		return inferred != null ? inferred : slotTy(staticType(init, fn));
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
			fn.writer.write(Instruction.TEE_LOCAL).writeUnsignedLeb128(slot);
			last = ty;
		}
		return last;
	}

	// (while test body...): a block/loop pair. The test is re-evaluated at the top; when
	// it is falsy (zero) br exits the block, otherwise the body runs (each value dropped)
	// and br jumps back. The form is VOID: the nil the other backends answer here is
	// never read as a value in practice (a while is a statement), and where it is, the
	// join materializes it -- so the loop itself pushes nothing.
	private Ty compileWhile(List<LispVal> args, Fn fn) {
		WasmWriter w = fn.writer;
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		fn.ctrlDepth += 2;
		Ty testTy = compileExpr(args.get(1), fn);
		if (takeFlag(fn)) {
			w.write(Instruction.I32_EQZ); // the predicate's own flag, negated
		}
		else {
			emitFalsy(testTy, w); // -> i32 (1 if the test is zero/false)
		}
		w.write(Instruction.BR_IF, 1);
		for (int i = 2; i < args.size(); i++) {
			compileStatement(args.get(i), fn);
		}
		w.write(Instruction.BR, 0);
		fn.ctrlDepth -= 2;
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		return Ty.VOID;
	}

	// The internal %block return boundary the loop macros wrap their expansion in: a
	// typed
	// wasm block whose result is the join of normal completion and every (return v)
	// inside
	// it. return (compileReturn) branches out carrying its value coerced to this type.
	private Ty compileBlock(LispCons cons, List<LispVal> args, Fn fn) {
		Ty result = staticType(cons, fn);
		WasmWriter w = fn.writer;
		w.write(Instruction.BLOCK, result.blockType());
		fn.ctrlDepth++;
		fn.blockMarkers.push(fn.ctrlDepth);
		fn.blockResultTypes.push(result);
		if (args.size() <= 1) {
			coerce(w, Ty.VOID, result);
		}
		else {
			for (int i = 1; i < args.size() - 1; i++) {
				compileStatement(args.get(i), fn);
			}
			compileCoerced(args.getLast(), fn, result);
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
			coerce(fn.writer, Ty.VOID, result);
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
			fn.writer.write(target == Ty.FLOAT ? floatOp : intOp);
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
			if (target == Ty.FLOAT) {
				compileCoerced(args.get(1), fn, Ty.FLOAT);
				fn.writer.write(Instruction.F64_NEG);
			}
			else {
				// 0 - x (wasm has no i64.neg)
				i64Const(fn.writer, 0);
				compileCoerced(args.get(1), fn, Ty.INT);
				fn.writer.write(Instruction.I64_SUB);
			}
			return target;
		}
		compileCoerced(args.get(1), fn, target);
		for (int i = 2; i < args.size(); i++) {
			compileCoerced(args.get(i), fn, target);
			fn.writer.write(target == Ty.FLOAT ? Instruction.F64_SUB : Instruction.I64_SUB);
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
		if (target == Ty.FLOAT && isMixedIntFloat(args, fn)) {
			// An integer beside a float folds exactly (see compileComparison): the
			// decision of every mixed round is the exact one, while the values stay
			// f64 (the join this backend returns for a mixed fold).
			return compileMixedMinMax(args, fn, min);
		}
		if (target == Ty.FLOAT) {
			// The same select the other backends fold with -- min(a,b) = (a<=b) ? a : b,
			// max(a,b) = (a>=b) ? a : b -- rather than f64.min/f64.max, which resolve a
			// +/-0.0 tie by SIGN and propagate NaN from either side. Shaped like the
			// integer fold below, on f64 locals.
			int acc = fn.allocLocal(Ty.FLOAT);
			compileCoerced(args.get(1), fn, Ty.FLOAT);
			fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(acc);
			for (int i = 2; i < args.size(); i++) {
				int t = fn.allocLocal(Ty.FLOAT);
				compileCoerced(args.get(i), fn, Ty.FLOAT);
				fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(t);
				fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(acc);
				fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
				fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(acc);
				fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
				fn.writer.write(min ? Instruction.F64_LE : Instruction.F64_GE);
				fn.writer.write(Instruction.SELECT);
				fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(acc);
			}
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(acc);
			return Ty.FLOAT;
		}
		int acc = fn.allocLocal(Ty.INT);
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(acc);
		for (int i = 2; i < args.size(); i++) {
			int t = fn.allocLocal(Ty.INT);
			compileCoerced(args.get(i), fn, Ty.INT);
			fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(t);
			// select acc if (min ? acc<t : acc>t) else t
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(acc);
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(acc);
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
			fn.writer.write(min ? Instruction.I64_LT_S : Instruction.I64_GT_S);
			fn.writer.write(Instruction.SELECT);
			fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(acc);
		}
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(acc);
		return Ty.INT;
	}

	// Whether every operand is an integer (or boolean) or a float, with at least one
	// of each: the mixed fold below owns exactly those programs (a VOID side keeps the
	// old float fold's nil-zero materialization; anything else never reaches here
	// because the join above already refused it).
	private boolean isMixedIntFloat(List<LispVal> args, Fn fn) {
		boolean seenInt = false;
		boolean seenFloat = false;
		for (int i = 1; i < args.size(); i++) {
			Ty t = staticType(args.get(i), fn);
			if (isIntLike(t)) {
				seenInt = true;
			}
			else if (t == Ty.FLOAT) {
				seenFloat = true;
			}
			else {
				return false;
			}
		}
		return seenInt && seenFloat;
	}

	// The mixed integer/float min/max fold: the same (a<=b) ? a : b select as the
	// float fold, but a round holding both representations decides exactly -- through
	// the shared i64-vs-f64 helper when the pair is mixed, natively otherwise -- so a
	// near tie past 2^53 still keeps the right operand. A tie keeps the left operand
	// on every path (the interpreter's rule; NaN is unordered and yields the second).
	// The accumulator rides in its own type until the first float arrives and only
	// then grows its f64 copy, so an all-integer prefix keeps the plain i64 select;
	// everything here is WAT emission over already-inferred types (no Lisp temporary,
	// so the inferTypes fixpoint never sees it).
	private Ty compileMixedMinMax(List<LispVal> args, Fn fn, boolean min) {
		WasmWriter w = fn.writer;
		Ty accTy = isIntLike(staticType(args.get(1), fn)) ? Ty.INT : Ty.FLOAT;
		compileCoerced(args.get(1), fn, accTy);
		int accVal = fn.allocLocal(accTy);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(accVal);
		int accF = accTy == Ty.FLOAT ? accVal : -1;
		for (int i = 2; i < args.size(); i++) {
			Ty tTy = isIntLike(staticType(args.get(i), fn)) ? Ty.INT : Ty.FLOAT;
			compileCoerced(args.get(i), fn, tTy);
			int tVal = fn.allocLocal(tTy);
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(tVal);
			if (accTy == Ty.INT && tTy == Ty.INT) {
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accVal);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tVal);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accVal);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tVal);
				w.write(min ? Instruction.I64_LE_S : Instruction.I64_GE_S);
				w.write(Instruction.SELECT);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(accVal);
				continue;
			}
			if (accF < 0) {
				accF = fn.allocLocal(Ty.FLOAT);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accVal);
				coerce(w, Ty.INT, Ty.FLOAT);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(accF);
			}
			int tF = tVal;
			if (tTy == Ty.INT) {
				tF = fn.allocLocal(Ty.FLOAT);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tVal);
				coerce(w, Ty.INT, Ty.FLOAT);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(tF);
			}
			if (accTy == tTy) {
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accF);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tF);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accF);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tF);
				w.write(min ? Instruction.F64_LE : Instruction.F64_GE);
			}
			else if (accTy == Ty.INT) {
				// The helper reads locals and leaves only the flag, so the select
				// values go on first.
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accF);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tF);
				emitExactIntFloatCompare(fn, accVal, tF, min ? Instruction.I64_LE_S : Instruction.I64_GE_S);
			}
			else {
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accF);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(tF);
				emitExactIntFloatCompare(fn, tVal, accF, min ? Instruction.I64_GE_S : Instruction.I64_LE_S);
			}
			w.write(Instruction.SELECT);
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(accF);
			accTy = Ty.FLOAT;
		}
		if (accTy == Ty.INT) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accVal);
			return Ty.INT;
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(accF);
		return Ty.FLOAT;
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
		if (target == Ty.FLOAT) {
			int a = fn.allocLocal(Ty.FLOAT);
			int b = fn.allocLocal(Ty.FLOAT);
			compileCoerced(args.get(1), fn, Ty.FLOAT);
			fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(a);
			compileCoerced(args.get(2), fn, Ty.FLOAT);
			fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(b);
			// The EXACT float remainder, emitted from the same builder the wasm-GC
			// _rat_rem/_rat_mod float arm uses, so the two backends cannot drift:
			// evaluating
			// `a - b*(floor|trunc)(a/b)` in f64 rounds above 2^53 and multiplies inf by a
			// zero quotient for an infinite divisor. This backend has no shared-runtime
			// section to hang a helper function off (every helper it emits is gated on
			// linear memory), so the reduction is inlined at the site -- ~180 bytes, and
			// only in a body that actually takes a float mod/rem.
			WasmFmodRuntimeBuilder.emitRemainder(fn.writer, mod, a, b, fn.allocLocal(Ty.FLOAT), fn.allocLocal(Ty.FLOAT),
					fn.allocLocal(Ty.FLOAT));
			return Ty.FLOAT;
		}
		int a = fn.allocLocal(Ty.INT);
		int b = fn.allocLocal(Ty.INT);
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(a);
		compileCoerced(args.get(2), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(b);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(a);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(b);
		fn.writer.write(Instruction.I64_REM_S);
		if (mod) {
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(b);
			fn.writer.write(Instruction.I64_ADD);
			fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(b);
			fn.writer.write(Instruction.I64_REM_S);
		}
		return Ty.INT;
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
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(t);
		// x < 0 ? 0 - x : x
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.I64_LT_S);
		fn.writer.write(Instruction.IF).write(Type.I64.code());
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
		fn.writer.write(Instruction.I64_SUB);
		fn.writer.write(Instruction.ELSE);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(t);
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
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(slot);
			operands.add(slot);
		}
		// total = sum of each operand's stored length.
		int total = fn.allocLocal(Ty.STRING); // i32 scratch
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(total);
		for (int s : operands) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(total);
			emitStrLen(w, s);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(total);
		}
		// dst = __alloc(4 + total); store the length header.
		int dst = fn.allocLocal(Ty.STRING); // i32 pointer
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(total);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(total);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// off = dst + 4; copy each operand's content bytes, advancing off.
		int off = fn.allocLocal(Ty.STRING); // i32 scratch
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(off);
		for (int s : operands) {
			// __memcpy(off, s + 4, len(s))
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(off);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(s);
			w.write(Instruction.I32_CONST).writeSignedLeb128(4);
			w.write(Instruction.I32_ADD);
			emitStrLen(w, s);
			w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.memcpyIndex());
			// off += len(s)
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(off);
			emitStrLen(w, s);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(off);
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		return Ty.STRING;
	}

	// (length s): the CHARACTER count for a string (the __strlen_cp helper counts
	// UTF-8 lead bytes), the element count for a packed float vector (which keeps it
	// as the leading i32 word, read directly as before).
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
		if (t == Ty.STRING) {
			if (fn.mem.strlenIndex() < 0) {
				throw new IllegalStateException(
						"--no-gc: string length without its __strlen_cp helper in '" + fn.fnName + "'");
			}
			fn.writer.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.strlenIndex());
			fn.writer.write(Instruction.I64_EXTEND_U_I32);
			return Ty.INT;
		}
		if (t != Ty.F64VEC && t != Ty.F32VEC) {
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(countLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL); // width*count
		w.write(Instruction.I32_ADD); // 4 + width*count
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(countLocal);
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
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(slot);
		return slot;
	}

	// Reads the [count] header word of the vector whose base pointer is in vecLocal into
	// a
	// fresh i32 local.
	private int loadVecCount(Fn fn, int vecLocal) {
		int count = fn.allocLocal(Ty.F64VEC);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(vecLocal);
		fn.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
		// An exhaustive switch over the PERMITS, not `instanceof LispSingleFloatArray`
		// with the negative answer read as "therefore double": that shape emitted a
		// bfloat16 literal as an F64VEC, eight bytes an element, with no diagnostic.
		// A supertype pattern or a negated instanceof is a default however it is
		// spelled, and is only safe when the answer does not depend on the width
		// (.kb/vec.md).
		boolean single = switch (fa) {
			case am.ik.rontolisp.LispSingleFloatArray ignored -> true;
			case am.ik.rontolisp.LispDoubleFloatArray ignored -> false;
			case am.ik.rontolisp.LispBFloat16Array ignored -> throw am.ik.rontolisp.compiler.UnsupportedFloatWidth
				.refuse(am.ik.rontolisp.FloatWidth.BFLOAT16, "the --no-gc backend");
		};
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
		int dst = allocVec(fn, count, vecTy);
		for (int i = 0; i < n; i++) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		return vecTy;
	}

	// Pushes an f32 constant via the widening f64.const + f32.demote_f64 trick
	// (WasmWriter has no writeF32). (double) value is the exact widening and demote
	// narrows back to the same f32 bits for every value that can reach here.
	//
	// The exception the round trip does NOT survive is a signalling NaN -- f2d and d2f
	// alike quiet one, and f32.demote_f64 is free by specification to invent any NaN
	// payload (.kb/bfloat16.md). CHECKED 2026-09-05 (.todo/487) rather than assumed:
	// a signalling NaN cannot reach this method, for three independent reasons, any one
	// of which is sufficient.
	//
	// 1. The #f(...) reader syntax admits no NaN at all. `#f(nan)` is "expected a
	// number, got NAN"; an overflowing literal answers Infinity, not NaN; and `#.` is
	// not evaluated inside a float-array literal ("expected a number, got %READ-EVAL").
	// 2. The one route that DOES put a float-array value in the AST -- `#.` at an
	// ordinary expression position -- cannot carry a signalling NaN into the array,
	// because there is no f32 SCALAR: the element crosses a double on the way in, and
	// (%ieee754-single-from-bits #x7F800001) already answers #x7FC00001.
	// 3. LispSingleFloatArray.elementAt widens f32 -> f64 on the way out, so anything
	// that did get stored is quiet again before this method sees it.
	//
	// A QUIET NaN is reachable (a `#.`-built array holding (/ 0.0 0.0) emits
	// f64.const 0x7ff8000000000000 here), and its payload is the canonical one every
	// implementation reproduces -- so nothing observable is lost. The claim rests on
	// that and on the unreachability above, not on the round trip being lossless for an
	// arbitrary bit pattern.
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(base);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		compileCoerced(iExpr, fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		if (jExpr != null) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(base);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(addr);
		compileCoerced(valueExpr, fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(val);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(addr);
		if (single) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(val);
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
			// return promote(demote(val)) -- the value as actually stored (f32-rounded).
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(val);
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F64_PROMOTE_F32);
		}
		else {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(val);
			w.write(Instruction.F64_STORE, 0x00, 0x00);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(val);
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
	/**
	 * Refuses an {@code :element-type} naming a packed float width this backend does not
	 * carry, before either the type pass or the emitter has to guess at it. A width it
	 * does not recognize at all is not this method's business -- {@code compileMakeArray}
	 * says so in its own words.
	 * @param elementType the resolved element-type argument, or {@code null}
	 */
	private static void refuseUnsupportedWidth(@Nullable LispVal elementType) {
		LispFloatArray proto = LispFloatArray.prototypeFor(elementType);
		if (proto != null) {
			// The switch covers the PERMITS with no default arm, so the next width must
			// be refused or carried HERE, where the representation is decided, rather
			// than guessed at by whichever pass reads the designator next (.kb/vec.md,
			// "Asking a packed array its width").
			switch (proto) {
				case am.ik.rontolisp.LispBFloat16Array ignored -> throw am.ik.rontolisp.compiler.UnsupportedFloatWidth
					.refuse(am.ik.rontolisp.FloatWidth.BFLOAT16, "the --no-gc backend");
				case am.ik.rontolisp.LispSingleFloatArray ignored -> {
				}
				case am.ik.rontolisp.LispDoubleFloatArray ignored -> {
				}
			}
		}
	}

	private Ty compileMakeArray(List<LispVal> args, Fn fn) {
		if (args.size() < 2) {
			throw new UnsupportedOperationException("--no-gc: make-array needs a dimension in '" + fn.fnName + "'");
		}
		LispVal elementType = findKeywordValue(args, LispNames.ELEMENT_TYPE_KEYWORD);
		refuseUnsupportedWidth(elementType);
		boolean single = switch (LispFloatArray.prototypeFor(elementType)) {
			case null -> throw new UnsupportedOperationException(
					"--no-gc: make-array is only supported with :element-type " + "'double-float or 'single-float in '"
							+ fn.fnName + "' (the scalar backend has no general array " + "type)");
			case am.ik.rontolisp.LispSingleFloatArray ignored -> true;
			case am.ik.rontolisp.LispDoubleFloatArray ignored -> false;
			// Unreachable -- refuseUnsupportedWidth threw first; the arm exists so this
			// switch stays exhaustive over the permits, which is the point.
			case am.ik.rontolisp.LispBFloat16Array ignored -> throw am.ik.rontolisp.compiler.UnsupportedFloatWidth
				.refuse(am.ik.rontolisp.FloatWidth.BFLOAT16, "the --no-gc backend");
		};
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(fill);
		// for (i = 0; i < count; i++) mem[dst + 4 + (i << shift)] = fill (narrowed for
		// f32)
		int i = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(fill);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		return vecTy;
	}

	// The per-dimension expressions of a make-array dimension spec. Accepts an integer /
	// runtime expression (a rank-1 length directly), a quoted literal list '(d n) (its
	// elements are plain data), or a (list d n) form (its elements are runtime
	// expressions). NIL is the rank-0 shape (no dimensions at all), which this backend
	// has no type for. The returned size is the rank; compileMakeArray keys the layout
	// off it (1 = packed vector, 2 = packed matrix, else a clear compile error).
	private static List<LispVal> dimExprs(LispVal dimsArg) {
		LispVal spec = dimsArg;
		if (spec instanceof LispCons c && c.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& c.cdr() instanceof LispCons rest) {
			spec = rest.car();
			if (spec instanceof LispCons list) {
				return list.toList();
			}
			return spec instanceof LispNil ? List.of() : List.of(spec);
		}
		if (spec instanceof LispNil) {
			return List.of();
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(rows);
		int cols = fn.allocLocal(Ty.F64VEC); // i32 column count
		compileCoerced(dims.get(1), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(cols);
		int total = fn.allocLocal(Ty.F64VEC); // i32 element count rows*cols
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(rows);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(cols);
		w.write(Instruction.I32_MUL);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(total);
		// dst = __alloc(8 + (total << shift)); mem[dst] = rows; mem[dst+4] = cols
		int dst = fn.allocLocal(matTy);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(total);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(matTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(rows);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(cols);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(fill);
		// for (i = 0; i < total; i++) mem[dst + 8 + (i << shift)] = fill (narrowed for
		// f32)
		int i = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(total);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(matTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(fill);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
			LispNames.VEC_RELU_INTO, LispNames.VEC_CLIP_INTO, LispNames.VEC_MATVEC, LispNames.VEC_MATVEC_INTO,
			LispNames.VEC_DIV, LispNames.VEC_DIV_INTO, LispNames.VEC_PLUS, LispNames.VEC_MINUS, LispNames.VEC_STAR,
			LispNames.VEC_SLASH);

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
		// constructor's width comes from its literal :element-type option
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
					LispNames.VEC_CLIP_INTO, LispNames.VEC_DIV, LispNames.VEC_DIV_INTO, LispNames.VEC_PLUS,
					LispNames.VEC_MINUS, LispNames.VEC_STAR, LispNames.VEC_SLASH ->
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
			case LispNames.VEC_DIV ->
				compileSimdElementwise(args, fn, Instruction.F64X2_DIV, Instruction.F64_DIV, false);
			case LispNames.VEC_SCALE -> compileSimdScale(args, fn, false);
			// The CL operator spellings: the same kernels under their alias names, so
			// --no-gc (which never sees the vec.lisp aliases) lowers (vec:+ a b) to the
			// very instructions (vec:add a b) lowers to, with no extra call.
			case LispNames.VEC_PLUS ->
				compileSimdElementwise(args, fn, Instruction.F64X2_ADD, Instruction.F64_ADD, false);
			case LispNames.VEC_MINUS ->
				compileSimdElementwise(args, fn, Instruction.F64X2_SUB, Instruction.F64_SUB, false);
			case LispNames.VEC_STAR ->
				compileSimdElementwise(args, fn, Instruction.F64X2_MUL, Instruction.F64_MUL, false);
			case LispNames.VEC_SLASH ->
				compileSimdElementwise(args, fn, Instruction.F64X2_DIV, Instruction.F64_DIV, false);
			// The destination-passing kernels: same loops, but the destination is the
			// caller's vector instead of a fresh allocVec block -- so a loop over them
			// never advances the bump allocator.
			case LispNames.VEC_ADD_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_ADD, Instruction.F64_ADD, true);
			case LispNames.VEC_SUB_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_SUB, Instruction.F64_SUB, true);
			case LispNames.VEC_MUL_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_MUL, Instruction.F64_MUL, true);
			case LispNames.VEC_DIV_INTO ->
				compileSimdElementwise(args, fn, Instruction.F64X2_DIV, Instruction.F64_DIV, true);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(baseLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(outLocal);
	}

	// count = i32.wrap(the length argument) into a fresh i32 local.
	private int compileCountArg(LispVal arg, Fn fn) {
		WasmWriter w = fn.writer;
		int count = fn.allocLocal(Ty.F64VEC);
		compileCoerced(arg, fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
		return count;
	}

	private static String fillModeName(int fillMode) {
		return switch (fillMode) {
			case FILL_ONE -> LispNames.VEC_ONES;
			case FILL_ARANGE -> LispNames.VEC_ARANGE;
			default -> LispNames.VEC_ZEROS;
		};
	}

	// The width a vec:zeros/ones/arange call constructs: F32VEC for a literal
	// :element-type 'single-float keyword pair following the count, F64VEC for
	// 'double-float or for a name vec::%make does not recognize (its double default).
	// A PERMIT this backend does not carry (bfloat16) is refused HERE, where the
	// representation is chosen -- letting it fall to the double default would build an
	// eight-byte vector the interpreter answers as a two-byte one, the same wrong
	// number every other refusal in .kb/bfloat16.md exists to prevent. The switch covers
	// the PERMITS with no default arm, so the next width is answered here too.
	private static Ty constructorVecType(List<LispVal> args) {
		if (args.size() == 4 && isElementTypeKeyword(args.get(2))) {
			LispFloatArray proto = LispFloatArray.prototypeFor(args.get(3));
			if (proto != null) {
				return switch (proto) {
					case am.ik.rontolisp.LispSingleFloatArray ignored -> Ty.F32VEC;
					case am.ik.rontolisp.LispDoubleFloatArray ignored -> Ty.F64VEC;
					case am.ik.rontolisp.LispBFloat16Array ignored ->
						throw am.ik.rontolisp.compiler.UnsupportedFloatWidth.refuse(am.ik.rontolisp.FloatWidth.BFLOAT16,
								"the --no-gc backend");
				};
			}
		}
		return Ty.F64VEC;
	}

	// Whether a constructor argument is the literal :element-type keyword.
	private static boolean isElementTypeKeyword(LispVal v) {
		return v instanceof LispSymbol sym && sym.isKeyword() && sym.name().substring(1).equals("ELEMENT-TYPE");
	}

	// (vec:zeros n [:element-type et]) / (vec:ones n [:element-type et]) -> a fresh
	// constant-filled vector; (vec:arange n [:element-type et]) -> [0.0, 1.0, ..., n-1].
	// A literal :element-type 'single-float builds an F32VEC (f32 stride + a narrowing
	// store); the default F64VEC path is byte-identical to before.
	private Ty compileSimdConstruct(List<LispVal> args, Fn fn, int fillMode) {
		if (args.size() != 2 && !(args.size() == 4 && isElementTypeKeyword(args.get(2)))) {
			throw new UnsupportedOperationException("--no-gc: vec:" + fillModeName(fillMode)
					+ " takes a count plus an optional :element-type keyword in '" + fn.fnName + "'");
		}
		Ty vecTy = constructorVecType(args);
		boolean single = vecTy == Ty.F32VEC;
		WasmWriter w = fn.writer;
		int count = compileCountArg(args.get(1), fn);
		int dst = allocVec(fn, count, vecTy);
		// for (i = 0; i < count; i++) mem[dst+4+width*i] = <fill>
		int i = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(count);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		// addr = dst + 4 + (i << shift)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		if (fillMode == FILL_ARANGE) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(lo);
		int hi = fn.allocLocal(Ty.FLOAT);
		compileCoerced(args.get(into ? 4 : 3), fn, Ty.FLOAT);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(hi);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(s);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count);
		int vp = fn.allocLocal(Ty.F64VEC);
		int dp = fn.allocLocal(Ty.F64VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdScale(w, dp, vp, count, rem, -1, s, false);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(vL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(vL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(aL);
		compileCoerced(args.get(2), fn, Ty.F64VEC);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(aL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
	// vec:dot uses at f64 (WasmVecLoops.simdDot), and at f32 the multi-accumulator
	// sibling WasmVecLoops.simdMatvecRowDotF32 -- vec:dot keeps ONE chain, a GEMV row
	// folds four above a column gate, so the two no longer sum in the same order
	// (todo-480; an f32 row still accumulates in f32 lanes and promotes once, the --simd
	// single-precision reduction contract); without --simd the
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(wl);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(d);
		int cols = fn.allocLocal(Ty.F64VEC); // i32 column count (cols header word)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(wl);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(cols);
		if (into) {
			// if (out == x || out == w) trap: the aliasing the other backends reject with
			// an error would silently corrupt the GEMV here.
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dstL);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(xl);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dstL);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(wl);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.I32_OR);
			w.write(Instruction.IF, 0x40);
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
		}
		int dst = into ? dstL : allocVec(fn, d, vecTy);
		int rp = fn.allocLocal(Ty.F64VEC); // current row's data pointer
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(wl);
		w.write(Instruction.I32_CONST).writeSignedLeb128(8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(rp);
		int xp = fn.allocLocal(Ty.F64VEC); // x's data base (never advanced)
		dataPtr(w, xl, xp);
		int dp = fn.allocLocal(Ty.F64VEC); // output element pointer
		dataPtr(w, dst, dp);
		int ap = fn.allocLocal(Ty.F64VEC); // per-row cursors the dot emitters clobber
		int bp = fn.allocLocal(Ty.F64VEC);
		int rem = fn.allocLocal(Ty.F64VEC);
		int trem = -1;
		int acc = -1;
		// The f32 row folds WasmVecLoops.MATVEC_ACCUMULATORS independent chains once it
		// is wide enough (todo-480); the f64 row keeps its one chain, so it needs neither
		// the extra accumulators nor the wide-loop counter.
		int wide = -1;
		int acc1 = -1;
		int acc2 = -1;
		int acc3 = -1;
		int sum;
		if (this.simd) {
			trem = single ? fn.allocLocal(Ty.F64VEC) : -1;
			wide = single ? fn.allocLocal(Ty.F64VEC) : -1;
			acc = fn.allocV128Local();
			if (single) {
				// Allocated adjacently so the local declaration run-length-encodes to one
				// entry (the --no-gc shortest-encoding contract).
				acc1 = fn.allocV128Local();
				acc2 = fn.allocV128Local();
				acc3 = fn.allocV128Local();
			}
			sum = single ? fn.allocF32Local() : fn.allocLocal(Ty.FLOAT);
		}
		else {
			sum = single ? fn.allocF32Local() : fn.allocLocal(Ty.FLOAT);
		}
		int rowVal = fn.allocLocal(Ty.FLOAT); // the row's dot result (f64 boundary)
		int i = fn.allocLocal(Ty.F64VEC); // i32 row index
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(d);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(rp);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(ap);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(xp);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bp);
		if (this.simd && single) {
			WasmVecLoops.simdMatvecRowDotF32(w, ap, bp, cols, rem, wide, trem, acc, acc1, acc2, acc3, sum,
					WasmVecLoops.MATVEC_ACC_THRESHOLD);
		}
		else if (this.simd) {
			WasmVecLoops.splatZero(w, acc, false);
			WasmVecLoops.simdDot(w, ap, bp, cols, rem, trem, acc, sum, false);
		}
		else {
			WasmVecLoops.scalarDot(w, ap, bp, cols, rem, sum, single);
		}
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(rowVal);
		// dst[i] = rowVal (narrowed on a f32 output, like aset)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dp);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(rowVal);
		if (single) {
			w.write(Instruction.F32_DEMOTE_F64);
			w.write(Instruction.F32_STORE, 0x00, 0x00);
		}
		else {
			w.write(Instruction.F64_STORE, 0x00, 0x00);
		}
		// rp += cols << shift (the next row); dp += one element
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(rp);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(cols);
		w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(vecTy));
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(rp);
		WasmVecLoops.advancePtr(w, dp, single ? 4 : 8);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(s);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, Ty.F32VEC);
		int vp = fn.allocLocal(Ty.F32VEC);
		int dp = fn.allocLocal(Ty.F32VEC);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		int rem = fn.allocLocal(Ty.F64VEC);
		int trem = fn.allocLocal(Ty.F64VEC);
		WasmVecLoops.simdScale(w, dp, vp, count, rem, trem, s, true);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(vL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(vL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(aL);
		compileCoerced(args.get(2), fn, Ty.F32VEC);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(aL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(s);
		int count = loadVecCount(fn, vL);
		int dst = into ? dstL : allocVec(fn, count, vecTy);
		int vp = fn.allocLocal(vecTy);
		int dp = fn.allocLocal(vecTy);
		dataPtr(w, vL, vp);
		dataPtr(w, dst, dp);
		emitScalarScaleLoop(fn, vp, dp, count, s, vecTy);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(vL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(vL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(aL);
		compileCoerced(args.get(2), fn, vecTy);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bL);
		int count = fn.allocLocal(Ty.F64VEC);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(aL);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(count);
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

	// (char s i): the CODE POINT of the i-th character, decoded by the __char_at
	// helper (which converts the character index to a byte offset and decodes the
	// UTF-8 sequence there). No bounds check, like the rest of the backend's lean
	// lowering.
	private Ty compileCharAt(List<LispVal> args, Fn fn) {
		if (args.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: char takes a string and an index in '" + fn.fnName + "'");
		}
		if (fn.mem.charAtIndex() < 0) {
			throw new IllegalStateException("--no-gc: char without its __char_at helper in '" + fn.fnName + "'");
		}
		WasmWriter w = fn.writer;
		compileCoerced(args.get(1), fn, Ty.STRING);
		compileCoerced(args.get(2), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.charAtIndex());
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
		fn.writer.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.streqIndex());
		return emitPredicate(fn, Instruction.I64_EXTEND_U_I32);
	}

	// (subseq s start [end]): allocate a fresh [len][bytes] header holding the
	// CONTENT slice of characters [start, end) -- the character indices convert to
	// byte offsets through __byte_offset (end defaults to the byte length, i.e. the
	// whole tail). The header stays a BYTE count, like every other block. No bounds
	// check.
	private Ty compileSubseq(List<LispVal> args, Fn fn) {
		if (args.size() != 3 && args.size() != 4) {
			throw new UnsupportedOperationException(
					"--no-gc: subseq takes a string, a start and an optional end in '" + fn.fnName + "'");
		}
		if (fn.mem.byteOffsetIndex() < 0) {
			throw new IllegalStateException("--no-gc: subseq without its __byte_offset helper in '" + fn.fnName + "'");
		}
		WasmWriter w = fn.writer;
		compileCoerced(args.get(1), fn, Ty.STRING);
		int s = fn.allocLocal(Ty.STRING);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(s);
		compileCoerced(args.get(2), fn, Ty.INT);
		w.write(Instruction.I32_WRAP_I64);
		int startChar = fn.allocLocal(Ty.STRING); // i32 scratch: the character index
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(startChar);
		// startByte = __byte_offset(s, startChar)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(s);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(startChar);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.byteOffsetIndex());
		int start = fn.allocLocal(Ty.STRING); // i32 scratch: the byte offset
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(start);
		int len = fn.allocLocal(Ty.STRING); // i32 scratch: end - start, in bytes
		if (args.size() > 3) {
			compileCoerced(args.get(3), fn, Ty.INT);
			w.write(Instruction.I32_WRAP_I64);
			int endChar = fn.allocLocal(Ty.STRING); // i32 scratch
			w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(endChar);
			// endByte = __byte_offset(s, endChar)
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(s);
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(endChar);
			w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.byteOffsetIndex());
		}
		else {
			emitStrLen(w, s);
		}
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(start);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(len);
		// dst = __alloc(4 + len); store the length header.
		int dst = fn.allocLocal(Ty.STRING);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.allocIndex());
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// __memcpy(dst + 4, s + 4 + start, len)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(s);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(start);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.memcpyIndex());
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(dst);
		return Ty.STRING;
	}

	// (princ-to-string x): a boolean answers the static "T"/"NIL" header, an integer
	// renders via the __itoa helper, a float via the __ftoa helper (the GC backend's
	// digit-extraction algorithm); a string passes through unchanged.
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
			fn.writer.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.ftoaIndex());
			return Ty.STRING;
		}
		if (argTy == Ty.BOOL) {
			compileCoerced(args.get(1), fn, Ty.BOOL);
			fn.writer.write(Instruction.I64_EQZ);
			fn.writer.write(Instruction.IF).write(Type.I32.code());
			fn.writer.write(Instruction.I32_CONST)
				.writeSignedLeb128(Objects.requireNonNull(fn.mem.literals().get("NIL"),
						() -> "--no-gc: princ-to-string without its NIL header in '" + fn.fnName + "'"));
			fn.writer.write(Instruction.ELSE);
			fn.writer.write(Instruction.I32_CONST)
				.writeSignedLeb128(Objects.requireNonNull(fn.mem.literals().get("T"),
						() -> "--no-gc: princ-to-string without its T header in '" + fn.fnName + "'"));
			fn.writer.write(Instruction.END);
			return Ty.STRING;
		}
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.itoaIndex());
		return Ty.STRING;
	}

	// (print x) / (princ x): write the rendered text through the __write_stdout funnel
	// and return the argument, exactly matching the interpreter's semantics (print =
	// prin1 text + a trailing newline, so strings are quoted; princ = display text, no
	// quotes, no newline). Rendering an int/float allocates a transient string, so the
	// emission is bracketed with a heap-pointer mark/reset -- a print loop stays flat by
	// construction. A boolean writes T/NIL by name like the other backends: joining BOOL
	// with INT answers INT, so (princ (if p t 1)) prints 1 where the interpreter prints
	// T -- a stated residual of the static type lattice, not a silent agreement.
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
			return Ty.BOOL;
		}
		Ty t = staticType(arg, fn);
		switch (t) {
			case BOOL -> {
				int v = fn.allocLocal(Ty.BOOL);
				compileCoerced(arg, fn, Ty.BOOL);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(v);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
				w.write(Instruction.I64_EQZ);
				w.write(Instruction.IF, 0x40);
				emitWriteLiteral(fn, "NIL");
				w.write(Instruction.ELSE);
				emitWriteLiteral(fn, "T");
				w.write(Instruction.END);
				if (print) {
					emitWriteLiteral(fn, "\n");
				}
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
				return Ty.BOOL;
			}
			case INT, FLOAT -> {
				int v = fn.allocLocal(t);
				compileCoerced(arg, fn, t);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(v);
				// mark / render / write / reset: the transient digits are reclaimed.
				int mark = fn.allocLocal(Ty.STRING);
				w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeUnsignedLeb128(mark);
				int s = fn.allocLocal(Ty.STRING);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
				w.write(Instruction.CALL).writeUnsignedLeb128(t == Ty.FLOAT ? fn.mem.ftoaIndex() : fn.mem.itoaIndex());
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(s);
				emitWriteString(fn, s);
				if (print) {
					emitWriteLiteral(fn, "\n");
				}
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
				return t;
			}
			case STRING -> {
				// A princ of a literal in VALUE position still writes its region as
				// two constants -- it just leaves the header address behind as the
				// value as well. A value use keeps the header by construction (the
				// header-free comparison counts only statement sites), so the
				// address is always laid out; the generic path below stays as the
				// fallback, which fails loudly if the two ever disagree.
				if (!print && arg instanceof LispString literal && fn.mem.literals().containsKey(literal.value())) {
					emitWriteLiteral(fn, literal.value());
					w.write(Instruction.I32_CONST)
						.writeSignedLeb128(Objects.requireNonNull(fn.mem.literals().get(literal.value())));
					return Ty.STRING;
				}
				// A string argument is passthrough -- nothing allocated, no bracket.
				// print (prin1 semantics) frames it in quotes and escapes the embedded
				// " / \ so the text reads back; princ writes it bare.
				int s = fn.allocLocal(Ty.STRING);
				compileCoerced(arg, fn, Ty.STRING);
				w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(s);
				if (print) {
					emitWriteLiteral(fn, "\"");
					emitWriteStringEscaped(fn, s);
					emitWriteLiteral(fn, "\"");
					emitWriteLiteral(fn, "\n");
				}
				else {
					emitWriteString(fn, s);
				}
				w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(s);
				return Ty.STRING;
			}
			default -> throw new UnsupportedOperationException("--no-gc: " + name
					+ " of a packed float array is not supported in '" + fn.fnName + "' (print scalars or strings)");
		}
	}

	// (terpri): write a newline. Unlike print/princ, which answer their argument, this
	// has no value to answer -- so it is VOID and the nil it stands for is materialized
	// only where someone actually reads it.
	private Ty compileTerpri(List<LispVal> args, Fn fn) {
		requireArgc(args, 1, LispNames.TERPRI, fn);
		emitWriteLiteral(fn, "\n");
		return Ty.VOID;
	}

	// Writes a static literal's content bytes to stdout via the __write_stdout funnel.
	private void emitWriteLiteral(Fn fn, String content) {
		Integer off = fn.mem.regions().get(content);
		if (off == null) {
			throw new IllegalStateException("--no-gc: print literal not laid out in '" + fn.fnName + "': " + content);
		}
		emitLiteralRegion(fn.writer, off, content);
		fn.writer.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.writeStdoutIndex());
	}

	// Pushes a laid-out literal's (content ptr, byte length) -- the pair every consumer
	// of raw text in this backend takes, from __write_stdout to a host import's :string
	// parameter. Both halves are compile-time constants: the bytes sit at a fixed address
	// in the static data segment, so the `ptr+4; [ptr]` arithmetic a runtime string needs
	// has nothing left to compute -- and a literal only ever pushed this way carries no
	// [len] header at all (MemLayout).
	private static void emitLiteralRegion(WasmWriter w, int contentOffset, String content) {
		w.write(Instruction.I32_CONST).writeSignedLeb128(contentOffset);
		w.write(Instruction.I32_CONST)
			.writeSignedLeb128(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
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
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(run);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(len);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF, 1);
		// b = mem[strLocal + 4 + i]
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(strLocal);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x04);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(b);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128('"');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(b);
		w.write(Instruction.I32_CONST).writeSignedLeb128('\\');
		w.write(Instruction.I32_EQ);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		emitWriteRun(fn, strLocal, run, i);
		emitWriteLiteral(fn, "\\");
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(run);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.I32_CONST).writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(i);
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		emitWriteRun(fn, strLocal, run, len);
	}

	// __write_stdout(strLocal + 4 + from, to - from): one unescaped stretch of a string's
	// bytes.
	private void emitWriteRun(Fn fn, int strLocal, int fromLocal, int toLocal) {
		WasmWriter w = fn.writer;
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(strLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(fromLocal);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(toLocal);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(fromLocal);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.writeStdoutIndex());
	}

	// Writes the [len][bytes] string held in the given local to stdout via the
	// __write_stdout funnel.
	private void emitWriteString(Fn fn, int strLocal) {
		WasmWriter w = fn.writer;
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(strLocal);
		w.write(Instruction.I32_CONST).writeSignedLeb128(4);
		w.write(Instruction.I32_ADD);
		emitStrLen(w, strLocal);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.writeStdoutIndex());
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
		w.write(Instruction.GET_GLOBAL, 0x00).write(Instruction.SET_LOCAL).writeUnsignedLeb128(mark);
		Ty t = compileProgn(body, fn);
		if (!isRefKind(t)) {
			// A scalar result: pop the whole arena, the value rides the stack.
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
			return t;
		}
		int v = fn.allocLocal(Ty.STRING);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(v);
		int size = fn.allocLocal(Ty.STRING);
		emitRefByteSize(fn, v, t, size);
		// v >= mark: allocated inside the arena -- copy down and keep just the value.
		// v < mark: predates the arena (e.g. a literal or an outer value passed
		// through) -- pop everything, the pointer stays valid as-is.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(size);
		w.write(Instruction.CALL).writeUnsignedLeb128(fn.mem.memcpyIndex());
		// heap = (mark + size + 3) & -4 (the same 4-byte rounding as __alloc)
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(size);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_CONST).writeSignedLeb128(-4);
		w.write(Instruction.I32_AND);
		w.write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(v);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mark).write(Instruction.SET_GLOBAL, 0x00);
		w.write(Instruction.END);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
		return t;
	}

	// Computes the total byte size (header + data) of the reference value in local v
	// into the given i32 scratch local: a string is 4 + len, a packed vector 4 +
	// (count << shift), a packed matrix 8 + (rows * cols << shift).
	private void emitRefByteSize(Fn fn, int v, Ty t, int size) {
		WasmWriter w = fn.writer;
		boolean mat = t == Ty.F64MAT || t == Ty.F32MAT;
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		if (mat) {
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
			w.write(Instruction.I32_LOAD, 0x02, 0x04);
			w.write(Instruction.I32_MUL);
		}
		if (t != Ty.STRING) {
			w.write(Instruction.I32_CONST).writeSignedLeb128(elemShift(t));
			w.write(Instruction.I32_SHL);
		}
		w.write(Instruction.I32_CONST).writeSignedLeb128(mat ? 8 : 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(size);
	}

	// Pushes the stored length (the i32 header word) of the string whose pointer is in
	// the
	// given local.
	private static void emitStrLen(WasmWriter w, int strLocal) {
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(strLocal);
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
	// count, avoiding a branch. The right-shift magnitude is clamped at 63 first: a
	// shift past the width answers the sign, and the raw wasm shift would otherwise
	// mask the huge count to 6 bits and shift by the remainder (MISC.47/.48). A huge
	// LEFT count still wraps in the scalar backend, which cannot represent the bignum
	// the shift denotes.
	private Ty compileAsh(List<LispVal> args, Fn fn) {
		if (args.size() != 3) {
			throw new UnsupportedOperationException("--no-gc: ash takes exactly two arguments in '" + fn.fnName + "'");
		}
		int v = fn.allocLocal(Ty.INT);
		int c = fn.allocLocal(Ty.INT);
		compileCoerced(args.get(1), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(v);
		compileCoerced(args.get(2), fn, Ty.INT);
		fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(c);
		// left = v << c
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(c);
		fn.writer.write(Instruction.I64_SHL);
		// mag = c <= -64 ? 63 : 0 - c
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(v);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(c);
		i64Const(fn.writer, -64);
		fn.writer.write(Instruction.I64_LE_S);
		fn.writer.write(Instruction.IF, 0x7E);
		i64Const(fn.writer, 63);
		fn.writer.write(Instruction.ELSE);
		i64Const(fn.writer, 0);
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(c);
		fn.writer.write(Instruction.I64_SUB);
		fn.writer.write(Instruction.END);
		// right = v >> mag
		fn.writer.write(Instruction.I64_SHR_S);
		// select left when c >= 0, else right
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(c);
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
		if (argTy == Ty.INT || argTy == Ty.BOOL) {
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
		Ty t1 = staticType(args.get(1), fn);
		Ty t2 = staticType(args.get(2), fn);
		Ty operand = t1.join(t2);
		if (operand == Ty.FLOAT && isIntLike(t1) != isIntLike(t2)) {
			// A mixed integer/float pair compares exact values, like the interpreter
			// (.todo/037): the float's exact binary value against the i64. Coercing
			// the integer through f64 rounds past 2^53, so (= 9007199254740993
			// 9007199254740992.0) answered T and (> 9007199254740993
			// 9007199254740992.0) answered NIL. Each side stays in its own type and
			// the decision is exact; two same-typed sides keep the path below.
			// (A VOID side still goes below: it materializes the nil zero in the
			// joined representation there.) The integer roots in one local; the
			// float rides the stack straight into the helper.
			boolean intFirst = isIntLike(t1);
			int iLocal;
			if (intFirst) {
				compileCoerced(args.get(1), fn, Ty.INT);
				iLocal = fn.allocLocal(Ty.INT);
				fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(iLocal);
				compileCoerced(args.get(2), fn, Ty.FLOAT);
			}
			else {
				compileCoerced(args.get(1), fn, Ty.FLOAT);
				compileCoerced(args.get(2), fn, Ty.INT);
				iLocal = fn.allocLocal(Ty.INT);
				fn.writer.write(Instruction.SET_LOCAL).writeUnsignedLeb128(iLocal);
			}
			emitExactIntFloatCompare(fn, iLocal, intFirst ? intOp : mirrorIntComparison(intOp));
			return emitPredicate(fn, Instruction.I64_EXTEND_S_I32);
		}
		compileCoerced(args.get(1), fn, operand);
		compileCoerced(args.get(2), fn, operand);
		fn.writer.write(operand == Ty.FLOAT ? floatOp : intOp); // -> i32 (0/1)
		// Booleans live in the BOOL domain (an i64 0/1), so the flag is widened -- unless
		// the consumer
		// is a branch, which takes it back off (emitPredicate).
		return emitPredicate(fn, Instruction.I64_EXTEND_S_I32);
	}

	// Whether the static type rides the i64 representation (a BOOL 0/1 widens to INT
	// for free, and a character IS its code point).
	private static boolean isIntLike(Ty ty) {
		return ty == Ty.INT || ty == Ty.BOOL;
	}

	// Mirrors an integer comparison opcode end for end, for a comparison whose INT
	// operand comes second: (= stays, < meets > and <= meets >=).
	private static int mirrorIntComparison(int intOp) {
		if (intOp == Instruction.I64_LT_S) {
			return Instruction.I64_GT_S;
		}
		if (intOp == Instruction.I64_GT_S) {
			return Instruction.I64_LT_S;
		}
		if (intOp == Instruction.I64_LE_S) {
			return Instruction.I64_GE_S;
		}
		if (intOp == Instruction.I64_GE_S) {
			return Instruction.I64_LE_S;
		}
		if (intOp == Instruction.I64_EQ) {
			return Instruction.I64_EQ;
		}
		throw new IllegalArgumentException("--no-gc: unexpected integer comparison opcode: " + intOp);
	}

	/**
	 * Emits an exact i64-vs-f64 comparison, leaving an i32 0/1 flag: 1 exactly when
	 * {@code intVal <intOp> floatVal} as real numbers, 0 otherwise -- and 0 for every
	 * operator when the float is NaN (unordered, like the interpreter). An infinity sits
	 * beyond every i64 on its side. The float operand arrives on the stack top (so a
	 * comparison site roots only its integer operand in a local); the decomposition
	 * scratch triple lives on the function ({@code Fn.exactScratch}), shared by every
	 * mixed site. The float decomposes from its raw IEEE 754 bits exactly like the
	 * interpreter's {@code rationalOfDouble} (hidden bit, subnormal shape, sign on the
	 * mantissa, either zero a plain zero), then:
	 * <ul>
	 * <li>a non-negative exponent shifts the mantissa up and checks the shift survived
	 * ({@code (g >>s exp) == mant}); past 2^63 of magnitude -- an {@code exp >= 63}, or a
	 * shift that lost bits -- the float is strictly beyond every i64 on the mantissa's
	 * side (the far side's only reachable i64, -2^63, decomposes with a small exponent
	 * and never arrives here);</li>
	 * <li>a negative exponent divides the mantissa down with a truncating quotient
	 * {@code mQ} and remainder {@code mR}: a differing quotient decides, an equal one
	 * falls back to the remainder against zero ({@code i > f} exactly when
	 * {@code mR < 0}) -- no shift ever overflows, however large {@code -exp} is. A
	 * {@code K >= 64} leaves {@code |f| < 1}, so a nonzero integer is decided by its own
	 * sign and a zero by the mantissa's.</li>
	 * </ul>
	 * @param fn the function being compiled
	 * @param iLocal the i64 local holding the integer operand
	 * @param fLocal the f64 local holding the float operand
	 * @param intOp the integer comparison opcode reading {@code (intVal, floatVal)}
	 */
	private static void emitExactIntFloatCompare(Fn fn, int iLocal, int fLocal, int intOp) {
		// Reloads the float operand on the stack top: net stack effect is one value
		// in, one flag out, so a min/max round can stage its select values first and
		// decide on top of them.
		fn.writer.write(Instruction.GET_LOCAL).writeUnsignedLeb128(fLocal);
		emitExactIntFloatCompare(fn, iLocal, intOp);
	}

	/**
	 * Emits an exact i64-vs-f64 comparison over a float already on the stack top, leaving
	 * an i32 0/1 flag with the same contract as
	 * {@link #emitExactIntFloatCompare(Fn, int, int, int)}.
	 * @param fn the function being compiled
	 * @param iLocal the i64 local holding the integer operand
	 * @param intOp the integer comparison opcode reading {@code (intVal, floatVal)}
	 */
	private static void emitExactIntFloatCompare(Fn fn, int iLocal, int intOp) {
		// The float operand arrives on the stack top, so a comparison site roots only
		// its integer operand in a local; the decomposition scratch triple is shared
		// across the whole function (Fn.exactScratch).
		WasmWriter w = fn.writer;
		if (fn.exactBits < 0) {
			fn.exactBits = fn.allocLocal(Ty.INT);
			fn.exactMant = fn.allocLocal(Ty.INT);
			fn.exactExp = fn.allocLocal(Ty.INT);
		}
		int bits = fn.exactBits;
		int mant = fn.exactMant;
		int exp = fn.exactExp;
		// bits = reinterpret(f); exp = the biased exponent.
		w.write(Instruction.I64_REINTERPRET_F64);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		i64Const(w, 52);
		w.write(Instruction.I64_SHR_U);
		i64Const(w, 0x7ff);
		w.write(Instruction.I64_AND);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(exp);
		// A 0x7ff exponent is NaN or an infinity, which has no exact rational.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		i64Const(w, 0x7ff);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.IF).write(Type.I32.code());
		// A zero mantissa field is an infinity, beyond every i64 on its side; a
		// nonzero one is NaN, unordered against everything.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		i64Const(w, 0x000fffffffffffffL);
		w.write(Instruction.I64_AND);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF).write(Type.I32.code());
		if (intOp == Instruction.I64_LT_S || intOp == Instruction.I64_LE_S) {
			// i < +Inf only.
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
			i64Const(w, 0);
			w.write(Instruction.I64_LT_S);
			w.write(Instruction.I32_EQZ);
		}
		else if (intOp == Instruction.I64_GT_S || intOp == Instruction.I64_GE_S) {
			// i > -Inf only.
			w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
			i64Const(w, 0);
			w.write(Instruction.I64_LT_S);
		}
		else {
			w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		}
		w.write(Instruction.ELSE);
		w.write(Instruction.I32_CONST).writeSignedLeb128(0);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		emitExactFiniteCompare(fn, iLocal, bits, mant, exp, intOp);
		w.write(Instruction.END);
	}

	// The finite arm of {@link #emitExactIntFloatCompare}: decomposes the float in
	// {@code bits} into the signed mantissa ({@code mant}) and the binary exponent
	// ({@code exp}) and compares exactly. {@code bits} is dead past the decomposition
	// and is reused below as the shifted-mantissa / truncating-quotient scratch.
	private static void emitExactFiniteCompare(Fn fn, int iLocal, int bits, int mant, int exp, int intOp) {
		WasmWriter w = fn.writer;
		// mant = the fraction field; a zero exponent is subnormal (2^-1074 scale),
		// anything else gains the hidden bit and reads bexp - 1075.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		i64Const(w, 0x000fffffffffffffL);
		w.write(Instruction.I64_AND);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF, 0x40);
		i64Const(w, -1074);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		i64Const(w, 0x0010000000000000L);
		w.write(Instruction.I64_OR);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		i64Const(w, 1075);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.END);
		// The sign bit, applied to the integer mantissa (either zero stays zero).
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		i64Const(w, 0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF, 0x40);
		i64Const(w, 0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.END);
		// Either zero is plain zero over one.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF).write(Type.I32.code());
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(iLocal);
		i64Const(w, 0);
		w.write(intOp);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		i64Const(w, 0);
		w.write(Instruction.I64_GE_S);
		w.write(Instruction.IF).write(Type.I32.code());
		emitExactNonNegativeExp(fn, iLocal, bits, mant, exp, intOp);
		w.write(Instruction.ELSE);
		emitExactNegativeExp(fn, iLocal, bits, mant, exp, intOp);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// The {@code exp >= 0} arm: the float is the integer {@code mant * 2^exp}.
	// {@code bits} is the shift scratch.
	private static void emitExactNonNegativeExp(Fn fn, int iLocal, int bits, int mant, int exp, int intOp) {
		WasmWriter w = fn.writer;
		// exp >= 63 puts |f| at or past 2^63: strictly beyond every i64 on the
		// mantissa's side (the far side's only i64, -2^63, decomposes with a small
		// exponent and never arrives here).
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		i64Const(w, 63);
		w.write(Instruction.I64_GE_S);
		w.write(Instruction.IF).write(Type.I32.code());
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		i64Const(w, 0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF).write(Type.I32.code());
		emitExactStrictFlag(w, intOp, false);
		w.write(Instruction.ELSE);
		emitExactStrictFlag(w, intOp, true);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// g = mant << exp, kept only when the shift survived ((g >>s exp) == mant --
		// a lost high bit can never shift back, so the check is exact); otherwise
		// the float is strictly beyond every i64 on the mantissa's side, as above.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_SHL);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_SHR_S);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.IF).write(Type.I32.code());
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(iLocal);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		w.write(intOp);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		i64Const(w, 0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.IF).write(Type.I32.code());
		emitExactStrictFlag(w, intOp, false);
		w.write(Instruction.ELSE);
		emitExactStrictFlag(w, intOp, true);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// The {@code exp < 0} arm: the float is {@code mant / 2^K} with {@code K = -exp}.
	// {@code bits} carries the truncating quotient, {@code mant} the remainder.
	private static void emitExactNegativeExp(Fn fn, int iLocal, int bits, int mant, int exp, int intOp) {
		WasmWriter w = fn.writer;
		i64Const(w, 0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(exp);
		// K >= 64 leaves |f| strictly below 1 (and nonzero -- the zero mantissa took
		// the zero branch): a nonzero integer is decided by its own sign, a zero by
		// the mantissa's.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		i64Const(w, 64);
		w.write(Instruction.I64_GE_S);
		w.write(Instruction.IF).write(Type.I32.code());
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(iLocal);
		w.write(Instruction.I64_EQZ);
		w.write(Instruction.IF).write(Type.I32.code());
		i64Const(w, 0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(intOp);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(iLocal);
		i64Const(w, 0);
		w.write(intOp);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		// mQ = trunc(mant / 2^K): the arithmetic shift floors, so a negative
		// mantissa with nonzero low bits rounds one step toward zero.
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_SHR_S);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		i64Const(w, 0);
		w.write(Instruction.I64_LT_S);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		i64Const(w, 1);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_SHL);
		i64Const(w, 1);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.I64_AND);
		i64Const(w, 0);
		w.write(Instruction.I64_NE);
		w.write(Instruction.I32_AND);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		i64Const(w, 1);
		w.write(Instruction.I64_ADD);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.END);
		// mR = mant - mQ * 2^K (|mR| < 2^K, so the shift fits): a differing
		// quotient decides, an equal one falls back to the remainder against zero
		// (i > f exactly when mR < 0).
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(exp);
		w.write(Instruction.I64_SHL);
		w.write(Instruction.I64_SUB);
		w.write(Instruction.SET_LOCAL).writeUnsignedLeb128(mant);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(iLocal);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		w.write(Instruction.I64_EQ);
		w.write(Instruction.IF).write(Type.I32.code());
		i64Const(w, 0);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(mant);
		w.write(intOp);
		w.write(Instruction.ELSE);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(iLocal);
		w.write(Instruction.GET_LOCAL).writeUnsignedLeb128(bits);
		w.write(intOp);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Emits the i32 constant the comparison answers when the float is known strictly
	// beyond the integer on one side: {@code positive} selects the +Inf side (only
	// {@code <} and {@code <=} hold) versus the -Inf side (only {@code >} and
	// {@code >=} hold); {@code =} never holds on either.
	private static void emitExactStrictFlag(WasmWriter w, int intOp, boolean positive) {
		boolean holds;
		if (intOp == Instruction.I64_LT_S || intOp == Instruction.I64_LE_S) {
			holds = positive;
		}
		else if (intOp == Instruction.I64_GT_S || intOp == Instruction.I64_GE_S) {
			holds = !positive;
		}
		else {
			holds = false;
		}
		w.write(Instruction.I32_CONST).writeSignedLeb128(holds ? 1 : 0);
	}

	// (not x): logical negation -> (x == 0) as an i64 0/1.
	private Ty compileNot(List<LispVal> args, Fn fn) {
		if (args.size() != 2) {
			throw new UnsupportedOperationException("--no-gc: not takes exactly one argument in '" + fn.fnName + "'");
		}
		Ty argTy = compileExpr(args.get(1), fn);
		if (argTy == Ty.FLOAT) {
			fn.writer.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_EQ);
		}
		else {
			fn.writer.write(Instruction.I64_EQZ); // i32: 1 if the value is 0
		}
		return emitPredicate(fn, Instruction.I64_EXTEND_S_I32);
	}

	/**
	 * Finish a predicate: the comparison left an i32 0/1 on the stack and the value
	 * domain is i64, so widen it -- and record that the widening is the last byte of the
	 * body, so a consumer that wanted the flag rather than the value can take it back off
	 * ({@link #takeFlag}).
	 * @param fn the function being compiled
	 * @param widenOp the widening instruction (signed or unsigned; the flag is 0/1, so
	 * the two agree -- each producer keeps the one it always emitted)
	 * @return the BOOL type every rontolisp boolean has
	 */
	private static Ty emitPredicate(Fn fn, int widenOp) {
		fn.writer.write(widenOp);
		fn.predicateEnd = fn.body.size();
		return Ty.BOOL;
	}

	/**
	 * Take back the widening of the predicate that just finished, leaving its i32 flag on
	 * the stack -- which is exactly what {@code if} and {@code br_if} consume. False when
	 * the expression just compiled did not end in one, in which case the caller emits the
	 * general truthiness test instead.
	 *
	 * <p>
	 * The test is positional and therefore exact: {@code predicateEnd} is only ever set
	 * to the body size immediately after a widening byte, so it can still equal that size
	 * only if nothing has been written since.
	 * @param fn the function being compiled
	 * @return true when the flag is now on the stack in place of the i64 value
	 */
	private static boolean takeFlag(Fn fn) {
		if (fn.predicateEnd != fn.body.size()) {
			return false;
		}
		fn.body.dropLastByte();
		fn.predicateEnd = -1;
		return true;
	}

	// Converts the top-of-stack value into an i32 truthiness flag: true (1) iff it is not
	// zero. (Scalar mode treats numeric 0 as false; see the class doc.)
	private static void emitTruthy(Ty ty, WasmWriter w) {
		if (ty == Ty.VOID) {
			// Nothing was pushed and nil is false: the flag IS the constant.
			w.write(Instruction.I32_CONST).writeSignedLeb128(0);
			return;
		}
		if (ty == Ty.FLOAT) {
			w.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_NE);
		}
		else {
			i64Const(w, 0);
			w.write(Instruction.I64_NE);
		}
	}

	// Converts the top-of-stack value into an i32 "is false" flag: 1 iff it is zero. Used
	// by while to br out of the loop when the test fails.
	private static void emitFalsy(Ty ty, WasmWriter w) {
		if (ty == Ty.VOID) {
			w.write(Instruction.I32_CONST).writeSignedLeb128(1);
			return;
		}
		if (ty == Ty.FLOAT) {
			w.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_EQ);
		}
		else {
			w.write(Instruction.I64_EQZ);
		}
	}

	// --- Eligibility / reachability ----------------------------------------------------

	// Validates that an expression is eligible for the scalar backend and records the
	// names of every eligible function it calls. Throws (naming the op + the function) on
	// anything unsupported, so the boundary is explicit.
	//
	// stmt is whether the form sits in STATEMENT position -- evaluated for its effect,
	// its value discarded -- exactly where the emitter's compileStatement runs. Only a
	// statement (princ <literal>) folds to two constants (and only such a site counts
	// toward printLiteralSites); every value position stays on the generic path, so the
	// walk and the emitter agree occurrence for occurrence.
	private void collectCalls(LispVal expr, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName, boolean stmt) {
		switch (expr) {
			case LispInteger ignored -> {
			}
			case LispDouble ignored -> {
			}
			case LispString s -> this.literalOccurrences.merge(s.value(), 1, Integer::sum);
			case LispFloatArray ignored -> {
			}
			case LispChar ignored -> {
			}
			case LispTrue ignored -> {
			}
			case LispNil ignored -> {
			}
			case LispSymbol sym -> {
				// A standard scalar constant in code position is a literal, not a
				// global to resolve (see .kb/read-time-constants.md). A lexical
				// binding still wins, checked first.
				if (!bound.contains(sym.name()) && scalarConstant(sym.name()) == null) {
					throw new UnsupportedOperationException("--no-gc: '" + sym.name() + "' in function '" + fnName
							+ "' is not a parameter or let binding (scalar mode has no globals or heap values)");
				}
			}
			case LispCons cons -> collectCallsCons(cons, bound, defuns, callees, fnName, stmt);
			case am.ik.rontolisp.LispComplex c ->
				throw new UnsupportedOperationException("--no-gc: complex numbers are not supported in function '"
						+ fnName + "': " + c.print() + " (the scalar backend is for pure numeric exports)");
			default -> throw new UnsupportedOperationException(
					"--no-gc: unsupported value in function '" + fnName + "': " + expr.print());
		}
	}

	private void collectCallsCons(LispCons cons, Set<String> bound, Map<String, Defun> defuns, Set<String> callees,
			String fnName, boolean stmt) {
		if (!(cons.car() instanceof LispSymbol head)) {
			throw new UnsupportedOperationException(
					"--no-gc: cannot call a non-symbol / first-class function in '" + fnName + "': " + cons.print());
		}
		String name = head.name();
		List<LispVal> args = cons.toList();
		LispVal expanded = expandMacro(name, cons, args.size() - 1);
		if (expanded != null) {
			collectCalls(expanded, bound, defuns, callees, fnName, stmt);
			return;
		}
		if (isComplexOperator(name)) {
			// The scalar backend is for pure numeric exports: constructing,
			// testing or dissecting a complex is refused here, naming the form --
			// never a trap, never a wrong number.
			throw new UnsupportedOperationException("--no-gc: complex numbers are not supported in function '" + fnName
					+ "': (" + name + " ...) (the scalar backend is for pure numeric exports)");
		}
		if (LispNames.RATIONAL.equals(name)) {
			// The unboxed i64/f64 value model has no ratio representation ((/)
			// never produces one here either), so rational is refused outright --
			// never a trap, never a float masquerading as an exact answer.
			throw new UnsupportedOperationException("--no-gc: rational numbers are not supported in function '" + fnName
					+ "': (" + name + " ...) (the scalar backend is for pure numeric exports)");
		}
		if (LispNames.INTEGER_DECODE_FLOAT.equals(name) || LispNames.RATIONALIZE.equals(name)) {
			// integer-decode-float's second and third values have nowhere to go
			// (values answers its primary only here) and rationalize's answer is a
			// ratio, so both are refused outright like rational above -- never a
			// silently dropped value, never a float masquerading as exact.
			throw new UnsupportedOperationException(
					"--no-gc: " + name.toLowerCase(java.util.Locale.ROOT) + " is not supported in function '" + fnName
							+ "': (" + name + " ...) (the scalar backend is for pure numeric exports)");
		}
		if (LispNames.FLOAT_SIGN.equals(name) || LispNames.FLOAT_DIGITS.equals(name)) {
			// float-sign's &optional lambda list and float-digits' floatp check
			// have no scalar lowering, so both are refused outright like
			// integer-decode-float above -- never a trap, never a wrong answer.
			throw new UnsupportedOperationException(
					"--no-gc: " + name.toLowerCase(java.util.Locale.ROOT) + " is not supported in function '" + fnName
							+ "': (" + name + " ...) (the scalar backend is for pure numeric exports)");
		}
		if (LispNames.LDB_TEST.equals(name)) {
			// ldb-test rides on ldb, whose expansion reads the bytespec cons
			// back -- and the scalar value model has no cons -- so it is
			// refused outright like deposit-field's field replacement above
			// (.todo/818) -- never a trap, never a wrong answer.
			throw new UnsupportedOperationException(
					"--no-gc: " + name.toLowerCase(java.util.Locale.ROOT) + " is not supported in function '" + fnName
							+ "': (" + name + " ...) (the scalar backend is for pure numeric exports)");
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
			// A statement (princ <literal>) is two constants at emission (the print
			// fold); print needs its quotes and escapes, and every value position
			// stays generic, so neither counts here.
			if (stmt && LispNames.PRINC.equals(name) && args.get(1) instanceof LispString lit) {
				this.printLiteralSites.merge(lit.value(), 1, Integer::sum);
			}
			collectCalls(args.get(1), bound, defuns, callees, fnName, false);
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
			// A progn with a reclamation boundary at emission: every form but the
			// last is a statement.
			for (int i = 2; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName, i < args.size() - 1);
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
			// A vec constructor's :element-type option is a keyword plus a literal
			// quoted symbol ('single-float / 'double-float) -- a compile-time designator,
			// not a runtime value -- so skip the keyword and the quote form, as
			// collectMakeArray does for :element-type.
			for (int i = 1; i < args.size(); i++) {
				LispVal a = args.get(i);
				if (a instanceof LispCons c && c.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())) {
					continue;
				}
				if (a instanceof LispSymbol sym && sym.isKeyword()) {
					continue;
				}
				collectCalls(a, bound, defuns, callees, fnName, false);
			}
			return;
		}
		if (LispNames.PROGN.equals(name) || LispNames.BLOCK_INTERNAL.equals(name)) {
			// Every form but the last is a statement; the last answers the value.
			for (int i = 1; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName, i < args.size() - 1);
			}
			return;
		}
		if (LispNames.WHILE.equals(name)) {
			// The test answers the loop condition; every body form is a statement.
			for (int i = 1; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName, i > 1);
			}
			return;
		}
		if (LispNames.IF.equals(name) || LispNames.RETURN.equals(name) || BUILTINS.contains(name)
				|| ARRAY_OPS.contains(name)) {
			for (int i = 1; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName, false);
			}
			return;
		}
		if (defuns.containsKey(name) || this.imports.containsKey(name)) {
			callees.add(name);
			// Every REACHED call site of every host import, in one place: what decides
			// whether the import's wrapper is worth emitting at all
			// (chooseFoldedImports). This walk and compileCall expand macros the same
			// way and walk the same forms, so the sites recorded here are the sites
			// compileUserCall will reach -- a `(if t ...)` dead branch the emitter folds
			// away is walked here and can only make the decision more conservative,
			// never less. The one call NOT recorded is a transparent forwarder's own:
			// its arguments are its parameters by definition, and the site that has the
			// literals is the call to the FORWARDER, one frame up.
			String imported = this.imports.containsKey(name) ? name : this.forwarders.get(name);
			if (imported != null && !this.forwarders.containsKey(fnName)) {
				this.importCallSites.computeIfAbsent(imported, ignored -> new ArrayList<>())
					.add(new ImportSite(name, cons));
			}
			for (int i = 1; i < args.size(); i++) {
				collectCalls(args.get(i), bound, defuns, callees, fnName, false);
			}
			return;
		}
		throw new UnsupportedOperationException("--no-gc: unsupported operation '" + name + "' in function '" + fnName
				+ "' (not a numeric primitive or an eligible function)");
	}

	// A complex constructor, predicate or accessor: refused outright, since the
	// scalar value model (unboxed i64/f64) has no complex representation.
	private static boolean isComplexOperator(String name) {
		return LispNames.COMPLEX.equals(name) || LispNames.COMPLEXP.equals(name) || LispNames.REALP.equals(name)
				|| LispNames.REALPART.equals(name) || LispNames.IMAGPART.equals(name)
				|| LispNames.CONJUGATE.equals(name) || LispNames.PHASE.equals(name);
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
			collectCalls(args.get(2 + 2 * p), bound, defuns, callees, fnName, false);
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
			collectCalls(args.get(i), bound, defuns, callees, fnName, false);
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
					collectCalls(dim, bound, defuns, callees, fnName, false);
				}
			}
		}
		LispVal init = findKeywordValue(args, LispNames.INITIAL_ELEMENT_KEYWORD);
		if (init != null) {
			collectCalls(init, bound, defuns, callees, fnName, false);
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
					collectCalls(bp.get(1), bound, defuns, callees, fnName, false);
				}
				inner.add(((LispSymbol) bp.get(0)).name());
			}
			else {
				throw new UnsupportedOperationException(
						"--no-gc: malformed let binding in '" + fnName + "': " + binding.print());
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			// A let body is a progn at emission: every form but the last is a
			// statement.
			collectCalls(parts.get(i), inner, defuns, callees, fnName, i < parts.size() - 1);
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
			// ffloor/fceiling/fround/ftruncate always expand -- (float (floor a [b])) --
			// onto the FLOAT and FLOOR-family builtins already handled above/below, so
			// this backend needs no separate lowering (todo-667). Unlike the plain
			// family there is no one-argument native form to fall through to.
			case LispNames.FFLOOR, LispNames.FCEILING, LispNames.FROUND, LispNames.FTRUNCATE ->
				LispMacroExpander.expandFFamily(cons);
			// lognand/lognor/logeqv lower to the scalar bitwise primitives this
			// backend already compiles, and float-radix to its constant (the operand
			// still evaluated once). deposit-field stays out like dpb: a field
			// replacement needs the bytespec list the general expansion reads back.
			case LispNames.LOGNAND, LispNames.LOGNOR -> LispMacroExpander.expandLogComplement(cons);
			case LispNames.LOGEQV -> LispMacroExpander.expandLogEqv(cons);
			case LispNames.FLOAT_RADIX -> LispMacroExpander.expandFloatRadix(cons);
			// logcount lowers to the scalar population-count loop (a negative
			// operand complemented first); integer-decode-float and rationalize
			// stay out: the former's second and third values and the latter's
			// ratio answer have no representation in the scalar value model
			// (refused in collectCallsCons, like rational).
			case LispNames.LOGCOUNT -> LispMacroExpander.expandLogcount(cons);
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
		if (type == BoundaryType.BYTES) {
			throw new UnsupportedOperationException("--no-gc does not support the :bytes export type for '"
					+ decl.name() + "' (the scalar backend has no arrays, so there is no byte vector to carry)");
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
		// A (defun (setf name) ...) arrives with a CONS in the name slot (the
		// prelude's bit/sbit writers splice in on any program spelling the
		// symbol, a quoted type specifier included). The scalar lowering has no
		// places to write through, so refuse it clearly instead of casting.
		if (!(parts.get(1) instanceof LispSymbol nameSym)) {
			throw new UnsupportedOperationException("Cannot compile function '" + parts.get(1).print()
					+ "': setf functions are not supported with --no-gc");
		}
		String name = nameSym.name();
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
	/**
	 * A function body under construction. {@code dropLastByte} is what lets a predicate
	 * hand its i32 flag straight to an {@code if} (see {@code emitPredicate}): the
	 * widening to the i64 value domain is already written when the consumer turns out not
	 * to want it.
	 */
	private static final class Body extends ByteArrayOutputStream {

		void dropLastByte() {
			this.count--;
		}

	}

	private static final class Fn {

		final Body body;

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

		// The exact int/float comparison's scratch triple (bits, mantissa, exponent),
		// allocated once per function and shared by every mixed site, so a function
		// with many mixed comparisons does not grow a triple per site. Sharing is
		// sound because each site's use is strictly scoped: operands are compiled
		// (nested sites complete first) before the helper runs, and the helper is
		// straight-line WAT with no calls. Negative until allocated (local indices
		// count up from the parameter slots, so -1 is never valid).
		int exactBits = -1;

		int exactMant = -1;

		int exactExp = -1;

		// The body offset just past a predicate's widening byte (emitPredicate). When it
		// still equals the body size, that byte is the last thing written and the i32
		// flag under it is intact -- which is what takeFlag tests.
		int predicateEnd = -1;

		Fn(Body body, Types types, Map<String, Integer> index, String fnName, Set<String> paramNames, Mem mem) {
			this.body = body;
			this.writer = new WasmWriter(body);
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
