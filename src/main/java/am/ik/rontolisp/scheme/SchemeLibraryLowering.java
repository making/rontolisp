package am.ik.rontolisp.scheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Libraries of the Scheme lowering: the {@code import} declarations that pick what the
 * global scope holds, the R7RS standard libraries, user {@code define-library}s lowered
 * once as whole files of their own (their names private, their exports the bindings
 * themselves), and the {@code include} and {@code cond-expand} forms spliced before the
 * lowering sees the program.
 *
 * <p>
 * One slice of {@link SchemeLowering}: every method takes the hub as its first argument
 * and re-enters it for subforms.
 */
final class SchemeLibraryLowering {

	private SchemeLibraryLowering() {
	}

	/** The R7RS libraries {@code (import (scheme <name>))} accepts. */
	private static final List<String> IMPORTABLE_LIBRARIES = List.of("base", "write", "read", "char", "inexact",
			"complex", "cxr", "lazy", "case-lambda", "process-context", "eval", "repl", "load", "file");

	/**
	 * {@code (defun rontolisp::%scheme-library-p (name) ...)}: whether
	 * {@code (scheme name)} is one of {@link #IMPORTABLE_LIBRARIES}, for what a run-time
	 * {@code (environment '(scheme base))} checks its import sets against. Generated so
	 * the list is spelled once.
	 * @return the definition, in the library's canonical shape
	 */
	static LispVal libraryPredicateForm() {
		List<LispVal> names = new ArrayList<>();
		for (String library : IMPORTABLE_LIBRARIES) {
			names.add(SchemeLowering.symbol(library));
		}
		LispSymbol name = SchemeLowering.symbol("NAME");
		return SchemeLowering.list(SchemeLowering.symbol("DEFUN"),
				SchemeLowering.symbol("RONTOLISP::%SCHEME-LIBRARY-P"), SchemeLowering.list(name),
				SchemeLowering.list(SchemeLowering.symbol("IF"),
						SchemeLowering.list(SchemeLowering.symbol("MEMBER"), name,
								SchemeLowering.list(SchemeLowering.symbol("QUOTE"), SchemeLowering.listOf(names))),
						LispTrue.INSTANCE, LispNil.INSTANCE));
	}

	/**
	 * {@code (defun rontolisp::%scheme-eval-extension-keyword-p (name) ...)}: whether
	 * {@code name} is a keyword {@code eval} knows beyond R7RS -- the {@code sicp} syntax
	 * ({@code cons-stream}), none under {@link SchemeStandard#R7RS}. Generated from the
	 * same table the lowering reads, so the list is spelled once.
	 * @param standard the standard the program is read against
	 * @return the definition, in the library's canonical shape
	 */
	static LispVal extensionKeywordForm(SchemeStandard standard) {
		List<LispVal> names = new ArrayList<>();
		if (standard == SchemeStandard.RONTOLISP) {
			for (String keyword : SchemeLowering.SICP_SYNTAX.keySet()) {
				names.add(SchemeLowering.symbol(SchemeNames.mangle(keyword)));
			}
		}
		LispSymbol name = SchemeLowering.symbol("NAME");
		return SchemeLowering.list(SchemeLowering.symbol("DEFUN"),
				SchemeLowering.symbol("RONTOLISP::%SCHEME-EVAL-EXTENSION-KEYWORD-P"), SchemeLowering.list(name),
				SchemeLowering.list(SchemeLowering.symbol("IF"),
						SchemeLowering.list(SchemeLowering.symbol("MEMBER"), name,
								SchemeLowering.list(SchemeLowering.symbol("QUOTE"), SchemeLowering.listOf(names))),
						LispTrue.INSTANCE, LispNil.INSTANCE));
	}

