package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the whole-program lowering of a compiled class lowering at run time had decided
 * when its open passes began ({@link ClojureLowering#lowerWhole}): the vars a call reads
 * through their cell, the walked protocols, the interface guards, the root setters, the
 * thread binders, the dynamic vars a bound function conveys -- the misses the passes
 * before had found. The class carries them as text, and its run-time read starts its
 * passes from them: the same call the compile made from that point, so it lowers to the
 * same names without lowering the passes before again. Every collection keeps the order
 * the loop iterated it in, so the replay fills its own in that order.
 *
 * @param redefinable the vars every call reads through the var
 * @param walking the protocols whose dispatchers walk a class's supers
 * @param guards each protocol's interface guards
 * @param rootSetters whether a computed root setter was seen
 * @param threadBinders whether a binding a thread conveys was seen
 * @param open whether the program lowers open
 * @param dynamics the dynamic vars a bound function conveys
 */
record ClojureWholeDecisions(List<String> redefinable, List<String> walking, Map<String, List<String>> guards,
		boolean rootSetters, boolean threadBinders, boolean open, List<String> dynamics) {

	ClojureWholeDecisions {
		redefinable = List.copyOf(redefinable);
		walking = List.copyOf(walking);
		Map<String, List<String>> copied = new LinkedHashMap<>();
		guards.forEach((protocol, tests) -> copied.put(protocol, List.copyOf(tests)));
		guards = java.util.Collections.unmodifiableMap(copied);
		dynamics = List.copyOf(dynamics);
	}

	/**
	 * The decisions of the loop's collections, in their iteration order.
	 */
	static ClojureWholeDecisions of(Collection<String> redefinable, Collection<String> walking,
			Map<String, ? extends Collection<String>> guards, boolean rootSetters, boolean threadBinders, boolean open,
			Collection<String> dynamics) {
		Map<String, List<String>> lists = new LinkedHashMap<>();
		guards.forEach((protocol, tests) -> lists.put(protocol, List.copyOf(tests)));
		return new ClojureWholeDecisions(List.copyOf(redefinable), List.copyOf(walking), lists, rootSetters,
				threadBinders, open, List.copyOf(dynamics));
	}

	/**
	 * The decisions as one string, each field a length-prefixed run.
	 * @return the encoded decisions
	 */
	String encode() {
		List<String> fields = new ArrayList<>();
		addAll(fields, this.redefinable);
		addAll(fields, this.walking);
		fields.add(Integer.toString(this.guards.size()));
		this.guards.forEach((protocol, tests) -> {
			fields.add(protocol);
			addAll(fields, tests);
		});
		fields.add((this.rootSetters ? "s" : "-") + (this.threadBinders ? "b" : "-") + (this.open ? "o" : "-"));
		addAll(fields, this.dynamics);
		StringBuilder out = new StringBuilder();
		for (String field : fields) {
			out.append(field.length()).append(':').append(field);
		}
		return out.toString();
	}

	private static void addAll(List<String> fields, List<String> names) {
		fields.add(Integer.toString(names.size()));
		fields.addAll(names);
	}

	/**
	 * The decisions an {@link #encode()} string holds.
	 * @param text the encoded decisions
	 * @return the decisions
	 * @throws IllegalArgumentException when the text holds none
	 */
	static ClojureWholeDecisions decode(String text) {
		Fields in = new Fields(text);
		List<String> redefinable = in.names();
		List<String> walking = in.names();
		Map<String, List<String>> guards = new LinkedHashMap<>();
		int protocols = Integer.parseInt(in.next());
		for (int i = 0; i < protocols; i++) {
			String protocol = in.next();
			guards.put(protocol, in.names());
		}
		String flags = in.next();
		if (flags.length() != 3) {
			throw new IllegalArgumentException("not the decisions of a whole-program lowering");
		}
		List<String> dynamics = in.names();
		return new ClojureWholeDecisions(redefinable, walking, guards, flags.charAt(0) == 's', flags.charAt(1) == 'b',
				flags.charAt(2) == 'o', dynamics);
	}

	private static final class Fields {

		private final String text;

		private int pos;

		Fields(String text) {
			this.text = text;
		}

		String next() {
			int colon = this.text.indexOf(':', this.pos);
			if (colon < 0) {
				throw new IllegalArgumentException("truncated decisions of a whole-program lowering");
			}
			int length = Integer.parseInt(this.text.substring(this.pos, colon));
			this.pos = colon + 1 + length;
			return this.text.substring(colon + 1, this.pos);
		}

		List<String> names() {
			int count = Integer.parseInt(next());
			List<String> names = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				names.add(next());
			}
			return names;
		}

	}

}
