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
 * The new {@code objc} base and the {@code cocoa} package: LispWorks 8.1's Objective-C
 * and Cocoa interface vocabulary ({@code objc:invoke}, {@code objc:invoke-into},
 * {@code objc:retain}, {@code cocoa:set-ns-rect*}, ...), written once in rontolisp
 * ({@code objc.lisp} on the classpath) over the per-backend primitive layer
 * ({@code objc::%send} and friends; .kb/objc.md, "The new base").
 *
 * <p>
 * Consumers, the {@link AppKitLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a new-base {@code objc:} name or any {@code cocoa:} name is resolved
 * ({@link #definesName}), or a form mentions one of its types
 * ({@link #mentionsType});</li>
 * <li>the compile path ({@code CompileFrontend}) calls {@link #process(List)} right
 * OUTSIDE {@code AppKitLibrary.process}, so a library written over the new base is seen
 * too; the spliced {@code objc::%} calls gate the embedded binding on a JVM class, and
 * {@link ObjcNativeLibrary} supplies them to a {@code --native} executable.</li>
 * </ul>
 */
public final class ObjcLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private ObjcLibrary() {
	}

	/**
	 * Returns the parsed library definitions, in canonical shape. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (ObjcLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = ObjcLibrary.class.getResourceAsStream("objc.lisp")) {
			if (in == null) {
				throw new IllegalStateException("objc.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Whether a symbol name is one the library defines or exports: a new-base
	 * {@code objc:} external, an {@code objc::%} helper of the library, or any
	 * {@code cocoa:} name.
	 * @param symbolName the canonical symbol name
	 * @return {@code true} when resolving it needs the library
	 */
	public static boolean definesName(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn == null) {
			return false;
		}
		if (LispNames.COCOA_PKG.equals(qn.pkg())) {
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
			for (LispVal form : forms()) {
				if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && DEFINERS.contains(op.name())
						&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
					names.add(name.name());
				}
			}
			cached = Set.copyOf(names);
			definedNames = cached;
		}
		return cached;
	}

	private static final Set<String> DEFINERS = Set.of(LispNames.DEFUN, LispNames.DEFVAR, LispNames.DEFCONSTANT);

	/**
	 * Whether a form mentions one of the library's type names anywhere -- a
	 * {@code typep}, a {@code typecase} clause or a method specializer can name
	 * {@code objc:objc-object-pointer} before any function of the library is resolved.
	 * @param form the form
	 * @return {@code true} when it does
	 */
	public static boolean mentionsType(LispVal form) {
		for (LispVal val = form;;) {
			if (val instanceof LispSymbol sym) {
				String name = sym.name();
				return LispNames.OBJC_POINTER_TYPE.equals(name) || LispNames.OBJC_CLASS_TYPE.equals(name)
						|| LispNames.OBJC_SEL_TYPE.equals(name);
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
	 * new-base {@code objc:} name or a {@code cocoa:} name anywhere, or a bare exported
	 * name while {@code (in-package objc)} / {@code (in-package cocoa)} is in effect).
	 * @param program the top-level forms
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!references(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
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
	 * Whether a top-level form makes a package use {@code objc} or {@code cocoa} -- a
	 * {@code defpackage} with such a {@code :use} clause, or a {@code use-package} of one
	 * -- so that its bare names are the library's. The manual's examples are written that
	 * way, and on the compile path a bare name is still bare when the library passes run.
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
		return LispNames.OBJC_PKG.equals(upper) || LispNames.COCOA_PKG.equals(upper);
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
											&& PackageRegistry.cocoaNames().contains(upper));
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
