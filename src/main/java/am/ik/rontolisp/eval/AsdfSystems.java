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
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The shared core of the limited ASDF subset: loads {@code .asd} files ({@link #loadAsd}
 * -- the file runs as Lisp in an {@link AsdImage}, and its {@code defsystem} forms are
 * parsed as plain data), orders a system's components by their
 * {@code :depends-on}/{@code :serial} constraints, and locates {@code NAME.asd} files on
 * a search path. Real ASDF is not ported -- the plan a system is loaded by is fixed, not
 * computed, and a {@code defsystem} option outside the supported subset is a hard error
 * naming the unsupported clause.
 *
 * <p>
 * The supported {@code defsystem} grammar is: a literal system name (string, keyword or
 * symbol), the ignored metadata options ({@code :description} and friends; only
 * {@code :version} is read back, by {@code asdf:component-version}), {@code :depends-on}
 * (system names loaded first, through the same search path),
 * {@code :defsystem-depends-on} (the same, loaded ahead of them -- real ASDF loads those
 * while the {@code .asd} is READ, and a built-in one contributes its declared features to
 * this system's read), {@code :serial} (each component implicitly depends on the previous
 * one), {@code :pathname} (a path prefix for every component), and {@code :components}
 * with {@code (:file "name" [:depends-on (...)])},
 * {@code (:module "dir" :components (...))} (a path prefix) and
 * {@code (:static-file "name")} (ignored) entries. A component's CLASS decides only the
 * two things a data-only parse can honor -- whether the component contributes a source
 * file and which extension its name gets -- so ASDF's own classes, the ones a top-level
 * {@code defclass} in the same {@code .asd} declares, and
 * {@code :default-component-class} are all supported.
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
	 * {@code .asd} pushed onto {@code *features*} ahead of the form, however it computed
	 * them ({@link AsdLoad#pushedFeatures}) -- one mechanism, two spellings. They hold
	 * while THIS system's own clauses are parsed and while its component files are read,
	 * and each loader widens its own base set with them ({@code Features.with}) -- so the
	 * backend feature stays the loader's, and a dependency (parsed from its own
	 * {@code .asd}) never inherits them
	 * @param packageInferredDir the directory a SUB-SYSTEM name resolves against when
	 * this system is a {@code :class :package-inferred-system} ({@code ""} when the
	 * system declares no {@code :pathname}, so sub-systems sit beside the {@code .asd}),
	 * or {@code null} when it is an ordinary system. Non-null is the whole marker for the
	 * class: such a system lists no {@code :components} and its graph is derived from the
	 * component files' own {@code defpackage} forms
	 * ({@link #inferPackageInferredSystems})
	 * @param packageInferredClass whether the runtime metaobject for this system is an
	 * {@code asdf:package-inferred-system} instance: true for a primary that declares the
	 * class ({@code packageInferredDir} non-null) AND for every sub-system derived from
	 * one -- real ASDF's shape, and the branch rove's {@code run-system} typecase takes
	 * for a derived {@code lib/tests} system
	 * @param testOp the recorded {@code :perform (test-op (o c) BODY...)} clause, or
	 * {@code null} when the {@code .asd} declares none; {@code asdf:test-system} runs the
	 * body with the two parameters bound (the operation is nil -- there is no
	 * {@code operate} machinery -- and the component is the system's metaobject)
	 * @param testOpEdges the system names a {@code :in-order-to ((test-op (test-op
	 * ...)))} option chains test-op to, in order; {@code asdf:test-system} follows them
	 * before the system's own perform
	 * @param defsystemDependsOn the {@code :defsystem-depends-on} names: systems real
	 * ASDF loads while the {@code .asd} is READ, so that the rest of the definition (and
	 * the component files) may rely on what they announce. Each loader loads/splices them
	 * BEFORE {@link #dependsOn} -- they are not sideway dependencies of the system and
	 * never appear in {@code asdf:component-sideway-dependencies}. A BUILT-IN one also
	 * ANNOUNCES its declared features into {@link #features} at parse time
	 * ({@code BuiltinSystems.declaredFeatures} -- as a built-in {@code :depends-on} entry
	 * does, see {@link #dependencyNames}), which is the whole point of the option for a
	 * reader: dexador's {@code trivial-features} entry
	 * @param version the {@code :version} value when it is a plain string literal,
	 * {@code null} otherwise (a {@code (:read-file-form ...)} indirection or an
	 * unresolved {@code #.} marker -- the option stays in {@link #IGNORED_OPTIONS}, so
	 * nothing here evaluates anything to find out). Read back by
	 * {@code asdf:component-version}
	 */
	public record LispSystem(String name, List<String> dependsOn, List<String> files, String baseDir,
			List<String> features, @Nullable String packageInferredDir, boolean packageInferredClass,
			@Nullable TestOp testOp, List<String> testOpEdges, List<String> defsystemDependsOn,
			@Nullable String version) {

		/**
		 * The old-shape constructor: no test-op wiring, package-inferred class iff the
		 * directory marker is present. Keeps the parse-only construction sites (and the
		 * tests) readable.
		 * @param name the system name
		 * @param dependsOn the names of the systems to load first, in order
		 * @param files the component source files in load order
		 * @param baseDir the directory the component files resolve against
		 * @param features the feature names this system declares
		 * @param packageInferredDir the sub-system resolution directory, or {@code null}
		 */
		public LispSystem(String name, List<String> dependsOn, List<String> files, String baseDir,
				List<String> features, @Nullable String packageInferredDir) {
			this(name, dependsOn, files, baseDir, features, packageInferredDir, packageInferredDir != null, null,
					List.of(), List.of(), null);
		}
	}

	/**
	 * A recorded {@code :perform (test-op (o c) BODY...)} clause: the two parameter
	 * symbols as written and the body forms as plain data (bare {@code asdf}/{@code uiop}
	 * member symbols pre-qualified by {@link #normalizeAsdUserForm}, the resolution a
	 * real {@code .asd} gets from being read in {@code asdf-user}).
	 *
	 * @param params the parameter symbols (operation, component), as written
	 * @param body the body forms
	 */
	public record TestOp(List<LispVal> params, List<LispVal> body) {
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
	 * No directory on the search path holds the {@code .asd} a system name needs -- the
	 * miss {@code ql:quickload} answers by downloading the system, as Quicklisp installs
	 * a dependency its index never named.
	 */
	public static final class SystemNotFoundException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		SystemNotFoundException(String message) {
			super(message);
		}

	}

	/**
	 * Returns whether {@code form} is an {@code asdf:defsystem} form. Only the
	 * {@code asdf}-qualified spelling counts here (inside a {@code .asd} file, loaded by
	 * {@link #loadAsd}, the bare {@code defsystem} spelling is accepted too).
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
	 * If {@code form} is a top-level literal {@code (asdf:test-system NAME)} call,
	 * returns the system name; otherwise {@code null}. Like
	 * {@link #loadSystemName(LispVal)}, a non-literal name is a hard error at inline
	 * time: the compile path must splice the system (and its test-op chain) to have
	 * anything to run.
	 * @param form the form to inspect
	 * @return the literal system name, or {@code null}
	 */
	@Nullable public static String testSystemName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !isAsdfMember(op, LispNames.TEST_SYSTEM)) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.size() != 2) {
			throw new IllegalStateException(
					LispNames.ASDF_TEST_SYSTEM + " expects exactly one system name: " + form.print());
		}
		return designator(LispNames.ASDF_TEST_SYSTEM, items.get(1));
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
	 * The image a {@code .asd} file is LOADED into: the evaluator its forms run in. A
	 * real {@code .asd} is a Lisp program -- a conditional {@code defpackage}, a
	 * {@code warn}, a {@code find-system} probe, a feature it computes and pushes -- so
	 * it runs in one ({@link #loadAsd}). The interpreter's image is the program's own
	 * evaluator; a compile path's is a build-time evaluator whose state never reaches the
	 * program, so what the program gets from a {@code .asd} is the plan its
	 * {@code defsystem} forms record, on every backend alike.
	 */
	@FunctionalInterface
	public interface AsdImage {

		/**
		 * Opens the load of one {@code .asd}: {@code *package*} bound to
		 * {@code asdf-user} (an {@code in-package} inside is scoped to the load),
		 * {@code *load-pathname*}/{@code *load-truename*} to the file, and
		 * {@code *features*} to its current value, so what the file pushes is ITS
		 * declaration ({@link AsdLoad#pushedFeatures}), undone when the load ends. A
		 * system the file's own forms load stays loaded, and so do the features it
		 * pushes.
		 * @param asdPath the resolved path of the {@code .asd} file
		 * @param nestedDefsystems records an {@code asdf:defsystem} that EVALUATION
		 * reaches -- one a {@code when} guards, or a macro expands to -- and answers the
		 * system it defined
		 * @return the open load, closed when the file is done
		 */
		AsdLoad open(String asdPath, Function<LispVal, LispSystem> nestedDefsystems);

	}

	/**
	 * One open {@code .asd} load ({@link AsdImage#open}).
	 */
	public interface AsdLoad extends AutoCloseable {

		/**
		 * Evaluates a top-level form of the file, its {@code #.} markers resolved first.
		 * @param form the form as read
		 */
		void eval(LispVal form);

		/**
		 * The value of a {@code #.} datum that a load-bearing {@code defsystem} option
		 * holds ({@link #resolveReadEval}).
		 * @param datum the datum of the {@code %read-eval} marker
		 * @return its value
		 */
		LispVal readEval(LispVal datum);

		/**
		 * The feature names the load has pushed onto {@code *features*} since it opened,
		 * in push order and spelled as {@code :rontolisp-features} records them -- what
		 * the file's later forms are read with and what a system it defines next holds.
		 * @return the pushed names
		 */
		List<String> pushedFeatures();

		/**
		 * The systems the file's OWN forms loaded ({@code asdf:load-system} and what
		 * calls it) since the last call, in order. Real ASDF loads them into the image
		 * while it reads the file, ahead of every system the file defines after the call,
		 * which is what {@code :defsystem-depends-on} declares.
		 * @return the loaded names
		 */
		List<String> takeLoadedSystems();

		@Override
		void close();

	}

	/**
	 * Loads the source text of a {@code .asd} file into {@code image}, form at a time,
	 * and answers the systems it defined. The file is Lisp and runs as Lisp; what the
	 * PLAN reads is recognized by its shape before anything runs, so a system's
	 * definition never depends on evaluating ASDF's own machinery:
	 * <ul>
	 * <li>{@code defsystem} (any package spelling) is the recording operator: its options
	 * are parsed as data ({@link #parseDefsystem}) with the features the file has pushed
	 * so far, and a {@code #.} in an option that decides what gets loaded is resolved in
	 * the image ({@link #resolveReadEval}). An {@code asdf:defsystem} that evaluation
	 * reaches instead -- inside a {@code when}, out of a macro -- is handed back through
	 * the image and recorded the same way.</li>
	 * <li>{@code register-system-packages} is recorded into {@code systemPackages}.</li>
	 * <li>A component-class {@code defclass} declares a component type
	 * ({@link #componentClass}); a {@code source-file-type} method sets its class's
	 * extension (a pre-pass, {@link #collectSourceFileTypes}); every other
	 * {@code defmethod} is ignored -- it extends the operations of the image the file was
	 * loaded into, which a compile path's program never meets, and the plan here is fixed
	 * rather than computed by them.</li>
	 * <li>{@code progn}, and an {@code eval-when} whose situations fire on load, are
	 * flattened, so each form inside meets these same rules; an {@code eval-when} that
	 * does not fire is dropped. A top-level {@code #.} is ignored (the ASDF version
	 * guard, whose whole effect is to refuse an old ASDF).</li>
	 * <li>Every other form is EVALUATED in the image: the package header (a conditional
	 * {@code defpackage} included), a {@code warn}, a {@code find-system} probe, a
	 * {@code defparameter} a later {@code #.} reads, a feature push however it is
	 * computed, an {@code asdf:load-system} -- whose system then precedes every system
	 * defined after it, as a {@code :defsystem-depends-on} entry.</li>
	 * </ul>
	 * Each form is read with the loader's {@code features} widened by what the file has
	 * pushed so far, so a {@code #+} sees the push of any form above it -- CL's
	 * form-at-a-time {@code load}. A pushed feature is the file's declaration and reaches
	 * only the systems it defines after the push (their {@link LispSystem#features}); it
	 * does not stay in {@code *features*}, so no other system's read, and no other
	 * {@code .asd}, depends on which files happened to load first.
	 * @param source the {@code .asd} source text
	 * @param asdPath the resolved path of the {@code .asd} file (for the base directory
	 * and error messages)
	 * @param features the loader's reader features
	 * @param systemPackages the loader's package-to-system map, extended in place by
	 * {@code register-system-packages} ({@link #inferPackageInferredSystems} reads it)
	 * @param image the image the file runs in
	 * @param register enters a defined system into the loader's registry the moment it is
	 * defined, so a later form of the same file finds or loads it
	 * @return the systems the file defined, in order
	 */
	public static List<LispSystem> loadAsd(String source, String asdPath, Features features,
			Map<String, String> systemPackages, AsdImage image, java.util.function.Consumer<LispSystem> register) {
		AsdFile file = new AsdFile(asdPath, features, register);
		// The source-file-type methods are a PRE-pass: real ASDF calls that generic
		// function when it OPERATES on a component, long after the whole file is read,
		// so where the method sits relative to the defsystem that uses the class must
		// not matter (htmlgen.asd writes it right under the defclass, acl-compat.asd a
		// hundred lines below one).
		collectSourceFileTypes(LispReader.scanForms(source, features, asdPath), file.componentClasses);
		try (AsdLoad load = image.open(asdPath, file::defineSystem)) {
			file.load = load;
			Deque<LispVal> pending = new ArrayDeque<>();
			int offset = 0;
			while (true) {
				if (pending.isEmpty()) {
					LispReader.NextForm next = LispReader.readNextForm(source, offset, file.features(), asdPath);
					if (next.form() == null) {
						break;
					}
					offset = next.end();
					pending.add(next.form());
					continue;
				}
				LispVal form = pending.removeFirst();
				if (isReadEvalMarker(form) || isUnreadableReadEvalMarker(form)) {
					continue;
				}
				if (operatorMemberIs(form, LispNames.PROGN)) {
					pushBody(pending, ((LispCons) form).toList(), 1);
					continue;
				}
				if (operatorMemberIs(form, LispNames.EVAL_WHEN)) {
					List<LispVal> items = properList(LispNames.EVAL_WHEN, form);
					if (items.size() >= 2 && firesOnLoad(items.get(1))) {
						pushBody(pending, items, 2);
					}
					continue;
				}
				if (operatorMemberIs(form, LispNames.REGISTER_SYSTEM_PACKAGES)) {
					collectSystemPackages(form, systemPackages, asdPath);
					continue;
				}
				if (operatorMemberIs(form, LispNames.DEFMETHOD)) {
					continue;
				}
				if (operatorMemberIs(form, LispNames.DEFCLASS) && file.declareComponentClass(form)) {
					continue;
				}
				if (operatorMemberIs(form, LispNames.DEFSYSTEM)) {
					file.defineSystem(form);
					continue;
				}
				LispCons unreadable = unreadableReadEval(form);
				if (unreadable != null) {
					throw new IllegalStateException(
							asdPath + ": the #. datum " + unreadableReadEvalText(unreadable) + " could not be read");
				}
				load.eval(form);
			}
		}
		return List.copyOf(file.systems);
	}

	/** Puts {@code items[from..]} back on the front of the worklist, in order. */
	private static void pushBody(Deque<LispVal> pending, List<LispVal> items, int from) {
		for (int i = items.size() - 1; i >= from; i--) {
			pending.addFirst(items.get(i));
		}
	}

	/**
	 * One {@code .asd} file being loaded: its directory, the component classes in scope,
	 * the systems it defined so far, and the systems its own forms loaded, which precede
	 * every system it defines after them.
	 */
	private static final class AsdFile {

		private final String asdPath;

		private final String baseDir;

		private final Features features;

		private final java.util.function.Consumer<LispSystem> register;

		private final ComponentClasses componentClasses = new ComponentClasses();

		private final List<LispSystem> systems = new ArrayList<>();

		private final List<String> loadedByFile = new ArrayList<>();

		@Nullable private AsdLoad load;

		AsdFile(String asdPath, Features features, java.util.function.Consumer<LispSystem> register) {
			this.asdPath = asdPath;
			String parent = SourceLoader.parentDir(asdPath);
			this.baseDir = parent == null ? "" : parent;
			this.features = features;
			this.register = register;
		}

		private AsdLoad load() {
			return java.util.Objects.requireNonNull(this.load, "the .asd load is not open");
		}

		/**
		 * The features the next form is read with: the loader's plus the file's pushes.
		 */
		Features features() {
			List<String> pushed = load().pushedFeatures();
			return pushed.isEmpty() ? this.features : this.features.with(pushed);
		}

		/**
		 * Declares the component class a {@code defclass} defines, or answers
		 * {@code false} when it is not one -- an ordinary class, which the file then
		 * evaluates like any other form.
		 */
		boolean declareComponentClass(LispVal form) {
			List<LispVal> items = ((LispCons) form).toList();
			ComponentClass declared = componentClass(items, this.componentClasses);
			if (declared == null) {
				return false;
			}
			this.componentClasses.declare(memberName((LispSymbol) items.get(1)), declared);
			return true;
		}

		/** Parses and registers one {@code defsystem} form, answering the system. */
		LispSystem defineSystem(LispVal form) {
			AsdLoad open = load();
			this.loadedByFile.addAll(open.takeLoadedSystems());
			LispSystem system = parseDefsystem(form, this.baseDir, this.features, open.pushedFeatures(),
					this.componentClasses, new AsdContext(this.asdPath, open));
			system = withEarlierLoads(system, this.loadedByFile);
			this.register.accept(system);
			this.systems.add(system);
			return system;
		}

	}

	/**
	 * {@code system} with the systems its {@code .asd} loaded before defining it leading
	 * its {@code :defsystem-depends-on} -- loaded first, never a sideway dependency.
	 */
	private static LispSystem withEarlierLoads(LispSystem system, List<String> loaded) {
		List<String> merged = new ArrayList<>();
		for (String name : loaded) {
			if (!name.equals(system.name()) && !merged.contains(name)) {
				merged.add(name);
			}
		}
		if (merged.isEmpty()) {
			return system;
		}
		for (String name : system.defsystemDependsOn()) {
			if (!merged.contains(name)) {
				merged.add(name);
			}
		}
		return new LispSystem(system.name(), system.dependsOn(), system.files(), system.baseDir(), system.features(),
				system.packageInferredDir(), system.packageInferredClass(), system.testOp(), system.testOpEdges(),
				List.copyOf(merged), system.version());
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
	 * A component CLASS, as much of one as a data-only parse can hold. Only two things a
	 * real subclass decides ever reach here: whether an instance contributes a SOURCE
	 * file at all ({@code cl-source-file} and its subclasses do; {@code static-file} /
	 * {@code doc-file} and theirs are ordering-only) and which file EXTENSION the
	 * component's name gets (ASDF's {@code source-file-type}). Everything else the
	 * upstream subclasses in the wild exist for -- muffling non-style warnings around
	 * {@code compile-op}, mostly -- is about compile-file warnings, which do not exist
	 * here at all, so ignoring it is exact rather than approximate.
	 *
	 * @param source whether a component of this class contributes its file
	 * @param fileType the extension a component of this class gets, without the dot
	 * (meaningless, and empty, for an ordering-only class)
	 */
	private record ComponentClass(boolean source, String fileType) {
	}

	/**
	 * The class {@code (:file "name")} means when no {@code :default-component-class}
	 * does -- ASDF's {@code *default-component-class*}.
	 */
	private static final ComponentClass DEFAULT_COMPONENT_CLASS = new ComponentClass(true, "lisp");

	/**
	 * ASDF's own component classes: usable as a component type and as a superclass
	 * without a local {@code defclass}. {@code cl-source-file.cl} and
	 * {@code cl-source-file.lsp} are ASDF's two "my sources are not named .lisp" classes
	 * -- portableaserve's {@code :default-component-class cl-source-file.cl} is exactly
	 * what they exist for.
	 */
	private static final Map<String, ComponentClass> BUILTIN_COMPONENT_CLASSES = Map.of("CL-SOURCE-FILE",
			DEFAULT_COMPONENT_CLASS, "CL-SOURCE-FILE.CL", new ComponentClass(true, "cl"), "CL-SOURCE-FILE.LSP",
			new ComponentClass(true, "lsp"), "STATIC-FILE", new ComponentClass(false, ""), "DOC-FILE",
			new ComponentClass(false, ""), "HTML-FILE", new ComponentClass(false, ""));

	private static final String SOURCE_FILE_TYPE = "SOURCE-FILE-TYPE";

	/**
	 * The component classes in scope while one {@code .asd} file is loaded: ASDF's own,
	 * the ones the file declared with a top-level {@code defclass}
	 * ({@link #componentClass}), and the file-extension overrides its
	 * {@code source-file-type} methods set ({@link #collectSourceFileTypes}).
	 */
	private static final class ComponentClasses {

		private final Map<String, ComponentClass> declared = new HashMap<>();

		private final Map<String, String> fileTypeOverrides = new HashMap<>();

		/**
		 * The class a component type or a superclass names, or {@code null} when no such
		 * class is in scope.
		 * @param member the UPPERCASE member name ({@link AsdfSystems#memberName})
		 * @return the class, or {@code null}
		 */
		@Nullable ComponentClass find(String member) {
			ComponentClass found = this.declared.get(member);
			if (found == null) {
				found = BUILTIN_COMPONENT_CLASSES.get(member);
			}
			if (found == null) {
				return null;
			}
			String override = this.fileTypeOverrides.get(member);
			return override == null ? found : new ComponentClass(found.source(), override);
		}

		void declare(String member, ComponentClass componentClass) {
			this.declared.put(member, componentClass);
		}

		void overrideFileType(String member, String fileType) {
			this.fileTypeOverrides.put(member, fileType);
		}

	}

	/**
	 * Pre-pass over a {@code .asd}'s top-level forms -- descending into {@code progn} and
	 * a firing {@code eval-when}, the way {@link #loadAsd} flattens them -- for
	 * {@code (defmethod source-file-type ((c CLASS) (s module)) "ext")}, the other half
	 * of how a class says its sources are not named {@code .lisp} (the first half being a
	 * {@code (type :initform "ext")} slot). It is a PRE-pass because real ASDF calls the
	 * generic function when it operates on a component, long after the whole file is
	 * read: htmlgen.asd writes the method directly under its {@code defclass},
	 * acl-compat.asd a hundred lines below one, and neither position may change what the
	 * {@code defsystem} between them means. A method whose shape is not that literal one
	 * contributes nothing and stays tolerated-and-ignored like every other method.
	 */
	private static void collectSourceFileTypes(List<LispVal> forms, ComponentClasses classes) {
		for (LispVal form : forms) {
			if (operatorMemberIs(form, LispNames.PROGN)) {
				List<LispVal> body = ((LispCons) form).toList();
				collectSourceFileTypes(body.subList(1, body.size()), classes);
				continue;
			}
			if (operatorMemberIs(form, LispNames.EVAL_WHEN)) {
				List<LispVal> items = ((LispCons) form).toList();
				if (items.size() >= 2 && items.get(1) instanceof LispCons situations && situations.isProperList()
						&& firesOnLoad(situations)) {
					collectSourceFileTypes(items.subList(2, items.size()), classes);
				}
				continue;
			}
			if (!operatorMemberIs(form, LispNames.DEFMETHOD)) {
				continue;
			}
			List<LispVal> items = ((LispCons) form).toList();
			if (items.size() != 4 || !(items.get(1) instanceof LispSymbol name)
					|| !SOURCE_FILE_TYPE.equals(memberName(name)) || !(items.get(2) instanceof LispCons params)
					|| !params.isProperList() || !(items.get(3) instanceof LispString fileType)) {
				continue;
			}
			if (params.car() instanceof LispCons specializer && specializer.isProperList()) {
				List<LispVal> parts = specializer.toList();
				if (parts.size() == 2 && parts.get(1) instanceof LispSymbol classSym) {
					classes.overrideFileType(memberName(classSym), fileType.value());
				}
			}
		}
	}

	/**
	 * The component class a top-level {@code defclass} in a {@code .asd} file declares,
	 * or {@code null} when it declares none. A COMPONENT class is one whose every
	 * superclass ASDF defines ({@link #BUILTIN_COMPONENT_CLASSES}) or this file declared
	 * earlier, which costs nothing in generality because real ASDF resolves a component
	 * type by {@code find-class} while it READS the {@code defsystem}, so the class has
	 * to precede its use anyway -- and the declaration decides only what a
	 * {@link ComponentClass} holds: whether components of the class contribute a source
	 * file (inherited from the superclasses) and their file extension (inherited too, and
	 * overridable by a {@code (type :initform "ext")} slot, which is the very slot ASDF's
	 * own {@code source-file-type} reads). The two driving shapes are chipz.asd's
	 * {@code (defclass txt-file (doc-file) ((type :initform "txt")))} -- ordering-only --
	 * and portableaserve's
	 * {@code (defclass legacy-acl-source-file (cl-source-file.cl) ())}, whose components
	 * load {@code NAME.cl}. Any other class (an operation, a condition) is the file's own
	 * business: {@link #loadAsd} evaluates it.
	 */
	@Nullable private static ComponentClass componentClass(List<LispVal> items, ComponentClasses classes) {
		if (items.size() < 3 || !(items.get(1) instanceof LispSymbol) || !(items.get(2) instanceof LispCons supers)
				|| !supers.isProperList()) {
			return null;
		}
		boolean source = false;
		String fileType = "";
		for (LispVal superClass : supers.toList()) {
			ComponentClass resolved = superClass instanceof LispSymbol superSym ? classes.find(memberName(superSym))
					: null;
			if (resolved == null) {
				return null;
			}
			if (resolved.source() && !source) {
				source = true;
				fileType = resolved.fileType();
			}
		}
		String initform = items.size() >= 4 ? slotTypeInitform(items.get(3)) : null;
		return new ComponentClass(source, initform == null ? fileType : initform);
	}

	/**
	 * The {@code "ext"} of a {@code (type :initform "ext")} slot in a component-class
	 * {@code defclass}: ASDF's {@code source-file-type} reads that very slot, so a class
	 * sets its extension either this way (chipz) or with a method (htmlgen).
	 */
	@Nullable private static String slotTypeInitform(LispVal slots) {
		if (!(slots instanceof LispCons cons) || !cons.isProperList()) {
			return null;
		}
		for (LispVal slot : cons.toList()) {
			if (!(slot instanceof LispCons slotCons) || !slotCons.isProperList()
					|| !(slotCons.car() instanceof LispSymbol slotName) || !"TYPE".equals(memberName(slotName))) {
				continue;
			}
			List<LispVal> parts = slotCons.toList();
			for (int i = 1; i + 1 < parts.size(); i += 2) {
				if (parts.get(i) instanceof LispSymbol option && option.isKeyword() && ":INITFORM".equals(option.name())
						&& parts.get(i + 1) instanceof LispString fileType) {
					return fileType.value();
				}
			}
		}
		return null;
	}

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
	 * Merges the features a {@code .asd} pushed before a {@code defsystem} with the
	 * system's own declared features, pushes first, without duplicates.
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

	/**
	 * Whether an {@code eval-when}'s situation list fires when the {@code .asd} is
	 * LOADED. ASDF {@code load}s a system-definition file and never compiles it, so a
	 * {@code (:compile-toplevel)}-only body genuinely has no effect in a real
	 * implementation either and must not run here.
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
	 * The enclosing {@code .asd} file's load context: its path (for error messages) and
	 * the open load whose image a {@code #.} marker in a load-bearing option is evaluated
	 * in. A {@code defsystem} written directly in a {@code .lisp} file has neither
	 * ({@link #NONE}).
	 *
	 * @param path the {@code .asd} path, or {@code null} when the form is not from one
	 * @param load the open load, or {@code null} when the form is not from one
	 */
	private record AsdContext(@Nullable String path, @Nullable AsdLoad load) {

		private static final AsdContext NONE = new AsdContext(null, null);

		/** The {@code "<path>: "} prefix every error from this file carries. */
		private String prefix() {
			return this.path == null ? "" : this.path + ": ";
		}
	}

	/**
	 * Resolves every {@code #.} marker inside a LOAD-BEARING {@code defsystem} value to
	 * its value in the image the {@code .asd} is loaded into -- a {@code defparameter}
	 * the file evaluated above it, say: the cl-postgres {@code (:file #.*string-file*)}
	 * idiom. An unresolvable one is a hard error naming the {@code .asd} and the clause.
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
		return LispTrees.rebuildSpine(form, node -> resolvedReadEvalNode(asd, context, node),
				car -> resolveReadEval(asd, context, car));
	}

	/**
	 * What a node {@link #resolveReadEval} does not walk into becomes -- an atom stays, a
	 * marker is its value -- or {@code null} for an ordinary cell.
	 */
	private static @Nullable LispVal resolvedReadEvalNode(AsdContext asd, String context, LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (isReadEvalMarker(cons)) {
			List<LispVal> items = cons.toList();
			if (items.size() != 2) {
				throw unresolvableReadEval(asd, context, cons.print(), "malformed read-time-eval marker");
			}
			AsdLoad load = asd.load();
			if (load == null) {
				throw unresolvableReadEval(asd, context, items.get(1).print(), "no .asd is being loaded");
			}
			try {
				return load.readEval(items.get(1));
			}
			catch (RuntimeException ex) {
				throw unresolvableReadEval(asd, context, items.get(1).print(),
						ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
			}
		}
		if (isUnreadableReadEvalMarker(cons)) {
			// The lexer could not even re-lex the datum, so there is nothing to evaluate
			// -- only the raw source text it carried here for this message.
			throw unresolvableReadEval(asd, context, unreadableReadEvalText(cons), "the datum could not be read");
		}
		return null;
	}

	private static IllegalStateException unresolvableReadEval(AsdContext asd, String context, String datum,
			String reason) {
		return new IllegalStateException(asd.prefix() + context + ": cannot resolve the #. read-time-eval form " + datum
				+ " (" + reason + "), and this clause decides what gets loaded");
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
		return parseDefsystem(form, baseDir, givenFeatures, List.of(), new ComponentClasses(), AsdContext.NONE);
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
	 * <p>
	 * {@code :version} is on this list even though {@code asdf:component-version} reads
	 * it back: what is recorded is the value AS WRITTEN when it is a plain string, and
	 * every other spelling answers nil. Resolving its markers instead would open a README
	 * at parse time to fill a field a User-Agent string prints -- the {@code #.} rule is
	 * that the consumer decides, and this consumer decides not to.
	 */
	private static final Set<String> IGNORED_OPTIONS = Set.of(":NAME", ":LONG-NAME", ":DESCRIPTION",
			":LONG-DESCRIPTION", ":VERSION", ":AUTHOR", ":MAINTAINER", ":LICENSE", ":LICENCE", ":HOMEPAGE",
			":BUG-TRACKER", ":SOURCE-CONTROL", ":MAILTO", ":IN-ORDER-TO", ":PERFORM");

	/**
	 * Parses a {@code defsystem} form with the component classes in scope in the
	 * enclosing {@code .asd} ({@link ComponentClasses}): ASDF's own plus whatever a
	 * tolerated top-level {@code defclass} declared. A component names one as its type,
	 * or takes the system's {@code :default-component-class}, and the class decides
	 * whether the component contributes a source file and with which extension.
	 * @param form the {@code defsystem} form
	 * @param baseDir the directory the component files resolve against, or {@code null}
	 * for working-directory-relative
	 * @param givenFeatures the features the {@code :if-feature} component option tests
	 * @param pushedFeatures the feature names the enclosing {@code .asd} pushed onto
	 * {@code *features*} before this form, merged into the system's declared features
	 * ahead of its own {@code :rontolisp-features}, so both spellings of "this system
	 * holds these features" travel one path
	 * @param componentClasses the component classes in scope in the enclosing
	 * {@code .asd}
	 * @param asd the enclosing {@code .asd} file's path and open load, whose image a
	 * load-bearing option's {@code #.} marker is evaluated in
	 * @return the parsed system
	 */
	private static LispSystem parseDefsystem(LispVal form, @Nullable String baseDir, Features givenFeatures,
			List<String> pushedFeatures, ComponentClasses componentClasses, AsdContext asd) {
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
		// The dependency lists are read BEFORE the option loop for the same reason: a
		// dependency ANNOUNCES its features to this system, and the announcement has to
		// hold while the rest of this definition and the component files are read.
		// :defsystem-depends-on is the one real ASDF loads while the .asd is READ; a
		// plain :depends-on is loaded before this system's files are compiled, so it
		// shows them its pushes too. Only a BUILT-IN system announces anything here (its
		// features are a static table, and the defsystem parse itself evaluates
		// nothing), which is exactly the trivial-features case the option exists for.
		Features preFeatures = declaredFeatures.isEmpty() ? givenFeatures : givenFeatures.with(declaredFeatures);
		List<String> defsystemDependsOn = dependencyNames(":DEFSYSTEM-DEPENDS-ON", name, items, asd, preFeatures);
		List<String> announcers = new ArrayList<>(defsystemDependsOn);
		announcers.addAll(dependencyNames(":DEPENDS-ON", name, items, asd, preFeatures));
		declaredFeatures = mergeFeatureNames(declaredFeatures,
				BuiltinSystems.declaredFeatures(announcers, givenFeatures));
		Features features = declaredFeatures.isEmpty() ? givenFeatures : givenFeatures.with(declaredFeatures);
		List<String> dependsOn = new ArrayList<>();
		String version = null;
		boolean serial = false;
		boolean packageInferred = false;
		LispVal components = null;
		String pathname = null;
		ComponentClass defaultComponentClass = null;
		TestOp testOp = null;
		List<String> testOpEdges = new ArrayList<>();
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
				// Metadata: accepted for .asd compatibility, not recorded anywhere.
				case ":NAME", ":LONG-NAME", ":DESCRIPTION", ":LONG-DESCRIPTION", ":AUTHOR", ":MAINTAINER", ":LICENSE",
						":LICENCE", ":HOMEPAGE", ":BUG-TRACKER", ":SOURCE-CONTROL", ":MAILTO" ->
					{
					}
				// The one metadata option something reads back: asdf:component-version.
				// It stays in IGNORED_OPTIONS -- nothing is evaluated to find the value,
				// so a plain string literal is recorded and every other spelling (ASDF's
				// (:read-file-form "version.sexp") indirection, an unresolved #. marker)
				// answers nil, silently, as it always did.
				case ":VERSION" -> {
					if (value instanceof LispString versionString) {
						version = versionString.value();
					}
				}
				// Already consumed by defsystemDependsOn above.
				case ":DEFSYSTEM-DEPENDS-ON" -> {
				}
				// Test-op wiring: the ONE op with machinery behind it
				// (asdf:test-system). The test-op shapes are RECORDED -- a
				// :perform (test-op (o c) BODY) body and the :in-order-to
				// ((test-op (test-op ...))) chain -- and every other op stays
				// tolerated-and-ignored. Still in IGNORED_OPTIONS: a #. marker inside is
				// never resolved and never complained about at parse time (nothing here
				// resolves the recorded data; an unresolvable marker surfaces only if
				// asdf:test-system actually runs that body).
				case ":PERFORM" -> {
					TestOp parsed = parseTestOpPerform(value);
					if (parsed != null) {
						testOp = parsed;
					}
				}
				case ":IN-ORDER-TO" -> testOpEdges.addAll(parseTestOpEdges(value, features));
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
					if (!LispNames.PACKAGE_INFERRED_SYSTEM.equals(
							memberName(classDesignator(LispNames.ASDF_DEFSYSTEM + " " + name + " :class", value)))) {
						throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name + ": unsupported :class "
								+ value.print() + " (the only supported class is :package-inferred-system)");
					}
					packageInferred = true;
				}
				// :default-component-class is the class of every (:file ...) entry below
				// it -- portableaserve says cl-source-file.cl and then writes
				// (:file "main"), meaning main.cl. Real ASDF falls back to it in
				// class-for-type at exactly that one spot, so an entry that names its own
				// class keeps it.
				case ":DEFAULT-COMPONENT-CLASS" ->
					defaultComponentClass = defaultComponentClass(LispNames.ASDF_DEFSYSTEM + " " + name,
							componentClasses, value);
				// Already consumed by declaredFeatures above.
				case ":RONTOLISP-FEATURES" -> {
				}
				default ->
					throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name + ": unsupported option "
							+ key.name() + " (supported: :name :long-name :description :long-description"
							+ " :version :author :maintainer :license :depends-on :defsystem-depends-on :serial"
							+ " :components :pathname :class :default-component-class" + " :rontolisp-features)");
			}
		}
		String prefix = pathname == null || pathname.isEmpty() ? "" : pathname + "/";
		if (packageInferred && components != null) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
					+ ": a :package-inferred-system has no :components (its graph is derived from each file's"
					+ " defpackage)");
		}
		List<String> files = components == null ? List.of()
				: orderComponents(name, components, serial, prefix, features, componentClasses, defaultComponentClass);
		// A package-inferred system's own :pathname is where its SUB-SYSTEM names
		// resolve:
		// array-operations says :pathname "src/" and then names array-operations/all.
		return new LispSystem(name, List.copyOf(dependsOn), files, baseDir == null ? "" : baseDir, declaredFeatures,
				packageInferred ? (pathname == null ? "" : pathname) : null, packageInferred, testOp,
				List.copyOf(testOpEdges), defsystemDependsOn, version);
	}

	/**
	 * Parses a {@code :perform} value when it wires {@code test-op}:
	 * {@code (test-op (o c) BODY...)}. Any other shape -- another operation, a malformed
	 * clause -- answers {@code null} and stays tolerated-and-ignored (the closed-world
	 * rule of {@link #IGNORED_OPTIONS}: nothing here may complain). The body's bare
	 * {@code asdf}/{@code uiop} member symbols are pre-qualified
	 * ({@link #normalizeAsdUserForm}); the parameter symbols are kept as written.
	 * @param value the {@code :perform} option value
	 * @return the recorded test-op, or {@code null}
	 */
	@Nullable private static TestOp parseTestOpPerform(LispVal value) {
		if (!(value instanceof LispCons cons) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.size() < 2 || !(items.get(0) instanceof LispSymbol op) || !LispNames.TEST_OP.equals(memberName(op))) {
			return null;
		}
		if (!(items.get(1) instanceof LispCons paramsCons) || !paramsCons.isProperList()) {
			return null;
		}
		List<LispVal> params = paramsCons.toList();
		Set<String> bound = new java.util.HashSet<>();
		for (LispVal param : params) {
			if (!(param instanceof LispSymbol paramSym)) {
				return null;
			}
			bound.add(paramSym.name());
		}
		List<LispVal> body = new ArrayList<>();
		for (LispVal form : items.subList(2, items.size())) {
			if (containsReadEvalMarker(form)) {
				// A #. inside the body (the seven *-test.asd (intern #.(string ...))
				// files): the IGNORED_OPTIONS rule says a marker here is never resolved
				// and never complained about, and a recorded marker would fail the
				// eager compile of the emitted test-op defun -- so the whole clause
				// stays tolerated-and-ignored, as it always was.
				return null;
			}
			body.add(normalizeAsdUserForm(form, bound));
		}
		return new TestOp(List.copyOf(params), List.copyOf(body));
	}

	/**
	 * The first {@code (%read-eval-unreadable "RAW")} marker in {@code form}, or
	 * {@code null}: a {@code #.} the tolerant read could not re-lex, which a form about
	 * to RUN cannot hold -- CL's read of it would have failed.
	 */
	@Nullable private static LispCons unreadableReadEval(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return null;
		}
		if (isUnreadableReadEvalMarker(cons)) {
			return cons;
		}
		for (LispVal cell = cons; cell instanceof LispCons link; cell = link.cdr()) {
			LispCons found = unreadableReadEval(link.car());
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static boolean containsReadEvalMarker(LispVal form) {
		while (form instanceof LispCons cons) {
			if (containsReadEvalMarker(cons.car())) {
				return true;
			}
			form = cons.cdr();
		}
		if (form instanceof LispSymbol sym) {
			return LispNames.READ_EVAL.equals(sym.name()) || LispNames.READ_EVAL_UNREADABLE.equals(sym.name());
		}
		return false;
	}

	/**
	 * Parses an {@code :in-order-to} value for its {@code test-op} chain:
	 * {@code ((test-op (test-op "x/tests")) ...)} answers the chained system names in
	 * order. Any other operation or shape contributes nothing (tolerated-and-ignored,
	 * like {@link #parseTestOpPerform}).
	 * @param value the {@code :in-order-to} option value
	 * @param features the feature set a {@code (:feature ...)} guard would test (none is
	 * expected here; a designator that fails to parse is skipped)
	 * @return the chained test-op system names, possibly empty
	 */
	private static List<String> parseTestOpEdges(LispVal value, Features features) {
		if (!(value instanceof LispCons cons) || !cons.isProperList()) {
			return List.of();
		}
		List<String> edges = new ArrayList<>();
		for (LispVal clause : cons.toList()) {
			if (!(clause instanceof LispCons clauseCons) || !clauseCons.isProperList()) {
				continue;
			}
			List<LispVal> items = clauseCons.toList();
			if (items.size() < 2 || !(items.get(0) instanceof LispSymbol op)
					|| !LispNames.TEST_OP.equals(memberName(op))) {
				continue;
			}
			for (LispVal spec : items.subList(1, items.size())) {
				if (!(spec instanceof LispCons specCons) || !specCons.isProperList()) {
					continue;
				}
				List<LispVal> specItems = specCons.toList();
				if (specItems.isEmpty() || !(specItems.get(0) instanceof LispSymbol specOp)
						|| !LispNames.TEST_OP.equals(memberName(specOp))) {
					continue;
				}
				for (LispVal dep : specItems.subList(1, specItems.size())) {
					try {
						edges.add(designator(LispNames.ASDF_DEFSYSTEM + " :in-order-to", dep));
					}
					catch (RuntimeException ex) {
						// Tolerated-and-ignored, like the rest of the option.
					}
				}
			}
		}
		return List.copyOf(edges);
	}

	/**
	 * Qualifies the bare {@code asdf}/{@code uiop} member symbols of a recorded
	 * {@code .asd} form. A real {@code .asd} is read in {@code asdf-user} (which uses
	 * {@code cl}, {@code asdf} and {@code uiop}), so {@code symbol-call} in a
	 * {@code :perform} body names {@code uiop:symbol-call}; this parse reads the form as
	 * plain data with no package context, so the same resolution is applied here, once,
	 * where the form is recorded. Bare {@code cl} names need nothing (the canonical
	 * shape), symbols the {@code BODY} binds itself ({@code bound}) are left alone, and
	 * anything else stays as written.
	 * @param form the recorded form
	 * @param bound the parameter names bound around the form
	 * @return the form with {@code asdf}/{@code uiop} members qualified
	 */
	/**
	 * The {@code asdf} externals a bare symbol in a recorded {@code .asd} form may name:
	 * the runtime FUNCTIONS (a bare class-name symbol is far more likely a variable, so
	 * class names are deliberately absent).
	 */
	private static final Set<String> ASDF_USER_FUNCTION_MEMBERS = Set.of(LispNames.TEST_SYSTEM, LispNames.LOAD_SYSTEM,
			LispNames.FIND_SYSTEM, LispNames.SYSTEM_SOURCE_DIRECTORY, LispNames.SYSTEM_RELATIVE_PATHNAME,
			LispNames.COMPONENT_PATHNAME, LispNames.REGISTERED_SYSTEMS, LispNames.COMPONENT_NAME,
			LispNames.COMPONENT_VERSION, LispNames.COMPONENT_CHILDREN, LispNames.COMPONENT_SIDEWAY_DEPENDENCIES,
			LispNames.COMPONENT_PARENT, LispNames.COMPONENT_SYSTEM);

	static LispVal normalizeAsdUserForm(LispVal form, Set<String> bound) {
		if (form instanceof LispSymbol sym) {
			String symName = sym.name();
			if (sym.isKeyword() || symName.startsWith("#:") || bound.contains(symName)) {
				return form;
			}
			int colon = symName.indexOf(':');
			if (colon > 0) {
				// Written with a uiop-family prefix of its own (uiop:, uiop::, or the
				// home sub-package): only the HOME spelling has a definition behind it,
				// so rewrite to it exactly as PackageResolver does for ordinary source.
				String pkg = symName.substring(0, colon);
				String member = symName.substring(symName.lastIndexOf(':') + 1);
				return am.ik.rontolisp.UiopExports.isUiopFamily(pkg)
						&& am.ik.rontolisp.UiopExports.homePackage(member) != null
								? new LispSymbol(am.ik.rontolisp.UiopExports.qualified(member)) : form;
			}
			if (colon == 0) {
				return form;
			}
			if (am.ik.rontolisp.UiopExports.homePackage(symName) != null) {
				return new LispSymbol(am.ik.rontolisp.UiopExports.qualified(symName));
			}
			if (ASDF_USER_FUNCTION_MEMBERS.contains(symName)) {
				return new LispSymbol(PackageRegistry.qualify(LispNames.ASDF_PKG, symName));
			}
			return form;
		}
		if (form instanceof LispCons) {
			return LispTrees.rebuildSpine(form,
					node -> node instanceof LispCons ? null : normalizeAsdUserForm(node, bound),
					car -> normalizeAsdUserForm(car, bound));
		}
		return form;
	}

	/**
	 * Reads a {@code :class} or {@code :default-component-class} value: a keyword, a
	 * symbol (possibly {@code asdf:}-qualified) or a string. The value is a CLASS name,
	 * so unlike a system designator it keeps its spelling for {@link #memberName} to
	 * strip and upcase.
	 */
	private static LispSymbol classDesignator(String context, LispVal value) {
		if (value instanceof LispSymbol sym) {
			return sym;
		}
		if (value instanceof LispString str) {
			return new LispSymbol(str.value());
		}
		throw new IllegalStateException(
				context + " expects a class name (keyword, symbol or string), got " + value.print());
	}

	/**
	 * Resolves a {@code :default-component-class} value, on a system or on a module,
	 * against the classes the enclosing {@code .asd} has in scope. An unknown class is a
	 * hard error naming it -- the same closed world a component TYPE lives in, and for
	 * the same reason: the class is what decides which file on disk a bare
	 * {@code (:file "name")} means.
	 */
	private static ComponentClass defaultComponentClass(String context, ComponentClasses classes, LispVal value) {
		String option = context + " :default-component-class";
		ComponentClass found = classes.find(memberName(classDesignator(option, value)));
		if (found == null) {
			throw new IllegalStateException(option + " names an unknown component class " + value.print()
					+ " (a component class is one of ASDF's own or one a defclass in the same .asd declares)");
		}
		return found;
	}

	/**
	 * Reads the {@code :rontolisp-features} option's list of feature designators, ahead
	 * of the option loop. rontolisp's own extension, not an ASDF option: it declares
	 * statically what an upstream {@code .asd} pushes onto {@code *features*} from an
	 * {@code eval-when}, for a replacement {@code .asd} that also has to decide the
	 * per-implementation branch upstream takes at run time (postmodern's two). An
	 * upstream {@code .asd}'s own push lands in the same place
	 * ({@link AsdLoad#pushedFeatures}). The declaration is additive only -- there is no
	 * way to turn a feature OFF, since that would let a system claim to be a backend it
	 * is not.
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
	 * Reads a dependency-list option ({@code :depends-on} /
	 * {@code :defsystem-depends-on}) ahead of the option loop, which is what lets a
	 * dependency ANNOUNCE features to this system before its clauses and component files
	 * are read -- the same reason {@link #declaredFeatures(String, List, AsdContext)} is
	 * read early. The entries take the shapes {@link #dependencyName} accepts: a plain
	 * designator, {@code (:feature EXPR DEP)}, {@code (:version DEP "1.2.3")}.
	 * <p>
	 * What a dependency may CONTRIBUTE is narrow and deliberately so: only a BUILT-IN
	 * system announces features ({@code BuiltinSystems.declaredFeatures}), because that
	 * is a static table a parse can read, while a real third-party system announces its
	 * features by RUNNING, and it runs only when the loader loads it, after this parse.
	 * Both options announce, because real ASDF loads a {@code :depends-on} system before
	 * this system's files are compiled and so shows them its pushes too; the only
	 * divergence is that here such an announcement also reaches this definition's OWN
	 * {@code :if-feature} clauses (there is no "load between the clauses" in a one-pass
	 * parse), which is precisely what {@code :defsystem-depends-on} exists to guarantee.
	 * @param option the option keyword, upcased with its leading colon
	 * @param systemName the system being parsed
	 * @param items the {@code defsystem} form's items
	 * @param asd the enclosing {@code .asd} context (for {@code #.} resolution)
	 * @param features the feature set a {@code (:feature ...)} entry tests
	 * @return the dependency names, in order
	 */
	private static List<String> dependencyNames(String option, String systemName, List<LispVal> items, AsdContext asd,
			Features features) {
		String context = LispNames.ASDF_DEFSYSTEM + " " + systemName + " " + lower(option);
		List<String> names = new ArrayList<>();
		for (int i = 2; i + 1 < items.size(); i += 2) {
			if (items.get(i) instanceof LispSymbol key && key.isKeyword() && option.equals(key.name())) {
				for (LispVal dep : properList(context, resolveReadEval(asd, context, items.get(i + 1)))) {
					String depName = dependencyName(systemName, dep, features);
					if (depName != null && !names.contains(depName)) {
						names.add(depName);
					}
				}
			}
		}
		return List.copyOf(names);
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
	 * @throws SystemNotFoundException if no directory holds it
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
				// A .asd whose systems this subset cannot take as written (ironclad's):
				// substitute the bundled replacement declaring the loadable slice,
				// keeping the located path so component files resolve against the real
				// library.
				String replacement = AsdOverrides.replacementSource(fileName);
				return new LocatedAsd(path, replacement != null ? replacement : source);
			}
			catch (IOException ex) {
				tried.add(path);
			}
		}
		throw new SystemNotFoundException(LispNames.ASDF_LOAD_SYSTEM + ": system '" + name + "' not found (tried: "
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
				+ SourceLanguage.COMMON_LISP.defaultExtension();
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
				primary.baseDir(), primary.features(), null, true, null, List.of(), primary.defsystemDependsOn(), null);
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
	 * The package a source FILE declares, read the way real ASDF's
	 * {@code asdf/package-inferred-system::file-defpackage-form} reads it: the first
	 * {@code defpackage} / {@code uiop:define-package} in the file, whatever precedes it
	 * (the common {@code (in-package #:cl-user)} header) skipped, downcased like a system
	 * designator. That correspondence is the point -- in a package-inferred system the
	 * declared package IS the sub-system name, which is how {@code rontolisp test FILE}
	 * (and upstream rove's {@code roswell/rove.ros}) finds the system a test file belongs
	 * to.
	 * @param source the file's source text
	 * @param path the file's path, for error messages
	 * @param features the reader features the file is read with
	 * @return the declared package name, downcased, or {@code null} when the file
	 * declares none
	 */
	@Nullable public static String fileDefpackageName(String source, String path, Features features) {
		LispVal form = LispReader.readFirstFormMatching(source, features, path, AsdfSystems::isPackageDefinitionForm);
		if (!isPackageDefinitionForm(form)) {
			return null;
		}
		List<LispVal> items = ((LispCons) form).toList();
		return items.size() < 2 ? null : packageDesignatorName(path, items.get(1));
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
			Features features, ComponentClasses classes, @Nullable ComponentClass defaultClass) {
		List<Component> components = new ArrayList<>();
		String previous = null;
		for (LispVal entry : properList(LispNames.ASDF_DEFSYSTEM + " " + systemName + " :components", componentsVal)) {
			Component component = parseComponent(systemName, entry, prefix, features, classes, defaultClass);
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

	/**
	 * Parses a component's name (the second element of {@code (:file NAME ...)} etc.): a
	 * string literal stays verbatim, and a symbol ({@code :t}, matching cl-json/test's
	 * {@code (:module :t ...)}) is coerced like a system designator ({@link #symbolName}
	 * -- strip a leading keyword colon, downcase) -- real ASDF runs every component name
	 * through {@code coerce-name}, which accepts any string designator. Anything else is
	 * a hard error naming the entry.
	 */
	private static String componentName(String systemName, LispVal val, LispVal entry) {
		if (val instanceof LispString str) {
			return str.value();
		}
		if (val instanceof LispSymbol sym) {
			return symbolName(sym);
		}
		throw new IllegalStateException(
				"system " + systemName + ": component name must be a string or symbol literal: " + entry.print());
	}

	private static Component parseComponent(String systemName, LispVal entry, String prefix, Features features,
			ComponentClasses classes, @Nullable ComponentClass defaultClass) {
		if (!(entry instanceof LispCons compCons) || !(compCons.car() instanceof LispSymbol type)
				|| !type.isKeyword()) {
			throw new IllegalStateException(
					"system " + systemName + ": each component must be (:file \"name\" ...), (:module \"dir\" ...) or"
							+ " (:static-file \"name\"), got " + entry.print());
		}
		List<LispVal> parts = compCons.toList();
		if (parts.size() < 2) {
			throw new IllegalStateException(
					"system " + systemName + ": component name must be a string or symbol literal: " + entry.print());
		}
		String name = componentName(systemName, parts.get(1), entry);
		if ((parts.size() - 2) % 2 != 0) {
			throw new IllegalStateException(
					"system " + systemName + ": component " + name + " expects :option value pairs: " + entry.print());
		}
		List<String> dependsOn = new ArrayList<>();
		boolean moduleSerial = false;
		boolean featureEnabled = true;
		LispVal nested = null;
		String pathname = null;
		ComponentClass moduleDefaultClass = null;
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
				// A module may re-point the default class for its own subtree; ASDF
				// walks up the parents from the component, so an inner module that says
				// nothing keeps the enclosing one.
				case ":DEFAULT-COMPONENT-CLASS" -> {
					if (!module) {
						throw unsupportedComponentOption(systemName, type, name, key);
					}
					moduleDefaultClass = defaultComponentClass("system " + systemName + ": module " + name, classes,
							value);
				}
				default -> throw unsupportedComponentOption(systemName, type, name, key);
			}
		}
		List<String> files;
		if (":MODULE".equals(type.name())) {
			if (nested == null) {
				throw new IllegalStateException(
						"system " + systemName + ": module " + name + " expects a :components option");
			}
			// An empty :pathname is ASDF's "this module adds no directory level".
			String dir = pathname == null ? name : pathname;
			files = orderComponents(systemName, nested, moduleSerial, dir.isEmpty() ? prefix : prefix + dir + "/",
					features, classes, moduleDefaultClass == null ? defaultClass : moduleDefaultClass);
		}
		else {
			// (:file "x") means the enclosing :default-component-class, ASDF's
			// cl-source-file when there is none; every other type NAMES its class, be it
			// one of ASDF's own (:static-file, :doc-file, :cl-source-file.cl) or one a
			// defclass in the same .asd declared (chipz's :txt-file, portableaserve's
			// :legacy-acl-source-file). An ordering-only class contributes no source.
			ComponentClass componentClass;
			if (":FILE".equals(type.name())) {
				componentClass = defaultClass == null ? DEFAULT_COMPONENT_CLASS : defaultClass;
			}
			else {
				componentClass = classes.find(type.name().substring(1));
				if (componentClass == null) {
					throw new IllegalStateException("system " + systemName + ": unsupported component type "
							+ type.name() + " (supported: :file, :module, ASDF's own component classes and the"
							+ " classes a defclass in the same .asd declares)");
				}
			}
			files = componentClass.source()
					? List.of(prefix + sourceFileName(name, pathname, componentClass.fileType())) : List.of();
		}
		// A feature-disabled component keeps its place in the dependency graph (a
		// sibling may :depends-on it) but contributes no source files.
		return new Component(name, dependsOn, featureEnabled ? files : List.of());
	}

	/**
	 * The file a source component names: its {@code :pathname} when it has one --
	 * verbatim if that namestring already carries an extension, which is how a component
	 * points at a file whose name differs from its own -- and otherwise the component
	 * name plus its CLASS's extension.
	 */
	private static String sourceFileName(String name, @Nullable String pathname, String fileType) {
		if (pathname == null) {
			return name + "." + fileType;
		}
		return pathname.indexOf('.') < 0 ? pathname + "." + fileType : pathname;
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
