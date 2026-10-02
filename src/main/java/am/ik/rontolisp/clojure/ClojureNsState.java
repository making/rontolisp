package am.ik.rontolisp.clojure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * One namespace of a lowering: what its {@code ns} clauses and top-level
 * {@code require}/{@code use}/{@code import} calls wired, and the vars it interns. Each
 * namespace has its own, like the oracle's: an alias or a refer serves only the namespace
 * that made it.
 */
final class ClojureNsState {

	/**
	 * An {@code :as} alias (or a known library's own name) to its namespace. Wired in
	 * order, so an alias serves only the forms below it.
	 */
	final Map<String, String> aliases = new HashMap<>();

	/**
	 * Unqualified names a {@code :refer} / {@code use} brought in: the name to its
	 * namespace and var -- a known library's lowering or a project namespace's var.
	 */
	final Map<String, ClojureLowering.VarRef> refers = new HashMap<>();

	/**
	 * Simple class names an {@code :import} (or a top-level {@code import}) registered:
	 * the name to its FQN.
	 */
	final Map<String, String> classNames = new HashMap<>();

	/**
	 * What {@code (:refer-clojure :only [...])} restricts the core to, or null without
	 * one; {@code (:refer-clojure :exclude [...])} removes instead. A name outside the
	 * set is not a builtin, so a user definition of it wins.
	 */
	@Nullable Set<String> referClojureOnly;

	final Set<String> referClojureExclude = new HashSet<>();

	/**
	 * The vars this namespace interns, in definition order: the name to whether it is
	 * private ({@code defn-}, {@code ^:private}). A private var is never referred and a
	 * qualified reference from another namespace is refused, like the oracle.
	 */
	final Map<String, Boolean> interns = new LinkedHashMap<>();

}
