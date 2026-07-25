package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.wit.WitDocument;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitLocations;
import am.ik.wit.WitParseException;
import am.ik.wit.WitParseResult;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * The {@code (rontolisp:wit-export "world.wit" :world name)} directive: <em>this program
 * implements this WIT world</em>. It is a compile-time front-end for
 * {@code rontolisp:wasm-export} -- the world's {@code export} items are checked against
 * the program's {@code defun}s and then lowered into exactly the
 * {@code rontolisp:wasm-export} directives a hand-written equivalent would carry, so the
 * emitted component is byte-identical and no new export path exists.
 *
 * <p>
 * The point is that the types stop being maintained in two places: without it a program
 * hand-writes {@code :params '(:string) :returns :int} next to a {@code .wit} that is
 * generated separately, and the two drift until {@code wasmtime --invoke} fails at run
 * time. With it the {@code .wit} is the single source of truth, and every drift is a
 * compile error naming the WIT file and line:
 *
 * <ul>
 * <li>an export the world declares but the program does not define, or defines with a
 * different arity</li>
 * <li>a WIT type outside the boundary subset (every fixed-width integer, {@code f64},
 * {@code bool} and {@code string} — see {@link BoundaryType})</li>
 * <li>an export name that is not a component-model label, or the reserved
 * {@code run}</li>
 * <li>an {@code async func} in the world (the {@code :async t} lift is stated by the WIT
 * instead of guessed; a sync-lifted export doing I/O traps at run time)</li>
 * </ul>
 *
 * <p>
 * A world may export freestanding functions or an <strong>interface defined in the same
 * file</strong> — the idiomatic {@code export docs:adder/add;} that references an in-file
 * {@code interface add { ... }}, or an inline {@code export ops: interface { ... }}. An
 * interface export is checked and lowered member by member, each carrying the interface's
 * id ({@code :interface "docs:adder/add@0.1.0"}) so the backend bundles them into one
 * exported component instance. An export naming an interface the file does NOT define (a
 * bare {@code wasi:*} reference) is still rejected; the fixed {@code wasi:cli/run} entry
 * point is the one such reference that is silently ignored.
 *
 * <p>
 * This directive covers the export side only. A world's {@code import} interface items
 * are ignored here (a component's WASI imports come from the fixed adapter surface); the
 * interfaces a program calls are declared with {@code rontolisp:wit-import}, which binds
 * them per backend. An inline {@code import name: func(...)} is rejected rather than
 * silently dropped.
 *
 * <p>
 * This class does no I/O and no codegen: the caller reads the WIT text (so the browser
 * playground and the interpreter can supply it their own way) and splices the returned
 * forms.
 *
 * @see WitTypeMapper
 */
public final class WitExportDirective {

	/** The component-model {@code label} grammar an export name must match. */
	private static final java.util.regex.Pattern LABEL = java.util.regex.Pattern
		.compile("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*");

	/**
	 * The export name a {@code --component} reserves for its {@code wasi:cli/run} entry
	 * point.
	 */
	private static final String RESERVED_RUN = "run";

	private WitExportDirective() {
	}

	/**
	 * The backend a world is being checked against. Only the WASM backends impose the
	 * boundary's backend-specific rules; on the interpreter and the JVM the directive is
	 * inert, and the check is a pure contract check (so a plain
	 * {@code rontolisp prog.lisp} still catches a drifted world).
	 */
	public enum Backend {

		/** The wasm-GC backend, Preview 1 ({@code -o out.wasm}). */
		WASM_GC,

		/**
		 * The wasm-GC backend in component mode ({@code -o out.wasm --component}).
		 * {@code wit-export} treats it exactly like {@link #WASM_GC} (the export boundary
		 * is the same); {@code wit-import} lowers differently -- a component's imports go
		 * through the canonical ABI ({@code canon lower}) instead of Preview 1 core
		 * imports.
		 */
		WASM_COMPONENT,

		/** The scalar backend ({@code -o out.wasm --no-gc}). */
		WASM_NO_GC,

		/** The interpreter or the JVM backend: the directive is inert. */
		OTHER

	}

	/**
	 * A parsed {@code rontolisp:wit-export} directive.
	 *
	 * @param path the WIT file path as written (relative paths resolve against the source
	 * file's directory, like {@code load})
	 * @param world the world to implement, or {@code null} to use the file's only world
	 */
	public record Directive(String path, @Nullable String world) {
	}

	/**
	 * The program's top-level function definitions, as seen by the caller.
	 */
	@FunctionalInterface
	public interface Defuns {

		/**
		 * Returns the lambda list of the named top-level {@code defun}.
		 * @param name the function name, spelled as the WIT export names it
		 * @return the parameter names in order (including any {@code &optional} /
		 * {@code &rest} / ... markers), or {@code null} when the program defines no such
		 * function
		 */
		@Nullable List<String> lambdaList(String name);

	}

	/**
	 * Returns whether the given form is a {@code (rontolisp:wit-export ...)} directive.
	 * @param form the top-level form
	 * @return {@code true} if it is a rontolisp:wit-export directive
	 */
	public static boolean isDirective(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WIT_EXPORT.equals(qn.member());
		}
		return false;
	}

	/**
	 * Parses a {@code (rontolisp:wit-export "world.wit" :world name)} directive.
	 * @param form the directive form
	 * @return the parsed directive
	 * @throws UnsupportedOperationException if the directive is malformed
	 */
	public static Directive parse(LispCons form) {
		List<LispVal> items = form.toList();
		if (items.size() < 2 || !(items.get(1) instanceof LispString path)) {
			throw new UnsupportedOperationException(
					"rontolisp:wit-export expects a WIT file path string, got: " + form.print());
		}
		String world = null;
		int i = 2;
		while (i < items.size()) {
			if (!(items.get(i) instanceof LispSymbol keyword) || !keyword.isKeyword()) {
				throw new UnsupportedOperationException(
						"Expected a keyword option in " + form.print() + ", got: " + items.get(i).print());
			}
			if (i + 1 >= items.size()) {
				throw new UnsupportedOperationException("Missing value for " + keyword.name() + " in " + form.print());
			}
			LispVal value = items.get(i + 1);
			if (":WORLD".equals(keyword.name())) {
				world = worldName(value, form);
			}
			else {
				throw new UnsupportedOperationException(
						"Unknown rontolisp:wit-export option " + keyword.name() + " in " + form.print());
			}
			i += 2;
		}
		return new Directive(path.value(), world);
	}

	// A :world value is a bare symbol (the WIT spelling) or a string.
	private static String worldName(LispVal value, LispCons form) {
		return switch (value) {
			case LispString str -> str.value();
			case LispSymbol sym when !sym.isKeyword() -> {
				// A SYMBOL world designator lowercases (WIT worlds are lower-kebab and
				// the reader upcases unescaped symbols); a string stays verbatim.
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				yield (qn == null ? sym.name() : qn.member()).toLowerCase(java.util.Locale.ROOT);
			}
			default -> throw new UnsupportedOperationException(
					"rontolisp:wit-export :world expects a world name in " + form.print() + ", got: " + value.print());
		};
	}

	/**
	 * Checks the program against the world and lowers it into the equivalent
	 * {@code rontolisp:wasm-export} directives.
	 * @param directive the parsed directive
	 * @param witSource the WIT text
	 * @param witPath the WIT file path, for error messages
	 * @param defuns the program's top-level function definitions
	 * @param backend the backend the world is being checked against
	 * @return one {@code (rontolisp:wasm-export ...)} form per world export, in world
	 * order
	 * @throws UnsupportedOperationException on any contract violation, naming the WIT
	 * file and line
	 */
	public static List<LispVal> lower(Directive directive, String witSource, String witPath, Defuns defuns,
			Backend backend) {
		WitParseResult parsed;
		try {
			parsed = WitParser.parseLocated(witSource);
		}
		catch (WitParseException ex) {
			throw new UnsupportedOperationException(witPath + ": " + ex.getMessage(), ex);
		}
		WitLocations locations = parsed.locations();
		WitItem.World world = selectWorld(parsed.document(), directive.world(), witPath);
		WitResolver resolver = new WitResolver(parsed.document());
		List<LispVal> forms = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (WitItem item : world.items()) {
			switch (item) {
				case WitItem.ExportNamed export -> {
					switch (export.extern()) {
						case WitItem.Extern.ExternFunc func -> {
							if (!seen.add(export.name())) {
								throw error(witPath, locations, item, "duplicate export '" + export.name() + "'");
							}
							forms.add(exportForm(export.name(), func.func(), witPath, locations, item, defuns, backend,
									null));
						}
						// An inline interface export (`export add: interface { ... }`):
						// its
						// plain name is the exported instance's id, and each of its
						// functions
						// is a member the program must implement.
						case WitItem.Extern.ExternInterface inline -> lowerInterfaceMembers(export.name(),
								inline.items(), forms, seen, witPath, locations, item, defuns, backend);
					}
				}
				case WitItem.ExportRef ref -> {
					// wasi:cli/run is the component's own entry point (the adapter lifts
					// the
					// program's top level as it), not something a defun implements -- and
					// every world --emit-wit emits for a GC component carries it, so a
					// world
					// we emitted must be feedable straight back in.
					if (isComponentRunExport(ref)) {
						break;
					}
					// `export add;` -- an interface DEFINED IN THIS FILE becomes an
					// exported
					// component instance whose members the program implements. An
					// interface
					// the file does not define (a bare `wasi:*` reference) has no
					// functions
					// to check and is not something this directive can bind.
					WitItem.InterfaceDef iface = resolver.findInterface(ref.target().toString());
					if (iface == null) {
						throw error(witPath, locations, item, "export '" + ref.target()
								+ "' names an interface this file does not define; rontolisp:wit-export implements "
								+ "plain function exports and interfaces defined in the same file only "
								+ "(a program's wasi:http/handler@0.3.0 export comes from rontolisp:http-handler)");
					}
					// canonicalId is non-null for an interface obtained from this
					// resolver.
					String ifaceId = java.util.Objects.requireNonNull(resolver.canonicalId(iface));
					lowerInterfaceMembers(ifaceId, iface.items(), forms, seen, witPath, locations, item, defuns,
							backend);
				}
				case WitItem.ImportNamed named -> throw error(witPath, locations, item, "import '" + named.name()
						+ "': a world's inline function imports are not bound; declare the interface to call with "
						+ "rontolisp:wit-import (or a host function with rontolisp:wasm-import)");
				// A world's interface imports come from the fixed WASI surface the
				// component is built on, and its type definitions only describe the
				// signatures. Neither is part of the export contract.
				default -> {
				}
			}
		}
		if (forms.isEmpty()) {
			throw new UnsupportedOperationException(
					witPath + ":" + locations.lineOf(world) + ": world '" + world.name() + "' declares no exports");
		}
		return forms;
	}

	// `export wasi:cli/run@0.3.0;` -- the fixed entry point of every non-serve GC
	// component.
	private static boolean isComponentRunExport(WitItem.ExportRef ref) {
		return ref.target().pkg() != null && "wasi".equals(ref.target().pkg().namespace())
				&& "cli".equals(ref.target().pkg().name()) && RESERVED_RUN.equals(ref.target().name());
	}

	private static WitItem.World selectWorld(WitDocument document, @Nullable String requested, String witPath) {
		List<WitItem.World> worlds = new ArrayList<>();
		collectWorlds(document.items(), worlds);
		if (requested != null) {
			for (WitItem.World world : worlds) {
				if (requested.equals(world.name())) {
					return world;
				}
			}
			throw new UnsupportedOperationException(
					witPath + ": no world named '" + requested + "' (found: " + worldNames(worlds) + ")");
		}
		if (worlds.size() == 1) {
			return worlds.get(0);
		}
		if (worlds.isEmpty()) {
			throw new UnsupportedOperationException(witPath + ": the file declares no world");
		}
		throw new UnsupportedOperationException(witPath + ": the file declares " + worlds.size() + " worlds ("
				+ worldNames(worlds) + "); name one with :world");
	}

	private static void collectWorlds(List<WitItem> items, List<WitItem.World> worlds) {
		for (WitItem item : items) {
			switch (item) {
				case WitItem.World world -> worlds.add(world);
				case WitItem.PackageBlock block -> collectWorlds(block.items(), worlds);
				default -> {
				}
			}
		}
	}

	private static String worldNames(List<WitItem.World> worlds) {
		return String.join(", ", worlds.stream().map(WitItem.World::name).toList());
	}

	// Checks every function of an exported interface (a world's `export docs:adder/add;`,
	// or an inline `export name: interface { ... }`) against the program and lowers each
	// into a wasm-export carrying the interface's id, so the backend bundles them into
	// one
	// exported component instance. `ifaceId` is the instance's export id (the interface's
	// fully-qualified id for a reference, its plain name for an inline interface).
	private static void lowerInterfaceMembers(String ifaceId, List<WitItem> members, List<LispVal> forms,
			Set<String> seen, String witPath, WitLocations locations, WitItem item, Defuns defuns, Backend backend) {
		boolean any = false;
		for (WitItem member : members) {
			// Only plain functions are exportable members; an interface's type
			// definitions
			// only describe signatures, and a resource is not a function export.
			if (member instanceof WitItem.FuncDef func && func.kind() == WitItem.FuncKind.PLAIN) {
				if (!seen.add(ifaceId + "#" + func.name())) {
					throw error(witPath, locations, item,
							"duplicate export '" + func.name() + "' in interface '" + ifaceId + "'");
				}
				forms.add(exportForm(func.name(), func.func(), witPath, locations, item, defuns, backend, ifaceId));
				any = true;
			}
		}
		if (!any) {
			throw error(witPath, locations, item, "interface '" + ifaceId + "' declares no functions to export");
		}
	}

	// Builds the (rontolisp:wasm-export 'name :params '(...) :param-names '(...) :returns
	// ... [:async t] [:interface "id"]) form for one world export, after checking it
	// against the program. `ifaceId` is null for a freestanding function export and the
	// exported interface's id for an interface member.
	private static LispVal exportForm(String name, WitFunc func, String witPath, WitLocations locations, WitItem item,
			Defuns defuns, Backend backend, @Nullable String ifaceId) {
		if (!LABEL.matcher(name).matches()) {
			throw error(witPath, locations, item,
					"export '" + name + "' is not a component-model label (lower-kebab-case words)");
		}
		if (RESERVED_RUN.equals(name)) {
			throw error(witPath, locations, item,
					"export 'run' collides with the component's wasi:cli/run entry point; rename it in the world");
		}
		// The reader upcases user defuns while WIT export names are lower-kebab: try
		// the literal spelling first (lowercase-authored sources), then the upcased
		// twin. The emitted wasm-export quotes the ACTUAL defun spelling; the export
		// label still derives lowercased, so the component surface keeps the WIT name.
		String defunName = name;
		List<String> lambdaList = defuns.lambdaList(defunName);
		if (lambdaList == null) {
			String upper = name.toUpperCase(java.util.Locale.ROOT);
			lambdaList = defuns.lambdaList(upper);
			if (lambdaList != null) {
				defunName = upper;
			}
		}
		if (lambdaList == null) {
			throw error(witPath, locations, item,
					"export '" + name + "' has no matching (defun " + name + " ...) in the program");
		}
		for (String parameter : lambdaList) {
			if (parameter.startsWith("&")) {
				throw error(witPath, locations, item, "export '" + name + "' maps to (defun " + name + " (" + parameter
						+ " ...)): an exported function takes required parameters only");
			}
		}
		if (lambdaList.size() != func.params().size()) {
			throw error(witPath, locations, item, "export '" + name + "' declares " + func.params().size()
					+ " parameter(s), but (defun " + name + " ...) takes " + lambdaList.size());
		}
		if (func.async() && backend == Backend.WASM_NO_GC) {
			throw error(witPath, locations, item,
					"export '" + name + "' is an async func, which --no-gc --component cannot lift "
							+ "(the adapter-free reactor has no async machinery)");
		}
		List<LispVal> params = new ArrayList<>();
		List<LispVal> paramNames = new ArrayList<>();
		for (WitFunc.Param param : func.params()) {
			params.add(new LispSymbol(
					designator(param.type(), name, param.name(), witPath, locations, item, backend).designator()));
			paramNames.add(new LispSymbol(param.name()));
		}
		WitType result = func.result();
		String returns = result == null ? BoundaryType.VOID.designator()
				: designator(result, name, "the result", witPath, locations, item, backend).designator();

		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_EXPORT)));
		out.add(quote(new LispSymbol(defunName)));
		out.add(new LispSymbol(":PARAMS"));
		out.add(quote(list(params)));
		out.add(new LispSymbol(":PARAM-NAMES"));
		out.add(quote(list(paramNames)));
		out.add(new LispSymbol(":RETURNS"));
		out.add(new LispSymbol(returns));
		if (func.async()) {
			out.add(new LispSymbol(":ASYNC"));
			out.add(LispTrue.INSTANCE);
		}
		if (ifaceId != null) {
			// The exported interface's id: the backend bundles every export sharing it
			// into
			// one component instance exported under this id (`export docs:adder/add;`).
			out.add(new LispSymbol(":INTERFACE"));
			out.add(new LispString(ifaceId));
		}
		return list(out);
	}

	// The wasm-export type designator a WIT type crosses the component boundary as. The
	// supported set is exactly the boundary's: the whole fixed-width integer family, plus
	// f64 / bool / string (WitTypeMapper names the representation of every WIT type, but
	// only these have a component-model scalar/string lift today); anything else is a
	// clear
	// compile error rather than a silent reinterpretation.
	private static BoundaryType designator(WitType type, String exportName, String what, String witPath,
			WitLocations locations, WitItem item, Backend backend) {
		if (type instanceof WitType.Prim prim) {
			BoundaryType boundary = BoundaryType.forWitName(prim.name());
			if (boundary != null) {
				return boundary;
			}
		}
		throw error(witPath, locations, item,
				"export '" + exportName + "': the WIT type of " + what + " is not supported at the export boundary yet "
						+ "(supported: " + supportedWitTypes() + "). Its rontolisp representation is settled " + "("
						+ describe(type) + "), but the component boundary cannot marshal it yet");
	}

	// The WIT spellings the export boundary carries, in vocabulary order, for the error
	// message above -- derived from the table so the message can never drift from it.
	private static String supportedWitTypes() {
		List<String> names = new ArrayList<>();
		for (BoundaryType type : BoundaryType.values()) {
			if (type.witName() != null) {
				names.add(type.witName());
			}
		}
		return String.join(", ", names);
	}

	// Names the WIT type and its settled house representation, so the error says what the
	// value WOULD be once the export boundary can marshal it, rather than just refusing.
	private static String describe(WitType type) {
		try {
			return WitTypeMapper.rep(type).name();
		}
		catch (IllegalArgumentException ex) {
			// A named type reference cannot be classified without resolving it.
			return "a named type";
		}
	}

	private static UnsupportedOperationException error(String witPath, WitLocations locations, WitItem item,
			String message) {
		return new UnsupportedOperationException(witPath + ":" + locations.lineOf(item) + ": " + message);
	}

	private static LispVal quote(LispVal value) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
