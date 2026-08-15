package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
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
 * path), {@code :serial} (each component implicitly depends on the previous one),
 * {@code :pathname} (a path prefix for every component), and {@code :components} with
 * {@code (:file "name" [:depends-on (...)])}, {@code (:module "dir" :components (...))}
 * (a path prefix) and {@code (:static-file "name")} (ignored) entries.
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
	 * @param features the feature names this system holds beyond the loader's own set:
	 * those its {@code :rontolisp-features} option declares, plus those the enclosing
	 * {@code .asd} really pushed onto {@code *features*} from an {@code eval-when} ahead
	 * of the form ({@code AsdfSystems.collectFeaturePushes}) -- one mechanism, two
	 * spellings. They hold while THIS system's own clauses are parsed and while its
	 * component files are read, and each loader widens its own base set with them
	 * ({@code Features.with}) -- so the backend feature stays the loader's, and a
	 * dependency (parsed from its own {@code .asd}) never inherits them
	 * @param packageInferredDir the directory a SUB-SYSTEM name resolves against when
	 * this system is a {@code :class :package-inferred-system} ({@code ""} when the
	 * system declares no {@code :pathname}, so sub-systems sit beside the {@code .asd}),
	 * or {@code null} when it is an ordinary system. Non-null is the whole marker for the
	 * class: such a system lists no {@code :components} and its graph is derived from the
	 * component files' own {@code defpackage} forms
	 * ({@link #inferPackageInferredSystems})
	 */
	public record LispSystem(String name, List<String> dependsOn, List<String> files, String baseDir,
			List<String> features, @Nullable String packageInferredDir) {
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
	 * If {@code form} is an {@code (asdf:load-system NAME [:option value]...)} call,
	 * returns the literal system name; otherwise returns {@code null}. A
	 * {@code load-system} whose argument is not a literal designator is a hard error: the
	 * compile path cannot evaluate it (the interpreter's runtime function accepts
	 * computed names instead). Keyword options are accepted and IGNORED -- see
	 * {@link #checkIgnoredLoadOptions}.
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
		if (items.size() < 2) {
			throw new IllegalStateException(
					LispNames.ASDF_LOAD_SYSTEM + " expects exactly one system name: " + form.print());
		}
		checkIgnoredLoadOptions(LispNames.ASDF_LOAD_SYSTEM, items.subList(2, items.size()));
		return designator(LispNames.ASDF_LOAD_SYSTEM, items.get(1));
	}

	/**
	 * Validates the trailing keyword options of an {@code asdf:load-system} /
	 * {@code ql:quickload} call, which are accepted and then IGNORED: there is no
	 * {@code operate} machinery for {@code :force}/{@code :verbose} to drive and loading
	 * a system twice is already a no-op. Tolerating them is not cosmetic -- a library
	 * that loads a system at RUN time spells the call that way (lack's
	 * {@code find-package-or-load} passes {@code :verbose nil}), so rejecting the option
	 * would make the library unloadable over a clause that has no effect either way. The
	 * shape is still checked: the options must be {@code :keyword value} pairs, so a
	 * mistyped second system name is an error rather than a silent no-op.
	 * @param context the operator name for error messages
	 * @param options the argument forms/values after the system name
	 */
	public static void checkIgnoredLoadOptions(String context, List<LispVal> options) {
		if (options.size() % 2 != 0) {
			throw new IllegalStateException(context + " expects :option value pairs after the system name");
		}
		for (int i = 0; i < options.size(); i += 2) {
			if (!(options.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new IllegalStateException(
						context + " expects a keyword option after the system name, got " + options.get(i).print());
			}
		}
	}

	/**
	 * Parses the source text of a {@code .asd} file as plain data: {@code defsystem}
	 * forms (any package spelling) become {@link LispSystem}s whose component files
	 * resolve against the {@code .asd} file's directory, {@code in-package} and
	 * {@code defpackage} forms are skipped (the file is never evaluated, so the
	 * system-definition package header idiom does not matter), a top-level
	 * {@code defparameter} of a pure literal/conditional value is evaluated into a
	 * parse-time data environment (the cl-postgres {@code *string-file*} idiom), a
	 * top-level {@code eval-when} announcing features with {@code pushnew} declares them
	 * for the systems defined after it ({@link #collectFeaturePushes}), a
	 * {@code (defmethod perform ...)} hook is tolerated and ignored
	 * ({@link #checkToleratedPerformMethod}), a doc-file component-class {@code defclass}
	 * declares an ordering-only component type ({@link #collectDocFileClass}), and any
	 * other form is a hard error naming the file. A {@code #.} read-time-eval datum
	 * (wrapped in a {@code %read-eval} marker by the tolerant reader) is resolved against
	 * that environment WHERE ITS VALUE IS CONSUMED ({@link #resolveReadEval}); a
	 * top-level one is ignored (the ASDF-version-guard idiom). {@code #+}/{@code #-}
	 * conditionals are evaluated against {@code features}.
	 * @param source the {@code .asd} source text
	 * @param asdPath the resolved path of the {@code .asd} file (for the base directory
	 * and error messages)
	 * @param features the active reader features
	 * @return the systems defined by the file
	 */
	public static List<LispSystem> parseAsdSource(String source, String asdPath, Features features) {
		return parseAsdSource(source, asdPath, features, new HashMap<>());
	}

	/**
	 * Parses a {@code .asd} file ({@link #parseAsdSource(String, String, Features)}),
	 * merging the file's {@code register-system-packages} declarations into
	 * {@code systemPackages}: the loader-wide "package P lives in system S" map a
	 * package-inferred system consults when it turns a component file's
	 * {@code defpackage} dependency into a system name
	 * ({@link #inferPackageInferredSystems}). ningle.asd's three lines are load-bearing
	 * -- without them {@code app.lisp}'s {@code (:import-from #:lack.request ...)} asks
	 * for a system called {@code lack.request}, and no such {@code .asd} exists.
	 * @param source the {@code .asd} source text
	 * @param asdPath the resolved path of the {@code .asd} file
	 * @param features the active reader features
	 * @param systemPackages the loader's package-to-system map, extended in place
	 * @return the systems defined by the file
	 */
	public static List<LispSystem> parseAsdSource(String source, String asdPath, Features features,
			Map<String, String> systemPackages) {
		String baseDir = SourceLoader.parentDir(asdPath);
		List<LispSystem> systems = new ArrayList<>();
		Map<String, LispVal> parameters = new HashMap<>();
		// Features the file pushes onto *features* ahead of a defsystem, accumulated in
		// file order and merged into the declared features of every LATER system (see
		// collectFeaturePushes).
		List<String> pushedFeatures = new ArrayList<>();
		// Component-class names a tolerated doc-file defclass declared, in file order
		// like the feature pushes: they reach only the systems defined after them.
		Set<String> docComponentTypes = new HashSet<>();
		for (LispVal form : LispReader.readAllSkippingReadEval(source, features, asdPath)) {
			if (isReadEvalMarker(form) || isUnreadableReadEvalMarker(form)) {
				// A top-level #. form (an ASDF version guard) has side effects the data
				// parse cannot perform; ignore it, re-lexable or not.
				continue;
			}
			if (operatorMemberIs(form, LispNames.IN_PACKAGE) || operatorMemberIs(form, LispNames.DEFPACKAGE)) {
				continue;
			}
			if (operatorMemberIs(form, LispNames.REGISTER_SYSTEM_PACKAGES)) {
				collectSystemPackages(form, systemPackages, asdPath);
				continue;
			}
			if (operatorMemberIs(form, LispNames.DEFPARAMETER)) {
				defineParameter(form, parameters, asdPath);
				continue;
			}
			if (operatorMemberIs(form, LispNames.EVAL_WHEN) || operatorMemberIs(form, LispNames.PUSHNEW)
					|| operatorMemberIs(form, LispNames.PUSH)) {
				collectFeaturePushes(form, true, features, pushedFeatures, asdPath);
				continue;
			}
			if (operatorMemberIs(form, LispNames.DEFMETHOD)) {
				checkToleratedPerformMethod(form, asdPath);
				continue;
			}
			if (operatorMemberIs(form, LispNames.DEFCLASS)) {
				collectDocFileClass(form, docComponentTypes, asdPath);
				continue;
			}
			if (operatorMemberIs(form, LispNames.DEFSYSTEM)) {
				systems.add(parseDefsystem(form, baseDir, features, pushedFeatures, docComponentTypes,
						new AsdContext(asdPath, parameters)));
				continue;
			}
			throw new IllegalStateException(asdPath + ": unsupported form in .asd file (only " + LispNames.DEFSYSTEM
					+ ", " + LispNames.DEFPACKAGE + ", " + LispNames.IN_PACKAGE + ", " + LispNames.DEFPARAMETER + ", "
					+ LispNames.REGISTER_SYSTEM_PACKAGES + ", a " + LispNames.EVAL_WHEN + "/" + LispNames.PUSHNEW
					+ " feature announcement, a (" + LispNames.DEFMETHOD + " PERFORM ...) hook and a doc-file "
					+ LispNames.DEFCLASS + " are recognized): " + form.print());
		}
		return systems;
	}

	/**
	 * Evaluates a top-level {@code (defparameter NAME VALUE [DOC])} in a {@code .asd}
	 * file into the parse-time data environment. The value must be pure data the mini
	 * evaluator supports ({@link #evalDataForm}); anything else is a hard error naming
	 * the file, like any other unsupported {@code .asd} form.
	 */
	private static void defineParameter(LispVal form, Map<String, LispVal> parameters, String asdPath) {
		List<LispVal> items = ((LispCons) form).toList();
		if ((items.size() != 3 && items.size() != 4) || !(items.get(1) instanceof LispSymbol nameSym)) {
			throw new IllegalStateException(asdPath + ": " + LispNames.DEFPARAMETER
					+ " in a .asd file expects (defparameter NAME VALUE): " + form.print());
		}
		try {
			parameters.put(symbolName(nameSym), evalDataForm(items.get(2), parameters));
		}
		catch (IllegalStateException ex) {
			throw new IllegalStateException(asdPath + ": " + LispNames.DEFPARAMETER + " " + nameSym.name() + ": "
					+ ex.getMessage() + " (a .asd defparameter value must be pure data: literals, quote, if/or/and/not"
					+ " over earlier defparameters)");
		}
	}

	/**
	 * Records a top-level {@code (register-system-packages SYSTEM PACKAGES)} into the
	 * loader's package-to-system map. Real ASDF keeps one global map and consults it from
	 * two places: a {@code find-package} miss autoloads the system that owns the package,
	 * and a package-inferred system translates a component file's {@code defpackage}
	 * dependency into a system name. Only the second reaches here (a package is otherwise
	 * located by its own {@code defpackage} and nicknames), and it is the reason the form
	 * stopped being skipped.
	 * <p>
	 * Both arguments are read as DATA, so the {@code '(#:a #:b)} the form is always
	 * written with is unwrapped as a quoted list; a bare designator counts as a
	 * one-element list, matching ASDF's own {@code ensure-list}. The map is keyed by the
	 * DOWNCASED package name, the spelling {@link #packageDesignatorName} produces on the
	 * lookup side, so a keyword, a {@code #:} designator and a string all meet.
	 */
	private static void collectSystemPackages(LispVal form, Map<String, String> systemPackages, String asdPath) {
		List<LispVal> items = ((LispCons) form).toList();
		if (items.size() != 3) {
			throw new IllegalStateException(asdPath + ": " + LispNames.REGISTER_SYSTEM_PACKAGES
					+ " expects (register-system-packages SYSTEM PACKAGES): " + form.print());
		}
		String system = designator(LispNames.REGISTER_SYSTEM_PACKAGES, items.get(1));
		LispVal packages = items.get(2);
		if (packages instanceof LispCons quoted && quoted.car() instanceof LispSymbol quoteOp
				&& LispNames.QUOTE.equals(quoteOp.name()) && quoted.cdr() instanceof LispCons datumCell) {
			packages = datumCell.car();
		}
		List<LispVal> names = packages instanceof LispCons list && list.isProperList() ? list.toList()
				: List.of(packages);
		for (LispVal packageName : names) {
			systemPackages.put(packageDesignatorName(LispNames.REGISTER_SYSTEM_PACKAGES, packageName), system);
		}
	}

	/**
	 * Checks a top-level {@code defmethod} in a {@code .asd} file: only a
	 * {@code (defmethod PERFORM ...)} hook is tolerated (and then IGNORED whole -- there
	 * is no {@code operate} machinery for it to run on), any other method name stays a
	 * hard error. Two upstream shapes drive the tolerance: iterate.asd's test-op wiring
	 * and esrap.asd's {@code perform :after} on {@code load-op}, which pushes six
	 * {@code :esrap.*} capability features and {@code (provide :esrap)}. Ignoring the
	 * esrap pushes is deliberate, not an oversight: nothing reads them -- grep of the
	 * whole cached dist for {@code #+esrap.}/{@code #-esrap.} and of esrap's own sources
	 * for any {@code esrap.} feature reference found zero hits (2026-08-03). If a future
	 * esrap or a downstream starts reading one, fold the pushed keywords into the
	 * system's features instead (the {@code :rontolisp-features} channel,
	 * {@code collectFeaturePushes}); a load-time push could never reach a reader
	 * conditional anyway ({@code .todo/181}).
	 */
	private static void checkToleratedPerformMethod(LispVal form, String asdPath) {
		List<LispVal> items = ((LispCons) form).toList();
		if (items.size() >= 2 && items.get(1) instanceof LispSymbol name && "PERFORM".equals(memberName(name))) {
			return;
		}
		throw new IllegalStateException(asdPath + ": only a (" + LispNames.DEFMETHOD
				+ " PERFORM ...) hook is tolerated as a top-level method in a .asd file: " + form.print());
	}

	/**
	 * Collects a tolerated top-level {@code defclass} in a {@code .asd} file: only a
	 * DOC-FILE component class is accepted -- every superclass must be ASDF's
	 * {@code doc-file} or a doc-file class this file declared earlier -- and the declared
	 * name is recorded so components of that type parse as ordering-only entries (like
	 * {@code :static-file}). chipz.asd is the driving shape: {@code (defclass txt-file
	 * (doc-file) ...)} + {@code (:txt-file "chipz-doc")} components. Any other defclass
	 * (a {@code cl-source-file} subclass changes how sources LOAD, which the data-only
	 * parse cannot honor) stays a hard error.
	 */
	private static void collectDocFileClass(LispVal form, Set<String> docComponentTypes, String asdPath) {
		List<LispVal> items = ((LispCons) form).toList();
		if (items.size() >= 3 && items.get(1) instanceof LispSymbol name && items.get(2) instanceof LispCons supers
				&& supers.isProperList()) {
			boolean allDoc = true;
			for (LispVal superClass : supers.toList()) {
				if (!(superClass instanceof LispSymbol superSym) || !(DOC_FILE.equals(memberName(superSym))
						|| docComponentTypes.contains(memberName(superSym)))) {
					allDoc = false;
					break;
				}
			}
			if (allDoc) {
				docComponentTypes.add(memberName(name));
				return;
			}
		}
		throw new IllegalStateException(asdPath + ": only a doc-file component class is tolerated as a top-level "
				+ LispNames.DEFCLASS + " in a .asd file: " + form.print());
	}

	/** ASDF's own documentation component classes, valid without a local defclass. */
	private static final String DOC_FILE = "DOC-FILE";

	private static final String HTML_FILE = "HTML-FILE";

	/**
	 * The UPPERCASE member name of a symbol, package qualifier and {@code #:} stripped --
	 * the spelling a component-type keyword ({@code :txt-file} -> {@code TXT-FILE})
	 * matches a defclass name against.
	 */
	private static String memberName(LispSymbol sym) {
		String name = sym.name();
		if (name.startsWith("#:")) {
			name = name.substring(2);
		}
		else if (sym.isKeyword()) {
			name = name.substring(1);
		}
		else {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
			name = qn == null ? name : qn.member();
		}
		return name.toUpperCase(java.util.Locale.ROOT);
	}

	/**
	 * Collects the features a top-level {@code (eval-when (SITUATION...) (pushnew :F
	 * *features*) ...)} -- or a bare top-level {@code pushnew}/{@code push} -- announces.
	 * This is the idiom a real {@code .asd} uses to say "this library is present" and to
	 * take its own per-implementation decisions before its {@code defsystem} reads them
	 * back; fast-io.asd opens with exactly two of them.
	 * <p>
	 * The push is recorded STATICALLY and handed to {@link #parseDefsystem} as if the
	 * system had declared it with {@code :rontolisp-features} -- one mechanism, so an
	 * upstream file and a bundled replacement {@code .asd} behave identically (see
	 * {@code .kb/asdf.md}). What that buys is the system's own {@code :if-feature} /
	 * {@code (:feature ...)} clauses and the reading of its component files; it does NOT
	 * reach a {@code #+} in the same {@code .asd}, which the reader resolved before this
	 * parse ever ran ({@code .todo/181}), nor a dependency, which declares its own.
	 * <p>
	 * Only the feature-announcement shape is accepted -- anything else inside the
	 * {@code eval-when} is a hard error naming the form, like every other unsupported
	 * {@code .asd} form (deny by default; the file is data, never evaluated).
	 * @param form the {@code eval-when}/{@code pushnew} form
	 * @param fires whether the enclosing {@code eval-when} situations fire when the
	 * {@code .asd} is loaded
	 * @param features the active reader features (to recognize a substituted
	 * {@code *features*})
	 * @param pushed the accumulating feature names, in file order
	 * @param asdPath the {@code .asd} path, for error messages
	 */
	private static void collectFeaturePushes(LispVal form, boolean fires, Features features, List<String> pushed,
			String asdPath) {
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			throw featurePushError(asdPath, form);
		}
		List<LispVal> items = cons.toList();
		if (operatorMemberIs(form, LispNames.EVAL_WHEN)) {
			if (items.size() < 2) {
				throw featurePushError(asdPath, form);
			}
			boolean nested = fires && firesOnLoad(items.get(1));
			for (LispVal body : items.subList(2, items.size())) {
				collectFeaturePushes(body, nested, features, pushed, asdPath);
			}
			return;
		}
		if (!operatorMemberIs(form, LispNames.PUSHNEW) && !operatorMemberIs(form, LispNames.PUSH)) {
			throw featurePushError(asdPath, form);
		}
		if (items.size() != 3 || !(items.get(1) instanceof LispSymbol feature) || !feature.isKeyword()
				|| !isFeaturesReference(items.get(2), features)) {
			throw featurePushError(asdPath, form);
		}
		String name = symbolName(feature);
		if (fires && !pushed.contains(name)) {
			pushed.add(name);
		}
	}

	/**
	 * Merges the {@code .asd}'s feature pushes with a system's own declared features,
	 * pushes first, without duplicates.
	 */
	private static List<String> mergeFeatureNames(List<String> pushed, List<String> declared) {
		if (pushed.isEmpty()) {
			return declared;
		}
		List<String> merged = new ArrayList<>(pushed);
		for (String name : declared) {
			if (!merged.contains(name)) {
				merged.add(name);
			}
		}
		return List.copyOf(merged);
	}

	private static IllegalStateException featurePushError(String asdPath, LispVal form) {
		return new IllegalStateException(asdPath + ": only a feature announcement -- (pushnew :FEATURE *features*),"
				+ " alone or inside an " + LispNames.EVAL_WHEN + " -- is supported here: " + form.print());
	}

	/**
	 * Whether an {@code eval-when}'s situation list fires when the {@code .asd} is
	 * LOADED. ASDF {@code load}s a system-definition file and never compiles it, so a
	 * {@code (:compile-toplevel)}-only push genuinely has no effect in a real
	 * implementation either and must not widen the feature set here.
	 */
	private static boolean firesOnLoad(LispVal situations) {
		for (LispVal situation : properList(LispNames.EVAL_WHEN, situations)) {
			if (situation instanceof LispSymbol sym) {
				String name = symbolName(sym);
				if ("load-toplevel".equals(name) || "execute".equals(name) || "load".equals(name)
						|| "eval".equals(name)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether {@code form} is a reference to {@code *features*}. Two spellings reach
	 * here, because the reader that produced this form differs per target: the
	 * interpreter leaves the symbol standing (it binds {@code *features*} as a global),
	 * while the compile backends substitute it at read time with the quoted active
	 * feature list ({@code Features.substituteFeaturesVar}). Matching the substituted
	 * list against the active set -- rather than accepting any quoted list -- keeps a
	 * stray {@code (pushnew :x '(:a :b))} an error.
	 */
	private static boolean isFeaturesReference(LispVal form, Features features) {
		if (form instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return LispNames.FEATURES_VAR.equals(qn == null ? sym.name() : qn.member());
		}
		if (!(form instanceof LispCons cons) || !cons.isProperList()) {
			return false;
		}
		List<LispVal> items = cons.toList();
		if (items.size() != 2 || !(items.get(0) instanceof LispSymbol op) || !LispNames.QUOTE.equals(op.name())) {
			return false;
		}
		List<String> substituted = new ArrayList<>();
		LispVal rest = items.get(1);
		while (rest instanceof LispCons cell) {
			if (!(cell.car() instanceof LispSymbol name)) {
				return false;
			}
			substituted.add(symbolName(name));
			rest = cell.cdr();
		}
		return rest instanceof LispNil && substituted.equals(features.names());
	}

	/**
	 * The mini evaluator for {@code .asd} parse-time data: literals evaluate to
	 * themselves, a symbol reads an earlier {@code defparameter} (keywords are
	 * self-evaluating), and {@code quote}/{@code if}/{@code or}/{@code and}/{@code not}
	 * are supported -- exactly enough for the cl-postgres header
	 * {@code (defparameter *string-file* (if *unicode* ...))} shape. Anything else throws
	 * (deny by default; the {@code .asd} is never really evaluated).
	 */
	private static LispVal evalDataForm(LispVal form, Map<String, LispVal> parameters) {
		if (form instanceof LispSymbol sym) {
			if (sym.isKeyword()) {
				return sym;
			}
			LispVal value = parameters.get(symbolName(sym));
			if (value == null) {
				throw new IllegalStateException("undefined variable " + sym.name());
			}
			return value;
		}
		if (!(form instanceof LispCons cons)) {
			// Literals (strings, numbers, t, nil) evaluate to themselves.
			return form;
		}
		if (!(cons.car() instanceof LispSymbol op)) {
			throw new IllegalStateException("unsupported form " + form.print());
		}
		List<LispVal> items = cons.toList();
		switch (op.name()) {
			case LispNames.QUOTE -> {
				return items.get(1);
			}
			case LispNames.IF -> {
				if (items.size() != 3 && items.size() != 4) {
					throw new IllegalStateException("unsupported form " + form.print());
				}
				boolean test = !(evalDataForm(items.get(1), parameters) instanceof LispNil);
				if (test) {
					return evalDataForm(items.get(2), parameters);
				}
				return items.size() == 4 ? evalDataForm(items.get(3), parameters) : LispNil.INSTANCE;
			}
			case LispNames.NOT -> {
				if (items.size() != 2) {
					throw new IllegalStateException("unsupported form " + form.print());
				}
				return evalDataForm(items.get(1), parameters) instanceof LispNil ? LispTrue.INSTANCE : LispNil.INSTANCE;
			}
			case LispNames.OR -> {
				LispVal result = LispNil.INSTANCE;
				for (int i = 1; i < items.size(); i++) {
					result = evalDataForm(items.get(i), parameters);
					if (!(result instanceof LispNil)) {
						return result;
					}
				}
				return result;
			}
			case LispNames.AND -> {
				LispVal result = LispTrue.INSTANCE;
				for (int i = 1; i < items.size(); i++) {
					result = evalDataForm(items.get(i), parameters);
					if (result instanceof LispNil) {
						return result;
					}
				}
				return result;
			}
			default -> throw new IllegalStateException("unsupported form " + form.print());
		}
	}

	/**
	 * The enclosing {@code .asd} file's parse-time context: its path (for error messages)
	 * and the data environment its top-level {@code defparameter}s built, which a
	 * {@code #.} marker in a load-bearing option resolves against. A {@code defsystem}
	 * written directly in a {@code .lisp} file has neither ({@link #NONE}).
	 *
	 * @param path the {@code .asd} path, or {@code null} when the form is not from one
	 * @param parameters the parse-time {@code defparameter} bindings
	 */
	private record AsdContext(@Nullable String path, Map<String, LispVal> parameters) {

		private static final AsdContext NONE = new AsdContext(null, Map.of());

		/** The {@code "<path>: "} prefix every error from this file carries. */
		private String prefix() {
			return this.path == null ? "" : this.path + ": ";
		}
	}

	/**
	 * Resolves every {@code #.} marker inside a LOAD-BEARING {@code defsystem} value to
	 * its parse-time value: a reference to an earlier {@code defparameter} or anything
	 * else the mini evaluator supports ({@link #evalDataForm}) -- the cl-postgres
	 * {@code (:file #.*string-file*)} idiom. An unresolvable one is a hard error naming
	 * the {@code .asd} and the clause, like every other unsupported {@code .asd} shape.
	 * <p>
	 * Only the CONSUMER may call this, and only where the value decides something: what
	 * gets loaded ({@code :depends-on}, {@code :components}, {@code :serial},
	 * {@code :pathname}, {@code :class}, {@code :rontolisp-features}). The parsed-and-
	 * ignored metadata keeps its markers untouched and says nothing about them -- most of
	 * the {@code #.} in a real dist is a {@code :long-description} reading the project's
	 * README at load time, and complaining about a value the very next line throws away
	 * is noise, not diagnostics. In a load-bearing position the opposite holds:
	 * substituting nil there drops a dependency or a source file and surfaces much later
	 * as an undefined symbol far from the cause.
	 * <p>
	 * Conses are rebuilt only where something actually changed, so an untouched form
	 * keeps its identity and with it its recorded source position
	 * ({@code .kb/source-positions.md}).
	 */
	private static LispVal resolveReadEval(AsdContext asd, String context, LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (isReadEvalMarker(cons)) {
			List<LispVal> items = cons.toList();
			if (items.size() != 2) {
				throw unresolvableReadEval(asd, context, cons.print(), "malformed read-time-eval marker");
			}
			try {
				return evalDataForm(items.get(1), asd.parameters());
			}
			catch (IllegalStateException ex) {
				throw unresolvableReadEval(asd, context, items.get(1).print(),
						ex.getMessage() == null ? "unsupported form" : ex.getMessage());
			}
		}
		if (isUnreadableReadEvalMarker(cons)) {
			// The lexer could not even re-lex the datum, so there is nothing to evaluate
			// -- only the raw source text it carried here for this message.
			throw unresolvableReadEval(asd, context, unreadableReadEvalText(cons), "the datum could not be read");
		}
		LispVal car = resolveReadEval(asd, context, cons.car());
		LispVal cdr = resolveReadEval(asd, context, cons.cdr());
		return car == cons.car() && cdr == cons.cdr() ? cons : new LispCons(car, cdr);
	}

	private static IllegalStateException unresolvableReadEval(AsdContext asd, String context, String datum,
			String reason) {
		return new IllegalStateException(asd.prefix() + context + ": cannot resolve the #. read-time-eval form " + datum
				+ " (" + reason + "), and this clause decides what gets loaded -- a .asd is parsed as data, so only"
				+ " literals, quote and if/or/and/not over earlier defparameters resolve");
	}

	private static boolean isReadEvalMarker(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.READ_EVAL.equals(op.name());
	}

	/**
	 * Whether {@code form} is the tolerant reader's {@code (%read-eval-unreadable "RAW
	 * TEXT")} marker: a {@code #.} whose datum could not be re-lexed at all.
	 */
	private static boolean isUnreadableReadEvalMarker(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.READ_EVAL_UNREADABLE.equals(op.name());
	}

	/** The raw source text an unreadable {@code #.} marker carries. */
	private static String unreadableReadEvalText(LispCons marker) {
		List<LispVal> items = marker.toList();
		return items.size() == 2 && items.get(1) instanceof LispString text ? text.value() : marker.print();
	}

	/**
	 * Parses a {@code defsystem} form into a {@link LispSystem}, ordering the components
	 * by their {@code :depends-on}/{@code :serial} constraints. A component whose
	 * {@code :if-feature} expression is not satisfied by {@code givenFeatures} still
	 * participates in the ordering but contributes no source files (this is how libraries
	 * gate CLOS-only files behind {@code (:or :sbcl ...)}). Any option or component shape
	 * outside the supported subset is a hard error naming the clause.
	 * @param form the {@code defsystem} form
	 * @param baseDir the directory the component files resolve against, or {@code null}
	 * for working-directory-relative
	 * @param givenFeatures the features the {@code :if-feature} component option tests
	 * @return the parsed system
	 */
	public static LispSystem parseDefsystem(LispVal form, @Nullable String baseDir, Features givenFeatures) {
		return parseDefsystem(form, baseDir, givenFeatures, List.of());
	}

	/**
	 * Parses a {@code defsystem} form that an enclosing {@code .asd} preceded with
	 * feature pushes ({@link #collectFeaturePushes}). The pushed names are merged into
	 * the system's declared features ahead of its own {@code :rontolisp-features}, so
	 * both spellings of "this system holds these features" travel one path.
	 * @param form the {@code defsystem} form
	 * @param baseDir the directory the component files resolve against, or {@code null}
	 * for working-directory-relative
	 * @param givenFeatures the features the {@code :if-feature} component option tests
	 * @param pushedFeatures the feature names the enclosing {@code .asd} pushed onto
	 * {@code *features*} before this form
	 * @return the parsed system
	 */
	public static LispSystem parseDefsystem(LispVal form, @Nullable String baseDir, Features givenFeatures,
			List<String> pushedFeatures) {
		return parseDefsystem(form, baseDir, givenFeatures, pushedFeatures, Set.of(), AsdContext.NONE);
	}

	/**
	 * The {@code defsystem} options whose value is parsed and then thrown away: the
	 * metadata, plus the test-op wiring there is no {@code operate} machinery to drive. A
	 * {@code #.} read-time-eval marker inside one of these is never resolved and never
	 * complained about -- nothing reads the value, so nothing may object to it. Every
	 * OTHER option decides what gets loaded, so it resolves its markers and an
	 * unresolvable one fails the parse ({@link #resolveReadEval}); the default direction
	 * is deliberate, so a load-bearing option added later is covered without being
	 * remembered here.
	 */
	private static final Set<String> IGNORED_OPTIONS = Set.of(":NAME", ":DESCRIPTION", ":LONG-DESCRIPTION", ":VERSION",
			":AUTHOR", ":MAINTAINER", ":LICENSE", ":LICENCE", ":HOMEPAGE", ":BUG-TRACKER", ":SOURCE-CONTROL", ":MAILTO",
			":IN-ORDER-TO", ":PERFORM");

	/**
	 * Parses a {@code defsystem} form with the doc-file component-class names the
	 * enclosing {@code .asd} declared with tolerated top-level {@code defclass} forms
	 * ({@link #collectDocFileClass}); a component of such a type (or of ASDF's own
	 * {@code :doc-file}/{@code :html-file}) parses as an ordering-only entry.
	 * @param form the {@code defsystem} form
	 * @param baseDir the directory the component files resolve against, or {@code null}
	 * for working-directory-relative
	 * @param givenFeatures the features the {@code :if-feature} component option tests
	 * @param pushedFeatures the feature names the enclosing {@code .asd} pushed onto
	 * {@code *features*} before this form
	 * @param docComponentTypes the doc-file component-class names declared before this
	 * form
	 * @param asd the enclosing {@code .asd} file's path and parse-time
	 * {@code defparameter} bindings, which a load-bearing option's {@code #.} marker
	 * resolves against
	 * @return the parsed system
	 */
	private static LispSystem parseDefsystem(LispVal form, @Nullable String baseDir, Features givenFeatures,
			List<String> pushedFeatures, Set<String> docComponentTypes, AsdContext asd) {
		if (!(form instanceof LispCons cons)) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " expects a system definition form");
		}
		List<LispVal> items = cons.toList();
		if (items.size() < 2) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " expects a system name: " + form.print());
		}
		String name = designator(LispNames.ASDF_DEFSYSTEM,
				resolveReadEval(asd, LispNames.ASDF_DEFSYSTEM + " system name", items.get(1)));
		if ((items.size() - 2) % 2 != 0) {
			throw new IllegalStateException(
					LispNames.ASDF_DEFSYSTEM + " " + name + " expects :option value pairs: " + form.print());
		}
		// :rontolisp-features is read BEFORE the option loop: upstream pushes such a
		// feature from an eval-when ahead of its defsystem, so it must already hold for
		// this system's own :if-feature / (:feature ...) clauses, whatever order the
		// options happen to appear in. A push the .asd really made (pushedFeatures) is
		// the same declaration, arriving from the file rather than from the option.
		List<String> declaredFeatures = mergeFeatureNames(pushedFeatures, declaredFeatures(name, items, asd));
		Features features = declaredFeatures.isEmpty() ? givenFeatures : givenFeatures.with(declaredFeatures);
		List<String> dependsOn = new ArrayList<>();
		boolean serial = false;
		boolean packageInferred = false;
		LispVal components = null;
		String pathname = null;
		for (int i = 2; i < items.size(); i += 2) {
			if (!(items.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
						+ " expects a keyword option, got " + items.get(i).print());
			}
			// A #. marker resolves where its value is CONSUMED. An option in
			// IGNORED_OPTIONS keeps its markers untouched and stays silent about them;
			// every other option resolves them, and an unresolvable one fails the parse
			// naming the file and the clause (resolveReadEval).
			LispVal value = IGNORED_OPTIONS.contains(key.name()) ? items.get(i + 1) : resolveReadEval(asd,
					LispNames.ASDF_DEFSYSTEM + " " + name + " " + lower(key.name()), items.get(i + 1));
			switch (key.name()) {
				// Metadata: accepted for .asd compatibility, not recorded anywhere. The
				// :version value may be any literal form, including ASDF's
				// (:read-file-form "version.sexp") indirection -- it is never inspected.
				case ":NAME", ":DESCRIPTION", ":LONG-DESCRIPTION", ":VERSION", ":AUTHOR", ":MAINTAINER", ":LICENSE",
						":LICENCE", ":HOMEPAGE", ":BUG-TRACKER", ":SOURCE-CONTROL", ":MAILTO" ->
					{
					}
				// Test-op wiring only (there is no operate/test-op machinery to drive):
				// tolerated so a real library's .asd parses, ignored like the metadata.
				case ":IN-ORDER-TO", ":PERFORM" -> {
				}
				case ":DEPENDS-ON" -> {
					for (LispVal dep : properList(LispNames.ASDF_DEFSYSTEM + " " + name + " :depends-on", value)) {
						String depName = dependencyName(name, dep, features);
						if (depName != null) {
							dependsOn.add(depName);
						}
					}
				}
				case ":SERIAL" -> serial = !(value instanceof LispNil);
				case ":COMPONENTS" -> components = value;
				// The system-level :pathname is a path prefix for EVERY component,
				// exactly like a module's -- lack.asd says :pathname "src" and then
				// names its components bare. It composes with both (a component-level
				// :pathname and a :module prefix nest inside it), and an empty string
				// adds no directory level, the same rule a module follows.
				case ":PATHNAME" -> pathname = pathnamePrefix(LispNames.ASDF_DEFSYSTEM + " " + name, value);
				// The only implemented component class: no :components at all, the graph
				// derived from each file's own defpackage (inferPackageInferredSystems).
				// Every other class picks a component TYPE that changes how sources load,
				// which a data-only defsystem front end cannot honor, so it stays an
				// error.
				case ":CLASS" -> {
					if (!LispNames.PACKAGE_INFERRED_SYSTEM.equals(memberName(classDesignator(name, value)))) {
						throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name + ": unsupported :class "
								+ value.print() + " (the only supported class is :package-inferred-system)");
					}
					packageInferred = true;
				}
				// Already consumed by declaredFeatures above.
				case ":RONTOLISP-FEATURES" -> {
				}
				default -> throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
						+ ": unsupported option " + key.name() + " (supported: :name :description :long-description"
						+ " :version :author :maintainer :license :depends-on :serial :components :pathname :class"
						+ " :rontolisp-features)");
			}
		}
		String prefix = pathname == null || pathname.isEmpty() ? "" : pathname + "/";
		if (packageInferred && components != null) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
					+ ": a :package-inferred-system has no :components (its graph is derived from each file's"
					+ " defpackage)");
		}
		List<String> files = components == null ? List.of()
				: orderComponents(name, components, serial, prefix, features, docComponentTypes);
		// A package-inferred system's own :pathname is where its SUB-SYSTEM names
		// resolve:
		// array-operations says :pathname "src/" and then names array-operations/all.
		return new LispSystem(name, List.copyOf(dependsOn), files, baseDir == null ? "" : baseDir, declaredFeatures,
				packageInferred ? (pathname == null ? "" : pathname) : null);
	}

	/**
	 * Reads a {@code :class} value: a keyword, a symbol (possibly
	 * {@code asdf:}-qualified) or a string. The value is a CLASS name, so unlike a system
	 * designator it keeps its spelling for {@link #memberName} to strip and upcase.
	 */
	private static LispSymbol classDesignator(String systemName, LispVal value) {
		if (value instanceof LispSymbol sym) {
			return sym;
		}
		if (value instanceof LispString str) {
			return new LispSymbol(str.value());
		}
		throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + systemName
				+ " :class expects a class name (keyword, symbol or string), got " + value.print());
	}

	/**
	 * Reads the {@code :rontolisp-features} option's list of feature designators, ahead
	 * of the option loop. rontolisp's own extension, not an ASDF option: it declares
	 * statically what an upstream {@code .asd} pushes onto {@code *features*} from an
	 * {@code eval-when}, for a replacement {@code .asd} that also has to decide the
	 * per-implementation branch upstream takes at run time (postmodern's two). The
	 * upstream push itself is read too now ({@link #collectFeaturePushes}) and lands in
	 * the same place; what neither can reach is a {@code #+} in the SAME file, resolved
	 * when the file was read. The declaration is additive only -- there is no way to turn
	 * a feature OFF, since that would let a system claim to be a backend it is not.
	 */
	private static List<String> declaredFeatures(String systemName, List<LispVal> items, AsdContext asd) {
		String context = LispNames.ASDF_DEFSYSTEM + " " + systemName + " :rontolisp-features";
		List<String> declared = new ArrayList<>();
		for (int i = 2; i + 1 < items.size(); i += 2) {
			if (items.get(i) instanceof LispSymbol key && key.isKeyword() && ":RONTOLISP-FEATURES".equals(key.name())) {
				for (LispVal feature : properList(context, resolveReadEval(asd, context, items.get(i + 1)))) {
					if (!(feature instanceof LispSymbol sym)) {
						throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + systemName
								+ " :rontolisp-features expects feature names, got " + feature.print());
					}
					declared.add(symbolName(sym));
				}
			}
		}
		return List.copyOf(declared);
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
				String source = loader.load(path);
				// An executable .asd (ironclad's) cannot be parsed as data: substitute
				// the bundled replacement declaring the loadable slice, keeping the
				// located path so component files resolve against the real library.
				String replacement = AsdOverrides.replacementSource(fileName);
				return new LocatedAsd(path, replacement != null ? replacement : source);
			}
			catch (IOException ex) {
				tried.add(path);
			}
		}
		throw new IllegalStateException(LispNames.ASDF_LOAD_SYSTEM + ": system '" + name + "' not found (tried: "
				+ String.join(", ", tried) + "); add its directory to --system-path or RONTOLISP_SOURCE_REGISTRY");
	}

	/**
	 * Derives the sub-systems of a {@code :class :package-inferred-system} on demand and
	 * registers them: such a system declares no {@code :components} at all, so
	 * {@code registry} holds only the primary after its {@code .asd} was parsed and every
	 * {@code NAME/SUB} name has to be answered from the FILES.
	 * <p>
	 * A sub-system name is a path under the primary's directory ({@code x/a/b} ->
	 * {@code a/b.lisp}, below the primary's {@code :pathname} when it has one), and the
	 * dependencies are the packages that file's own {@code defpackage} names (the first
	 * one in the file: whatever precedes it, typically an {@code (in-package #:cl-user)}
	 * header, is skipped like real ASDF does) -- so reading the files is not an
	 * optimization, it is the whole dependency graph ({@code ningle.asd} lists only
	 * {@code "ningle/main"} and never mentions myway or alexandria). The closure
	 * reachable from {@code name} is derived in one pass, so the {@code .asd} is not
	 * re-read once per sub-system.
	 * <p>
	 * Cycles are left to the CALLER's existing {@code :depends-on} cycle check: an edge
	 * back to an already-registered sibling is simply not followed here, so this walk
	 * terminates and a real circular dependency is reported at load time with the stack
	 * that reached it, exactly like a hand-listed one.
	 * @param name the sub-system name to derive
	 * @param registry the loader's system registry, extended in place with every system
	 * derived
	 * @param systemPackages the loader's package-to-system map
	 * ({@code register-system-packages})
	 * @param loader the loader used to read the component files
	 * @param features the loader's reader features (widened by the primary's own
	 * {@code :rontolisp-features}, like any of its component files)
	 */
	public static void inferPackageInferredSystems(String name, Map<String, LispSystem> registry,
			Map<String, String> systemPackages, SourceLoader loader, Features features) {
		int slash = name.indexOf('/');
		if (slash < 0) {
			return;
		}
		LispSystem primary = registry.get(name.substring(0, slash));
		if (primary == null || primary.packageInferredDir() == null) {
			return;
		}
		Features readFeatures = primary.features().isEmpty() ? features : features.with(primary.features());
		Deque<String> pending = new ArrayDeque<>();
		pending.addLast(name);
		while (!pending.isEmpty()) {
			String subName = pending.removeFirst();
			if (registry.containsKey(subName)) {
				continue;
			}
			LispSystem derived = deriveSubSystem(subName, primary, systemPackages, loader, readFeatures);
			registry.put(subName, derived);
			for (String dependency : derived.dependsOn()) {
				if (dependency.startsWith(primary.name() + "/")) {
					pending.addLast(dependency);
				}
			}
		}
	}

	/**
	 * Derives one sub-system of a package-inferred system: its file is the name below the
	 * primary's, its dependencies are that file's {@code defpackage} dependencies. It
	 * inherits the primary's base directory and declared features (it IS one of the
	 * primary's component files), and is never itself package-inferred -- only the
	 * primary roots sub-system names.
	 */
	private static LispSystem deriveSubSystem(String name, LispSystem primary, Map<String, String> systemPackages,
			SourceLoader loader, Features features) {
		String dir = primary.packageInferredDir();
		String file = (dir == null || dir.isEmpty() ? "" : dir + "/") + name.substring(primary.name().length() + 1)
				+ ".lisp";
		String path = SourceLoader.resolve(primary.baseDir(), file);
		String source;
		try {
			source = loader.load(path);
		}
		catch (IOException ex) {
			throw new IllegalStateException(LispNames.ASDF_LOAD_SYSTEM + ": system '" + name
					+ "' is a sub-system of the package-inferred system '" + primary.name() + "', so it names the file "
					+ path + ", which cannot be read: " + ex.getMessage(), ex);
		}
		// Only up to the package definition form: every source in the system is opened
		// here, and the rest of each file is the load's business, not the dependency
		// graph's.
		LispVal packageForm = LispReader.readFirstFormMatching(source, features, path,
				AsdfSystems::isPackageDefinitionForm);
		return new LispSystem(name, packageDependencies(packageForm, path, systemPackages), List.of(file),
				primary.baseDir(), primary.features(), null);
	}

	/**
	 * Whether {@code form} is the file's package declaration -- a {@code defpackage} or a
	 * {@code uiop:define-package}. Everything before it is skipped, the way real ASDF's
	 * {@code file-defpackage-form} does: an {@code (in-package #:cl-user)} header (and
	 * anything else) ahead of the {@code defpackage} is a common style, and refusing it
	 * would make the library unloadable over a form that declares no dependency at all.
	 */
	private static boolean isPackageDefinitionForm(LispVal form) {
		return form instanceof LispCons cons && cons.isProperList()
				&& (operatorMemberIs(form, LispNames.DEFPACKAGE) || operatorMemberIs(form, LispNames.DEFINE_PACKAGE));
	}

	/**
	 * The system names a component file's own {@code defpackage} /
	 * {@code uiop:define-package} declares as dependencies: every package named in
	 * {@code :use}, {@code :mix}, {@code :reexport}, {@code :use-reexport} and
	 * {@code :mix-reexport}, plus the FIRST argument of each {@code :import-from} /
	 * {@code :shadowing-import-from}. {@code :nicknames}/{@code :shadow}/{@code :export}/
	 * {@code :intern}/{@code :documentation} contribute nothing, which is why the clauses
	 * are matched by name rather than rejected wholesale.
	 */
	private static List<String> packageDependencies(LispVal form, String path, Map<String, String> systemPackages) {
		if (!isPackageDefinitionForm(form)) {
			throw new IllegalStateException(
					path + ": a package-inferred system's file must contain a " + LispNames.DEFPACKAGE
							+ " (its dependencies are read from there), but no package definition form" + " was found");
		}
		List<LispVal> items = ((LispCons) form).toList();
		if (items.size() < 2) {
			throw new IllegalStateException(path + ": the " + LispNames.DEFPACKAGE
					+ " of a package-inferred system's file must name a package, got " + form.print());
		}
		List<String> dependencies = new ArrayList<>();
		for (LispVal clause : items.subList(2, items.size())) {
			if (!(clause instanceof LispCons clauseCons) || !clauseCons.isProperList()
					|| !(clauseCons.car() instanceof LispSymbol option)) {
				continue;
			}
			List<LispVal> args = clauseCons.toList();
			List<LispVal> packages = switch (memberName(option)) {
				case "USE", "MIX", "REEXPORT", "USE-REEXPORT", "MIX-REEXPORT" -> args.subList(1, args.size());
				case "IMPORT-FROM", "SHADOWING-IMPORT-FROM" ->
					args.size() < 2 ? List.<LispVal>of() : args.subList(1, 2);
				default -> List.<LispVal>of();
			};
			for (LispVal designator : packages) {
				String system = packageSystemName(packageDesignatorName(path, designator), systemPackages);
				if (system != null && !dependencies.contains(system)) {
					dependencies.add(system);
				}
			}
		}
		return List.copyOf(dependencies);
	}

	/**
	 * The system a package lives in: what {@code register-system-packages} recorded, or
	 * -- ASDF's default -- the downcased package name itself. {@code null} for a package
	 * no system provides: the ANSI packages and {@code asdf}, which are simply THERE.
	 * That exclusion is what keeps a {@code (:use #:cl)} from asking the loader for a
	 * system called {@code cl}.
	 */
	@Nullable private static String packageSystemName(String packageName, Map<String, String> systemPackages) {
		String registered = systemPackages.get(packageName);
		if (registered != null) {
			return registered;
		}
		return IMPLEMENTATION_PACKAGES.contains(packageName) ? null : packageName;
	}

	/**
	 * The packages that are present without loading anything (see
	 * {@link #packageSystemName}).
	 */
	private static final Set<String> IMPLEMENTATION_PACKAGES = Set.of("cl", "common-lisp", "cl-user",
			"common-lisp-user", "keyword", "asdf");

	/**
	 * Resolves one {@code :depends-on} entry to a system name, or {@code null} when the
	 * entry is dropped. A plain entry is a literal designator; a
	 * {@code (:feature FEATURE-EXPR DEPENDENCY-DEF)} entry contributes its dependency
	 * only when the feature expression is satisfied (this is how cl-postgres gates its
	 * {@code usocket} dependency to non-builtin-socket implementations -- under
	 * rontolisp's feature set such clauses are typically dropped). A surviving
	 * {@code (:require MODULE)} (an implementation-provided module) is a hard error:
	 * there is nothing to load it from.
	 */
	@Nullable private static String dependencyName(String systemName, LispVal dep, Features features) {
		if (dep instanceof LispCons cons && cons.car() instanceof LispSymbol op && cons.isProperList()) {
			if (":FEATURE".equals(op.name())) {
				return featureDependency(LispNames.ASDF_DEFSYSTEM + " " + systemName + " :depends-on", cons, features,
						(spec) -> dependencyName(systemName, spec, features));
			}
			if (":VERSION".equals(op.name())) {
				// (:version NAME "1.2.3") (mito-core.asd's dbi entry) resolves to the
				// plain dependency; the version constraint is NOT checked -- the systems
				// reachable here carry no reliable :version to check against (that option
				// is parsed-and-ignored metadata), so a check would compare with nothing.
				List<LispVal> items = cons.toList();
				if (items.size() < 3) {
					throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + systemName
							+ " :depends-on (:version ...) expects (:version NAME VERSION): " + dep.print());
				}
				return dependencyName(systemName, items.get(1), features);
			}
			if (":REQUIRE".equals(op.name())) {
				throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + systemName
						+ " :depends-on (:require ...) is not supported (implementation-provided modules do not"
						+ " exist here): " + dep.print());
			}
		}
		return designator(":depends-on", dep);
	}

	/**
	 * Resolves one component-level {@code :depends-on} entry to a sibling component name,
	 * or {@code null} when a {@code (:feature ...)} entry is dropped. Component
	 * dependencies take the same {@code (:feature FEATURE-EXPR DEPENDENCY-DEF)} form as
	 * system dependencies -- postmodern's {@code deftable} depends on {@code "table"}
	 * only in the MOP build.
	 */
	@Nullable private static String componentDependency(String systemName, String componentName, LispVal dep, Features features) {
		if (dep instanceof LispCons cons && cons.car() instanceof LispSymbol op && cons.isProperList()
				&& ":FEATURE".equals(op.name())) {
			return featureDependency("system " + systemName + ": component " + componentName + " :depends-on", cons,
					features, (spec) -> designator(":depends-on", spec));
		}
		return designator(":depends-on", dep);
	}

	/**
	 * Resolves a {@code (:feature FEATURE-EXPR DEPENDENCY-DEF ...)} dependency entry:
	 * {@code null} when the feature expression is not satisfied, otherwise the dependency
	 * resolved by {@code resolve}.
	 * <p>
	 * Elements past the dependency are IGNORED rather than rejected, matching real ASDF
	 * ({@code resolve-dependency-combination} for {@code :feature} reads exactly the
	 * first and second argument). Upstream postmodern's {@code deftable} carries
	 * {@code (:feature :postmodern-use-mop "table" "config")}, whose trailing
	 * {@code "config"} real ASDF silently drops; erroring on it would make a
	 * widely-deployed {@code .asd} unreadable over a clause that has never had an effect.
	 */
	@Nullable private static String featureDependency(String context, LispCons cons, Features features,
			Function<LispVal, @Nullable String> resolve) {
		List<LispVal> items = cons.toList();
		if (items.size() < 3) {
			throw new IllegalStateException(
					context + " (:feature ...) expects (:feature FEATURE-EXPR DEPENDENCY-DEF): " + cons.print());
		}
		if (!features.isEnabled(items.get(1))) {
			return null;
		}
		return resolve.apply(items.get(2));
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

	/**
	 * Parses a PACKAGE-name designator -- a string, a keyword, a {@code #:} designator or
	 * a bare symbol -- to the downcased package name. Unlike a system designator
	 * ({@link #designator}, where a string stays verbatim like ASDF's
	 * {@code coerce-name}), a string spelling is downcased too: this name is only ever a
	 * map key or a name to compare, and the three spellings of one package have to meet.
	 * @param context the operator name for error messages
	 * @param val the designator form
	 * @return the downcased package name
	 */
	private static String packageDesignatorName(String context, LispVal val) {
		if (val instanceof LispString str) {
			return lower(str.value());
		}
		if (val instanceof LispSymbol sym) {
			return symbolName(sym);
		}
		throw new IllegalStateException(
				context + " expects a package name (string, keyword or symbol), got " + val.print());
	}

	private static String symbolName(LispSymbol sym) {
		// A SYMBOL designator downcases, like ASDF's coerce-name (string designators
		// stay verbatim, also like ASDF). This is what keeps (ql:quickload :LIB) --
		// the spelling the upcase reader mode produces -- pointing at the same
		// lowercase dist/directory name as (ql:quickload :lib).
		if (sym.name().startsWith("#:")) {
			// An uninterned designator (#:lib), the portable defsystem idiom.
			return lower(sym.name().substring(2));
		}
		if (sym.isKeyword()) {
			return lower(sym.name().substring(1));
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return lower(qn == null ? sym.name() : qn.member());
	}

	private static String lower(String name) {
		return name.toLowerCase(java.util.Locale.ROOT);
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
			Features features, Set<String> docComponentTypes) {
		List<Component> components = new ArrayList<>();
		String previous = null;
		for (LispVal entry : properList(LispNames.ASDF_DEFSYSTEM + " " + systemName + " :components", componentsVal)) {
			Component component = parseComponent(systemName, entry, prefix, features, docComponentTypes);
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

	private static Component parseComponent(String systemName, LispVal entry, String prefix, Features features,
			Set<String> docComponentTypes) {
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
		String pathname = null;
		for (int i = 2; i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new IllegalStateException("system " + systemName + ": component " + name
						+ " expects a keyword option, got " + parts.get(i).print());
			}
			LispVal value = parts.get(i + 1);
			boolean module = ":MODULE".equals(type.name());
			switch (key.name()) {
				case ":DEPENDS-ON" -> {
					for (LispVal dep : properList("component " + name + " :depends-on", value)) {
						String resolved = componentDependency(systemName, name, dep, features);
						if (resolved != null) {
							dependsOn.add(resolved);
						}
					}
				}
				case ":IF-FEATURE" -> featureEnabled = features.isEnabled(value);
				// :pathname decouples a component's NAME (its identity in the sibling
				// dependency graph) from the path it contributes. quri's uri-classes
				// module is exactly this: named uri-classes, living in src/uri/.
				case ":PATHNAME" -> pathname = componentPathname(systemName, name, value);
				case ":SERIAL" -> {
					if (!module) {
						throw unsupportedComponentOption(systemName, type, name, key);
					}
					moduleSerial = !(value instanceof LispNil);
				}
				case ":COMPONENTS" -> {
					if (!module) {
						throw unsupportedComponentOption(systemName, type, name, key);
					}
					nested = value;
				}
				default -> throw unsupportedComponentOption(systemName, type, name, key);
			}
		}
		List<String> files = switch (type.name()) {
			case ":FILE" -> List.of(prefix
					+ (pathname == null ? name + ".lisp" : pathname.indexOf('.') < 0 ? pathname + ".lisp" : pathname));
			// A static file participates in ordering but contributes no source.
			case ":STATIC-FILE" -> List.of();
			case ":MODULE" -> {
				if (nested == null) {
					throw new IllegalStateException(
							"system " + systemName + ": module " + name + " expects a :components option");
				}
				// An empty :pathname is ASDF's "this module adds no directory level".
				String dir = pathname == null ? name : pathname;
				yield orderComponents(systemName, nested, moduleSerial, dir.isEmpty() ? prefix : prefix + dir + "/",
						features, docComponentTypes);
			}
			default -> {
				// A documentation component -- ASDF's own :doc-file/:html-file or a type
				// the .asd declared with a tolerated doc-file defclass (chipz's
				// :txt-file/:css-file) -- is ordering-only, like :static-file.
				String member = type.name().substring(1);
				if (DOC_FILE.equals(member) || HTML_FILE.equals(member) || docComponentTypes.contains(member)) {
					yield List.of();
				}
				throw new IllegalStateException("system " + systemName + ": unsupported component type " + type.name()
						+ " (supported: :file :module :static-file and declared doc-file classes)");
			}
		};
		// A feature-disabled component keeps its place in the dependency graph (a
		// sibling may :depends-on it) but contributes no source files.
		return new Component(name, dependsOn, featureEnabled ? files : List.of());
	}

	private static String componentPathname(String systemName, String name, LispVal value) {
		return pathnamePrefix("system " + systemName + ": component " + name, value);
	}

	/**
	 * Reads a system-level or component-level {@code :pathname} value. Only a literal
	 * namestring is accepted ({@code "src"}, or the {@code #P"src"} the reader hands over
	 * as a string): a computed pathname would need the pathname machinery ASDF-as-data
	 * deliberately does not have. A trailing slash is dropped so the caller composes
	 * exactly one separator.
	 */
	private static String pathnamePrefix(String context, LispVal value) {
		if (!(value instanceof LispString str)) {
			throw new IllegalStateException(context + " :pathname expects a namestring literal, got " + value.print());
		}
		String path = str.value();
		return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
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
