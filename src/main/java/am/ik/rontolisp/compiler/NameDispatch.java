package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * A dispatch of a runtime name over a static name set, as the compilers see it: the
 * else-chain {@code (if (%symbol-is n 'S1) A1 (if (%symbol-is n 'S2) A2 ... MISS))} the
 * macro expander spells for every by-name access of a global ({@code %global-access},
 * {@code progv}'s bind and unbind, an inline {@code set} or {@code symbol-value}). The
 * AST keeps that chain -- every pass reads ordinary {@code if}s -- and a backend that
 * recognises it here emits it as a search instead of a walk: the name's key computed
 * once, a binary search over the arms' keys, a short run of exact tests at the bottom
 * ({@code .kb/dynamic-special-variables.md}, "Name dispatch").
 * <p>
 * The chain's tests run in order and stop at the first match, and none has an effect, so
 * an arm whose name an earlier arm already tests is dead and is dropped; the rest may be
 * tested in any order.
 *
 * @param subject the variable holding the name, read by every test
 * @param names the names the arms test, the chain's order, no repeats
 * @param arms the form each name's match answers, parallel to {@code names}
 * @param miss the form a name matching no arm answers
 */
public record NameDispatch(LispSymbol subject, List<String> names, List<LispVal> arms, LispVal miss) {

	/**
	 * The dispatch {@code form} heads, or null when it is not a name test's {@code if}.
	 * @param form an {@code if} form
	 * @return the dispatch, at least one arm
	 */
	public static @Nullable NameDispatch match(LispCons form) {
		LispSymbol subject = null;
		List<String> names = new ArrayList<>();
		List<LispVal> arms = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		LispVal cur = form;
		while (cur instanceof LispCons cons && cons.car() instanceof LispSymbol head && LispNames.IF.equals(head.name())
				&& cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3 || parts.size() > 4) {
				break;
			}
			String name = testedName(parts.get(1), subject);
			if (name == null) {
				break;
			}
			if (subject == null) {
				subject = (LispSymbol) ((LispCons) parts.get(1)).toList().get(1);
			}
			if (seen.add(name)) {
				names.add(name);
				arms.add(parts.get(2));
			}
			cur = parts.size() > 3 ? parts.get(3) : LispNil.INSTANCE;
		}
		return subject == null ? null : new NameDispatch(subject, names, arms, cur);
	}

	/**
	 * The name {@code (%symbol-is v 'NAME)} tests, when {@code test} is one over a plain
	 * variable -- {@code subject} when given.
	 */
	private static @Nullable String testedName(LispVal test, @Nullable LispSymbol subject) {
		if (!(test instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& LispNames.SYMBOL_IS.equals(head.name()) && cons.isProperList())) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3 || !(parts.get(1) instanceof LispSymbol v) || v.isKeyword()
				|| subject != null && !subject.name().equals(v.name())) {
			return null;
		}
		if (parts.get(2) instanceof LispCons quoted && quoted.car() instanceof LispSymbol q
				&& LispNames.QUOTE.equals(q.name()) && quoted.cdr() instanceof LispCons body
				&& body.cdr() instanceof LispNil && body.car() instanceof LispSymbol name) {
			return name.name();
		}
		return null;
	}

	/**
	 * A binary search over sorted keys: a {@link Split} sends a key below its pivot one
	 * way, the rest the other; a {@link Leaf} is a run of positions tested one by one.
	 */
	public sealed interface Node permits Split, Leaf {

	}

	/**
	 * Keys below {@code pivot} continue in {@code below}, the rest in {@code atOrAbove}.
	 *
	 * @param pivot the first key of {@code atOrAbove}
	 * @param below the lower half
	 * @param atOrAbove the upper half
	 */
	public record Split(long pivot, Node below, Node atOrAbove) implements Node {

	}

	/**
	 * The positions {@code [from, to)} of the sorted keys, tested in turn.
	 *
	 * @param from the first position
	 * @param to one past the last
	 */
	public record Leaf(int from, int to) implements Node {

	}

	/**
	 * The search over {@code keys}: halved until a part holds at most {@code leafSize}
	 * keys, never between two equal keys (a key's every arm lands in one leaf, so the
	 * leaf's exact tests decide between names whose keys collide).
	 * @param keys the keys, ascending
	 * @param leafSize the most keys a leaf holds when it can be split
	 * @return the root
	 */
	public static Node searchTree(long[] keys, int leafSize) {
		return searchTree(keys, 0, keys.length, leafSize);
	}

	private static Node searchTree(long[] keys, int from, int to, int leafSize) {
		if (to - from <= leafSize) {
			return new Leaf(from, to);
		}
		int mid = from + (to - from) / 2;
		int split = mid;
		while (split > from && keys[split - 1] == keys[split]) {
			split--;
		}
		if (split == from) {
			split = mid + 1;
			while (split < to && keys[split - 1] == keys[split]) {
				split++;
			}
			if (split == to) {
				return new Leaf(from, to);
			}
		}
		return new Split(keys[split], searchTree(keys, from, split, leafSize), searchTree(keys, split, to, leafSize));
	}

}
