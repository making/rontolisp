package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OperandTypes;
import org.jspecify.annotations.Nullable;

/**
 * Compiles a {@code dotimes} whose body is a numeric loop over packed float arrays --
 * fixnum counters, {@code aref}/{@code setf aref} on packed single/double-float arrays,
 * {@code let}-bound temporaries, {@code + - * /}, the unary {@code java.lang.Math}
 * functions, binary comparisons under {@code if}/{@code when}/{@code unless},
 * {@code setq} -- into primitive {@code long}/{@code double} locals and raw
 * {@code float[]}/{@code
 * double[]} accesses, with no {@code Long}/{@code Double} allocation and no
 * {@code _add(Object, Object)} dispatch in the loop.
 *
 * <p>
 * The typed loop is a SPECULATION with a total fallback: the body is typed statically
 * (the counter is a fixnum by construction, an {@code aref} of a packed array is a
 * double, arithmetic over doubles is a double, ...), and every assumption the typing made
 * about a variable the loop reads from its environment -- this one holds a {@code Long}
 * that fits an int, that one a {@code Double}, that one a packed array -- is GUARDED once
 * at loop entry. When a guard fails the loop runs exactly as it always has (the ordinary
 * {@code expandDotimes} emission follows the guards as the bail target), so the typed
 * path can only ever be faster, never different. Where the boxed path computes in double
 * (the numeric runtime's float contagion, {@code _fvAref*} widening single-floats) the
 * typed path computes in double too, and long arithmetic is only typed where a static
 * magnitude bound proves it cannot overflow -- so the values are bit-identical, including
 * the single-float narrowing of a store. See {@code .kb/jvm-typed-loops.md}.
 */
final class JvmTypedLoopCompiler {

	/** Force-disables typed loops, for A/B profiling. */
	private static final boolean DISABLED = Boolean.getBoolean("rontolisp.debug.notypedloops");

	/**
	 * Prints every {@code dotimes} the analyzer DECLINED, with the form and the frames
	 * that threw. The {@code notypedloops} A/B says a loop is boxed; this says which form
	 * made it so, which is otherwise a bisect of the source.
	 */
	static final boolean TRACE = Boolean.getBoolean("rontolisp.debug.typedlooptrace");

	/**
	 * The highest local slot the one-byte load/store operands can name, with headroom.
	 */
	private static final int SLOT_BUDGET = 250;

	private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

	/** A guarded free fixnum fits an int: |v| <= 2^31. */
	private static final BigInteger INT_BOUND = BigInteger.ONE.shiftLeft(31);

	private static final Set<String> CMP_OPS = Set.of(LispNames.LT, LispNames.GT, LispNames.LE, LispNames.GE,
			LispNames.EQ);

	private JvmTypedLoopCompiler() {
	}

