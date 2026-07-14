package am.ik.rontolisp.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.eval.SourceLoader;

import org.jspecify.annotations.Nullable;

/**
 * Expands a top-level {@code (rontolisp:wit-export "world.wit" :world name)} directive on
 * the compile path: the WIT world is read, the program's {@code defun}s are checked
 * against it, and the directive is replaced by the {@code rontolisp:wasm-export}
 * directives it stands for -- so the backends see exactly what a hand-written export list
 * would have produced, and the emitted component is byte-identical to it.
 *
 * <p>
 * It runs after {@link LoadInliner} and {@link am.ik.rontolisp.eval.UserMacroExpander},
 * so every {@code defun} the program has (including those spliced in from a
 * {@code load}ed file or produced by a macro) is a literal top-level form and can be
 * checked. The interpreter does its own check as it evaluates the directive
 * ({@code LispEvaluator.evalWitExport}).
 *
 * <p>
 * When a world is in effect it is the <strong>authoritative</strong> export list: a
 * hand-written {@code rontolisp:wasm-export} in the same program is a compile error, so
 * the component's exports and the {@code .wit} can never disagree.
 */
public final class WitExportInliner {

	private WitExportInliner() {
	}

	/**
	 * Returns whether the program contains a top-level {@code rontolisp:wit-export}
	 * directive.
	 * @param program the top-level forms
	 * @return {@code true} if the program implements a WIT world
	 */
	public static boolean usesWitExport(List<LispVal> program) {
		return program.stream().anyMatch(WitExportDirective::isDirective);
	}

	/**
	 * Returns a copy of {@code program} with every {@code rontolisp:wit-export} directive
	 * replaced by the {@code rontolisp:wasm-export} directives its world declares.
	 * @param program the top-level forms
	 * @param baseDir the directory of the source file (relative WIT paths resolve against
	 * it), or {@code null} for the working directory
	 * @param backend the backend being compiled for (only the WASM ones impose the
	 * boundary's backend-specific rules)
	 * @return the rewritten program, or {@code program} itself if it implements no world
	 */
	public static List<LispVal> inline(List<LispVal> program, @Nullable String baseDir,
			WitExportDirective.Backend backend) {
		if (!usesWitExport(program)) {
			return program;
		}
		Map<String, List<String>> defuns = collectDefuns(program);
		List<LispVal> result = new ArrayList<>(program.size());
		for (LispVal form : program) {
			if (WitExportDirective.isDirective(form)) {
				WitExportDirective.Directive directive = WitExportDirective.parse((LispCons) form);
				String path = SourceLoader.resolve(baseDir, directive.path());
				result.addAll(WitExportDirective.lower(directive, read(path), path, defuns::get, backend));
			}
			else if (isWasmExport(form)) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-export cannot be combined with rontolisp:wit-export: the WIT world is the "
								+ "program's export list. Declare the export in the world, or drop the wit-export "
								+ "directive. Offending form: " + form.print());
			}
			else {
				result.add(form);
			}
		}
		return result;
	}

	private static String read(String path) {
		try {
			return Files.readString(Path.of(path));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("rontolisp:wit-export: cannot read WIT file " + path, ex);
		}
	}

	// The lambda list of every top-level defun, in the shape WitExportDirective checks
	// (the written parameter names, &optional / &rest / ... markers included -- an
	// exported function must take required parameters only). The pass runs before
	// LambdaLists desugaring, so this is the user's own lambda list.
	private static Map<String, List<String>> collectDefuns(List<LispVal> program) {
		Map<String, List<String>> defuns = new HashMap<>();
		for (LispVal form : program) {
			if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
					|| !LispNames.DEFUN.equals(op.name())) {
				continue;
			}
			List<LispVal> parts = cons.toList();
			if (parts.size() < 3 || !(parts.get(1) instanceof LispSymbol name)) {
				continue;
			}
			List<String> lambdaList = new ArrayList<>();
			if (parts.get(2) instanceof LispCons params) {
				for (LispVal param : params.toList()) {
					if (param instanceof LispSymbol symbol) {
						lambdaList.add(symbol.name());
					}
					else if (param instanceof LispCons initForm && initForm.car() instanceof LispSymbol symbol) {
						// (&optional (x 0)) / (&key (k 1)): the parameter is the car.
						lambdaList.add(symbol.name());
					}
				}
			}
			defuns.put(name.name(), List.copyOf(lambdaList));
		}
		return defuns;
	}

	private static boolean isWasmExport(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			var qn = am.ik.rontolisp.PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WASM_EXPORT.equals(qn.member());
		}
		return false;
	}

}