	// Leading (import ...) forms -- after the leading define-library forms, at `start` --
	// pick what the global scope holds; a program with none sees everything, like a REPL.
	static int imports(SchemeLowering s, int start) {
		int index = start;
		Map<String, SchemeLowering.Binding> imported = new LinkedHashMap<>();
		while (resolveTopLevelCondExpand(s, index) && s.datums.get(index) instanceof LispCons form
				&& form.car() instanceof LispSymbol head && head.name().equals("import")) {
			for (LispVal set : s.elements(form.cdr(), form)) {
				imported.putAll(importSet(s, set, form));
			}
			index++;
		}
		if (index == start) {
			// R7RS 5.1: a program begins with an import declaration. A session has no
			// program to begin, and starts with every library instead; a file of
			// libraries alone has no program.
			if (s.standard == SchemeStandard.R7RS && !s.interactive && (start == 0 || start < s.datums.size())) {
				SourceLocation first = start < s.datums.size() ? s.reader.locate(s.datums.get(start)) : null;
				throw new LispReadException("an R7RS program begins with an import declaration",
						first != null ? first : s.reader.locateFirstDatum());
			}
			imported.putAll(everything(s));
		}
		for (Map.Entry<String, SchemeLowering.Binding> entry : imported.entrySet()) {
			s.global.bindings.put(SchemeNames.mangle(entry.getKey()), entry.getValue());
		}
		return index;
	}

	// What a program, a library or a session with no import declaration sees.
	static Map<String, SchemeLowering.Binding> everything(SchemeLowering s) {
		Map<String, SchemeLowering.Binding> imported = new LinkedHashMap<>();
		for (String library : IMPORTABLE_LIBRARIES) {
			imported.putAll(library(s, library));
		}
		// Not R7RS exports, so not reachable by name through (import ...): a REPL, and a
		// file with no import at all, sees them anyway, the way an unqualified SICP
		// sample -- written against an implementation that already had them -- expects.
		// r5rs is the same shape: (scheme r5rs) would promise all of R5RS. Strict R7RS
		// sees neither.
		if (s.standard == SchemeStandard.RONTOLISP) {
			imported.putAll(library(s, "sicp"));
			imported.putAll(library(s, "r5rs"));
		}
		return imported;
	}

	static Map<String, SchemeLowering.Binding> importSet(SchemeLowering s, LispVal set, LispCons form) {
		List<LispVal> parts = s.elements(set, form);
		if (parts.isEmpty() || !(parts.get(0) instanceof LispSymbol head)) {
			throw s.error("malformed import set", form);
		}
		boolean modifier = parts.size() >= 2 && parts.get(1) instanceof LispCons;
		if (!modifier) {
			if (parts.size() == 2 && head.name().equals("scheme") && parts.get(1) instanceof LispSymbol name
					&& IMPORTABLE_LIBRARIES.contains(name.name())) {
				return library(s, name.name());
			}
			if (!head.name().equals("scheme")) {
				return userLibrary(s, libraryName(s, set, form), form);
			}
			List<String> names = IMPORTABLE_LIBRARIES.stream().map(l -> "(scheme " + l + ")").toList();
			throw s.error("library " + set.print() + " is not available: this experimental front end has "
					+ String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast() + " only",
					form);
		}
		Map<String, SchemeLowering.Binding> base = importSet(s, parts.get(1), form);
		Map<String, SchemeLowering.Binding> result = new LinkedHashMap<>();
		List<LispVal> arguments = parts.subList(2, parts.size());
		switch (head.name()) {
			case "only" -> {
				for (LispVal argument : arguments) {
					String name = s.identifier(argument, form).name();
					result.put(name, imported(s, base, name, form));
				}
			}
			case "except" -> {
				result.putAll(base);
				for (LispVal argument : arguments) {
					String name = s.identifier(argument, form).name();
					imported(s, base, name, form);
					result.remove(name);
				}
			}
			case "prefix" -> {
				String prefix = s.identifier(arguments.size() == 1 ? arguments.get(0) : LispNil.INSTANCE, form).name();
				base.forEach((name, binding) -> result.put(prefix + name, binding));
			}
			case "rename" -> {
				result.putAll(base);
				for (LispVal argument : arguments) {
					List<LispVal> pair = s.elements(argument, form);
					if (pair.size() != 2) {
						throw s.error("malformed rename", form);
					}
					String from = s.identifier(pair.get(0), form).name();
					SchemeLowering.Binding binding = imported(s, base, from, form);
					result.remove(from);
					result.put(s.identifier(pair.get(1), form).name(), binding);
				}
			}
			default -> throw s.error("unknown import set: " + head.name(), form);
		}
		return result;
	}

