package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispHashTable;
import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Loop forms of the Clojure lowering: threading, doseq/dototimes/for and comprehension
 * machinery.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureLoopLowering {

	private ClojureLoopLowering() {
	}

	/**
	 * {@code (-> x form...)}: each step with the value inserted second (a bare name calls
	 * with it, a keyword step reads through it); a pure datum rewrite.
	 */
	static LispVal threadFirst(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "-> takes a value and forms to thread it through");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			acc = threadInsert(items.get(i), acc, false);
		}
		return ctx.lower(acc);
	}

	/**
	 * {@code (->> x form...)}: each step with the value appended last; a pure datum
	 * rewrite like {@link #threadFirst}.
	 */
	static LispVal threadLast(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "->> takes a value and forms to thread it through");
		LispVal acc = items.get(1);
		for (int i = 2; i < items.size(); i++) {
			acc = threadInsert(items.get(i), acc, true);
		}
		return ctx.lower(acc);
	}

	/**
	 * One threading step around the threaded datum: a proper list not headed by a reader
	 * marker takes the value second (first) or last; anything else -- a bare name, a
	 * keyword, a literal -- calls or reads with it. A list headed by another list inserts
	 * blindly, like the oracle's purely syntactic rule; a marker-headed literal cannot
	 * take the value and signals when called.
	 */
	static LispVal threadInsert(LispVal form, LispVal acc, boolean last) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		if (parts != null && !parts.isEmpty() && !isThreadAtomHead(parts.get(0))) {
			List<LispVal> out = new ArrayList<>();
			out.add(parts.get(0));
			if (!last) {
				out.add(acc);
			}
			out.addAll(parts.subList(1, parts.size()));
			if (last) {
				out.add(acc);
			}
			return ClojureLowerUtil.list(out);
		}
		return ClojureLowerUtil.list(List.of(form, acc));
	}

	/** A threading step head that must not be inserted into: a reader marker. */
	static boolean isThreadAtomHead(LispVal head) {
		return head instanceof LispSymbol s && s.name().startsWith("%");
	}

	/**
	 * {@code (as-> x name form...)}: nested {@code let}s rebinding the name step by step,
	 * so shadowing matches the oracle exactly.
	 */
	static LispVal threadAs(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 3, "as-> takes a value, a name and forms");
		String name = ClojureLowerUtil.plainName(items.get(2), "as->");
		if (items.size() == 3) {
			return ctx.lower(items.get(1));
		}
		LispVal acc = items.get(items.size() - 1);
		for (int i = items.size() - 2; i >= 3; i--) {
			acc = ClojureBindingLowering.letDatum(name, items.get(i), acc);
		}
		return ctx.lower(ClojureBindingLowering.letDatum(name, items.get(1), acc));
	}

	/**
	 * {@code (doto x form...)}: each step threaded first around one temporary, answering
	 * the original value.
	 */
	static LispVal dotoOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "doto takes a value and forms");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		String temp = "doto-" + ctx.counter++;
		scope.put(temp, ClojureLowering.Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return ctx.inScope(scope, () -> {
			List<LispVal> form = new ArrayList<>();
			form.add(ClojureLowerUtil.sym("let"));
			form.add(ClojureLowerUtil
				.list(List.of(ClojureLowerUtil.list(ClojureLowerUtil.idSym(temp), ctx.lower(items.get(1))))));
			for (int i = 2; i < items.size(); i++) {
				form.add(ctx.lower(threadInsert(items.get(i), ref, false)));
			}
			form.add(ClojureLowerUtil.idSym(temp));
			return ClojureLowerUtil.list(form);
		});
	}

	/**
	 * {@code (cond-> x test form...)} (or {@code cond->>} threading last): each pair
	 * rebinds one temporary to the running value and threads only when its test is
	 * truthy.
	 */
	static LispVal condThread(ClojureLowering ctx, List<LispVal> items, boolean last) {
		String arrow = last ? "cond->>" : "cond->";
		ClojureLowerUtil.isTrue(items.size() >= 2 && items.size() % 2 == 0,
				arrow + " takes a value and test/form pairs");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		String temp = "condthread-" + ctx.counter++;
		scope.put(temp, ClojureLowering.Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return ctx.inScope(scope, () -> {
			LispVal acc = items.get(1);
			for (int i = 2; i + 1 < items.size(); i += 2) {
				acc = ClojureBindingLowering.letDatum(temp, acc, ClojureLowerUtil
					.list(List.of(new LispSymbol("if"), items.get(i), threadInsert(items.get(i + 1), ref, last), ref)));
			}
			return ctx.lower(acc);
		});
	}

	/**
	 * {@code (some-> x form...)} (or {@code some->>} threading last): each step threaded
	 * around one temporary, short-circuiting to nil when it is nil -- but not when it is
	 * false, like the oracle.
	 */
	static LispVal someThread(ClojureLowering ctx, List<LispVal> items, boolean last) {
		String arrow = last ? "some->>" : "some->";
		ClojureLowerUtil.isTrue(items.size() >= 2, arrow + " takes a value and forms");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		String temp = "somethread-" + ctx.counter++;
		scope.put(temp, ClojureLowering.Kind.VARIABLE);
		LispSymbol ref = new LispSymbol(temp);
		return ctx.inScope(scope, () -> {
			LispVal acc = items.get(1);
			for (int i = 2; i < items.size(); i++) {
				acc = ClojureBindingLowering.letDatum(temp, acc,
						ClojureLowerUtil.list(List.of(new LispSymbol("if"),
								ClojureLowerUtil.list(List.of(new LispSymbol("nil?"), ref)), LispNil.INSTANCE,
								threadInsert(items.get(i), ref, last))));
			}
			return ctx.lower(acc);
		});
	}

	/**
	 * {@code list*}: a right fold of {@code cons} over the seq view -- of one argument,
	 * just its seq, signalling for a non-collection like the oracle.
	 */
	static LispVal listStar(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "list* takes a value and more collections");
		if (items.size() == 2) {
			return ClojureSeqLowering.seqForm(ctx, ctx.lower(items.get(1)));
		}
		LispVal acc = ctx.lower(items.get(items.size() - 1));
		for (int i = items.size() - 2; i >= 1; i--) {
			acc = consForm(ctx, ctx.lower(items.get(i)), acc);
		}
		return acc;
	}

	static LispVal consForm(ClojureLowering ctx, LispVal item, LispVal coll) {
		return ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CONS"), item, coll);
	}

	/**
	 * {@code doseq}: side-effecting iteration over the seq view, answering nil. One
	 * {@code dolist} per binding pair (which the macro expander already shares with every
	 * backend), nested left to right; patterns destructure through the same {@code let}
	 * lowering; {@code :when} skips the element, {@code :while} ends its level's loop
	 * through a block (an outer level's ends the whole form), {@code :let} binds
	 * sequentially. An empty binding vector runs the body once.
	 */
	static LispVal doseqOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "doseq takes a binding vector and a body");
		List<ClojureLowering.SeqLevel> levels = seqLevels(ClojureLowerUtil.bindingItems(items.get(1), "doseq"),
				"doseq");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		for (ClojureLowering.SeqLevel level : levels) {
			collectSeqNames(level, scope);
		}
		return ctx.inScope(scope, () -> {
			// the body answers nil through the loops, never the target: a recur
			// inside one is not in tail position, like the oracle
			LispVal inner = ctx.nonTailBody(items, 2);
			for (int i = levels.size() - 1; i >= 0; i--) {
				inner = seqLevel(ctx, levels.get(i), inner, scope, "doseq");
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("progn"), inner, ClojureLowering.NIL_CONST);
		});
	}

	/**
	 * {@code dotimes}: one strict binding over the integers below the count, answering
	 * nil -- the core {@code dotimes} the macro expander already shares. The count runs
	 * through {@code truncate} first, the oracle's {@code intCast} cast in lowering form:
	 * a float counts its truncation ({@code 2.5} runs {@code 0 1}), and a non-number
	 * signals there instead of in the loop's comparison.
	 */
	static LispVal dotimesOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() >= 2, "dotimes takes a binding vector and a body");
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(items.get(1), "dotimes");
		ClojureLowerUtil.isTrue(bindings.size() == 2, "dotimes takes exactly one name and count");
		String name = ClojureLowerUtil.plainName(bindings.get(0), "dotimes");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		scope.put(name, ClojureLowering.Kind.VARIABLE);
		LispVal count = ClojureLowerUtil.list(ClojureLowerUtil.sym("truncate"), ctx.lower(bindings.get(1)));
		// the body answers nil through the loop, never the target: a recur inside
		// one is not in tail position, like the oracle
		return ctx.inScope(scope, () -> ClojureLowerUtil.list(ClojureLowerUtil.sym("dotimes"),
				ClojureLowerUtil.list(List.of(ClojureLowerUtil.idSym(name), count)), ctx.nonTailBody(items, 2)));
	}

	/**
	 * {@code for}: a strict list comprehension over the seq view -- nested {@code dolist}
	 * loops accumulating in reverse, like {@code take}'s labels walk, so no backend
	 * learns a representation. Modifiers behave per level, left to right: {@code :when}
	 * skips the element, {@code :while} ends its level's loop (an outer level's ends the
	 * whole comprehension), {@code :let} binds sequentially. Answers the strict list,
	 * {@code nil} when empty (the {@code rest}/{@code take} divergence, not {@code ()}).
	 */
	static LispVal forOf(ClojureLowering ctx, List<LispVal> items) {
		ClojureLowerUtil.isTrue(items.size() == 3, "for takes a binding vector and a body");
		List<ClojureLowering.SeqLevel> levels = seqLevels(ClojureLowerUtil.bindingItems(items.get(1), "for"), "for");
		ClojureLowerUtil.isTrue(!levels.isEmpty(), "for takes at least one binding pair");
		Map<String, ClojureLowering.Kind> scope = new HashMap<>();
		for (ClojureLowering.SeqLevel level : levels) {
			collectSeqNames(level, scope);
		}
		return ctx.inScope(scope, () -> {
			LispSymbol acc = ctx.freshTemp();
			LispVal inner = ClojureLowerUtil.list(ClojureLowerUtil.sym("setq"), acc,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("cons"), ctx.lower(items.get(2)), acc));
			for (int i = levels.size() - 1; i >= 0; i--) {
				inner = seqLevel(ctx, levels.get(i), inner, scope, "for");
			}
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(acc, ClojureLowering.NIL_CONST))), inner,
					ClojureLowerUtil.list(ClojureLowerUtil.sym("reverse"), acc));
		});
	}

	/**
	 * The levels of a {@code doseq}/{@code for} binding vector: pattern/collection pairs,
	 * each trailed by its {@code :when}/{@code :while}/{@code :let} modifiers in order.
	 * Any other keyword is the oracle's {@code Invalid ... keyword} refusal.
	 */
	static List<ClojureLowering.SeqLevel> seqLevels(List<LispVal> bindings, String owner) {
		List<ClojureLowering.SeqLevel> levels = new ArrayList<>();
		int i = 0;
		while (i < bindings.size()) {
			LispVal head = bindings.get(i);
			if (head instanceof LispSymbol keyword && keyword.name().startsWith(":")) {
				if (keyword.name().equals(":when") || keyword.name().equals(":while")
						|| keyword.name().equals(":let")) {
					throw new LispReadException(
							"Invalid '" + owner + "' keyword " + keyword.name() + " without a binding before it");
				}
				throw new LispReadException("Invalid '" + owner + "' keyword " + keyword.name());
			}
			ClojureLowerUtil.isTrue(i + 1 < bindings.size(),
					"a " + owner + " binding vector pairs a name with a value");
			LispVal pattern = head;
			LispVal coll = bindings.get(i + 1);
			i += 2;
			List<ClojureLowering.SeqModifier> modifiers = new ArrayList<>();
			while (i < bindings.size() && bindings.get(i) instanceof LispSymbol trailer
					&& trailer.name().startsWith(":")) {
				String kind = trailer.name();
				ClojureLowerUtil.isTrue(kind.equals(":when") || kind.equals(":while") || kind.equals(":let"),
						"Invalid '" + owner + "' keyword " + kind);
				ClojureLowerUtil.isTrue(i + 1 < bindings.size(), owner + " " + kind + " takes a form after it");
				modifiers.add(new ClojureLowering.SeqModifier(kind, bindings.get(i + 1)));
				i += 2;
			}
			levels.add(new ClojureLowering.SeqLevel(pattern, coll, List.copyOf(modifiers)));
		}
		return levels;
	}

	/**
	 * One binding level wrapped around its inner content: the collection's seq view
	 * iterated by {@code dolist} (patterns through the {@code let} destructuring), the
	 * level's modifiers applied in order around the content. A {@code :while} ends the
	 * level's own loop through a block, so an outer level's ends the whole
	 * {@code doseq}/{@code for} while an inner one's lets the outer loops continue, like
	 * the oracle's.
	 */
	static LispVal seqLevel(ClojureLowering ctx, ClojureLowering.SeqLevel level, LispVal inner,
			Map<String, ClojureLowering.Kind> scope, String owner) {
		LispVal seq = ClojureSeqLowering.seqForm(ctx, ctx.lower(level.coll()));
		LispVal wrap = inner;
		LispSymbol whileBlock = null;
		for (int m = level.modifiers().size() - 1; m >= 0; m--) {
			ClojureLowering.SeqModifier modifier = level.modifiers().get(m);
			switch (modifier.kind()) {
				case ":when" -> wrap = ctx.ifFalsey(ctx.lower(modifier.datum()), wrap, ClojureLowering.NIL_CONST);
				case ":while" -> {
					if (whileBlock == null) {
						whileBlock = ctx.freshTemp();
					}
					LispSymbol stop = whileBlock;
					wrap = ctx.ifFalsey(ctx.lower(modifier.datum()), wrap, ClojureLowerUtil
						.list(ClojureLowerUtil.sym("return-from"), stop, ClojureLowering.NIL_CONST));
				}
				case ":let" -> wrap = seqLetOf(ctx, modifier.datum(), wrap, scope, owner);
				default -> throw new LispReadException("Invalid '" + owner + "' keyword " + modifier.kind());
			}
		}
		LispVal loopForm = dolistOf(ctx, level.pattern(), seq, wrap, scope, owner);
		if (whileBlock != null) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("block"), whileBlock, loopForm);
		}
		return loopForm;
	}

	/**
	 * One {@code dolist} over an already-lowered seq view: a plain name binds the element
	 * directly, a pattern through the {@code let} destructuring over a temporary.
	 */
	static LispVal dolistOf(ClojureLowering ctx, LispVal pattern, LispVal seq, LispVal wrap,
			Map<String, ClojureLowering.Kind> scope, String owner) {
		if (pattern instanceof LispSymbol) {
			String name = ClojureLowerUtil.plainName(pattern, owner);
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"),
					ClojureLowerUtil.list(List.of(ClojureLowerUtil.idSym(name), seq)), wrap);
		}
		LispSymbol temp = ctx.freshTemp();
		List<LispVal> pairs = new ArrayList<>();
		ClojureBindingLowering.destructureInto(ctx, pattern, temp, pairs, scope, owner);
		if (pairs.isEmpty()) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(temp, seq)),
					wrap);
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("dolist"), ClojureLowerUtil.list(List.of(temp, seq)),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs), wrap));
	}

	/**
	 * A {@code :let} modifier's binding vector around its level's content: sequential
	 * pairs through the {@code let} destructuring, like {@code let} itself.
	 */
	static LispVal seqLetOf(ClojureLowering ctx, LispVal letVector, LispVal wrap,
			Map<String, ClojureLowering.Kind> scope, String owner) {
		List<LispVal> bindings = ClojureLowerUtil.bindingItems(letVector, owner + " :let");
		ClojureLowerUtil.isTrue(bindings.size() % 2 == 0, "a " + owner + " :let vector pairs a name with a value");
		List<LispVal> pairs = new ArrayList<>();
		for (int i = 0; i < bindings.size(); i += 2) {
			LispVal pattern = ClojureLowerUtil.stripMeta(bindings.get(i));
			if (pattern instanceof LispSymbol) {
				String name = ClojureLowerUtil.plainName(pattern, owner + " :let");
				pairs.add(ClojureLowerUtil.list(ClojureLowerUtil.idSym(name), ctx.lower(bindings.get(i + 1))));
				scope.put(name, ClojureLowering.Kind.VARIABLE);
				continue;
			}
			LispSymbol temp = ctx.freshTemp();
			pairs.add(ClojureLowerUtil.list(temp, ctx.lower(bindings.get(i + 1))));
			ClojureBindingLowering.destructureInto(ctx, pattern, temp, pairs, scope, owner + " :let");
		}
		if (pairs.isEmpty()) {
			return wrap;
		}
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("let*"), ClojureLowerUtil.list(pairs), wrap);
	}

	/**
	 * Every name a {@code doseq}/{@code for} level binds, registered before anything
	 * lowers: a later collection (or the body) may use an earlier binding, like
	 * {@code let*}'s sequential scope. Lenient by design -- anything malformed stays for
	 * the lowering to refuse with the {@code let} shape.
	 */
	static void collectSeqNames(ClojureLowering.SeqLevel level, Map<String, ClojureLowering.Kind> scope) {
		collectPatternNames(level.pattern(), scope);
		for (ClojureLowering.SeqModifier modifier : level.modifiers()) {
			if (modifier.kind().equals(":let")) {
				collectLetNames(modifier.datum(), scope);
			}
		}
	}

	/**
	 * The names a binding pattern binds, without lowering: a plain name binds directly, a
	 * vector positionally ({@code &} the rest, {@code :as} the whole), a map through
	 * {@code :keys}/{@code :syms}/{@code :strs}, explicit locals, {@code :as} and nested
	 * patterns -- mirroring {@code destructureInto}, which still owns every refusal.
	 */
	static void collectPatternNames(LispVal pattern, Map<String, ClojureLowering.Kind> scope) {
		if (pattern instanceof LispSymbol name) {
			if (!name.name().startsWith(":") && !name.name().equals("&")) {
				scope.put(name.name(), ClojureLowering.Kind.VARIABLE);
			}
			return;
		}
		List<LispVal> elements = ClojureLowerUtil.items(pattern);
		if (elements == null || elements.isEmpty()) {
			return;
		}
		if (elements.get(0) == ClojureReader.VECTOR) {
			List<LispVal> rest = elements.subList(1, elements.size());
			for (int i = 0; i < rest.size(); i++) {
				LispVal element = rest.get(i);
				if (ClojureLowerUtil.isSymbolNamed(element, ":as")) {
					if (i + 1 < rest.size() && rest.get(i + 1) instanceof LispSymbol named
							&& !named.name().startsWith(":") && !named.name().equals("&")) {
						scope.put(named.name(), ClojureLowering.Kind.VARIABLE);
					}
					i++;
					continue;
				}
				if (ClojureLowerUtil.isSymbolNamed(element, "&")) {
					if (i + 1 < rest.size()) {
						collectPatternNames(rest.get(i + 1), scope);
					}
					break;
				}
				collectPatternNames(element, scope);
			}
			return;
		}
		if (ClojureLowerUtil.isSymbolNamed(elements.get(0), "%hash-map")) {
			List<LispVal> entries = elements.subList(1, elements.size());
			for (int i = 0; i + 1 < entries.size(); i += 2) {
				LispVal head = entries.get(i);
				LispVal arg = entries.get(i + 1);
				if (ClojureLowerUtil.isSymbolNamed(head, ":or")) {
					continue;
				}
				if (ClojureLowerUtil.isSymbolNamed(head, ":as")) {
					if (arg instanceof LispSymbol named && !named.name().startsWith(":") && !named.name().equals("&")) {
						scope.put(named.name(), ClojureLowering.Kind.VARIABLE);
					}
					continue;
				}
				if (head instanceof LispSymbol kind && (kind.name().equals(":keys") || kind.name().equals(":syms")
						|| kind.name().equals(":strs"))) {
					collectKeyNames(kind.name(), arg, scope);
					continue;
				}
				if (head instanceof LispSymbol named && !named.name().startsWith(":")) {
					scope.put(named.name(), ClojureLowering.Kind.VARIABLE);
					continue;
				}
				collectPatternNames(head, scope);
			}
		}
	}

	/**
	 * The locals one {@code :keys}/{@code :syms}/{@code :strs} directive binds: each
	 * entry its local (a {@code :keys} entry may qualify, binding the short name) --
	 * mirroring {@code bindKeys}.
	 */
	static void collectKeyNames(String kind, LispVal names, Map<String, ClojureLowering.Kind> scope) {
		List<LispVal> elements = ClojureLowerUtil.items(names);
		if (elements == null || elements.isEmpty() || elements.get(0) != ClojureReader.VECTOR) {
			return;
		}
		for (LispVal element : elements.subList(1, elements.size())) {
			if (element instanceof LispSymbol spelled && !spelled.name().startsWith(":")
					&& !spelled.name().equals("&")) {
				String local = spelled.name();
				if ((kind.equals(":keys") || kind.equals(":syms")) && local.lastIndexOf('/') >= 0) {
					local = local.substring(local.lastIndexOf('/') + 1);
					if (local.isEmpty()) {
						continue;
					}
				}
				scope.put(local, ClojureLowering.Kind.VARIABLE);
			}
		}
	}

	/** The names a {@code :let} modifier's binding vector binds, without lowering. */
	static void collectLetNames(LispVal letVector, Map<String, ClojureLowering.Kind> scope) {
		List<LispVal> found = ClojureLowerUtil.items(letVector);
		if (found == null || found.isEmpty() || found.get(0) != ClojureReader.VECTOR) {
			return;
		}
		List<LispVal> bindings = found.subList(1, found.size());
		for (int i = 0; i + 1 < bindings.size(); i += 2) {
			collectPatternNames(bindings.get(i), scope);
		}
	}

}
