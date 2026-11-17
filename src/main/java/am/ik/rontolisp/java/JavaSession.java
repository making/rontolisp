package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaParser.SessionTree;
import am.ik.rontolisp.java.JavaParser.Snippet;
import am.ik.rontolisp.java.JavaParser.SnippetKind;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.java.JavaType.Wildcard;
import am.ik.rontolisp.reader.LispReadException;

import static am.ik.rontolisp.java.JavaForms.list;
import static am.ik.rontolisp.java.JavaForms.runtimeCall;

/**
 * An interactive session of the Java subset: the buffers a REPL reads one at a time. A
 * buffer holds statements, expressions, variables, methods, types and imports, as a
 * snippet shell reads them; a statement needs no {@code ;}. Each variable is a static
 * field of one class of the session, each method a static method of it, each type a
 * top-level class; a later declaration of one replaces it.
 *
 * <p>
 * A file's program is closed, so its calls are direct and its dispatch sets fixed
 * ({@code .kb/java-frontend.md}). A later buffer may add a subclass, an overload or a new
 * body, so every buffer lowers the whole session again -- the declarations the earlier
 * buffers left, kept as text, and the buffer -- and its setup defines every function
 * again. What does not run again is a static initialization: a class's static state
 * starts over only when a buffer declares the class. Measured and decided in
 * {@code .kb/java-frontend.md}, "A session".
 */
public final class JavaSession {

	/** The class holding the session's variables and methods. */
	static final String CLASS = "$Session";

	/** The declarations of the buffers so far, by what a later one replacing them has. */
	private final Map<String, String> declarations = new LinkedHashMap<>();

	/** The classes whose static state a buffer set up. */
	private final Set<String> initialized = new HashSet<>();

	/** The classes of the library an instance of was made: a variable may hold one. */
	private final Set<String> made = new HashSet<>();

	/** The interfaces a lambda was made for: a variable may hold one. */
	private final Set<String> lambdas = new HashSet<>();

	/** Whether a buffer reached {@code Thread}: a monitor may be held. */
	private boolean monitors;

	/** The number of the last generated variable. */
	private int echoes;

	/**
	 * Reads and lowers one buffer. A buffer whose reading or lowering fails leaves the
	 * session as it was.
	 * @param buffer the typed text
	 * @return the steps to run, in order: the setup, then one per statement, expression
	 * or variable
	 * @throws LispReadException on what reading a file would refuse, at its position in
	 * the buffer
	 */
	public List<JavaTopLevel> read(String buffer) {
		// the buffer alone first: its syntax errors are its own, and it names what it
		// replaces
		String own = buffer + "\n;";
		SessionTree alone = JavaParser.session(own, own.length(), CLASS, this.echoes);
		Set<String> replaced = new HashSet<>();
		Set<String> declaredTypes = new HashSet<>();
		for (Snippet snippet : alone.snippets()) {
			if (!snippet.key().isEmpty()) {
				replaced.add(snippet.key());
			}
			if (snippet.kind() == SnippetKind.TYPE) {
				declaredTypes.add(snippet.key().substring("type ".length()));
			}
		}
		Map<String, String> kept = new LinkedHashMap<>(this.declarations);
		kept.keySet().removeAll(replaced);
		String text = own + "\n" + String.join("\n", kept.values());
		SessionTree tree = JavaParser.session(text, own.length(), CLASS, this.echoes);
		JavaLowering.Session session = new JavaLowering.Session(declaredTypes, this.initialized, this.made,
				this.lambdas, this.monitors);
		List<LispVal> forms = JavaLowering.lower(tree.unit(), text, null, JavaFiles.NONE, session, false);
		// what the buffer leaves for the next ones
		Map<String, String> declarations = new LinkedHashMap<>(kept);
		List<JavaTopLevel> steps = new ArrayList<>();
		steps.add(new JavaTopLevel(forms, false));
		for (Snippet snippet : tree.snippets()) {
			if (snippet.start() >= own.length()) {
				// a declaration an earlier buffer left, kept as it was
				continue;
			}
			String written = buffer.substring(snippet.start(), Math.min(snippet.end(), buffer.length())).strip();
			switch (snippet.kind()) {
				case IMPORT, TYPE, METHOD -> declarations.put(snippet.key(), written);
				case VARIABLE, EXPRESSION -> {
					String variable = snippet.variable();
					if (variable != null) {
						JavaType inferred = session.inferred.get(variable);
						String type = "var".equals(snippet.type()) ? inferred == null ? null : spell(inferred)
								: snippet.type();
						if (type != null) {
							declarations.put(snippet.key(), type + " " + variable + ";");
						}
					}
				}
				default -> {
				}
			}
			String step = snippet.step();
			if (step != null) {
				LispVal call = list(JavaNames.symbol(CLASS + "." + step + "()"));
				LispSymbol condition = new LispSymbol("%c");
				steps.add(new JavaTopLevel(
						List.of(list(new LispSymbol("HANDLER-CASE"), call,
								list(new LispSymbol("ERROR"), list(condition),
										runtimeCall("session-error", condition)))),
						snippet.kind() != SnippetKind.STATEMENT));
			}
		}
		this.declarations.clear();
		this.declarations.putAll(declarations);
		this.initialized.addAll(session.initializing);
		this.made.addAll(session.made);
		this.lambdas.addAll(session.lambdas);
		this.monitors = session.monitors;
		this.echoes = tree.echoes();
		return steps;
	}

