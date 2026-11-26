package am.ik.rontolisp.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.macro.IntegerTypeRange;
import org.jspecify.annotations.Nullable;

/**
 * Maps a declared type specifier to the interval of i64 it pins an integer to, and reads
 * {@code (declare (type spec var...))} forms out of a body head: the integer half of
 * declaration-driven emission, beside {@link DeclaredArrayTypes}. A backend that keeps
 * integers raw may take a value so declared without the test that would send any other
 * value to its generic path, and may use the interval as a proof.
 *
 * <p>
 * Only a specifier whose {@code typep} tests that interval counts:
 * {@code (signed-byte n)}, {@code (unsigned-byte n)}, {@code (integer lo hi)},
 * {@code (mod n)}, {@code bit}, an {@code (and ...)} of those and a {@code deftype} alias
 * of one, within i64 at both ends. {@code fixnum} proves no bound here: its {@code typep}
 * is {@code integerp} ({@code .kb/declarations-type-checks.md}), so it reads as
 * {@code integer} inside an {@code (and ...)} and as nothing alone.
 *
 * <p>
 * A false declaration is undefined behavior in CL; where a backend trusts one it becomes
 * a deterministic trap, never a wrong value -- {@code .kb/declarations-type-checks.md}.
 */
public final class DeclaredIntegerTypes {

	/** How many {@code deftype} aliases a specifier is followed through. */
	private static final int ALIAS_HOPS = 16;

	private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

	private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

	private DeclaredIntegerTypes() {
	}

	/**
	 * A closed interval of i64.
	 *
	 * @param lo the least member
	 * @param hi the greatest member
	 */
	public record Interval(long lo, long hi) {

	}

	/**
	 * The interval a type specifier pins an integer to, or null when it pins none within
	 * i64.
	 * @param spec the type specifier as written in the declaration
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return the interval, or null
	 */
	public static @Nullable Interval of(@Nullable LispVal spec, @Nullable ClosRegistry registry) {
		if (spec == null) {
			return null;
		}
		LispVal resolved = resolve(spec, registry, 0);
		if (resolved == null || isFixnum(resolved)) {
			return null;
		}
		IntegerTypeRange range = IntegerTypeRange.of(resolved);
		if (range == null || range.lo() == null || range.hi() == null || range.isEmpty()
				|| range.lo().compareTo(LONG_MIN) < 0 || range.hi().compareTo(LONG_MAX) > 0) {
			return null;
		}
		return new Interval(range.lo().longValueExact(), range.hi().longValueExact());
	}

