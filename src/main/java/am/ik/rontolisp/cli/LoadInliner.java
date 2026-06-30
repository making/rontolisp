package am.ik.rontolisp.cli;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
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
		List<LispVal> result = new ArrayList<>();
		expandInto(program, result, loader, new ArrayDeque<>(), baseDir);
		return result;
	}

	private static void expandInto(List<LispVal> forms, List<LispVal> out, SourceLoader loader, Deque<String> loading,
			@Nullable String baseDir) {
		for (LispVal form : forms) {
			String rawPath = loadPath(form);
			if (rawPath == null) {
				out.add(form);
				continue;
			}
			// Resolve relative to the loading file's directory (the entry source at the
			// top level), the same rule the runtime load uses.
			String path = SourceLoader.resolve(baseDir, rawPath);
			if (loading.contains(path)) {
				throw new IllegalStateException(
						"Circular load detected: " + String.join(" -> ", loading) + " -> " + path);
			}
			String source;
			try {
				source = loader.load(path);
			}
			catch (IOException ex) {
				throw new IllegalStateException(LispNames.LOAD + ": cannot read file " + path + ": " + ex.getMessage(),
						ex);
			}
			loading.addLast(path);
			// A nested load inside this file resolves relative to this file's directory.
			expandInto(LispReader.readAllFromString(source), out, loader, loading, SourceLoader.parentDir(path));
			loading.removeLast();
		}
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

}
