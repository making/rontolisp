package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The core seq/map/higher-order backlog of the Clojure lowering ({@code drop-last},
 * {@code split-at}, {@code juxt}, {@code reduce-kv}, ...): each verb is one call to its
 * spliced {@code rontolisp::%clojure-} worker in {@code clojure.lisp}, after an arity
 * check worded like the oracle's, and names that worker's {@code -v} entry as a value
 * (which checks the count at run time with the same wording). {@code pmap} is
 * {@code map}: there is no thread pool on any backend, and the printed answer is the
 * same.
 *
 * <p>
 * One slice of {@link ClojureLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class ClojureCoreLowering {

	private ClojureCoreLowering() {
	}

	/**
	 * A backlog verb in call position, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @param items the call, head included
	 * @return the lowered call, or null
	 */
	static @Nullable LispVal callOf(ClojureLowering ctx, String name, List<LispVal> items) {
		int n = items.size() - 1;
		switch (name) {
			case "drop-last":
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, new LispInteger(1), ctx.lower(items.get(1)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "split-at", "take-last", "nthnext", "nthrest", "update-keys", "update-vals":
				arity(name, n, 2, 2);
				if (name.startsWith("update-")) {
					return worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)));
				}
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "split-with":
				arity(name, n, 2, 2);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "peek", "pop", "not-empty":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "dedupe":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "replace":
				arity(name, n, 1, 2);
				return n == 1 ? worker("xf-replace", ctx.lower(items.get(1)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "partition-all":
				arity(name, n, 2, 3);
				if (n == 2) {
					// the size is also the step: one evaluation, bound once
					LispSymbol size = ctx.freshTemp();
					return ClojureLowerUtil.list(ClojureLowerUtil.sym("let"),
							ClojureLowerUtil.list(List.of(ClojureLowerUtil.list(size, ctx.lower(items.get(1))))),
							worker(name, size, size, ctx.lower(items.get(2))));
				}
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
			case "partition-by":
				arity(name, n, 2, 2);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
			case "min-key", "max-key":
				arity(name, n, 2, -1);
				return ClojureLowerUtil.list(runtime("extreme-key"),
						ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)),
						name.equals("max-key") ? ClojureLowering.TRUE_CONST : ClojureLowering.NIL_CONST);
			case "juxt", "every-pred", "some-fn":
				arity(name, n, 1, -1);
				return worker(name, ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), fnValues(ctx, items, 1)));
			case "fnil":
				arity(name, n, 2, 4);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
			case "reduce-kv":
				arity(name, n, 3, 3);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)),
						ctx.lower(items.get(3)));
			case "pmap":
				arity(name, n, 2, -1);
				return ClojureSeqLowering.mapForm(ctx, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ctx.lowers(items, 2));
			case "with-meta":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "meta":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "test":
				arity(name, n, 1, 1);
				return ClojureVarLowering.testOf(ctx.lower(items.get(1)));
			case "vary-meta":
				arity(name, n, 2, -1);
				return worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)));
			case "read-string", "read":
				return ClojureReadLowering.callOf(ctx, name, items);
			default:
				return null;
		}
	}

	/**
	 * A backlog verb as a function value, or null when the name is none of them.
	 * @param ctx the hub
	 * @param name the Clojure name
	 * @return the value form, or null
	 */
	static @Nullable LispVal valueOf(ClojureLowering ctx, String name) {
		return switch (name) {
			case "drop-last", "split-at", "split-with", "take-last", "nthnext", "nthrest", "peek", "pop", "not-empty",
					"dedupe", "replace", "partition-all", "partition-by", "min-key", "max-key", "juxt", "fnil",
					"every-pred", "some-fn", "update-keys", "update-vals", "reduce-kv", "with-meta", "meta",
					"vary-meta" ->
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime(name + "-v"));
			case "pmap" -> ClojureSeqLowering.mapValue(ctx);
			case "test" -> ClojureVarLowering.testValue();
			case "read-string", "read" -> ClojureReadLowering.valueOf(ctx, name);
			default -> null;
		};
	}

	/**
	 * Reader metadata ({@code ^meta form}) in value position: on a vector, map or set
	 * literal it attaches to the fresh collection, like the oracle's reader; anywhere
	 * else (a type hint on a local, a flag on a call) it parses and drops. Nested layers
	 * merge with the outer one winning, like the oracle's reader; a keyword is {@code {:k
	 * true}}, a symbol or string {@code {:tag x}}.
	 * @param ctx the hub
	 * @param form the {@code (%with-meta form meta)} datum
	 * @return the lowered value
	 */
	static LispVal readerMetaOf(ClojureLowering ctx, LispVal form) {
		List<LispVal> layers = new ArrayList<>();
		LispVal target = form;
		List<LispVal> parts = ClojureLowerUtil.items(target);
		while (parts != null && parts.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(parts.get(0), ClojureLowerUtil.READER_META)) {
			layers.add(parts.get(2));
			target = parts.get(1);
			parts = ClojureLowerUtil.items(target);
		}
		boolean literal = parts != null && !parts.isEmpty()
				&& (parts.get(0) == ClojureReader.VECTOR || ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-map")
						|| ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-set"));
		if (!literal) {
			return ctx.lower(target);
		}
		List<LispVal> entries = new ArrayList<>();
		for (int i = layers.size() - 1; i >= 0; i--) {
			entries.addAll(metaEntries(layers.get(i)));
		}
		return ClojureLowerUtil.list(runtime("put-meta"), ctx.lower(target),
				ClojureCollectionLowering.mapBuild(ctx.lowers(entries, 0)));
	}

	/** One reader metadata datum as map entries (key and value datums). */
	static List<LispVal> metaEntries(LispVal meta) {
		if (meta instanceof LispSymbol s && s.name().startsWith(":")) {
			return List.of(s, new LispSymbol("true"));
		}
		if (meta instanceof LispSymbol || meta instanceof LispString) {
			return List.of(new LispSymbol(":tag"), ClojureLowerUtil.list(new LispSymbol("quote"), meta));
		}
		List<LispVal> parts = ClojureLowerUtil.items(meta);
		if (parts != null && !parts.isEmpty() && ClojureLowerUtil.isSymbolNamed(parts.get(0), "%hash-map")) {
			return parts.subList(1, parts.size());
		}
		throw new LispReadException("Metadata must be Symbol,Keyword,String or Map");
	}

	private static LispVal worker(String name, LispVal... args) {
		List<LispVal> out = new ArrayList<>();
		out.add(runtime(name));
		out.addAll(List.of(args));
		return ClojureLowerUtil.list(out);
	}

	private static LispSymbol runtime(String name) {
		return new LispSymbol("RONTOLISP::%CLOJURE-" + name.toUpperCase(java.util.Locale.ROOT));
	}

	private static List<LispVal> fnValues(ClojureLowering ctx, List<LispVal> items, int from) {
		List<LispVal> out = new ArrayList<>();
		for (int i = from; i < items.size(); i++) {
			out.add(ClojureBindingLowering.realFnValue(ctx, items.get(i)));
		}
		return out;
	}

	/** The oracle's arity refusal when {@code n} falls outside {@code min..max}. */
	static void arity(String name, int n, int min, int max) {
		if (n < min || (max >= 0 && n > max)) {
			throw new LispReadException("Wrong number of args (" + n + ") passed to: clojure.core/" + name);
		}
	}

}
