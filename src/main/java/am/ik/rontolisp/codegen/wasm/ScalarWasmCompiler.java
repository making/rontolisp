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

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Mutability;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * A non-GC ("scalar") WASM lowering for pure-numeric {@code rontolisp:wasm-export}
 * functions ({@code --no-gc}).
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
 * / omitted. Memory-backed {@code :string}/{@code :sexpr} would need a second
 * linear-memory string runtime and are deferred (Phase 2).
 */
public final class ScalarWasmCompiler implements LispCompiler {

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
		STRING;

		/**
		 * The result type when this and another type are combined. Within the numeric
		 * sublattice float wins over int. {@code STRING} is a separate kind: it joins
		 * only with itself or with {@code INT} (which doubles as the inference bottom, so
		 * a not-yet-seen slot yields to a string), while joining a {@code STRING} with a
		 * {@code FLOAT} is a genuine type error (a slot cannot be both a string and a
		 * float).
		 */
		Ty join(Ty other) {
			if (this == STRING || other == STRING) {
				if (this == FLOAT || other == FLOAT) {
					throw new UnsupportedOperationException("--no-gc: a value cannot be both a string and a number");
				}
				return STRING;
			}
			return (this == FLOAT || other == FLOAT) ? FLOAT : INT;
		}

		/**
		 * The wasm value type byte (also the {@code if}/{@code block} result blocktype).
		 */
		int valType() {
			return switch (this) {
				case INT -> Type.I64.code();
				case FLOAT -> Type.F64.code();
				case STRING -> Type.I32.code();
			};
		}

	}

	/** Operators handled directly as primitive numeric/boolean/bitwise operations. */
	private static final Set<String> BUILTINS = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV,
			LispNames.MOD, LispNames.REM, LispNames.ABS, LispNames.MIN, LispNames.MAX, LispNames.FLOAT,
			LispNames.TRUNCATE, LispNames.FLOOR, LispNames.CEILING, LispNames.ROUND, LispNames.EQ, LispNames.LT,
			LispNames.LE, LispNames.GT, LispNames.GE, LispNames.NOT, LispNames.SQRT, LispNames.LOGAND, LispNames.LOGIOR,
			LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.CONCATENATE);

	private final boolean optimize;

	/** Creates a new scalar WASM compiler. */
	public ScalarWasmCompiler() {
		this(false);
	}

	/**
	 * Creates a new scalar WASM compiler.
	 * @param optimize when {@code true}, the finished module is run through
	 * {@link am.ik.wasm.WasmTreeShaker} so anything unreachable from the exports is
	 * dropped and the survivors renumbered. The shaker is GC-agnostic, so it composes
	 * with the non-GC module shape for free.
	 */
	public ScalarWasmCompiler(boolean optimize) {
		this.optimize = optimize;
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		// Resolve packages first, like the other backends, so qualified names
		// (rontolisp:wasm-export) and in-package directives are canonical.
		program = new PackageResolver().resolveProgram(program);

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
		Mem mem = planMemory(reachable, defuns, exportDecls, internalCount);

		// Internal functions occupy indices 0..N-1; wrapper j occupies N + j; the two
		// memory helpers (when present) occupy N+E and N+E+1.
		List<byte[]> internalBodies = new ArrayList<>();
		for (String name : reachable) {
			internalBodies.add(compileDefunBody(Objects.requireNonNull(defuns.get(name)), name, types, index, mem));
		}
		List<byte[]> wrapperBodies = new ArrayList<>();
		for (WasmExportCompiler.Decl decl : exportDecls) {
			wrapperBodies.add(compileWrapperBody(decl, Objects.requireNonNull(index.get(decl.name())), types, mem));
		}

		byte[] module = assemble(reachable, internalBodies, exportDecls, wrapperBodies, internalCount, types, mem);
		return this.optimize ? am.ik.wasm.WasmTreeShaker.shake(module) : module;
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
	// :string -> STRING, :int/:bool -> INT.
	private static Ty boundaryTy(String designator) {
		if (WasmExportCompiler.T_FLOAT.equals(designator)) {
			return Ty.FLOAT;
		}
		if (WasmExportCompiler.T_STRING.equals(designator)) {
			return Ty.STRING;
		}
		return Ty.INT;
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
			// Comparisons, predicates, not and the bitwise operators yield an integer.
			case LispNames.EQ, LispNames.LT, LispNames.LE, LispNames.GT, LispNames.GE, LispNames.NOT, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH -> {
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
	 * output).
	 *
	 * @param literals string-literal content to its header address in the data segment
	 * @param data the static data-segment bytes (laid out from {@code dataBase})
	 * @param dataBase the memory address at which {@code data} is placed
	 * @param heapBase the initial bump-allocator pointer (just past the static data)
	 * @param allocIndex the function index of the {@code __alloc} bump allocator
	 * @param memcpyIndex the function index of the {@code __memcpy} byte-copy helper
	 * @param used whether the module uses linear memory at all
	 */
	private record Mem(Map<String, Integer> literals, byte[] data, int dataBase, int heapBase, int allocIndex,
			int memcpyIndex, boolean used) {
	}

	private static final int STR_DATA_BASE = 8;

	private Mem planMemory(List<String> reachable, Map<String, Defun> defuns, List<WasmExportCompiler.Decl> exportDecls,
			int internalCount) {
		// Gather every string literal in every reachable body (deterministic order), then
		// lay each out as a 4-byte-aligned [len:i32 LE][bytes] header.
		LinkedHashSet<String> literals = new LinkedHashSet<>();
		for (String name : reachable) {
			collectLiterals(progn(Objects.requireNonNull(defuns.get(name)).body()), literals);
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

		boolean boundaryString = false;
		for (WasmExportCompiler.Decl decl : exportDecls) {
			if (WasmExportCompiler.T_STRING.equals(decl.returnType())
					|| decl.paramTypes().contains(WasmExportCompiler.T_STRING)) {
				boundaryString = true;
			}
		}
		boolean used = !literals.isEmpty() || boundaryString;
		int allocIndex = internalCount + exportDecls.size();
		int memcpyIndex = allocIndex + 1;
		return new Mem(offsets, data.toByteArray(), STR_DATA_BASE, heapBase, allocIndex, memcpyIndex, used);
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
			List<WasmExportCompiler.Decl> exportDecls, List<byte[]> wrapperBodies, int internalCount, Types types,
			Mem mem) {
		// The two extra function types/bodies for the memory helpers, present only when
		// the
		// module uses linear memory.
		int helperTypeBase = internalCount + exportDecls.size();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write("\0asm")
			.writeLittleEndian4(1)
			// Type section: one function type per function (no dedup needed). Internal
			// function k: its inferred (i64|f64|i32 ...) -> (i64|f64|i32). Wrapper j: the
			// host signature. Then, when memory is used, __alloc (i32)->i32 and __memcpy
			// (i32,i32,i32)->().
			.writeTypeSection(typeSec -> {
				for (String name : reachable) {
					typeSec.addFunc(wasmParamTypes(name, types), new Type[] { wasmType(returnTy(name, types)) });
				}
				for (WasmExportCompiler.Decl decl : exportDecls) {
					typeSec.addFunc(WasmExportCompiler.paramWasmTypes(decl), WasmExportCompiler.resultWasmTypes(decl));
				}
				if (mem.used()) {
					typeSec.addFunc(new Type[] { Type.I32 }, new Type[] { Type.I32 });
					typeSec.addFunc(new Type[] { Type.I32, Type.I32, Type.I32 }, new Type[0]);
				}
			})
			// Function section: function index k uses type index k (1:1).
			.writeFunction(func -> {
				int total = helperTypeBase + (mem.used() ? 2 : 0);
				for (int k = 0; k < total; k++) {
					func.addFunction(k);
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
			// Export section: each directive exports its wrapper under the function name.
			// When memory is used, also export the linear memory and the bump allocator
			// so a host can write :string inputs and read :string results.
			.writeExport(exports -> {
				for (int j = 0; j < exportDecls.size(); j++) {
					exports.addExport(exportDecls.get(j).name(), ExternalKind.FUNCTION, internalCount + j);
				}
				if (mem.used()) {
					exports.addExport("memory", ExternalKind.MEMORY, 0);
					exports.addExport("__ronto_alloc", ExternalKind.FUNCTION, mem.allocIndex());
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
			case STRING -> Type.I32;
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
		return withLocals(bodyStream.toByteArray(), fn.extraLocalTypes);
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

		// Box each host argument into the internal value of the inferred parameter type,
		// in
		// order, then call the internal function.
		int slot = 0;
		for (int p = 0; p < decl.paramTypes().size(); p++) {
			String hostType = decl.paramTypes().get(p);
			Ty internal = internalParams[p];
			if (WasmExportCompiler.T_STRING.equals(hostType)) {
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
				w.write(Instruction.GET_LOCAL).writeSignedLeb128(slot);
				if (WasmExportCompiler.T_FLOAT.equals(hostType)) {
					// host f64 -> internal (always FLOAT, since :float pins the param)
					if (internal == Ty.INT) {
						w.write(Instruction.I64_TRUNC_S_F64);
					}
				}
				else {
					// host i32 (:int/:bool) -> internal
					if (internal == Ty.INT) {
						w.write(Instruction.I64_EXTEND_S_I32);
					}
					else {
						w.write(Instruction.F64_CONVERT_S_I32);
					}
				}
				slot += 1;
			}
		}
		w.write(Instruction.CALL).writeSignedLeb128(targetIndex);
		// Unbox the internal result (its inferred return type) back to the host type.
		Ty ret = returnTy(name, types);
		switch (decl.returnType()) {
			case WasmExportCompiler.T_INT -> {
				if (ret == Ty.INT) {
					w.write(Instruction.I32_WRAP_I64);
				}
				else {
					w.write(Instruction.I32_TRUNC_S_F64);
				}
			}
			case WasmExportCompiler.T_FLOAT -> {
				if (ret == Ty.INT) {
					w.write(Instruction.F64_CONVERT_S_I64);
				}
			}
			case WasmExportCompiler.T_BOOL -> {
				// non-zero -> 1, zero -> 0
				if (ret == Ty.INT) {
					i64Const(w, 0);
					w.write(Instruction.I64_NE);
				}
				else {
					w.write(Instruction.F64_CONST).writeF64(0.0).write(Instruction.F64_NE);
				}
			}
			case WasmExportCompiler.T_STRING -> {
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
			case WasmExportCompiler.T_VOID -> w.write(Instruction.DROP);
			default -> throw new UnsupportedOperationException("--no-gc does not support the export return type "
					+ decl.returnType() + " (only :int/:float/:bool/:string/:void)");
		}
		w.write(Instruction.END);
		return withLocals(bodyStream.toByteArray(), wrapperLocals);
	}

	// Prepends the locals declaration to a function body. Each extra local is emitted as
	// its own run (count 1) so the declaration order matches the allocation order
	// regardless of the i64/f64 mix.
	private static byte[] withLocals(byte[] code, List<Ty> extraLocals) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.writeUnsignedLeb128(extraLocals.size());
		for (Ty ty : extraLocals) {
			w.write(1);
			w.write(ty.valType());
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
		if (target == Ty.STRING && expr instanceof LispNil) {
			fn.writer.write(Instruction.I32_CONST).writeSignedLeb128(0);
			return;
		}
		coerce(fn.writer, compileExpr(expr, fn), target);
	}

	private static void coerce(WasmWriter w, Ty from, Ty to) {
		if (from == to) {
			return;
		}
		if (from == Ty.STRING || to == Ty.STRING) {
			throw new UnsupportedOperationException(
					"--no-gc: cannot use a string where a number is expected (or vice versa)");
		}
		if (from == Ty.INT) {
			w.write(Instruction.F64_CONVERT_S_I64); // i64 -> f64
		}
		else {
			w.write(Instruction.I64_TRUNC_S_F64); // f64 -> i64 (truncate toward zero)
		}
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

		return switch (name) {
			case LispNames.IF -> compileIf(args, fn);
			case LispNames.PROGN -> compileProgn(args.subList(1, args.size()), fn);
			case LispNames.LET -> compileLet(cons, fn);
			case LispNames.SETQ -> compileSetq(args, fn);
			case LispNames.WHILE -> compileWhile(args, fn);
			case LispNames.BLOCK_INTERNAL -> compileBlock(cons, args, fn);
			case LispNames.RETURN -> compileReturn(args, fn);
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
		fn.writer.write(Instruction.CALL).writeSignedLeb128(funcIndex);
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
		if (LispNames.IF.equals(name) || LispNames.PROGN.equals(name) || LispNames.WHILE.equals(name)
				|| LispNames.BLOCK_INTERNAL.equals(name) || LispNames.RETURN.equals(name) || BUILTINS.contains(name)) {
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
		if (!isQuotedSymbol(args.get(1), "string")) {
			throw new UnsupportedOperationException(
					"--no-gc: concatenate only supports 'string in '" + fnName + "' (got " + args.get(1).print() + ")");
		}
		for (int i = 2; i < args.size(); i++) {
			collectCalls(args.get(i), bound, defuns, callees, fnName);
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
			default -> null;
		};
	}

	private static void validateScalarTypes(WasmExportCompiler.Decl decl) {
		for (String t : decl.paramTypes()) {
			requireSupported(t, decl);
		}
		if (!WasmExportCompiler.T_VOID.equals(decl.returnType())) {
			requireSupported(decl.returnType(), decl);
		}
	}

	private static void requireSupported(String type, WasmExportCompiler.Decl decl) {
		if (WasmExportCompiler.T_SEXPR.equals(type)) {
			throw new UnsupportedOperationException("--no-gc does not support the :sexpr export type for '"
					+ decl.name()
					+ "' (it needs a cons/reader/printer runtime; only :int/:float/:bool/:string/:void are supported)");
		}
		if (!WasmExportCompiler.T_INT.equals(type) && !WasmExportCompiler.T_FLOAT.equals(type)
				&& !WasmExportCompiler.T_BOOL.equals(type) && !WasmExportCompiler.T_STRING.equals(type)) {
			throw new UnsupportedOperationException("--no-gc supports only :int/:float/:bool/:string export types, "
					+ "got " + type + " for '" + decl.name() + "'");
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

	private static Defun extractDefun(LispVal setqLambda) {
		// (setq name (lambda (params...) body...))
		List<LispVal> parts = ((LispCons) setqLambda).toList();
		String name = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LispVal paramsVal = lambdaParts.get(1);
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

		final List<Ty> extraLocalTypes = new ArrayList<>();

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
			this.extraLocalTypes.add(ty);
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