	private static SchemeLowering.Binding imported(SchemeLowering s, Map<String, SchemeLowering.Binding> base,
			String name, LispCons form) {
		SchemeLowering.Binding binding = base.get(name);
		if (binding == null) {
			throw s.error("the library does not export " + name, form);
		}
		return binding;
	}

	static Map<String, SchemeLowering.Binding> library(SchemeLowering s, String library) {
		Map<String, SchemeLowering.Binding> exports = new LinkedHashMap<>();
		if (library.equals("base")) {
			SchemeLowering.SYNTAX.forEach((name, core) -> exports.put(name, new SchemeLowering.Syntax(core, name)));
		}
		if (library.equals("lazy")) {
			SchemeLowering.LAZY_SYNTAX
				.forEach((name, core) -> exports.put(name, new SchemeLowering.Syntax(core, name)));
		}
		if (library.equals("case-lambda")) {
			SchemeLowering.CASE_LAMBDA_SYNTAX
				.forEach((name, core) -> exports.put(name, new SchemeLowering.Syntax(core, name)));
		}
		if (library.equals("sicp")) {
			SchemeLowering.SICP_SYNTAX
				.forEach((name, core) -> exports.put(name, new SchemeLowering.Syntax(core, name)));
			SchemeBuiltins.constants().forEach((name, form) -> exports.put(name, new SchemeLowering.Constant(form)));
		}
		for (SchemeBuiltins.Entry entry : SchemeBuiltins.entries(s.standard).values()) {
			if (entry.library().equals(library)) {
				exports.put(entry.name(), new SchemeLowering.Builtin(entry));
			}
		}
		return exports;
	}

	// ------------------------------------------------------------------ libraries

	static boolean isLibraryDefinition(LispVal datum) {
		return datum instanceof LispCons form && form.car() instanceof LispSymbol head
				&& head.name().equals("define-library");
	}

	// The leading define-library forms of a file: declared here, lowered when imported.
	static int declareLibraries(SchemeLowering s) {
		int index = 0;
		while (resolveTopLevelCondExpand(s, index) && isLibraryDefinition(s.datums.get(index))) {
			declareLibrary(s, (LispCons) s.datums.get(index), s.reader, s.reader.file());
			index++;
		}
		return index;
	}

	// A cond-expand standing at the top level at `index` is replaced by the datums of the
	// clause it takes, until the datum there is none: what the leading scans (the
	// define-library, then the import declarations) run over, so a clause may hold
	// either, and a (library ...) requirement sees every library declared before it.
	// Spelled, not resolved: there is no scope before the imports, as for `import`.
	// Answers whether a datum stands at `index`.
	static boolean resolveTopLevelCondExpand(SchemeLowering s, int index) {
		while (index < s.datums.size() && s.datums.get(index) instanceof LispCons form
				&& form.car() instanceof LispSymbol head && head.name().equals("cond-expand")) {
			List<LispVal> taken = condExpandBody(s, form);
			s.datums.remove(index);
			s.datums.addAll(index, taken);
		}
		return index < s.datums.size();
	}

	// The datums of the clause a (cond-expand clause...) takes.
	static List<LispVal> condExpandBody(SchemeLowering s, LispCons form) {
		List<LispVal> clauses = s.elements(form.cdr(), form);
		int taken = SchemeFeatures.clause(form, featureHost(s));
		return SchemeFeatures.body(clauses.get(taken), form, featureHost(s));
	}

	// (cond-expand clause...) as the (begin datums...) of the clause it takes.
	static LispCons condExpanded(SchemeLowering s, LispCons form) {
		return s.positioned(form,
				new LispCons(SchemeLowering.CORE_BEGIN, SchemeLowering.listOf(condExpandBody(s, form))));
	}

	static SchemeFeatures.Host featureHost(SchemeLowering s) {

		return new SchemeFeatures.Host() {

			@Override
			public boolean libraryAvailable(LispVal name, LispCons form) {
				return SchemeLibraryLowering.libraryAvailable(s, libraryName(s, name, form));
			}

			@Override
			public LispReadException error(String message, LispCons form) {
				return s.error(message, form);
			}

		};
	}

