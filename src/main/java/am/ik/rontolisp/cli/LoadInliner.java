package am.ik.rontolisp.cli;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.AsdfSystems;
import am.ik.rontolisp.eval.SourceLoader;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * Expands top-level {@code (load "path")} forms into the forms of the loaded file so that
 * a program split across files (a console driver that loads a rendering-free core)
 * compiles on the JVM and WASM backends.
 *
 * <p>
 * The interpreter loads at runtime against the global environment, so this pass runs only
 * on the compile path: the compilers collect {@code defun}s in a static pass, which a
 * runtime {@code load} cannot feed. Inlining the loaded forms at the source level makes
 * the loaded definitions visible to that static pass, exactly as if the files had been
 * concatenated.
 *
 * <p>
 * Only a top-level call whose operator is {@code load} and whose single argument is a
 * string literal is inlined; a {@code load} with a computed argument, or one nested
 * inside another form, is left untouched (it still runs at runtime via the embedded
 * reader, e.g. under {@code --dynamic}). Inlining is recursive (a loaded file may load
 * another) and guards against cycles. A relative path is resolved against the directory
 * of the file doing the load (the entry source for top-level loads), matching the runtime
 * {@code load} (see {@link SourceLoader#resolve}); the resolved path is then read by the
 * supplied {@link SourceLoader}.
 *
 * <p>
 * The same pass implements the compile-time side of {@code require}/{@code provide}
 * (idempotent module loading). A top-level {@code (provide NAME)} records the module name
 * and is consumed (replaced by a quoted symbol, like {@code in-package}); a top-level
 * {@code (require NAME)} splices {@code NAME.lisp} exactly like {@code load} -- unless
 * the name was already provided, in which case it is consumed without loading. An
 * explicit second argument {@code (require NAME "path.lisp")} overrides the file mapping.
 * The provided-module set is threaded through the whole inline recursion, so the diamond
 * case (two files both requiring the same module) loads the module once. Unlike
 * {@code load}, a {@code require}/{@code provide} that is not a literal top-level form
 * cannot be deferred to runtime (the compiled runtime reader does not know them), so the
 * compilers reject any occurrence left after this pass.
 *
 * <p>
 * The same pass also implements the compile-time side of the limited ASDF subset (see
 * {@link AsdfSystems}). A top-level {@code (asdf:defsystem ...)} registers a system and
 * is consumed; a top-level literal {@code (asdf:load-system NAME)} resolves the system --
 * from a prior {@code defsystem}, or by locating {@code NAME.asd} in the directory of the
 * loading file and then on the system search path -- and splices its dependency systems
 * and component files in order, exactly like a chain of {@code load}s. Loading the same
 * system twice is a no-op (like {@code require}). The handling lives inside this
 * recursion rather than as a separate pass so that a loaded file can call
 * {@code asdf:load-system} and a spliced component file can {@code load}/{@code require}
 * its own companions.
 */
public final class LoadInliner {

	private LoadInliner() {
	}

	/**
	 * Returns a copy of {@code program} with every top-level literal
	 * {@code (load "path")} replaced by the (recursively inlined) forms of the loaded
	 * file, resolving top-level relative paths working-directory-relative.
	 * @param program the top-level forms read from the source
	 * @param loader the loader used to resolve {@code load} paths
	 * @return the program with top-level {@code load} forms inlined
	 */
	public static List<LispVal> inline(List<LispVal> program, SourceLoader loader) {
		return inline(program, loader, null);
	}

	/**
	 * Returns a copy of {@code program} with every top-level literal
	 * {@code (load "path")} replaced by the (recursively inlined) forms of the loaded
	 * file.
	 * @param program the top-level forms read from the source
	 * @param loader the loader used to resolve {@code load} paths
	 * @param baseDir the directory of the entry source against which a top-level relative
	 * {@code load} resolves, or {@code null} for working-directory-relative
	 * @return the program with top-level {@code load} forms inlined
	 */
	public static List<LispVal> inline(List<LispVal> program, SourceLoader loader, @Nullable String baseDir) {
		return inline(program, loader, baseDir, List.of());
	}

	/**
	 * Returns a copy of {@code program} with every top-level literal
	 * {@code (load "path")} replaced by the (recursively inlined) forms of the loaded
	 * file, and every top-level literal {@code (asdf:load-system NAME)} replaced by the
	 * system's component files (dependency systems first).
	 * @param program the top-level forms read from the source
	 * @param loader the loader used to resolve {@code load} paths
	 * @param baseDir the directory of the entry source against which a top-level relative
	 * {@code load} resolves, or {@code null} for working-directory-relative
	 * @param systemPath extra directories searched for {@code NAME.asd} files, after the
	 * directory of the loading file
	 * @return the program with top-level {@code load} forms inlined
	 */
	public static List<LispVal> inline(List<LispVal> program, SourceLoader loader, @Nullable String baseDir,
			List<String> systemPath) {
		List<LispVal> result = new ArrayList<>();
		expandInto(program, result, new Ctx(loader, new ArrayDeque<>(), new HashSet<>(), new HashMap<>(),
				new HashSet<>(), new ArrayDeque<>(), systemPath), baseDir);
		return result;
	}

	/**
	 * The state threaded through the inline recursion: the loader, the in-progress file
	 * stack (cycle guard), the provided modules, and the ASDF side -- the registered
	 * systems, the already-loaded systems, the in-progress system stack (cycle guard) and
	 * the {@code .asd} search path.
	 */
	private record Ctx(SourceLoader loader, Deque<String> loading, Set<String> provided,
			Map<String, AsdfSystems.LispSystem> systems, Set<String> loadedSystems, Deque<String> loadingSystems,
			List<String> systemPath) {
	}

	private static void expandInto(List<LispVal> forms, List<LispVal> out, Ctx ctx, @Nullable String baseDir) {
		for (LispVal form : forms) {
			if (AsdfSystems.isDefsystemForm(form)) {
				// Register the system for a later load-system and consume the directive
				// (like provide). Component paths resolve against this file's directory.
				AsdfSystems.LispSystem system = AsdfSystems.parseDefsystem(form, baseDir);
				ctx.systems().put(system.name(), system);
				out.add(quotedSymbol(system.name()));
				continue;
			}
			String systemName = AsdfSystems.loadSystemName(form);
			if (systemName != null) {
				spliceSystem(systemName, out, ctx, baseDir);
				out.add(quotedSymbol(systemName));
				continue;
			}
			String provideName = provideName(form);
			if (provideName != null) {
				// Record the module and consume the directive (a duplicate provide is a
				// no-op, like Common Lisp).
				ctx.provided().add(provideName);
				out.add(quotedSymbol(provideName));
				continue;
			}
			RequireForm require = requireForm(form);
			String rawPath;
			if (require != null) {
				if (ctx.provided().contains(require.name())) {
					// Already provided: consume without loading (this is the whole point
					// of require over load).
					out.add(quotedSymbol(require.name()));
					continue;
				}
				rawPath = require.path() != null ? require.path() : require.name() + ".lisp";
			}
			else {
				rawPath = loadPath(form);
			}
			if (rawPath == null) {
				out.add(form);
				continue;
			}
			String operator = require != null ? LispNames.REQUIRE : LispNames.LOAD;
			// Resolve relative to the loading file's directory (the entry source at the
			// top level), the same rule the runtime load uses.
			spliceFile(operator, SourceLoader.resolve(baseDir, rawPath), out, ctx);
		}
	}

	/**
	 * Reads the file at the (resolved) path and recursively expands its forms into
	 * {@code out}, guarding against load cycles. Nested loads inside the file resolve
	 * relative to the file's directory.
	 */
	private static void spliceFile(String operator, String path, List<LispVal> out, Ctx ctx) {
		if (ctx.loading().contains(path)) {
			throw new IllegalStateException(
					"Circular load detected: " + String.join(" -> ", ctx.loading()) + " -> " + path);
		}
		String source;
		try {
			source = ctx.loader().load(path);
		}
		catch (IOException ex) {
			throw new IllegalStateException(operator + ": cannot read file " + path + ": " + ex.getMessage(), ex);
		}
		ctx.loading().addLast(path);
		expandInto(LispReader.readAllFromString(source), out, ctx, SourceLoader.parentDir(path));
		ctx.loading().removeLast();
	}

	/**
	 * Splices the named system into {@code out}: dependency systems first (recursively),
	 * then the component files in their {@code :depends-on}/{@code :serial} order. An
	 * already-loaded system is a no-op; an unknown system is located as {@code NAME.asd}
	 * in the directory of the loading file ({@code requestBaseDir}) and then on the
	 * system search path.
	 */
	private static void spliceSystem(String name, List<LispVal> out, Ctx ctx, @Nullable String requestBaseDir) {
		if (ctx.loadedSystems().contains(name)) {
			return;
		}
		if (ctx.loadingSystems().contains(name)) {
			throw new IllegalStateException("Circular system :depends-on detected: "
					+ String.join(" -> ", ctx.loadingSystems()) + " -> " + name);
		}
		AsdfSystems.LispSystem system = ctx.systems().get(name);
		if (system == null) {
			List<String> searchDirs = new ArrayList<>();
			searchDirs.add(requestBaseDir == null ? "" : requestBaseDir);
			searchDirs.addAll(ctx.systemPath());
			AsdfSystems.LocatedAsd asd = AsdfSystems.locate(name, searchDirs, ctx.loader());
			for (AsdfSystems.LispSystem defined : AsdfSystems.parseAsdSource(asd.source(), asd.path())) {
				ctx.systems().putIfAbsent(defined.name(), defined);
			}
			system = ctx.systems().get(name);
			if (system == null) {
				throw new IllegalStateException(asd.path() + " does not define system '" + name + "'");
			}
		}
		ctx.loadingSystems().addLast(name);
		try {
			for (String dependency : system.dependsOn()) {
				// A dependency's .asd most likely sits next to this system's (or on the
				// search path), so its directory becomes the first search entry.
				spliceSystem(dependency, out, ctx, system.baseDir());
			}
			for (String file : system.files()) {
				spliceFile(LispNames.ASDF_LOAD_SYSTEM, SourceLoader.resolve(system.baseDir(), file), out, ctx);
			}
		}
		finally {
			ctx.loadingSystems().removeLast();
		}
		ctx.loadedSystems().add(name);
	}

	/**
	 * If {@code form} is a top-level {@code (load "path")} with a string-literal
	 * argument, returns the path; otherwise returns {@code null}.
	 */
	@Nullable private static String loadPath(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.size() != 2) {
			return null;
		}
		if (!(items.get(0) instanceof LispSymbol op) || !LispNames.LOAD.equals(op.name())) {
			return null;
		}
		if (!(items.get(1) instanceof LispString path)) {
			return null;
		}
		return path.value();
	}

	/**
	 * If {@code form} is a top-level {@code (provide NAME)}, returns the module name;
	 * otherwise returns {@code null}. A {@code provide} whose argument is not a literal
	 * designator is a hard error: unlike {@code load}, the compiled runtime reader does
	 * not know {@code provide}, so it cannot be deferred to runtime.
	 */
	@Nullable private static String provideName(LispVal form) {
		List<LispVal> items = operatorForm(form, LispNames.PROVIDE);
		if (items == null) {
			return null;
		}
		if (items.size() != 2) {
			throw new IllegalStateException(LispNames.PROVIDE + " expects exactly one argument: " + form.print());
		}
		return moduleDesignator(LispNames.PROVIDE, items.get(1), form);
	}

	/**
	 * If {@code form} is a top-level {@code (require NAME)} or
	 * {@code (require NAME "path.lisp")}, returns the parsed directive; otherwise returns
	 * {@code null}. Like {@code provide}, a non-literal argument is a hard error.
	 */
	@Nullable private static RequireForm requireForm(LispVal form) {
		List<LispVal> items = operatorForm(form, LispNames.REQUIRE);
		if (items == null) {
			return null;
		}
		if (items.size() != 2 && items.size() != 3) {
			throw new IllegalStateException(
					LispNames.REQUIRE + " expects a module name and an optional file path: " + form.print());
		}
		String name = moduleDesignator(LispNames.REQUIRE, items.get(1), form);
		String path = null;
		if (items.size() == 3) {
			if (!(items.get(2) instanceof LispString str)) {
				throw new IllegalStateException(
						LispNames.REQUIRE + " expects a string-literal file path: " + form.print());
			}
			path = str.value();
		}
		return new RequireForm(name, path);
	}

	@Nullable private static List<LispVal> operatorForm(LispVal form, String operator) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !operator.equals(op.name())) {
			return null;
		}
		return cons.toList();
	}

	/**
	 * Parses a literal module-name designator: a keyword ({@code :util}), a quoted symbol
	 * ({@code 'util}) or a string ({@code "util"}). Anything else (a bare symbol would be
	 * a variable reference at runtime, a computed expression cannot be evaluated here) is
	 * a hard error.
	 */
	private static String moduleDesignator(String operator, LispVal designator, LispVal form) {
		if (designator instanceof LispCons quoted && quoted.car() instanceof LispSymbol quoteOp
				&& LispNames.QUOTE.equals(quoteOp.name()) && quoted.cdr() instanceof LispCons datumCell
				&& datumCell.car() instanceof LispSymbol datum) {
			return datum.name();
		}
		if (designator instanceof LispSymbol sym && sym.isKeyword()) {
			return sym.name().substring(1);
		}
		if (designator instanceof LispString str) {
			return str.value();
		}
		throw new IllegalStateException(
				operator + " expects a literal module name (keyword, quoted symbol or string), got " + form.print());
	}

	private static LispVal quotedSymbol(String name) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(new LispSymbol(name), LispNil.INSTANCE));
	}

	private record RequireForm(String name, @Nullable String path) {
	}

}