	/**
	 * Whether the text can be read now, or the next line continues it: an open bracket,
	 * string, comment or statement continues it; a missing {@code ;} at the end does not,
	 * a statement needing none. Text that is complete but WRONG answers {@code true}: it
	 * must reach {@link #read} to be reported.
	 * @param buffer the typed text so far
	 * @return {@code false} when more input is needed
	 */
	public static boolean isComplete(String buffer) {
		try {
			JavaParser.session(buffer, buffer.length(), CLASS, 0);
			return true;
		}
		catch (LispReadException ex) {
			return !ex.isEndOfFile() || ex.reason().startsWith("expected ';'");
		}
	}

	/**
	 * A type as a declaration of a later buffer writes it: by its qualified name, a local
	 * or an anonymous class as the type it extends or implements, a type variable as its
	 * bound, a captured wildcard as the wildcard.
	 */
	static String spell(JavaType type) {
		return switch (type) {
			case JavaType.Primitive primitive -> primitive.toString();
			case JavaType.Null nothing -> "Object";
			case ArrayType array -> spell(array.element()) + "[]";
			case Variable variable ->
				variable.parameter().bounds.isEmpty() ? "Object" : spell(variable.parameter().bounds.get(0));
			case Wildcard wildcard -> wildcard.upper() != null ? "? extends " + spell(wildcard.upper())
					: wildcard.lower() != null ? "? super " + spell(wildcard.lower()) : "?";
			case ClassType named -> {
				JavaClass declaration = named.declaration();
				if (declaration.isLocal) {
					JavaClass above = declaration.superclass;
					if ((above == null || above.name.equals("java.lang.Object")) && !declaration.interfaces.isEmpty()) {
						above = declaration.interfaces.get(0);
					}
					yield above == null ? "Object" : spell(declaration.supertype(above));
				}
				int own = declaration.typeParameters.size();
				JavaClass outer = declaration.outer;
				if (outer != null && named.arguments().size() > own) {
					// Outer<String>.Inner
					yield spell(new ClassType(outer, named.arguments().subList(own, named.arguments().size()))) + "."
							+ declaration.simpleName() + arguments(named.arguments().subList(0, own));
				}
				yield declaration.name + arguments(named.arguments());
			}
		};
	}

	private static String arguments(List<JavaType> arguments) {
		return arguments.isEmpty() ? ""
				: arguments.stream()
					.map(argument -> argument instanceof Variable variable && variable.parameter().captured != null
							? spell(variable.parameter().captured) : spell(argument))
					.collect(Collectors.joining(", ", "<", ">"));
	}

}
