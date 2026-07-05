package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The shared core of the limited ASDF subset: parses {@code asdf:defsystem} forms and
 * {@code .asd} files as plain data (they are never evaluated), orders a system's
 * components by their {@code :depends-on}/{@code :serial} constraints, and locates
 * {@code NAME.asd} files on a search path. Real ASDF is not ported -- there is no CLOS
 * {@code operate} machinery, no {@code :defsystem-depends-on}, no {@code :perform} -- so
 * anything outside the supported subset is a hard error naming the unsupported clause.
 *
 * <p>
 * The supported {@code defsystem} grammar is: a literal system name (string, keyword or
 * symbol), the ignored metadata options ({@code :description}, {@code :version} and
 * friends), {@code :depends-on} (system names loaded first, through the same search
 * path), {@code :serial} (each component implicitly depends on the previous one), and
 * {@code :components} with {@code (:file "name" [:depends-on (...)])},
 * {@code (:module "dir" :components (...))} (a path prefix) and
 * {@code (:static-file "name")} (ignored) entries.
 *
 * <p>
 * Consumers: the compile path splices systems in the {@code LoadInliner} pass (so the
 * JVM/WASM compilers see the component files natively, like {@code load}); the
 * interpreter registers {@code asdf:defsystem} as a special form and
 * {@code asdf:load-system} as a runtime function in {@link LispEvaluator}.
 */
public final class AsdfSystems {

	private AsdfSystems() {
	}

	/**
	 * A parsed system definition.
	 *
	 * @param name the system name
	 * @param dependsOn the names of the systems to load first, in order
	 * @param files the component source files in load order, relative to {@code baseDir}
	 * @param baseDir the directory the component files resolve against (the directory of
	 * the {@code .asd} file, or of the source that defined the system inline; empty for
	 * working-directory-relative)
	 */
	public record LispSystem(String name, List<String> dependsOn, List<String> files, String baseDir) {
	}

	/**
	 * A located {@code .asd} file: the resolved path and its source text.
	 *
	 * @param path the resolved path of the {@code .asd} file
	 * @param source the file's source text
	 */
	public record LocatedAsd(String path, String source) {
	}

	/**
	 * Returns whether {@code form} is an {@code asdf:defsystem} form. Only the
	 * {@code asdf}-qualified spelling counts here (inside a {@code .asd} file, parsed by
	 * {@link #parseAsdSource}, the bare {@code defsystem} spelling is accepted too).
	 * @param form the form to test
	 * @return {@code true} if the form is an {@code asdf:defsystem} call
	 */
	public static boolean isDefsystemForm(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& isAsdfMember(op, LispNames.DEFSYSTEM);
	}

