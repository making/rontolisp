package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.util.StringJoiner} editscript's character-level string patch builds
 * ({@code .kb/clojure-frontend.md}, "Portable StringJoiner"): an untagged construction of
 * a delimiter, or of a delimiter, a prefix and a suffix, where the host is not (wasm) is
 * the deftype of the shipped internal namespace {@value #NAMESPACE}
 * ({@code lib/rontolisp/internal/string_joiner.clj}), loaded where such a construction
 * first lowers -- never a {@code java:new}, which wasm refuses at call time. Its
 * {@code add}, {@code setEmptyValue} and {@code length} answer through a defined
 * interface, {@code toString} and {@code str} through its {@code Object} method. Where
 * the host is the construction keeps the host's object.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableStringJoinerLowering {

	/** The shipped internal namespace whose deftype is the joiner. */
	static final String NAMESPACE = "rontolisp.internal.string-joiner";

	/** The host class the construction names. */
	static final String STRING_JOINER_CLASS = "java.util.StringJoiner";

	private ClojurePortableStringJoinerLowering() {
	}

	/**
	 * Whether the construction of the class and count is the portable joiner: of a
	 * delimiter, or of a delimiter, a prefix and a suffix, in a top-level datum (a
	 * run-time lowering loads no namespace, so it keeps {@code java:new}).
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param argCount the argument count
	 * @return {@code true} when {@link #construction} applies
	 */
	static boolean constructs(ClojureLowering ctx, String cls, int argCount) {
		return cls.equals(STRING_JOINER_CLASS) && (argCount == 1 || argCount == 3) && ctx.atTopLevel();
	}

	/**
	 * A {@code java.util.StringJoiner} construction as the portable joiner, where the
	 * host is not: the caller checks the target, that the construction is untagged and
	 * {@link #constructs}.
	 * @param ctx the hub
	 * @param args the lowered arguments
	 * @return the construction
	 */
	static LispVal construction(ClojureLowering ctx, List<LispVal> args) {
		preload(ctx);
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowering.varSym(NAMESPACE + "/string-joiner"));
		call.addAll(args);
		return ClojureLowerUtil.list(call);
	}

	/**
	 * Loads the namespace for an instance call whose receiver is hinted
	 * {@code ^StringJoiner}, where the host is not: the deftype's interface rows must
	 * exist when the call lowers, and editscript's reducing function calls {@code .add}
	 * on its hinted parameter before the construction it reduces from lowers. The caller
	 * checks the target.
	 * @param ctx the hub
	 * @param hint the receiver's hinted class, or null
	 */
	static void preloadForHint(ClojureLowering ctx, @Nullable String hint) {
		if (STRING_JOINER_CLASS.equals(hint) && ctx.atTopLevel()) {
			preload(ctx);
		}
	}

	private static void preload(ClojureLowering ctx) {
		if (!ctx.loadedNamespaces.contains(NAMESPACE) && !ctx.loadingNamespaces.contains(NAMESPACE)) {
			ClojureNamespaceLowering.preload(ctx, NAMESPACE);
		}
	}

}
