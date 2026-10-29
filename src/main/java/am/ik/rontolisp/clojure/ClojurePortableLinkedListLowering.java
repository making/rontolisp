package am.ik.rontolisp.clojure;

import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.util.LinkedList} tools.reader's {@code read} makes as its pending-forms
 * queue ({@code .kb/clojure-frontend.md}, "Portable LinkedList"): an untagged
 * zero-argument construction where the host is not (wasm) is the deftype of the shipped
 * internal namespace {@value #NAMESPACE}
 * ({@code lib/rontolisp/internal/linked_list.clj}), loaded where such a construction
 * first lowers -- never a {@code java:new}, which wasm refuses at call time. Every member
 * call and core verb reads it through the methods its body implements. Where the host is
 * the construction keeps the host's object.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableLinkedListLowering {

	/** The shipped internal namespace whose deftype is the list. */
	static final String NAMESPACE = "rontolisp.internal.linked-list";

	private static final String LINKED_LIST_CLASS = "java.util.LinkedList";

	private ClojurePortableLinkedListLowering() {
	}

	/**
	 * A zero-argument {@code java.util.LinkedList} construction as the portable list,
	 * where the host is not: the caller checks the target, the count and that the
	 * construction is untagged (the collection copy keeps {@code java:new}). Null for any
	 * other class, and outside top-level datums (a run-time lowering), where no namespace
	 * loads: that keeps {@code java:new} too.
	 * @param ctx the hub
	 * @param cls the class's binary name
	 * @return the construction, or null
	 */
	static @Nullable LispVal construction(ClojureLowering ctx, String cls) {
		if (!cls.equals(LINKED_LIST_CLASS) || !ctx.atTopLevel()) {
			return null;
		}
		if (!ctx.loadedNamespaces.contains(NAMESPACE) && !ctx.loadingNamespaces.contains(NAMESPACE)) {
			ClojureNamespaceLowering.preload(ctx, NAMESPACE);
		}
		return ClojureLowerUtil.list(ClojureLowering.varSym(NAMESPACE + "/linked-list"));
	}

}
