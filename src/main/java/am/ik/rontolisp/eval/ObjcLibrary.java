package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code objc} and {@code cocoa} packages: LispWorks 8.1's Objective-C and Cocoa
 * interface vocabulary ({@code objc:invoke}, {@code objc:invoke-into},
 * {@code objc:retain}, {@code cocoa:set-ns-rect*}, ...), written once in rontolisp
 * ({@code objc.lisp} on the classpath) over the per-backend primitive layer
 * ({@code objc::%send} and friends; .kb/objc.md, "The primitive layer").
 *
 * <p>
 * Consumers, the {@link AppKitLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time an {@code objc:} name or any {@code cocoa:} name is resolved
 * ({@link #definesName}), or a form mentions one of its types
 * ({@link #mentionsType});</li>
 * <li>the compile path ({@code CompileFrontend}) calls {@link #process(List)} right
 * OUTSIDE {@code AppKitLibrary.process}, so a library written over {@code objc} is seen
 * too; the spliced {@code objc::%} calls gate the embedded binding on a JVM class, and
 * {@link ObjcNativeLibrary} supplies them to a {@code --native} executable.</li>
 * </ul>
 */
public final class ObjcLibrary {

	@Nullable private static volatile List<LispVal> forms;

	@Nullable private static volatile List<LispVal> classForms;

	@Nullable private static volatile List<LispVal> macroForms;

	@Nullable private static volatile List<LispVal> blockForms;

	private ObjcLibrary() {
	}

	/**
	 * Returns the parsed library definitions ({@code objc.lisp}), in canonical shape.
	 * Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (ObjcLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource("objc.lisp"), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Returns the class-definition half ({@code objc-class.lisp}: standard-objc-object,
	 * the classes and methods the defining macros make), parsed once and cached. The
	 * compile path splices it only into a program that defines something
	 * ({@link #process}).
	 * @return the forms
	 */
	public static List<LispVal> classForms() {
		List<LispVal> cached = classForms;
		if (cached == null) {
			synchronized (ObjcLibrary.class) {
				cached = classForms;
				if (cached == null) {
					cached = List
						.copyOf(LispReader.readAllFromString(readSource("objc-class.lisp"), Features.INTERPRETER));
					classForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Returns the blocks half ({@code objc-block.lisp}: {@code make-objc-block},
	 * {@code call-objc-block} and the rest), parsed once and cached. The compile path
	 * splices it only into a program that names a block ({@link #process}).
	 * @return the forms
	 */
	public static List<LispVal> blockForms() {
		List<LispVal> cached = blockForms;
		if (cached == null) {
			synchronized (ObjcLibrary.class) {
				cached = blockForms;
				if (cached == null) {
					cached = List
						.copyOf(LispReader.readAllFromString(readSource("objc-block.lisp"), Features.INTERPRETER));
					blockForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Returns the defining macros ({@code objc-macros.lisp}: {@code define-objc-class}
	 * and the rest), parsed once and cached. The compile path expands macros BEFORE it
	 * splices libraries, so {@link #withMacros} puts these in front of the program for
	 * that expansion; the interpreter evaluates them with the library.
	 * @return the forms
	 */
	public static List<LispVal> macroForms() {
		List<LispVal> cached = macroForms;
		if (cached == null) {
			synchronized (ObjcLibrary.class) {
				cached = macroForms;
				if (cached == null) {
					cached = List
						.copyOf(LispReader.readAllFromString(readSource("objc-macros.lisp"), Features.INTERPRETER));
					macroForms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Every form the interpreter evaluates on the first use: the library, the class half,
	 * the blocks half, the macros.
	 * @return the forms, in evaluation order
	 */
	public static List<LispVal> allForms() {
		List<LispVal> all = new ArrayList<>(forms());
		all.addAll(classForms());
		all.addAll(blockForms());
		all.addAll(macroForms());
		return all;
	}

	private static String readSource(String name) {
		try (InputStream in = ObjcLibrary.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException(name + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Whether a symbol name is one the library defines or exports: an {@code objc:}
	 * external, an {@code objc::%} helper of the library, or any {@code cocoa:} or
	 * {@code fli:} name.
	 * @param symbolName the canonical symbol name
	 * @return {@code true} when resolving it needs the library
	 */
	public static boolean definesName(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn == null) {
			return false;
		}
		if (LispNames.COCOA_PKG.equals(qn.pkg()) || LispNames.FLI_PKG.equals(qn.pkg())) {
			return true;
		}
		return LispNames.OBJC_PKG.equals(qn.pkg()) && isBaseMember(qn.member(), qn.internal());
	}

	private static boolean isBaseMember(String member, boolean internal) {
		String upper = member.toUpperCase(Locale.ROOT);
		if (PackageRegistry.objcBaseNames().contains(upper)) {
			return true;
		}
		// objc.lisp's own helpers; the primitives under them are the backend's.
		return internal && definedNames().contains(LispNames.OBJC_PKG + "::" + upper);
	}

	@Nullable private static volatile Set<String> definedNames;

	/**
	 * The names the library's top-level forms define ({@code defun}, {@code defvar},
	 * {@code defconstant}), as spelled there.
	 */
	private static Set<String> definedNames() {
		Set<String> cached = definedNames;
		if (cached == null) {
			Set<String> names = new HashSet<>();
			collectDefinedNames(forms(), names);
			collectDefinedNames(classForms(), names);
			collectDefinedNames(blockForms(), names);
			collectDefinedNames(macroForms(), names);
			cached = Set.copyOf(names);
			definedNames = cached;
		}
		return cached;
	}

	@Nullable private static volatile Set<String> classDefinedNames;

	/**
	 * The names the class half defines or is reached through: its own definitions, and
	 * the exported names only it gives a meaning.
	 */
	private static Set<String> classDefinedNames() {
		Set<String> cached = classDefinedNames;
		if (cached == null) {
			Set<String> names = new HashSet<>();
			collectDefinedNames(classForms(), names);
			for (String member : CLASS_HALF_EXPORTS) {
				names.add(PackageRegistry.qualify(LispNames.OBJC_PKG, member));
			}
			cached = Set.copyOf(names);
			classDefinedNames = cached;
		}
		return cached;
	}

	@Nullable private static volatile Set<String> blockDefinedNames;

	/**
	 * The names the blocks half defines or is reached through: its own definitions, and
	 * {@code objc:objc-block}, its type.
	 */
	private static Set<String> blockDefinedNames() {
		Set<String> cached = blockDefinedNames;
		if (cached == null) {
			Set<String> names = new HashSet<>();
			collectDefinedNames(blockForms(), names);
			names.add(OBJC_BLOCK);
			cached = Set.copyOf(names);
			blockDefinedNames = cached;
		}
		return cached;
	}

	/** {@code objc:objc-block}, qualified: the blocks half's type. */
	private static final String OBJC_BLOCK = LispNames.OBJC_PKG + ":OBJC-BLOCK";

	/** {@code objc:standard-objc-object}, qualified: a superclass a defclass names. */
	private static final String STANDARD_OBJC_OBJECT = LispNames.OBJC_PKG + ":STANDARD-OBJC-OBJECT";

	/** The exported names of the class half that no macro expansion spells. */
	private static final List<String> CLASS_HALF_EXPORTS = List.of("STANDARD-OBJC-OBJECT", "OBJC-OBJECT-VAR-VALUE",
			"OBJC-OBJECT-COPIED", "OBJC-OBJECT-DESTROYED");

	private static void collectDefinedNames(List<LispVal> source, Set<String> names) {
		for (LispVal form : source) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && DEFINERS.contains(op.name())
					&& cons.cdr() instanceof LispCons rest) {
				if (rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
				else if (rest.car() instanceof LispCons setf && setf.cdr() instanceof LispCons place
						&& place.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
		}
	}

	private static final Set<String> DEFINERS = Set.of(LispNames.DEFUN, LispNames.DEFVAR, LispNames.DEFCONSTANT,
			LispNames.DEFMACRO, LispNames.DEFGENERIC, LispNames.DEFCLASS);

	/**
	 * Whether a symbol names one of the defining macros ({@code objc-macros.lisp}), which
	 * the interpreter must load before it asks whether a call is a macro.
	 * @param symbolName the canonical symbol name
	 * @return {@code true} for a macro of the library
	 */
	public static boolean definesMacro(String symbolName) {
		return (symbolName.startsWith(LispNames.OBJC_PKG + ":") || symbolName.startsWith(LispNames.FLI_PKG + ":"))
				&& macroNames().contains(symbolName);
	}

	@Nullable private static volatile Set<String> macroNames;

	private static Set<String> macroNames() {
		Set<String> cached = macroNames;
		if (cached == null) {
			Set<String> names = new HashSet<>();
			collectDefinedNames(macroForms(), names);
			cached = Set.copyOf(names);
			macroNames = cached;
		}
		return cached;
	}

	/**
	 * Whether a form mentions one of the library's type names anywhere -- a
	 * {@code typep}, a {@code typecase} clause or a method specializer can name
	 * {@code objc:objc-object-pointer} before any function of the library is resolved,
	 * and a {@code defclass} {@code objc:standard-objc-object} (what
	 * {@code define-objc-class} expands to, which the compile path's macro-time evaluator
	 * evaluates too).
	 * @param form the form
	 * @return {@code true} when it does
	 */
	public static boolean mentionsType(LispVal form) {
		for (LispVal val = form;;) {
			if (val instanceof LispSymbol sym) {
				String name = sym.name();
				return LispNames.OBJC_POINTER_TYPE.equals(name) || LispNames.OBJC_CLASS_TYPE.equals(name)
						|| LispNames.OBJC_SEL_TYPE.equals(name) || STANDARD_OBJC_OBJECT.equals(name)
						|| OBJC_BLOCK.equals(name) || LispNames.OBJC_EXCEPTION_TYPE.equals(name)
						|| LispNames.NS_ERROR_TYPE.equals(name) || LispNames.FLI_POINTER_TYPE.equals(name);
			}
			if (val instanceof LispCons cons) {
				if (mentionsType(cons.car())) {
					return true;
				}
				val = cons.cdr();
				continue;
			}
			return false;
		}
	}

	/**
	 * The compile-path pre-pass: prepends the library when the program references it (a
	 * {@code objc:} name or a {@code cocoa:} name anywhere, or a bare exported name while
	 * {@code (in-package objc)} / {@code (in-package cocoa)} is in effect).
	 * @param program the top-level forms
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!references(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		if (referencesClassHalf(program)) {
			out.addAll(classForms());
		}
		if (referencesBlockHalf(program)) {
			out.addAll(blockForms());
		}
		out.addAll(program);
		return out;
	}

	/**
	 * The compile-path pass in front of user-macro expansion: prepends the defining
	 * macros when the program references the library, so {@code define-objc-class} and
	 * the rest expand like any macro (and are dropped with the other {@code defmacro}s).
	 * @param program the top-level forms, before user-macro expansion
	 * @return the program with the macros in front when used
	 */
	public static List<LispVal> withMacros(List<LispVal> program) {
		if (!references(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(macroForms());
		out.addAll(program);
		return out;
	}

	/**
	 * A shipped library's forms with the defining macros ({@code define-objc-class} and
	 * the rest) expanded. The compile path expands user macros BEFORE it splices the
	 * libraries, so a library spliced after that expansion ({@code appkit.lisp},
	 * {@code scene.lisp}) that defines Objective-C classes has its definitions expanded
	 * here, by the same expander a program's go through. The interpreter evaluates the
	 * library's own forms and expands the macros natively.
	 * @param forms the library's forms
	 * @return the forms, the macros expanded (unchanged when none is named)
	 */
	public static List<LispVal> expandDefinitions(List<LispVal> forms) {
		return List.copyOf(UserMacroExpander.expand(withMacros(forms)));
	}

	/**
	 * Whether a program, its macros expanded, uses the class half: a name only that half
	 * defines, or {@code standard-objc-object} and its generics.
	 * @param program the top-level forms
	 * @return {@code true} when it does
	 */
	public static boolean referencesClassHalf(List<LispVal> program) {
		Set<String> names = classDefinedNames();
		for (LispVal form : program) {
			if (mentionsAny(form, names)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a program, its macros expanded, uses the blocks half: a name only that half
	 * defines, or {@code objc:objc-block}.
	 * @param program the top-level forms
	 * @return {@code true} when it does
	 */
	public static boolean referencesBlockHalf(List<LispVal> program) {
		Set<String> names = blockDefinedNames();
		for (LispVal form : program) {
			if (mentionsAny(form, names)) {
				return true;
			}
		}
		return false;
	}

	private static boolean mentionsAny(LispVal form, Set<String> names) {
		for (LispVal val = form;;) {
			if (val instanceof LispSymbol sym) {
				return names.contains(sym.name());
			}
			if (val instanceof LispCons cons) {
				if (mentionsAny(cons.car(), names)) {
					return true;
				}
				val = cons.cdr();
				continue;
			}
			return false;
		}
	}

	/**
	 * Whether a program references the library ({@link #process}'s question).
	 * @param program the top-level forms
	 * @return {@code true} when it does
	 */
	public static boolean references(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			if (usesPackage(form)) {
				return true;
			}
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
			if (walker.found) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a top-level form makes a package use {@code objc}, {@code cocoa} or
	 * {@code fli} -- a {@code defpackage} with such a {@code :use} clause, or a
	 * {@code use-package} of one -- so that its bare names are the library's. The
	 * manual's examples are written that way, and on the compile path a bare name is
	 * still bare when the library passes run.
	 * @param form a top-level form
	 * @return {@code true} when it does
	 */
	public static boolean usesPackage(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol op)
				|| !(cons.cdr() instanceof LispCons rest)) {
			return false;
		}
		String name = memberOf(op.name());
		if (LispNames.USE_PACKAGE.equals(name)) {
			return designatesObjc(rest.car()) || rest.car() instanceof LispCons quoted
					&& quoted.cdr() instanceof LispCons designator && designatesObjc(designator.car());
		}
		if (!LispNames.DEFPACKAGE.equals(name)) {
			return false;
		}
		for (LispVal option = rest.cdr(); option instanceof LispCons cell; option = cell.cdr()) {
			if (cell.car() instanceof LispCons clause && clause.car() instanceof LispSymbol key
					&& ":USE".equals(key.name())) {
				for (LispVal used = clause.cdr(); used instanceof LispCons u; used = u.cdr()) {
					if (designatesObjc(u.car())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean designatesObjc(LispVal designator) {
		String name = switch (designator) {
			case LispSymbol sym -> sym.name().replaceFirst("^(#:|:)", "");
			case LispString str -> str.value();
			default -> "";
		};
		String upper = PackageRegistry.canonicalBuiltinName(name.toUpperCase(Locale.ROOT));
		return LispNames.OBJC_PKG.equals(upper) || LispNames.COCOA_PKG.equals(upper) || LispNames.FLI_PKG.equals(upper);
	}

	private static String memberOf(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static final class Walker {

		private boolean found;

		private String currentPackage = LispNames.CL_USER_PKG;

		private void trackTopLevelInPackage(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.IN_PACKAGE.equals(member(op.name())) && cons.cdr() instanceof LispCons argCell) {
				String name = switch (argCell.car()) {
					case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
					case LispString str -> str.value();
					default -> this.currentPackage;
				};
				this.currentPackage = PackageRegistry.canonicalBuiltinName(name);
			}
		}

		private static String member(String name) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
			return qn == null ? name : qn.member();
		}

		private void detect(LispVal form) {
			while (true) {
				if (this.found) {
					return;
				}
				switch (form) {
					case LispSymbol sym -> {
						String name = sym.name();
						if (definesName(name)) {
							this.found = true;
						}
						else if (PackageRegistry.splitQualified(name) == null) {
							String upper = name.toUpperCase(Locale.ROOT);
							this.found = (LispNames.OBJC_PKG.equals(this.currentPackage)
									&& PackageRegistry.objcBaseNames().contains(upper))
									|| (LispNames.COCOA_PKG.equals(this.currentPackage)
											&& PackageRegistry.cocoaNames().contains(upper))
									|| (LispNames.FLI_PKG.equals(this.currentPackage)
											&& PackageRegistry.fliNames().contains(upper));
						}
					}
					case LispCons cons -> {
						detect(cons.car());
						form = cons.cdr();
						continue;
					}
					default -> {
					}
				}
				return;
			}
		}

	}

}
