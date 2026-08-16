package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The ASDF component metaobjects at run time ({@code asdf.lisp}): the
 * {@code asdf:component} class family, the readers over it, and {@code asdf:find-system}
 * answering a memoized {@code asdf:system} instance per name -- one Lisp source shared by
 * every backend, so {@code typecase}/{@code typep}/defmethod specializers over the
 * classes work by construction (rove's system-driven {@code run} reads the whole
 * component model this way).
 *
 * <p>
 * The one per-backend seam is the record source behind
 * {@code %asdf-system-record}/{@code %asdf-system-names}: the interpreter registers them
 * as Java built-ins over its live {@code asdfSystems} registry ({@link LispEvaluator}),
 * while the compile paths bake the registry {@code LoadInliner} spliced into a
 * {@code %asdf-registry%} table and generate the two defuns over it ({@link #process}) --
 * plus the compile-only runtime forms the interpreter keeps in Java:
 * {@code asdf:load-system}/{@code ql:quickload} ("already spliced" answers nil, anything
 * else is the call-time error) and the {@code asdf:test-system} dispatch over the
 * {@code %asdf-test-op-<name>} defuns {@code LoadInliner.spliceSystem} emitted at each
 * system's splice point.
 *
 * <p>
 * A record is {@code (CLASS DIR FILES DEPS LOADED-P VERSION)}: the component class
 * keyword ({@code :system} / {@code :package-inferred-system}), the source directory with
 * a trailing slash, the component files as {@code (RELATIVE . RESOLVED)} namestring pairs
 * in load order, the {@code :depends-on} names, whether the system is loaded, and the
 * declared {@code :version} string (nil when it declared none as a plain string).
 */
public final class AsdfRuntimeLibrary {

	private AsdfRuntimeLibrary() {
	}

	@Nullable private static volatile List<LispVal> classForms;

	@Nullable private static volatile List<LispVal> seamForms;

	/** The names {@code asdf.lisp} defines (functions plus the one variable). */
	private static final Set<String> DEFINED_NAMES = Set.of(LispNames.ASDF_FIND_SYSTEM,
			qualify(LispNames.REGISTERED_SYSTEMS), qualify(LispNames.COMPONENT_NAME),
			qualify(LispNames.COMPONENT_VERSION), LispNames.ASDF_COMPONENT_PATHNAME,
			qualify(LispNames.COMPONENT_CHILDREN), qualify(LispNames.COMPONENT_SIDEWAY_DEPENDENCIES),
			qualify(LispNames.COMPONENT_PARENT), qualify(LispNames.COMPONENT_SYSTEM),
			LispNames.ASDF_SYSTEM_SOURCE_DIRECTORY, LispNames.ASDF_SYSTEM_RELATIVE_PATHNAME,
			qualify(LispNames.ASDF_USER_CACHE));

	/** The component metaobject class names {@code asdf.lisp} defines. */
	private static final Set<String> CLASS_NAMES = Set.of(qualify(LispNames.COMPONENT),
			qualify(LispNames.CHILD_COMPONENT), qualify(LispNames.PARENT_COMPONENT), qualify(LispNames.MODULE),
			qualify("SYSTEM"), qualify(LispNames.PACKAGE_INFERRED_SYSTEM), qualify(LispNames.SOURCE_FILE),
			qualify(LispNames.CL_SOURCE_FILE), qualify(LispNames.STATIC_FILE));

	/**
	 * The names whose presence in a compiled program splices the runtime: everything
	 * {@code asdf.lisp} defines, the class names, and the runtime forms only the compile
	 * seam provides (a NESTED {@code asdf:load-system}/{@code ql:quickload} -- the
	 * top-level literal ones were consumed by {@code LoadInliner} -- and
	 * {@code asdf:test-system}).
	 */
	private static final Set<String> TRIGGER_NAMES;
	static {
		java.util.Set<String> triggers = new java.util.HashSet<>(DEFINED_NAMES);
		triggers.addAll(CLASS_NAMES);
		triggers.add(LispNames.ASDF_LOAD_SYSTEM);
		triggers.add(LispNames.QL_QUICKLOAD);
		triggers.add(LispNames.ASDF_TEST_SYSTEM);
		TRIGGER_NAMES = Set.copyOf(triggers);
	}

	private static String qualify(String member) {
		return am.ik.rontolisp.PackageRegistry.qualify(LispNames.ASDF_PKG, member);
	}

	/**
	 * Whether the interpreter's lazy load should fire for a resolution of {@code name}
	 * (function or variable -- {@code asdf:*user-cache*} is a variable read).
	 * @param name the canonical qualified name being resolved
	 * @return {@code true} when {@code asdf.lisp} defines it
	 */
	public static boolean definesName(String name) {
		return DEFINED_NAMES.contains(name);
	}

	/**
	 * Whether {@code form} mentions an ASDF component class name -- the trigger for the
	 * interpreter to seed the classes before a {@code defmethod} specializer /
	 * {@code typep} / {@code typecase} / {@code make-instance} resolves one.
	 * @param form the form about to be evaluated
	 * @return {@code true} when a class name occurs anywhere in it
	 */
	public static boolean mentionsComponentClass(LispVal form) {
		if (form instanceof LispSymbol sym) {
			return CLASS_NAMES.contains(sym.name());
		}
		if (form instanceof LispCons cons) {
			return mentionsComponentClass(cons.car()) || mentionsComponentClass(cons.cdr());
		}
		return false;
	}

	/**
	 * Returns the parsed {@code asdf.lisp} forms (canonical shape, no package resolution
	 * needed). Parsed once and cached.
	 * @return the class-family forms
	 */
	public static List<LispVal> classForms() {
		List<LispVal> cached = classForms;
		if (cached == null) {
			synchronized (AsdfRuntimeLibrary.class) {
				cached = classForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource("asdf.lisp"), Features.INTERPRETER));
					classForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The compile-path pre-pass, run at the end of {@code LoadInliner.inline} (after the
	 * pathname folder, so a program whose only asdf use folded away -- the uax-15/quri
	 * literal shapes -- splices nothing): when the program references any runtime asdf
	 * name, prepends the class family plus the compile seam -- the baked
	 * {@code %asdf-registry%} table, the record defuns over it, the runtime
	 * {@code asdf:load-system}/{@code ql:quickload} ("already spliced" is a nil no-op,
	 * anything else the call-time error the nested forms always were), and the
	 * {@code asdf:test-system} dispatch over the recorded test-op wiring.
	 * @param program the inlined and folded top-level forms
	 * @param systems the registry of every system the inline pass parsed
	 * @param loadedSystems the names of the systems it actually spliced
	 * @return the program with the asdf runtime spliced, or {@code program} unchanged
	 */
	public static List<LispVal> process(List<LispVal> program, Map<String, AsdfSystems.LispSystem> systems,
			Set<String> loadedSystems) {
		if (program.stream().noneMatch(AsdfRuntimeLibrary::referencesRuntime)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(classForms());
		out.add(registryDefvar(systems, loadedSystems));
		out.addAll(seamForms());
		out.add(runTestOpDefun(systems, loadedSystems));
		out.addAll(program);
		return out;
	}

	private static boolean referencesRuntime(LispVal form) {
		if (form instanceof LispSymbol sym) {
			return TRIGGER_NAMES.contains(sym.name());
		}
		if (form instanceof LispCons cons) {
			return referencesRuntime(cons.car()) || referencesRuntime(cons.cdr());
		}
		return false;
	}

	/**
	 * The record for one system, in the shape {@code asdf.lisp} reads:
	 * {@code (CLASS DIR FILES DEPS LOADED-P)}.
	 * @param system the parsed system
	 * @param loaded whether the system has been loaded/spliced
	 * @return the record as Lisp data
	 */
	public static LispVal recordFor(AsdfSystems.LispSystem system, boolean loaded) {
		List<LispVal> files = new ArrayList<>();
		for (String file : system.files()) {
			files.add(new LispCons(new LispString(file),
					new LispString(SourceLoader.resolve(emptyToNull(system.baseDir()), file))));
		}
		List<LispVal> deps = new ArrayList<>();
		for (String dependency : system.dependsOn()) {
			deps.add(new LispString(dependency));
		}
		return list(new LispSymbol(system.packageInferredClass() ? ":PACKAGE-INFERRED-SYSTEM" : ":SYSTEM"),
				new LispString(normalizeDir(system.baseDir())), list(files.toArray(LispVal[]::new)),
				list(deps.toArray(LispVal[]::new)), loaded ? LispTrue.INSTANCE : LispNil.INSTANCE,
				system.version() == null ? LispNil.INSTANCE : new LispString(system.version()));
	}

	/**
	 * The record for a built-in shim system ({@code BuiltinSystems}): a plain system with
	 * no directory, files, dependencies or version.
	 * @param loaded whether the shim has been loaded
	 * @return the record as Lisp data
	 */
	public static LispVal builtinRecord(boolean loaded) {
		return list(new LispSymbol(":SYSTEM"), new LispString("./"), LispNil.INSTANCE, LispNil.INSTANCE,
				loaded ? LispTrue.INSTANCE : LispNil.INSTANCE, LispNil.INSTANCE);
	}

	/**
	 * The name of the defun {@code LoadInliner.spliceSystem} emits for a system's
	 * recorded {@code :perform (test-op ...)} body.
	 * @param systemName the system's downcase-canonical name
	 * @return the defun's symbol name
	 */
	public static String testOpDefunName(String systemName) {
		return "%ASDF-TEST-OP-" + systemName.toUpperCase(java.util.Locale.ROOT);
	}

	/**
	 * The {@code (defun %asdf-test-op-<name> (o c) BODY...)} form for a spliced system's
	 * recorded test-op perform, or {@code null} when the system records none. Emitted at
	 * the system's splice point, so the body compiles in the system's own context.
	 * @param system the spliced system
	 * @return the defun form, or {@code null}
	 */
	@Nullable public static LispVal testOpDefun(AsdfSystems.LispSystem system) {
		AsdfSystems.TestOp testOp = system.testOp();
		if (testOp == null) {
			return null;
		}
		List<LispVal> parts = new ArrayList<>();
		parts.add(new LispSymbol(LispNames.DEFUN));
		parts.add(new LispSymbol(testOpDefunName(system.name())));
		parts.add(list(testOp.params().toArray(LispVal[]::new)));
		parts.addAll(testOp.body());
		return list(parts.toArray(LispVal[]::new));
	}

	/** The baked registry: {@code (defvar %asdf-registry% '(("name" . RECORD) ...))}. */
	private static LispVal registryDefvar(Map<String, AsdfSystems.LispSystem> systems, Set<String> loadedSystems) {
		List<LispVal> entries = new ArrayList<>();
		for (AsdfSystems.LispSystem system : systems.values()) {
			entries.add(new LispCons(new LispString(system.name()),
					recordFor(system, loadedSystems.contains(system.name()))));
		}
		for (String loaded : loadedSystems) {
			if (!systems.containsKey(loaded) && BuiltinSystems.isBuiltin(loaded)) {
				entries.add(new LispCons(new LispString(loaded), builtinRecord(true)));
			}
		}
		return list(new LispSymbol(LispNames.DEFVAR), new LispSymbol("%ASDF-REGISTRY%"),
				list(new LispSymbol(LispNames.QUOTE), list(entries.toArray(LispVal[]::new))));
	}

	/**
	 * The generated {@code %asdf-run-test-op} dispatch: one {@code cond} arm per spliced
	 * system with recorded test-op wiring -- its {@code :in-order-to} test-op edges first
	 * (each loaded-then-dispatched through {@code %asdf-test-edge}), then its own perform
	 * defun; a system with no wiring is real ASDF's default no-op.
	 */
	private static LispVal runTestOpDefun(Map<String, AsdfSystems.LispSystem> systems, Set<String> loadedSystems) {
		List<LispVal> cond = new ArrayList<>();
		cond.add(new LispSymbol(LispNames.COND));
		for (AsdfSystems.LispSystem system : systems.values()) {
			if (!loadedSystems.contains(system.name()) || (system.testOp() == null && system.testOpEdges().isEmpty())) {
				continue;
			}
			List<LispVal> arm = new ArrayList<>();
			arm.add(list(new LispSymbol(LispNames.EQUAL), new LispSymbol("%ASDF-KEY"), new LispString(system.name())));
			for (String edge : system.testOpEdges()) {
				if (!edge.equals(system.name())) {
					arm.add(list(new LispSymbol("%ASDF-TEST-EDGE"), new LispString(edge)));
				}
			}
			if (system.testOp() != null) {
				arm.add(list(new LispSymbol(testOpDefunName(system.name())), LispNil.INSTANCE,
						list(new LispSymbol(LispNames.ASDF_FIND_SYSTEM), new LispString(system.name()))));
			}
			if (arm.size() == 1) {
				arm.add(LispNil.INSTANCE);
			}
			cond.add(list(arm.toArray(LispVal[]::new)));
		}
		cond.add(list(LispTrue.INSTANCE, LispNil.INSTANCE));
		return list(new LispSymbol(LispNames.DEFUN), new LispSymbol("%ASDF-RUN-TEST-OP"),
				list(new LispSymbol("%ASDF-KEY")), list(cond.toArray(LispVal[]::new)));
	}

	/** The fixed compile-seam defuns, parsed once and cached. */
	private static List<LispVal> seamForms() {
		List<LispVal> cached = seamForms;
		if (cached == null) {
			synchronized (AsdfRuntimeLibrary.class) {
				cached = seamForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(SEAM_SOURCE, Features.INTERPRETER));
					seamForms = cached;
				}
			}
		}
		return cached;
	}

	// The compile-path halves of the seam: the record defuns over the baked table, the
	// runtime load forms and the test-system entry (the interpreter keeps all of these
	// in Java over its live registry).
	private static final String SEAM_SOURCE = """
			(defun %asdf-system-record (key)
			  (let ((found nil))
			    (dolist (entry %asdf-registry%)
			      (when (and (not found) (equal (car entry) key))
			        (setq found (cdr entry))))
			    found))
			(defun %asdf-system-names ()
			  (mapcar (lambda (entry) (car entry)) %asdf-registry%))
			(defun %asdf-runtime-load (operator name)
			  (let ((rec (%asdf-system-record (%asdf-coerce-name name))))
			    (if (and rec (nth 4 rec))
			        nil
			        (error "~a cannot load a system at run time on the compiled backends (systems are spliced at compile time): ~a"
			               operator (%asdf-coerce-name name)))))
			(defun asdf:load-system (name &rest options)
			  (%asdf-runtime-load "ASDF:LOAD-SYSTEM" name))
			(defun ql:quickload (name &rest options)
			  (if (consp name)
			      (dolist (entry name)
			        (%asdf-runtime-load "QL:QUICKLOAD" entry))
			      (%asdf-runtime-load "QL:QUICKLOAD" name))
			  name)
			(defun %asdf-test-edge (key)
			  (asdf:load-system key)
			  (%asdf-run-test-op key))
			(defun asdf:test-system (name)
			  (let ((sys (asdf:find-system name)))
			    (asdf:load-system sys)
			    (%asdf-run-test-op (asdf:component-name sys))
			    t))
			""";

	private static String readSource(String resource) {
		try (InputStream in = AsdfRuntimeLibrary.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Normalizes a system base directory the way the old Java built-ins answered it:
	 * {@code "./"} for none, a trailing slash otherwise.
	 * @param baseDir the recorded base directory, possibly empty
	 * @return the normalized directory namestring
	 */
	public static String normalizeDir(@Nullable String baseDir) {
		if (baseDir == null || baseDir.isEmpty()) {
			return "./";
		}
		return baseDir.endsWith("/") ? baseDir : baseDir + "/";
	}

	@Nullable private static String emptyToNull(@Nullable String dir) {
		return dir == null || dir.isEmpty() ? null : dir;
	}

	private static LispVal list(LispVal... items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			tail = new LispCons(items[i], tail);
		}
		return tail;
	}

}
