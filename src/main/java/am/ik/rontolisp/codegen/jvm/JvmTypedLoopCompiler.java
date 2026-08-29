package am.ik.rontolisp.codegen.jvm;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
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

	record Aref(Var arr, List<Node> idx) implements Node {
		@Override
		public T type() {
			return T.DOUBLE;
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

	record Aset(Var arr, List<Node> idx, Node value) implements Node {
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

		static final Ineligible INSTANCE = new Ineligible();

		private Ineligible() {
			super(null, null, false, false);
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
				throw Ineligible.INSTANCE;
			}
			Var ctr = new Var(varName, Kind.LOOP, T.LONG, maxAbsOf(count), 0);
			Scope loopScope = new Scope(top);
			loopScope.vars.put(varName, ctr);
			this.loopDepth = 1;
			this.maxLoopDepth = 1;
			List<Node> body = body(bodyForms, loopScope, false);
			if (resultForm instanceof LispSymbol rs && !rs.isKeyword() && !rs.name().equals(varName)
					&& !resolvable(rs.name())) {
				throw Ineligible.INSTANCE;
			}
			// A loop whose body touches no array and assigns nothing has nothing to
			// speed up that the boxed path would not do as well; keep the emission
			// byte-identical there.
			if (this.free.values().stream().noneMatch(v -> v.kind == Kind.ARRAY || v.assigned)) {
				throw Ineligible.INSTANCE;
			}
			// Every array must be consistently ranked and the program must be able to
			// produce a packed float array at all, or the guard could never pass.
			for (Var v : this.free.values()) {
				if (v.kind == Kind.ARRAY && !this.ctx.usesFloatArray) {
					throw Ineligible.INSTANCE;
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
				throw Ineligible.INSTANCE;
			}
			Spec sp = this.specs.get(name);
			if (sp == null) {
				throw Ineligible.INSTANCE;
			}
			Integer rawDoubleSlot = this.ctx.rawDoubleLocals.get(name);
			if (rawDoubleSlot != null) {
				// A bound-declared float local: already a raw double, and its slot is
				// always authoritative (no flag, no boxed shadow), so it is the EASIEST
				// free variable this loop can have -- strictly DOUBLE with no entry
				// guard, no typed copy and no write-back. It is not an index and not an
				// array; a body that uses it as one keeps the boxed emission.
				if (sp.arrayRank != 0 || sp.indexUse) {
					throw Ineligible.INSTANCE;
				}
				v = new Var(name, Kind.FREE, T.DOUBLE, BigInteger.ZERO, 0);
				v.rawDouble = true;
				v.assigned = sp.assigned;
				this.free.put(name, v);
				return v;
			}
			if (sp.arrayRank != 0) {
				if (sp.arrayRank < 0 || sp.arrayRank > 2 || sp.indexUse || sp.assigned) {
					throw Ineligible.INSTANCE;
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
					throw Ineligible.INSTANCE;
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
				case Ref r -> r.v().maxAbs;
				case Arith a -> a.maxAbs();
				case Neg g -> g.maxAbs();
				case Setq s -> s.v().maxAbs;
				case Let l -> maxAbsOf(l.body().getLast());
				case Progn p -> maxAbsOf(p.body().getLast());
				case If i -> maxAbsOf(i.then()).max(maxAbsOf(java.util.Objects.requireNonNull(i.els())));
				default -> throw Ineligible.INSTANCE;
			};
		}

		private Node expr(LispVal form, Scope sc) {
			Node n = node(form, sc, true);
			if (n.type() == null) {
				throw Ineligible.INSTANCE;
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
				throw Ineligible.INSTANCE;
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
						throw Ineligible.INSTANCE;
					}
					return new Nop();
				}
				case LispSymbol s -> {
					if (s.isKeyword()) {
						throw Ineligible.INSTANCE;
					}
					Var v = sc.lookup(s.name());
					if (v == null) {
						v = freeVar(s.name());
					}
					if (v.kind == Kind.ARRAY) {
						throw Ineligible.INSTANCE;
					}
					return new Ref(v);
				}
				case LispCons cons -> {
					return consNode(cons, sc, valueNeeded);
				}
				default -> throw Ineligible.INSTANCE;
			}
		}

		private Node consNode(LispCons cons, Scope sc, boolean valueNeeded) {
			if (!cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
				throw Ineligible.INSTANCE;
			}
			List<LispVal> parts = cons.toList();
			String name = head.name();
			switch (name) {
				case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.DIV -> {
					int n = parts.size() - 1;
					if (n == 0) {
						throw Ineligible.INSTANCE;
					}
					if (n == 1) {
						Node a = exprStrict(parts.get(1), sc);
						return switch (name) {
							case LispNames.SUB -> {
								if (a.type() == T.LONG) {
									BigInteger m = maxAbsOf(a);
									if (m.compareTo(LONG_MAX) > 0) {
										throw Ineligible.INSTANCE;
									}
									yield new Neg(a, T.LONG, m);
								}
								yield new Neg(a, T.DOUBLE, BigInteger.ZERO);
							}
							case LispNames.DIV -> {
								if (a.type() != T.DOUBLE) {
									throw Ineligible.INSTANCE;
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
					throw Ineligible.INSTANCE;
				}
				case LispNames.SQRT, LispNames.EXP, LispNames.LOG, LispNames.SIN, LispNames.COS, LispNames.TAN,
						LispNames.ASIN, LispNames.ACOS, LispNames.ATAN, LispNames.SINH, LispNames.COSH,
						LispNames.TANH -> {
					if (parts.size() != 2) {
						throw Ineligible.INSTANCE;
					}
					return new MathFn(name, expr(parts.get(1), sc));
				}
				case LispNames.AREF -> {
					return aref(parts, sc);
				}
				case LispNames.ASET -> {
					return aset(parts, sc);
				}
				case LispNames.SETF, LispNames.SETQ -> {
					if (parts.size() < 3 || parts.size() % 2 == 0) {
						throw Ineligible.INSTANCE;
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
							stores.add(aset(ap, sc));
						}
						else {
							throw Ineligible.INSTANCE;
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
						throw Ineligible.INSTANCE;
					}
					Cmp cond = cmp(parts.get(1), sc, false);
					Node then = valueNeeded ? exprStrict(parts.get(2), sc) : node(parts.get(2), sc, false);
					Node els = parts.size() > 3
							? (valueNeeded ? exprStrict(parts.get(3), sc) : node(parts.get(3), sc, false)) : null;
					if (valueNeeded) {
						if (els == null || then.type() != els.type()) {
							throw Ineligible.INSTANCE;
						}
						return new If(cond, then, els, then.type());
					}
					return new If(cond, then, els, null);
				}
				case LispNames.WHEN, LispNames.UNLESS -> {
					if (valueNeeded || parts.size() < 3) {
						throw Ineligible.INSTANCE;
					}
					Cmp cond = cmp(parts.get(1), sc, LispNames.UNLESS.equals(name));
					List<Node> body = body(parts.subList(2, parts.size()), sc, false);
					return new If(cond, new Progn(body, null), null, null);
				}
				case LispNames.DOTIMES -> {
					if (valueNeeded || parts.size() < 2 || !(parts.get(1) instanceof LispCons spec)
							|| !spec.isProperList()) {
						throw Ineligible.INSTANCE;
					}
					List<LispVal> sp = spec.toList();
					if (sp.size() < 2 || sp.size() > 3 || !(sp.get(0) instanceof LispSymbol v)
							|| (sp.size() == 3 && !(sp.get(2) instanceof LispNil))) {
						throw Ineligible.INSTANCE;
					}
					Var shadowed = sc.lookup(v.name());
					if (shadowed != null && shadowed.kind == Kind.LOOP) {
						// rebinding an enclosing counter: keep it simple
						throw Ineligible.INSTANCE;
					}
					Node count = exprStrict(sp.get(1), sc);
					if (count.type() != T.LONG) {
						throw Ineligible.INSTANCE;
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
						throw Ineligible.INSTANCE;
					}
					return new Nop();
				}
				default -> throw Ineligible.INSTANCE;
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
					default -> throw Ineligible.INSTANCE;
				};
				if (m.compareTo(LONG_MAX) > 0) {
					throw Ineligible.INSTANCE;
				}
				return new Arith(op, a, b, T.LONG, m);
			}
			return new Arith(op, a, b, T.DOUBLE, BigInteger.ZERO);
		}

		private Cmp cmp(LispVal form, Scope sc, boolean negate) {
			if (!(form instanceof LispCons c) || !c.isProperList() || !(c.car() instanceof LispSymbol h)
					|| !CMP_OPS.contains(h.name())) {
				throw Ineligible.INSTANCE;
			}
			List<LispVal> parts = c.toList();
			if (parts.size() != 3) {
				throw Ineligible.INSTANCE;
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
				throw Ineligible.INSTANCE;
			}
			Var v = freeVar(s.name());
			if (v.kind != Kind.ARRAY || v.rank != rank) {
				throw Ineligible.INSTANCE;
			}
			return v;
		}

		private List<Node> subscripts(List<LispVal> parts, int from, int rank, Scope sc) {
			List<Node> idx = new ArrayList<>();
			for (int i = from; i < from + rank; i++) {
				Node n = exprStrict(parts.get(i), sc);
				if (n.type() != T.LONG) {
					throw Ineligible.INSTANCE;
				}
				idx.add(n);
			}
			return idx;
		}

		private Node aref(List<LispVal> parts, Scope sc) {
			int rank = parts.size() - 2;
			if (rank < 1 || rank > 2) {
				throw Ineligible.INSTANCE;
			}
			Var arr = arrayVar(parts.get(1), sc, rank);
			return new Aref(arr, subscripts(parts, 2, rank, sc));
		}

		private Node aset(List<LispVal> parts, Scope sc) {
			// (%aset a i... value)
			int rank = parts.size() - 3;
			if (rank < 1 || rank > 2) {
				throw Ineligible.INSTANCE;
			}
			Var arr = arrayVar(parts.get(1), sc, rank);
			List<Node> idx = subscripts(parts, 2, rank, sc);
			Node value = expr(parts.get(2 + rank), sc);
			return new Aset(arr, idx, value);
		}

		private Node setq(LispSymbol place, LispVal valueForm, Scope sc) {
			if (place.isKeyword()) {
				throw Ineligible.INSTANCE;
			}
			Var v = sc.lookup(place.name());
			if (v == null) {
				v = freeVar(place.name());
			}
			if (v.kind == Kind.LOOP || v.kind == Kind.ARRAY) {
				throw Ineligible.INSTANCE;
			}
			Node value = exprStrict(valueForm, sc);
			if (value.type() != v.type) {
				if (v.kind == Kind.FREE && v.type == T.LONG && value.type() == T.DOUBLE) {
					this.assignedTypes.put(v.name, T.DOUBLE);
					throw Restart.INSTANCE;
				}
				throw Ineligible.INSTANCE;
			}
			if (v.type == T.LONG && maxAbsOf(value).compareTo(v.maxAbs) > 0) {
				throw Ineligible.INSTANCE;
			}
			return new Setq(v, value);
		}

		private Node let(List<LispVal> parts, Scope sc, boolean valueNeeded, boolean sequential) {
			if (parts.size() < 2) {
				throw Ineligible.INSTANCE;
			}
			Scope inner = new Scope(sc);
			List<Var> vars = new ArrayList<>();
			List<Node> inits = new ArrayList<>();
			if (parts.get(1) instanceof LispCons bs) {
				if (!bs.isProperList()) {
					throw Ineligible.INSTANCE;
				}
				for (LispVal b : bs.toList()) {
					if (!(b instanceof LispCons bc) || !bc.isProperList()) {
						throw Ineligible.INSTANCE;
					}
					List<LispVal> bp = bc.toList();
					if (bp.size() != 2 || !(bp.get(0) instanceof LispSymbol bn) || bn.isKeyword()
							|| this.ctx.specialVars.contains(bn.name())) {
						throw Ineligible.INSTANCE;
					}
					Node init = exprStrict(bp.get(1), sequential ? inner : sc);
					T t = java.util.Objects.requireNonNull(init.type());
					Var v = new Var(bn.name(), Kind.LET, t, t == T.LONG ? maxAbsOf(init) : BigInteger.ZERO, 0);
					if (inner.vars.containsKey(bn.name())) {
						throw Ineligible.INSTANCE;
					}
					inner.vars.put(bn.name(), v);
					vars.add(v);
					inits.add(init);
				}
			}
			else if (!(parts.get(1) instanceof LispNil)) {
				throw Ineligible.INSTANCE;
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

		private final ClassConstant floatArrayClass;

		private final ClassConstant doubleArrayClass;

		private final @Nullable MethodrefConstant written;

		private final @Nullable MethodrefConstant materialize;

		Emitter(Analysis an, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
			this.an = an;
			this.cons = cons;
			this.ctx = ctx;
			this.className = className;
			this.floatArrayClass = ctx.cp.addClass(ctx.cp.addUtf8("[F"));
			this.doubleArrayClass = ctx.cp.addClass(ctx.cp.addUtf8("[D"));
			Map<String, MethodrefConstant> gpuOps = ctx.gpuOps;
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
			int needed = guarded * 3 + 1 + arrays.size() * 4 + this.an.loopDepth() * 4 + this.an.letDepth() * 2 + 4;
			if (this.ctx.nextLocal + needed > SLOT_BUDGET) {
				return false;
			}
			int savedNextLocal = this.ctx.nextLocal;
			List<Integer> bails = new ArrayList<>();
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
				this.ctx.emit(Opcode.ASTORE);
				this.ctx.emit(v.refSlot);
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
				this.ctx.emit(Opcode.ASTORE);
				this.ctx.emit(v.refSlot);
				v.slot = this.ctx.allocTemp();
				v.baseSlot = this.ctx.allocTemp();
				if (v.rank == 2) {
					v.colsSlot = this.ctx.allocTemp();
				}
			}
			List<Integer> joins = new ArrayList<>();
			int excSlot = anyAssigned ? this.ctx.allocTemp() : -1;
			if (arrays.isEmpty()) {
				this.single = false;
				variant(numbers, joins, excSlot);
			}
			else {
				// 2. all arrays float[] -> the single variant; all double[] -> the
				// double variant; anything else -> the boxed path
				List<Integer> notSingle = new ArrayList<>();
				for (Var v : arrays) {
					this.ctx.emit(Opcode.ALOAD);
					this.ctx.emit(v.refSlot);
					this.ctx.emit(Opcode.INSTANCEOF);
					this.ctx.emitU2(this.floatArrayClass.index());
					notSingle.add(branch(Opcode.IFEQ));
				}
				this.single = true;
				hoistArrays(arrays);
				variant(numbers, joins, excSlot);
				int tryDouble = this.ctx.code.size();
				for (int pos : notSingle) {
					JvmEmitHelper.patchBranch(this.ctx, pos, tryDouble);
				}
				for (Var v : arrays) {
					this.ctx.emit(Opcode.ALOAD);
					this.ctx.emit(v.refSlot);
					this.ctx.emit(Opcode.INSTANCEOF);
					this.ctx.emitU2(this.doubleArrayClass.index());
					bails.add(branch(Opcode.IFEQ));
				}
				this.single = false;
				hoistArrays(arrays);
				variant(numbers, joins, excSlot);
			}
			// 3. the boxed path: exactly what the loop compiled to before
			int bail = this.ctx.code.size();
			for (int pos : bails) {
				JvmEmitHelper.patchBranch(this.ctx, pos, bail);
			}
			this.ctx.nextLocal = savedNextLocal;
			JvmExprCompiler.compileExpr(am.ik.rontolisp.macro.LispMacroExpander.expandDotimes(this.cons), this.ctx,
					this.className);
			int join = this.ctx.code.size();
			for (int pos : joins) {
				JvmEmitHelper.patchBranch(this.ctx, pos, join);
			}
			return true;
		}

		private int allocWide() {
			int slot = this.ctx.allocTemp();
			this.ctx.allocTemp();
			return slot;
		}

		private int branch(int opcode) {
			int pos = this.ctx.code.size();
			this.ctx.emit(opcode);
			this.ctx.emitU2(0);
			return pos;
		}

		private void guardLong(Var v, List<Integer> bails) {
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.INSTANCEOF);
			this.ctx.emitU2(this.ctx.longClass.index());
			bails.add(branch(Opcode.IFEQ));
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.CHECKCAST);
			this.ctx.emitU2(this.ctx.longClass.index());
			this.ctx.emit(Opcode.INVOKEVIRTUAL);
			this.ctx.emitU2(this.ctx.longValue.index());
			this.ctx.emit(Opcode.LSTORE);
			this.ctx.emit(v.slot);
			// the magnitude bound the typing relies on: the value fits an int
			this.ctx.emit(Opcode.LLOAD);
			this.ctx.emit(v.slot);
			this.ctx.emit(Opcode.L2I);
			this.ctx.emit(Opcode.I2L);
			this.ctx.emit(Opcode.LLOAD);
			this.ctx.emit(v.slot);
			this.ctx.emit(Opcode.LCMP);
			bails.add(branch(Opcode.IFNE));
		}

		private void guardDouble(Var v, List<Integer> bails) {
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.INSTANCEOF);
			this.ctx.emitU2(this.ctx.doubleClass.index());
			bails.add(branch(Opcode.IFEQ));
			unboxDoubleInto(v);
		}

		private void guardDoubleOrLong(Var v, List<Integer> bails) {
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.INSTANCEOF);
			this.ctx.emitU2(this.ctx.doubleClass.index());
			int notDouble = branch(Opcode.IFEQ);
			unboxDoubleInto(v);
			int done = branch(Opcode.GOTO);
			JvmEmitHelper.patchBranch(this.ctx, notDouble, this.ctx.code.size());
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.INSTANCEOF);
			this.ctx.emitU2(this.ctx.longClass.index());
			bails.add(branch(Opcode.IFEQ));
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.CHECKCAST);
			this.ctx.emitU2(this.ctx.longClass.index());
			this.ctx.emit(Opcode.INVOKEVIRTUAL);
			this.ctx.emitU2(this.ctx.longValue.index());
			this.ctx.emit(Opcode.L2D);
			this.ctx.emit(Opcode.DSTORE);
			this.ctx.emit(v.slot);
			JvmEmitHelper.patchBranch(this.ctx, done, this.ctx.code.size());
		}

		private void unboxDoubleInto(Var v) {
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(v.refSlot);
			this.ctx.emit(Opcode.CHECKCAST);
			this.ctx.emitU2(this.ctx.doubleClass.index());
			this.ctx.emit(Opcode.INVOKEVIRTUAL);
			this.ctx.emitU2(this.ctx.numberDoubleValue.index());
			this.ctx.emit(Opcode.DSTORE);
			this.ctx.emit(v.slot);
		}

		/**
		 * Casts every array into its typed slot and reads its header once. The arrays are
		 * loop-invariant, so under {@code --gpu} this is also where each is materialized
		 * -- the raw {@code faload}s in the body read the host's bytes, which must be the
		 * device's first ({@code .kb/gpu.md}).
		 */
		private void hoistArrays(List<Var> arrays) {
			ClassConstant cls = this.single ? this.floatArrayClass : this.doubleArrayClass;
			for (Var v : arrays) {
				this.ctx.emit(Opcode.ALOAD);
				this.ctx.emit(v.refSlot);
				if (this.materialize != null) {
					// The typed slot takes what the guard answers: the array, or a
					// result stub's backing. The variable's own slot keeps the program's
					// object, which is what the body's aset reports as written.
					this.ctx.emit(Opcode.INVOKESTATIC);
					this.ctx.emitU2(this.materialize.index());
				}
				this.ctx.emit(Opcode.CHECKCAST);
				this.ctx.emitU2(cls.index());
				this.ctx.emit(Opcode.ASTORE);
				this.ctx.emit(v.slot);
				// base = 1 + rank, the data offset (_fvAref*'s `1 + rank`)
				this.ctx.emit(Opcode.ICONST_1);
				this.ctx.emit(Opcode.ALOAD);
				this.ctx.emit(v.slot);
				this.ctx.emit(Opcode.ICONST_0);
				loadHeaderInt();
				this.ctx.emit(Opcode.IADD);
				this.ctx.emit(Opcode.ISTORE);
				this.ctx.emit(v.baseSlot);
				if (v.rank == 2) {
					this.ctx.emit(Opcode.ALOAD);
					this.ctx.emit(v.slot);
					this.ctx.emit(Opcode.ICONST_2);
					loadHeaderInt();
					this.ctx.emit(Opcode.ISTORE);
					this.ctx.emit(v.colsSlot);
				}
			}
		}

		private void loadHeaderInt() {
			if (this.single) {
				this.ctx.emit(Opcode.FALOAD);
				this.ctx.emit(Opcode.F2I);
			}
			else {
				this.ctx.emit(Opcode.DALOAD);
				this.ctx.emit(Opcode.D2I);
			}
		}

		/**
		 * One typed variant: the loop, the write-back of the assigned variables, the
		 * result value, and (when anything is written back) the handler that writes back
		 * and rethrows.
		 */
		private void variant(List<Var> numbers, List<Integer> joins, int excSlot) {
			int savedNextLocal = this.ctx.nextLocal;
			int start = this.ctx.code.size();
			loop(this.an.ctr(), this.an.count(), this.an.body());
			int end = this.ctx.code.size();
			List<Var> assigned = numbers.stream().filter(v -> v.assigned && !v.rawDouble).toList();
			writeBack(assigned);
			// the value: nil, or the result form with the counter bound to its final
			// value
			LispVal resultForm = this.an.resultForm();
			if (resultForm == null) {
				this.ctx.emit(Opcode.ACONST_NULL);
			}
			else {
				Map<String, Integer> savedLocals = new HashMap<>(this.ctx.locals);
				Set<String> savedBoxed = this.ctx.boxedVars;
				this.ctx.boxedVars = new HashSet<>(this.ctx.boxedVars);
				int slot = this.ctx.allocLocal(this.an.ctr().name);
				this.ctx.boxedVars.remove(this.an.ctr().name);
				this.ctx.emit(Opcode.LLOAD);
				this.ctx.emit(this.an.ctr().slot);
				this.ctx.emit(Opcode.INVOKESTATIC);
				this.ctx.emitU2(this.ctx.longValueOf.index());
				this.ctx.emit(Opcode.ASTORE);
				this.ctx.emit(slot);
				JvmExprCompiler.compileExpr(resultForm, this.ctx, this.className);
				this.ctx.locals = savedLocals;
				this.ctx.boxedVars = savedBoxed;
			}
			joins.add(branch(Opcode.GOTO));
			if (!assigned.isEmpty() && start < end) {
				int handler = this.ctx.code.size();
				this.ctx.stack.enterHandler();
				this.ctx.emit(Opcode.ASTORE);
				this.ctx.emit(excSlot);
				writeBack(assigned);
				this.ctx.emit(Opcode.ALOAD);
				this.ctx.emit(excSlot);
				this.ctx.emit(Opcode.ATHROW);
				this.ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(start, end, handler, 0));
			}
			this.ctx.nextLocal = savedNextLocal;
		}

		private void writeBack(List<Var> assigned) {
			for (Var v : assigned) {
				int boxedSlot = java.util.Objects.requireNonNull(this.ctx.locals.get(v.name));
				if (v.type == T.LONG) {
					this.ctx.emit(Opcode.LLOAD);
					this.ctx.emit(v.slot);
					this.ctx.emit(Opcode.INVOKESTATIC);
					this.ctx.emitU2(this.ctx.longValueOf.index());
				}
				else {
					this.ctx.emit(Opcode.DLOAD);
					this.ctx.emit(v.slot);
					this.ctx.emit(Opcode.INVOKESTATIC);
					this.ctx.emitU2(this.ctx.doubleValueOf.index());
				}
				this.ctx.emit(Opcode.ASTORE);
				this.ctx.emit(boxedSlot);
			}
		}

		private void loop(Var ctr, Node count, List<Node> body) {
			int savedNextLocal = this.ctx.nextLocal;
			int limSlot = allocWide();
			ctr.slot = allocWide();
			expr(count);
			this.ctx.emit(Opcode.LSTORE);
			this.ctx.emit(limSlot);
			this.ctx.emit(Opcode.LCONST_0);
			this.ctx.emit(Opcode.LSTORE);
			this.ctx.emit(ctr.slot);
			int loopStart = this.ctx.code.size();
			this.ctx.emit(Opcode.LLOAD);
			this.ctx.emit(ctr.slot);
			this.ctx.emit(Opcode.LLOAD);
			this.ctx.emit(limSlot);
			this.ctx.emit(Opcode.LCMP);
			int exit = branch(Opcode.IFGE);
			for (Node n : body) {
				stmt(n);
			}
			this.ctx.emit(Opcode.LLOAD);
			this.ctx.emit(ctr.slot);
			this.ctx.emit(Opcode.LCONST_1);
			this.ctx.emit(Opcode.LADD);
			this.ctx.emit(Opcode.LSTORE);
			this.ctx.emit(ctr.slot);
			int back = branch(Opcode.GOTO);
			JvmEmitHelper.patchBranch(this.ctx, back, loopStart);
			JvmEmitHelper.patchBranch(this.ctx, exit, this.ctx.code.size());
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
					this.ctx.emit(Opcode.POP2);
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
				case Aref a -> {
					arrayIndex(a.arr(), a.idx());
					if (this.single) {
						this.ctx.emit(Opcode.FALOAD);
						this.ctx.emit(Opcode.F2D);
					}
					else {
						this.ctx.emit(Opcode.DALOAD);
					}
				}
				case Arith a -> {
					if (a.type() == T.LONG) {
						expr(a.a());
						expr(a.b());
						this.ctx.emit(switch (a.op()) {
							case LispNames.ADD -> Opcode.LADD;
							case LispNames.SUB -> Opcode.LSUB;
							default -> Opcode.LMUL;
						});
					}
					else {
						exprAsDouble(a.a());
						exprAsDouble(a.b());
						this.ctx.emit(switch (a.op()) {
							case LispNames.ADD -> Opcode.DADD;
							case LispNames.SUB -> Opcode.DSUB;
							case LispNames.MUL -> Opcode.DMUL;
							default -> Opcode.DDIV;
						});
					}
				}
				case Neg g -> {
					expr(g.a());
					this.ctx.emit(g.type() == T.LONG ? Opcode.LNEG : Opcode.DNEG);
				}
				case Recip r -> {
					this.ctx.emit(Opcode.DCONST_1);
					expr(r.a());
					this.ctx.emit(Opcode.DDIV);
				}
				case MathFn m -> {
					exprAsDouble(m.a());
					this.ctx.emit(Opcode.INVOKESTATIC);
					this.ctx.emitU2(this.ctx.mathOp(m.name()).index());
				}
				case Aset a -> aset(a, true);
				case Setq s -> {
					expr(s.value());
					this.ctx.emit(Opcode.DUP2);
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
					List<Integer> falses = cond(i.cond());
					expr(i.then());
					int end = branch(Opcode.GOTO);
					int elseStart = this.ctx.code.size();
					for (int pos : falses) {
						JvmEmitHelper.patchBranch(this.ctx, pos, elseStart);
					}
					expr(java.util.Objects.requireNonNull(i.els()));
					JvmEmitHelper.patchBranch(this.ctx, end, this.ctx.code.size());
				}
				default -> throw new IllegalStateException("not a typed expression: " + n);
			}
		}

		private void exprAsDouble(Node n) {
			expr(n);
			if (n.type() == T.LONG) {
				this.ctx.emit(Opcode.L2D);
			}
		}

		private void load(Var v) {
			this.ctx.emit(v.type == T.LONG ? Opcode.LLOAD : Opcode.DLOAD);
			this.ctx.emit(v.slot);
		}

		private void store(Var v) {
			this.ctx.emit(v.type == T.LONG ? Opcode.LSTORE : Opcode.DSTORE);
			this.ctx.emit(v.slot);
		}

		/** Leaves {@code (arrayref, int index)} on the stack, the helpers' arithmetic. */
		private void arrayIndex(Var arr, List<Node> idx) {
			this.ctx.emit(Opcode.ALOAD);
			this.ctx.emit(arr.slot);
			this.ctx.emit(Opcode.ILOAD);
			this.ctx.emit(arr.baseSlot);
			expr(idx.get(0));
			this.ctx.emit(Opcode.L2I);
			if (idx.size() == 2) {
				this.ctx.emit(Opcode.ILOAD);
				this.ctx.emit(arr.colsSlot);
				this.ctx.emit(Opcode.IMUL);
				this.ctx.emit(Opcode.IADD);
				expr(idx.get(1));
				this.ctx.emit(Opcode.L2I);
			}
			this.ctx.emit(Opcode.IADD);
		}

		private void aset(Aset a, boolean valueNeeded) {
			if (this.written != null) {
				// --gpu, BEFORE the store: the array may have a resident device copy
				// (materialized at loop entry, so it is clean by now); it is stale now.
				// Reported on the program's object (the variable's slot), not the typed
				// slot, which may be a stub's backing the library does not key on; the
				// guard's answer is the typed slot already and is dropped.
				this.ctx.emit(Opcode.ALOAD);
				this.ctx.emit(a.arr().refSlot);
				this.ctx.emit(Opcode.INVOKESTATIC);
				this.ctx.emitU2(this.written.index());
				this.ctx.emit(Opcode.POP);
			}
			arrayIndex(a.arr(), a.idx());
			exprAsDouble(a.value());
			int tmp = -1;
			if (valueNeeded) {
				tmp = allocWide();
				this.ctx.emit(Opcode.DUP2);
				this.ctx.emit(Opcode.DSTORE);
				this.ctx.emit(tmp);
			}
			if (this.single) {
				this.ctx.emit(Opcode.D2F);
				this.ctx.emit(Opcode.FASTORE);
			}
			else {
				this.ctx.emit(Opcode.DASTORE);
			}
			if (valueNeeded) {
				// the value of a store is the value AS STORED (narrowed for single)
				this.ctx.emit(Opcode.DLOAD);
				this.ctx.emit(tmp);
				if (this.single) {
					this.ctx.emit(Opcode.D2F);
					this.ctx.emit(Opcode.F2D);
				}
			}
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
			List<Integer> falses = cond(i.cond());
			stmt(i.then());
			if (i.els() == null) {
				int end = this.ctx.code.size();
				for (int pos : falses) {
					JvmEmitHelper.patchBranch(this.ctx, pos, end);
				}
				return;
			}
			int end = branch(Opcode.GOTO);
			int elseStart = this.ctx.code.size();
			for (int pos : falses) {
				JvmEmitHelper.patchBranch(this.ctx, pos, elseStart);
			}
			stmt(i.els());
			JvmEmitHelper.patchBranch(this.ctx, end, this.ctx.code.size());
		}

		/**
		 * Emits the comparison as a conditional branch taken when the test is FALSE
		 * (after negation), answering the branch positions to patch to the false target.
		 */
		private List<Integer> cond(Cmp c) {
			boolean longs = c.a().type() == T.LONG && c.b().type() == T.LONG;
			String op = c.op();
			if (longs) {
				expr(c.a());
				expr(c.b());
				this.ctx.emit(Opcode.LCMP);
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
				this.ctx.emit(g ? Opcode.DCMPG : Opcode.DCMPL);
			}
			// the branch skips the body when the test (as written) is false
			int whenFalse = switch (op) {
				case LispNames.LT -> Opcode.IFGE;
				case LispNames.LE -> Opcode.IFGT;
				case LispNames.GT -> Opcode.IFLE;
				case LispNames.GE -> Opcode.IFLT;
				default -> Opcode.IFNE;
			};
			int whenTrue = switch (op) {
				case LispNames.LT -> Opcode.IFLT;
				case LispNames.LE -> Opcode.IFLE;
				case LispNames.GT -> Opcode.IFGT;
				case LispNames.GE -> Opcode.IFGE;
				default -> Opcode.IFEQ;
			};
			List<Integer> out = new ArrayList<>();
			out.add(branch(c.negate() ? whenTrue : whenFalse));
			return out;
		}

	}

}
