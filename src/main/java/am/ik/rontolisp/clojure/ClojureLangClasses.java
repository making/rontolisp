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
 * namespace loaded.
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
	 * {@code clojure.lang.PersistentArrayMap}, whose {@code EMPTY} and
	 * {@code createAsIfByAssoc} map destructuring's expansion spells: the empty map and
	 * the map of an array's keys and values ({@link #staticCall}), with no namespace.
	 */
	static final String PERSISTENT_ARRAY_MAP = "clojure.lang.PersistentArrayMap";

	/**
	 * A static method of a {@code clojure.lang} class this front end answers in place:
	 * {@code PersistentArrayMap/createAsIfByAssoc} of an array of keys and values, the
	 * map a {@code hash-map} of them makes (a later key replaces an earlier one, an odd
	 * count is refused with the oracle's words).
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param member the method
	 * @param args the lowered arguments
	 * @return the lowered call, or null for any other method
	 */
	static @Nullable LispVal staticCall(ClojureLowering ctx, String cls, String member, List<LispVal> args) {
		if (cls.equals(PERSISTENT_ARRAY_MAP) && member.equals("createAsIfByAssoc") && args.size() == 1) {
			return ctx.lower(ClojureLowerUtil.list(new LispSymbol(ClojureCoreNames.PREFIX + "apply"),
					new LispSymbol(ClojureCoreNames.PREFIX + "hash-map"),
					ClojureLowerUtil.list(ClojureRunTime.EMBEDDED, args.get(0))));
		}
		return null;
	}

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
		if (cls.equals(PERSISTENT_ARRAY_MAP) && member.equals("EMPTY")) {
			// the empty map, which map destructuring's expansion spells
			return ctx.lower(ClojureLowerUtil.list(new LispSymbol("%hash-map")));
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