	/**
	 * If {@code form} is an {@code (asdf:load-system NAME)} call, returns the literal
	 * system name; otherwise returns {@code null}. A {@code load-system} whose argument
	 * is not a literal designator is a hard error: the compile path cannot evaluate it
	 * (the interpreter's runtime function accepts computed names instead).
	 * @param form the form to test
	 * @return the system name, or {@code null} if the form is not a
	 * {@code asdf:load-system} call
	 */
	@Nullable public static String loadSystemName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !isAsdfMember(op, LispNames.LOAD_SYSTEM)) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.size() != 2) {
			throw new IllegalStateException(
					LispNames.ASDF_LOAD_SYSTEM + " expects exactly one system name: " + form.print());
		}
		return designator(LispNames.ASDF_LOAD_SYSTEM, items.get(1));
	}

	/**
	 * Parses the source text of a {@code .asd} file as plain data: {@code defsystem}
	 * forms (any package spelling) become {@link LispSystem}s whose component files
	 * resolve against the {@code .asd} file's directory, {@code in-package} forms are
	 * skipped (the file is never evaluated, so the package does not matter), and any
	 * other form is a hard error naming the file. A {@code #.} read-time-eval form (the
	 * ASDF-version-guard idiom) is skipped with a warning instead of erroring, and
	 * {@code #+}/{@code #-} conditionals are evaluated against {@code features}.
	 * @param source the {@code .asd} source text
	 * @param asdPath the resolved path of the {@code .asd} file (for the base directory
	 * and error messages)
	 * @param features the active reader features
	 * @return the systems defined by the file
	 */
	public static List<LispSystem> parseAsdSource(String source, String asdPath, Features features) {
		String baseDir = SourceLoader.parentDir(asdPath);
		List<LispSystem> systems = new ArrayList<>();
		for (LispVal form : LispReader.readAllSkippingReadEval(source, features)) {
			if (operatorMemberIs(form, LispNames.IN_PACKAGE)) {
				continue;
			}
			if (operatorMemberIs(form, LispNames.DEFSYSTEM)) {
				systems.add(parseDefsystem(form, baseDir, features));
				continue;
			}
			throw new IllegalStateException(asdPath + ": unsupported form in .asd file (only " + LispNames.DEFSYSTEM
					+ " and " + LispNames.IN_PACKAGE + " are recognized): " + form.print());
		}
		return systems;
	}

	/**
	 * Parses a {@code defsystem} form into a {@link LispSystem}, ordering the components
	 * by their {@code :depends-on}/{@code :serial} constraints. A component whose
	 * {@code :if-feature} expression is not satisfied by {@code features} still
	 * participates in the ordering but contributes no source files (this is how libraries
	 * gate CLOS-only files behind {@code (:or :sbcl ...)}). Any option or component shape
	 * outside the supported subset is a hard error naming the clause.
	 * @param form the {@code defsystem} form
	 * @param baseDir the directory the component files resolve against, or {@code null}
	 * for working-directory-relative
	 * @param features the features the {@code :if-feature} component option tests
	 * @return the parsed system
	 */
	public static LispSystem parseDefsystem(LispVal form, @Nullable String baseDir, Features features) {
		if (!(form instanceof LispCons cons)) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " expects a system definition form");
		}
		List<LispVal> items = cons.toList();
		if (items.size() < 2) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " expects a system name: " + form.print());
		}
		String name = designator(LispNames.ASDF_DEFSYSTEM, items.get(1));
		if ((items.size() - 2) % 2 != 0) {
			throw new IllegalStateException(
					LispNames.ASDF_DEFSYSTEM + " " + name + " expects :option value pairs: " + form.print());
		}
		List<String> dependsOn = new ArrayList<>();
		boolean serial = false;
		LispVal components = null;
		for (int i = 2; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
						+ " expects a keyword option, got " + items.get(i).print());
			}
			LispVal value = items.get(i + 1);
			switch (key.name()) {
				// Metadata: accepted for .asd compatibility, not recorded anywhere. The
				// :version value may be any literal form, including ASDF's
				// (:read-file-form "version.sexp") indirection -- it is never inspected.
				case ":description", ":long-description", ":version", ":author", ":maintainer", ":license", ":licence",
						":homepage", ":bug-tracker", ":source-control", ":mailto" ->
					{
					}
				// Test-op wiring only (there is no operate/test-op machinery to drive):
				// tolerated so a real library's .asd parses, ignored like the metadata.
				case ":in-order-to", ":perform" -> {
				}
				case ":depends-on" -> {
					for (LispVal dep : properList(LispNames.ASDF_DEFSYSTEM + " " + name + " :depends-on", value)) {
						dependsOn.add(designator(":depends-on", dep));
					}
				}
				case ":serial" -> serial = !(value instanceof LispNil);
				case ":components" -> components = value;
				default -> throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
						+ ": unsupported option " + key.name() + " (supported: :description :long-description"
						+ " :version :author :maintainer :license :depends-on :serial :components)");
			}
		}
		List<String> files = components == null ? List.of() : orderComponents(name, components, serial, "", features);
		return new LispSystem(name, List.copyOf(dependsOn), files, baseDir == null ? "" : baseDir);
	}

	/**
	 * Locates {@code NAME.asd} on the search path by attempting to read it from each
	 * directory in order (the {@link SourceLoader} abstraction has no existence check, so
	 * a failed read means "not here"). For a secondary system name like
	 * {@code "lib/tests"} the file is the primary system's ({@code lib.asd}).
	 * @param name the system name
	 * @param searchDirs the directories to search, in order (empty entries mean
	 * working-directory-relative)
	 * @param loader the loader used to read candidate files
	 * @return the located {@code .asd} file
	 */
	public static LocatedAsd locate(String name, List<String> searchDirs, SourceLoader loader) {
		int slash = name.indexOf('/');
		String fileName = (slash < 0 ? name : name.substring(0, slash)) + ".asd";
		List<String> tried = new ArrayList<>();
		for (String dir : searchDirs) {
			String path = SourceLoader.resolve(dir == null ? "" : dir, fileName);
			if (tried.contains(path)) {
				continue;
			}
			try {
				return new LocatedAsd(path, loader.load(path));
			}
			catch (IOException ex) {
				tried.add(path);
			}
		}
		throw new IllegalStateException(LispNames.ASDF_LOAD_SYSTEM + ": system '" + name + "' not found (tried: "
				+ String.join(", ", tried) + "); add its directory to --system-path or RONTOLISP_SOURCE_REGISTRY");
	}

	/**
	 * Parses a literal system-name designator: a string ({@code "lib"}), a keyword
	 * ({@code :lib}), a bare symbol ({@code lib}, package qualifiers stripped -- the
	 * package resolver may have qualified it) or a quoted symbol ({@code 'lib}).
	 * @param context the operator name for error messages
	 * @param val the designator form
	 * @return the system name
	 */
	public static String designator(String context, LispVal val) {
		if (val instanceof LispString str) {
			return str.value();
		}
		if (val instanceof LispSymbol sym) {
			return symbolName(sym);
		}
		if (val instanceof LispCons quoted && quoted.car() instanceof LispSymbol quoteOp
				&& LispNames.QUOTE.equals(quoteOp.name()) && quoted.cdr() instanceof LispCons datumCell
				&& datumCell.car() instanceof LispSymbol datum) {
			return symbolName(datum);
		}
		throw new IllegalStateException(
				context + " expects a literal system name (string, keyword or symbol), got " + val.print());
	}

	private static String symbolName(LispSymbol sym) {
		if (sym.name().startsWith("#:")) {
			// An uninterned designator (#:lib), the portable defsystem idiom.
			return sym.name().substring(2);
		}
		if (sym.isKeyword()) {
			return sym.name().substring(1);
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn == null ? sym.name() : qn.member();
	}

	/**
	 * A parsed component: its sibling-scoped name, the sibling names it depends on, and
	 * the source files it contributes (already ordered for a module).
	 */
	private record Component(String name, List<String> dependsOn, List<String> files) {
	}

	/**
	 * Parses a {@code :components} list and returns the source files in load order: a
	 * stable topological sort of the sibling components by {@code :depends-on} (original
	 * order is preserved among unconstrained components), with {@code :serial} adding an
	 * implicit dependency on the previous sibling; a module's files stay contiguous.
	 */
	private static List<String> orderComponents(String systemName, LispVal componentsVal, boolean serial, String prefix,
			Features features) {
		List<Component> components = new ArrayList<>();
		String previous = null;
		for (LispVal entry : properList(LispNames.ASDF_DEFSYSTEM + " " + systemName + " :components", componentsVal)) {
			Component component = parseComponent(systemName, entry, prefix, features);
			List<String> deps = new ArrayList<>(component.dependsOn());
			if (serial && previous != null) {
				deps.add(previous);
			}
			components.add(new Component(component.name(), deps, component.files()));
			previous = component.name();
		}
		Set<String> names = new HashSet<>();
		for (Component component : components) {
			names.add(component.name());
		}
		for (Component component : components) {
			for (String dep : component.dependsOn()) {
				if (!names.contains(dep)) {
					throw new IllegalStateException("system " + systemName + ": component " + component.name()
							+ " :depends-on unknown component " + dep);
				}
			}
		}
		List<String> files = new ArrayList<>();
		Set<String> placed = new HashSet<>();
		List<Component> remaining = new ArrayList<>(components);
		while (!remaining.isEmpty()) {
			boolean progress = false;
			for (Iterator<Component> it = remaining.iterator(); it.hasNext();) {
				Component component = it.next();
				if (placed.containsAll(component.dependsOn())) {
					files.addAll(component.files());
					placed.add(component.name());
					it.remove();
					progress = true;
				}
			}
			if (!progress) {
				List<String> stuck = remaining.stream().map(Component::name).toList();
				throw new IllegalStateException(
						"system " + systemName + ": circular component :depends-on among " + stuck);
			}
		}
		return List.copyOf(files);
	}

	private static Component parseComponent(String systemName, LispVal entry, String prefix, Features features) {
		if (!(entry instanceof LispCons compCons) || !(compCons.car() instanceof LispSymbol type)
				|| !type.isKeyword()) {
			throw new IllegalStateException(
					"system " + systemName + ": each component must be (:file \"name\" ...), (:module \"dir\" ...) or"
							+ " (:static-file \"name\"), got " + entry.print());
		}
		List<LispVal> parts = compCons.toList();
		if (parts.size() < 2 || !(parts.get(1) instanceof LispString nameStr)) {
			throw new IllegalStateException(
					"system " + systemName + ": component name must be a string literal: " + entry.print());
		}
		String name = nameStr.value();
		if ((parts.size() - 2) % 2 != 0) {
			throw new IllegalStateException(
					"system " + systemName + ": component " + name + " expects :option value pairs: " + entry.print());
		}
		List<String> dependsOn = new ArrayList<>();
		boolean moduleSerial = false;
		boolean featureEnabled = true;
		LispVal nested = null;
		for (int i = 2; i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new IllegalStateException("system " + systemName + ": component " + name
						+ " expects a keyword option, got " + parts.get(i).print());
			}
			LispVal value = parts.get(i + 1);
			boolean module = ":module".equals(type.name());
			switch (key.name()) {
				case ":depends-on" -> {
					for (LispVal dep : properList("component " + name + " :depends-on", value)) {
						dependsOn.add(designator(":depends-on", dep));
					}
				}
				case ":if-feature" -> featureEnabled = features.isEnabled(value);
				case ":serial" -> {
					if (!module) {
						throw unsupportedComponentOption(systemName, type, name, key);
					}
					moduleSerial = !(value instanceof LispNil);
				}
				case ":components" -> {
					if (!module) {
						throw unsupportedComponentOption(systemName, type, name, key);
					}
					nested = value;
				}
				default -> throw unsupportedComponentOption(systemName, type, name, key);
			}
		}
		List<String> files = switch (type.name()) {
			case ":file" -> List.of(prefix + name + ".lisp");
			// A static file participates in ordering but contributes no source.
			case ":static-file" -> List.of();
			case ":module" -> {
				if (nested == null) {
					throw new IllegalStateException(
							"system " + systemName + ": module " + name + " expects a :components option");
				}
				yield orderComponents(systemName, nested, moduleSerial, prefix + name + "/", features);
			}
			default -> throw new IllegalStateException("system " + systemName + ": unsupported component type "
					+ type.name() + " (supported: :file :module :static-file)");
		};
		// A feature-disabled component keeps its place in the dependency graph (a
		// sibling may :depends-on it) but contributes no source files.
		return new Component(name, dependsOn, featureEnabled ? files : List.of());
	}

	private static IllegalStateException unsupportedComponentOption(String systemName, LispSymbol type, String name,
			LispSymbol key) {
		return new IllegalStateException("system " + systemName + ": unsupported " + type.name() + " option "
				+ key.name() + " on component " + name);
	}

	private static List<LispVal> properList(String context, LispVal val) {
		if (val instanceof LispNil) {
			return List.of();
		}
		if (val instanceof LispCons cons && cons.isProperList()) {
			return cons.toList();
		}
		throw new IllegalStateException(context + " expects a list, got " + val.print());
	}

	private static boolean operatorMemberIs(LispVal form, String member) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return member.equals(qn == null ? op.name() : qn.member());
	}

	private static boolean isAsdfMember(LispSymbol op, String member) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn != null && LispNames.ASDF_PKG.equals(qn.pkg()) && member.equals(qn.member());
	}

}
