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
 * <li>a WIT type outside the boundary subset ({@code s32} / {@code s64} / {@code f64} /
 * {@code bool} / {@code string}), including {@code s64} on the wasm-GC backend, which
 * only {@code --no-gc --component} can carry</li>
 * <li>an export name that is not a component-model label, or the reserved
 * {@code run}</li>
 * <li>an {@code async func} in the world (the {@code :async t} lift is stated by the WIT
 * instead of guessed; a sync-lifted export doing I/O traps at run time)</li>
 * </ul>
 *
 * <p>
 * The world's <em>import</em> side is not a contract yet: {@code import} items are
 * ignored (a component's WASI imports come from the fixed adapter surface), and binding a
 * world's imports to host functions is {@code .todo/127} / {@code .todo/128}. An inline
 * {@code import name: func(...)} is rejected rather than silently dropped.
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

		/** The wasm-GC backend ({@code -o out.wasm}). */
		WASM_GC,

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
			if (":world".equals(keyword.name())) {
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
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				yield qn == null ? sym.name() : qn.member();
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
		List<LispVal> forms = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (WitItem item : world.items()) {
			switch (item) {
				case WitItem.ExportNamed export -> {
					if (!(export.extern() instanceof WitItem.Extern.ExternFunc func)) {
						throw error(witPath, locations, item, "export '" + export.name()
								+ "' is an inline interface; rontolisp:wit-export implements plain function exports only");
					}
					if (!seen.add(export.name())) {
						throw error(witPath, locations, item, "duplicate export '" + export.name() + "'");
					}
					forms.add(exportForm(export, func.func(), witPath, locations, item, defuns, backend));
				}
				case WitItem.ExportRef ref -> {
					// wasi:cli/run is the component's own entry point (the adapter lifts
					// the
					// program's top level as it), not something a defun implements -- and
					// every world --emit-wit emits for a GC component carries it, so a
					// world
					// we
					// emitted must be feedable straight back in. Any OTHER interface
					// export
					// would need machinery this directive does not have.
					if (!isComponentRunExport(ref)) {
						throw error(witPath, locations, item, "export '" + ref.target().name()
								+ "' names an interface; rontolisp:wit-export implements plain function exports only "
								+ "(a program's wasi:http/incoming-handler export comes from rontolisp:http-handler)");
					}
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

	// Builds the (rontolisp:wasm-export 'name :params '(...) :param-names '(...) :returns
	// ... [:async t]) form for one world export, after checking it against the program.
	private static LispVal exportForm(WitItem.ExportNamed export, WitFunc func, String witPath, WitLocations locations,
			WitItem item, Defuns defuns, Backend backend) {
		String name = export.name();
		if (!LABEL.matcher(name).matches()) {
			throw error(witPath, locations, item,
					"export '" + name + "' is not a component-model label (lower-kebab-case words)");
		}
		if (RESERVED_RUN.equals(name)) {
			throw error(witPath, locations, item,
					"export 'run' collides with the component's wasi:cli/run entry point; rename it in the world");
		}
		List<String> lambdaList = defuns.lambdaList(name);
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
			params.add(new LispSymbol(designator(param.type(), name, param.name(), witPath, locations, item, backend)));
			paramNames.add(new LispSymbol(param.name()));
		}
		WitType result = func.result();
		String returns = result == null ? ":void"
				: designator(result, name, "the result", witPath, locations, item, backend);

		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_EXPORT)));
		out.add(quote(new LispSymbol(name)));
		out.add(new LispSymbol(":params"));
		out.add(quote(list(params)));
		out.add(new LispSymbol(":param-names"));
		out.add(quote(list(paramNames)));
		out.add(new LispSymbol(":returns"));
		out.add(new LispSymbol(returns));
		if (func.async()) {
			out.add(new LispSymbol(":async"));
			out.add(LispTrue.INSTANCE);
		}
		return list(out);
	}

	// The wasm-export type designator a WIT type crosses the component boundary as. The
	// supported set is exactly the boundary's (WitTypeMapper names the representation of
	// every WIT type, but only these five have a component-model scalar/string lift
	// today);
	// anything else is a clear compile error rather than a silent reinterpretation.
	private static String designator(WitType type, String exportName, String what, String witPath,
			WitLocations locations, WitItem item, Backend backend) {
		if (type instanceof WitType.Prim prim) {
			switch (prim.name()) {
				case "s32":
					return ":int";
				case "s64":
					if (backend == Backend.WASM_GC) {
						throw error(witPath, locations, item, "export '" + exportName + "': s64 (" + what
								+ ") requires --no-gc (the wasm-GC backend's integers are i31ref)");
					}
					return ":long";
				case "f64":
					return ":float";
				case "bool":
					return ":bool";
				case "string":
					return ":string";
				default:
					break;
			}
		}
		throw error(witPath, locations, item,
				"export '" + exportName + "': the WIT type of " + what + " is not supported at the export boundary yet "
						+ "(supported: s32, s64, f64, bool, string). Its rontolisp representation is settled " + "("
						+ describe(type) + "), but the component boundary cannot marshal it yet");
	}

	// Names the WIT type and its settled house representation, so the error says what the
	// value WOULD be once .todo/128 lands rather than just refusing.
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
