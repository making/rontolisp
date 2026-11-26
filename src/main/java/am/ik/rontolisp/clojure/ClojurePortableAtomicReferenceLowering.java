package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.util.concurrent.atomic.AtomicReference} schema keeps its fn-validation
 * switch in ({@code .kb/clojure-frontend.md}, "Portable AtomicReference"): an untagged
 * construction of no argument or of the initial value where the host is not (wasm) is the
 * deftype of the shipped internal namespace {@value #NAMESPACE}
 * ({@code lib/rontolisp/internal/atomic_reference.clj}), loaded where such a construction
 * first lowers or a receiver hinted with the class is called -- never a {@code java:new},
 * which wasm refuses at call time. Its members answer through a defined interface,
 * {@code toString} and {@code str} through its {@code Object} method. Where the host is
 * the construction keeps the host's object.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableAtomicReferenceLowering {

	/** The shipped internal namespace whose deftype is the cell. */
	static final String NAMESPACE = "rontolisp.internal.atomic-reference";

	/** The host class the construction names. */
	static final String ATOMIC_REFERENCE_CLASS = "java.util.concurrent.atomic.AtomicReference";

	private ClojurePortableAtomicReferenceLowering() {
	}

	/**
	 * An untagged construction of no argument or of one where the host is not, in a
	 * top-level datum (a run-time lowering loads no namespace, so it keeps
	 * {@code java:new}): the portable cell. The caller checks the target and that it is
	 * untagged.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @param argDatums the argument datums
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(ClojureLowering ctx, String cls, List<LispVal> argDatums) {
		if (!cls.equals(ATOMIC_REFERENCE_CLASS) || argDatums.size() > 1 || !ctx.atTopLevel()) {
			return null;
		}
		preload(ctx);
		List<LispVal> call = new ArrayList<>();
		call.add(ClojureLowering.varSym(NAMESPACE + "/atomic-reference"));
		for (LispVal datum : argDatums) {
			call.add(ctx.lower(datum));
		}
		return ClojureLowerUtil.list(call);
	}

	/**
	 * Loads the namespace for an instance call whose receiver is hinted
	 * {@code ^AtomicReference} where the host is not: the deftype's interface rows must
	 * exist when the call lowers, which may be ahead of the construction it reads. The
	 * caller checks the target.
	 * @param ctx the hub
	 * @param hint the receiver's hinted class, or null
	 */
	static void preloadForHint(ClojureLowering ctx, @Nullable String hint) {
		if (ATOMIC_REFERENCE_CLASS.equals(hint) && ctx.atTopLevel()) {
			preload(ctx);
		}
	}

	private static void preload(ClojureLowering ctx) {
		if (!ctx.loadedNamespaces.contains(NAMESPACE) && !ctx.loadingNamespaces.contains(NAMESPACE)) {
			ClojureNamespaceLowering.preload(ctx, NAMESPACE);
		}
	}

}