	// (library name) of cond-expand: a standard library this front end has, or a user
	// library declared already or found as a file -- whether an import of it would find
	// it, without lowering it.
	private static boolean libraryAvailable(SchemeLowering s, List<String> name) {
		if (name.getFirst().equals("scheme")) {
			return name.size() == 2 && IMPORTABLE_LIBRARIES.contains(name.get(1));
		}
		if (s.libraries.declared(name) != null) {
			return true;
		}
		SchemeFiles.Source source = libraryFileSource(s, name);
		if (source != null) {
			declareLibraryFile(s, source);
		}
		return s.libraries.declared(name) != null;
	}

	static void declareLibrary(SchemeLowering s, LispCons form, SchemeReader reader, @Nullable String file) {
		List<String> name = libraryName(s, s.second(form), form);
		if (name.getFirst().equals("scheme")) {
			throw s.error("library names beginning with scheme are reserved: " + printed(name), form);
		}
		if (!s.libraries.declare(name, new SchemeLibraries.Declaration(form, reader, file))) {
			throw s.error("library " + printed(name) + " is defined twice", form);
		}
	}

	// A library name, R7RS 5.6.1: identifiers and exact non-negative integers.
	static List<String> libraryName(SchemeLowering s, LispVal datum, LispCons form) {
		List<String> name = new ArrayList<>();
		LispVal rest = datum;
		while (rest instanceof LispCons cell) {
			switch (cell.car()) {
				case LispSymbol part when part != SchemeReader.TRUE && part != SchemeReader.FALSE ->
					name.add(part.name());
				case LispInteger integer when integer.value() >= 0 -> name.add(Long.toString(integer.value()));
				default -> throw s.error("malformed library name: " + SchemeExpander.written(datum), form);
			}
			rest = cell.cdr();
		}
		if (name.isEmpty() || rest != LispNil.INSTANCE) {
			throw s.error("malformed library name: " + SchemeExpander.written(datum), form);
		}
		return name;
	}

	static String printed(List<String> name) {
		return "(" + String.join(" ", name) + ")";
	}

	// An import of a user library: lowered the first time, its exports every time.
	static Map<String, SchemeLowering.Binding> userLibrary(SchemeLowering s, List<String> name, LispCons form) {
		Map<String, SchemeLowering.Binding> exports = s.libraries.exports(name);
		if (exports == null) {
			exports = instantiate(s, name, form);
		}
		for (SchemeLowering.Binding binding : exports.values()) {
			if (binding instanceof SchemeLowering.Variable || binding instanceof SchemeLowering.GlobalFunction
					|| binding instanceof SchemeLowering.GlobalPredicate
					|| binding instanceof SchemeLowering.ImportedSyntax) {
				s.libraryImports.add(binding);
			}
		}
		return exports;
	}

	private static Map<String, SchemeLowering.Binding> instantiate(SchemeLowering s, List<String> name, LispCons form) {
		SchemeLibraries.Declaration declaration = s.libraries.declared(name);
		if (declaration == null) {
			declaration = libraryFile(s, name, form);
		}
		List<List<String>> cycle = s.libraries.enter(name);
		if (cycle != null) {
			throw s.error("library import cycle: "
					+ String.join(" -> ", cycle.stream().map(SchemeLibraryLowering::printed).toList()), form);
		}
		boolean done = false;
		try {
			SchemeLowering library = new SchemeLowering(declaration.reader(), false, s.standard, s.libraries,
					SchemeNames.libraryPrefix(name));
			Map<String, SchemeLowering.Binding> exports = lowerLibrary(library, declaration);
			s.libraries.leave(name, exports, library.libraryForms);
			done = true;
			return exports;
		}
		finally {
			if (!done) {
				s.libraries.abandon(name);
			}
		}
	}

	// (a b) is a/b.sld (else a/b.scm) beside the file the lowering started from, the way
	// Gauche finds it on its load path; every define-library in that file is declared.
	private static SchemeLibraries.Declaration libraryFile(SchemeLowering s, List<String> name, LispCons form) {
		SchemeFiles.Source source = libraryFileSource(s, name);
		if (source == null) {
			throw s.error("library " + printed(name) + " is not available: no define-library of it precedes the"
					+ " program and there is no " + String.join("/", name) + ".sld", form);
		}
		declareLibraryFile(s, source);
		SchemeLibraries.Declaration declaration = s.libraries.declared(name);
		if (declaration == null) {
			throw s.error(source.path() + " does not define library " + printed(name), form);
		}
		return declaration;
	}

