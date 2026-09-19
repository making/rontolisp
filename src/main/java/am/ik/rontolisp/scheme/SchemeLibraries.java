package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The user libraries ({@code define-library}) one lowering knows: the file or session
 * that started it and every library it imports, transitively. A library is lowered once
 * here, the first time something imports it, and its forms wait in {@link #pending} until
 * the importer emits them ahead of its own -- so they come out in dependency order. How a
 * library is lowered is {@link SchemeLowering}'s; this holds the state
 * ({@code .kb/scheme-frontend.md}, "Libraries and include").
 */
final class SchemeLibraries<B> {

	/**
	 * A {@code define-library} form, not yet lowered.
	 *
	 * @param form the whole form
	 * @param reader the reader that positions it
	 * @param file the file it stands in, what its {@code include}s are relative to
	 */
	record Declaration(LispCons form, SchemeReader reader, @Nullable String file) {
	}

	private final SchemeFiles files;

	private final @Nullable String root;

	private final Map<List<String>, Declaration> declared = new LinkedHashMap<>();

	private final Map<List<String>, Map<String, B>> instantiated = new HashMap<>();

	private final SequencedSet<List<String>> instantiating = new LinkedHashSet<>();

	private final List<LispVal> pending = new ArrayList<>();

	/**
	 * The libraries of one lowering.
	 * @param files where included files and library files are read from
	 * @param root the file a library {@code (a b)} is found beside, as {@code a/b.sld};
	 * {@code null} for the working directory
	 */
	SchemeLibraries(SchemeFiles files, @Nullable String root) {
		this.files = files;
		this.root = root;
	}

	SchemeFiles files() {
		return this.files;
	}

	@Nullable String root() {
		return this.root;
	}

	/**
	 * Registers a {@code define-library} form under its name; the first one wins, as the
	 * first file found wins.
	 * @param name the library name
	 * @param declaration the form
	 * @return whether the name was new
	 */
	boolean declare(List<String> name, Declaration declaration) {
		return this.declared.putIfAbsent(name, declaration) == null;
	}

	@Nullable Declaration declared(List<String> name) {
		return this.declared.get(name);
	}

	@Nullable Map<String, B> exports(List<String> name) {
		return this.instantiated.get(name);
	}

	/**
	 * Marks a library as being lowered, so an import cycle is found instead of recursing.
	 * @param name the library name
	 * @return the libraries being lowered, outermost first, when {@code name} is one of
	 * them; {@code null} otherwise
	 */
	@Nullable List<List<String>> enter(List<String> name) {
		if (this.instantiating.contains(name)) {
			List<List<String>> cycle = new ArrayList<>(this.instantiating);
			cycle.add(name);
			return cycle;
		}
		this.instantiating.add(name);
		return null;
	}

	void abandon(List<String> name) {
		this.instantiating.remove(name);
	}

	void leave(List<String> name, Map<String, B> exports, List<LispVal> forms) {
		this.instantiating.remove(name);
		this.instantiated.put(name, exports);
		this.pending.addAll(forms);
	}

	/**
	 * The forms of the libraries lowered since the last call, in dependency order.
	 * @return the forms, now no longer pending
	 */
	List<LispVal> drain() {
		List<LispVal> forms = List.copyOf(this.pending);
		this.pending.clear();
		return forms;
	}

}
