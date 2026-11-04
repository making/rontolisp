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
 * A record is {@code (CLASS DIR FILES DEPS LOADED-P VERSION META)}: the component class
 * keyword ({@code :system} / {@code :package-inferred-system}), the source directory with
 * a trailing slash, the component files as {@code (RELATIVE . RESOLVED)} namestring pairs
 * in load order, the {@code :depends-on} names, whether the system is loaded, the
 * declared {@code :version} string (nil when it declared none as a plain string), and the
 * plist of what the {@code .asd} declared besides.
 *
 * <p>
 * The surface is the inventory ({@link AsdfExports}): an external {@code asdf.lisp} does
 * not define is a signalling stub or an empty class synthesized here
 * ({@link #stubForms}). The interpreter evaluates them all with the runtime; a compile
 * path splices the ones the program names and the superclasses they need.
 */
public final class AsdfRuntimeLibrary {

	private AsdfRuntimeLibrary() {
	}

	@Nullable private static volatile List<LispVal> classForms;

	@Nullable private static volatile List<LispVal> seamForms;

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
			forms.add(list(new LispSymbol(LispNames.DEFCLASS), name, superclasses(entry), LispNil.INSTANCE));
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
	 * {@link #definitionNames}, so a compile that names no ASDF never reads it.
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
	 * Returns what the interpreter evaluates as the runtime: {@code asdf.lisp} and every
	 * stub, superclasses first.
	 * @return the forms
	 */
	public static List<LispVal> interpreterForms() {
		List<LispVal> forms = new ArrayList<>(coreForms());
		forms.addAll(stubsFor(definitions().stubs().keySet()));
		return List.copyOf(forms);
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
		List<LispVal> out = new ArrayList<>(coreForms());
		out.addAll(stubsFor(mentioned));
		out.add(registryDefvar(systems, loadedSystems, metadata));
		out.addAll(seamForms());
		out.add(testOpEdgesDefun(systems, loadedSystems));
		out.add(recordedTestOpDefun(systems, loadedSystems));
		out.addAll(program);
		return out;
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
	 * {@code (CLASS DIR FILES DEPS LOADED-P VERSION META)}.
	 * @param system the parsed system
	 * @param loaded whether the system has been loaded/spliced
	 * @return the record as Lisp data
	 */
	public static LispVal recordFor(AsdfSystems.LispSystem system, boolean loaded) {
		return recordFor(system, loaded, true);
	}

	private static LispVal recordFor(AsdfSystems.LispSystem system, boolean loaded, boolean metadata) {
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
				system.version() == null ? LispNil.INSTANCE : new LispString(system.version()),
				metadata ? metadataPlist(system) : LispNil.INSTANCE);
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
	// thunk. Any other component load is the error a run-time load-system is.
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
			  (when (member key %asdf-cleared% :test #'equal)
			    (setq %asdf-cleared% (remove key %asdf-cleared% :test #'equal))
			    (%asdf-baked-record key)))
			(defun %asdf-load-asd (path)
			  (error "ASDF:LOAD-ASD cannot load ~a at run time on the compiled backends (systems are spliced at compile time)"
			         path))
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
			(defvar %asdf-pending-load% nil)
			(defun %asdf-load-spliced (system index thunk)
			  (let ((%asdf-pending-load% (list system index thunk)))
			    (%asdf-perform-component system index)))
			(defun %asdf-load-component (system index)
			  (let ((pending %asdf-pending-load%))
			    (if (and pending (equal (car pending) system) (eql (car (cdr pending)) index))
			        (funcall (car (cdr (cdr pending))))
			        (error "ASDF:PERFORM cannot load ~a of system ~a at run time on the compiled backends (systems are spliced at compile time)"
			               (asdf:component-name (nth index (asdf:component-children (asdf:find-system system))))
			               system))))
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