	// (a b) is a/b.sld, else a/b.scm, or null.
	private static SchemeFiles.@Nullable Source libraryFileSource(SchemeLowering s, List<String> name) {
		String stem = String.join("/", name);
		for (String extension : List.of(".sld", ".scm")) {
			SchemeFiles.Source source = s.libraries.files().find(s.libraries.root(), stem + extension);
			if (source != null) {
				return source;
			}
		}
		return null;
	}

	static void declareLibraryFile(SchemeLowering s, SchemeFiles.Source source) {
		SchemeReader reader = new SchemeReader(source.text(), source.path());
		for (LispVal datum : reader.readAll()) {
			if (isLibraryDefinition(datum)) {
				LispCons definition = (LispCons) datum;
				s.libraries.declare(libraryName(s, s.second(definition), definition),
						new SchemeLibraries.Declaration(definition, reader, source.path()));
			}
		}
	}

	/**
	 * Lowers a library's body as a whole file of its own: its imports are its scope, its
	 * top-level names are private ({@link SchemeNames#libraryPrefix}), and what it
	 * exports reaches an importer as the bindings themselves -- a {@code defun} stays a
	 * direct call there.
	 */
	static Map<String, SchemeLowering.Binding> lowerLibrary(SchemeLowering s, SchemeLibraries.Declaration declaration) {
		List<LispCons> importForms = new ArrayList<>();
		List<LispCons> exportForms = new ArrayList<>();
		List<Chunk> chunks = new ArrayList<>();
		LispCons form = declaration.form();
		List<LispVal> parts = s.elements(form, form);
		libraryDeclarations(s, parts.subList(2, parts.size()), declaration.file(), form,
				new Declarations(importForms, exportForms, chunks), new java.util.ArrayDeque<>());
		Map<String, SchemeLowering.Binding> imported = new LinkedHashMap<>();
		for (LispCons importForm : importForms) {
			for (LispVal set : s.elements(importForm.cdr(), importForm)) {
				imported.putAll(importSet(s, set, importForm));
			}
		}
		if (importForms.isEmpty()) {
			if (s.standard == SchemeStandard.R7RS && !chunks.isEmpty()) {
				throw s.error("a library that imports nothing binds nothing, not even define:"
						+ " add (import (scheme base))", form);
			}
			imported.putAll(everything(s));
		}
		for (Map.Entry<String, SchemeLowering.Binding> entry : imported.entrySet()) {
			s.global.bindings.put(SchemeNames.mangle(entry.getKey()), entry.getValue());
		}
		List<LispVal> body = new ArrayList<>();
		for (Chunk chunk : chunks) {
			body.addAll(includes(s, chunk.datums(), chunk.file()));
		}
		List<LispVal> forms = new ArrayList<>();
		for (LispVal datum : s.expanded(body)) {
			s.spliceBegins(datum, forms);
		}
		for (LispVal datum : forms) {
			s.collectAssigned(datum);
		}
		SchemeDefinitionLowering.declareGlobals(s, forms);
		List<LispVal> out = new ArrayList<>();
		for (LispVal datum : forms) {
			SchemeDefinitionLowering.topLevel(s, datum, out);
		}
		if (SchemeExitGuardLowering.mayThrowExit(forms) || s.foreignExitOrEval) {
			out.replaceAll(lowered -> SchemeExitGuardLowering.exitGuard(s, lowered));
		}
		s.libraryForms = instantiationGuarded(s, out);
		return exports(s, exportForms);
	}

	/**
	 * Body datums and the file they were read from, what an {@code include} in them is
	 * relative to.
	 *
	 * @param datums the datums
	 * @param file the file, or {@code null} for a session buffer
	 */
	private record Chunk(List<LispVal> datums, @Nullable String file) {
	}

	/**
	 * What a library's declarations collect, in order.
	 *
	 * @param imports the {@code import} declarations
	 * @param exports the {@code export} declarations
	 * @param body the {@code begin} and {@code include} bodies
	 */
	private record Declarations(List<LispCons> imports, List<LispCons> exports, List<Chunk> body) {
	}

