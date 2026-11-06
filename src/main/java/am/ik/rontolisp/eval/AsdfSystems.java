package am.ik.rontolisp.eval;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import am.ik.rontolisp.AsdfExports;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
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
 * this system's read), {@code :weakly-depends-on} (the ones of them a loader finds,
 * {@link #resolveWeakDependencies}), {@code :around-compile} (a function called around a
 * component's load), {@code :encoding} ({@code :utf-8} or {@code :default}, what source
 * is read as), {@code :serial} (each component implicitly depends on the previous one),
 * {@code :pathname} (a path prefix for every component), and {@code :components} with
 * {@code (:file "name" [:depends-on (...)])}, {@code (:module "dir" :components (...))}
 * (a path prefix) and {@code (:static-file "name")} (ignored) entries. A component's
 * CLASS is ASDF's own, one a top-level {@code defclass} in the same {@code .asd} declares
 * -- for those the parse reads whether the component contributes a source file and which
 * extension its name gets -- or one loaded code defines, which the image answers for
 * ({@link LiveClass}) and the program instantiates ({@link LoadedClasses}).
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
	 * @param version the {@code :version} value as {@link #declaredVersion} reads it,
	 * {@code null} when there is none or it does not parse. Read back by
	 * {@code asdf:component-version}
	 * @param weaklyDependsOn the {@code :weakly-depends-on} names no loader has looked
	 * for yet ({@link #resolveWeakDependencies} moves the ones it finds to
	 * {@link #dependsOn})
	 * @param aroundCompile the {@code :around-compile} hook of each entry of
	 * {@link #files} that has one, in what {@code LispEvaluator.callAroundCompile} takes:
	 * a {@code .asd}'s lambda already a function of the image that loaded the file, and
	 * its symbols homed in the file's package
	 * @param metadata what the definition declared and no loader reads
	 * @param loadedClasses the classes of this system and its components that loaded code
	 * defines
	 * @param tree the components as the definition writes them
	 */
	public record LispSystem(String name, List<String> dependsOn, List<String> files, String baseDir,
			List<String> features, @Nullable String packageInferredDir, boolean packageInferredClass,
			@Nullable TestOp testOp, List<String> testOpEdges, List<String> defsystemDependsOn,
			@Nullable String version, List<String> weaklyDependsOn, Map<String, LispVal> aroundCompile,
			Metadata metadata, LoadedClasses loadedClasses, ComponentTree tree) {

		/**
		 * What {@code asdf:component-pathname} answers for the system: its directory
		 * below the {@code .asd}'s, by its {@code :pathname}.
		 * @return the directory namestring, with a trailing slash
		 */
		public String pathname() {
			return resolved(this.tree.root());
		}

		/**
		 * Resolves a {@link ComponentNode#path} against the {@code .asd}'s directory.
		 * @param path the path below it
		 * @return what {@code asdf:component-pathname} answers: a directory keeps its
		 * trailing slash
		 */
		String resolved(String path) {
			if (path.isEmpty()) {
				return AsdfRuntimeLibrary.normalizeDir(this.baseDir);
			}
			String resolved = SourceLoader.resolve(this.baseDir, path);
			return path.endsWith("/") ? AsdfRuntimeLibrary.normalizeDir(resolved) : resolved;
		}
	}

	/**
	 * A system's components as its definition writes them, which is what a program walks;
	 * the loaders read the plan.
	 *
	 * @param root the system's {@code :pathname} directory below the {@code .asd}'s,
	 * empty or with a trailing slash
	 * @param children the system's children, in definition order
	 * @param plan the nodes of {@link LispSystem#files}, one each in the same order:
	 * every source file, in plan order
	 */
	public record ComponentTree(String root, List<ComponentNode> children, List<ComponentNode> plan) {
	}

	/**
	 * One component below a system.
	 *
	 * @param kind ASDF's class of the instance, as an upper-case member name of
	 * {@code asdf}, when no loaded code defines it ({@link #loaded})
	 * @param name the name, as real ASDF's {@code coerce-name} makes it
	 * @param written the {@code :pathname} as written, or {@code null}
	 * @param relative what {@code asdf:component-relative-pathname} answers: the path
	 * below the parent's, a directory's with a trailing slash
	 * @param path the path below the {@code .asd}'s directory, a directory's empty or
	 * with a trailing slash
	 * @param fileType the {@code type} slot of an instance whose class's says something
	 * else, or {@code null}
	 * @param sideway the sibling names it depends on, a {@code :serial} predecessor first
	 * @param ifFeature the {@code :if-feature} as written, or {@code null}
	 * @param enabled whether that feature expression held in the parse: a disabled
	 * component is no source of the plan, and {@code sub-components} skips it
	 * @param loaded the class loaded code defines for it, or {@code null}
	 * @param children a module's children, in definition order
	 */
	public record ComponentNode(String kind, String name, @Nullable String written, String relative, String path,
			@Nullable String fileType, List<String> sideway, @Nullable LispVal ifFeature, boolean enabled,
			@Nullable LoadedClass loaded, List<ComponentNode> children) {
	}

	/**
	 * What of a definition loaded code decides: the plan a program is built from. The
	 * image that loaded the classes ({@link ClassResolver}) answers these names; the
	 * instances are made where the class's methods act, in the program that loaded the
	 * same code ({@code AsdfRuntimeLibrary}).
	 *
	 * @param system the name of the system's class, or {@code null} for ASDF's own
	 * @param components the class of each entry of {@link LispSystem#files} that has one
	 */
	public record LoadedClasses(@Nullable String system, Map<String, LoadedClass> components) {

		static final LoadedClasses NONE = new LoadedClasses(null, Map.of());

	}

	/**
	 * The class of one component file, as a loader takes it.
	 *
	 * @param name the class name, as its symbol is spelled in the program
	 * @param lispSource whether the file is Lisp a loader reads: a {@code cl-source-file}
	 * of the class. Any other is the class's methods' business and no loader opens it
	 * ({@code lass-file})
	 * @param deferred whether {@code load-op} leaves the file unread: the class has a
	 * primary method of its own there, so its forms run when something asks
	 * ({@code prove-asdf}'s {@code test-file}), never as part of loading the system
	 * @param initargs the options of the component the parse does not read, in pairs as
	 * written: real ASDF hands them to {@code make-instance} ({@code lass-file}'s
	 * {@code :output})
	 */
	public record LoadedClass(String name, boolean lispSource, boolean deferred, List<LispVal> initargs) {
	}

	/**
	 * What an image answers about a class a definition names: real ASDF's
	 * {@code class-for-type} over the classes live in the image.
	 *
	 * @param name the name of the class the program instantiates: the class itself, or
	 * the nearest superclass when the {@code .asd} itself defined it, since the program
	 * has no copy of a {@code .asd}'s forms
	 * @param plain whether that class is the one a definition saying nothing gets
	 * ({@code system}, {@code package-inferred-system}, {@code cl-source-file}), so no
	 * instance needs its name
	 * @param system whether the class is a {@code system}
	 * @param packageInferred whether it is a {@code package-inferred-system}
	 * @param module whether it is a {@code module}
	 * @param file whether an instance names a file
	 * @param lispSource whether it is a {@code cl-source-file}
	 * @param deferred whether {@code load-op} leaves its file unread
	 * ({@link LoadedClass#deferred})
	 * @param fileType what {@code source-file-type} answers for an instance, or
	 * {@code null}
	 * @param defaultComponentClass the class of a {@code :file} below an instance, or
	 * {@code null}
	 * @param aroundCompile what {@code :around-compile} holds for an instance when the
	 * definition says nothing, or {@code null}
	 */
	public record LiveClass(String name, boolean plain, boolean system, boolean packageInferred, boolean module,
			boolean file, boolean lispSource, boolean deferred, @Nullable String fileType,
			@Nullable LispVal defaultComponentClass, @Nullable LispVal aroundCompile) {
	}

	/**
	 * Resolves a class designator of a definition in an image. Real ASDF loads
	 * {@code :defsystem-depends-on} while it reads the definition, and only then looks
	 * the class up -- so a class the dependency defines in its source is found.
	 */
	@FunctionalInterface
	public interface ClassResolver {

		/**
		 * @param designator the {@code :class}, component type keyword or
		 * {@code :default-component-class} as written: a keyword finds the symbol in
		 * {@code asdf} or the current package, a string is read in {@code asdf}, any
		 * other symbol names the class
		 * @param definers the {@code :defsystem-depends-on} of the definition, which are
		 * loaded first
		 * @param ownClasses the member names of the classes the {@code .asd} itself
		 * defined, which only the image has
		 * @return the class, or {@code null} when no such component class exists
		 */
		@Nullable LiveClass resolve(LispVal designator, List<String> definers, Set<String> ownClasses);

		/** Knows no class: the parse of a form no image is behind. */
		ClassResolver NONE = (designator, definers, ownClasses) -> null;

	}

	/**
	 * What a definition declared for {@code asdf:system-description} and its fellows to
	 * read back: the {@code .asd} it came from, and each option written as a literal, by
	 * keyword spelling and in the order written. A {@code #.} marker in these options is
	 * never evaluated ({@link #IGNORED_OPTIONS}), so such an option is simply absent.
	 *
	 * @param sourceFile the path of the defining {@code .asd}, or {@code null} for an
	 * inline {@code defsystem}
	 * @param options the recorded option values
	 */
	public record Metadata(@Nullable String sourceFile, Map<String, LispVal> options) {

		static final Metadata NONE = new Metadata(null, Map.of());

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

		private final String detail;

		SystemNotFoundException(String message, String detail) {
			super(message);
			this.detail = detail;
		}

		/**
		 * Where the search looked and how to widen it, for a report that names the system
		 * itself.
		 * @return the text
		 */
		public String detail() {
			return this.detail;
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
	 * The systems a top-level form that is one more way of saying {@code load-system}
	 * asks for: {@code (asdf:oos 'asdf:load-op NAME)} and {@code asdf:operate} with
	 * {@code load-op} or {@code load-source-op}, {@code asdf:load-systems},
	 * {@code asdf:load-systems*} over a quoted list, and {@code asdf:require-system}. The
	 * compile path consumes these like {@link #loadSystemName}, with the same rule for a
	 * name that is not a literal.
	 * @param form the form to inspect
	 * @return the literal system names, or {@code null} for any other form
	 */
	@Nullable public static List<String> loadSystemsNames(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (isAsdfMember(op, "OOS") || isAsdfMember(op, LispNames.OPERATE)) {
			if (items.size() < 3) {
				return null;
			}
			String operation = quotedOperation(items.get(1));
			if (!LispNames.LOAD_OP.equals(operation) && !"LOAD-SOURCE-OP".equals(operation)) {
				return null;
			}
			return List.of(designator(LispNames.ASDF_LOAD_SYSTEM, items.get(2)));
		}
		if (isAsdfMember(op, "REQUIRE-SYSTEM") && items.size() >= 2) {
			return List.of(designator(LispNames.ASDF_LOAD_SYSTEM, items.get(1)));
		}
		if (isAsdfMember(op, "LOAD-SYSTEMS")) {
			List<String> names = new ArrayList<>();
			for (LispVal item : items.subList(1, items.size())) {
				names.add(designator(LispNames.ASDF_LOAD_SYSTEM, item));
			}
			return names;
		}
		if (isAsdfMember(op, "LOAD-SYSTEMS*") && items.size() >= 2 && items.get(1) instanceof LispCons quoted
				&& quoted.car() instanceof LispSymbol quote && LispNames.QUOTE.equals(quote.name())
				&& quoted.cdr() instanceof LispCons datum && datum.car() instanceof LispCons list
				&& list.isProperList()) {
			List<String> names = new ArrayList<>();
			for (LispVal item : list.toList()) {
				names.add(designator(LispNames.ASDF_LOAD_SYSTEM, item));
			}
			return names;
		}
		return null;
	}

	/**
	 * The member name of a quoted operation designator, {@code 'asdf:load-op} in any
	 * package spelling of ASDF's; {@code null} for anything else.
	 */
	@Nullable private static String quotedOperation(LispVal form) {
		if (form instanceof LispCons quoted && quoted.car() instanceof LispSymbol quote
				&& LispNames.QUOTE.equals(quote.name()) && quoted.cdr() instanceof LispCons datum
				&& datum.car() instanceof LispSymbol operation) {
			return memberName(operation);
		}
		return null;
	}

	/**
	 * If {@code form} is a top-level literal {@code (asdf:test-system NAME)} call, or
	 * {@code asdf:oos}/{@code asdf:operate} with {@code test-op}, returns the system
	 * name; otherwise {@code null}. Like {@link #loadSystemName(LispVal)}, a non-literal
	 * name is a hard error at inline time: the compile path must splice the system (and
	 * its test-op chain) to have anything to run.
	 * @param form the form to inspect
	 * @return the literal system name, or {@code null}
	 */
	@Nullable public static String testSystemName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)) {
			return null;
		}
		if ((isAsdfMember(op, "OOS") || isAsdfMember(op, LispNames.OPERATE)) && cons.isProperList()) {
			List<LispVal> items = cons.toList();
			return items.size() >= 3 && LispNames.TEST_OP.equals(quotedOperation(items.get(1)))
					? designator(LispNames.ASDF_TEST_SYSTEM, items.get(2)) : null;
		}
		if (!isAsdfMember(op, LispNames.TEST_SYSTEM)) {
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
	 * If {@code form} is a top-level literal {@code (asdf:make NAME)}, returns the name:
	 * the compile path splices the system and keeps the call, which decides at run time
	 * what the system's {@code :build-operation} asks for.
	 * @param form the form to inspect
	 * @return the literal system name, or {@code null}
	 */
	@Nullable public static String makeSystemName(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !isAsdfMember(op, "MAKE")) {
			return null;
		}
		List<LispVal> items = cons.toList();
		return items.size() < 2 ? null : designator(LispNames.ASDF_LOAD_SYSTEM, items.get(1));
	}

	/**
	 * If {@code form} is a top-level {@code (asdf:load-asd PATH ...)}, returns the path
	 * form.
	 * @param form the form to inspect
	 * @return the path form, or {@code null}
	 */
	@Nullable public static LispVal loadAsdPath(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op && isAsdfMember(op, "LOAD-ASD")
				&& cons.cdr() instanceof LispCons rest ? rest.car() : null;
	}

	/**
	 * If {@code form} is a top-level {@code (push DIR asdf:*central-registry*)} or
	 * {@code (pushnew DIR asdf:*central-registry* ...)}, returns the {@code DIR} form:
	 * what a compile path can read of the variable.
	 * @param form the form to inspect
	 * @return the directory form, or {@code null}
	 */
	@Nullable public static LispVal centralRegistryPush(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return null;
		}
		String operator = memberName(op);
		if (!LispNames.PUSH.equals(operator) && !LispNames.PUSHNEW.equals(operator)) {
			return null;
		}
		List<LispVal> items = cons.toList();
		return items.size() >= 3 && items.get(2) instanceof LispSymbol place
				&& isAsdfMember(place, "*CENTRAL-REGISTRY*") ? items.get(1) : null;
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
	 * One open {@code .asd} load ({@link AsdImage#open}). As a {@link ClassResolver} it
	 * answers from the image the file runs in, reading a keyword in the file's package.
	 */
	public interface AsdLoad extends AutoCloseable, ClassResolver {

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
	 * rather than computed by them. A class loaded code defines is another matter: its
	 * methods are in the program too ({@link LoadedClasses}).</li>
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
				if (items.size() >= 2 && items.get(1) instanceof LispSymbol name) {
					this.componentClasses.noteEvaluated(memberName(name));
				}
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
					this.componentClasses, new AsdContext(this.asdPath, open, open));
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
				List.copyOf(merged), system.version(), system.weaklyDependsOn(), system.aroundCompile(),
				system.metadata(), system.loadedClasses(), system.tree());
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
	 * A component CLASS, as much of one as the plan reads. For ASDF's own classes and the
	 * ones a {@code .asd} declares, only two things a real subclass decides ever reach
	 * here: whether an instance contributes a SOURCE file at all ({@code cl-source-file}
	 * and its subclasses do; {@code static-file} / {@code doc-file} and theirs are
	 * ordering-only) and which file EXTENSION the component's name gets (ASDF's
	 * {@code source-file-type}). Everything else the upstream subclasses in the wild
	 * exist for -- muffling non-style warnings around {@code compile-op}, mostly -- is
	 * about compile-file warnings, which do not exist here at all, so ignoring it is
	 * exact rather than approximate. A class loaded code defines also names the class the
	 * program instantiates, and may say what its instances' {@code :around-compile} is.
	 *
	 * @param source whether a component of this class contributes its file
	 * @param fileType the extension a component of this class gets, without the dot
	 * (empty for an ordering-only class), or {@code null} when it adds none
	 * @param module whether a component of this class holds components
	 * @param loaded what the loaders take from a class loaded code defines, or
	 * {@code null}
	 * @param aroundCompile what {@code :around-compile} holds for an instance when the
	 * definition says nothing, or {@code null}
	 * @param slotType what an instance's {@code type} slot holds, the slot's initform:
	 * {@code fileType} unless a {@code source-file-type} method set the extension, or
	 * {@code null} when the slot is nil or the class is a loaded one
	 */
	private record ComponentClass(boolean source, @Nullable String fileType, boolean module,
			@Nullable LoadedClass loaded, @Nullable LispVal aroundCompile, String kind, @Nullable String slotType) {

		ComponentClass(boolean source, String fileType, @Nullable String slotType, String kind) {
			this(source, fileType, false, null, null, kind, slotType);
		}

		/** A class whose extension is its {@code type} slot. */
		ComponentClass(boolean source, String fileType, String kind) {
			this(source, fileType, fileType.isEmpty() ? null : fileType, kind);
		}

		/** The class with its extension set by a {@code source-file-type} method. */
		ComponentClass withFileType(String type) {
			return new ComponentClass(this.source, type, this.module, this.loaded, this.aroundCompile, this.kind,
					this.slotType);
		}

		static ComponentClass of(LiveClass live) {
			LoadedClass loaded = live.plain() ? null
					: new LoadedClass(live.name(), live.lispSource(), live.deferred(), List.of());
			return new ComponentClass(live.file(), live.fileType(), live.module(), loaded, live.aroundCompile(),
					CL_SOURCE_FILE, null);
		}

	}

	private static final String CL_SOURCE_FILE = "CL-SOURCE-FILE";

	private static final String MODULE = "MODULE";

	/**
	 * The class {@code (:file "name")} means when no {@code :default-component-class}
	 * does -- ASDF's {@code *default-component-class*}.
	 */
	private static final ComponentClass DEFAULT_COMPONENT_CLASS = new ComponentClass(true, "lisp", CL_SOURCE_FILE);

	/**
	 * ASDF's own component classes: usable as a component type and as a superclass
	 * without a local {@code defclass}. {@code cl-source-file.cl} and
	 * {@code cl-source-file.lsp} are ASDF's two "my sources are not named .lisp" classes
	 * -- portableaserve's {@code :default-component-class cl-source-file.cl} is exactly
	 * what they exist for.
	 */
	private static final Map<String, ComponentClass> BUILTIN_COMPONENT_CLASSES = Map.of(CL_SOURCE_FILE,
			DEFAULT_COMPONENT_CLASS, "CL-SOURCE-FILE.CL", new ComponentClass(true, "cl", "CL-SOURCE-FILE.CL"),
			"CL-SOURCE-FILE.LSP", new ComponentClass(true, "lsp", "CL-SOURCE-FILE.LSP"), "STATIC-FILE",
			new ComponentClass(false, "", "STATIC-FILE"), "DOC-FILE", new ComponentClass(false, "", "DOC-FILE"),
			"HTML-FILE", new ComponentClass(false, "html", "HTML-FILE"));

	/** What the {@code type} slot of an instance of each of ASDF's own classes holds. */
	private static @Nullable String classFileType(String kind) {
		return switch (kind) {
			case CL_SOURCE_FILE -> "lisp";
			case "CL-SOURCE-FILE.CL" -> "cl";
			case "CL-SOURCE-FILE.LSP" -> "lsp";
			case "HTML-FILE" -> "html";
			default -> null;
		};
	}

	private static final String SOURCE_FILE_TYPE = "SOURCE-FILE-TYPE";

	/**
	 * The component classes in scope while one {@code .asd} file is loaded: ASDF's own,
	 * the ones the file declared with a top-level {@code defclass}
	 * ({@link #componentClass}), the file-extension overrides its
	 * {@code source-file-type} methods set ({@link #collectSourceFileTypes}), and the
	 * ones the image holds, asked for the first time one is named.
	 */
	private static final class ComponentClasses {

		private final Map<String, ComponentClass> declared = new HashMap<>();

		private final Map<String, String> fileTypeOverrides = new HashMap<>();

		private final Map<String, LiveClass> live = new HashMap<>();

		private final Set<String> evaluated = new HashSet<>();

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
			return override == null ? found : found.withFileType(override);
		}

		void declare(String member, ComponentClass componentClass) {
			this.declared.put(member, componentClass);
		}

		void overrideFileType(String member, String fileType) {
			this.fileTypeOverrides.put(member, fileType);
		}

		/** Notes a class the file's own {@code defclass} defined in the image. */
		void noteEvaluated(String member) {
			this.evaluated.add(member);
		}

		/**
		 * The class the image holds under a designator, or {@code null} when it holds
		 * none. A name a loaded class answers for stays that class: asking again loads
		 * nothing and makes no prototype.
		 */
		@Nullable LiveClass live(LispVal designator, List<String> definers, AsdContext asd) {
			String key = designator.print();
			LiveClass found = this.live.get(key);
			if (found == null) {
				found = asd.classes().resolve(designator, definers, this.evaluated);
				if (found != null) {
					this.live.put(key, found);
				}
			}
			return found;
		}

		/**
		 * The class of a component: what the file itself knows, else what the image does.
		 */
		@Nullable ComponentClass named(LispVal designator, List<String> definers, AsdContext asd) {
			ComponentClass found = find(memberName(classDesignator("component class", designator)));
			if (found != null) {
				return found;
			}
			LiveClass answered = live(designator, definers, asd);
			return answered == null || answered.system() ? null : ComponentClass.of(answered);
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
		String slotType = null;
		String kind = null;
		for (LispVal superClass : supers.toList()) {
			ComponentClass resolved = superClass instanceof LispSymbol superSym ? classes.find(memberName(superSym))
					: null;
			if (resolved == null) {
				return null;
			}
			if (kind == null || resolved.source() && !source) {
				source = resolved.source();
				kind = resolved.kind();
				fileType = java.util.Objects.requireNonNullElse(resolved.fileType(), "");
				slotType = resolved.slotType();
			}
		}
		if (kind == null) {
			return null;
		}
		String initform = items.size() >= 4 ? slotTypeInitform(items.get(3)) : null;
		if (initform == null) {
			return new ComponentClass(source, fileType, slotType, kind);
		}
		// A superclass's source-file-type method applies to this class too, ahead of the
		// slot the default method reads.
		boolean methodSet = !fileType.equals(java.util.Objects.requireNonNullElse(slotType, ""));
		return new ComponentClass(source, methodSet ? fileType : initform, initform, kind);
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
	 * ({@link #NONE}), though its loader may know an image for its classes.
	 *
	 * @param path the {@code .asd} path, or {@code null} when the form is not from one
	 * @param load the open load, or {@code null} when the form is not from one
	 * @param classes the image's answers about a class the definition names
	 */
	private record AsdContext(@Nullable String path, @Nullable AsdLoad load, ClassResolver classes) {

		private static final AsdContext NONE = new AsdContext(null, null, ClassResolver.NONE);

		/** The {@code "<path>: "} prefix every error from this file carries. */
		private String prefix() {
			return this.path == null ? "" : this.path + ": ";
		}
	}

	/**
	 * What ASDF's {@code normalize-version} makes of a {@code :version} value: a string,
	 * or the first form or line of a {@code (:read-file-form PATH :at N)} or
	 * {@code (:read-file-line PATH :at N)}, a number spelled as a string, and nil for
	 * what does not parse as dotted integers. A file that cannot be read answers nil too:
	 * the option is metadata, and an inline definition has no image to read it in.
	 */
	private static @Nullable String declaredVersion(LispVal value, @Nullable String baseDir, AsdContext asd) {
		LispVal version = value;
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol op && asd.load() != null
				&& baseDir != null && cons.cdr() instanceof LispCons args && args.car() instanceof LispString subpath) {
			String reader = switch (op.name()) {
				case ":READ-FILE-FORM" -> "UIOP/STREAM:SAFE-READ-FILE-FORM";
				case ":READ-FILE-LINE" -> "UIOP/STREAM:SAFE-READ-FILE-LINE";
				default -> null;
			};
			if (reader == null) {
				return null;
			}
			try {
				version = asd.load()
					.readEval(new LispCons(new LispSymbol(reader),
							new LispCons(new LispString(baseDir + "/" + subpath.value()), args.cdr())));
			}
			catch (RuntimeException ex) {
				return null;
			}
		}
		String text = switch (version) {
			case LispString string -> string.value();
			case LispInteger integer -> integer.print();
			case LispDouble number -> number.print();
			default -> null;
		};
		return text != null && text.matches("[0-9]+(\\.[0-9]+)*") ? text : null;
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
		return parseDefsystem(form, baseDir, givenFeatures, ClassResolver.NONE);
	}

	/**
	 * Parses a {@code defsystem} form no {@code .asd} holds, a class it names answered by
	 * the image its loader knows.
	 * @param form the {@code defsystem} form
	 * @param baseDir the directory the component files resolve against, or {@code null}
	 * for working-directory-relative
	 * @param givenFeatures the features the {@code :if-feature} component option tests
	 * @param classes what answers about a class the definition names
	 * @return the parsed system
	 */
	public static LispSystem parseDefsystem(LispVal form, @Nullable String baseDir, Features givenFeatures,
			ClassResolver classes) {
		return parseDefsystem(form, baseDir, givenFeatures, List.of(), new ComponentClasses(),
				new AsdContext(null, null, classes));
	}

	/**
	 * The {@code defsystem} options no load depends on: the metadata and the test-op
	 * wiring. A {@code #.} read-time-eval marker inside one of these never fails the
	 * parse; what the option leaves unread may hold anything, so nothing may object to
	 * it. Every OTHER option decides what gets loaded, so it resolves its markers and an
	 * unresolvable one fails the parse ({@link #resolveReadEval}); the default direction
	 * is deliberate, so a load-bearing option added later is covered without being
	 * remembered here.
	 * <p>
	 * {@code :perform} reads the marker in a {@code test-op} body itself
	 * ({@link #parseTestOpPerform}), since that body runs; one that cannot read drops the
	 * clause, as the rest of the option's shapes do.
	 * <p>
	 * {@code :version} is on this list even though {@code asdf:component-version} reads
	 * it back: what is recorded is the value AS WRITTEN when it is a plain string, and
	 * every other spelling answers nil. Resolving its markers instead would open a README
	 * at parse time to fill a field a User-Agent string prints -- the {@code #.} rule is
	 * that the consumer decides, and this consumer decides not to.
	 * <p>
	 * {@code :entry-point}, {@code :build-operation} and {@code :build-pathname} drive
	 * {@code program-op}, which has no meaning here: an image is not dumped.
	 */
	private static final Set<String> IGNORED_OPTIONS = Set.of(":NAME", ":LONG-NAME", ":DESCRIPTION",
			":LONG-DESCRIPTION", ":VERSION", ":AUTHOR", ":MAINTAINER", ":LICENSE", ":LICENCE", ":HOMEPAGE",
			":BUG-TRACKER", ":SOURCE-CONTROL", ":MAILTO", ":PROPERTIES", ":IN-ORDER-TO", ":PERFORM", ":ENTRY-POINT",
			":BUILD-OPERATION", ":BUILD-PATHNAME");

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
		// A built-in system is always available, so a weak dependency on one is a
		// dependency on it.
		List<String> weaklyDependsOn = dependencyNames(":WEAKLY-DEPENDS-ON", name, items, asd, preFeatures);
		announcers.addAll(weaklyDependsOn);
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
		LiveClass systemClass = null;
		LispVal aroundCompile = null;
		boolean aroundCompileWritten = false;
		TestOp testOp = null;
		List<String> testOpEdges = new ArrayList<>();
		Map<String, LispVal> metadata = new java.util.LinkedHashMap<>();
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
				case ":NAME" -> {
				}
				// Metadata: nothing here loads by it, and asdf:system-description and
				// its fellows read it back as written (:properties is ASDF's free-form
				// property list, arnesi's feature names). Nothing is evaluated to find
				// it, so an option holding a #. marker is absent.
				case ":LONG-NAME", ":DESCRIPTION", ":LONG-DESCRIPTION", ":AUTHOR", ":MAINTAINER", ":LICENSE",
						":LICENCE", ":HOMEPAGE", ":BUG-TRACKER", ":SOURCE-CONTROL", ":MAILTO", ":PROPERTIES",
						":ENTRY-POINT", ":BUILD-OPERATION", ":BUILD-PATHNAME" -> {
					if (!holdsReadEval(value)) {
						metadata.put(key.name(), value);
					}
				}
				// The one metadata option something reads back: asdf:component-version.
				// It stays in IGNORED_OPTIONS: a string is recorded, ASDF's read-file
				// indirections are read in the .asd's image, and every other spelling
				// (an unresolved #. marker) answers nil, silently.
				case ":VERSION" -> version = declaredVersion(value, baseDir, asd);
				// Already consumed by defsystemDependsOn and weaklyDependsOn above.
				case ":DEFSYSTEM-DEPENDS-ON", ":WEAKLY-DEPENDS-ON" -> {
				}
				case ":AROUND-COMPILE" -> {
					aroundCompile = aroundCompileHook(asd, value);
					aroundCompileWritten = true;
				}
				case ":ENCODING" -> checkEncoding(LispNames.ASDF_DEFSYSTEM + " " + name, value);
				// Test-op wiring: the ONE op with machinery behind it
				// (asdf:test-system). The test-op shapes are RECORDED -- a
				// :perform (test-op (o c) BODY) body and the :in-order-to
				// ((test-op (test-op ...))) chain -- and every other op stays
				// tolerated-and-ignored. Still in IGNORED_OPTIONS, so no marker fails the
				// parse: a test-op body reads its own in the image (parseTestOpPerform),
				// one that cannot read drops the clause, and another op's body is never
				// looked into.
				case ":PERFORM" -> {
					TestOp parsed = parseTestOpPerform(value, asd);
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
				// :package-inferred-system has no :components at all, the graph derived
				// from each file's own defpackage (inferPackageInferredSystems). Any
				// other
				// class the image answers for: real ASDF looks it up once
				// :defsystem-depends-on has loaded, so loaded code may define it.
				case ":CLASS" -> {
					String option = LispNames.ASDF_DEFSYSTEM + " " + name + " :class";
					if (LispNames.PACKAGE_INFERRED_SYSTEM.equals(memberName(classDesignator(option, value)))) {
						packageInferred = true;
					}
					else {
						systemClass = systemClass(LispNames.ASDF_DEFSYSTEM + " " + name, value, defsystemDependsOn,
								componentClasses, asd);
						packageInferred = systemClass.packageInferred();
					}
				}
				// :default-component-class is the class of every (:file ...) entry below
				// it -- portableaserve says cl-source-file.cl and then writes
				// (:file "main"), meaning main.cl. Real ASDF falls back to it in
				// class-for-type at exactly that one spot, so an entry that names its own
				// class keeps it.
				case ":DEFAULT-COMPONENT-CLASS" ->
					defaultComponentClass = defaultComponentClass(LispNames.ASDF_DEFSYSTEM + " " + name,
							componentClasses, value, defsystemDependsOn, asd);
				// Already consumed by declaredFeatures above.
				case ":RONTOLISP-FEATURES" -> {
				}
				default ->
					throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name + ": unsupported option "
							+ key.name() + " (supported: :name :long-name :description :long-description"
							+ " :version :author :maintainer :license :depends-on :defsystem-depends-on"
							+ " :weakly-depends-on :around-compile :encoding :entry-point :build-operation"
							+ " :build-pathname :serial :components :pathname :class :default-component-class"
							+ " :rontolisp-features)");
			}
		}
		String prefix = pathname == null || pathname.isEmpty() ? "" : pathname + "/";
		if (packageInferred && components != null) {
			throw new IllegalStateException(LispNames.ASDF_DEFSYSTEM + " " + name
					+ ": a :package-inferred-system has no :components (its graph is derived from each file's"
					+ " defpackage)");
		}
		// What the system's class says of the components below an instance, unless the
		// definition itself said it: hu.dwim.system's default-component-class slot, an
		// :around-compile default-initarg.
		if (systemClass != null) {
			if (defaultComponentClass == null && systemClass.defaultComponentClass() != null) {
				defaultComponentClass = defaultComponentClass(LispNames.ASDF_DEFSYSTEM + " " + name, componentClasses,
						systemClass.defaultComponentClass(), defsystemDependsOn, asd);
			}
			if (!aroundCompileWritten && systemClass.aroundCompile() != null) {
				aroundCompile = systemClass.aroundCompile();
			}
		}
		Map<String, LispVal> aroundCompiles = new HashMap<>();
		Map<String, LoadedClass> loadedComponents = new HashMap<>();
		ComponentList parsed = components == null ? new ComponentList(List.of(), List.of())
				: orderComponents(name, components, serial, prefix,
						new ComponentScope(features, componentClasses, defaultComponentClass, aroundCompile,
								aroundCompiles, loadedComponents, defsystemDependsOn, asd));
		if (!weaklyDependsOn.isEmpty()) {
			LispVal names = LispNil.INSTANCE;
			for (int i = weaklyDependsOn.size() - 1; i >= 0; i--) {
				names = new LispCons(new LispString(weaklyDependsOn.get(i)), names);
			}
			metadata.put(":WEAKLY-DEPENDS-ON", names);
		}
		// A package-inferred system's own :pathname is where its SUB-SYSTEM names
		// resolve:
		// array-operations says :pathname "src/" and then names array-operations/all.
		return new LispSystem(name, List.copyOf(dependsOn), parsed.plan().stream().map(ComponentNode::path).toList(),
				baseDir == null ? "" : baseDir, declaredFeatures,
				packageInferred ? (pathname == null ? "" : pathname) : null, packageInferred, testOp,
				List.copyOf(testOpEdges), defsystemDependsOn, version, weaklyDependsOn, Map.copyOf(aroundCompiles),
				new Metadata(asd.path(), java.util.Collections.unmodifiableMap(metadata)),
				new LoadedClasses(systemClass == null || systemClass.plain() ? null : systemClass.name(),
						Map.copyOf(loadedComponents)),
				new ComponentTree(prefix, parsed.children(), parsed.plan()));
	}

	/**
	 * Resolves a {@code :class} value in the image, as real ASDF's
	 * {@code parse-defsystem} does. A class it cannot find or that is no system is a hard
	 * error naming it: the system would load as something its definition did not ask for.
	 */
	private static LiveClass systemClass(String context, LispVal value, List<String> definers, ComponentClasses classes,
			AsdContext asd) {
		LiveClass found = classes.live(value, definers, asd);
		if (found == null) {
			throw new IllegalStateException(context + ": unsupported :class " + value.print() + " (a system class is"
					+ " :package-inferred-system or one the image defines by then: the .asd, or its"
					+ " :defsystem-depends-on)");
		}
		if (!found.system()) {
			throw new IllegalStateException(context + ": the :class " + value.print() + " is not a system class");
		}
		return found;
	}

	/** Whether a metadata value holds a {@code #.} marker, anywhere in it. */
	private static boolean holdsReadEval(LispVal value) {
		for (LispVal val = value; val instanceof LispCons cons; val = cons.cdr()) {
			if (isReadEvalMarker(val) || isUnreadableReadEvalMarker(val) || holdsReadEval(cons.car())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads a {@code :around-compile} value: what ASDF's {@code uiop:ensure-function}
	 * will take, the symbols homed where the file reads them. ASDF keeps the form and
	 * {@code eval}s it at the call, in symbols the read already homed; here a symbol is
	 * homed by the image the file runs in, so a lambda becomes that image's function now
	 * and any other form its resolved datum. A string is read when it is called, in
	 * {@code cl}, and a {@code defsystem} of no {@code .asd} has no file to home anything
	 * in: both stay as written.
	 * @param asd the enclosing {@code .asd} context
	 * @param value the option's value
	 * @return the hook, or {@code null} for {@code nil}: no hook, whatever the parent has
	 */
	@Nullable private static LispVal aroundCompileHook(AsdContext asd, LispVal value) {
		if (value instanceof LispNil) {
			return null;
		}
		AsdLoad load = asd.load();
		if (load == null || value instanceof LispString) {
			return value;
		}
		String operator = isLambdaExpression(value) ? LispNames.FUNCTION : LispNames.QUOTE;
		return load.readEval(new LispCons(new LispSymbol(PackageRegistry.qualify("CL", operator)),
				new LispCons(value, LispNil.INSTANCE)));
	}

	/**
	 * Whether {@code form} is a {@code (lambda ...)} expression.
	 * @param form the form
	 * @return {@code true} for a lambda expression, in any package spelling
	 */
	static boolean isLambdaExpression(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.LAMBDA.equals(memberName(op));
	}

	/**
	 * Checks an {@code :encoding} value. Source is read as UTF-8 and nothing else, which
	 * is what ASDF's two encodings without {@code asdf-encodings} mean here:
	 * {@code :utf-8}, and {@code :default}. Anything else would be misread, so it is
	 * refused by name.
	 */
	private static void checkEncoding(String context, LispVal value) {
		if (!(value instanceof LispNil) && !(value instanceof LispSymbol encoding && encoding.isKeyword()
				&& (":UTF-8".equals(encoding.name()) || ":DEFAULT".equals(encoding.name())))) {
			throw new IllegalStateException(context + ": unsupported :encoding " + value.print()
					+ " (source files are read as UTF-8; supported: :utf-8, :default)");
		}
	}

	/**
	 * Parses a {@code :perform} value when it wires {@code test-op}:
	 * {@code (test-op (o c) BODY...)}, or {@code (test-op :after (o c) BODY...)} -- how
	 * most of prove's consumers write it; ASDF's own method does nothing, so a qualifier
	 * of {@code :after} or {@code :before} changes nothing about what runs. Any other
	 * shape -- another operation, a malformed clause -- answers {@code null} and stays
	 * tolerated-and-ignored (the closed-world rule of {@link #IGNORED_OPTIONS}: nothing
	 * here may complain). The body's bare {@code asdf}/{@code uiop} member symbols are
	 * pre-qualified ({@link #normalizeAsdUserForm}); the parameter symbols are kept as
	 * written.
	 * @param value the {@code :perform} option value
	 * @param asd the enclosing {@code .asd} context, whose image reads a {@code #.} in
	 * the body
	 * @return the recorded test-op, or {@code null}
	 */
	@Nullable private static TestOp parseTestOpPerform(LispVal value, AsdContext asd) {
		if (!(value instanceof LispCons cons) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (items.size() < 2 || !(items.get(0) instanceof LispSymbol op) || !LispNames.TEST_OP.equals(memberName(op))) {
			return null;
		}
		int paramsAt = items.get(1) instanceof LispSymbol qualifier
				&& (":AFTER".equals(qualifier.name()) || ":BEFORE".equals(qualifier.name())) ? 2 : 1;
		if (items.size() <= paramsAt || !(items.get(paramsAt) instanceof LispCons paramsCons)
				|| !paramsCons.isProperList()) {
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
		for (LispVal form : items.subList(paramsAt + 1, items.size())) {
			LispVal read = form;
			if (containsReadEvalMarker(form)) {
				// A #. inside the body (the prove consumers' (intern #.(string
				// :run-test-system) :prove-asdf)) reads in the image the file runs in, as
				// real ASDF read it. No image, or a datum it cannot read: a marker would
				// fail the eager compile of the emitted test-op defun, and the option
				// complains about nothing, so the whole clause stays
				// tolerated-and-ignored.
				if (asd.load() == null) {
					return null;
				}
				try {
					read = resolveReadEval(asd, LispNames.ASDF_DEFSYSTEM + " :perform", form);
				}
				catch (IllegalStateException ex) {
					return null;
				}
			}
			body.add(normalizeAsdUserForm(read, bound));
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
	 * Whether a bare symbol in a recorded {@code .asd} form names an {@code asdf}
	 * external: the runtime FUNCTIONS (a bare class-name symbol is far more likely a
	 * variable, so class names are deliberately absent).
	 */
	private static boolean isAsdfUserFunction(String name) {
		AsdfExports.Entry entry = AsdfExports.externals().get(name);
		return entry != null && (entry.is("function") || entry.is("generic"));
	}

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
			if (isAsdfUserFunction(symName)) {
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
	private static ComponentClass defaultComponentClass(String context, ComponentClasses classes, LispVal value,
			List<String> definers, AsdContext asd) {
		String option = context + " :default-component-class";
		classDesignator(option, value);
		ComponentClass found = classes.named(value, definers, asd);
		if (found == null) {
			throw new IllegalStateException(option + " names an unknown component class " + value.print()
					+ " (a component class is one of ASDF's own, one a defclass in the same .asd declares or one"
					+ " the .asd's :defsystem-depends-on defines)");
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
	 * Answers {@code :weakly-depends-on} the way ASDF's {@code find-system NAME nil} at
	 * the parse does: an entry that can be found joins {@code :depends-on} after what the
	 * system lists, and the others are forgotten. Nothing is downloaded for one, as
	 * Quicklisp downloads nothing for one, and a system refused by name is one this
	 * implementation does not have. A loader asks when it is about to load the system,
	 * which has a search path the parse does not.
	 * @param system the system
	 * @param systems the registry, whose entry the answer replaces
	 * @param searchFinds whether the loader's search, from the system's own directory,
	 * finds a name
	 * @return the system with its weak dependencies decided, {@code system} when it has
	 * none
	 */
	public static LispSystem resolveWeakDependencies(LispSystem system, Map<String, LispSystem> systems,
			Predicate<String> searchFinds) {
		if (system.weaklyDependsOn().isEmpty()) {
			return system;
		}
		List<String> dependsOn = new ArrayList<>(system.dependsOn());
		for (String name : system.weaklyDependsOn()) {
			if (!dependsOn.contains(name) && isAvailable(name, systems, searchFinds)) {
				dependsOn.add(name);
			}
		}
		LispSystem resolved = new LispSystem(system.name(), List.copyOf(dependsOn), system.files(), system.baseDir(),
				system.features(), system.packageInferredDir(), system.packageInferredClass(), system.testOp(),
				system.testOpEdges(), system.defsystemDependsOn(), system.version(), List.of(), system.aroundCompile(),
				system.metadata(), system.loadedClasses(), system.tree());
		systems.put(system.name(), resolved);
		return resolved;
	}

	private static boolean isAvailable(String name, Map<String, LispSystem> systems, Predicate<String> searchFinds) {
		if (ShimLibraries.refusalReason(name) != null) {
			return false;
		}
		return BuiltinSystems.isBuiltin(name) || systems.containsKey(name) || searchFinds.test(name);
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
		String fileName = primarySystemName(name) + ".asd";
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
		throw notFound(name, tried);
	}

	/**
	 * Whether a {@code require} of this module is answered by the implementation, as
	 * {@code (require :asdf)} is on any Lisp that ships ASDF: it is how a script opens,
	 * and here there is no file to load.
	 * @param name the module name, in any case
	 * @return {@code true} for {@code asdf} and {@code uiop}
	 */
	public static boolean isImplementationModule(String name) {
		return "asdf".equalsIgnoreCase(name) || "uiop".equalsIgnoreCase(name);
	}

	/**
	 * The failure of a search, naming where it looked.
	 * @param name the system name
	 * @param tried the {@code .asd} paths it tried
	 * @return the exception to throw
	 */
	static SystemNotFoundException notFound(String name, List<String> tried) {
		String detail = "(tried: " + String.join(", ", tried)
				+ "); add its directory to --system-path or RONTOLISP_SOURCE_REGISTRY";
		return new SystemNotFoundException(LispNames.ASDF_LOAD_SYSTEM + ": system '" + name + "' not found " + detail,
				detail);
	}

	/**
	 * The name before the first slash, whose {@code .asd} defines a system.
	 * @param name the system name
	 * @return the primary system's name
	 */
	public static String primarySystemName(String name) {
		int slash = name.indexOf('/');
		return slash < 0 ? name : name.substring(0, slash);
	}

	/**
	 * What a source registry configuration found, which a compile path searches: the
	 * {@code .asd} per system name ahead of the inherited search path and behind it, and
	 * whether it inherits that path at all.
	 *
	 * @param ahead the files ahead of the search path, by system name
	 * @param inherits whether the configuration reached {@code :inherit-configuration}
	 * @param behind the files behind the search path, by system name
	 */
	public record SourceRegistry(Map<String, String> ahead, boolean inherits, Map<String, String> behind) {

		/** No configuration: only the search path. */
		public static final SourceRegistry DEFAULT = new SourceRegistry(Map.of(), true, Map.of());

	}

	/**
	 * A top-level call a compile path can read of the source registry family.
	 *
	 * @param clear whether it is {@code clear-source-registry}
	 * @param parameter the literal configuration, or {@code null} when the call passes
	 * none: {@code initialize-source-registry} then reads the last one
	 */
	public record SourceRegistryCall(boolean clear, @Nullable LispVal parameter) {
	}

	/**
	 * If {@code form} is a top-level {@code (asdf:clear-source-registry)} or an
	 * {@code (asdf:initialize-source-registry [PARAMETER])} whose parameter is literal:
	 * quoted, a string or nil. Anything computed is the program's alone.
	 * @param form the form to inspect
	 * @return the call, or {@code null}
	 */
	@Nullable public static SourceRegistryCall sourceRegistryCall(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op) || !cons.isProperList()) {
			return null;
		}
		List<LispVal> items = cons.toList();
		if (isAsdfMember(op, "CLEAR-SOURCE-REGISTRY")) {
			return items.size() == 1 ? new SourceRegistryCall(true, null) : null;
		}
		if (!isAsdfMember(op, "INITIALIZE-SOURCE-REGISTRY") || items.size() > 2) {
			return null;
		}
		if (items.size() == 1) {
			return new SourceRegistryCall(false, null);
		}
		LispVal parameter = items.get(1);
		if (parameter instanceof LispString || parameter instanceof LispNil) {
			return new SourceRegistryCall(false, parameter);
		}
		if (parameter instanceof LispCons quoted && quoted.car() instanceof LispSymbol quote
				&& LispNames.QUOTE.equals(memberName(quote)) && quoted.cdr() instanceof LispCons datum) {
			return new SourceRegistryCall(false, datum.car());
		}
		return null;
	}

	/**
	 * Reads one {@code .asd} file by its path, for {@code asdf:load-asd}, with the
	 * replacement {@link #locate} substitutes.
	 * @param path the resolved path
	 * @param loader the loader
	 * @return the file, or {@code null} when it cannot be read: such a file defines
	 * nothing
	 */
	@Nullable public static LocatedAsd readAsd(String path, SourceLoader loader) {
		try {
			String source = loader.load(path);
			int slash = path.lastIndexOf('/');
			String replacement = AsdOverrides.replacementSource(path.substring(slash + 1));
			return new LocatedAsd(path, replacement != null ? replacement : source);
		}
		catch (IOException ex) {
			return null;
		}
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
		String root = dir == null || dir.isEmpty() ? "" : dir + "/";
		String sub = name.substring(primary.name().length() + 1);
		String relative = sub + SourceLanguage.COMMON_LISP.defaultExtension();
		String file = root + relative;
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
		// Real ASDF's shape for it: one cl-source-file named "lisp", at the name below
		// the
		// primary's directory.
		ComponentNode node = new ComponentNode(CL_SOURCE_FILE, "lisp", sub, relative, file, null, List.of(), null, true,
				null, List.of());
		return new LispSystem(name, packageDependencies(packageForm, path, systemPackages), List.of(file),
				primary.baseDir(), primary.features(), null, true, null, List.of(), primary.defsystemDependsOn(), null,
				List.of(), Map.of(), new Metadata(primary.metadata().sourceFile(), Map.of()), LoadedClasses.NONE,
				new ComponentTree(root, List.of(node), List.of(node)));
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
	 * A parsed component: its sibling-scoped name, the sibling names it depends on, the
	 * source files it contributes (already ordered for a module) and its node.
	 */
	private record Component(String name, List<String> dependsOn, List<ComponentNode> plan, ComponentNode node) {
	}

	/**
	 * A parsed {@code :components} list.
	 *
	 * @param children the components in definition order
	 * @param plan their source files in plan order
	 */
	private record ComponentList(List<ComponentNode> children, List<ComponentNode> plan) {
	}

	/**
	 * What a component inherits from the components around it, and where its hook is
	 * recorded.
	 *
	 * @param features the features its {@code :if-feature} tests
	 * @param classes the component classes in scope in the {@code .asd}
	 * @param defaultClass the {@code :default-component-class} in force, or {@code null}
	 * @param aroundCompile the {@code :around-compile} hook in force, or {@code null}
	 * @param aroundCompiles the hook of each source file so far, by its path
	 * @param loadedClasses the class of each file so far that loaded code defines, by its
	 * path
	 * @param definers the system's {@code :defsystem-depends-on}, loaded before a class
	 * is looked up in the image
	 * @param asd the enclosing {@code .asd} context
	 */
	private record ComponentScope(Features features, ComponentClasses classes, @Nullable ComponentClass defaultClass,
			@Nullable LispVal aroundCompile, Map<String, LispVal> aroundCompiles,
			Map<String, LoadedClass> loadedClasses, List<String> definers, AsdContext asd) {

		ComponentScope forChildren(@Nullable ComponentClass moduleClass, @Nullable LispVal moduleHook) {
			return new ComponentScope(this.features, this.classes,
					moduleClass == null ? this.defaultClass : moduleClass, moduleHook, this.aroundCompiles,
					this.loadedClasses, this.definers, this.asd);
		}
	}

	/**
	 * Parses a {@code :components} list. Its children stay in definition order, and its
	 * source files come in load order: a stable topological sort of the sibling
	 * components by {@code :depends-on} (original order is preserved among unconstrained
	 * components), with {@code :serial} adding an implicit dependency on the previous
	 * sibling; a module's files stay contiguous.
	 */
	private static ComponentList orderComponents(String systemName, LispVal componentsVal, boolean serial,
			String prefix, ComponentScope scope) {
		List<Component> components = new ArrayList<>();
		String previous = null;
		for (LispVal entry : properList(LispNames.ASDF_DEFSYSTEM + " " + systemName + " :components", componentsVal)) {
			Component component = parseComponent(systemName, entry, prefix, scope, serial ? previous : null);
			components.add(component);
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
		// ASDF's plan order: the components in definition order, each after its
		// dependencies (depth first, in the order its :depends-on lists them). A
		// component
		// declaring no dependency therefore still loads after every component listed
		// before it -- arnesi's (:file "debug" :depends-on ()) comes after the "asdf"
		// component that pulls "packages" in, and opens with (in-package
		// :it.bese.arnesi).
		Map<String, Component> byName = new java.util.LinkedHashMap<>();
		for (Component component : components) {
			byName.put(component.name(), component);
		}
		List<ComponentNode> plan = new ArrayList<>();
		Set<String> placed = new HashSet<>();
		for (Component component : components) {
			placeComponent(systemName, component, byName, placed, new java.util.LinkedHashSet<>(), plan);
		}
		return new ComponentList(components.stream().map(Component::node).toList(), List.copyOf(plan));
	}

	/**
	 * Places a component after its not-yet-placed dependencies, depth first: ASDF's plan
	 * traversal. {@code visiting} is the dependency path being walked, which a cycle
	 * returns to.
	 */
	private static void placeComponent(String systemName, Component component, Map<String, Component> byName,
			Set<String> placed, Set<String> visiting, List<ComponentNode> plan) {
		if (placed.contains(component.name())) {
			return;
		}
		if (!visiting.add(component.name())) {
			throw new IllegalStateException(
					"system " + systemName + ": circular component :depends-on among " + List.copyOf(visiting));
		}
		for (String dep : component.dependsOn()) {
			// Every name was checked against the siblings above.
			placeComponent(systemName, java.util.Objects.requireNonNull(byName.get(dep)), byName, placed, visiting,
					plan);
		}
		visiting.remove(component.name());
		plan.addAll(component.plan());
		placed.add(component.name());
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

	private static Component parseComponent(String systemName, LispVal entry, String prefix, ComponentScope scope,
			@Nullable String serialPrevious) {
		// The type is any symbol, as ASDF's class-for-type reads it: a keyword, or the
		// class symbol itself (cl-unification's (asdf-system-definition-file "x")).
		if (!(entry instanceof LispCons compCons) || !(compCons.car() instanceof LispSymbol type)) {
			throw new IllegalStateException("system " + systemName
					+ ": each component must be (TYPE \"name\" ...) with TYPE a symbol naming a component class,"
					+ " such as (:file \"name\") or (:module \"dir\" ...), got " + entry.print());
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
		// (:file "x") means the enclosing :default-component-class, ASDF's cl-source-file
		// when there is none; every other type NAMES its class, be it one of ASDF's own
		// (:static-file, :doc-file, :cl-source-file.cl), one a defclass in the same .asd
		// declared (chipz's :txt-file, portableaserve's :legacy-acl-source-file) or one
		// loaded code defines (prove-asdf's :test-file).
		ComponentClass componentClass = null;
		if (":FILE".equals(type.name())) {
			componentClass = scope.defaultClass() == null ? DEFAULT_COMPONENT_CLASS : scope.defaultClass();
		}
		else if (!":MODULE".equals(type.name())) {
			componentClass = scope.classes().named(type, scope.definers(), scope.asd());
			if (componentClass == null) {
				throw new IllegalStateException("system " + systemName + ": unsupported component type " + type.name()
						+ " (supported: :file, :module, ASDF's own component classes, the classes a defclass in the"
						+ " same .asd declares and the ones its :defsystem-depends-on defines)");
			}
		}
		boolean module = componentClass == null || componentClass.module();
		List<String> dependsOn = new ArrayList<>();
		boolean moduleSerial = false;
		boolean featureEnabled = true;
		LispVal ifFeature = null;
		LispVal nested = null;
		String pathname = null;
		boolean pathnameObject = false;
		ComponentClass moduleDefaultClass = null;
		LispVal aroundCompile = componentClass != null && componentClass.aroundCompile() != null
				? componentClass.aroundCompile() : scope.aroundCompile();
		List<LispVal> initargs = new ArrayList<>();
		for (int i = 2; i < parts.size(); i += 2) {
			if (!(parts.get(i) instanceof LispSymbol key) || !key.isKeyword()) {
				throw new IllegalStateException("system " + systemName + ": component " + name
						+ " expects a keyword option, got " + parts.get(i).print());
			}
			LispVal value = parts.get(i + 1);
			switch (key.name()) {
				case ":DEPENDS-ON" -> {
					for (LispVal dep : properList("component " + name + " :depends-on", value)) {
						String resolved = componentDependency(systemName, name, dep, scope.features());
						if (resolved != null) {
							dependsOn.add(resolved);
						}
					}
				}
				case ":IF-FEATURE" -> {
					featureEnabled = scope.features().isEnabled(value);
					ifFeature = value;
				}
				// ASDF's around-compile-hook: the nearest component that says.
				case ":AROUND-COMPILE" -> aroundCompile = aroundCompileHook(scope.asd(), value);
				case ":ENCODING" -> checkEncoding("system " + systemName + ": component " + name, value);
				// :pathname decouples a component's NAME (its identity in the sibling
				// dependency graph) from the path it contributes. quri's uri-classes
				// module is exactly this: named uri-classes, living in src/uri/.
				case ":PATHNAME" -> {
					pathname = componentPathname(systemName, name, value);
					pathnameObject = !(value instanceof LispString);
				}
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
					moduleDefaultClass = defaultComponentClass("system " + systemName + ": module " + name,
							scope.classes(), value, scope.definers(), scope.asd());
				}
				default -> {
					if (componentClass == null || componentClass.loaded() == null) {
						throw unsupportedComponentOption(systemName, type, name, key);
					}
					initargs.add(key);
					initargs.add(value);
				}
			}
		}
		// Real ASDF's slot holds the predecessor ahead of what the definition wrote; the
		// plan visits what it wrote first.
		List<String> sideway = new ArrayList<>();
		if (serialPrevious != null) {
			sideway.add(serialPrevious);
		}
		sideway.addAll(dependsOn);
		if (serialPrevious != null) {
			dependsOn.add(serialPrevious);
		}
		LoadedClass loaded = null;
		if (componentClass != null && componentClass.loaded() != null) {
			LoadedClass named = componentClass.loaded();
			loaded = new LoadedClass(named.name(), named.lispSource(), named.deferred(), List.copyOf(initargs));
		}
		ComponentNode node;
		List<ComponentNode> plan = List.of();
		if (module) {
			if (nested == null) {
				throw new IllegalStateException(
						"system " + systemName + ": module " + name + " expects a :components option");
			}
			// An empty :pathname is ASDF's "this module adds no directory level".
			String dir = pathname == null ? name : pathname;
			String childPrefix = dir.isEmpty() ? prefix : prefix + dir + "/";
			ComponentList parsed = orderComponents(systemName, nested, moduleSerial, childPrefix,
					scope.forChildren(moduleDefaultClass, aroundCompile));
			node = new ComponentNode(MODULE, name, pathname, dir.isEmpty() ? "" : dir + "/", childPrefix, null,
					List.copyOf(sideway), ifFeature, featureEnabled, loaded, parsed.children());
			plan = parsed.plan();
		}
		else {
			ComponentClass fileClass = java.util.Objects.requireNonNull(componentClass);
			String relative = sourceFileName(name, pathname, pathnameObject, fileClass.fileType());
			String file = prefix + relative;
			node = new ComponentNode(fileClass.kind(), name, pathname, relative, file,
					fileTypeOverride(fileClass, relative), List.copyOf(sideway), ifFeature, featureEnabled, loaded,
					List.of());
			// An ordering-only class contributes no source.
			if (fileClass.source()) {
				plan = List.of(node);
				if (aroundCompile != null) {
					scope.aroundCompiles().put(file, aroundCompile);
				}
				if (loaded != null) {
					scope.loadedClasses().put(file, loaded);
				}
			}
		}
		// A feature-disabled component keeps its place in the dependency graph (a
		// sibling may :depends-on it) and in the tree, but contributes no source files.
		return new Component(name, dependsOn, featureEnabled ? plan : List.of(), node);
	}

	/**
	 * The {@code type} slot of an instance, when the class it is made of does not hold
	 * it. For ASDF's own classes and the ones a {@code .asd} declares it is the declared
	 * class's slot, which neither a {@code source-file-type} method nor a
	 * {@code :pathname} extension changes (cl-unification's {@code .asd} component is
	 * {@code "lisp"}, measured on SBCL 2.2.9). A loaded class's source file takes the
	 * extension the plan named the file by, any other file of one its class's extension.
	 */
	private static @Nullable String fileTypeOverride(ComponentClass fileClass, String relative) {
		String fileType;
		if (fileClass.loaded() == null) {
			fileType = fileClass.slotType();
		}
		else if (fileClass.source()) {
			int dot = relative.lastIndexOf('.');
			fileType = dot > relative.lastIndexOf('/') ? relative.substring(dot + 1) : null;
		}
		else {
			fileType = fileClass.fileType();
		}
		return fileType == null || fileType.isEmpty() || fileType.equals(classFileType(fileClass.kind())) ? null
				: fileType;
	}

	/**
	 * The file a component names, as ASDF's {@code parse-unix-namestring} makes it: its
	 * {@code :pathname} or else its name, plus its CLASS's extension when the class gives
	 * one -- even past a dot already there ({@code "d.cl"} names {@code d.cl.lisp},
	 * measured on SBCL 2.2.9). A {@code #P} literal is a pathname already and names its
	 * file verbatim.
	 */
	private static String sourceFileName(String name, @Nullable String pathname, boolean pathnameObject,
			@Nullable String fileType) {
		String file = pathname == null ? name : pathname;
		if (fileType == null || fileType.isEmpty() || pathname != null && pathnameObject) {
			return file;
		}
		return file + "." + fileType;
	}

	private static String componentPathname(String systemName, String name, LispVal value) {
		return pathnamePrefix("system " + systemName + ": component " + name, value);
	}

	/**
	 * Reads a system-level or component-level {@code :pathname} value. Only a literal
	 * namestring is accepted ({@code "src"}, or the constant {@code #P"src"} the reader
	 * hands over as a pathname value): a computed pathname would need the pathname
	 * machinery ASDF-as-data deliberately does not have. A trailing slash is dropped so
	 * the caller composes exactly one separator.
	 */
	private static String pathnamePrefix(String context, LispVal value) {
		String path = PathnameOps.designatorNamestring(value);
		if (path == null) {
			throw new IllegalStateException(context + " :pathname expects a namestring literal, got " + value.print());
		}
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
		return qn != null && AsdfExports.isAsdfFamily(qn.pkg()) && member.equals(qn.member());
	}

}