	/**
	 * Compiles the {@code dotimes} form as a guarded typed loop when its body is in the
	 * typed subset; answers false (having emitted nothing) when it is not, so the caller
	 * falls through to the ordinary expansion.
	 */
	static boolean tryCompile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (DISABLED || !ctx.typedLoops || ctx.dynamic) {
			return false;
		}
		if (JvmLispCompiler.hasComplexOperand(cons.toList())) {
			// A typed loop unboxes its numeric values as long/double; a complex
			// literal or complex/conjugate form in the loop would unbox a
			// holder (`.kb/jvm-complex.md`).
			return false;
		}
		Analysis analysis = Analyzer.analyze(cons, ctx);
		if (analysis == null) {
			return false;
		}
		return new Emitter(analysis, cons, ctx, className).emit();
	}

	// ------------------------------------------------------------------ the typed IR

	enum T {

		LONG, DOUBLE

	}

	enum Kind {

		LOOP, LET, FREE, ARRAY

	}

	/** A variable the typed loop knows: its kind, static type and emission slots. */
	static final class Var {

		final String name;

		final Kind kind;

		/** LONG or DOUBLE; null for an ARRAY. */
		final @Nullable T type;

		/** For a LONG: the proven bound |v| <= maxAbs. */
		final BigInteger maxAbs;

		/** For an ARRAY: the subscript count every access uses (1 or 2). */
		final int rank;

		/** A FREE numeric variable the loop assigns (written back at exit). */
		boolean assigned;

		/**
		 * An ARRAY the body stores into. Under {@code --gpu} such an array's device copy
		 * is dropped ONCE at loop entry rather than per store ({@code .kb/gpu.md}).
		 */
		boolean stored;

		/**
		 * A FREE DOUBLE variable that may hold a {@code Long} at run time: every use of
		 * it is a position where the boxed path converts a Long to double anyway.
		 */
		boolean acceptsLong;

		/**
		 * A FREE variable that already lives in a raw {@code double} slot -- a
		 * bound-declared float local ({@code Ctx.rawDoubleLocals},
		 * {@code .kb/jvm-double-arithmetic.md}). The slot is always authoritative, so
		 * there is nothing to read, guard, copy or write back: the typed loop uses that
		 * slot directly.
		 */
		boolean rawDouble;

		/**
		 * Emission: the typed local (2 slots for LONG/DOUBLE, the array ref for ARRAY).
		 */
		int slot = -1;

		/** Emission, ARRAY: the int local holding {@code 1 + rank} (the data offset). */
		int baseSlot = -1;

		/** Emission, ARRAY: the int local holding dimension 0, subscript 0's bound. */
		int dim0Slot = -1;

		/** Emission, ARRAY of rank 2: the int local holding the column count. */
		int colsSlot = -1;

		/** Emission, FREE: the temp holding the boxed value read at entry. */
		int refSlot = -1;

		Var(String name, Kind kind, @Nullable T type, BigInteger maxAbs, int rank) {
			this.name = name;
			this.kind = kind;
			this.type = type;
			this.maxAbs = maxAbs;
			this.rank = rank;
		}

	}

	sealed interface Node {

		/** LONG/DOUBLE for an expression, null for a statement. */
		@Nullable T type();

	}

	record Lit(T type, long l, double d, BigInteger maxAbs) implements Node {
	}

	record Ref(Var v) implements Node {
		@Override
		public T type() {
			return java.util.Objects.requireNonNull(this.v.type);
		}
	}

	/**
	 * An element read. {@code site} is the {@code aref} form's source site
	 * ({@link JvmSourceSites}), which an out-of-range index is reported at.
	 */
	record Aref(Var arr, List<Node> idx, int site) implements Node {
		@Override
		public T type() {
			return T.DOUBLE;
		}
	}

	/** {@code (length a)} of a rank-1 packed array: its one header dimension. */
	record ArrLen(Var arr) implements Node {
		@Override
		public T type() {
			return T.LONG;
		}
	}

	record Arith(String op, Node a, Node b, T type, BigInteger maxAbs) implements Node {
	}

	record Neg(Node a, T type, BigInteger maxAbs) implements Node {
	}

	record Recip(Node a) implements Node {
		@Override
		public T type() {
			return T.DOUBLE;
		}
	}

	record MathFn(String name, Node a) implements Node {
		@Override
		public T type() {
			return T.DOUBLE;
		}
	}

	record Cmp(String op, Node a, Node b, boolean negate) implements Node {
		@Override
		public @Nullable T type() {
			return null;
		}
	}

	/**
	 * An element store. {@code site} is the store form's source site, which an
	 * out-of-range index is reported at.
	 */
	record Aset(Var arr, List<Node> idx, Node value, int site) implements Node {
		@Override
		public T type() {
			return T.DOUBLE;
		}
	}

	record Setq(Var v, Node value) implements Node {
		@Override
		public T type() {
			return java.util.Objects.requireNonNull(this.v.type);
		}
	}

	record Let(List<Var> vars, List<Node> inits, List<Node> body, @Nullable T type) implements Node {
	}

	record Progn(List<Node> body, @Nullable T type) implements Node {
	}

	record If(Cmp cond, Node then, @Nullable Node els, @Nullable T type) implements Node {
	}

	record Loop(Var ctr, Node count, List<Node> body) implements Node {
		@Override
		public @Nullable T type() {
			return null;
		}
	}

	record Nop() implements Node {
		@Override
		public @Nullable T type() {
			return null;
		}
	}

	/** The analysis result: the free variables, the outer loop, the result form. */
	record Analysis(LinkedHashMap<String, Var> free, Var ctr, Node count, List<Node> body, @Nullable LispVal resultForm,
			int letDepth, int loopDepth) {
	}

	/** Thrown by the analyzer when a form is outside the typed subset. */
	private static final class Ineligible extends RuntimeException {

		static final Ineligible INSTANCE = new Ineligible(false);

		static Ineligible raise() {
			return TRACE ? new Ineligible(true) : INSTANCE;
		}

		private Ineligible(boolean trace) {
			super(null, null, false, trace);
		}

	}

	/** Thrown by the analyzer when a speculation changed and the pass must restart. */
	private static final class Restart extends RuntimeException {

		static final Restart INSTANCE = new Restart();

		private Restart() {
			super(null, null, false, false);
		}

	}

	// ------------------------------------------------------------------ analysis

	/** What the syntactic pre-scan learned about a name the loop reads from outside. */
	private static final class Spec {

		boolean indexUse;

		/** 0 = not used as an array, 1/2 = the subscript count, -1 = inconsistent. */
		int arrayRank;

		boolean assigned;

	}

	private static final class Scope {

		final Map<String, Var> vars = new HashMap<>();

		final @Nullable Scope parent;

		Scope(@Nullable Scope parent) {
			this.parent = parent;
		}

		@Nullable Var lookup(String name) {
			for (Scope s = this; s != null; s = s.parent) {
				Var v = s.vars.get(name);
				if (v != null) {
					return v;
				}
			}
			return null;
		}

	}

	private static final class Analyzer {

		private final JvmLispCompiler.Ctx ctx;

		private final Map<String, Spec> specs;

		/** Assigned non-index free variables start LONG and may promote to DOUBLE. */
		private final Map<String, T> assignedTypes = new HashMap<>();

		/** Read-only DOUBLE free variables demoted from Long-accepting to strict. */
		private final Set<String> strictDoubles = new HashSet<>();

		private LinkedHashMap<String, Var> free = new LinkedHashMap<>();

		private int letDepth;

		private int maxLetDepth;

		private int loopDepth;

		private int maxLoopDepth;

		private Analyzer(JvmLispCompiler.Ctx ctx, Map<String, Spec> specs) {
			this.ctx = ctx;
			this.specs = specs;
		}

		static @Nullable Analysis analyze(LispCons cons, JvmLispCompiler.Ctx ctx) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 2 || !(parts.get(1) instanceof LispCons spec)) {
				return null;
			}
			List<LispVal> specParts = spec.toList();
			if (specParts.size() < 2 || specParts.size() > 3 || !(specParts.get(0) instanceof LispSymbol var)) {
				return null;
			}
			LispVal resultForm = specParts.size() > 2 ? specParts.get(2) : null;
			// The result form runs after the loop with the counter bound; only a pure
			// one (a symbol, a number, nil) is taken along -- anything else keeps the
			// loop on the ordinary path, which wraps the whole thing in its %block.
			if (resultForm instanceof LispNil) {
				resultForm = null;
			}
			if (resultForm != null && !(resultForm instanceof LispSymbol || resultForm instanceof LispInteger
					|| resultForm instanceof LispDouble)) {
				return null;
			}
			Map<String, Spec> specs = new HashMap<>();
			// A name is index-shaped when it appears inside a subscript or a count, or
			// feeds one through a let binding / setq -- iterate until that set is stable.
			Set<String> longNames = new HashSet<>();
			for (int size = -1; size != longNames.size();) {
				size = longNames.size();
				specs.clear();
				scan(cons, new HashSet<>(), false, specs, longNames);
			}
			Analyzer an = new Analyzer(ctx, specs);
			for (int attempt = 0; attempt < 32; attempt++) {
				try {
					return an.run(var.name(), specParts.get(1), parts.subList(2, parts.size()), resultForm);
				}
				catch (Ineligible e) {
					if (TRACE) {
						StringBuilder sb = new StringBuilder("[typedloop] REJECT " + cons.print() + " at");
						StackTraceElement[] st = e.getStackTrace();
						for (int i = 0; i < Math.min(6, st.length); i++) {
							sb.append("\n    ").append(st[i]);
						}
						System.err.println(sb);
					}
					return null;
				}
				catch (Restart e) {
					// a speculation moved (LONG -> DOUBLE, accepting -> strict); run
					// again
				}
			}
			return null;
		}

		private Analysis run(String varName, LispVal countForm, List<LispVal> bodyForms, @Nullable LispVal resultForm) {
			this.free = new LinkedHashMap<>();
			this.letDepth = 0;
			this.maxLetDepth = 0;
			this.loopDepth = 0;
			this.maxLoopDepth = 0;
			Scope top = new Scope(null);
			Node count = exprStrict(countForm, top);
			if (count.type() != T.LONG) {
				throw Ineligible.raise();
			}
			Var ctr = new Var(varName, Kind.LOOP, T.LONG, maxAbsOf(count), 0);
			Scope loopScope = new Scope(top);
			loopScope.vars.put(varName, ctr);
			this.loopDepth = 1;
			this.maxLoopDepth = 1;
			List<Node> body = body(bodyForms, loopScope, false);
			if (resultForm instanceof LispSymbol rs && !rs.isKeyword() && !rs.name().equals(varName)
					&& !resolvable(rs.name())) {
				throw Ineligible.raise();
			}
			// A loop whose body touches no array and assigns nothing has nothing to
			// speed up that the boxed path would not do as well; keep the emission
			// byte-identical there.
			if (this.free.values().stream().noneMatch(v -> v.kind == Kind.ARRAY || v.assigned)) {
				throw Ineligible.raise();
			}
			// Every array must be consistently ranked and the program must be able to
			// produce a packed float array at all, or the guard could never pass.
			for (Var v : this.free.values()) {
				if (v.kind == Kind.ARRAY && !this.ctx.usesFloatArray) {
					throw Ineligible.raise();
				}
			}
			return new Analysis(this.free, ctr, count, body, resultForm, this.maxLetDepth, this.maxLoopDepth);
		}

		// -- the syntactic pre-scan: which outside names are indices, arrays, targets

		private static void scan(LispVal form, Set<String> bound, boolean inIndex, Map<String, Spec> specs,
				Set<String> longNames) {
			switch (form) {
				case LispSymbol s -> {
					if (s.isKeyword()) {
						return;
					}
					if (inIndex) {
						longNames.add(s.name());
					}
					if (!bound.contains(s.name())) {
						Spec sp = specs.computeIfAbsent(s.name(), k -> new Spec());
						sp.indexUse |= inIndex;
					}
				}
				case LispCons cons -> {
					if (!cons.isProperList()) {
						return;
					}
					List<LispVal> parts = cons.toList();
					String head = parts.get(0) instanceof LispSymbol hs ? hs.name() : "";
					switch (head) {
						case LispNames.AREF, LispNames.ASET -> scanArrayAccess(parts, bound, inIndex, specs, longNames);
						case LispNames.LENGTH -> {
							// (length a) reads the header, so it neither indexes A nor
							// makes A index-shaped -- without this the count of
							// `(dotimes (i (length a)) ...)` would mark the ARRAY as a
							// fixnum. A name that is nothing but a (length x) argument
							// gets no Spec and stays outside the subset.
							if (parts.size() != 2 || !(parts.get(1) instanceof LispSymbol ls) || ls.isKeyword()
									|| bound.contains(ls.name())) {
								for (int i = 1; i < parts.size(); i++) {
									scan(parts.get(i), bound, inIndex, specs, longNames);
								}
							}
						}
						case LispNames.SETF, LispNames.SETQ -> {
							for (int i = 1; i + 1 < parts.size(); i += 2) {
								LispVal place = parts.get(i);
								if (place instanceof LispSymbol ps) {
									if (!ps.isKeyword() && !bound.contains(ps.name())) {
										specs.computeIfAbsent(ps.name(), k -> new Spec()).assigned = true;
									}
								}
								else if (place instanceof LispCons pc && pc.isProperList()
										&& pc.car() instanceof LispSymbol ph && LispNames.AREF.equals(ph.name())) {
									scanArrayAccess(pc.toList(), bound, inIndex, specs, longNames);
								}
								else {
									scan(place, bound, inIndex, specs, longNames);
								}
								scan(parts.get(i + 1), bound,
										inIndex || (place instanceof LispSymbol ps2 && longNames.contains(ps2.name())),
										specs, longNames);
							}
						}
						case LispNames.DOTIMES -> {
							if (parts.size() >= 2 && parts.get(1) instanceof LispCons spec && spec.isProperList()) {
								List<LispVal> sp = spec.toList();
								if (sp.size() >= 2) {
									scan(sp.get(1), bound, true, specs, longNames);
								}
								Set<String> inner = new HashSet<>(bound);
								if (!sp.isEmpty() && sp.get(0) instanceof LispSymbol v) {
									inner.add(v.name());
								}
								for (int i = 2; i < parts.size(); i++) {
									scan(parts.get(i), inner, inIndex, specs, longNames);
								}
							}
						}
						case LispNames.LET, LispNames.LET_STAR -> {
							Set<String> inner = new HashSet<>(bound);
							boolean sequential = LispNames.LET_STAR.equals(head);
							if (parts.size() >= 2 && parts.get(1) instanceof LispCons bs && bs.isProperList()) {
								for (LispVal b : bs.toList()) {
									if (b instanceof LispCons bc && bc.isProperList()) {
										List<LispVal> bp = bc.toList();
										if (bp.size() > 1) {
											boolean idx = inIndex || (bp.get(0) instanceof LispSymbol bn0
													&& longNames.contains(bn0.name()));
											scan(bp.get(1), sequential ? inner : bound, idx, specs, longNames);
										}
										if (!bp.isEmpty() && bp.get(0) instanceof LispSymbol bn) {
											inner.add(bn.name());
										}
									}
									else if (b instanceof LispSymbol bn) {
										inner.add(bn.name());
									}
								}
							}
							for (int i = 2; i < parts.size(); i++) {
								scan(parts.get(i), inner, inIndex, specs, longNames);
							}
						}
						default -> {
							for (int i = 1; i < parts.size(); i++) {
								scan(parts.get(i), bound, inIndex, specs, longNames);
							}
						}
					}
				}
				default -> {
				}
			}
		}

		private static void scanArrayAccess(List<LispVal> parts, Set<String> bound, boolean inIndex,
				Map<String, Spec> specs, Set<String> longNames) {
			// parts: (aref a i...) or (%aset a i... value)
			boolean aset = LispNames.ASET.equals(((LispSymbol) parts.get(0)).name());
			int subscripts = parts.size() - 2 - (aset ? 1 : 0);
			if (parts.size() > 1 && parts.get(1) instanceof LispSymbol as && !bound.contains(as.name())) {
				Spec sp = specs.computeIfAbsent(as.name(), k -> new Spec());
				if (sp.arrayRank == 0) {
					sp.arrayRank = subscripts;
				}
				else if (sp.arrayRank != subscripts) {
					sp.arrayRank = -1;
				}
			}
			else if (parts.size() > 1) {
				scan(parts.get(1), bound, inIndex, specs, longNames);
			}
			for (int i = 2; i < 2 + subscripts && i < parts.size(); i++) {
				scan(parts.get(i), bound, true, specs, longNames);
			}
			if (aset && parts.size() > 2 + subscripts) {
				scan(parts.get(2 + subscripts), bound, inIndex, specs, longNames);
			}
		}

		// -- typing

		private boolean resolvable(String name) {
			return this.ctx.locals.containsKey(name) || this.ctx.captures.containsKey(name)
					|| this.ctx.globals.contains(name) || this.ctx.rawDoubleLocals.containsKey(name);
		}

		private boolean plainLocal(String name) {
			return this.ctx.locals.containsKey(name) && !this.ctx.boxedVars.contains(name)
					&& !this.ctx.specialVars.contains(name) && !this.ctx.captures.containsKey(name);
		}

		private Var freeVar(String name) {
			Var v = this.free.get(name);
			if (v != null) {
				return v;
			}
			if (!resolvable(name)) {
				if (TRACE) {
					System.err.println("[typedloop]   unresolvable free name: " + name);
				}
				throw Ineligible.raise();
			}
			Spec sp = this.specs.get(name);
			if (sp == null) {
				if (TRACE) {
					System.err.println("[typedloop]   no spec for free name: " + name);
				}
				throw Ineligible.raise();
			}
			Integer rawDoubleSlot = this.ctx.rawDoubleLocals.get(name);
			if (rawDoubleSlot != null) {
				// A bound-declared float local: already a raw double, and its slot is
				// always authoritative (no flag, no boxed shadow), so it is the EASIEST
				// free variable this loop can have -- strictly DOUBLE with no entry
				// guard, no typed copy and no write-back. It is not an index and not an
				// array; a body that uses it as one keeps the boxed emission.
				if (sp.arrayRank != 0 || sp.indexUse) {
					throw Ineligible.raise();
				}
				v = new Var(name, Kind.FREE, T.DOUBLE, BigInteger.ZERO, 0);
				v.rawDouble = true;
				v.assigned = sp.assigned;
				this.free.put(name, v);
				return v;
			}
			if (sp.arrayRank != 0) {
				if (sp.arrayRank < 0 || sp.arrayRank > 2 || sp.indexUse || sp.assigned) {
					if (TRACE) {
						System.err.println("[typedloop]   array name " + name + " rank=" + sp.arrayRank + " indexUse="
								+ sp.indexUse + " assigned=" + sp.assigned);
					}
					throw Ineligible.raise();
				}
				v = new Var(name, Kind.ARRAY, null, BigInteger.ZERO, sp.arrayRank);
			}
			else if (sp.indexUse) {
				v = new Var(name, Kind.FREE, T.LONG, INT_BOUND, 0);
			}
			else if (sp.assigned) {
				T t = this.assignedTypes.getOrDefault(name, T.LONG);
				v = new Var(name, Kind.FREE, t, t == T.LONG ? INT_BOUND : BigInteger.ZERO, 0);
			}
			else {
				v = new Var(name, Kind.FREE, T.DOUBLE, BigInteger.ZERO, 0);
				v.acceptsLong = !this.strictDoubles.contains(name);
			}
			if (sp.assigned) {
				if (!plainLocal(name)) {
					throw Ineligible.raise();
				}
				v.assigned = true;
			}
			this.free.put(name, v);
			return v;
		}

		private void demote(Var v) {
			if (v.kind == Kind.FREE && v.acceptsLong) {
				this.strictDoubles.add(v.name);
				throw Restart.INSTANCE;
			}
		}

		/**
		 * An expression whose value the boxed path would have used AS IS (no coercion).
		 */
		private Node exprStrict(LispVal form, Scope sc) {
			Node n = expr(form, sc);
			if (n instanceof Ref r) {
				demote(r.v());
			}
			return n;
		}

		/** Whether the boxed path computes this node as a Double whatever the inputs. */
		private static boolean staticDouble(Node n) {
			return switch (n) {
				case Lit l -> l.type() == T.DOUBLE;
				case Ref r -> r.v().type == T.DOUBLE && !r.v().acceptsLong;
				case Aref ignored -> true;
				case Arith a -> a.type() == T.DOUBLE && (staticDouble(a.a()) || staticDouble(a.b()));
				case Neg g -> g.type() == T.DOUBLE && staticDouble(g.a());
				case Recip ignored -> true;
				case MathFn ignored -> true;
				case Aset ignored -> true;
				case Setq s -> s.v().type == T.DOUBLE && !(s.v().kind == Kind.FREE && s.v().acceptsLong);
				case Let l -> l.type() == T.DOUBLE && staticDouble(l.body().getLast());
				case Progn p -> p.type() == T.DOUBLE && staticDouble(p.body().getLast());
				case If i -> i.type() == T.DOUBLE && staticDouble(i.then()) && i.els() != null && staticDouble(i.els());
				default -> false;
			};
		}

		private static BigInteger maxAbsOf(Node n) {
			return switch (n) {
				case Lit l -> l.maxAbs();
				case ArrLen ignored -> INT_BOUND;
				case Ref r -> r.v().maxAbs;
				case Arith a -> a.maxAbs();
				case Neg g -> g.maxAbs();
				case Setq s -> s.v().maxAbs;
				case Let l -> maxAbsOf(l.body().getLast());
				case Progn p -> maxAbsOf(p.body().getLast());
				case If i -> maxAbsOf(i.then()).max(maxAbsOf(java.util.Objects.requireNonNull(i.els())));
				default -> throw Ineligible.raise();
			};
		}

		private Node expr(LispVal form, Scope sc) {
			Node n = node(form, sc, true);
			if (n.type() == null) {
				throw Ineligible.raise();
			}
			return n;
		}

		private List<Node> body(List<LispVal> forms, Scope sc, boolean valueNeeded) {
			List<Node> out = new ArrayList<>();
			for (int i = 0; i < forms.size(); i++) {
				boolean last = i == forms.size() - 1;
				Node n = last && valueNeeded ? exprStrict(forms.get(i), sc) : node(forms.get(i), sc, false);
				out.add(n);
			}
			if (valueNeeded && out.isEmpty()) {
				throw Ineligible.raise();
			}
			return out;
		}

		private static @Nullable T lastType(List<Node> body, boolean valueNeeded) {
			return valueNeeded ? body.getLast().type() : null;
		}

		private Node node(LispVal form, Scope sc, boolean valueNeeded) {
			switch (form) {
				case LispInteger i -> {
					long v = i.value();
					return new Lit(T.LONG, v, 0,
							v == Long.MIN_VALUE ? BigInteger.ONE.shiftLeft(63) : BigInteger.valueOf(Math.abs(v)));
				}
				case LispDouble d -> {
					return new Lit(T.DOUBLE, 0, d.value(), BigInteger.ZERO);
				}
				case LispNil ignored -> {
					if (valueNeeded) {
						throw Ineligible.raise();
					}
					return new Nop();
				}
				case LispSymbol s -> {
					if (s.isKeyword()) {
						throw Ineligible.raise();
					}
					Var v = sc.lookup(s.name());
					if (v == null) {
						v = freeVar(s.name());
					}
					if (v.kind == Kind.ARRAY) {
						throw Ineligible.raise();
					}
					return new Ref(v);
				}
				case LispCons cons -> {
					return consNode(cons, sc, valueNeeded);
				}
				default -> throw Ineligible.raise();
			}
		}

		private Node consNode(LispCons cons, Scope sc, boolean valueNeeded) {
			if (!cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
				throw Ineligible.raise();
			}
			List<LispVal> parts = cons.toList();
			String name = head.name();
			switch (name) {
				case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV -> {
					int n = parts.size() - 1;
					if (n == 0) {
						throw Ineligible.raise();
					}
					if (n == 1) {
						Node a = exprStrict(parts.get(1), sc);
						return switch (name) {
							case LispNames.SUB -> {
								if (a.type() == T.LONG) {
									BigInteger m = maxAbsOf(a);
									if (m.compareTo(LONG_MAX) > 0) {
										throw Ineligible.raise();
									}
									yield new Neg(a, T.LONG, m);
								}
								yield new Neg(a, T.DOUBLE, BigInteger.ZERO);
							}
							case LispNames.DIV -> {
								if (a.type() != T.DOUBLE) {
									throw Ineligible.raise();
								}
								yield new Recip(a);
							}
							default -> a;
						};
					}
					Node acc = expr(parts.get(1), sc);
					for (int i = 2; i < parts.size(); i++) {
						Node b = expr(parts.get(i), sc);
						acc = binary(name, acc, b);
					}
					return acc;
				}
				case LispNames.LT, LispNames.GT, LispNames.LE, LispNames.GE, LispNames.EQ -> {
					// a comparison is only typed as the test of if/when/unless
					throw Ineligible.raise();
				}
				case LispNames.SQRT, LispNames.EXP, LispNames.LOG, LispNames.SIN, LispNames.COS, LispNames.TAN,
						LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH,
						LispNames.TANH -> {
					if (parts.size() != 2) {
						throw Ineligible.raise();
					}
					return new MathFn(name, expr(parts.get(1), sc));
				}
				case LispNames.LENGTH -> {
					// Only of a rank-1 array of THIS loop: the guard has already proven
					// it packed, and _fvLength answers its one header dimension for
					// exactly that shape.
					if (parts.size() != 2) {
						throw Ineligible.raise();
					}
					return new ArrLen(arrayVar(parts.get(1), sc, 1));
				}
				case LispNames.AREF -> {
					return aref(parts, sc, siteOf(cons));
				}
				case LispNames.ASET -> {
					return aset(parts, sc, siteOf(cons));
				}
				case LispNames.SETF, LispNames.SETQ -> {
					if (parts.size() < 3 || parts.size() % 2 == 0) {
						throw Ineligible.raise();
					}
					List<Node> stores = new ArrayList<>();
					for (int i = 1; i + 1 < parts.size(); i += 2) {
						LispVal place = parts.get(i);
						LispVal value = parts.get(i + 1);
						if (place instanceof LispSymbol ps) {
							stores.add(setq(ps, value, sc));
						}
						else if (LispNames.SETF.equals(name) && place instanceof LispCons pc && pc.isProperList()
								&& pc.car() instanceof LispSymbol ph && LispNames.AREF.equals(ph.name())) {
							List<LispVal> ap = new ArrayList<>(pc.toList());
							ap.add(value);
							// The store is the setf's: the place itself is never
							// evaluated.
							stores.add(aset(ap, sc, siteOf(cons)));
						}
						else {
							throw Ineligible.raise();
						}
					}
					if (stores.size() == 1) {
						return stores.get(0);
					}
					return new Progn(stores, lastType(stores, valueNeeded));
				}
				case LispNames.LET, LispNames.LET_STAR -> {
					return let(parts, sc, valueNeeded, LispNames.LET_STAR.equals(name));
				}
				case LispNames.PROGN -> {
					List<Node> body = body(parts.subList(1, parts.size()), sc, valueNeeded);
					return new Progn(body, lastType(body, valueNeeded));
				}
				case LispNames.IF -> {
					if (parts.size() < 3 || parts.size() > 4) {
						throw Ineligible.raise();
					}
					Cmp cond = cmp(parts.get(1), sc, false);
					Node then = valueNeeded ? exprStrict(parts.get(2), sc) : node(parts.get(2), sc, false);
					Node els = parts.size() > 3
							? (valueNeeded ? exprStrict(parts.get(3), sc) : node(parts.get(3), sc, false)) : null;
					if (valueNeeded) {
						if (els == null || then.type() != els.type()) {
							throw Ineligible.raise();
						}
						return new If(cond, then, els, then.type());
					}
					return new If(cond, then, els, null);
				}
				case LispNames.WHEN, LispNames.UNLESS -> {
					if (valueNeeded || parts.size() < 3) {
						throw Ineligible.raise();
					}
					Cmp cond = cmp(parts.get(1), sc, LispNames.UNLESS.equals(name));
					List<Node> body = body(parts.subList(2, parts.size()), sc, false);
					return new If(cond, new Progn(body, null), null, null);
				}
				case LispNames.DOTIMES -> {
					if (valueNeeded || parts.size() < 2 || !(parts.get(1) instanceof LispCons spec)
							|| !spec.isProperList()) {
						throw Ineligible.raise();
					}
					List<LispVal> sp = spec.toList();
					if (sp.size() < 2 || sp.size() > 3 || !(sp.get(0) instanceof LispSymbol v)
							|| (sp.size() == 3 && !(sp.get(2) instanceof LispNil))) {
						throw Ineligible.raise();
					}
					Var shadowed = sc.lookup(v.name());
					if (shadowed != null && shadowed.kind == Kind.LOOP) {
						// rebinding an enclosing counter: keep it simple
						throw Ineligible.raise();
					}
					Node count = exprStrict(sp.get(1), sc);
					if (count.type() != T.LONG) {
						throw Ineligible.raise();
					}
					Var ctr = new Var(v.name(), Kind.LOOP, T.LONG, maxAbsOf(count), 0);
					Scope inner = new Scope(sc);
					inner.vars.put(v.name(), ctr);
					this.loopDepth++;
					this.maxLoopDepth = Math.max(this.maxLoopDepth, this.loopDepth);
					List<Node> body = body(parts.subList(2, parts.size()), inner, false);
					this.loopDepth--;
					return new Loop(ctr, count, body);
				}
				case LispNames.DECLARE -> {
					if (valueNeeded) {
						throw Ineligible.raise();
					}
					return new Nop();
				}
				default -> {
					if (TRACE) {
						System.err.println("[typedloop]   form outside the subset: " + cons.print());
					}
					throw Ineligible.raise();
				}
			}
		}

		private Node binary(String op, Node a, Node b) {
			// A Long-accepting variable is only acceptable beside an operand the boxed
			// path makes a Double anyway (float contagion converts the Long then).
			if (a instanceof Ref ra && ra.v().acceptsLong && !staticDouble(b)) {
				demote(ra.v());
			}
			if (b instanceof Ref rb && rb.v().acceptsLong && !staticDouble(a)) {
				demote(rb.v());
			}
			if (a.type() == T.LONG && b.type() == T.LONG) {
				BigInteger ma = maxAbsOf(a);
				BigInteger mb = maxAbsOf(b);
				BigInteger m = switch (op) {
					case LispNames.ADD, LispNames.SUB -> ma.add(mb);
					case LispNames.MUL -> ma.multiply(mb);
					// an integer quotient is exact (a ratio) on the boxed path
					default -> throw Ineligible.raise();
				};
				if (m.compareTo(LONG_MAX) > 0) {
					throw Ineligible.raise();
				}
				return new Arith(op, a, b, T.LONG, m);
			}
			return new Arith(op, a, b, T.DOUBLE, BigInteger.ZERO);
		}

		private Cmp cmp(LispVal form, Scope sc, boolean negate) {
			if (!(form instanceof LispCons c) || !c.isProperList() || !(c.car() instanceof LispSymbol h)
					|| !CMP_OPS.contains(h.name())) {
				throw Ineligible.raise();
			}
			List<LispVal> parts = c.toList();
			if (parts.size() != 3) {
				throw Ineligible.raise();
			}
			Node a = expr(parts.get(1), sc);
			Node b = expr(parts.get(2), sc);
			if (a instanceof Ref ra && ra.v().acceptsLong && !staticDouble(b)) {
				demote(ra.v());
			}
			if (b instanceof Ref rb && rb.v().acceptsLong && !staticDouble(a)) {
				demote(rb.v());
			}
			return new Cmp(h.name(), a, b, negate);
		}

		private Var arrayVar(LispVal form, Scope sc, int rank) {
			if (!(form instanceof LispSymbol s) || s.isKeyword() || sc.lookup(s.name()) != null) {
				throw Ineligible.raise();
			}
			Var v = freeVar(s.name());
			if (v.kind != Kind.ARRAY || v.rank != rank) {
				throw Ineligible.raise();
			}
			return v;
		}

		private List<Node> subscripts(List<LispVal> parts, int from, int rank, Scope sc) {
			List<Node> idx = new ArrayList<>();
			for (int i = from; i < from + rank; i++) {
				Node n = exprStrict(parts.get(i), sc);
				if (n.type() != T.LONG) {
					throw Ineligible.raise();
				}
				idx.add(n);
			}
			return idx;
		}

		/**
		 * The source site of a form the loop body holds, under the compiling method's
		 * function; 0 when the compile records no positions or the form has none.
		 */
		private int siteOf(LispCons form) {
			JvmSourceSites table = this.ctx.sites;
			return table == null ? 0 : table.site(form, this.ctx.siteOwner);
		}

		private Node aref(List<LispVal> parts, Scope sc, int site) {
			int rank = parts.size() - 2;
			if (rank < 1 || rank > 2) {
				throw Ineligible.raise();
			}
			Var arr = arrayVar(parts.get(1), sc, rank);
			return new Aref(arr, subscripts(parts, 2, rank, sc), site);
		}

		private Node aset(List<LispVal> parts, Scope sc, int site) {
			// (%aset a i... value)
			int rank = parts.size() - 3;
			if (rank < 1 || rank > 2) {
				throw Ineligible.raise();
			}
			Var arr = arrayVar(parts.get(1), sc, rank);
			arr.stored = true;
			List<Node> idx = subscripts(parts, 2, rank, sc);
			Node value = expr(parts.get(2 + rank), sc);
			return new Aset(arr, idx, value, site);
		}

		private Node setq(LispSymbol place, LispVal valueForm, Scope sc) {
			if (place.isKeyword()) {
				throw Ineligible.raise();
			}
			Var v = sc.lookup(place.name());
			if (v == null) {
				v = freeVar(place.name());
			}
			if (v.kind == Kind.LOOP || v.kind == Kind.ARRAY) {
				throw Ineligible.raise();
			}
			Node value = exprStrict(valueForm, sc);
			if (value.type() != v.type) {
				if (v.kind == Kind.FREE && v.type == T.LONG && value.type() == T.DOUBLE) {
					this.assignedTypes.put(v.name, T.DOUBLE);
					throw Restart.INSTANCE;
				}
				throw Ineligible.raise();
			}
			if (v.type == T.LONG && maxAbsOf(value).compareTo(v.maxAbs) > 0) {
				throw Ineligible.raise();
			}
			return new Setq(v, value);
		}

		private Node let(List<LispVal> parts, Scope sc, boolean valueNeeded, boolean sequential) {
			if (parts.size() < 2) {
				throw Ineligible.raise();
			}
			Scope inner = new Scope(sc);
			List<Var> vars = new ArrayList<>();
			List<Node> inits = new ArrayList<>();
			if (parts.get(1) instanceof LispCons bs) {
				if (!bs.isProperList()) {
					throw Ineligible.raise();
				}
				for (LispVal b : bs.toList()) {
					if (!(b instanceof LispCons bc) || !bc.isProperList()) {
						throw Ineligible.raise();
					}
					List<LispVal> bp = bc.toList();
					if (bp.size() != 2 || !(bp.get(0) instanceof LispSymbol bn) || bn.isKeyword()
							|| this.ctx.specialVars.contains(bn.name())) {
						throw Ineligible.raise();
					}
					Node init = exprStrict(bp.get(1), sequential ? inner : sc);
					T t = java.util.Objects.requireNonNull(init.type());
					Var v = new Var(bn.name(), Kind.LET, t, t == T.LONG ? maxAbsOf(init) : BigInteger.ZERO, 0);
					if (inner.vars.containsKey(bn.name())) {
						throw Ineligible.raise();
					}
					inner.vars.put(bn.name(), v);
					vars.add(v);
					inits.add(init);
				}
			}
			else if (!(parts.get(1) instanceof LispNil)) {
				throw Ineligible.raise();
			}
			this.letDepth += vars.size();
			this.maxLetDepth = Math.max(this.maxLetDepth, this.letDepth);
			List<Node> body = body(parts.subList(2, parts.size()), inner, valueNeeded);
			this.letDepth -= vars.size();
			return new Let(vars, inits, body, lastType(body, valueNeeded));
		}

	}

	// ------------------------------------------------------------------ emission

	private static final class Emitter {

		private final Analysis an;

		private final LispCons cons;

		private final JvmLispCompiler.Ctx ctx;

		private final String className;

		/** The array element kind of the variant being emitted. */
		private boolean single;

		private final ClassEntry floatArrayClass;

		private final ClassEntry doubleArrayClass;

		private final @Nullable MethodRefEntry written;

		private final @Nullable MethodRefEntry materialize;

		/**
		 * The source site current where the loop is compiled -- the {@code dotimes}
		 * form's -- which every instruction but an array access reports.
		 */
		private final int loopSite;

		Emitter(Analysis an, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
			this.an = an;
			this.cons = cons;
			this.ctx = ctx;
			this.className = className;
			this.loopSite = ctx.siteCurrent;
			this.floatArrayClass = ctx.cp.classEntry("[F");
			this.doubleArrayClass = ctx.cp.classEntry("[D");
			Map<String, MethodRefEntry> gpuOps = ctx.gpuOps;
			this.written = gpuOps == null ? null : gpuOps.get(JvmGpuRuntimeBuilder.WRITTEN);
			this.materialize = gpuOps == null ? null : gpuOps.get(JvmGpuRuntimeBuilder.MATERIALIZE);
		}

		boolean emit() {
			List<Var> arrays = new ArrayList<>();
			List<Var> numbers = new ArrayList<>();
			boolean anyAssigned = false;
			for (Var v : this.an.free().values()) {
				if (v.kind == Kind.ARRAY) {
					arrays.add(v);
				}
				else {
					numbers.add(v);
					// A raw double local needs no write-back, so it needs no handler
					// either -- its slot already holds what the boxed path would have
					// left there.
					anyAssigned |= v.assigned && !v.rawDouble;
				}
			}
			// An exception handler writes the assigned variables back; entering a
			// handler discards the operand stack, so a loop nested inside an expression
			// with live operands stays on the ordinary path when it would need one.
			if (anyAssigned && !this.ctx.stack.snapshot().isEmpty()) {
				return false;
			}
			int guarded = (int) numbers.stream().filter(v -> !v.rawDouble).count();
			// + 10: the long subscript temps a store holds while an access in its value
			// holds its own, and the value's (subscripts(), aset()).
			int needed = guarded * 3 + 1 + arrays.size() * 5 + this.an.loopDepth() * 4 + this.an.letDepth() * 2 + 14;
			if (this.ctx.nextLocal + needed > SLOT_BUDGET) {
				return false;
			}
			int savedNextLocal = this.ctx.nextLocal;
			MethodCode.Label bails = this.ctx.body.newLabel();
			// 1. read and guard every free variable
			for (Var v : numbers) {
				if (v.rawDouble) {
					// Nothing to read and nothing to guard: the loop reads and writes
					// the program's own raw slot in place, so an exception mid-loop
					// leaves exactly what the boxed emission would have left.
					v.slot = java.util.Objects.requireNonNull(this.ctx.rawDoubleLocals.get(v.name));
					continue;
				}
				v.refSlot = this.ctx.allocTemp();
				JvmExprCompiler.compileSymbolRef(new LispSymbol(v.name), this.ctx);
				this.ctx.body.astore(v.refSlot);
				v.slot = allocWide();
				if (v.type == T.LONG) {
					guardLong(v, bails);
				}
				else if (v.acceptsLong) {
					guardDoubleOrLong(v, bails);
				}
				else {
					guardDouble(v, bails);
				}
			}
			for (Var v : arrays) {
				v.refSlot = this.ctx.allocTemp();
				JvmExprCompiler.compileSymbolRef(new LispSymbol(v.name), this.ctx);
				this.ctx.body.astore(v.refSlot);
				v.slot = this.ctx.allocTemp();
				v.baseSlot = this.ctx.allocTemp();
				v.dim0Slot = this.ctx.allocTemp();
				if (v.rank == 2) {
					v.colsSlot = this.ctx.allocTemp();
				}
			}
			MethodCode.Label joins = this.ctx.body.newLabel();
			int excSlot = anyAssigned ? this.ctx.allocTemp() : -1;
			if (arrays.isEmpty()) {
				this.single = false;
				variant(numbers, joins, excSlot);
			}
			else {
				// 2. all arrays float[] -> the single variant; all double[] -> the
				// double variant; anything else -> the boxed path
				MethodCode.Label notSingle = this.ctx.body.newLabel();
				for (Var v : arrays) {
					this.ctx.body.aload(v.refSlot).instanceOf(this.floatArrayClass);
					this.ctx.body.ifeq(notSingle);
				}
				this.single = true;
				hoistArrays(arrays);
				variant(numbers, joins, excSlot);
				this.ctx.body.labelBinding(notSingle);
				for (Var v : arrays) {
					this.ctx.body.aload(v.refSlot).instanceOf(this.doubleArrayClass);
					this.ctx.body.ifeq(bails);
				}
				this.single = false;
				hoistArrays(arrays);
				variant(numbers, joins, excSlot);
			}
			// 3. the boxed path: exactly what the loop compiled to before
			this.ctx.body.labelBinding(bails);
			this.ctx.nextLocal = savedNextLocal;
			JvmExprCompiler.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.expandDotimes(this.cons), this.ctx,
					this.className);
			this.ctx.body.labelBinding(joins);
			return true;
		}

		private int allocWide() {
			int slot = this.ctx.allocTemp();
			this.ctx.allocTemp();
			return slot;
		}

		private void guardLong(Var v, MethodCode.Label bails) {
			this.ctx.body.aload(v.refSlot).instanceOf(this.ctx.longClass).ifeq(bails);
			this.ctx.body.aload(v.refSlot).checkcast(this.ctx.longClass);
			this.ctx.body.invokevirtual(this.ctx.longValue).lstore(v.slot);
			// the magnitude bound the typing relies on: the value fits an int
			this.ctx.body.lload(v.slot).l2i().i2l().lload(v.slot).lcmp().ifne(bails);
		}

		private void guardDouble(Var v, MethodCode.Label bails) {
			this.ctx.body.aload(v.refSlot).instanceOf(this.ctx.doubleClass).ifeq(bails);
			unboxDoubleInto(v);
		}

		private void guardDoubleOrLong(Var v, MethodCode.Label bails) {
			this.ctx.body.aload(v.refSlot).instanceOf(this.ctx.doubleClass);
			MethodCode.Label notDouble = this.ctx.body.newLabel();
			MethodCode.Label done = this.ctx.body.newLabel();
			this.ctx.body.ifeq(notDouble);
			unboxDoubleInto(v);
			this.ctx.body.goto_(done);
			this.ctx.body.labelBinding(notDouble);
			this.ctx.body.aload(v.refSlot).instanceOf(this.ctx.longClass).ifeq(bails);
			this.ctx.body.aload(v.refSlot).checkcast(this.ctx.longClass);
			this.ctx.body.invokevirtual(this.ctx.longValue).l2d().dstore(v.slot);
			this.ctx.body.labelBinding(done);
		}

		private void unboxDoubleInto(Var v) {
			this.ctx.body.aload(v.refSlot).checkcast(this.ctx.doubleClass);
			this.ctx.body.invokevirtual(this.ctx.numberDoubleValue).dstore(v.slot);
		}

		/**
		 * Casts every array into its typed slot and reads its header once. The arrays are
		 * loop-invariant, so under {@code --gpu} this is also where each is materialized
		 * -- the raw {@code faload}s in the body read the host's bytes, which must be the
		 * device's first -- and where each array the body STORES into is reported written
		 * ({@code .kb/gpu.md}). Nothing inside a typed loop can put an array back on the
		 * device (the subset has no calls), so one report at entry covers every store the
		 * loop makes; a loop of zero trips drops a copy it did not need to, which costs
		 * speed and nothing else.
		 */
		private void hoistArrays(List<Var> arrays) {
			ClassEntry cls = this.single ? this.floatArrayClass : this.doubleArrayClass;
			for (Var v : arrays) {
				this.ctx.body.aload(v.refSlot);
				if (this.materialize != null) {
					// The typed slot takes what the guard answers: the array, or a
					// result stub's backing. The variable's own slot keeps the program's
					// object, which is what the body's aset reports as written.
					this.ctx.body.invokestatic(this.materialize);
				}
				this.ctx.body.checkcast(cls).astore(v.slot);
				// base = 1 + rank, the data offset (_fvAref*'s `1 + rank`)
				this.ctx.body.iconst_1().aload(v.slot).iconst_0();
				loadHeaderInt();
				this.ctx.body.iadd().istore(v.baseSlot);
				// dimension 0 from the header, as _fvAref* bound subscript 0 by it
				this.ctx.body.aload(v.slot).iconst_1();
				loadHeaderInt();
				this.ctx.body.istore(v.dim0Slot);
				if (v.rank == 2) {
					this.ctx.body.aload(v.slot).iconst_2();
					loadHeaderInt();
					this.ctx.body.istore(v.colsSlot);
				}
			}
			// After EVERY materialize, not interleaved with them: two of the loop's
			// arrays can be one object at run time, and dropping a device copy before
			// the other one's materialize would leave that read with nothing to bring
			// home. Reported on the program's object (refSlot), not the typed slot,
			// which may be a result stub's backing the library does not key on.
			if (this.written != null) {
				for (Var v : arrays) {
					if (v.stored) {
						this.ctx.body.aload(v.refSlot).invokestatic(this.written).pop();
					}
				}
			}
		}

		private void loadHeaderInt() {
			if (this.single) {
				this.ctx.body.faload().f2i();
			}
			else {
				this.ctx.body.daload().d2i();
			}
		}

		/**
		 * One typed variant: the loop, the write-back of the assigned variables, the
		 * result value, and (when anything is written back) the handler that writes back
		 * and rethrows.
		 */
		private void variant(List<Var> numbers, MethodCode.Label joins, int excSlot) {
			int savedNextLocal = this.ctx.nextLocal;
			MethodCode.Label start = this.ctx.body.newBoundLabel();
			loop(this.an.ctr(), this.an.count(), this.an.body());
			MethodCode.Label end = this.ctx.body.newBoundLabel();
			List<Var> assigned = numbers.stream().filter(v -> v.assigned && !v.rawDouble).toList();
			writeBack(assigned);
			// the value: nil, or the result form with the counter bound to its final
			// value
			LispVal resultForm = this.an.resultForm();
			if (resultForm == null) {
				this.ctx.body.aconst_null();
			}
			else {
				Map<String, Integer> savedLocals = new HashMap<>(this.ctx.locals);
				Set<String> savedBoxed = this.ctx.boxedVars;
				this.ctx.boxedVars = new HashSet<>(this.ctx.boxedVars);
				int slot = this.ctx.allocLocal(this.an.ctr().name);
				this.ctx.boxedVars.remove(this.an.ctr().name);
				this.ctx.body.lload(this.an.ctr().slot).invokestatic(this.ctx.longValueOf);
				this.ctx.body.astore(slot);
				JvmExprCompiler.compileExpr(resultForm, this.ctx, this.className);
				this.ctx.locals = savedLocals;
				this.ctx.boxedVars = savedBoxed;
			}
			this.ctx.body.goto_(joins);
			if (!assigned.isEmpty() && start.position() < end.position()) {
				MethodCode.Label handler = this.ctx.body.newBoundLabel();
				this.ctx.stack.enterHandler();
				this.ctx.body.astore(excSlot);
				writeBack(assigned);
				this.ctx.body.aload(excSlot).athrow();
				this.ctx.body.exceptionCatch(start, end, handler, null);
			}
			this.ctx.nextLocal = savedNextLocal;
		}

		private void writeBack(List<Var> assigned) {
			for (Var v : assigned) {
				int boxedSlot = java.util.Objects.requireNonNull(this.ctx.locals.get(v.name));
				if (v.type == T.LONG) {
					this.ctx.body.lload(v.slot).invokestatic(this.ctx.longValueOf);
				}
				else {
					this.ctx.body.dload(v.slot).invokestatic(this.ctx.doubleValueOf);
				}
				this.ctx.body.astore(boxedSlot);
			}
		}

		private void loop(Var ctr, Node count, List<Node> body) {
			int savedNextLocal = this.ctx.nextLocal;
			int limSlot = allocWide();
			ctr.slot = allocWide();
			expr(count);
			this.ctx.body.lstore(limSlot).lconst_0().lstore(ctr.slot);
			MethodCode.Label loopStart = this.ctx.body.newBoundLabel();
			MethodCode.Label exit = this.ctx.body.newLabel();
			this.ctx.body.lload(ctr.slot).lload(limSlot).lcmp().ifge(exit);
			for (Node n : body) {
				stmt(n);
			}
			this.ctx.body.lload(ctr.slot).lconst_1().ladd().lstore(ctr.slot).goto_(loopStart);
			this.ctx.body.labelBinding(exit);
			this.ctx.nextLocal = savedNextLocal;
		}

		private void stmt(Node n) {
			switch (n) {
				case Nop ignored -> {
				}
				case Progn p -> p.body().forEach(this::stmt);
				case Let l -> let(l, false);
				case If i -> ifStmt(i);
				case Loop l -> loop(l.ctr(), l.count(), l.body());
				case Setq s -> {
					expr(s.value());
					store(s.v());
				}
				case Aset a -> aset(a, false);
				case Cmp ignored -> throw new IllegalStateException("a bare comparison is not a typed statement");
				default -> {
					expr(n);
					this.ctx.body.pop2();
				}
			}
		}

		/** Emits the expression, leaving its long or double on the operand stack. */
		private void expr(Node n) {
			switch (n) {
				case Lit l -> {
					if (l.type() == T.LONG) {
						JvmEmitHelper.emitRawLong(l.l(), this.ctx);
					}
					else {
						JvmEmitHelper.emitRawDouble(l.d(), this.ctx);
					}
				}
				case Ref r -> load(r.v());
				case ArrLen l -> {
					// _fvLength of a rank-1 packed array: header dimension 0, read from
					// the header rather than the Java length (a lazy result stub is the
					// header alone).
					this.ctx.body.aload(l.arr().slot).iconst_1();
					loadHeaderInt();
					this.ctx.body.i2l();
				}
				case Aref a -> {
					// An index out of range fails in its bound check: it reports the
					// aref,
					// by the name the boxed accessor's wrapper gives it.
					int saved = this.ctx.nextLocal;
					int[] subs = subscripts(a.idx());
					atSite(a.site());
					arrayIndex(a.arr(), subs, LispNames.AREF);
					this.ctx.nextLocal = saved;
					if (this.single) {
						this.ctx.body.faload().f2d();
					}
					else {
						this.ctx.body.daload();
					}
					this.ctx.restoreSite(this.loopSite);
				}
				case Arith a -> {
					if (a.type() == T.LONG) {
						expr(a.a());
						expr(a.b());
						switch (a.op()) {
							case LispNames.ADD -> this.ctx.body.ladd();
							case LispNames.SUB -> this.ctx.body.lsub();
							default -> this.ctx.body.lmul();
						}
					}
					else {
						exprAsDouble(a.a());
						exprAsDouble(a.b());
						switch (a.op()) {
							case LispNames.ADD -> this.ctx.body.dadd();
							case LispNames.SUB -> this.ctx.body.dsub();
							case LispNames.MUL -> this.ctx.body.dmul();
							default -> this.ctx.body.ddiv();
						}
					}
				}
				case Neg g -> {
					expr(g.a());
					if (g.type() == T.LONG) {
						this.ctx.body.lneg();
					}
					else {
						this.ctx.body.dneg();
					}
				}
				case Recip r -> {
					this.ctx.body.dconst_1();
					expr(r.a());
					this.ctx.body.ddiv();
				}
				case MathFn m -> {
					exprAsDouble(m.a());
					this.ctx.body.invokestatic(this.ctx.mathOp(m.name()));
				}
				case Aset a -> aset(a, true);
				case Setq s -> {
					expr(s.value());
					this.ctx.body.dup2();
					store(s.v());
				}
				case Let l -> let(l, true);
				case Progn p -> {
					for (int i = 0; i < p.body().size() - 1; i++) {
						stmt(p.body().get(i));
					}
					expr(p.body().getLast());
				}
				case If i -> {
					MethodCode.Label falses = cond(i.cond());
					expr(i.then());
					MethodCode.Label end = this.ctx.body.newLabel();
					this.ctx.body.goto_(end);
					this.ctx.body.labelBinding(falses);
					expr(java.util.Objects.requireNonNull(i.els()));
					this.ctx.body.labelBinding(end);
				}
				default -> throw new IllegalStateException("not a typed expression: " + n);
			}
		}

		private void exprAsDouble(Node n) {
			expr(n);
			if (n.type() == T.LONG) {
				this.ctx.body.l2d();
			}
		}

		private void load(Var v) {
			this.ctx.body.loadLocal(v.type == T.LONG ? TypeKind.LONG : TypeKind.DOUBLE, v.slot);
		}

		private void store(Var v) {
			this.ctx.body.storeLocal(v.type == T.LONG ? TypeKind.LONG : TypeKind.DOUBLE, v.slot);
		}

		/**
		 * Evaluates the subscripts, in order, into fresh long temps -- every one before
		 * any bound is checked, as the boxed access evaluates its arguments before its
		 * accessor runs.
		 */
		private int[] subscripts(List<Node> idx) {
			int[] slots = new int[idx.size()];
			for (int k = 0; k < slots.length; k++) {
				expr(idx.get(k));
				slots[k] = allocWide();
				this.ctx.body.lstore(slots[k]);
			}
			return slots;
		}

		/**
		 * Leaves {@code (arrayref, int index)} on the stack, the helpers' arithmetic:
		 * each subscript checked against its own dimension through {@code _ckBoundJ}
		 * under the access's wrapper, so an out-of-range one throws the report the boxed
		 * accessor throws ({@code JvmOperandTypeRuntime}).
		 */
		private void arrayIndex(Var arr, int[] subs, String operator) {
			this.ctx.body.aload(arr.slot).iload(arr.baseSlot);
			bounded(subs[0], arr.dim0Slot, operator);
			if (subs.length == 2) {
				this.ctx.body.iload(arr.colsSlot).imul().iadd();
				bounded(subs[1], arr.colsSlot, operator);
			}
			this.ctx.body.iadd();
		}

		/**
		 * Pushes the long subscript in {@code slot} checked against the int {@code dim}.
		 */
		private void bounded(int slot, int dimSlot, String operator) {
			this.ctx.body.lload(slot).iload(dimSlot);
			@Nullable String outer = this.ctx.operator;
			this.ctx.operator = operator;
			try {
				this.ctx.body.invokestatic(this.ctx.wrapForOperator(JvmOperandTypeRuntime.CK_BOUND_J,
						JvmOperandTypeRuntime.CK_BOUND_J_DESC,
						java.util.Objects.requireNonNull(this.ctx.numOps.get(JvmOperandTypeRuntime.CK_BOUND_J))));
			}
			finally {
				this.ctx.operator = outer;
			}
		}

		/** Makes the next instruction report {@code site}, the loop's own without one. */
		private void atSite(int site) {
			this.ctx.restoreSite(site == 0 ? this.loopSite : site);
		}

		private void aset(Aset a, boolean valueNeeded) {
			// No --gpu guard here: hoistArrays already reported the array written, once
			// for the whole loop. The subscripts and the value are evaluated before any
			// bound is checked, as the boxed %aset evaluates its arguments before its
			// store helper runs; an out-of-range one reports the store's form.
			int saved = this.ctx.nextLocal;
			int[] subs = subscripts(a.idx());
			exprAsDouble(a.value());
			int tmp = allocWide();
			this.ctx.body.dstore(tmp);
			atSite(a.site());
			arrayIndex(a.arr(), subs, OperandTypes.SETF_AREF);
			this.ctx.body.dload(tmp);
			if (this.single) {
				this.ctx.body.d2f().fastore();
			}
			else {
				this.ctx.body.dastore();
			}
			this.ctx.restoreSite(this.loopSite);
			if (valueNeeded) {
				// the value of a store is the value AS STORED (narrowed for single)
				this.ctx.body.dload(tmp);
				if (this.single) {
					this.ctx.body.d2f().f2d();
				}
			}
			this.ctx.nextLocal = saved;
		}

		private void let(Let l, boolean valueNeeded) {
			int savedNextLocal = this.ctx.nextLocal;
			for (int i = 0; i < l.vars().size(); i++) {
				Var v = l.vars().get(i);
				expr(l.inits().get(i));
				v.slot = allocWide();
				store(v);
			}
			for (int i = 0; i < l.body().size(); i++) {
				boolean last = i == l.body().size() - 1;
				if (last && valueNeeded) {
					expr(l.body().get(i));
				}
				else {
					stmt(l.body().get(i));
				}
			}
			this.ctx.nextLocal = savedNextLocal;
		}

		private void ifStmt(If i) {
			MethodCode.Label falses = cond(i.cond());
			stmt(i.then());
			if (i.els() == null) {
				this.ctx.body.labelBinding(falses);
				return;
			}
			MethodCode.Label end = this.ctx.body.newLabel();
			this.ctx.body.goto_(end);
			this.ctx.body.labelBinding(falses);
			stmt(i.els());
			this.ctx.body.labelBinding(end);
		}

		/**
		 * Emits the comparison as a conditional branch taken when the test is FALSE
		 * (after negation), answering the false target's label for the caller to bind.
		 */
		private MethodCode.Label cond(Cmp c) {
			boolean longs = c.a().type() == T.LONG && c.b().type() == T.LONG;
			String op = c.op();
			if (longs) {
				expr(c.a());
				expr(c.b());
				this.ctx.body.lcmp();
			}
			else {
				exprAsDouble(c.a());
				exprAsDouble(c.b());
				// javac's rule, which _cmpb's bitmask reproduces: NaN must fail every
				// operator, so < and <= compare with DCMPG (NaN -> +1) and the others
				// with DCMPL (NaN -> -1)
				// with DCMPL (NaN -> -1). A negated test (unless) keeps the SAME compare
				// and flips the jump: "not (a < b)" is true for NaN on both paths.
				boolean g = LispNames.LT.equals(op) || LispNames.LE.equals(op);
				if (g) {
					this.ctx.body.dcmpg();
				}
				else {
					this.ctx.body.dcmpl();
				}
			}
			// the branch skips the body when the test (as written) is false
			Opcode whenFalse = switch (op) {
				case LispNames.LT -> Opcode.IFGE;
				case LispNames.LE -> Opcode.IFGT;
				case LispNames.GT -> Opcode.IFLE;
				case LispNames.GE -> Opcode.IFLT;
				default -> Opcode.IFNE;
			};
			Opcode whenTrue = switch (op) {
				case LispNames.LT -> Opcode.IFLT;
				case LispNames.LE -> Opcode.IFLE;
				case LispNames.GT -> Opcode.IFGT;
				case LispNames.GE -> Opcode.IFGE;
				default -> Opcode.IFEQ;
			};
			MethodCode.Label out = this.ctx.body.newLabel();
			this.ctx.body.branch(c.negate() ? whenTrue : whenFalse, out);
			return out;
		}

	}

}