	private static void libraryDeclarations(SchemeLowering s, List<LispVal> declarations, @Nullable String file,
			LispCons library, Declarations out, java.util.Deque<String> reading) {
		for (LispVal datum : declarations) {
			if (!(datum instanceof LispCons declaration) || !(declaration.car() instanceof LispSymbol head)) {
				throw s.error("malformed library declaration: " + SchemeExpander.written(datum), library);
			}
			switch (head.name()) {
				case "export" -> out.exports().add(declaration);
				case "import" -> out.imports().add(declaration);
				case "begin" -> out.body().add(new Chunk(s.elements(declaration.cdr(), declaration), file));
				case "include", "include-ci" -> {
					for (Included included : readIncluded(s, declaration, file, head.name().equals("include-ci"),
							reading)) {
						out.body().add(new Chunk(included.datums(), included.file()));
					}
				}
				case "include-library-declarations" -> {
					for (Included included : readIncluded(s, declaration, file, false, reading)) {
						reading.push(included.file());
						libraryDeclarations(s, included.datums(), included.file(), library, out, reading);
						reading.pop();
					}
				}
				case "cond-expand" ->
					libraryDeclarations(s, condExpandBody(s, declaration), file, library, out, reading);
				default -> throw s.error("unknown library declaration: " + head.name(), declaration);
			}
		}
	}

	// The exports: (export id (rename internal external) ...), each resolved in the
	// library's own scope after its body is lowered.
	private static Map<String, SchemeLowering.Binding> exports(SchemeLowering s, List<LispCons> exportForms) {
		Map<String, SchemeLowering.Binding> exports = new LinkedHashMap<>();
		for (LispCons exportForm : exportForms) {
			for (LispVal spec : s.elements(exportForm.cdr(), exportForm)) {
				LispSymbol internal;
				LispSymbol external;
				if (spec instanceof LispCons rename && rename.car() instanceof LispSymbol head
						&& head.name().equals("rename")) {
					List<LispVal> pair = s.elements(rename.cdr(), exportForm);
					if (pair.size() != 2) {
						throw s.error("malformed export rename: " + SchemeExpander.written(spec), exportForm);
					}
					internal = s.identifier(pair.get(0), exportForm);
					external = s.identifier(pair.get(1), exportForm);
				}
				else {
					internal = s.identifier(spec, exportForm);
					external = internal;
				}
				// A syntax definition shadows an import of the same name, as the expander
				// resolves it.
				Object macro = s.expander != null ? s.expander.exportedMacro(internal.name()) : null;
				SchemeLowering.Binding binding = macro != null
						? new SchemeLowering.ImportedSyntax(macro, internal.name()) : s.global.find(s.name(internal));
				if (binding == null) {
					throw s.error("the library exports " + internal.name() + ", which it neither defines nor imports",
							exportForm);
				}
				if (exports.put(external.name(), binding) != null) {
					throw s.error("the library exports " + external.name() + " twice", exportForm);
				}
			}
		}
		return exports;
	}

	// A library runs once per program, however many separately lowered files import it
	// (R7RS 5.6.1): its definitions are idempotent and stay top-level forms (the
	// backends hoist a defun or defstruct only as a direct child of the program), its
	// statements run behind a flag the first instantiation sets.
	private static List<LispVal> instantiationGuarded(SchemeLowering s, List<LispVal> forms) {
		List<LispVal> definitions = new ArrayList<>();
		List<LispVal> statements = new ArrayList<>();
		if (!s.initialValues.isEmpty()) {
			List<LispVal> assignment = new ArrayList<>(List.of(SchemeLowering.symbol("SETQ")));
			s.initialValues.forEach((variable, value) -> {
				assignment.add(variable);
				assignment.add(value);
			});
			statements.add(SchemeLowering.listOf(assignment));
		}
		for (LispVal form : forms) {
			boolean definition = form instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& ("DEFUN".equals(head.name()) || "DEFSTRUCT".equals(head.name()));
			(definition ? definitions : statements).add(form);
		}
		if (statements.isEmpty()) {
			return List.copyOf(definitions);
		}
		LispSymbol flag = SchemeLowering.symbol(s.prefix + "%SCM-INSTANTIATED");
		List<LispVal> guarded = new ArrayList<>();
		guarded.add(SchemeLowering.list(SchemeLowering.symbol("DEFVAR"), flag, LispNil.INSTANCE));
		guarded.addAll(definitions);
		List<LispVal> body = new ArrayList<>(List.of(SchemeLowering.symbol("PROGN"),
				SchemeLowering.list(SchemeLowering.symbol("SETQ"), flag, LispTrue.INSTANCE)));
		body.addAll(statements);
		guarded
			.add(SchemeLowering.list(SchemeLowering.symbol("IF"), flag, LispNil.INSTANCE, SchemeLowering.listOf(body)));
		return List.copyOf(guarded);
	}