	/**
	 * The integer intervals a body's leading {@code (declare (type spec var...))} forms
	 * pin variables to. Only leading declarations count (an optional docstring before or
	 * between them is skipped), only {@code type} specifiers are read, and a name
	 * declared twice keeps the meet of its intervals -- an empty meet proves nothing and
	 * the name is left out.
	 * @param bodyExprs the body forms, declarations included
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return declared variable name to its interval (empty when none declare one)
	 */
	public static Map<String, Interval> declared(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Map<String, Interval> intervals = null;
		List<String> contradicted = new ArrayList<>();
		for (LispVal form : bodyExprs) {
			if (form instanceof LispString) {
				continue;
			}
			if (!(form instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
					|| !LispNames.DECLARE.equals(plainName(head.name()))) {
				break;
			}
			List<LispVal> specifiers = cons.toList();
			for (int i = 1; i < specifiers.size(); i++) {
				if (!(specifiers.get(i) instanceof LispCons specifier) || !specifier.isProperList()
						|| !(specifier.car() instanceof LispSymbol specHead)
						|| !"TYPE".equals(plainName(specHead.name()))) {
					continue;
				}
				List<LispVal> parts = specifier.toList();
				Interval interval = parts.size() < 3 ? null : of(parts.get(1), registry);
				if (interval == null) {
					continue;
				}
				for (int v = 2; v < parts.size(); v++) {
					if (!(parts.get(v) instanceof LispSymbol variable)) {
						continue;
					}
					if (intervals == null) {
						intervals = new LinkedHashMap<>();
					}
					Interval before = intervals.get(variable.name());
					Interval meet = before == null ? interval
							: new Interval(Math.max(before.lo(), interval.lo()), Math.min(before.hi(), interval.hi()));
					if (meet.lo() > meet.hi()) {
						contradicted.add(variable.name());
					}
					intervals.put(variable.name(), meet);
				}
			}
		}
		if (intervals == null) {
			return Map.of();
		}
		contradicted.forEach(intervals::remove);
		return intervals;
	}

	/**
	 * {@link #declared} of a function body's head, and of the head of the sole
	 * {@code %fn-block}/{@code block} the body's last form wraps around the rest, the
	 * shape a {@code defun}'s body is lowered to. The caller removes special variable
	 * names.
	 * @param bodyExprs the function body forms as lowered
	 * @param registry the registry holding {@code deftype} expansions, or null
	 * @return declared variable name to its interval (empty when none declare one)
	 */
	public static Map<String, Interval> functionBody(List<LispVal> bodyExprs, @Nullable ClosRegistry registry) {
		Map<String, Interval> intervals = new LinkedHashMap<>(declared(bodyExprs, registry));
		for (int i = 0; i < bodyExprs.size(); i++) {
			LispVal form = bodyExprs.get(i);
			if (form instanceof LispString || am.ik.rontolisp.SpecialDeclarations.isDeclaration(form)) {
				continue;
			}
			if (i == bodyExprs.size() - 1 && form instanceof LispCons wrapper && wrapper.isProperList()
					&& wrapper.car() instanceof LispSymbol head
					&& (LispNames.FN_BLOCK_INTERNAL.equals(head.name()) || LispNames.BLOCK.equals(head.name()))
					&& wrapper.toList().size() >= 3) {
				List<LispVal> wrapped = wrapper.toList();
				declared(wrapped.subList(2, wrapped.size()), registry).forEach(intervals::putIfAbsent);
			}
			break;
		}
		return intervals;
	}

	/**
	 * Whether a declared variable is worth keeping raw: it is read raw -- an operand of
	 * an integer operation or comparison, where a fused site reads the slot -- or
	 * assigned such an operation's value more often than it is read anywhere else, where
	 * the raw value is boxed again (a call's argument, a stored value, the value a form
	 * answers). Each raw read of a boxed variable unboxes it; each boxed read of a raw
	 * one boxes it, which allocates past the fixnum range on wasm and past
	 * {@code Long.valueOf}'s cache on the JVM. Blind to scope and counted over the forms
	 * as written, which is all a choice of representation needs. The rule both backends
	 * choose a declared variable's representation by
	 * ({@code .kb/declarations-type-checks.md}, "Declared integer ranges").
	 * @param name the variable
	 * @param body the forms of its scope
	 * @return whether its raw uses outnumber its boxed ones
	 */
	public static boolean readMostlyRaw(String name, List<LispVal> body) {
		int[] uses = new int[2];
		for (LispVal form : body) {
			countUses(name, form, false, uses);
		}
		return uses[0] > uses[1];
	}

	/** Counts {@code name}'s raw uses into {@code uses[0]}, its boxed ones into [1]. */
	private static void countUses(String name, LispVal form, boolean rawOperand, int[] uses) {
		if (form instanceof LispSymbol sym) {
			if (sym.name().equals(name)) {
				uses[rawOperand ? 0 : 1]++;
			}
			return;
		}
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head) || !cons.isProperList()) {
			return;
		}
		List<LispVal> parts = cons.toList();
		switch (head.name()) {
			case LispNames.QUOTE, LispNames.DECLARE -> {
			}
			case LispNames.THE -> {
				if (parts.size() == 3) {
					countUses(name, parts.get(2), rawOperand, uses);
				}
			}
			case LispNames.LET, LispNames.LET_STAR -> {
				// each init is read where it stands: a variable bound to another is a
				// boxed read, one in an integer operation a raw one
				if (parts.size() > 1 && parts.get(1) instanceof LispCons bindings && bindings.isProperList()) {
					for (LispVal binding : bindings.toList()) {
						if (binding instanceof LispCons pair && pair.cdr() instanceof LispCons init) {
							countUses(name, init.car(), false, uses);
						}
					}
				}
				for (int i = 2; i < parts.size(); i++) {
					countUses(name, parts.get(i), false, uses);
				}
			}
			case LispNames.SETQ, LispNames.SETF -> {
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					if (parts.get(i) instanceof LispSymbol target && target.name().equals(name)) {
						// an assignment of an integer operation is a raw store
						if (isIntegerOperation(withoutThe(parts.get(i + 1)))) {
							uses[0]++;
						}
					}
					else {
						countUses(name, parts.get(i), false, uses);
					}
					countUses(name, parts.get(i + 1), false, uses);
				}
			}
			default -> {
				boolean operands = isIntegerOperation(cons);
				for (int i = 1; i < parts.size(); i++) {
					countUses(name, parts.get(i), operands, uses);
				}
			}
		}
	}

	/** Whether the form is an integer operation or comparison a fused site reads raw. */
	private static boolean isIntegerOperation(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head && switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.ONE_PLUS,
					LispNames.ONE_MINUS, LispNames.MASK_SIGNED_FIELD, LispNames.TRUNCATE, LispNames.FLOOR, LispNames.LT,
					LispNames.GT, LispNames.LE, LispNames.GE, LispNames.EQ ->
				true;
			default -> false;
		};
	}

	/** The form under any {@code (the type form)} wraps. */
	private static LispVal withoutThe(LispVal form) {
		LispVal expr = form;
		while (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.THE.equals(head.name()) && cons.isProperList() && cons.toList().size() == 3) {
			expr = cons.toList().get(2);
		}
		return expr;
	}

	/**
	 * The specifier with its {@code deftype} aliases followed -- the whole of it, and
	 * each conjunct of an {@code (and ...)} -- or null where an alias does not end within
	 * {@link #ALIAS_HOPS}.
	 */
	private static @Nullable LispVal resolve(LispVal spec, @Nullable ClosRegistry registry, int depth) {
		LispVal resolved = spec;
		for (int hop = 0; resolved instanceof LispSymbol sym && registry != null; hop++) {
			if (hop == ALIAS_HOPS) {
				return null;
			}
			LispVal expansion = registry.findDeftype(sym.name());
			if (expansion == null) {
				break;
			}
			resolved = unquoted(expansion);
		}
		if (depth < ALIAS_HOPS && resolved instanceof LispCons cons && cons.isProperList()
				&& cons.car() instanceof LispSymbol head && "AND".equals(plainName(head.name()))) {
			List<LispVal> parts = cons.toList();
			List<LispVal> conjuncts = new ArrayList<>(parts.size());
			conjuncts.add(head);
			for (LispVal conjunct : parts.subList(1, parts.size())) {
				LispVal part = resolve(conjunct, registry, depth + 1);
				if (part == null) {
					return null;
				}
				// fixnum is integerp here: no bound of its own inside a conjunction
				conjuncts.add(isFixnum(part) ? new LispSymbol("INTEGER") : part);
			}
			return LispCons.rebuiltList(cons, conjuncts);
		}
		return resolved;
	}

	private static LispVal unquoted(LispVal spec) {
		if (spec instanceof LispCons quote && quote.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& quote.cdr() instanceof LispCons rest) {
			return rest.car();
		}
		return spec;
	}

	private static boolean isFixnum(LispVal spec) {
		return spec instanceof LispSymbol sym && "FIXNUM".equals(plainName(sym.name()));
	}

	private static String plainName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
