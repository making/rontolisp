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
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.eval.AsdfSystems;
import am.ik.rontolisp.eval.PathnameOps;
import am.ik.rontolisp.reader.LispReader;
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
 * {@code uiop:merge-pathnames*} and {@link LispNames#ASDF_SYSTEM_SOURCE_DIRECTORY} /
 * {@code asdf:system-relative-pathname}. {@code asdf:find-system} itself no longer folds
 * (at run time it answers a memoized system metaobject, {@code AsdfRuntimeLibrary}), but
 * a nested literal {@code (asdf:find-system 'lib nil)} in a fold's system-designator
 * position still reduces, so the uax-15 shape keeps folding. The pass also detects
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
		java.util.Set<String> writtenPaths = new java.util.HashSet<>();
		for (LispVal form : program) {
			collectWrittenPaths(form, writtenPaths);
		}
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(foldForm(form, systems, parameters, writtenPaths));
		}
		return out;
	}

	/**
	 * Collects the literal namestrings the program itself opens for OUTPUT. Bundling such
	 * a file's compile-time contents into a {@code with-input-from-string} would make the
	 * program read what was on disk when it was COMPILED instead of what it just wrote --
	 * a silent wrong answer, and the one shape where the input-bundling rewrite is not
	 * conservative (an append-then-read round trip is exactly it). Only LITERAL paths are
	 * collected: a computed output path cannot be matched against a literal input path
	 * anyway, and a program mixing the two is outside the rewrite's reach in the first
	 * place.
	 */
	private static void collectWrittenPaths(LispVal form, java.util.Set<String> writtenPaths) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return;
		}
		List<LispVal> items = cons.toList();
		if (!items.isEmpty() && items.get(0) instanceof LispSymbol op) {
			if (LispNames.QUOTE.equals(op.name())) {
				return;
			}
			if (LispNames.WITH_OPEN_FILE.equals(op.name()) && items.size() >= 2 && items.get(1) instanceof LispCons spec
					&& spec.isProperList()) {
				List<LispVal> specParts = spec.toList();
				String specPath = specParts.size() >= 2 ? PathnameOps.designatorNamestring(specParts.get(1)) : null;
				if (specPath != null && namesAnOutputDirection(specParts.subList(2, specParts.size()))) {
					writtenPaths.add(specPath);
				}
			}
			if (LispNames.OPEN.equals(op.name()) && items.size() >= 3
					&& PathnameOps.designatorNamestring(items.get(1)) instanceof String openPath
					&& namesAnOutputDirection(items.subList(2, items.size()))) {
				writtenPaths.add(openPath);
			}
		}
		for (LispVal item : items) {
			collectWrittenPaths(item, writtenPaths);
		}
	}

	/**
	 * Whether an {@code open}/{@code with-open-file} option or positional tail selects an
	 * output direction -- {@code :output} / {@code :append} as a bare positional or as
	 * the value of {@code :direction}. Deliberately loose: a false positive only costs
	 * the bundling of one file.
	 */
	private static boolean namesAnOutputDirection(List<LispVal> tail) {
		for (LispVal item : tail) {
			if (item instanceof LispSymbol sym
					&& (LispNames.OUTPUT_KEYWORD.equals(sym.name()) || LispNames.APPEND_KEYWORD.equals(sym.name()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Walks {@code form}, rewriting the recognized shapes. Non-cons forms and cons forms
	 * whose operator is not one of the recognized shapes are recursed into so a primitive
	 * nested inside a larger expression still folds.
	 */
	private static LispVal foldForm(LispVal form, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters, java.util.Set<String> writtenPaths) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return form;
		}
		List<LispVal> items = cons.toList();
		if (items.isEmpty() || !(items.get(0) instanceof LispSymbol op)) {
			return recurseCons(cons, items, systems, parameters, writtenPaths);
		}
		String opName = op.name();
		// Quoted data is opaque: never recurse into a datum, otherwise we would
		// rewrite quoted list literals as if they were code.
		if (LispNames.QUOTE.equals(opName)) {
			return form;
		}
		if (LispNames.DEFPARAMETER.equals(opName) || LispNames.DEFVAR.equals(opName)) {
			return foldDefParam(cons, items, systems, parameters, writtenPaths);
		}
		if (LispNames.WITH_OPEN_FILE.equals(opName)) {
			return foldWithOpenFile(cons, items, systems, parameters, writtenPaths);
		}
		if (isFoldablePrimitiveHead(op)) {
			LispVal reduced = reduce(form, systems, parameters, writtenPaths);
			if (reduced != null) {
				return reduced;
			}
		}
		return recurseCons(cons, items, systems, parameters, writtenPaths);
	}

	/**
	 * Handles {@code (defparameter NAME VALUE [DOC])} and {@code (defvar NAME [VALUE
	 * [DOC]])}: folds VALUE, records NAME to the folded value if it reduced to a literal
	 * string.
	 */
	private static LispVal foldDefParam(LispCons original, List<LispVal> items,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		if (items.size() < 2 || !(items.get(1) instanceof LispSymbol nameSym)) {
			return recurseCons(original, items, systems, parameters, writtenPaths);
		}
		List<LispVal> out = new ArrayList<>(items.size());
		out.add(items.get(0));
		out.add(nameSym);
		boolean changed = false;
		if (items.size() >= 3) {
			LispVal valueExpr = items.get(2);
			LispVal reduced = reduce(valueExpr, systems, parameters, writtenPaths);
			if (reduced instanceof LispString
					|| (reduced instanceof LispInstance inst && inst.layout().kind() == LispLayout.Kind.PATHNAME)) {
				parameters.put(nameSym.name(), reduced);
				out.add(reduced);
				changed |= reduced != valueExpr;
			}
			else {
				LispVal foldedValue = foldForm(valueExpr, systems, parameters, writtenPaths);
				out.add(foldedValue);
				changed |= foldedValue != valueExpr;
			}
			for (int i = 3; i < items.size(); i++) {
				LispVal foldedDoc = foldForm(items.get(i), systems, parameters, writtenPaths);
				out.add(foldedDoc);
				changed |= foldedDoc != items.get(i);
			}
		}
		// Unchanged means unchanged, cons identity included -- see recurseCons.
		return changed ? listToCons(out) : original;
	}

	/**
	 * Rewrites {@code (with-open-file (VAR PATH [:external-format :UTF-8]) BODY...)} into
	 * {@code (with-input-from-string (VAR CONTENT) BODY...)} when PATH folds to a literal
	 * string that names a file existing on disk and the option set is bundleable. On any
	 * other shape, the with-open-file is passed through with PATH folded in place and the
	 * body recursively folded, so a downstream backend that DOES have filesystem access
	 * (the JVM) still sees a literal namestring.
	 */
	private static LispVal foldWithOpenFile(LispCons original, List<LispVal> items,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		if (items.size() < 2 || !(items.get(1) instanceof LispCons spec) || !spec.isProperList()) {
			return recurseCons(original, items, systems, parameters, writtenPaths);
		}
		List<LispVal> specParts = spec.toList();
		if (specParts.size() < 2 || !(specParts.get(0) instanceof LispSymbol var)) {
			return recurseCons(original, items, systems, parameters, writtenPaths);
		}
		LispVal pathExpr = specParts.get(1);
		LispVal reducedPath = reduce(pathExpr, systems, parameters, writtenPaths);
		LispVal foldedPathExpr = reducedPath != null ? reducedPath
				: foldForm(pathExpr, systems, parameters, writtenPaths);
		boolean changed = foldedPathExpr != pathExpr;
		List<LispVal> options = specParts.subList(2, specParts.size());
		List<LispVal> body = new ArrayList<>();
		for (int i = 2; i < items.size(); i++) {
			LispVal foldedBody = foldForm(items.get(i), systems, parameters, writtenPaths);
			changed |= foldedBody != items.get(i);
			body.add(foldedBody);
		}
		String reducedPathNs = reducedPath == null ? null : PathnameOps.designatorNamestring(reducedPath);
		if (reducedPathNs != null && supportsInputBundling(options) && !writtenPaths.contains(reducedPathNs)) {
			String contents = tryReadFile(reducedPathNs);
			if (contents != null) {
				return buildWithInputFromString(var, contents, body);
			}
		}
		List<LispVal> newSpec = new ArrayList<>(specParts.size());
		newSpec.add(var);
		newSpec.add(foldedPathExpr);
		for (LispVal opt : options) {
			LispVal foldedOpt = foldForm(opt, systems, parameters, writtenPaths);
			changed |= foldedOpt != opt;
			newSpec.add(foldedOpt);
		}
		// Unchanged means unchanged, cons identity included -- see recurseCons.
		if (!changed) {
			return original;
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
		if (UiopExports.denotes(qn.pkg(), qn.member(), LispNames.MERGE_PATHNAMES_STAR)
				|| UiopExports.denotes(qn.pkg(), qn.member(), LispNames.SUBPATHNAME)) {
			return true;
		}
		if (LispNames.ASDF_PKG.equals(qn.pkg()) && (LispNames.SYSTEM_SOURCE_DIRECTORY.equals(qn.member())
				|| LispNames.COMPONENT_PATHNAME.equals(qn.member())
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
	/**
	 * Folds every element of a form and rebuilds it -- but returns {@code original}
	 * untouched when nothing folded. A pass that changes nothing must hand back what it
	 * was given: {@link am.ik.rontolisp.SourceProvenance} is keyed by cons IDENTITY, so a
	 * gratuitous rebuild silently erases the source position of every form that flows
	 * through it (and allocates a copy of the whole program for nothing). Almost every
	 * program reaches this folder with nothing to fold.
	 */
	private static LispVal recurseCons(LispCons original, List<LispVal> items,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		List<LispVal> out = new ArrayList<>(items.size());
		boolean changed = false;
		for (LispVal item : items) {
			LispVal folded = foldForm(item, systems, parameters, writtenPaths);
			changed |= folded != item;
			out.add(folded);
		}
		return changed ? listToCons(out) : original;
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
	 *
	 * <p>
	 * Also recognizes {@code (eval (read-from-string "<literal>"))}: local-time asks ASDF
	 * for its bundled data directory at load time through exactly that shape so it can be
	 * skipped when ASDF is absent. When the string is a literal, we read it and reduce
	 * the resulting form, which lets nested {@code asdf:component-pathname} /
	 * {@code asdf:find-system} calls fold exactly as they do unwrapped.
	 * <p>
	 * Returns {@code null} for any other shape -- the caller then falls back to a generic
	 * recursion.
	 */
	private static @Nullable LispVal reduce(LispVal expr, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters, java.util.Set<String> writtenPaths) {
		if (expr instanceof LispString || expr instanceof LispInteger || expr instanceof LispDouble
				|| expr instanceof LispNil || expr instanceof LispTrue) {
			return expr;
		}
		if (expr instanceof LispInstance inst && inst.layout().kind() == LispLayout.Kind.PATHNAME) {
			// A #P"..." literal (or an already-folded pathname) is its own reduction.
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
			return reduceList(args, systems, parameters, writtenPaths);
		}
		if (LispNames.MAKE_PATHNAME.equals(opName)) {
			return reduceMakePathname(args, systems, parameters, writtenPaths);
		}
		if (LispNames.EVAL.equals(opName)) {
			LispVal unwrapped = reduceEvalReadFromString(args, systems, parameters, writtenPaths);
			if (unwrapped != null) {
				return unwrapped;
			}
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(opName);
		if (qn == null) {
			return null;
		}
		if (UiopExports.denotes(qn.pkg(), qn.member(), LispNames.MERGE_PATHNAMES_STAR)) {
			return reduceMergePathnames(args, systems, parameters, writtenPaths);
		}
		if (UiopExports.denotes(qn.pkg(), qn.member(), LispNames.SUBPATHNAME)) {
			return reduceSubpathname(args, systems, parameters, writtenPaths);
		}
		if (LispNames.ASDF_PKG.equals(qn.pkg())) {
			if (LispNames.SYSTEM_SOURCE_DIRECTORY.equals(qn.member())
					|| LispNames.COMPONENT_PATHNAME.equals(qn.member())) {
				// component-pathname of a SYSTEM is its source directory, and a system is
				// the only component object rontolisp materializes.
				return reduceSystemSourceDirectory(args, systems, parameters, writtenPaths);
			}
			if (LispNames.SYSTEM_RELATIVE_PATHNAME.equals(qn.member())) {
				return reduceSystemRelativePathname(args, systems, parameters, writtenPaths);
			}
		}
		return null;
	}

	/**
	 * {@code (eval (read-from-string "..."))} over a literal string: read the string at
	 * fold time and reduce the resulting form. The only shape reduced is the one where
	 * read-from-string has exactly one argument (the literal string); any other use of
	 * eval is left untouched.
	 */
	private static @Nullable LispVal reduceEvalReadFromString(List<LispVal> evalArgs,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		if (evalArgs.size() != 1) {
			return null;
		}
		LispVal inner = reduce(evalArgs.get(0), systems, parameters, writtenPaths);
		if (!(inner instanceof LispCons rfs) || !rfs.isProperList()) {
			return null;
		}
		List<LispVal> rfsItems = rfs.toList();
		if (rfsItems.size() != 2 || !(rfsItems.get(0) instanceof LispSymbol rfsOp)
				|| !LispNames.READ_FROM_STRING.equals(rfsOp.name()) || !(rfsItems.get(1) instanceof LispString text)) {
			return null;
		}
		try {
			LispVal parsed = LispReader.readFromString(text.value());
			return reduce(parsed, systems, parameters, writtenPaths);
		}
		catch (RuntimeException ex) {
			return null;
		}
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
			Map<String, LispVal> parameters, java.util.Set<String> writtenPaths) {
		LispVal tail = LispNil.INSTANCE;
		List<LispVal> reduced = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			LispVal r = reduce(arg, systems, parameters, writtenPaths);
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
			Map<String, LispVal> parameters, java.util.Set<String> writtenPaths) {
		List<LispVal> reduced = new ArrayList<>(args.size());
		for (LispVal arg : args) {
			LispVal r = reduce(arg, systems, parameters, writtenPaths);
			if (r == null) {
				return null;
			}
			reduced.add(r);
		}
		try {
			// The value make-pathname answers at run time is a pathname VALUE, so the
			// fold produces the same -- PathnameOps.makePathname
			// itself unwraps a pathname :defaults argument.
			return PathnameOps.pathnameValue(PathnameOps.makePathname(reduced));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static @Nullable LispVal reduceMergePathnames(List<LispVal> args,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		if (args.isEmpty() || args.size() > 2) {
			return null;
		}
		LispVal specified = reduce(args.get(0), systems, parameters, writtenPaths);
		String specifiedNs = specified == null ? null : PathnameOps.designatorNamestring(specified);
		if (specifiedNs == null) {
			return null;
		}
		String defaults = "";
		if (args.size() == 2) {
			LispVal defaultsVal = reduce(args.get(1), systems, parameters, writtenPaths);
			String defaultsNs = defaultsVal == null ? null : PathnameOps.designatorNamestring(defaultsVal);
			if (defaultsNs != null) {
				defaults = defaultsNs;
			}
			else if (defaultsVal instanceof LispNil) {
				defaults = "";
			}
			else {
				return null;
			}
		}
		// Real uiop:merge-pathnames* answers a pathname; the interpreter's Java twin
		// wraps too, so the fold and the runtime agree.
		return PathnameOps.pathnameValue(PathnameOps.mergePathnames(specifiedNs, defaults));
	}

	/**
	 * {@code (uiop:subpathname BASE SUB [:type TYPE])} over literals -> the pathname
	 * literal the {@code uiop-pathname.lisp} definition answers at run time: an absolute
	 * pathname VALUE passes through, a relative namestring is normalized ({@code ""} and
	 * {@code "."} components dropped, {@code TYPE} appended to the last component) and
	 * appended under BASE's directory. An absolute STRING sub is declined -- at run time
	 * that arm is a {@code :want-relative} error, and a fold must never fold an error
	 * away.
	 */
	private static @Nullable LispVal reduceSubpathname(List<LispVal> args, Map<String, AsdfSystems.LispSystem> systems,
			Map<String, LispVal> parameters, java.util.Set<String> writtenPaths) {
		if (args.size() != 2 && args.size() != 4) {
			return null;
		}
		String type = null;
		if (args.size() == 4) {
			if (!(args.get(2) instanceof LispSymbol key) || !":TYPE".equals(key.name())) {
				return null;
			}
			LispVal typeVal = reduce(args.get(3), systems, parameters, writtenPaths);
			if (typeVal instanceof LispString typeStr) {
				type = typeStr.value();
			}
			else if (!(typeVal instanceof LispNil)) {
				return null;
			}
		}
		LispVal base = reduce(args.get(0), systems, parameters, writtenPaths);
		String baseNs = base == null ? null : PathnameOps.designatorNamestring(base);
		if (baseNs == null) {
			return null;
		}
		LispVal sub = reduce(args.get(1), systems, parameters, writtenPaths);
		if (sub instanceof LispInstance inst && inst.layout().kind() == LispLayout.Kind.PATHNAME) {
			String subNs = PathnameOps.designatorNamestring(sub);
			if (subNs != null && subNs.startsWith("/")) {
				// An absolute pathname VALUE passes through unchanged.
				return sub;
			}
		}
		String subNs = sub == null ? null : PathnameOps.designatorNamestring(sub);
		if (subNs == null || subNs.startsWith("/")) {
			return null;
		}
		int slash = baseNs.lastIndexOf('/');
		String baseDir = slash < 0 ? "" : baseNs.substring(0, slash + 1);
		boolean directory = subNs.isEmpty() || subNs.endsWith("/");
		List<String> components = new ArrayList<>();
		if (!subNs.isEmpty() && subNs.indexOf('/') < 0) {
			// The bare file-namestring fast path: no component filtering, exactly like
			// split-unix-namestring-directory-components (so "." stays a name).
			components.add(subNs);
		}
		else {
			for (String component : subNs.split("/")) {
				if (!component.isEmpty() && !".".equals(component)) {
					components.add(component);
				}
			}
		}
		StringBuilder normalized = new StringBuilder();
		for (int i = 0; i < components.size(); i++) {
			String component = components.get(i);
			boolean last = i == components.size() - 1;
			if (last && !directory && type != null) {
				component = component + "." + type;
			}
			normalized.append(component);
			if (!last || directory) {
				normalized.append('/');
			}
		}
		return PathnameOps.pathnameValue(baseDir + normalized);
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
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		if (args.size() != 2) {
			return null;
		}
		String name = systemDesignator(args.get(0), parameters);
		if (name == null) {
			return null;
		}
		AsdfSystems.LispSystem system = systems.get(name);
		if (system == null) {
			return null;
		}
		LispVal relative = reduce(args.get(1), systems, parameters, writtenPaths);
		String relativeNs = relative == null ? null : PathnameOps.designatorNamestring(relative);
		if (relativeNs == null) {
			return null;
		}
		String base = system.baseDir();
		if (base == null || base.isEmpty()) {
			base = "./";
		}
		return new LispString(PathnameOps.mergePathnames(relativeNs, base.endsWith("/") ? base : base + "/"));
	}

	private static @Nullable LispVal reduceSystemSourceDirectory(List<LispVal> args,
			Map<String, AsdfSystems.LispSystem> systems, Map<String, LispVal> parameters,
			java.util.Set<String> writtenPaths) {
		if (args.size() != 1) {
			return null;
		}
		String name = systemDesignator(args.get(0), parameters);
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
	 * A literal system designator in a fold's SYSTEM argument position: a plain literal
	 * ({@link #literalDesignator}) or a nested literal
	 * {@code (asdf:find-system NAME [ERROR-P])} call. find-system itself no longer folds
	 * -- at run time it answers a system metaobject ({@code AsdfRuntimeLibrary}) -- but
	 * the designator-position unwrap keeps the uax-15 seed shape
	 * {@code (asdf:system-source-directory (asdf:find-system 'lib nil))} folding to the
	 * same literal it always did.
	 */
	private static @Nullable String systemDesignator(LispVal arg, Map<String, LispVal> parameters) {
		String literal = literalDesignator(arg, parameters);
		if (literal != null) {
			return literal;
		}
		if (!(arg instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return null;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		if (qn == null || !LispNames.ASDF_PKG.equals(qn.pkg()) || !LispNames.FIND_SYSTEM.equals(qn.member())) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.size() < 2 || items.size() > 3) {
			return null;
		}
		return literalDesignator(items.get(1), parameters);
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
