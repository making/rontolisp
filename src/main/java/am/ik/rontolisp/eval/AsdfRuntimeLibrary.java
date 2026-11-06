package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.AsdfExports;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispPackageException;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.compiler.SplicedComponentGroups;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The ASDF component metaobjects and operation protocol at run time ({@code asdf.lisp}):
 * the {@code asdf:component} class family, the readers over it, {@code asdf:find-system}
 * answering a memoized {@code asdf:system} instance per name, and the operation classes
 * with the {@code asdf:perform} generic every component load goes through -- one Lisp
 * source shared by every backend, so {@code typecase}/{@code typep}/defmethod
 * specializers over the classes work by construction (rove's system-driven {@code run}
 * reads the whole component model this way, asdf-flv's {@code perform :around} wraps
 * every load).
 *
 * <p>
 * The per-backend seams are the record source behind
 * {@code %asdf-system-record}/{@code %asdf-system-names}, the component load
 * ({@code %asdf-load-component}) and the recorded test-op wiring
 * ({@code %asdf-test-op-edges}/{@code %asdf-run-recorded-test-op}): the interpreter
 * registers them as Java built-ins over its live {@code asdfSystems} registry
 * ({@link LispEvaluator}), while the compile paths bake the registry {@code LoadInliner}
 * spliced into a {@code %asdf-registry%} table and generate the defuns over it
 * ({@link #process}) -- plus the compile-only runtime forms the interpreter keeps in
 * Java: {@code asdf:load-system}/{@code ql:quickload} ("already spliced" answers nil,
 * anything else is the call-time error), and the component load that runs the thunk of a
 * spliced component ({@code compiler.SplicedComponentGroups}).
 *
 * <p>
 * A record is {@code (CLASS DIR FILES DEPS LOADED-P VERSION META COMPONENT-CLASSES)}: the
 * system's class ({@code :system} / {@code :package-inferred-system}, or the name of a
 * class loaded code defines as a string), the source directory with a trailing slash, the
 * component files as {@code (RELATIVE . RESOLVED)} namestring pairs in load order, the
 * {@code :depends-on} names, whether the system is loaded, the declared {@code :version}
 * string (nil when it declared none as a plain string), the plist of what the
 * {@code .asd} declared besides, and {@code (RELATIVE CLASS . INITARGS)} entries for the
 * files whose class loaded code defines. A name stays a string in the record: a compile
 * path's table stands ahead of the packages those classes live in.
 *
 * <p>
 * The surface is the inventory ({@link AsdfExports}): an external {@code asdf.lisp} does
 * not define is a signalling stub or an empty class synthesized here
 * ({@link #stubForms}). The interpreter evaluates them all with the runtime; a compile
 * path splices the ones the program names and the superclasses they need.
 *
 * <p>
 * How a system definition is found ({@code asdf-search.lisp}) goes the same way: the
 * interpreter's loader walks it, and a compile path, whose search ran while it spliced,
 * carries it only for a program naming one of its members.
 */
public final class AsdfRuntimeLibrary {

	private AsdfRuntimeLibrary() {
	}

	@Nullable private static volatile List<LispVal> classForms;

	@Nullable private static volatile List<LispVal> searchForms;

	@Nullable private static volatile List<LispVal> seamForms;

	@Nullable private static volatile List<LispVal> imageForms;

	/**
	 * What {@code asdf.lisp} and the stubs define, by canonical name: the externals'
	 * stubs in inventory order, each with its forms, and the members with a definition of
	 * any kind.
	 *
	 * @param stubs the synthesized forms per member name
	 * @param functions the function, generic and variable members
	 * @param classes the class and condition members
	 * @param generics the generic members, which a method may extend
	 */
	private record Definitions(Map<String, List<LispVal>> stubs, Set<String> functions, Set<String> classes,
			Set<String> generics) {
	}

	@Nullable private static volatile Definitions definitions;

	private static final String STUB_ARGS = "%ASDF-STUB-ARGS";

	private static final String CLASS_VAR = "%ASDF-CLASS";

	/**
	 * The file type upstream's classes without a definition in {@code asdf.lisp} hold,
	 * which a subclass inherits ({@code source-file-type}).
	 */
	private static final Map<String, String> STUB_FILE_TYPES = Map.of("C-SOURCE-FILE", "c", "JAVA-SOURCE-FILE", "java");

	/**
	 * The classes a definition saying nothing gets: an instance needs no class name in
	 * its record.
	 */
	private static final Set<String> PLAIN_CLASSES = Set.of(qualify("SYSTEM"),
			qualify(LispNames.PACKAGE_INFERRED_SYSTEM), qualify("CL-SOURCE-FILE"));

	/**
	 * Whether a class a definition names is one it gets anyway.
	 * @param name the class's name
	 * @return {@code true} for {@code system}, {@code package-inferred-system} and
	 * {@code cl-source-file}
	 */
	static boolean isPlainClass(String name) {
		return PLAIN_CLASSES.contains(name);
	}

	/**
	 * The members a program reads a system's metadata through: a compile path bakes the
	 * metadata into the registry only for a program naming one, so no other program
	 * carries a long description.
	 */
	private static final Set<String> METADATA_READERS = Set.of("SYSTEM-SOURCE-FILE", "SYSTEM-DEFINITION-PATHNAME",
			"SYSTEM-DESCRIPTION", "SYSTEM-LONG-DESCRIPTION", "SYSTEM-AUTHOR", "SYSTEM-MAINTAINER", "SYSTEM-LICENSE",
			"SYSTEM-LICENCE", "SYSTEM-HOMEPAGE", "SYSTEM-BUG-TRACKER", "SYSTEM-MAILTO", "SYSTEM-LONG-NAME",
			"SYSTEM-SOURCE-CONTROL", "SYSTEM-WEAKLY-DEPENDS-ON", "SYSTEM-DEFSYSTEM-DEPENDS-ON", "COMPONENT-PROPERTY",
			"COMPONENT-PROPERTIES", "COMPONENT-DESCRIPTION", "COMPONENT-LONG-DESCRIPTION", "COMPONENT-ENTRY-POINT",
			"COMPONENT-BUILD-OPERATION", "COMPONENT-BUILD-PATHNAME", "MAKE", "BUILD-OP", "DESCRIPTION",
			"LONG-DESCRIPTION", "PROPERTIES", "AUTHOR", "MAINTAINER", "LICENCE", "HOMEPAGE", "BUG-TRACKER", "MAILTO",
			"LONG-NAME", "SOURCE-CONTROL", "SOURCE-FILE", "WEAKLY-DEPENDS-ON", "DEFSYSTEM-DEPENDS-ON", "ENTRY-POINT",
			"BUILD-OPERATION", "BUILD-PATHNAME");

	/** The operators {@link #stubForms} recognizes a definition by. */
	private static final Set<String> FUNCTION_DEFINERS = Set.of(LispNames.DEFUN, LispNames.DEFGENERIC, LispNames.DEFVAR,
			LispNames.DEFPARAMETER);

	private static Definitions definitions() {
		Definitions cached = definitions;
		if (cached == null) {
			synchronized (AsdfRuntimeLibrary.class) {
				cached = definitions;
				if (cached == null) {
					cached = buildDefinitions();
					definitions = cached;
				}
			}
		}
		return cached;
	}

	private static Definitions buildDefinitions() {
		Set<String> functions = new HashSet<>();
		Set<String> classes = new HashSet<>();
		Set<String> generics = new HashSet<>();
		for (LispVal form : coreForms()) {
			collectDefinition(form, functions, classes, generics);
		}
		for (LispVal form : searchForms()) {
			collectDefinition(form, functions, classes, generics);
		}
		// What the seam and the Java built-ins define on every path.
		functions.add(LispNames.LOAD_SYSTEM);
		Map<String, List<LispVal>> stubs = new LinkedHashMap<>();
		Set<String> allFunctions = new HashSet<>(functions);
		Set<String> allClasses = new HashSet<>(classes);
		Set<String> allGenerics = new HashSet<>(generics);
		List<AsdfExports.Entry> pending = new ArrayList<>(AsdfExports.externals().values());
		Set<String> seen = new HashSet<>();
		while (!pending.isEmpty()) {
			AsdfExports.Entry entry = pending.removeFirst();
			if (entry.isUiopHomed() || LispNames.DEFSYSTEM.equals(entry.symbol()) || !seen.add(entry.symbol())) {
				continue;
			}
			List<LispVal> forms = stubForms(entry, functions, classes);
			if (!forms.isEmpty()) {
				stubs.put(entry.symbol(), forms);
			}
			if (entry.is("generic")) {
				allGenerics.add(entry.symbol());
			}
			if (entry.is("generic") || entry.is("function") || entry.is("macro") || entry.is("variable")) {
				allFunctions.add(entry.symbol());
			}
			if (entry.is("class") || entry.is("condition")) {
				allClasses.add(entry.symbol());
				// A superclass only a sub-package exports (asdf/plan:plan-traversal) has
				// to
				// exist for its subclass.
				for (String superclass : entry.superclasses()) {
					AsdfExports.Entry row = AsdfExports.entry(superclass);
					if (row != null) {
						pending.add(row);
					}
				}
			}
		}
		return new Definitions(java.util.Collections.unmodifiableMap(stubs), Set.copyOf(allFunctions),
				Set.copyOf(allClasses), Set.copyOf(allGenerics));
	}

	/**
	 * Records what one {@code asdf.lisp} form defines. A public name it defines has to be
	 * an inventory name, so the surface cannot grow past real ASDF's unnoticed.
	 */
	private static void collectDefinition(LispVal form, Set<String> functions, Set<String> classes,
			Set<String> generics) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispSymbol defined)) {
			return;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(defined.name());
		if (qn == null) {
			return;
		}
		if (FUNCTION_DEFINERS.contains(op.name())) {
			functions.add(qn.member());
			if (LispNames.DEFGENERIC.equals(op.name())) {
				generics.add(qn.member());
			}
		}
		else if (LispNames.DEFCLASS.equals(op.name()) || LispNames.DEFINE_CONDITION.equals(op.name())) {
			classes.add(qn.member());
			collectSlotReaders(form, functions);
		}
		if (!AsdfExports.owned().contains(qn.member())) {
			throw new IllegalStateException("asdf.lisp defines " + defined.name() + ", which no ASDF package exports");
		}
	}

	private static void collectSlotReaders(LispVal definition, Set<String> functions) {
		List<LispVal> items = ((LispCons) definition).toList();
		if (items.size() < 4 || !(items.get(3) instanceof LispCons slots)) {
			return;
		}
		for (LispVal slot : slots.toList()) {
			if (!(slot instanceof LispCons slotCons) || !slotCons.isProperList()) {
				continue;
			}
			List<LispVal> options = slotCons.toList();
			for (int i = 1; i + 1 < options.size(); i += 2) {
				if (options.get(i) instanceof LispSymbol key
						&& (":READER".equals(key.name()) || ":ACCESSOR".equals(key.name()))
						&& options.get(i + 1) instanceof LispSymbol reader) {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(reader.name());
					if (qn != null) {
						functions.add(qn.member());
					}
				}
			}
		}
	}

	/**
	 * The forms standing in for the parts of an external {@code asdf.lisp} does not
	 * define: upstream's shape, so a generic takes a library's method and a class a
	 * library's subclass, and any call signals {@code uiop:not-implemented-error} naming
	 * the operation.
	 */
	private static List<LispVal> stubForms(AsdfExports.Entry entry, Set<String> functions, Set<String> classes) {
		String member = entry.symbol();
		LispSymbol name = new LispSymbol(canonicalName(member));
		List<LispVal> forms = new ArrayList<>();
		if (entry.is("condition") && !classes.contains(member)) {
			forms.add(list(new LispSymbol(LispNames.DEFINE_CONDITION), name, superclasses(entry), LispNil.INSTANCE));
		}
		if (entry.is("class") && !classes.contains(member)) {
			String fileType = STUB_FILE_TYPES.get(member);
			forms.add(list(new LispSymbol(LispNames.DEFCLASS), name, superclasses(entry), fileType == null
					? LispNil.INSTANCE
					: list(list(new LispSymbol("TYPE"), new LispSymbol(":INITFORM"), new LispString(fileType)))));
		}
		if (entry.is("variable") && !functions.contains(member)) {
			forms.add(list(new LispSymbol(LispNames.DEFVAR), name, LispNil.INSTANCE));
		}
		if (functions.contains(member)) {
			return forms;
		}
		LispVal refusal = list(new LispSymbol(UiopExports.qualified(LispNames.NOT_IMPLEMENTED_ERROR)),
				new LispString(name.name()));
		if (entry.is("generic") && !entry.lambdaList().isEmpty()) {
			List<LispVal> parameters = new ArrayList<>();
			List<LispVal> ignored = new ArrayList<>();
			ignored.add(new LispSymbol("IGNORE"));
			for (String parameter : entry.lambdaList()) {
				LispSymbol symbol = new LispSymbol(parameter);
				parameters.add(symbol);
				if (!parameter.startsWith("&")) {
					ignored.add(symbol);
				}
			}
			LispVal lambdaList = list(parameters.toArray(LispVal[]::new));
			forms.add(list(new LispSymbol(LispNames.DEFGENERIC), name, lambdaList));
			forms.add(list(new LispSymbol(LispNames.DEFMETHOD), name, lambdaList,
					list(new LispSymbol(LispNames.DECLARE), list(ignored.toArray(LispVal[]::new))), refusal));
		}
		else if (entry.is("function") || entry.is("macro") || entry.is("generic")) {
			forms.add(list(new LispSymbol(LispNames.DEFUN), name,
					list(new LispSymbol(LispNames.LAMBDA_REST), new LispSymbol(STUB_ARGS)), refusal));
		}
		return forms;
	}

	/** A stub's superclass list, each spelled where the inventory homes it. */
	private static LispVal superclasses(AsdfExports.Entry entry) {
		List<LispVal> supers = new ArrayList<>();
		for (String superclass : entry.superclasses()) {
			supers.add(new LispSymbol(canonicalName(superclass)));
		}
		return list(supers.toArray(LispVal[]::new));
	}

	private static String canonicalName(String member) {
		AsdfExports.Entry external = AsdfExports.externals().get(member);
		if (external != null) {
			return external.isUiopHomed() ? UiopExports.qualified(member) : qualify(member);
		}
		return AsdfExports.owned().contains(member) ? PackageRegistry.qualifyInternal(LispNames.ASDF_PKG, member)
				: member;
	}

	private static String qualify(String member) {
		return PackageRegistry.qualify(LispNames.ASDF_PKG, member);
	}

	/**
	 * Whether the interpreter's lazy load should fire for a resolution of {@code name}
	 * (function or variable -- {@code asdf:*central-registry*} is a variable read).
	 * @param name the canonical qualified name being resolved
	 * @return {@code true} when the runtime defines it
	 */
	public static boolean definesName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn != null && LispNames.ASDF_PKG.equals(qn.pkg()) && definitions().functions().contains(qn.member());
	}

	/**
	 * Whether {@code form} mentions an ASDF class name or a generic -- the trigger for
	 * the interpreter to seed the runtime before a {@code defmethod} specializer /
	 * {@code typep} / {@code typecase} / {@code make-instance} resolves one.
	 * @param form the form about to be evaluated
	 * @return {@code true} when such a name occurs anywhere in it
	 */
	public static boolean mentionsComponentClass(LispVal form) {
		// The spine is a loop: the scan runs at whatever depth the evaluating program
		// has reached, so it costs frames for the form's NESTING, never its length.
		for (LispVal val = form;;) {
			if (val instanceof LispSymbol sym) {
				return isSeedingName(sym.name());
			}
			if (val instanceof LispCons cons) {
				if (mentionsComponentClass(cons.car())) {
					return true;
				}
				val = cons.cdr();
				continue;
			}
			return false;
		}
	}

	private static boolean isSeedingName(String name) {
		// A cheap test first: this runs over every class-resolving form until the
		// runtime loads, and few of their symbols are asdf's.
		if (!name.startsWith(LispNames.ASDF_PKG)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn == null || !LispNames.ASDF_PKG.equals(qn.pkg())) {
			return false;
		}
		Definitions defined = definitions();
		return defined.classes().contains(qn.member()) || defined.generics().contains(qn.member());
	}

	/**
	 * Whether a {@code handler-case} or {@code handler-bind} names an ASDF condition: the
	 * type test is built from the classes known when it is expanded, so a class first
	 * defined while its body loads a system would never match. Only the clause heads are
	 * read, which is what an evaluation of the form can afford.
	 * @param form the handler form
	 * @return {@code true} when a clause's type is an ASDF class
	 */
	public static boolean handlesAsdfCondition(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		boolean bind = cons.car() instanceof LispSymbol op && LispNames.HANDLER_BIND.equals(op.name());
		LispVal clauses = bind && cons.cdr() instanceof LispCons rest ? rest.car()
				: cons.cdr() instanceof LispCons rest ? rest.cdr() : LispNil.INSTANCE;
		for (LispVal cell = clauses; cell instanceof LispCons clauseCell; cell = clauseCell.cdr()) {
			if (clauseCell.car() instanceof LispCons clause && mentionsComponentClass(clause.car())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code name} may be a definition of this runtime: the gate in front of
	 * reading the definition names, so a compile that names no ASDF never reads it.
	 * @param name the canonical name
	 * @return {@code false} when no form of the runtime can define it
	 */
	public static boolean mayDefine(String name) {
		return name.startsWith("ASDF") || name.startsWith("%ASDF");
	}

	/**
	 * Returns the parsed {@code asdf.lisp} forms (canonical shape, no package resolution
	 * needed). Parsed once and cached.
	 * @return the forms
	 */
	public static List<LispVal> coreForms() {
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
	 * Returns the parsed {@code asdf-search.lisp} forms, parsed once and cached: the
	 * search for a system definition and the source registry.
	 * @return the forms
	 */
	private static List<LispVal> searchForms() {
		List<LispVal> cached = searchForms;
		if (cached == null) {
			synchronized (AsdfRuntimeLibrary.class) {
				cached = searchForms;
				if (cached == null) {
					cached = List
						.copyOf(LispReader.readAllFromString(readSource("asdf-search.lisp"), Features.INTERPRETER));
					searchForms = cached;
				}
			}
		}
		return cached;
	}

	/** The members {@code asdf-search.lisp} defines, each naming the whole file. */
	private static Set<String> searchMembers() {
		Set<String> members = new HashSet<>();
		Set<String> classes = new HashSet<>();
		Set<String> generics = new HashSet<>();
		for (LispVal form : searchForms()) {
			collectDefinition(form, members, classes, generics);
		}
		members.addAll(classes);
		return members;
	}

	/**
	 * Returns what the interpreter evaluates as the runtime: {@code asdf.lisp}, the
	 * search and every stub, superclasses first.
	 * @return the forms
	 */
	public static List<LispVal> interpreterForms() {
		List<LispVal> cached = imageForms;
		if (cached == null) {
			synchronized (AsdfRuntimeLibrary.class) {
				cached = imageForms;
				if (cached == null) {
					List<LispVal> forms = new ArrayList<>(coreForms());
					forms.addAll(searchForms());
					forms.addAll(stubsFor(definitions().stubs().keySet()));
					forms.addAll(LispReader.readAllFromString(IMAGE_SOURCE, Features.INTERPRETER));
					cached = List.copyOf(forms);
					imageForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * The forms whose names {@code LibraryDefunPruner} may drop when nothing reaches
	 * them. A stub is spliced only for a program naming it, so none is here.
	 * @return the forms to collect the names of
	 */
	public static List<LispVal> prunableForms() {
		List<LispVal> forms = new ArrayList<>();
		for (LispVal form : coreForms()) {
			if (!isLoweringTarget(form)) {
				forms.add(form);
			}
		}
		forms.addAll(searchForms());
		for (LispVal form : seamForms()) {
			if (!isLoweringTarget(form)) {
				forms.add(form);
			}
		}
		return List.copyOf(forms);
	}

	/**
	 * Whether a runtime form defines what a compiler's lowering of a component bracket
	 * calls ({@code SplicedComponentGroups}), which happens after the pruner has run and
	 * so is a reference it cannot see.
	 */
	private static boolean isLoweringTarget(LispVal form) {
		return form instanceof LispCons cons && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol defined
				&& SplicedComponentGroups.LOWERING_TARGETS.contains(defined.name());
	}

	/**
	 * The stub forms of {@code members} and of the stub superclasses they need,
	 * superclasses first: a class is defined before anything naming it.
	 */
	private static List<LispVal> stubsFor(Set<String> members) {
		Map<String, List<LispVal>> stubs = definitions().stubs();
		Set<String> ordered = new LinkedHashSet<>();
		for (String member : stubs.keySet()) {
			if (members.contains(member)) {
				placeStub(member, stubs, ordered);
			}
		}
		List<LispVal> forms = new ArrayList<>();
		for (String member : ordered) {
			forms.addAll(stubs.get(member));
		}
		return forms;
	}

	private static void placeStub(String member, Map<String, List<LispVal>> stubs, Set<String> ordered) {
		if (ordered.contains(member)) {
			return;
		}
		AsdfExports.Entry entry = AsdfExports.entry(member);
		if (entry != null) {
			for (String superclass : entry.superclasses()) {
				if (stubs.containsKey(superclass)) {
					placeStub(superclass, stubs, ordered);
				}
			}
		}
		ordered.add(member);
	}

	/**
	 * The compile-path pre-pass, run at the end of {@code LoadInliner.inline} (after the
	 * pathname folder, so a program whose only asdf use folded away -- the uax-15/quri
	 * literal shapes -- splices nothing): when the program references any runtime asdf
	 * name, prepends the class family plus the compile seam -- the baked
	 * {@code %asdf-registry%} table, the record defuns over it, the runtime
	 * {@code asdf:load-system}/{@code ql:quickload} ("already spliced" is a nil no-op,
	 * anything else the call-time error the nested forms always were), the component load
	 * and the recorded test-op wiring. It also drops {@code LoadInliner}'s component
	 * brackets from a program that defines no {@code asdf:perform} method.
	 * @param program the inlined and folded top-level forms
	 * @param systems the registry of every system the inline pass parsed
	 * @param loadedSystems the names of the systems it actually spliced
	 * @return the program with the asdf runtime spliced, or {@code program} unchanged
	 */
	public static List<LispVal> process(List<LispVal> program, Map<String, AsdfSystems.LispSystem> systems,
			Set<String> loadedSystems) {
		// The component brackets LoadInliner put around every spliced component matter
		// only to a program that defines an asdf:perform method (the compilers lower
		// them once its forms are expanded, SplicedComponentGroups); every other program
		// loses them here, as if they had never been emitted. One that keeps them needs
		// the runtime its lowering calls, whatever else it references.
		boolean performMethods = program.stream().anyMatch(SplicedComponentGroups::definesPerformMethod);
		if (!performMethods) {
			program = SplicedComponentGroups.stripComponentMarkers(program);
		}
		Set<String> mentioned = mentionedMembers(program);
		Definitions defined = definitions();
		boolean referenced = mentioned.stream()
			.anyMatch(member -> defined.functions().contains(member) || defined.classes().contains(member));
		if (!performMethods && !referenced) {
			return program;
		}
		boolean metadata = mentioned.stream().anyMatch(METADATA_READERS::contains);
		LoadedNames loaded = loadedClassNames(systems, loadedSystems);
		List<LispVal> out = new ArrayList<>(coreForms());
		boolean searches = mentioned.stream().anyMatch(searchMembers()::contains);
		if (searches) {
			out.addAll(searchForms());
		}
		Set<String> stubs = new HashSet<>(mentioned);
		stubs.addAll(loaded.asdfMembers());
		out.addAll(stubsFor(stubs));
		out.add(registryDefvar(systems, loadedSystems, metadata));
		out.addAll(seamForms());
		out.add(searchAtRunTimeDefun(searches));
		out.add(testOpEdgesDefun(systems, loadedSystems));
		out.add(recordedTestOpDefun(systems, loadedSystems));
		out.addAll(program);
		// Behind the program: they name classes its packages define.
		out.add(makeSystemDefun(loaded.systems()));
		out.add(reinitializeSystemDefun(loaded.systems()));
		out.add(makeComponentDefun(loaded.components()));
		return out;
	}

	/**
	 * The classes the spliced systems' definitions name, in splice order.
	 *
	 * @param systems the system classes
	 * @param components the component classes
	 */
	private record LoadedNames(Set<String> systems, Set<AsdfSystems.LoadedClass> components) {

		/** The members of the classes homed in {@code asdf}: stubs the program needs. */
		Set<String> asdfMembers() {
			Set<String> members = new HashSet<>();
			for (AsdfSystems.LoadedClass loaded : this.components) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(loaded.name());
				if (qn != null && LispNames.ASDF_PKG.equals(qn.pkg())) {
					members.add(qn.member());
				}
			}
			return members;
		}

	}

	private static LoadedNames loadedClassNames(Map<String, AsdfSystems.LispSystem> systems,
			Set<String> loadedSystems) {
		Set<String> systemClasses = new LinkedHashSet<>();
		Set<AsdfSystems.LoadedClass> componentClasses = new LinkedHashSet<>();
		for (AsdfSystems.LispSystem system : systems.values()) {
			if (!loadedSystems.contains(system.name())) {
				continue;
			}
			if (system.loadedClasses().system() != null) {
				systemClasses.add(system.loadedClasses().system());
			}
			collectLoadedClasses(system.tree().children(), componentClasses);
		}
		return new LoadedNames(systemClasses, componentClasses);
	}

	private static void collectLoadedClasses(List<AsdfSystems.ComponentNode> nodes,
			Set<AsdfSystems.LoadedClass> classes) {
		for (AsdfSystems.ComponentNode node : nodes) {
			if (node.loaded() != null) {
				classes.add(node.loaded());
			}
			collectLoadedClasses(node.children(), classes);
		}
	}

	/**
	 * The generated {@code %asdf-make-system} seam: an instance of a class a spliced
	 * system's definition names. A literal {@code make-instance} per class, so a program
	 * without one flips no computed-class gate; any other record is a system only the
	 * build image loaded the class of, and is a plain one.
	 */
	private static LispVal makeSystemDefun(Set<String> classes) {
		LispSymbol name = clUser("%ASDF-MAKE-SYSTEM-NAME");
		LispSymbol dir = clUser("%ASDF-MAKE-SYSTEM-DIR");
		LispSymbol version = clUser("%ASDF-MAKE-SYSTEM-VERSION");
		LispSymbol deps = clUser("%ASDF-MAKE-SYSTEM-DEPS");
		java.util.function.Function<String, LispVal> make = className -> list(cl(LispNames.MAKE_INSTANCE),
				quoted(new LispSymbol(className)), new LispSymbol(":NAME"), name, new LispSymbol(":PATHNAME"), dir,
				new LispSymbol(":ABSOLUTE-PATHNAME"), dir, new LispSymbol(":VERSION"), version,
				new LispSymbol(":SIDEWAY"), deps);
		List<LispVal> clauses = new ArrayList<>();
		for (String className : classes) {
			clauses.add(list(classIs(className), make.apply(className)));
		}
		return classDispatch("%ASDF-MAKE-SYSTEM", List.of(name, dir, version, deps), clauses,
				make.apply(qualify("SYSTEM")));
	}

	/**
	 * The generated {@code %asdf-reinitialize-system} seam: what real ASDF's
	 * {@code parse-defsystem} does to the system it registered, and where
	 * {@code hu.dwim.system} fills its slots. Nothing in a program no definition of which
	 * names a class, so that none carries the protocol for it.
	 */
	private static LispVal reinitializeSystemDefun(Set<String> classes) {
		LispSymbol system = clUser("%ASDF-REINITIALIZED-SYSTEM");
		return list(cl(LispNames.DEFUN), clUser("%ASDF-REINITIALIZE-SYSTEM"), list(system),
				classes.isEmpty() ? system : list(cl("REINITIALIZE-INSTANCE"), system));
	}

	/**
	 * The generated {@code %asdf-make-component} seam: an instance of a class a spliced
	 * system's definition names for a component, with the options the definition wrote
	 * for it, and a {@code cl-source-file} otherwise.
	 */
	private static LispVal makeComponentDefun(Set<AsdfSystems.LoadedClass> classes) {
		LispSymbol initargs = clUser("%ASDF-MAKE-COMPONENT-INITARGS");
		LispSymbol name = clUser("%ASDF-MAKE-COMPONENT-NAME");
		LispSymbol relative = clUser("%ASDF-MAKE-COMPONENT-RELATIVE");
		LispSymbol resolved = clUser("%ASDF-MAKE-COMPONENT-RESOLVED");
		LispSymbol parent = clUser("%ASDF-MAKE-COMPONENT-PARENT");
		java.util.function.BiFunction<String, List<LispVal>, LispVal> make = (className, options) -> {
			List<LispVal> call = new ArrayList<>(List.of(cl(LispNames.MAKE_INSTANCE), quoted(new LispSymbol(className)),
					new LispSymbol(":NAME"), name, new LispSymbol(":PATHNAME"), relative,
					new LispSymbol(":ABSOLUTE-PATHNAME"), resolved, new LispSymbol(":PARENT"), parent));
			for (int i = 0; i < options.size(); i += 2) {
				call.add(options.get(i));
				call.add(quoted(options.get(i + 1)));
			}
			return list(call.toArray(LispVal[]::new));
		};
		List<LispVal> clauses = new ArrayList<>();
		for (AsdfSystems.LoadedClass loaded : classes) {
			if (!loaded.initargs().isEmpty()) {
				clauses.add(list(
						list(cl("AND"), classIs(loaded.name()),
								list(cl(LispNames.EQUAL), initargs,
										quoted(list(loaded.initargs().toArray(LispVal[]::new))))),
						make.apply(loaded.name(), loaded.initargs())));
			}
		}
		for (AsdfSystems.LoadedClass loaded : classes) {
			if (loaded.initargs().isEmpty()) {
				clauses.add(list(classIs(loaded.name()), make.apply(loaded.name(), List.of())));
			}
		}
		return classDispatch("%ASDF-MAKE-COMPONENT", List.of(initargs, name, relative, resolved, parent), clauses,
				make.apply(qualify("CL-SOURCE-FILE"), List.of()));
	}

	/**
	 * {@code (defun NAME (%asdf-class PARAMS...) (cond CLAUSES... (t FALLBACK)))}, with
	 * every name spelled so it resolves to its canonical form whatever package the
	 * program ends in.
	 */
	private static LispVal classDispatch(String function, List<LispVal> params, List<LispVal> clauses,
			LispVal fallback) {
		List<LispVal> cond = new ArrayList<>();
		cond.add(cl(LispNames.COND));
		cond.addAll(clauses);
		cond.add(list(LispTrue.INSTANCE, fallback));
		List<LispVal> parameters = new ArrayList<>();
		parameters.add(clUser(CLASS_VAR));
		parameters.addAll(params);
		return list(cl(LispNames.DEFUN), clUser(function), list(parameters.toArray(LispVal[]::new)),
				list(cond.toArray(LispVal[]::new)));
	}

	private static LispVal classIs(String className) {
		return list(cl(LispNames.EQUAL), clUser(CLASS_VAR), new LispString(className));
	}

	private static LispVal quoted(LispVal datum) {
		return list(cl(LispNames.QUOTE), datum);
	}

	private static LispSymbol cl(String member) {
		return new LispSymbol(PackageRegistry.qualify(LispNames.CL_PKG, member));
	}

	private static LispSymbol clUser(String member) {
		return new LispSymbol(PackageRegistry.qualifyInternal(LispNames.CL_USER_PKG, member));
	}

	/**
	 * The {@code asdf} members a program names, read off its resolved copy so a bare
	 * {@code component-name} under {@code (:use :asdf)} and a sub-package's spelling
	 * count as the symbol they denote. A program spelling no ASDF package anywhere is not
	 * resolved at all.
	 */
	private static Set<String> mentionedMembers(List<LispVal> program) {
		if (program.stream().noneMatch(AsdfRuntimeLibrary::spellsAsdf)) {
			return Set.of();
		}
		List<LispVal> forms = program;
		try {
			forms = new PackageResolver().resolveProgram(program);
		}
		catch (LispPackageException ex) {
			// Not this pass's error to report: the backends run the same resolution
			// first thing. What is left is the spelling as written.
		}
		Set<String> members = new HashSet<>();
		for (LispVal form : forms) {
			collectMembers(form, members);
		}
		return members;
	}

	private static boolean spellsAsdf(LispVal form) {
		for (LispVal val = form;;) {
			if (val instanceof LispCons cons) {
				if (spellsAsdf(cons.car())) {
					return true;
				}
				val = cons.cdr();
				continue;
			}
			if (val instanceof LispSymbol sym) {
				return sym.name().contains(LispNames.ASDF_PKG) || sym.name().contains(LispNames.QL_PKG + ":");
			}
			return val instanceof LispString str && "asdf".equalsIgnoreCase(str.value());
		}
	}

	private static void collectMembers(LispVal form, Set<String> members) {
		for (LispVal val = form;;) {
			if (val instanceof LispCons cons) {
				collectMembers(cons.car(), members);
				val = cons.cdr();
				continue;
			}
			if (val instanceof LispSymbol sym) {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null && (AsdfExports.isAsdfFamily(qn.pkg())
						|| LispNames.QL_PKG.equals(qn.pkg()) && LispNames.QUICKLOAD.equals(qn.member()))) {
					members.add(LispNames.QL_PKG.equals(qn.pkg()) ? LispNames.LOAD_SYSTEM : qn.member());
				}
			}
			return;
		}
	}

	/**
	 * The record for one system, in the shape {@code asdf.lisp} reads:
	 * {@code (CLASS DIR COMPONENTS DEPS LOADED-P VERSION META ROOT)}.
	 * @param system the parsed system
	 * @param loaded whether the system has been loaded/spliced
	 * @return the record as Lisp data
	 */
	public static LispVal recordFor(AsdfSystems.LispSystem system, boolean loaded) {
		return recordFor(system, loaded, true);
	}

	private static LispVal recordFor(AsdfSystems.LispSystem system, boolean loaded, boolean metadata) {
		Map<AsdfSystems.ComponentNode, Integer> planIndexes = new java.util.IdentityHashMap<>();
		List<AsdfSystems.ComponentNode> plan = system.tree().plan();
		for (int index = 0; index < plan.size(); index++) {
			planIndexes.put(plan.get(index), index);
		}
		List<LispVal> deps = new ArrayList<>();
		for (String dependency : system.dependsOn()) {
			deps.add(new LispString(dependency));
		}
		String systemClass = system.loadedClasses().system();
		LispVal classOf = systemClass != null ? new LispString(systemClass)
				: new LispSymbol(system.packageInferredClass() ? ":PACKAGE-INFERRED-SYSTEM" : ":SYSTEM");
		return list(classOf, new LispString(normalizeDir(system.baseDir())),
				nodeRecords(system, system.tree().children(), planIndexes), list(deps.toArray(LispVal[]::new)),
				loaded ? LispTrue.INSTANCE : LispNil.INSTANCE,
				system.version() == null ? LispNil.INSTANCE : new LispString(system.version()),
				metadata ? metadataPlist(system) : LispNil.INSTANCE, new LispString(system.pathname()));
	}

	/**
	 * Components as {@code asdf.lisp} reads them:
	 * {@code (KIND NAME RELATIVE RESOLVED PLAN-INDEX . OPTIONS)}. {@code KIND} is a
	 * keyword for one of ASDF's own classes and a string for a class loaded code defines;
	 * what few components say goes in the {@code OPTIONS} plist, so that none carries it
	 * for nothing.
	 */
	private static LispVal nodeRecords(AsdfSystems.LispSystem system, List<AsdfSystems.ComponentNode> nodes,
			Map<AsdfSystems.ComponentNode, Integer> planIndexes) {
		List<LispVal> records = new ArrayList<>();
		for (AsdfSystems.ComponentNode node : nodes) {
			AsdfSystems.LoadedClass loaded = node.loaded();
			Integer index = planIndexes.get(node);
			List<LispVal> items = new ArrayList<>(
					List.of(loaded != null ? new LispString(loaded.name()) : new LispSymbol(":" + node.kind()),
							new LispString(node.name()), new LispString(node.relative()),
							new LispString(system.resolved(node.path())),
							index == null ? LispNil.INSTANCE : new LispInteger(index)));
			if (node.written() != null) {
				option(items, ":PATHNAME", new LispString(node.written()));
			}
			if (node.fileType() != null) {
				option(items, ":TYPE", new LispString(node.fileType()));
			}
			if (!node.sideway().isEmpty()) {
				option(items, ":SIDEWAY", list(node.sideway().stream().map(LispString::new).toArray(LispVal[]::new)));
			}
			if (node.ifFeature() != null) {
				option(items, ":IF-FEATURE", node.ifFeature());
			}
			if (!node.enabled()) {
				option(items, ":DISABLED", LispTrue.INSTANCE);
			}
			if (loaded != null && !loaded.initargs().isEmpty()) {
				option(items, ":INITARGS", list(loaded.initargs().toArray(LispVal[]::new)));
			}
			if (!node.children().isEmpty()) {
				option(items, ":CHILDREN", nodeRecords(system, node.children(), planIndexes));
			}
			records.add(list(items.toArray(LispVal[]::new)));
		}
		return list(records.toArray(LispVal[]::new));
	}

	private static void option(List<LispVal> items, String key, LispVal value) {
		items.add(new LispSymbol(key));
		items.add(value);
	}

	private static LispVal metadataPlist(AsdfSystems.LispSystem system) {
		List<LispVal> plist = new ArrayList<>();
		AsdfSystems.Metadata metadata = system.metadata();
		if (metadata.sourceFile() != null) {
			plist.add(new LispSymbol(":SOURCE-FILE"));
			plist.add(new LispString(metadata.sourceFile()));
		}
		metadata.options().forEach((option, value) -> {
			plist.add(new LispSymbol(option));
			plist.add(value);
		});
		if (!system.defsystemDependsOn().isEmpty()) {
			plist.add(new LispSymbol(":DEFSYSTEM-DEPENDS-ON"));
			plist.add(list(system.defsystemDependsOn().stream().map(LispString::new).toArray(LispVal[]::new)));
		}
		return list(plist.toArray(LispVal[]::new));
	}

	/**
	 * The record for a built-in shim system ({@code BuiltinSystems}): a plain system with
	 * no directory, files, dependencies or version.
	 * @param loaded whether the shim has been loaded
	 * @return the record as Lisp data
	 */
	public static LispVal builtinRecord(boolean loaded) {
		return list(new LispSymbol(":SYSTEM"), new LispString("./"), LispNil.INSTANCE, LispNil.INSTANCE,
				loaded ? LispTrue.INSTANCE : LispNil.INSTANCE, LispNil.INSTANCE, LispNil.INSTANCE);
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
	private static LispVal registryDefvar(Map<String, AsdfSystems.LispSystem> systems, Set<String> loadedSystems,
			boolean metadata) {
		List<LispVal> entries = new ArrayList<>();
		for (AsdfSystems.LispSystem system : systems.values()) {
			entries.add(new LispCons(new LispString(system.name()),
					recordFor(system, loadedSystems.contains(system.name()), metadata)));
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
	 * The generated {@code %asdf-search-at-run-time} seam: what {@code asdf:find-system}
	 * asks about a name the baked table does not hold. A program that names none of the
	 * search cannot have widened it, so it asks nothing and carries none of it.
	 */
	private static LispVal searchAtRunTimeDefun(boolean searches) {
		return LispReader.readAllFromString(searches ? SEARCH_AT_RUN_TIME : NO_SEARCH_AT_RUN_TIME, Features.INTERPRETER)
			.getFirst();
	}

	/**
	 * The generated {@code %asdf-test-op-edges} seam: the {@code :in-order-to} test-op
	 * edges of each spliced system that records any, which {@code asdf.lisp}'s test-op
	 * walks before performing test-op on the system itself.
	 */
	private static LispVal testOpEdgesDefun(Map<String, AsdfSystems.LispSystem> systems, Set<String> loadedSystems) {
		List<LispVal> cond = new ArrayList<>();
		cond.add(new LispSymbol(LispNames.COND));
		for (AsdfSystems.LispSystem system : systems.values()) {
			if (!loadedSystems.contains(system.name()) || system.testOpEdges().isEmpty()) {
				continue;
			}
			List<LispVal> edges = new ArrayList<>();
			for (String edge : system.testOpEdges()) {
				edges.add(new LispString(edge));
			}
			cond.add(list(keyTest(system), list(new LispSymbol(LispNames.QUOTE), list(edges.toArray(LispVal[]::new)))));
		}
		cond.add(list(LispTrue.INSTANCE, LispNil.INSTANCE));
		return list(new LispSymbol(LispNames.DEFUN), new LispSymbol("%ASDF-TEST-OP-EDGES"),
				list(new LispSymbol("%ASDF-KEY")), list(cond.toArray(LispVal[]::new)));
	}

	/**
	 * The generated {@code %asdf-run-recorded-test-op} seam: the default
	 * {@code asdf:perform} method for test-op on a system calls the
	 * {@code %asdf-test-op-<name>} defun of the spliced system's recorded
	 * {@code :perform (test-op ...)} body with the operation and the component; a system
	 * that records none is real ASDF's default no-op.
	 */
	private static LispVal recordedTestOpDefun(Map<String, AsdfSystems.LispSystem> systems, Set<String> loadedSystems) {
		List<LispVal> cond = new ArrayList<>();
		cond.add(new LispSymbol(LispNames.COND));
		for (AsdfSystems.LispSystem system : systems.values()) {
			if (!loadedSystems.contains(system.name()) || system.testOp() == null) {
				continue;
			}
			cond.add(list(keyTest(system), list(new LispSymbol(testOpDefunName(system.name())),
					new LispSymbol("%ASDF-O"), new LispSymbol("%ASDF-C"))));
		}
		cond.add(list(LispTrue.INSTANCE, LispNil.INSTANCE));
		return list(new LispSymbol(LispNames.DEFUN), new LispSymbol("%ASDF-RUN-RECORDED-TEST-OP"),
				list(new LispSymbol("%ASDF-KEY"), new LispSymbol("%ASDF-O"), new LispSymbol("%ASDF-C")),
				list(cond.toArray(LispVal[]::new)));
	}

	private static LispVal keyTest(AsdfSystems.LispSystem system) {
		return list(new LispSymbol(LispNames.EQUAL), new LispSymbol("%ASDF-KEY"), new LispString(system.name()));
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
	// runtime load forms and the component load of the operation protocol (the
	// interpreter keeps all of these in Java over its live registry). A spliced
	// component is not loaded at run time: once the program defines an asdf:perform
	// method, its forms are a top-level group whose thunk %asdf-load-spliced hands the
	// protocol (compiler.SplicedComponentGroups), and load-op's primary method runs that
	// thunk. Any other component load is the error a run-time load-system is. An image op
	// (asdf:make of a program-op system) refuses: dumping is the interpreter's.
	private static final String SEAM_SOURCE = """
			(defun %asdf-baked-record (key)
			  (let ((found nil))
			    (dolist (entry %asdf-registry%)
			      (when (and (not found) (equal (car entry) key))
			        (setq found (cdr entry))))
			    found))
			(defvar %asdf-cleared% nil)
			(defun %asdf-system-record (key)
			  (if (member key %asdf-cleared% :test #'equal)
			      nil
			      (%asdf-baked-record key)))
			(defun %asdf-system-names ()
			  (remove-if (lambda (name) (member name %asdf-cleared% :test #'equal))
			             (mapcar (lambda (entry) (car entry)) %asdf-registry%)))
			(defun %asdf-clear-system (key)
			  (when (%asdf-baked-record key)
			    (setq %asdf-cleared% (cons key %asdf-cleared%))))
			(defun %asdf-locate-system (key)
			  (if (member key %asdf-cleared% :test #'equal)
			      (setq %asdf-cleared% (remove key %asdf-cleared% :test #'equal))
			      (%asdf-search-at-run-time key)))
			(defun %asdf-note-found-system (key) key)
			(defun %asdf-derive-system (key) (%asdf-registered-instance key))
			(defun %asdf-search-path () nil)
			(defun %asdf-search-path-locate (name inherits) nil)
			(defun %asdf-load-asd (path)
			  (error "ASDF:LOAD-ASD cannot load ~a at run time on the compiled backends (systems are spliced at compile time)"
			         path))
			(defun %asdf-check-image-build (operation system)
			  (error "ASDF:OPERATE: ~a on ~a dumps an image, which a compiled program has no compiler for: run rontolisp build ~a"
			         operation system system))
			(defmethod asdf:perform ((operation asdf:image-op) (component asdf:system))
			  (%asdf-check-image-build (class-name (class-of operation)) (asdf:component-name component)))
			(defun %asdf-runtime-load (operator name)
			  (let ((rec (%asdf-baked-record (asdf:coerce-name name))))
			    (if (and rec (nth 4 rec))
			        nil
			        (error "~a cannot load a system at run time on the compiled backends (systems are spliced at compile time): ~a"
			               operator (asdf:coerce-name name)))))
			(defun asdf:load-system (name &rest options)
			  (%asdf-runtime-load "ASDF:LOAD-SYSTEM" name))
			(defun ql:quickload (name &rest options)
			  (if (consp name)
			      (dolist (entry name)
			        (%asdf-runtime-load "QL:QUICKLOAD" entry))
			      (%asdf-runtime-load "QL:QUICKLOAD" name))
			  name)
			(defvar %asdf-spliced-thunks% nil)
			(defun %asdf-load-spliced (system index thunk)
			  (setq %asdf-spliced-thunks% (cons (list system index thunk) %asdf-spliced-thunks%))
			  (%asdf-perform-component system index))
			(defun %asdf-load-component (system index)
			  (let ((thunk nil))
			    (dolist (entry %asdf-spliced-thunks%)
			      (when (and (not thunk) (equal (car entry) system) (eql (car (cdr entry)) index))
			        (setq thunk (car (cdr (cdr entry))))))
			    (if thunk
			        (funcall thunk)
			        (error "ASDF:PERFORM cannot load ~a of system ~a at run time on the compiled backends (systems are spliced at compile time)"
			               (asdf:component-name (%asdf-planned-component system index))
			               system))))
			""";

	// A pushed search function runs here, as the program's own function it is. A system
	// it
	// answers is registered; a file cannot be loaded at run time.
	private static final String SEARCH_AT_RUN_TIME = """
			(defun %asdf-search-at-run-time (key)
			  (let ((found (%asdf-check-found (asdf:search-for-system-definition key))))
			    (cond ((typep found 'asdf:system) (%asdf-adopt-system key found))
			          (found (%asdf-load-asd found)))))
			""";

	private static final String NO_SEARCH_AT_RUN_TIME = """
			(defun %asdf-search-at-run-time (key) key nil)
			""";

	// What only an image asks or does, the interpreter's half of the seams above: what a
	// definition's class designator means (real ASDF's class-for-type, over a prototype
	// of
	// the class; a keyword finds its symbol in asdf or the current package, a string
	// reads
	// in asdf), and an instance of a class named at run time. A compile path generates
	// the
	// second over the classes it spliced, and runs the first in its build image.
	// And the image ops' methods, ASDF 3.3.1's: where a program-op writes (its
	// bundle-output-files: :build-pathname below the system's directory, by default the
	// system's name, no type on a Unix host), the entry point it restores, and the dump.
	// Only an image dumps (a compiled program's seam refuses before the perform), and
	// here they keep uiop:ensure-function, and through it eval, out of every compiled
	// program that loads a system.
	private static final String IMAGE_SOURCE = """
			(defun %asdf-class-named (designator)
			  (let* ((name (cond ((keywordp designator)
			                      (or (find-symbol (symbol-name designator) :asdf)
			                          (find-symbol (symbol-name designator) *package*)))
			                     ((stringp designator)
			                      (handler-case
			                          (let ((*package* (find-package :asdf)))
			                            (read-from-string designator))
			                        (error () nil)))
			                     (t designator)))
			         (class (and name (symbolp name) (not (keywordp name)) (find-class name nil))))
			    (and class (subtypep name 'asdf:component) class)))
			(defun %asdf-class-facts (designator)
			  (let ((class (%asdf-class-named designator)))
			    (when class
			      (let* ((prototype (make-instance (class-name class) :name "%asdf-prototype"))
			             (file (typep prototype 'asdf:file-component))
			             (hooked (slot-boundp prototype 'asdf::around-compile)))
			        (list (class-name class)
			              (typep prototype 'asdf:system)
			              (typep prototype 'asdf:package-inferred-system)
			              (typep prototype 'asdf:module)
			              file
			              (typep prototype 'asdf:cl-source-file)
			              (and file
			                   (asdf:source-file-type prototype
			                                          (make-instance 'asdf:system :name "%asdf-prototype")))
			              (and (typep prototype 'asdf:parent-component)
			                   (asdf::module-default-component-class prototype))
			              hooked
			              (and hooked (slot-value prototype 'asdf::around-compile)))))))
			(defun %asdf-search-file (key)
			  (let ((found (%asdf-check-found (asdf:search-for-system-definition key))))
			    (cond ((typep found 'asdf:system) (%asdf-adopt-system key found) t)
			          (found (namestring found)))))
			(defmethod asdf:output-files ((operation asdf:image-op) (component asdf:system))
			  (let* ((program (typep operation 'asdf:program-op))
			         (name (or (asdf::component-build-pathname component)
			                   (if program
			                       (asdf:component-name component)
			                       (concatenate 'string (asdf:component-name component) "--all-systems")))))
			    (values (list (if (and (pathnamep name) (uiop/pathname:absolute-pathname-p name))
			                      name
			                      (uiop/pathname:subpathname (asdf:component-pathname component) name
			                                                 :type (if program nil "image"))))
			            t)))
			(defmethod asdf:perform :before ((operation asdf:program-op) (component asdf:system))
			  (setf uiop/image:*image-entry-point*
			        (uiop/utility:ensure-function (asdf::component-entry-point component))))
			(defmethod asdf:perform ((operation asdf:image-op) (component asdf:system))
			  (uiop/image:dump-image (asdf:output-file operation component)
			                         :executable (typep operation 'asdf:program-op)))
			(defun %asdf-derive-system (key)
			  (and (%asdf-infer-system key) (%asdf-registered-instance key)))
			(defun %asdf-read-source-registry (parameter)
			  (let ((asdf:*source-registry-parameter* nil)
			        (asdf::*source-registry* nil)
			        (%asdf-registry-behind% nil)
			        (%asdf-registry-inherits% t))
			    (asdf:initialize-source-registry parameter)
			    (flet ((entries (table)
			             (let ((alist nil))
			               (maphash (lambda (name asd)
			                          (setq alist (cons (cons name (namestring asd)) alist)))
			                        table)
			               alist)))
			      (list (entries asdf::*source-registry*)
			            %asdf-registry-inherits%
			            (entries %asdf-registry-behind%)))))
			(defun %asdf-make-system (class name dir version deps)
			  (make-instance (read-from-string class) :name name :pathname dir
			                 :absolute-pathname dir :version version :sideway deps))
			(defun %asdf-reinitialize-system (system)
			  (reinitialize-instance system))
			(defun %asdf-make-component (class initargs name relative resolved parent)
			  (apply #'make-instance (read-from-string class) :name name :pathname relative
			         :absolute-pathname resolved :parent parent initargs))
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

	private static LispVal list(LispVal... items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			tail = new LispCons(items[i], tail);
		}
		return tail;
	}

}
