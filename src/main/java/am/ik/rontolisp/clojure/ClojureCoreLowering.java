package am.ik.rontolisp.clojure;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispRatio;
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
			case "empty", "double", "float", "byte", "short", "num", "bigint", "biginteger", "bigdec", "rationalize",
					"numerator", "denominator", "unchecked-int", "unchecked-long", "unchecked-short", "unchecked-byte",
					"unchecked-char", "unchecked-double", "unchecked-float":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "int", "long":
				arity(name, n, 1, 1);
				return castOf(ctx, name, items.get(1));
			case "unchecked-inc", "unchecked-dec", "unchecked-negate", "unchecked-inc-int", "unchecked-dec-int",
					"unchecked-negate-int":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "unchecked-add", "unchecked-subtract", "unchecked-multiply", "unchecked-add-int",
					"unchecked-subtract-int", "unchecked-multiply-int", "unchecked-divide-int",
					"unchecked-remainder-int":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "comparator":
				arity(name, n, 1, 1);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)));
			case "hash-set":
				return worker("set-of", ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 1)));
			case "find":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "key", "val", "map-entry?":
				arity(name, n, 1, 1);
				return worker("map-entry?".equals(name) ? "map-entry-p" : name, ctx.lower(items.get(1)));
			case "rseq":
				// a sorted map or set walks backwards through its items vector (a view a
				// program building no sorted collection sheds, ClojureArms)
				arity(name, n, 1, 1);
				return worker(name, worker("sorted-items", ctx.lower(items.get(1))));
			case "find-keyword":
				arity(name, n, 1, 2);
				return n == 1 ? worker(name, ctx.lower(items.get(1)))
						: worker("find-keyword-2", ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "subvec":
				arity(name, n, 2, 3);
				return n == 2 ? worker("subvec-from", ctx.lower(items.get(1)), ctx.lower(items.get(2)))
						: worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)), ctx.lower(items.get(3)));
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
			case "iteration":
				// the options stay a run-time list: one map, or keyword arguments the
				// worker reads like the oracle's destructuring of them
				arity(name, n, 1, -1);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 2)));
			case "run!":
				arity(name, n, 2, 2);
				return worker(name, ClojureBindingLowering.realFnValue(ctx, items.get(1)), ctx.lower(items.get(2)));
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
			case "var-get":
				arity(name, n, 1, 1);
				return ClojureVarLowering.getOf(ctx.lower(items.get(1)));
			case "the-ns", "find-ns", "ns-name":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)), ctx.knownNamespaces());
			case "vary-meta":
				arity(name, n, 2, -1);
				return worker(name, ctx.lower(items.get(1)), ClojureBindingLowering.realFnValue(ctx, items.get(2)),
						ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), ctx.lowers(items, 3)));
			case "read-string", "read":
				return ClojureReadLowering.callOf(ctx, name, items);
			case "reader-conditional", "tagged-literal":
				arity(name, n, 2, 2);
				return worker(name, ctx.lower(items.get(1)), ctx.lower(items.get(2)));
			case "inst-ms", "inst-ms*":
				// the Inst protocol's one method: a Date's or a Timestamp's
				// milliseconds, a host Date's or Instant's
				arity(name, n, 1, 1);
				return worker("inst-ms", ctx.lower(items.get(1)));
			case "parse-uuid":
				arity(name, n, 1, 1);
				return worker(name, ctx.lower(items.get(1)));
			case "random-uuid":
				arity(name, n, 0, 0);
				return worker(name);
			case "read-line":
				// the next line of *in*, nil past the end, a closed one's IOException,
				// like the oracle's
				arity(name, n, 0, 0);
				return ClojureLowerUtil.list(ClojureLowerUtil.sym("read-line"),
						ClojureStringLowering.openReader(new LispSymbol("*STANDARD-INPUT*")), ClojureLowering.NIL_CONST,
						ClojureLowering.NIL_CONST);
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
					"dedupe", "replace", "find", "subvec", "key", "val", "rseq", "find-keyword", "partition-all",
					"partition-by", "min-key", "max-key", "juxt", "fnil", "every-pred", "some-fn", "update-keys",
					"update-vals", "reduce-kv", "with-meta", "meta", "vary-meta", "empty", "comparator", "hash-set",
					"int", "long", "double", "float", "byte", "short", "num", "bigint", "biginteger", "bigdec",
					"rationalize", "numerator", "denominator", "unchecked-int", "unchecked-long", "unchecked-short",
					"unchecked-byte", "unchecked-char", "unchecked-double", "unchecked-float", "unchecked-inc",
					"unchecked-dec", "unchecked-negate", "unchecked-inc-int", "unchecked-dec-int",
					"unchecked-negate-int", "unchecked-add", "unchecked-subtract", "unchecked-multiply",
					"unchecked-add-int", "unchecked-subtract-int", "unchecked-multiply-int", "unchecked-divide-int",
					"unchecked-remainder-int", "run!", "iteration", "println", "print", "prn", "pr", "read-line",
					"reader-conditional", "tagged-literal", "inst-ms", "parse-uuid", "random-uuid" ->
				ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime(name + "-v"));
			case "inst-ms*" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("inst-ms-v"));
			case "map-entry?" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("function"), runtime("map-entry-p-v"));
			case "pmap" -> ClojureSeqLowering.mapValue(ctx);
			case "test" -> ClojureVarLowering.testValue();
			case "var-get" -> ClojureVarLowering.getValue();
			case "the-ns", "find-ns", "ns-name" -> {
				LispSymbol arg = ctx.freshTemp();
				yield ClojureLowerUtil.list(ClojureLowerUtil.sym("lambda"), ClojureLowerUtil.list(arg),
						worker(name, arg, ctx.knownNamespaces()));
			}
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
			LispVal tags = ClojureInteropLowering.paramTags(form);
			if (tags != null && target instanceof LispSymbol member && ctx.hostMemberName(member.name())) {
				// ^[types] Class/member as a value: the tags name the overload
				LispVal tagged = ClojureInteropLowering.memberValue(ctx, member.name(), tags);
				if (tagged != null) {
					return tagged;
				}
			}
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

	/**
	 * {@code (int x)} / {@code (long x)}: the oracle's {@code RT.intCast} /
	 * {@code RT.longCast} of an object ({@code %clojure-int-cast} /
	 * {@code %clojure-long-cast}). A literal argument folds here, through the overload
	 * the oracle's compiler picks for its type: a double literal takes the {@code double}
	 * cast, so {@code (int 1e10)} refuses with {@code Value out of range for int:
	 * 1.0E10} where the object cast of the same value refuses with {@code integer
	 * overflow}. A {@code count} is an int already and is not cast.
	 * @param ctx the hub
	 * @param name {@code int} or {@code long}
	 * @param arg the argument datum
	 * @return the folded value or refusal, or the cast call
	 */
	static LispVal castOf(ClojureLowering ctx, String name, LispVal arg) {
		boolean toInt = name.equals("int");
		LispVal folded = foldedCast(toInt, arg);
		if (folded != null) {
			return folded;
		}
		LispVal lowered = ctx.lower(arg);
		return isCoreCall(ctx, arg, "count") ? lowered : worker(toInt ? "int-cast" : "long-cast", lowered);
	}

	/** Whether {@code form} is a one-argument call of the core function {@code name}. */
	private static boolean isCoreCall(ClojureLowering ctx, LispVal form, String name) {
		List<LispVal> parts = ClojureLowerUtil.items(form);
		if (parts == null || parts.size() != 2 || !(parts.get(0) instanceof LispSymbol head)) {
			return false;
		}
		return name.equals(ClojureCoreNames.coreSpelling(head.name()))
				|| (head.name().equals(name) && !ctx.known(name));
	}

	/**
	 * The cast of a literal number or character, or null when {@code arg} is none:
	 * {@code RT.longCast} / {@code RT.intCast} of the literal's type ({@code long},
	 * {@code double}, or the object casts of a bigint and a ratio).
	 */
	private static @Nullable LispVal foldedCast(boolean toInt, LispVal arg) {
		if (arg instanceof LispDouble(double x)) {
			if (toInt) {
				return (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) ? outOfRange("int", Double.toString(x))
						: new LispInteger((int) x);
			}
			return (x < Long.MIN_VALUE || x > Long.MAX_VALUE) ? outOfRange("long", Double.toString(x))
					: new LispInteger((long) x);
		}
		BigInteger n;
		if (arg instanceof LispInteger(long value)) {
			n = BigInteger.valueOf(value);
		}
		else if (arg instanceof LispBigInteger(BigInteger value)) {
			n = value;
		}
		else if (arg instanceof LispRatio(BigInteger numerator, BigInteger denominator)) {
			n = numerator.divide(denominator);
		}
		else if (arg instanceof LispChar(int codePoint)) {
			n = BigInteger.valueOf(codePoint);
		}
		else {
			return null;
		}
		if (n.bitLength() >= 64) {
			return outOfRange("long", n.toString());
		}
		long value = n.longValue();
		if (toInt && (int) value != value) {
			return ClojureRefusals.refusal(ClojureRefusals.ARITHMETIC, LispString.literal("integer overflow"));
		}
		return new LispInteger(value);
	}

	private static LispVal outOfRange(String kind, String spelled) {
		return ClojureRefusals.refusal(ClojureRefusals.ILLEGAL_ARGUMENT,
				LispString.literal("Value out of range for " + kind + ": " + spelled));
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
