package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import am.ik.wit.WitParseResult;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * The {@code (rontolisp:wit-export "world.wit" :world name)} directive: <em>this program
 * implements this WIT world</em>. It is a compile-time front-end for
 * {@code rontolisp:wasm-export} -- the world's {@code export} items are checked against
 * the program's {@code defun}s and then lowered into {@code rontolisp:wasm-export}
 * directives, so no new export path exists. A world of flat functions that names no type
 * lowers into exactly the directives a hand-written equivalent would carry, and its
 * component is byte-identical; the exports of any other world carry the WIT, from which a
 * component declares the world's types.
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
 * <li>a WIT type no export carries (a resource handle, a stream, a future), or one beyond
 * the flat set (every fixed-width integer, {@code f64}, {@code bool} and {@code string} —
 * see {@link BoundaryType}) where the output is no component</li>
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

		/**
		 * The scalar backend in component mode ({@code -o out.wasm --no-gc --component}).
		 * {@code wit-export} treats it exactly like {@link #WASM_NO_GC} (the export
		 * boundary is the same scalar one); {@code wit-import} lowers to the same
		 * {@code rontolisp:wasm-import} block but names the import by the interface's
		 * canonical id and the function by its WIT label, which is what the component's
		 * {@code canon lower}ed import instance is typed by.
		 */
		WASM_NO_GC_COMPONENT,

		/** The interpreter or the JVM backend: the directive is inert. */
		OTHER;

		/**
		 * Whether this is the scalar ({@code --no-gc}) backend, in either output shape.
		 * @return {@code true} for {@link #WASM_NO_GC} and {@link #WASM_NO_GC_COMPONENT}
		 */
		public boolean isNoGc() {
			return this == WASM_NO_GC || this == WASM_NO_GC_COMPONENT;
		}

	}

	/**
	 * A parsed {@code rontolisp:wit-export} directive.
	 *
	 * @param path the WIT file path as written (relative paths resolve against the source
	 * file's directory, like {@code load})
	 * @param world the world to implement, or {@code null} to use the file's only world
	 * @param names the naming hook of a front end that spells its identifiers its own way
	 * ({@code :names (("greet" "lisp-name") ...)}), or {@code null}: each world export is
	 * implemented by exactly the function the table names (and exported under the WIT
	 * label, {@code :as}), instead of the defun the label spells; an interface's member
	 * is keyed by {@link #namesKey}, or by its label alone. The Clojure lowering passes
	 * it ({@code rontolisp.wit/export}): a label names a var there, whose function is a
	 * {@code c%ns/name} defun or a wrapper the lowering wrote
	 * @param reactor whether the world's exports are the program's entry points
	 * ({@code :reactor t}): a {@code --component} lifts no {@code wasi:cli/run}, and the
	 * first export call runs the program's top level, once, before its own body. A
	 * program with no main of its own says so: the Java front end's program of
	 * {@code @WitExport} classes
	 * @param octets whether a {@code list<u8>} a host passes an export arrives as the
	 * octets it holds, an {@code (unsigned-byte 8)} vector, instead of the text they
	 * decode to ({@code :octets t}, {@code rontolisp:wit-import}'s option)
	 * @param envelope whether a function whose WIT result is a {@code result} answers its
	 * envelope itself, {@code (:ok . v)} or {@code (:error . e)} ({@code :envelope t}),
	 * instead of the ok arm with {@code rontolisp:wit-error} signalled for the error arm:
	 * a front end whose own error value is no Common Lisp condition makes the envelope in
	 * its wrapper, and the export then makes none
	 */
	public record Directive(String path, @Nullable String world, @Nullable Map<String, String> names, boolean reactor,
			boolean octets, boolean envelope) {

		/**
		 * A directive with no naming hook: each export is the defun its label spells.
		 * @param path the WIT file path as written
		 * @param world the world to implement, or {@code null} for the file's only one
		 */
		public Directive(String path, @Nullable String world) {
			this(path, world, null, false, false, false);
		}

		/**
		 * A directive whose {@code list<u8>} parameters are text.
		 * @param path the WIT file path as written
		 * @param world the world to implement, or {@code null} for the file's only one
		 * @param names the naming hook, or {@code null}
		 * @param reactor whether the world's exports are the program's entry points
		 */
		public Directive(String path, @Nullable String world, @Nullable Map<String, String> names, boolean reactor) {
			this(path, world, names, reactor, false, false);
		}

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
		Map<String, String> names = null;
		boolean reactor = false;
		boolean octets = false;
		boolean envelope = false;
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
			else if (":NAMES".equals(keyword.name())) {
				names = WitNamingHook.parse(value, "rontolisp:wit-export", form);
			}
			else if (":REACTOR".equals(keyword.name())) {
				reactor = WasmImportDirective.booleanValue(value, "rontolisp:wit-export", ":reactor", form);
			}
			else if (":OCTETS".equals(keyword.name())) {
				octets = WasmImportDirective.booleanValue(value, "rontolisp:wit-export", ":octets", form);
			}
			else if (":ENVELOPE".equals(keyword.name())) {
				envelope = WasmImportDirective.booleanValue(value, "rontolisp:wit-export", ":envelope", form);
			}
			else {
				throw new UnsupportedOperationException(
						"Unknown rontolisp:wit-export option " + keyword.name() + " in " + form.print());
			}
			i += 2;
		}
		return new Directive(path.value(), world, names, reactor, octets, envelope);
	}

	/**
	 * Whether a {@code rontolisp:wit-export} directive says the program is a reactor
	 * ({@code :reactor t}), read without checking the rest of it.
	 * @param form the directive form
	 * @return {@code true} when its {@code :reactor} option is {@code t}
	 */
	public static boolean isReactor(LispVal form) {
		return isDirective(form) && parse((LispCons) form).reactor();
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
		return lower(directive, WitSource.file(witPath, witSource), defuns, backend);
	}

	/**
	 * Like {@link #lower(Directive, String, String, Defuns, Backend)}, over a WIT file or
	 * a package read from its directory.
	 * @param directive the parsed directive
	 * @param source the WIT
	 * @param defuns the program's top-level function definitions
	 * @param backend the backend the world is being checked against
	 * @return one {@code (rontolisp:wasm-export ...)} form per world export, in world
	 * order
	 */
	public static List<LispVal> lower(Directive directive, WitSource source, Defuns defuns, Backend backend) {
		String witPath = source.path();
		Implemented implemented = implemented(directive, source);
		WitLocations locations = implemented.locations();
		List<LispVal> forms = new ArrayList<>();
		// The whole document as one text, printed at most once: what a canonical export
		// carries to the backend, which reads no file (as a wit-import's form does).
		String[] text = new String[1];
		java.util.function.Supplier<String> document = () -> {
			if (text[0] == null) {
				text[0] = am.ik.wit.WitPrinter.print(implemented.resolver().document());
			}
			return text[0];
		};
		boolean typed = (backend == Backend.WASM_COMPONENT || backend == Backend.WASM_NO_GC_COMPONENT)
				&& namesTypes(implemented, witPath);
		walk(implemented, witPath, (name, func, item, ifaceId, scope) -> forms.addAll(exportForms(name, func, witPath,
				locations, item, defuns, backend, ifaceId, directive, implemented, scope, document, typed)));
		if (forms.isEmpty()) {
			throw new UnsupportedOperationException(WitSource.at(witPath, locations, implemented.world()) + ": world '"
					+ implemented.world().name() + "' declares no exports");
		}
		return forms;
	}

	/**
	 * One world export, as the walk hands it over: its label, its function, the world
	 * item declaring it, the interface it belongs to ({@code null} for a freestanding
	 * function), and the scope its type names resolve in -- that interface, or the
	 * world's own type definitions.
	 */
	@FunctionalInterface
	private interface ExportVisitor {

		void visit(String name, WitFunc func, WitItem item, @Nullable String ifaceId, WitItem.InterfaceDef scope);

	}

	/**
	 * The world a directive implements, parsed and selected, with the scope a
	 * freestanding function export's type names resolve in.
	 */
	private record Implemented(WitLocations locations, WitResolver resolver, WitItem.World world,
			WitItem.InterfaceDef worldScope, Map<Object, WitItem.InterfaceDef> scopes) {

		// The scope an exported interface's type names resolve in: the interface with
		// its use clauses written out (WitUses), one instance per interface -- keyed by
		// the interface, or by the world item of an inline one.
		WitItem.InterfaceDef scope(Object key) {
			return java.util.Objects.requireNonNull(this.scopes.get(key));
		}

	}

	// Every scope is made here, once: a world read for one program's export forms is
	// read by every compilation of that text (lastRead), on whatever thread.
	private static Implemented implemented(Directive directive, WitSource source) {
		WitParseResult parsed = source.parse();
		WitItem.World world = selectWorld(parsed.document(), directive.world(), source);
		WitResolver resolver = new WitResolver(parsed.document());
		String witPath = source.path();
		WitLocations locations = parsed.locations();
		Map<Object, WitItem.InterfaceDef> scopes = new java.util.IdentityHashMap<>();
		for (WitItem item : world.items()) {
			if (item instanceof WitItem.ExportNamed export
					&& export.extern() instanceof WitItem.Extern.ExternInterface inline) {
				scopes.put(item, inlineUses(resolver, inlineScope(export.name(), inline), true, witPath, locations));
			}
			else if (item instanceof WitItem.ExportRef ref && !isComponentRunExport(ref)) {
				WitItem.InterfaceDef iface = resolver.findInterface(ref.target().toString());
				if (iface != null && !scopes.containsKey(iface)) {
					scopes.put(iface, inlineUses(resolver, iface, true, witPath, locations));
				}
			}
		}
		return new Implemented(locations, resolver, world,
				inlineUses(resolver, worldScope(world), false, witPath, locations), scopes);
	}

	// A scope with the value types its use clauses name written out as its own: a value
	// type is structural, so the copy is the type, and the export then names nothing of
	// another interface (no instance to import, no type to alias). What stays a clause
	// holds a resource, which is nominal. An interface's types are its exports, so what
	// a copied definition mentions comes with it; a world's are imports of the
	// component, which a world of the WIT does not have unless the clause names them.
	private static WitItem.InterfaceDef inlineUses(WitResolver resolver, WitItem.InterfaceDef scope, boolean iface,
			String witPath, WitLocations locations) {
		try {
			return am.ik.wit.WitUses.inline(resolver, scope, iface);
		}
		catch (am.ik.wit.WitUses.Unnamed unnamed) {
			String name = unnamed.name();
			throw error(witPath, locations, unnamed.use(),
					"use " + unnamed.use().path() + " brings the type '" + name + "', "
							+ (unnamed.taken() ? "and the scope holds another type of that name"
									: "which the world does not name (a world's types are imports of the component)")
							+ "; name it in the clause ('" + name + (unnamed.taken() ? " as other-name')" : "')"));
		}
	}

	/**
	 * A world export described for a front end that resolves the function implementing it
	 * before the lowering runs ({@link #describe}).
	 *
	 * @param name the export's label (an interface member's own label)
	 * @param params its parameters: the WIT name and type of each
	 * @param result its result type, or {@code null} for none
	 * @param async whether it is an {@code async func}
	 * @param iface the exported interface it belongs to, or {@code null} for a
	 * freestanding function
	 * @param at the WIT file and line declaring it ({@code path:line})
	 */
	public record Export(String name, List<WitTypeMapper.Param> params, WitTypeMapper.@Nullable Shape result,
			boolean async, @Nullable String iface, String at) {

		/**
		 * {@return the key the directive's {@code :names} table names this export by}
		 * ({@link WitExportDirective#namesKey})
		 */
		public String key() {
			return namesKey(this.name, this.iface);
		}

	}

	/**
	 * A world, described for a front end ({@link #describe}).
	 *
	 * @param name the world's name
	 * @param exports its exports, in world order
	 */
	public record WorldDescription(String name, List<Export> exports) {
	}

	/**
	 * Describes the world a directive implements: every export {@link #lower} checks the
	 * program against, after every check that does not read the program -- the world
	 * exists and exports something, each label is a component-model label and not
	 * {@code run}, no export is declared twice, an interface export names an interface
	 * the file defines, every type is one the export boundary carries. What is left is
	 * the function implementing each export, which {@link #lower} reads from the program;
	 * the Clojure lowering resolves it first, from a label to a var
	 * ({@code rontolisp.wit/export}).
	 * @param directive the parsed directive
	 * @param witSource the WIT text
	 * @param witPath the WIT file path, for error messages
	 * @return the world's name and exports
	 * @throws UnsupportedOperationException on a contract violation, naming the WIT file
	 * and line
	 */
	public static WorldDescription describe(Directive directive, String witSource, String witPath) {
		return describe(directive, WitSource.file(witPath, witSource));
	}

	/**
	 * Like {@link #describe(Directive, String, String)}, over a WIT file or a package
	 * read from its directory.
	 * @param directive the parsed directive
	 * @param source the WIT
	 * @return the world's name and exports
	 */
	public static WorldDescription describe(Directive directive, WitSource source) {
		String witPath = source.path();
		Implemented implemented = implemented(directive, source);
		WitLocations locations = implemented.locations();
		List<Export> exports = new ArrayList<>();
		WitResolver resolver = implemented.resolver();
		walk(implemented, witPath, (name, func, item, ifaceId, scope) -> {
			checkLabel(name, witPath, locations, item);
			List<WitTypeMapper.Param> params = new ArrayList<>();
			for (WitFunc.Param param : func.params()) {
				params.add(new WitTypeMapper.Param(param.name(),
						shapeOf(param.type(), name, param.name(), func, witPath, locations, item, resolver, scope)));
			}
			WitType result = func.result();
			exports.add(new Export(name, List.copyOf(params),
					result == null ? null
							: shapeOf(result, name, "the result", func, witPath, locations, item, resolver, scope),
					func.async(), ifaceId, WitSource.at(witPath, locations, item)));
		});
		if (exports.isEmpty()) {
			throw new UnsupportedOperationException(WitSource.at(witPath, locations, implemented.world()) + ": world '"
					+ implemented.world().name() + "' declares no exports");
		}
		return new WorldDescription(implemented.world().name(), List.copyOf(exports));
	}

	// The shape of an export's type for a front end that binds its own names: a type the
	// flat boundary carries as that primitive, any other whole (aliases followed, every
	// nested type resolved, as an import's member is: WitTypeMapper.shape) once the
	// refusals every
	// backend makes have passed -- lower's, but --no-gc's and a core module's, which it
	// makes knowing the output.
	private static WitTypeMapper.Shape shapeOf(WitType type, String exportName, String what, WitFunc func,
			String witPath, WitLocations locations, WitItem item, WitResolver resolver, WitItem.InterfaceDef scope) {
		BoundaryType boundary = flatDesignator(type, resolver, scope);
		if (boundary != null) {
			String wit = java.util.Objects.requireNonNull(boundary.witName());
			return new WitTypeMapper.Shape(WitTypeMapper.rep(new WitType.Prim(wit)), null, wit);
		}
		requireCanonical(type, exportName, what, witPath, locations, item, Backend.OTHER, resolver, scope);
		if (func.async()) {
			throw error(witPath, locations, item,
					"export '" + exportName + "' is an async func, and an async export carries the flat types only ("
							+ supportedWitTypes() + ")");
		}
		return WitTypeMapper.shape(type, resolver, scope);
	}

	/**
	 * One function a world exports, with what a backend needs to marshal it through the
	 * canonical ABI: the scope its type names resolve in.
	 *
	 * @param resolver the resolver over the WIT document
	 * @param scope the interface the function's type names are written in -- the exported
	 * interface, or the world's own type definitions for a freestanding function
	 * @param ofWorld whether {@code scope} is the world's (its named types are the
	 * world's, which a component declares as type imports), not an exported interface's
	 * @param func the function
	 */
	public record Signature(WitResolver resolver, WitItem.InterfaceDef scope, boolean ofWorld, WitFunc func) {
	}

	// The world the last signature was read from: every canonical export of a program
	// carries one document, and reading it once keeps one resolver -- one identity of
	// each scope -- behind all of them.
	private record ReadWorld(String text, String world, Implemented implemented) {
	}

	private static volatile @Nullable ReadWorld lastRead;

	/**
	 * Finds the function behind a canonical export ({@code rontolisp:wasm-export ... :wit
	 * "text" :wit-world "world"}), as {@link #lower} walked to it.
	 * @param witText the WIT document the form carries
	 * @param world the world's name
	 * @param ifaceId the exported interface's id, or {@code null} for a freestanding
	 * function
	 * @param label the export's label
	 * @return the function and its scope
	 * @throws UnsupportedOperationException when the world exports no such function
	 */
	public static Signature signature(String witText, String world, @Nullable String ifaceId, String label) {
		Implemented implemented = read(witText, world);
		Signature[] found = new Signature[1];
		walk(implemented, "the world", (name, func, item, id, scope) -> {
			if (name.equals(label) && java.util.Objects.equals(id, ifaceId)) {
				found[0] = new Signature(implemented.resolver(), scope, id == null, func);
			}
		});
		if (found[0] == null) {
			throw new UnsupportedOperationException("the world '" + world + "' exports no function '"
					+ namesKey(label, ifaceId) + "' (rontolisp:wasm-export :wit)");
		}
		return found[0];
	}

	/**
	 * An interface a world exports.
	 *
	 * @param id the exported instance's id: the interface's fully-qualified id, or the
	 * plain name of an inline one
	 * @param scope the interface as its members' type names resolve in it
	 */
	public record InterfaceExport(String id, WitItem.InterfaceDef scope) {
	}

	/**
	 * Lists the interfaces the world behind an export form exports, in world order -- one
	 * of types alone among them, which no export form stands for.
	 * @param witText the WIT document the form carries ({@code :wit})
	 * @param world the world's name ({@code :wit-world})
	 * @return the exported interfaces
	 */
	public static List<InterfaceExport> interfaceExports(String witText, String world) {
		return interfaceExports(read(witText, world));
	}

	private static List<InterfaceExport> interfaceExports(Implemented implemented) {
		List<InterfaceExport> exports = new ArrayList<>();
		for (WitItem item : implemented.world().items()) {
			if (item instanceof WitItem.ExportNamed export
					&& export.extern() instanceof WitItem.Extern.ExternInterface inline) {
				exports.add(new InterfaceExport(export.name(), implemented.scope(item)));
			}
			else if (item instanceof WitItem.ExportRef ref && !isComponentRunExport(ref)) {
				WitItem.InterfaceDef iface = implemented.resolver().findInterface(ref.target().toString());
				if (iface != null) {
					exports.add(new InterfaceExport(
							java.util.Objects.requireNonNull(implemented.resolver().canonicalId(iface)),
							implemented.scope(iface)));
				}
			}
		}
		return exports;
	}

	// Whether the world names a type a component has to declare: a type one of its
	// exporting scopes defines (an alias of a flat type too -- a component importing the
	// interface names it), or a type of an export beyond the flat set. A component of
	// such a world declares the types of every scope, so every export form carries the
	// WIT; a world of flat functions over primitives is the hand-written export list.
	private static boolean namesTypes(Implemented implemented, String witPath) {
		if (definesTypes(implemented.worldScope())) {
			return true;
		}
		for (InterfaceExport export : interfaceExports(implemented)) {
			if (definesTypes(export.scope())) {
				return true;
			}
		}
		boolean[] compound = new boolean[1];
		walk(implemented, witPath, (name, func, item, ifaceId, scope) -> {
			for (WitFunc.Param param : func.params()) {
				compound[0] |= flatDesignator(param.type(), implemented.resolver(), scope) == null;
			}
			compound[0] |= func.result() != null
					&& flatDesignator(func.result(), implemented.resolver(), scope) == null;
		});
		return compound[0];
	}

	private static boolean definesTypes(WitItem.InterfaceDef scope) {
		for (WitItem item : scope.items()) {
			if (item instanceof WitItem.TypeAlias || item instanceof WitItem.RecordDef
					|| item instanceof WitItem.VariantDef || item instanceof WitItem.EnumDef
					|| item instanceof WitItem.FlagsDef) {
				return true;
			}
		}
		return false;
	}

	private static Implemented read(String witText, String world) {
		ReadWorld read = lastRead;
		if (read == null || !read.text().equals(witText) || !read.world().equals(world)) {
			read = new ReadWorld(witText, world,
					implemented(new Directive("the world", world), WitSource.file("the world", witText)));
			lastRead = read;
		}
		return read.implemented();
	}

	// Walks the world's exports in world order, handing each to the visitor right after
	// the structural checks lower() has always made before it: a freestanding function
	// export or an interface's members, a duplicate refused as it is met, an interface
	// the file does not define and an inline import refused where they stand.
	private static void walk(Implemented implemented, String witPath, ExportVisitor visitor) {
		WitLocations locations = implemented.locations();
		WitResolver resolver = implemented.resolver();
		Set<String> seen = new LinkedHashSet<>();
		WitItem.InterfaceDef worldScope = implemented.worldScope();
		for (WitItem item : implemented.world().items()) {
			switch (item) {
				case WitItem.ExportNamed export -> {
					switch (export.extern()) {
						case WitItem.Extern.ExternFunc func -> {
							if (!seen.add(export.name())) {
								throw error(witPath, locations, item, "duplicate export '" + export.name() + "'");
							}
							visitor.visit(export.name(), func.func(), item, null, worldScope);
						}
						// An inline interface export (`export add: interface { ... }`):
						// its
						// plain name is the exported instance's id, and each of its
						// functions
						// is a member the program must implement.
						case WitItem.Extern.ExternInterface inline -> lowerInterfaceMembers(export.name(),
								inline.items(), seen, witPath, locations, item, visitor, implemented.scope(item));
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
					lowerInterfaceMembers(ifaceId, iface.items(), seen, witPath, locations, item, visitor,
							implemented.scope(iface));
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
	}

	// `export wasi:cli/run@0.3.0;` -- the fixed entry point of every non-serve GC
	// component.
	private static boolean isComponentRunExport(WitItem.ExportRef ref) {
		return ref.target().pkg() != null && "wasi".equals(ref.target().pkg().namespace())
				&& "cli".equals(ref.target().pkg().name()) && RESERVED_RUN.equals(ref.target().name());
	}

	// A package read from its directory chooses among its OWN worlds: its deps/ are
	// packages it uses, whose worlds (wasi:http's proxy, ...) it does not implement.
	private static WitItem.World selectWorld(WitDocument document, @Nullable String requested, WitSource source) {
		String witPath = source.path();
		List<WitItem.World> worlds = new ArrayList<>();
		collectWorlds(document.items(), worlds, !source.isDirectory());
		String declarer = source.isDirectory() ? "the package" : "the file";
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
			throw new UnsupportedOperationException(witPath + ": " + declarer + " declares no world");
		}
		throw new UnsupportedOperationException(witPath + ": " + declarer + " declares " + worlds.size() + " worlds ("
				+ worldNames(worlds) + "); name one with :world");
	}

	private static void collectWorlds(List<WitItem> items, List<WitItem.World> worlds, boolean blocks) {
		for (WitItem item : items) {
			switch (item) {
				case WitItem.World world -> worlds.add(world);
				case WitItem.PackageBlock block when blocks -> collectWorlds(block.items(), worlds, true);
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
	private static void lowerInterfaceMembers(String ifaceId, List<WitItem> members, Set<String> seen, String witPath,
			WitLocations locations, WitItem item, ExportVisitor visitor, WitItem.InterfaceDef scope) {
		boolean any = false;
		for (WitItem member : members) {
			// A resource's functions and its handles are exports of the interface too,
			// and a component that left them out would not be the world's.
			if (member instanceof WitItem.ResourceDef resource) {
				throw error(witPath, locations, item,
						"interface '" + ifaceId + "' declares the resource '" + resource.name()
								+ "', and an export carries no resource (its constructor, its methods and its "
								+ "handles are not exported)");
			}
			if (member instanceof WitItem.FuncDef func && func.kind() == WitItem.FuncKind.PLAIN) {
				if (!seen.add(ifaceId + "#" + func.name())) {
					throw error(witPath, locations, item,
							"duplicate export '" + func.name() + "' in interface '" + ifaceId + "'");
				}
				visitor.visit(func.name(), func.func(), item, ifaceId, scope);
				any = true;
			}
		}
		// An interface of types alone exports those, which a component declares.
		if (!any && !definesTypes(scope)) {
			throw error(witPath, locations, item, "interface '" + ifaceId + "' declares no functions to export");
		}
	}

	/**
	 * The key a {@code :names} table names an export by where its label alone does not:
	 * {@code <iface>#<label>} of an interface member (the canonical ABI's spelling of the
	 * member), the label of a flat export. A table that names a member by its label alone
	 * names every member of that label.
	 * @param label the export's label
	 * @param iface the exported interface's id, or {@code null} for a flat export
	 * @return the key
	 */
	public static String namesKey(String label, @Nullable String iface) {
		return iface == null ? label : iface + "#" + label;
	}

	// An export's label must be a component-model label, and not the run entry point's.
	private static void checkLabel(String name, String witPath, WitLocations locations, WitItem item) {
		if (!LABEL.matcher(name).matches()) {
			throw error(witPath, locations, item,
					"export '" + name + "' is not a component-model label (lower-kebab-case words)");
		}
		if (RESERVED_RUN.equals(name)) {
			throw error(witPath, locations, item,
					"export 'run' collides with the component's wasi:cli/run entry point; rename it in the world");
		}
	}

	// Builds the forms of one world export, after checking it against the program:
	// (rontolisp:wasm-export 'name :params '(...) :param-names '(...) :returns ...
	// [:async
	// t] [:interface "id"]) where every type is one the flat boundary carries -- the
	// form a hand-written export list would hold, byte for byte --, and otherwise the
	// canonical form, which names the function's place in the WIT instead of its types
	// (:wit, with the text). `ifaceId` is null for a freestanding function export and
	// the exported interface's id for an interface member. Under a :names table the
	// function is the one the table names, exported under the label (:as).
	private static List<LispVal> exportForms(String name, WitFunc func, String witPath, WitLocations locations,
			WitItem item, Defuns defuns, Backend backend, @Nullable String ifaceId, Directive directive,
			Implemented implemented, WitItem.InterfaceDef scope, java.util.function.Supplier<String> document,
			boolean typed) {
		Map<String, String> names = directive.names();
		checkLabel(name, witPath, locations, item);
		String defunName = name;
		List<String> lambdaList;
		if (names != null) {
			String named = names.get(namesKey(name, ifaceId));
			if (named == null && ifaceId != null) {
				named = names.get(name);
			}
			lambdaList = named == null ? null : defuns.lambdaList(named);
			if (named != null) {
				defunName = named;
			}
		}
		else {
			// The reader upcases user defuns while WIT export names are lower-kebab: try
			// the literal spelling first (lowercase-authored sources), then the upcased
			// twin. The emitted wasm-export quotes the ACTUAL defun spelling; the export
			// label still derives lowercased, so the component surface keeps the WIT
			// name.
			lambdaList = defuns.lambdaList(defunName);
			if (lambdaList == null) {
				String upper = name.toUpperCase(java.util.Locale.ROOT);
				lambdaList = defuns.lambdaList(upper);
				if (lambdaList != null) {
					defunName = upper;
				}
			}
		}
		if (lambdaList == null) {
			throw error(witPath, locations, item,
					"export '" + name + "' has no matching (defun " + name + " ...) in the program");
		}
		for (String parameter : lambdaList) {
			if (PackageRegistry.isClLambdaListKeyword(parameter)) {
				throw error(witPath, locations, item, "export '" + name + "' maps to (defun " + name + " (" + parameter
						+ " ...)): an exported function takes required parameters only");
			}
		}
		if (lambdaList.size() != func.params().size()) {
			throw error(witPath, locations, item, "export '" + name + "' declares " + func.params().size()
					+ " parameter(s), but (defun " + name + " ...) takes " + lambdaList.size());
		}
		if (func.async() && backend.isNoGc()) {
			throw error(witPath, locations, item,
					"export '" + name + "' is an async func, which --no-gc --component cannot lift "
							+ "(the adapter-free reactor has no async machinery)");
		}
		WitResolver resolver = implemented.resolver();
		List<LispVal> params = new ArrayList<>();
		List<LispVal> paramNames = new ArrayList<>();
		boolean flat = true;
		for (WitFunc.Param param : func.params()) {
			BoundaryType boundary = flatDesignator(param.type(), resolver, scope);
			if (boundary == null) {
				flat = false;
				requireCanonical(param.type(), name, param.name(), witPath, locations, item, backend, resolver, scope);
			}
			else {
				params.add(new LispSymbol(boundary.designator()));
			}
			paramNames.add(new LispSymbol(param.name()));
		}
		WitType result = func.result();
		String returns = BoundaryType.VOID.designator();
		if (result != null) {
			BoundaryType boundary = flatDesignator(result, resolver, scope);
			if (boundary == null) {
				flat = false;
				requireCanonical(result, name, "the result", witPath, locations, item, backend, resolver, scope);
			}
			else {
				returns = boundary.designator();
			}
		}
		if (!flat && func.async()) {
			throw error(witPath, locations, item,
					"export '" + name + "' is an async func, and an async export carries the flat types only ("
							+ supportedWitTypes() + ")");
		}

		List<LispVal> forms = new ArrayList<>();
		String exported = defunName;
		// A result at the top of a canonical export's answer is the settled mapping's:
		// the function answers the ok arm and signals rontolisp:wit-error for the error
		// arm, as a provider does. The boundary lowers the envelope, which a defun
		// around the function makes -- no backend learns a condition. Only a WASM
		// backend compiles the export.
		if (!flat && backend != Backend.OTHER && !directive.envelope() && result != null
				&& resolveAliases(result, resolver, scope).type() instanceof WitType.ResultOf) {
			exported = defunName + "%wit-export";
			forms.add(envelopeDefun(exported, defunName, lambdaList, defuns.lambdaList(WIT_RUNTIME_DEFUN) != null));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_EXPORT)));
		out.add(quote(new LispSymbol(exported)));
		if (names != null || !flat) {
			// the label, since the function's own name derives no export name (a
			// c%ns/name symbol lowercased is no label, nor is the envelope defun's)
			out.add(new LispSymbol(":AS"));
			out.add(new LispString(name));
		}
		if (flat) {
			out.add(new LispSymbol(":PARAMS"));
			out.add(quote(list(params)));
		}
		if (!flat || typed) {
			// The function's types are the WIT's own: the text travels in the form, with
			// the world and the line the export stands on. A flat export of a world that
			// names types carries it beside its designators: its wrapper is the flat
			// one, its type in a component the WIT's.
			out.add(new LispSymbol(":WIT"));
			out.add(new LispString(document.get()));
			out.add(new LispSymbol(":WIT-WORLD"));
			out.add(new LispString(implemented.world().name()));
			out.add(new LispSymbol(":WIT-AT"));
			out.add(new LispString(WitSource.at(witPath, locations, item)));
			if (!flat && directive.octets()) {
				out.add(new LispSymbol(":OCTETS"));
				out.add(LispTrue.INSTANCE);
			}
		}
		out.add(new LispSymbol(":PARAM-NAMES"));
		out.add(quote(list(paramNames)));
		if (flat) {
			out.add(new LispSymbol(":RETURNS"));
			out.add(new LispSymbol(returns));
		}
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
		forms.add(list(out));
		return forms;
	}

	// A defun of the WIT run time (wit.lisp): the program holds it exactly when the
	// library was spliced, that is when it names rontolisp:wit-error and so may signal
	// it.
	private static final String WIT_RUNTIME_DEFUN = PackageRegistry.qualify(LispNames.RONTOLISP_PKG,
			LispNames.WIT_PROVIDE);

	// (defun f%wit-export (p0 p1) (handler-case (cons :ok (f p0 p1)) (rontolisp:wit-error
	// (e) (cons :error (rontolisp:wit-error-payload e))))) -- or, in a program that names
	// no rontolisp:wit-error and so can signal none, the ok arm alone.
	private static LispVal envelopeDefun(String name, String target, List<String> lambdaList, boolean signals) {
		List<LispVal> parameters = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(target));
		for (int i = 0; i < lambdaList.size(); i++) {
			LispSymbol parameter = new LispSymbol("P" + i);
			parameters.add(parameter);
			call.add(parameter);
		}
		LispVal ok = list(List.of(new LispSymbol(LispNames.CONS), new LispSymbol(":OK"), list(call)));
		LispVal body = ok;
		if (signals) {
			LispSymbol condition = new LispSymbol("E");
			LispVal payload = list(List.of(
					new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIT_ERROR_PAYLOAD)),
					condition));
			LispVal clause = list(
					List.of(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WIT_ERROR)),
							list(List.of(condition)),
							list(List.of(new LispSymbol(LispNames.CONS), new LispSymbol(":ERROR"), payload))));
			body = list(List.of(new LispSymbol(LispNames.HANDLER_CASE), ok, clause));
		}
		return list(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(name), list(parameters), body));
	}

	// The world's own type definitions and `use` clauses, as the scope a freestanding
	// function export's type names resolve in.
	private static WitItem.InterfaceDef worldScope(WitItem.World world) {
		List<WitItem> types = new ArrayList<>();
		for (WitItem item : world.items()) {
			switch (item) {
				case WitItem.TypeAlias ignored -> types.add(item);
				case WitItem.RecordDef ignored -> types.add(item);
				case WitItem.VariantDef ignored -> types.add(item);
				case WitItem.EnumDef ignored -> types.add(item);
				case WitItem.FlagsDef ignored -> types.add(item);
				case WitItem.ResourceDef ignored -> types.add(item);
				case WitItem.Use ignored -> types.add(item);
				default -> {
				}
			}
		}
		return new WitItem.InterfaceDef(world.meta(), world.name(), List.copyOf(types));
	}

	// An inline interface export's items, as the scope its members' type names resolve
	// in.
	private static WitItem.InterfaceDef inlineScope(String name, WitItem.Extern.ExternInterface inline) {
		return new WitItem.InterfaceDef(am.ik.wit.WitMeta.none(), name, inline.items());
	}

	// A type and the scope it is written in.
	private record Scoped(WitItem.InterfaceDef scope, WitType type) {
	}

	// Follows a `type` alias chain (across interfaces) to the type it bottoms out at.
	private static Scoped resolveAliases(WitType type, WitResolver resolver, WitItem.InterfaceDef scope) {
		if (type instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(scope, named.name());
			if (owned != null && owned.item() instanceof WitItem.TypeAlias alias) {
				return resolveAliases(alias.target(), resolver, owned.owner());
			}
		}
		return new Scoped(scope, type);
	}

	// The wasm-export type designator of a WIT type the flat boundary carries: the whole
	// fixed-width integer family, f64, bool and string, through any alias; null for
	// every other type, which crosses through the canonical ABI or not at all.
	private static @Nullable BoundaryType flatDesignator(WitType type, WitResolver resolver,
			WitItem.InterfaceDef scope) {
		return resolveAliases(type, resolver, scope).type() instanceof WitType.Prim prim
				? BoundaryType.forWitName(prim.name()) : null;
	}

	// Refuses a type the canonical export does not carry, and a backend that has no
	// canonical export: the scalar backend holds no value a record, a list or a variant
	// could be. What is carried is everything but a resource handle, a stream and a
	// future, wherever it nests.
	private static void requireCanonical(WitType type, String exportName, String what, String witPath,
			WitLocations locations, WitItem item, Backend backend, WitResolver resolver, WitItem.InterfaceDef scope) {
		if (backend.isNoGc()) {
			throw error(witPath, locations, item,
					"export '" + exportName + "': the WIT type of " + what + " is not one --no-gc can carry ("
							+ supportedWitTypes() + "): the scalar backend has no value for it (" + describe(type)
							+ "); compile the world on the default backend's --component, where the canonical ABI "
							+ "marshals it");
		}
		String refused = refusedCanonical(type, resolver, scope, new java.util.HashSet<>());
		if (refused != null) {
			throw error(witPath, locations, item,
					"export '" + exportName + "': the WIT type of " + what + " holds " + refused
							+ ", which an export does not carry (carried: every primitive type, and a list, an "
							+ "option, a result, a tuple, a record, a variant, an enum or flags of them)");
		}
	}

	// What of a type the canonical export does not carry, in words, or null.
	private static @Nullable String refusedCanonical(WitType type, WitResolver resolver, WitItem.InterfaceDef scope,
			Set<String> walking) {
		return switch (type) {
			case WitType.Prim ignored -> null;
			case WitType.ListOf list -> refusedCanonical(list.element(), resolver, scope, walking);
			case WitType.OptionOf option -> refusedCanonical(option.element(), resolver, scope, walking);
			case WitType.ResultOf res -> {
				String ok = res.ok() == null ? null : refusedCanonical(res.ok(), resolver, scope, walking);
				yield ok != null || res.err() == null ? ok : refusedCanonical(res.err(), resolver, scope, walking);
			}
			case WitType.TupleOf tuple -> {
				String refused = null;
				for (WitType element : tuple.elements()) {
					refused = refused != null ? refused : refusedCanonical(element, resolver, scope, walking);
				}
				yield refused;
			}
			case WitType.StreamOf ignored -> "a stream";
			case WitType.FutureOf ignored -> "a future";
			case WitType.BorrowOf borrow -> "a handle of the resource '" + borrow.resource() + "'";
			case WitType.OwnOf own -> "a handle of the resource '" + own.resource() + "'";
			case WitType.Named named -> {
				WitResolver.Owned owned = resolver.resolveOwned(scope, named.name());
				if (owned == null) {
					yield "'" + named.name() + "', a type the file does not define";
				}
				if (owned.owner() != scope) {
					// What is still a use clause holds a resource (inlineUses): say
					// which.
					String name = java.util.Objects.requireNonNull(WitResolver.definedTypeName(owned.item()));
					String held = refusedCanonical(new WitType.Named(name), resolver, owned.owner(),
							new java.util.HashSet<>());
					yield held != null ? held : "'" + named.name() + "', a type of another interface (a use clause)";
				}
				if (!walking.add(named.name())) {
					yield "'" + named.name() + "', a type that contains itself";
				}
				String refused = switch (owned.item()) {
					case WitItem.TypeAlias alias -> refusedCanonical(alias.target(), resolver, scope, walking);
					case WitItem.RecordDef record -> {
						String field = record.fields().isEmpty() ? "'" + named.name() + "', a record of no field"
								: null;
						for (WitItem.Field f : record.fields()) {
							field = field != null ? field : refusedCanonical(f.type(), resolver, scope, walking);
						}
						yield field;
					}
					case WitItem.VariantDef variant -> {
						String payload = null;
						for (WitItem.Case c : variant.cases()) {
							payload = payload != null || c.payload() == null ? payload
									: refusedCanonical(c.payload(), resolver, scope, walking);
						}
						yield payload;
					}
					case WitItem.EnumDef ignored -> null;
					case WitItem.FlagsDef ignored -> null;
					case WitItem.ResourceDef ignored -> "a handle of the resource '" + named.name() + "'";
					default -> "'" + named.name() + "'";
				};
				walking.remove(named.name());
				yield refused;
			}
		};
	}

	// The WIT spellings the flat boundary carries, in vocabulary order.
	private static String supportedWitTypes() {
		return BoundaryType.witNames();
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
		return new UnsupportedOperationException(WitSource.at(witPath, locations, item) + ": " + message);
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
