package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * The outermost {@code if} of a generic function's dispatcher on the interpreter, which
 * remembers what its tests answer for each combination of the arguments' shapes. Without
 * it a call walks the chain: fset's {@code compare} has some 350 branches, a symbol pair
 * reaches its own near the end, and that walk was three quarters of the time coalton's
 * typechecker took ({@code .kb/clos.md}, "The interpreter's chain memo").
 *
 * <p>
 * <b>Same subclass reasoning as {@link LocatedCons}.</b> Its car and cdr are the chain's
 * own, so every walker over a dispatcher sees the plain nested {@code if} every other
 * backend gets, and a pass that rebuilds the form gets a plain {@link LispCons}, which
 * only costs the speed. {@code evalCons} takes the memo by a type test in its {@code if}
 * arm. A dispatcher generated again is a new chain, so nothing has to be invalidated.
 *
 * <p>
 * <b>What is remembered.</b> A test the shapes decide is answered once, by evaluating it:
 * {@link #SHAPE_TESTS} over a parameter, under {@code and}/{@code or}/{@code not}/
 * {@code if}. Any other test -- an {@code eql} specializer's, a nested method's guard,
 * {@code package}'s {@code find-package} -- stays a test for every call, and only the
 * shapes it is still undecided for (a {@link Selection} per combination): tests have no
 * effect, so dropping one that cannot hold is unobservable.
 */
public final class DispatchChain extends LispCons {

	/**
	 * The predicates whose answer over a value is a function of {@link #shapeOf}, as the
	 * interpreter defines them (each an {@code instanceof} over the value's class, the
	 * keyword spelling or the instance's layout).
	 */
	private static final Set<String> SHAPE_TESTS = Set.of(LispNames.OBJ_IS, LispNames.NULL, LispNames.NOT,
			LispNames.ATOM, LispNames.NUMBERP, LispNames.INTEGERP, LispNames.FLOATP, LispNames.RATIONALP,
			LispNames.REALP, LispNames.COMPLEXP, LispNames.SYMBOLP, LispNames.STRINGP, LispNames.LISTP, LispNames.CONSP,
			LispNames.KEYWORDP, LispNames.FUNCTIONP, LispNames.STREAMP, LispNames.ARRAYP_INTERNAL, LispNames.CHARACTERP,
			LispNames.HASH_TABLE_P);

	/** What a keyword is told apart from any other symbol by. */
	private static final Object KEYWORD_SHAPE = new Object();

	private final LispVal[] tests;

	private final LispVal[] arms;

	private final LispVal last;

	private final LispSymbol[] keys;

	private final Shapes root = new Shapes();

	private DispatchChain(LispCons head, List<LispVal> tests, List<LispVal> arms, LispVal last, List<LispSymbol> keys) {
		super(head.car(), head.cdr());
		this.tests = tests.toArray(LispVal[]::new);
		this.arms = arms.toArray(LispVal[]::new);
		this.last = last;
		this.keys = keys.toArray(LispSymbol[]::new);
	}

	/**
	 * A generated dispatcher with its chain memoized.
	 * @param defun the {@code (defun name params body)} form of a dispatcher
	 * @return the same definition over a {@link DispatchChain}, or the form itself when
	 * its body is no chain of tests over its parameters
	 */
	public static LispVal memoizing(LispVal defun) {
		if (!(defun instanceof LispCons form) || !form.isProperList()) {
			return defun;
		}
		List<LispVal> parts = form.toList();
		if (parts.size() != 4) {
			return defun;
		}
		Set<String> params = new java.util.HashSet<>();
		if (parts.get(2) instanceof LispCons paramList) {
			for (LispVal param : paramList.toList()) {
				if (param instanceof LispSymbol symbol && !symbol.isLambdaListKeyword()) {
					params.add(symbol.name());
				}
			}
		}
		LispVal body = memoizedBody(parts.get(3), params);
		return body == parts.get(3) ? defun : new LispCons(parts.get(0),
				new LispCons(parts.get(1), new LispCons(parts.get(2), new LispCons(body, LispNil.INSTANCE))));
	}

	/**
	 * A dispatcher's body with its chain memoized. Under the generic-function protocol or
	 * with classes made at run time the chain stands inside the {@code let}s of the
	 * dynamic arm, whose own test is one more test the shapes leave open.
	 */
	private static LispVal memoizedBody(LispVal body, Set<String> params) {
		if (!(body instanceof LispCons head) || !head.isProperList()) {
			return body;
		}
		if (head.car() instanceof LispSymbol operator && LispNames.LET.equals(operator.name())
				&& head.properLength() == 3) {
			List<LispVal> let = head.toList();
			LispVal inner = memoizedBody(let.get(2), params);
			return inner == let.get(2) ? body
					: new LispCons(let.get(0), new LispCons(let.get(1), new LispCons(inner, LispNil.INSTANCE)));
		}
		if (!isIf(head)) {
			return body;
		}
		List<LispVal> tests = new ArrayList<>();
		List<LispVal> arms = new ArrayList<>();
		LispVal link = head;
		while (link instanceof LispCons cons && isIf(cons)) {
			List<LispVal> ifParts = cons.toList();
			tests.add(ifParts.get(1));
			arms.add(ifParts.get(2));
			link = ifParts.get(3);
		}
		Set<LispSymbol> keys = new LinkedHashSet<>();
		for (LispVal test : tests) {
			collectKeys(test, params, keys);
		}
		return keys.isEmpty() ? body : new DispatchChain(head, tests, arms, link, List.copyOf(keys));
	}

	private static boolean isIf(LispCons cons) {
		return cons.car() instanceof LispSymbol head && LispNames.IF.equals(head.name()) && cons.properLength() == 4;
	}

	private static void collectKeys(LispVal test, Set<String> params, Set<LispSymbol> keys) {
		if (test instanceof LispCons form && form.car() instanceof LispSymbol head && form.isProperList()) {
			if (isShapeTest(form, params)) {
				keys.add((LispSymbol) ((LispCons) form.cdr()).car());
			}
			else if (isComposite(head.name())) {
				for (LispVal term : form.toList().subList(1, form.properLength())) {
					collectKeys(term, params, keys);
				}
			}
		}
	}

	private static boolean isComposite(String head) {
		return LispNames.AND.equals(head) || LispNames.OR.equals(head) || LispNames.NOT.equals(head)
				|| LispNames.NULL.equals(head) || LispNames.IF.equals(head);
	}

	/** {@code (predicate parameter 'literal...)}. */
	private static boolean isShapeTest(LispCons form, Set<String> params) {
		if (!(form.car() instanceof LispSymbol head) || !SHAPE_TESTS.contains(head.name())
				|| !(form.cdr() instanceof LispCons args) || !(args.car() instanceof LispSymbol arg)
				|| !params.contains(arg.name())) {
			return false;
		}
		LispVal rest = args.cdr();
		while (rest instanceof LispCons cell) {
			if (!(cell.car() instanceof LispCons literal && literal.car() instanceof LispSymbol quote
					&& LispNames.isQuote(quote.name()))) {
				return false;
			}
			rest = cell.cdr();
		}
		return rest instanceof LispNil;
	}

	/**
	 * What the tests of a chain can tell a value by: its layout when it is an instance
	 * (the tag a test reads, and a funcallable or Gray class), whether a symbol is a
	 * keyword, and otherwise its class.
	 * @param value the argument
	 * @return its shape, compared by identity
	 */
	public static Object shapeOf(LispVal value) {
		if (value instanceof LispInstance instance) {
			return instance.layout();
		}
		if (value instanceof LispSymbol symbol) {
			return symbol.isKeyword() ? KEYWORD_SHAPE : LispSymbol.class;
		}
		return value.getClass();
	}

	/**
	 * The parameters a test tells apart by shape, in the order a combination is keyed.
	 * @return the parameter symbols
	 */
	public LispSymbol[] keys() {
		return this.keys;
	}

	/**
	 * The table of the first key's shapes.
	 * @return the root table
	 */
	public Shapes root() {
		return this.root;
	}

	/**
	 * Works out what a call with these arguments has left to test, and remembers it.
	 * @param shapes the arguments' shapes, one per {@link #keys() key}
	 * @param holds what evaluating a shape test in the call's environment answers
	 * @return the selection for that combination of shapes
	 */
	public Selection select(Object[] shapes, Predicate<LispCons> holds) {
		List<LispVal> tests = new ArrayList<>();
		List<LispVal> arms = new ArrayList<>();
		LispVal chosen = this.last;
		Set<String> params = new java.util.HashSet<>();
		for (LispSymbol key : this.keys) {
			params.add(key.name());
		}
		for (int i = 0; i < this.tests.length; i++) {
			LispVal residual = residual(this.tests[i], params, holds);
			if (residual instanceof LispTrue) {
				chosen = this.arms[i];
				break;
			}
			if (!(residual instanceof LispNil)) {
				tests.add(residual);
				arms.add(this.arms[i]);
			}
		}
		Selection selection = new Selection(tests.toArray(LispVal[]::new), arms.toArray(LispVal[]::new), chosen);
		Shapes table = this.root;
		for (int i = 0; i < shapes.length - 1; i++) {
			table = table.tableFor(shapes[i]);
		}
		table.put(shapes[shapes.length - 1], selection);
		return selection;
	}

	/**
	 * A test with what the shapes decide folded away: {@code t}, {@code nil}, or what is
	 * left to evaluate per call.
	 */
	private static LispVal residual(LispVal test, Set<String> params, Predicate<LispCons> holds) {
		if (!(test instanceof LispCons form) || !(form.car() instanceof LispSymbol head) || !form.isProperList()) {
			return test;
		}
		if (isShapeTest(form, params)) {
			return holds.test(form) ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}
		List<LispVal> terms = form.toList().subList(1, form.properLength());
		switch (head.name()) {
			case LispNames.AND:
				return conjunction(head, terms, params, holds, true);
			case LispNames.OR:
				return conjunction(head, terms, params, holds, false);
			case LispNames.NOT:
			case LispNames.NULL:
				if (terms.size() == 1) {
					LispVal term = residual(terms.get(0), params, holds);
					return term instanceof LispTrue ? LispNil.INSTANCE
							: term instanceof LispNil ? LispTrue.INSTANCE : test;
				}
				return test;
			case LispNames.IF:
				if (terms.size() >= 2) {
					LispVal condition = residual(terms.get(0), params, holds);
					if (condition instanceof LispTrue) {
						return residual(terms.get(1), params, holds);
					}
					if (condition instanceof LispNil) {
						return terms.size() > 2 ? residual(terms.get(2), params, holds) : LispNil.INSTANCE;
					}
				}
				return test;
			default:
				return test;
		}
	}

	/** An {@code and} or an {@code or} over its residual terms. */
	private static LispVal conjunction(LispSymbol head, List<LispVal> terms, Set<String> params,
			Predicate<LispCons> holds, boolean isAnd) {
		List<LispVal> left = new ArrayList<>();
		for (LispVal term : terms) {
			LispVal residual = residual(term, params, holds);
			if (isAnd ? residual instanceof LispNil : residual instanceof LispTrue) {
				return residual;
			}
			if (!(isAnd ? residual instanceof LispTrue : residual instanceof LispNil)) {
				left.add(residual);
			}
		}
		if (left.isEmpty()) {
			return isAnd ? LispTrue.INSTANCE : LispNil.INSTANCE;
		}
		if (left.size() == 1) {
			return left.get(0);
		}
		LispVal rest = LispNil.INSTANCE;
		for (int i = left.size() - 1; i >= 0; i--) {
			rest = new LispCons(left.get(i), rest);
		}
		return new LispCons(head, rest);
	}

	/**
	 * What a call with one combination of shapes has left to do.
	 *
	 * @param tests the tests the shapes leave open, in chain order
	 * @param arms the form each of them selects
	 * @param last the form a call evaluates when none of them holds
	 */
	public record Selection(LispVal[] tests, LispVal[] arms, LispVal last) {
	}

	/**
	 * A table keyed by a shape's identity, read without a lock: an insertion publishes a
	 * new array, and there are as few as there are shapes a generic is called with.
	 */
	public static final class Shapes {

		private volatile @Nullable Object[] slots = new Object[8];

		private int count;

		/**
		 * The value kept for a shape.
		 * @param shape the shape
		 * @return its table or selection, or null
		 */
		public @Nullable Object get(Object shape) {
			@Nullable Object[] table = this.slots;
			int mask = table.length - 2;
			int i = (System.identityHashCode(shape) << 1) & mask;
			while (true) {
				@Nullable Object key = table[i];
				if (key == shape) {
					return table[i + 1];
				}
				if (key == null) {
					return null;
				}
				i = (i + 2) & mask;
			}
		}

		private synchronized Shapes tableFor(Object shape) {
			if (get(shape) instanceof Shapes existing) {
				return existing;
			}
			Shapes table = new Shapes();
			put(shape, table);
			return table;
		}

		private synchronized void put(Object shape, Object value) {
			@Nullable Object[] table = this.slots;
			if (get(shape) == null) {
				this.count++;
			}
			int capacity = table.length;
			if (this.count * 4 > capacity) {
				capacity *= 2;
			}
			@Nullable Object[] grown = new Object[capacity];
			for (int i = 0; i < table.length; i += 2) {
				if (table[i] != null && table[i] != shape) {
					insert(grown, table[i], table[i + 1]);
				}
			}
			insert(grown, shape, value);
			this.slots = grown;
		}

		private static void insert(@Nullable Object[] table, @Nullable Object key, @Nullable Object value) {
			int mask = table.length - 2;
			int i = (System.identityHashCode(key) << 1) & mask;
			while (table[i] != null) {
				i = (i + 2) & mask;
			}
			table[i] = key;
			table[i + 1] = value;
		}

	}

}
