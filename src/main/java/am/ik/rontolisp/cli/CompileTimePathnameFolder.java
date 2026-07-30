package am.ik.rontolisp.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.eval.AsdfSystems;
import am.ik.rontolisp.eval.PathnameOps;
import org.jspecify.annotations.Nullable;

/**
 * Compile-time constant folding for the four ASDF/UIOP pathname primitives that a real
 * Quicklisp library evaluates at load time to build a namestring pointing at a bundled
 * data file (the seed case is uax-15's
 * {@code (asdf:system-source-directory (asdf:find-system 'uax-15 nil))} composed with a
 * {@code (make-pathname ...)} and merged via {@code uiop:merge-pathnames*}). The
 * interpreter has runtime companions for those calls in {@link PathnameOps} and
 * {@code LispEvaluator}; this pass gives the compile paths (JVM + both WASM backends) the
 * same reach, by reducing every call-shape whose arguments are ultimately literal strings
 * / symbols back to a literal namestring at inline time, before the compilers reject it
 * as an unknown call.
 *
 * <p>
 * The primitives handled are: {@link LispNames#MAKE_PATHNAME},
 * {@link LispNames#UIOP_MERGE_PATHNAMES_STAR}, {@link LispNames#ASDF_FIND_SYSTEM} and
 * {@link LispNames#ASDF_SYSTEM_SOURCE_DIRECTORY}. The pass also detects
 * {@code (defparameter *NAME* <folded string>)} at top level: the recorded literal feeds
 * later folds that reference the same {@code *NAME*} inside another primitive's argument
 * -- the {@code *data-directory*} + {@code "file.txt"} idiom.
 *
 * <p>
 * On top of the pathname fold, the pass rewrites
 * {@code (with-open-file (VAR <literal utf-8 path> [:external-format :UTF-8]) BODY...)}
 * as {@code (with-input-from-string (VAR <inlined file contents>) BODY...)} when the
 * literal path names a file that exists at compile time. This is what lets the WASM
 * backends load a system whose {@code with-open-file} reads a bundled data file: the
 * wasmtime sandbox has no host filesystem, so the contents are baked into the module
 * instead. The JVM benefits too (the compiled binary stops depending on the same absolute
 * path being present at run time). Large contents are chunked below the 65535 UTF-8 byte
 * per-string ceiling ({@code CONSTANT_Utf8}) and reassembled at runtime via
 * {@code (concatenate 'string CHUNK1 CHUNK2 ...)}, so a multi-megabyte data file
 * (uax-15's 1.9 MB {@code UnicodeData.txt}) fits under the JVM class-format cap.
 *
 * <p>
 * Scope: only pattern-recognized call shapes are rewritten. Any bare symbol reference
 * substituted for a folded {@code *NAME*} happens INSIDE a foldable primitive's argument
 * position, never as a general AST rewrite; a {@code (let ((*NAME* ...))
 * ...)} rebinding is therefore left intact -- its inner-scope value is never substituted.
 * A {@code (quote DATUM)} form is passed through untouched so quoted data structures are
 * never rewritten.
 */
final class CompileTimePathnameFolder {

	private CompileTimePathnameFolder() {
	}

