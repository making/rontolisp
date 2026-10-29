package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * One completion candidate of a prefix typed in a namespace: what an nREPL
 * {@code completions} op answers, after the oracle's sources (nREPL 1.3's
 * compliment-lite) over what the lowering knows -- the vars the namespace maps (its own,
 * its refers, {@code clojure.core}'s), an alias's or a namespace's public vars behind a
 * qualified prefix, the namespaces, the special forms and the literals, a part matched
 * per {@code -} or {@code .} segment ({@code m-i} completes {@code map-indexed}). Keyword
 * and host class candidates are not answered: no keyword table and no class path scan
 * stand behind the lowering.
 *
 * @param candidate the completed text, the prefix's leading {@code #'}, {@code '} or
 * {@code @} kept
 * @param type {@code function}, {@code macro}, {@code var}, {@code namespace} or
 * {@code special-form}
 * @param ns the namespace a var's candidate lives in, or null for any other
 */
public record ClojureCompletion(String candidate, String type, @Nullable String ns) {

	/** The oracle's special forms a candidate names. */
	private static final List<String> SPECIAL_FORMS = List.of("def", "if", "do", "quote", "var", "recur", "throw",
			"try", "catch", "monitor-enter", "monitor-exit", "new", "set!");

	private static final Pattern LEADING_LITERALS = Pattern.compile("(@{0,2}#'|'|@)?(.*)", Pattern.DOTALL);

	private static final Pattern VAR_SYMBOL = Pattern.compile("(?:([^/:][^/]*)/)?(|[^/:][^/]*)");

	private static final Pattern NAMESPACE_SYMBOL = Pattern.compile("[^/:]+");

	/**
	 * The candidates of a prefix in a namespace, sorted by their text.
	 * @param lowering the lowering whose tables answer
	 * @param prefix the prefix
	 * @param namespace the namespace, {@code user} where the lowering knows none of that
	 * name
	 * @return the candidates
	 */
	static List<ClojureCompletion> of(ClojureLowering lowering, String prefix, String namespace) {
		String here = lowering.knownNamespaceNames().contains(namespace) || lowering.namespaces.containsKey(namespace)
				? namespace : "user";
		Set<ClojureCompletion> out = new LinkedHashSet<>();
		Matcher literals = LEADING_LITERALS.matcher(prefix);
		String literal = "";
		String bare = prefix;
		if (literals.matches()) {
			literal = literals.group(1) == null ? "" : literals.group(1);
			bare = literals.group(2);
		}
		Matcher symbol = VAR_SYMBOL.matcher(bare);
		if (symbol.matches()) {
			vars(lowering, here, literal, symbol.group(1), symbol.group(2), out);
			if (symbol.group(1) == null) {
				for (String form : SPECIAL_FORMS) {
					if (fuzzyMatches(bare, form, '-')) {
						out.add(new ClojureCompletion(form, "special-form", null));
					}
				}
			}
		}
		if (NAMESPACE_SYMBOL.matcher(bare).matches() && !bare.startsWith(".")) {
			Set<String> names = new LinkedHashSet<>(lowering.knownNamespaceNames());
			names.addAll(ClojureBuiltinNamespaces.requirable());
			ClojureNsState state = lowering.namespaces.get(here);
			if (state != null) {
				names.addAll(state.aliases.keySet());
			}
			boolean dotted = bare.indexOf('.') >= 0;
			for (String name : names) {
				if (dotted ? fuzzyMatches(bare, name, '.') : name.startsWith(bare)) {
					out.add(new ClojureCompletion(literal + name, "namespace", null));
				}
			}
		}
		for (String value : List.of("true", "false", "nil")) {
			if (value.startsWith(prefix)) {
				out.add(new ClojureCompletion(value, "special-form", null));
			}
		}
		List<ClojureCompletion> sorted = new ArrayList<>(out);
		sorted.sort(Comparator.comparing(ClojureCompletion::candidate));
		return sorted;
	}

	/**
	 * The var candidates: behind a scope (an alias or a namespace), that namespace's
	 * public vars, its private ones too after {@code #'}; without one, every var the
	 * namespace maps.
	 */
	private static void vars(ClojureLowering lowering, String here, String literal, @Nullable String scope, String part,
			Set<ClojureCompletion> out) {
		ClojureNsState state = lowering.namespaces.get(here);
		if (scope != null) {
			String target = state != null && state.aliases.containsKey(scope) ? state.aliases.get(scope) : scope;
			for (Map.Entry<String, String> var : publics(lowering, target, literal.endsWith("#'")).entrySet()) {
				if (fuzzyMatches(part, var.getKey(), '-')) {
					out.add(new ClojureCompletion(literal + scope + "/" + var.getKey(), var.getValue(), target));
				}
			}
			return;
		}
		if (state != null) {
			for (String name : state.interns.keySet()) {
				if (fuzzyMatches(part, name, '-')) {
					out.add(new ClojureCompletion(literal + name,
							typeOf(lowering.globals.get(ClojureLowering.varKey(here, name))), here));
				}
			}
			for (Map.Entry<String, ClojureLowering.VarRef> refer : state.refers.entrySet()) {
				if (fuzzyMatches(part, refer.getKey(), '-')) {
					ClojureLowering.VarRef ref = refer.getValue();
					ClojureLowering.Kind kind = lowering.globals.get(ClojureLowering.varKey(ref.ns(), ref.var()));
					out.add(new ClojureCompletion(literal + refer.getKey(), kind == null ? "function" : typeOf(kind),
							ref.ns()));
				}
			}
		}
		for (String name : ClojureCoreNames.NAMES) {
			if (coreVisible(state, name) && fuzzyMatches(part, name, '-')) {
				out.add(new ClojureCompletion(literal + name, coreType(name), ClojureCoreNames.NAMESPACE));
			}
		}
		if (state != null) {
			for (Map.Entry<String, String> renamed : state.coreRenames.entrySet()) {
				if (fuzzyMatches(part, renamed.getKey(), '-')) {
					out.add(new ClojureCompletion(literal + renamed.getKey(), coreType(renamed.getValue()),
							ClojureCoreNames.NAMESPACE));
				}
			}
		}
	}

	/**
	 * A namespace's vars by name, each with its candidate type: a namespace whose vars
	 * lower to core forms answers what it implements, {@code clojure.core} its public
	 * vars, a namespace the lowering loaded what it interns (the private ones only when
	 * asked).
	 */
	private static Map<String, String> publics(ClojureLowering lowering, String ns, boolean privateToo) {
		Map<String, String> vars = new java.util.TreeMap<>();
		if (ns.equals(ClojureCoreNames.NAMESPACE)) {
			for (String name : ClojureCoreNames.NAMES) {
				vars.put(name, coreType(name));
			}
			return vars;
		}
		if (ClojureNamespaceLowering.isKnownNamespace(ns)) {
			for (String name : ClojureNamespaceLowering.varsOf(ns)) {
				vars.put(name, "function");
			}
		}
		ClojureNsState state = lowering.namespaces.get(ns);
		if (state != null) {
			for (Map.Entry<String, Boolean> intern : state.interns.entrySet()) {
				if (privateToo || !intern.getValue()) {
					vars.put(intern.getKey(),
							typeOf(lowering.globals.get(ClojureLowering.varKey(ns, intern.getKey()))));
				}
			}
		}
		return vars;
	}

	/** Whether a {@code clojure.core} name is mapped in a namespace of this state. */
	private static boolean coreVisible(@Nullable ClojureNsState state, String name) {
		if (state == null) {
			return true;
		}
		if (state.referClojureOnly != null && !state.referClojureOnly.contains(name)) {
			return false;
		}
		return !state.referClojureExclude.contains(name);
	}

	/** The candidate type of a var the lowering defined. */
	private static String typeOf(ClojureLowering.@Nullable Kind kind) {
		if (kind == ClojureLowering.Kind.MACRO) {
			return "macro";
		}
		return kind == ClojureLowering.Kind.FUNCTION ? "function" : "var";
	}

	/** The candidate type of a {@code clojure.core} var. */
	private static String coreType(String name) {
		if (ClojureCoreNames.isMacro(name)) {
			return "macro";
		}
		return ClojureCoreSpecials.of(name) != null ? "var" : "function";
	}

	/**
	 * Whether the prefix matches the name segment by segment: the first characters agree,
	 * and each later run of the prefix's characters is found in order, a run that misses
	 * skipping to the name's next separator -- the oracle's {@code fuzzy-matches?}.
	 * @param prefix the prefix
	 * @param name the name
	 * @param separator the segment separator
	 * @return whether it matches
	 */
	static boolean fuzzyMatches(String prefix, String name, char separator) {
		int pn = prefix.length();
		int sn = name.length();
		if (pn == 0) {
			return true;
		}
		if (sn == 0 || prefix.charAt(0) != name.charAt(0)) {
			return false;
		}
		int pi = 1;
		int si = 1;
		boolean skipping = false;
		while (pi < pn) {
			if (si >= sn) {
				return false;
			}
			char pc = prefix.charAt(pi);
			char sc = name.charAt(si);
			boolean match = pc == sc;
			if (sc == separator) {
				if (match) {
					pi++;
				}
				si++;
				skipping = false;
			}
			else if (skipping || !match) {
				si++;
				skipping = true;
			}
			else {
				pi++;
				si++;
			}
		}
		return true;
	}

}
