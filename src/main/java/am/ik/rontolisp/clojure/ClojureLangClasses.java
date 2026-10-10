package am.ik.rontolisp.clojure;

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
 * static field the program reads is a var of the namespace ({@link #staticValue}).
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

	private ClojureLangClasses() {
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
		Map<String, String> statics = STATICS.get(cls);
		String var = statics == null ? null : statics.get(member);
		if (var == null) {
			return null;
		}
		ctx.loadLangClass(cls);
		return ctx.lower(new LispSymbol(NAMESPACE + "/" + var));
	}

}