	/**
	 * Folds every recognized pathname / file-bundling shape in {@code program}, using
	 * {@code systems} as the compile-time system registry (populated by
	 * {@link LoadInliner} from {@code asdf:defsystem}/{@code .asd} parses). Top-level
	 * {@code defparameter}/{@code defvar} of a folded literal string is recorded so later
	 * forms can substitute the reference.
	 * @param program the (already-inlined) program forms
	 * @param systems the compile-time system registry
	 * @return a copy of {@code program} with folded forms
	 */
	public static List<LispVal> fold(List<LispVal> program, Map<String, AsdfSystems.LispSystem> systems) {
		Map<String, LispVal> parameters = new HashMap<>();
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(foldForm(form, systems, parameters));
		}
		return out;
	}

	/**
	 * Walks {@code form}, rewriting the recognized shapes. Non-cons forms and cons forms
	 * whose operator is not one of the recognized shapes are recursed into so a primitive
	 * nested inside a larger expression still folds.
	 */
	private static LispVal foldForm(LispVal form, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		List<LispVal> items = cons.toList();
		if (items.isEmpty() || !(items.get(0) instanceof LispSymbol op)) {
			return recurseCons(items, systems, parameters);
		}
		String opName = op.name();
		// Quoted data is opaque: never recurse into a datum, otherwise we would
		// rewrite quoted list literals as if they were code.
		if (LispNames.QUOTE.equals(opName)) {
			return form;
		}
		if (LispNames.DEFPARAMETER.equals(opName) || LispNames.DEFVAR.equals(opName)) {
			return foldDefParam(items, systems, parameters);
		}
		if (LispNames.WITH_OPEN_FILE.equals(opName)) {
			return foldWithOpenFile(items, systems, parameters);
		}
		if (isFoldablePrimitiveHead(op)) {
			LispVal reduced = reduce(form, systems, parameters);
			if (reduced != null) {
				return reduced;
			}
		}
		return recurseCons(items, systems, parameters);
	}

	/**
	 * Handles {@code (defparameter NAME VALUE [DOC])} and {@code (defvar NAME [VALUE
	 * [DOC]])}: folds VALUE, records NAME to the folded value if it reduced to a literal
	 * string.
	 */
	private static LispVal foldDefParam(List<LispVal> items, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		if (items.size() < 2 || !(items.get(1) instanceof LispSymbol nameSym)) {
			return recurseCons(items, systems, parameters);
		}
		List<LispVal> out = new ArrayList<>(items.size());
		out.add(items.get(0));
		out.add(nameSym);
		if (items.size() >= 3) {
			LispVal valueExpr = items.get(2);
			LispVal reduced = reduce(valueExpr, systems, parameters);
			if (reduced instanceof LispString folded) {
				parameters.put(nameSym.name(), folded);
				out.add(folded);
			}
			else {
				out.add(foldForm(valueExpr, systems, parameters));
			}
			for (int i = 3; i < items.size(); i++) {
				out.add(foldForm(items.get(i), systems, parameters));
			}
		}
		return listToCons(out);
	}

	/**
	 * Rewrites {@code (with-open-file (VAR PATH [:external-format :UTF-8]) BODY...)} into
	 * {@code (with-input-from-string (VAR CONTENT) BODY...)} when PATH folds to a literal
	 * string that names a file existing on disk and the option set is bundleable. On any
	 * other shape, the with-open-file is passed through with PATH folded in place and the
	 * body recursively folded, so a downstream backend that DOES have filesystem access
	 * (the JVM) still sees a literal namestring.
	 */
	private static LispVal foldWithOpenFile(List<LispVal> items, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		if (items.size() < 2 || !(items.get(1) instanceof LispCons spec) || !spec.isProperList()) {
			return recurseCons(items, systems, parameters);
		}
		List<LispVal> specParts = spec.toList();
		if (specParts.size() < 2 || !(specParts.get(0) instanceof LispSymbol var)) {
			return recurseCons(items, systems, parameters);
		}
		LispVal pathExpr = specParts.get(1);
		LispVal reducedPath = reduce(pathExpr, systems, parameters);
		LispVal foldedPathExpr = reducedPath != null ? reducedPath : foldForm(pathExpr, systems, parameters);
		List<LispVal> options = specParts.subList(2, specParts.size());
		List<LispVal> body = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			body.add(foldForm(items.get(i), systems, parameters));
		}
		if (reducedPath instanceof LispString pathStr && supportsInputBundling(options)) {
			String contents = tryReadFile(pathStr.value());
			if (contents != null) {
				return buildWithInputFromString(var, contents, body);
			}
		}
		List<LispVal> newSpec = new ArrayList<>(specParts.size());
		newSpec.add(var);
		newSpec.add(foldedPathExpr);
		for (LispVal opt : options) {
			newSpec.add(foldForm(opt, systems, parameters));
		}
		List<LispVal> out = new ArrayList<>(items.size());
		out.add(items.get(0));
		out.add(listToCons(newSpec));
		out.addAll(body);
		return listToCons(out);
	}

	private static @Nullable String tryReadFile(String path) {
		try {
			return Files.readString(Path.of(path), StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Whether the option set only carries the input defaults ({@code :external-format
	 * :utf-8} or {@code :default}) that make a with-input-from-string substitution
	 * behaviorally equivalent. Any other option -- output direction, binary element type,
	 * unusual defaulting -- suppresses bundling and the with-open-file passes through.
	 */
	private static boolean supportsInputBundling(List<LispVal> options) {
		if ((options.size() & 1) != 0) {
			return false;
		}
		for (int i = 0; i < options.size(); i += 2) {
			if (!(options.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				return false;
			}
			LispVal value = options.get(i + 1);
			switch (key.name()) {
				case ":EXTERNAL-FORMAT" -> {
					if (!(value instanceof LispSymbol sym)
							|| !(":UTF-8".equals(sym.name()) || ":DEFAULT".equals(sym.name()))) {
						return false;
					}
				}
				case ":DIRECTION" -> {
					if (!(value instanceof LispSymbol sym) || !LispNames.INPUT_KEYWORD.equals(sym.name())) {
						return false;
					}
				}
				default -> {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Builds a {@code (with-input-from-string (VAR CONTENT-EXPR) BODY...)} form. When
	 * {@code contents} would exceed the 65535 UTF-8 byte per-string ceiling on the JVM,
	 * it is split into chunks reassembled at runtime as
	 * {@code (concatenate 'string CHUNK1 CHUNK2 ...)}.
	 */
	private static LispVal buildWithInputFromString(LispSymbol var, String contents, List<LispVal> body) {
		LispVal contentExpr = buildContentExpr(contents);
		List<LispVal> spec = new ArrayList<>();
		spec.add(var);
		spec.add(contentExpr);
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(LispNames.WITH_INPUT_FROM_STRING));
		out.add(listToCons(spec));
		out.addAll(body);
		return listToCons(out);
	}

	// A safety margin below the 65535 UTF-8 byte per-string cap (CONSTANT_Utf8). Text
	// input is almost pure ASCII in the seed case (Unicode data files), but a
	// non-ASCII line can expand up to 4 bytes per code point, so the split point is
	// chosen so 20k Java chars fit even at the worst UTF-16 -> UTF-8 expansion (~60k
	// bytes), well below the ceiling.
	private static final int CHUNK_CODEPOINT_LIMIT = 20000;

	private static LispVal buildContentExpr(String contents) {
		List<String> chunks = splitAtCodePointBoundary(contents);
		if (chunks.size() == 1) {
			return new LispString(chunks.get(0));
		}
		List<LispVal> concat = new ArrayList<>(chunks.size() + 2);
		concat.add(new LispSymbol(LispNames.CONCATENATE));
		concat.add(new LispCons(new LispSymbol(LispNames.QUOTE),
				new LispCons(new LispSymbol(LispNames.STRING), LispNil.INSTANCE)));
		for (String chunk : chunks) {
			concat.add(new LispString(chunk));
		}
		return listToCons(concat);
	}

	/**
	 * Splits {@code s} into slices whose length is at most {@link #CHUNK_CODEPOINT_LIMIT}
	 * chars, always ending on a code-point boundary so a surrogate pair is never cut in
	 * half.
	 */
	private static List<String> splitAtCodePointBoundary(String s) {
		List<String> parts = new ArrayList<>();
		int len = s.length();
		int start = 0;
		while (start < len) {
			int end = Math.min(start + CHUNK_CODEPOINT_LIMIT, len);
			if (end < len && Character.isHighSurrogate(s.charAt(end - 1))) {
				end--;
			}
			parts.add(s.substring(start, end));
			start = end;
		}
		if (parts.isEmpty()) {
			parts.add("");
		}
		return parts;
	}

	/**
	 * Whether {@code op} names one of the four foldable primitives. A generic call (like
	 * {@code funcall}, {@code let}) is not considered a fold target: those forms recurse
	 * through {@link #recurseCons} instead.
	 */
	private static boolean isFoldablePrimitiveHead(LispSymbol op) {
		String name = op.name();
		if (LispNames.MAKE_PATHNAME.equals(name)) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn == null) {
			return false;
		}
		if (LispNames.UIOP_PKG.equals(qn.pkg()) && LispNames.MERGE_PATHNAMES_STAR.equals(qn.member())) {
			return true;
		}
		if (LispNames.ASDF_PKG.equals(qn.pkg())
				&& (LispNames.FIND_SYSTEM.equals(qn.member()) || LispNames.SYSTEM_SOURCE_DIRECTORY.equals(qn.member())
						|| LispNames.SYSTEM_RELATIVE_PATHNAME.equals(qn.member()))) {
			return true;
		}
		return false;
	}

	/**
	 * Recurses into the children of a cons, folding each and rebuilding the list. If no
	 * child changed, the input list is still copied -- the cost is small next to the deep
	 * walk this pass does anyway.
	 */
	private static LispVal recurseCons(List<LispVal> items, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		List<LispVal> out = new ArrayList<>(items.size());
		for (LispVal item : items) {
			out.add(foldForm(item, systems, parameters));
		}
		return listToCons(out);
	}

	// -- expression reduction ------------------------------------------------

	/**
	 * Attempts to reduce {@code expr} to a concrete {@link LispVal} literal.
	 * <p>
	 * Recognized: self-evaluating literals (strings, numbers, {@code nil}, {@code t},
	 * keyword symbols), a top-level parameter reference (a bare symbol previously
	 * recorded by {@link #foldDefParam}), {@code (quote DATUM)}, {@code (list ARGS...)},
	 * {@code (make-pathname ...)}, {@code (uiop:merge-pathnames* ...)},
	 * {@code (asdf:find-system ...)}, {@code (asdf:system-source-directory ...)}.
	 * <p>
	 * Returns {@code null} for any other shape -- the caller then falls back to a generic
	 * recursion.
	 */
	private static @Nullable LispVal reduce(LispVal expr, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		if (expr instanceof LispString || expr instanceof LispInteger || expr instanceof LispDouble
				|| expr instanceof LispNil || expr instanceof LispTrue) {
			return expr;
		}
		if (expr instanceof LispSymbol sym) {
			return reduceSymbol(sym, parameters);
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.isEmpty() || !(items.get(0) instanceof LispSymbol op)) {
			return null;
		}
		String opName = op.name();
		List<LispVal> args = items.subList(1, items.size());
		if (LispNames.QUOTE.equals(opName)) {
			return args.size() == 1 ? args.get(0) : null;
		}
		if (LispNames.LIST.equals(opName)) {
			return reduceList(args, systems, parameters);
		}
		if (LispNames.MAKE_PATHNAME.equals(opName)) {
			return reduceMakePathname(args, systems, parameters);
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(opName);
		if (qn == null) {
			return null;
		}
		if (LispNames.UIOP_PKG.equals(qn.pkg()) && LispNames.MERGE_PATHNAMES_STAR.equals(qn.member())) {
			return reduceMergePathnames(args, systems, parameters);
		}
		if (LispNames.ASDF_PKG.equals(qn.pkg())) {
			if (LispNames.FIND_SYSTEM.equals(qn.member())) {
				return reduceFindSystem(args, systems, parameters);
			}
			if (LispNames.SYSTEM_SOURCE_DIRECTORY.equals(qn.member())) {
				return reduceSystemSourceDirectory(args, systems, parameters);
			}
			if (LispNames.SYSTEM_RELATIVE_PATHNAME.equals(qn.member())) {
				return reduceSystemRelativePathname(args, systems, parameters);
			}
		}
		return null;
	}

	private static @Nullable LispVal reduceSymbol(LispSymbol sym, Map<String, LispVal> parameters) {
		String name = sym.name();
		if ("NIL".equals(name)) {
			return LispNil.INSTANCE;
		}
		if ("T".equals(name)) {
			return LispTrue.INSTANCE;
		}
		if (sym.isKeyword()) {
			return sym;
		}
		return parameters.get(name);
	}

	private static @Nullable LispVal reduceList(List<LispVal> args, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		LispVal tail = LispNil.INSTANCE;
		List<LispVal> reduced = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			LispVal r = reduce(arg, systems, parameters);
			if (r == null) {
				return null;
			}
			reduced.add(r);
		}
		for (int i = reduced.size() - 1; i >= 0; i--) {
			tail = new LispCons(reduced.get(i), tail);
		}
		return tail;
	}

	private static @Nullable LispVal reduceMakePathname(List<LispVal> args, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		List<LispVal> reduced = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			LispVal r = reduce(arg, systems, parameters);
			if (r == null) {
				return null;
			}
			reduced.add(r);
		}
		try {
			return new LispString(PathnameOps.makePathname(reduced));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static @Nullable LispVal reduceMergePathnames(List<LispVal> args,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters) {
		if (args.isEmpty() || args.size() > 2) {
			return null;
		}
		LispVal specified = reduce(args.get(0), systems, parameters);
		if (!(specified instanceof LispString specifiedStr)) {
			return null;
		}
		String defaults = "";
		if (args.size() == 2) {
			LispVal defaultsVal = reduce(args.get(1), systems, parameters);
			if (defaultsVal instanceof LispString defaultsStr) {
				defaults = defaultsStr.value();
			}
			else if (defaultsVal instanceof LispNil) {
				defaults = "";
			}
			else {
				return null;
			}
		}
		return new LispString(PathnameOps.mergePathnames(specifiedStr.value(), defaults));
	}

	private static @Nullable LispVal reduceFindSystem(List<LispVal> args, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters) {
		if (args.isEmpty() || args.size() > 2) {
			return null;
		}
		String name = literalDesignator(args.get(0), parameters);
		if (name == null) {
			return null;
		}
		boolean errorP = true;
		if (args.size() == 2) {
			LispVal errorVal = reduce(args.get(1), systems, parameters);
			if (errorVal == null) {
				return null;
			}
			errorP = !(errorVal instanceof LispNil);
		}
		if (systems.containsKey(name)) {
			return new LispString(name);
		}
		return errorP ? null : LispNil.INSTANCE;
	}

	/**
	 * {@code (asdf:system-relative-pathname SYSTEM RELATIVE)} -> the merged namestring:
	 * the system's recorded base directory with {@code RELATIVE} merged onto it. The
	 * one-call form of source-directory + merge, and the shape a library uses to name a
	 * data file bundled next to its {@code .asd} (quri's effective-TLD list). Trailing
	 * {@code :type}/{@code :name} keywords are not reduced -- no caller passes them and
	 * guessing would silently build the wrong path.
	 */
	private static @Nullable LispVal reduceSystemRelativePathname(List<LispVal> args,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters) {
		if (args.size() != 2) {
			return null;
		}
		String name = literalDesignator(args.get(0), parameters);
		if (name == null) {
			return null;
		}
		AsdfSystems.LispSystem system = systems.get(name);
		if (system == null) {
			return null;
		}
		LispVal relative = reduce(args.get(1), systems, parameters);
		if (!(relative instanceof LispString relativeStr)) {
			return null;
		}
		String base = system.baseDir();
		if (base == null || base.isEmpty()) {
			base = "./";
		}
		return new LispString(PathnameOps.mergePathnames(relativeStr.value(), base.endsWith("/") ? base : base + "/"));
	}

	private static @Nullable LispVal reduceSystemSourceDirectory(List<LispVal> args,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters) {
		if (args.size() != 1) {
			return null;
		}
		LispVal reduced = reduce(args.get(0), systems, parameters);
		if (reduced == null) {
			return null;
		}
		String name;
		if (reduced instanceof LispString str) {
			name = str.value();
		}
		else if (reduced instanceof LispSymbol sym) {
			name = literalDesignator(sym, parameters);
		}
		else {
			return null;
		}
		if (name == null) {
			return null;
		}
		AsdfSystems.LispSystem system = systems.get(name);
		if (system == null) {
			return null;
		}
		String base = system.baseDir();
		if (base == null || base.isEmpty()) {
			return new LispString("./");
		}
		return new LispString(base.endsWith("/") ? base : base + "/");
	}

	/**
	 * Coerces a literal system-name designator ({@code "lib"}, {@code :lib},
	 * {@code 'lib}, {@code lib}) to the ASDF-canonical downcased name, matching
	 * {@link AsdfSystems#designator}. Returns {@code null} on a shape the compile path
	 * cannot resolve (a symbol previously bound to a non-literal value, a computed
	 * expression).
	 */
	private static @Nullable String literalDesignator(LispVal val, Map<String, LispVal> parameters) {
		if (val instanceof LispString str) {
			return normalizeDesignator(str.value());
		}
		if (val instanceof LispSymbol sym) {
			if (sym.isKeyword()) {
				return normalizeDesignator(sym.name().substring(1));
			}
			LispVal stored = parameters.get(sym.name());
			if (stored instanceof LispString storedStr) {
				return normalizeDesignator(storedStr.value());
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return normalizeDesignator(qn == null ? sym.name() : qn.member());
		}
		if (val instanceof LispCons quoted && quoted.car() instanceof LispSymbol quoteOp
				&& LispNames.QUOTE.equals(quoteOp.name()) && quoted.cdr() instanceof LispCons datumCell
				&& datumCell.car() instanceof LispSymbol datum) {
			return normalizeDesignator(datum.name());
		}
		return null;
	}

	private static String normalizeDesignator(String raw) {
		if (raw.startsWith("#:")) {
			raw = raw.substring(2);
		}
		else if (raw.startsWith(":")) {
			raw = raw.substring(1);
		}
		return raw.toLowerCase(java.util.Locale.ROOT);
	}

	private static LispVal listToCons(List<LispVal> items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			tail = new LispCons(items.get(i), tail);
		}
		return tail;
	}

}
