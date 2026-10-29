package am.ik.rontolisp.clojure;

import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code clojure.lang} classes this front end defines as Clojure source
 * ({@code .kb/clojure-frontend.md}, "Persistent queues"): each a {@code deftype} of the
 * shipped namespace {@value #NAMESPACE} ({@code lib/clojure/lang.clj}), so its class name
 * is the oracle's and every core verb reads it through the collection interfaces its body
 * implements. The namespace loads where a top-level datum first names one of its classes
 * ({@link ClojureLowering#loadLangClass}), so a program naming none lowers as before; a
 * static field the program reads is a var of the namespace ({@link #staticValue}). A
 * static field of a {@code clojure.lang} class no namespace defines may instead be held
 * by the run-time library ({@link #runtimeStatic}), read and {@code set!} with no
 * namespace loaded, and a static method answered by it ({@link #staticCall}).
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureLangClasses {

	/** The shipped namespace whose deftypes are the classes. */
	static final String NAMESPACE = "clojure.lang";

	/** {@code clojure.lang.PersistentQueue}. */
	static final String PERSISTENT_QUEUE = "clojure.lang.PersistentQueue";

	/**
	 * Each class's static fields, by name, with the var of {@link #NAMESPACE} holding it.
	 */
	private static final Map<String, Map<String, String>> STATICS = Map.of(PERSISTENT_QUEUE,
			Map.of("EMPTY", "PersistentQueue-EMPTY"));

	/**
	 * The static fields of {@code clojure.lang} classes no namespace defines whose value
	 * the run-time library holds, by class and name, with the global holding it: a
	 * mutable flag a program reads and {@code set!}s, so no namespace loads for it.
	 * {@code RT/checkSpecAsserts} is spec.alpha's {@code check-asserts} switch.
	 */
	private static final Map<String, Map<String, String>> RUNTIME_STATICS = Map.of("clojure.lang.RT",
			Map.of("checkSpecAsserts", "RONTOLISP::%CLOJURE-CHECK-SPEC-ASSERTS"));

	private ClojureLangClasses() {
	}

	/**
	 * The run-time library's global holding a static field ({@link #RUNTIME_STATICS}).
	 * @param cls the class's binary name
	 * @param member the field's name
	 * @return the global, or null when the library holds no such field
	 */
	static @Nullable String runtimeStatic(String cls, String member) {
		Map<String, String> statics = RUNTIME_STATICS.get(cls);
		return statics == null ? null : statics.get(member);
	}

	/**
	 * The run-time library's global a {@code set!} target names: {@code Class/field} or
	 * {@code (. Class field)} of a field the library holds ({@link #RUNTIME_STATICS}).
	 * @param ctx the hub
	 * @param target the target as written
	 * @return the global, or null for any other target
	 */
	static @Nullable String runtimeStaticTarget(ClojureLowering ctx, LispVal target) {
		if (target instanceof LispSymbol s) {
			int slash = ClojureLowering.qualifierSlash(s.name());
			String head = slash < 0 ? null : s.name().substring(0, slash);
			return head == null || !ClojureNamespaceLowering.isClasslike(ctx, head) ? null
					: runtimeStatic(ClojureNamespaceLowering.resolveClass(ctx, head), s.name().substring(slash + 1));
		}
		List<LispVal> parts = ClojureLowerUtil.items(target);
		if (parts == null || parts.size() != 3 || !ClojureLowerUtil.isSymbolNamed(parts.get(0), ".")
				|| !(parts.get(1) instanceof LispSymbol cls) || !(parts.get(2) instanceof LispSymbol field)
				|| !ClojureNamespaceLowering.isClasslike(ctx, cls.name())) {
			return null;
		}
		return runtimeStatic(ClojureNamespaceLowering.resolveClass(ctx, cls.name()), field.name());
	}

	/**
	 * A static method of a {@code clojure.lang} class no namespace defines, answered on
	 * every backend by the run-time library: no {@code clojure.lang} class loads here.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param member the method
	 * @param args the lowered arguments
	 * @return the form, or null when the library answers no such method
	 */
	static @Nullable LispVal staticCall(ClojureLowering ctx, String cls, String member, List<LispVal> args) {
		if (!cls.startsWith("clojure.lang.")) {
			return null;
		}
		String call = cls.substring(cls.lastIndexOf('.') + 1) + "/" + member + "/" + args.size();
		return switch (call) {
			// the applyTo an IFn body delegates to: the function called on the members
			// of the argument seq by their count
			case "AFn/applyToHelper/2" -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CALL"), args.get(0),
					ClojureSeqLowering.seqAllForm(ctx, args.get(1)));
			// a map entry is the two-member vector here
			case "MapEntry/create/2" -> ClojureLowerUtil.list(ClojureLowerUtil.sym("vector"), args.get(0), args.get(1));
			// an owned array is already the vector here (object-array answers one), so
			// taking ownership is vec, like the oracle's :bb branch (malli's -vmap)
			case "LazilyPersistentVector/createOwning/1" -> ClojureCollectionLowering.vecForm(ctx, args.get(0));
			// the duplicate-checking map build (malli's -eager-entry-parser); RT/map
			// is the same build over its array (tools.reader's map reader)
			case "PersistentArrayMap/createWithCheck/1", "RT/map/1" ->
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-CREATE-WITH-CHECK"), args.get(0));
			// the duplicate-checking set build over a List's members (tools.reader's
			// set reader)
			case "PersistentHashSet/createWithCheck/1" -> ClojureLowerUtil.list(
					new LispSymbol("RONTOLISP::%CLOJURE-SET-LITERAL"), ClojureSeqLowering.seqAllForm(ctx, args.get(0)));
			// a List's members as a list, the empty list nil (tools.reader's list
			// reader)
			case "PersistentList/create/1" -> ClojureSeqLowering.seqAllForm(ctx, args.get(0));
			// an Iterable's own iterator, else the iterator over the seq
			case "RT/iter/1" -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-ITER"), args.get(0));
			// the next of the ids the oracle's gensym and auto-gensym draw from
			// (tools.reader's syntax-quote)
			case "RT/nextID/0" -> ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-RT-NEXT-ID"));
			// a class name spelled back as the var it was munged from, $ as / (spec's
			// fn-sym)
			case "Compiler/demunge/1" ->
				ClojureLowerUtil.list(new LispSymbol("RONTOLISP::%CLOJURE-DEMUNGE"), args.get(0));
			// a BigInteger is an integer here, as a BigInt is, and a BigInt the long
			// it reduces to is the same integer (tools.reader's number reader)
			case "BigInt/fromBigInteger/1", "Numbers/reduceBigInt/1" -> args.get(0);
			default -> null;
		};
	}

	/**
	 * Whether the class is one of these.
	 * @param fqn the class's binary name
	 * @return whether {@link #NAMESPACE} defines it
	 */
	static boolean isLangClass(String fqn) {
		return STATICS.containsKey(fqn);
	}

	/**
	 * A {@code Class/FIELD} spelling naming a static field of one of these classes: the
	 * read of the var holding it, the namespace loaded first.
	 * @param ctx the hub
	 * @param name the spelling, as written
	 * @return the lowered read, or null when the spelling names no such field
	 */
	static @Nullable LispVal staticValue(ClojureLowering ctx, String name) {
		int slash = ClojureLowering.qualifierSlash(name);
		if (slash < 0) {
			return null;
		}
		String head = name.substring(0, slash);
		if (!ClojureNamespaceLowering.isClasslike(ctx, head)) {
			return null;
		}
		return staticValue(ctx, ClojureNamespaceLowering.resolveClass(ctx, head), name.substring(slash + 1));
	}

	/**
	 * {@link #staticValue(ClojureLowering, String)} over a resolved class and a member.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param member the member
	 * @return the lowered read, or null when it names no such field
	 */
	static @Nullable LispVal staticValue(ClojureLowering ctx, String cls, String member) {
		String global = runtimeStatic(cls, member);
		if (global != null) {
			return new LispSymbol(global);
		}
		Map<String, String> statics = STATICS.get(cls);
		String var = statics == null ? null : statics.get(member);
		if (var == null) {
			return null;
		}
		ctx.loadLangClass(cls);
		return ctx.lower(new LispSymbol(NAMESPACE + "/" + var));
	}

}