	/**
	 * A file an {@code include} read.
	 *
	 * @param datums the file's datums
	 * @param file the resolved path
	 */
	private record Included(List<LispVal> datums, String file) {
	}

	private static List<Included> readIncluded(SchemeLowering s, LispCons form, @Nullable String from, boolean foldCase,
			java.util.Deque<String> reading) {
		List<LispVal> names = s.elements(form.cdr(), form);
		if (names.isEmpty()) {
			throw s.error("include needs a file name", form);
		}
		List<Included> files = new ArrayList<>();
		for (LispVal name : names) {
			if (!(name instanceof LispString path)) {
				throw s.error("include takes file names as strings, got " + SchemeExpander.written(name), form);
			}
			SchemeFiles.Source source = s.libraries.files().find(from, path.value());
			if (source == null) {
				throw s.error("include: cannot read " + path.value(), form);
			}
			if (reading.contains(source.path())) {
				throw s.error("include: " + path.value() + " includes itself", form);
			}
			files.add(new Included(s.reader.other(source.text(), source.path(), foldCase).readAll(), source.path()));
		}
		return files;
	}

	// Splices every (include "file" ...) the datums spell as a (begin datums...) of the
	// files' contents, and every (cond-expand clause...) as a (begin datums...) of the
	// clause it takes, recursively, BEFORE macros are expanded -- so an included or
	// feature-dependent definition or syntax definition is seen by every pre-scan. Quoted
	// data is left alone, and so is a cond-expand in a syntax-rules template: what the
	// expander does with it (SchemeExpander). Datums that spell neither are returned as
	// they are, the same objects.
	static List<LispVal> includes(SchemeLowering s, List<LispVal> datums, @Nullable String file) {
		List<LispVal> out = new ArrayList<>(datums.size());
		boolean changed = false;
		for (LispVal datum : datums) {
			LispVal resolved = included(s, datum, file, new java.util.ArrayDeque<>(), true);
			changed |= resolved != datum;
			out.add(resolved);
		}
		return changed ? out : datums;
	}

	private static LispVal included(SchemeLowering s, LispVal datum, @Nullable String file,
			java.util.Deque<String> reading, boolean features) {
		if (!(datum instanceof LispCons form)) {
			return datum;
		}
		Core core = s.syntaxOf(form, s.global);
		if (core == Core.QUOTE || core == Core.QUASIQUOTE) {
			return datum;
		}
		if (core == Core.COND_EXPAND && features) {
			return included(s, condExpanded(s, form), file, reading, true);
		}
		if (core == Core.INCLUDE || core == Core.INCLUDE_CI) {
			List<LispVal> spliced = new ArrayList<>();
			for (Included included : readIncluded(s, form, file, core == Core.INCLUDE_CI, reading)) {
				reading.push(included.file());
				for (LispVal inner : included.datums()) {
					spliced.add(included(s, inner, included.file(), reading, features));
				}
				reading.pop();
			}
			return s.positioned(form, new LispCons(SchemeLowering.CORE_BEGIN, SchemeLowering.listOf(spliced)));
		}
		List<LispVal> elements = new ArrayList<>();
		boolean changed = false;
		LispVal rest = form;
		while (rest instanceof LispCons cell) {
			LispVal element = included(s, cell.car(), file, reading, features && core != Core.SYNTAX_RULES);
			changed |= element != cell.car();
			elements.add(element);
			rest = cell.cdr();
		}
		if (!changed) {
			return datum;
		}
		LispVal rebuilt = rest;
		for (int i = elements.size() - 1; i >= 0; i--) {
			rebuilt = new LispCons(elements.get(i), rebuilt);
		}
		return s.positioned(form, (LispCons) rebuilt);
	}

}
