package am.ik.rontolisp.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.java.JavaSyntax.ArrayInitializer;
import am.ik.rontolisp.java.JavaSyntax.Assign;
import am.ik.rontolisp.java.JavaSyntax.Binary;
import am.ik.rontolisp.java.JavaSyntax.Block;
import am.ik.rontolisp.java.JavaSyntax.BooleanLiteral;
import am.ik.rontolisp.java.JavaSyntax.Break;
import am.ik.rontolisp.java.JavaSyntax.Call;
import am.ik.rontolisp.java.JavaSyntax.Cast;
import am.ik.rontolisp.java.JavaSyntax.Catch;
import am.ik.rontolisp.java.JavaSyntax.CharLiteral;
import am.ik.rontolisp.java.JavaSyntax.ClassDeclaration;
import am.ik.rontolisp.java.JavaSyntax.CompilationUnit;
import am.ik.rontolisp.java.JavaSyntax.Conditional;
import am.ik.rontolisp.java.JavaSyntax.ConstructorCall;
import am.ik.rontolisp.java.JavaSyntax.Continue;
import am.ik.rontolisp.java.JavaSyntax.Declarator;
import am.ik.rontolisp.java.JavaSyntax.DoWhile;
import am.ik.rontolisp.java.JavaSyntax.DoubleLiteral;
import am.ik.rontolisp.java.JavaSyntax.EnumConstant;
import am.ik.rontolisp.java.JavaSyntax.Empty;
import am.ik.rontolisp.java.JavaSyntax.Expression;
import am.ik.rontolisp.java.JavaSyntax.ExpressionStatement;
import am.ik.rontolisp.java.JavaSyntax.Field;
import am.ik.rontolisp.java.JavaSyntax.For;
import am.ik.rontolisp.java.JavaSyntax.ForEach;
import am.ik.rontolisp.java.JavaSyntax.If;
import am.ik.rontolisp.java.JavaSyntax.Import;
import am.ik.rontolisp.java.JavaSyntax.Index;
import am.ik.rontolisp.java.JavaSyntax.Initializer;
import am.ik.rontolisp.java.JavaSyntax.InstanceOf;
import am.ik.rontolisp.java.JavaSyntax.IntLiteral;
import am.ik.rontolisp.java.JavaSyntax.Labeled;
import am.ik.rontolisp.java.JavaSyntax.Lambda;
import am.ik.rontolisp.java.JavaSyntax.LocalClass;
import am.ik.rontolisp.java.JavaSyntax.LocalVariable;
import am.ik.rontolisp.java.JavaSyntax.Method;
import am.ik.rontolisp.java.JavaSyntax.Name;
import am.ik.rontolisp.java.JavaSyntax.New;
import am.ik.rontolisp.java.JavaSyntax.NewArray;
import am.ik.rontolisp.java.JavaSyntax.NullLiteral;
import am.ik.rontolisp.java.JavaSyntax.Pattern;
import am.ik.rontolisp.java.JavaSyntax.QualifiedThis;
import am.ik.rontolisp.java.JavaSyntax.RecordPattern;
import am.ik.rontolisp.java.JavaSyntax.Parameter;
import am.ik.rontolisp.java.JavaSyntax.Return;
import am.ik.rontolisp.java.JavaSyntax.Select;
import am.ik.rontolisp.java.JavaSyntax.Statement;
import am.ik.rontolisp.java.JavaSyntax.StringLiteral;
import am.ik.rontolisp.java.JavaSyntax.This;
import am.ik.rontolisp.java.JavaSyntax.Throw;
import am.ik.rontolisp.java.JavaSyntax.Try;
import am.ik.rontolisp.java.JavaSyntax.TypeName;
import am.ik.rontolisp.java.JavaSyntax.Unary;
import am.ik.rontolisp.java.JavaSyntax.While;
import am.ik.rontolisp.java.JavaToken.Kind;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * Tokens to a syntax tree, by recursive descent over the grammar of the language
 * specification. A construct of the language the front end does not lower yet is refused
 * here BY NAME, at its position ({@link Unsupported}): nothing is read as something else.
 */
final class JavaParser {

	/** A construct of the language the subset does not have yet. */
	static final class Unsupported extends LispReadException {

		private static final long serialVersionUID = 1L;

		Unsupported(String what, SourceLocation at) {
			super(what + " is not supported by the experimental Java subset yet", at);
		}

	}

	private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "_");

	private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long", "float",
			"double");

	private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "final",
			"abstract");

	private static final Set<String> REFUSED_MODIFIERS = Set.of("synchronized", "native", "volatile", "transient",
			"strictfp");

	private static final List<List<String>> LEVELS = List.of(List.of("||"), List.of("&&"), List.of("|"), List.of("^"),
			List.of("&"), List.of("==", "!="), List.of("<", ">", "<=", ">=", "instanceof"), List.of("<<", ">>", ">>>"),
			List.of("+", "-"), List.of("*", "/", "%"));

	private static final Set<String> ASSIGNMENTS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=",
			">>=", ">>>=");

	private static final BigInteger INT_LIMIT = BigInteger.ONE.shiftLeft(31);

	private static final BigInteger LONG_LIMIT = BigInteger.ONE.shiftLeft(63);

	private final String source;

	private final @Nullable String file;

	private final List<JavaToken> tokens;

	private int p;

	/** How many tokens the operator {@link #operator} last answered spans. */
	private int operatorWidth;

	private JavaParser(String source, @Nullable String file) {
		this.source = source;
		this.file = file;
		this.tokens = JavaLexer.lex(source, file);
	}

	/**
	 * Parses a source file.
	 * @param source the text
	 * @param file the origin file, or {@code null}
	 * @param implicitName the name of the class a compact source file declares
	 * @return the tree
	 * @throws LispReadException on a syntax error or a construct the subset does not have
	 * yet, at its position
	 */
	static CompilationUnit parse(String source, @Nullable String file, String implicitName) {
		return new JavaParser(source, file).unit(implicitName);
	}

	/**
	 * Parses members the front end writes for a class itself -- a record's
	 * {@code equals}, an enum's {@code values()} --, every node at one position of the
	 * program.
	 * @param text the members, in the subset
	 * @param className the class they belong to
	 * @param kind what the class is
	 * @param pos the position every node gets
	 * @return the methods
	 */
	static List<Method> synthetic(String text, String className, JavaSyntax.Kind kind, int pos) {
		JavaParser parser = new JavaParser(text, null);
		parser.tokens.replaceAll(token -> new JavaToken(token.kind(), token.text(), token.integer(), token.decimal(),
				token.number(), pos));
		Body body = new Body();
		while (parser.current().kind() != Kind.END) {
			parser.member(className, kind, body);
		}
		return body.methods;
	}

	// ---- tokens

	private JavaToken current() {
		return this.tokens.get(this.p);
	}

	private JavaToken peek(int ahead) {
		return this.tokens.get(Math.min(this.p + ahead, this.tokens.size() - 1));
	}

	private boolean at(String spelling) {
		return current().is(spelling);
	}

	private boolean peekIs(int ahead, String spelling) {
		return peek(ahead).is(spelling);
	}

	private boolean eat(String spelling) {
		if (at(spelling)) {
			this.p++;
			return true;
		}
		return false;
	}

	private JavaToken expect(String spelling) {
		if (!at(spelling)) {
			throw error("expected '" + spelling + "' but found " + current().shown());
		}
		return this.tokens.get(this.p++);
	}

	private boolean atIdentifier() {
		return current().kind() == Kind.IDENTIFIER && !RESERVED.contains(current().text());
	}

	private String identifier() {
		if (current().kind() == Kind.IDENTIFIER && current().text().equals("_")) {
			throw error("the unnamed _ is allowed only where a local, a parameter of a lambda or of a catch,"
					+ " or a pattern is declared");
		}
		if (!atIdentifier()) {
			throw error("expected an identifier but found " + current().shown());
		}
		return this.tokens.get(this.p++).text();
	}

	private SourceLocation location(int offset) {
		return SourceLocation.at(this.file, offset, this.source);
	}

	private LispReadException error(String message) {
		return new LispReadException(message, location(current().offset()), current().kind() == Kind.END);
	}

	private Unsupported unsupported(String what) {
		return unsupported(what, current().offset());
	}

	private Unsupported unsupported(String what, int offset) {
		return new Unsupported(what, location(offset));
	}

	// ---- declarations

	/** The members of a type's body, as they are read. */
	private static final class Body {

		final List<EnumConstant> constants = new ArrayList<>();

		final List<Field> fields = new ArrayList<>();

		final List<Method> methods = new ArrayList<>();

		final List<Initializer> initializers = new ArrayList<>();

		final List<ClassDeclaration> nested = new ArrayList<>();

	}

	private CompilationUnit unit(String implicitName) {
		String packageName = null;
		List<Import> imports = new ArrayList<>();
		List<ClassDeclaration> classes = new ArrayList<>();
		Body implicit = new Body();
		int implicitAt = -1;
		while (current().kind() != Kind.END) {
			if (eat(";")) {
				continue;
			}
			int start = this.p;
			annotations();
			if (at("package") && classes.isEmpty() && imports.isEmpty()) {
				this.p++;
				packageName = qualifiedName();
				expect(";");
				continue;
			}
			if (at("import")) {
				imports.add(importDeclaration());
				continue;
			}
			this.p = start;
			Set<String> modifiers = modifiers();
			if (atTypeDeclaration()) {
				classes.add(typeDeclaration(modifiers));
				continue;
			}
			// a member at the top level: a compact source file
			this.p = start;
			if (implicitAt < 0) {
				implicitAt = current().offset();
			}
			member(implicitName, JavaSyntax.Kind.CLASS, implicit);
		}
		if (implicitAt >= 0) {
			if (packageName != null) {
				throw new LispReadException("a compact source file has no package declaration", location(implicitAt));
			}
			if (!implicit.initializers.isEmpty()) {
				throw new LispReadException("an initializer block is not allowed at the top level of a file",
						location(implicit.initializers.get(0).pos()));
			}
			// the types declared beside the members are the implicit class's member types
			ClassDeclaration declared = new ClassDeclaration(implicitAt, Set.of("final"), JavaSyntax.Kind.CLASS,
					implicitName, null, List.of(), List.of(), List.of(), List.of(), implicit.fields, implicit.methods,
					List.of(), List.copyOf(classes), true);
			return new CompilationUnit(null, imports, List.of(declared));
		}
		return new CompilationUnit(packageName, imports, classes);
	}

	private Import importDeclaration() {
		int pos = expect("import").offset();
		if (at("static")) {
			throw unsupported("a static import");
		}
		if (at("module") && peek(1).kind() == Kind.IDENTIFIER) {
			this.p++;
			String module = qualifiedName();
			expect(";");
			return new Import(pos, module, false, true);
		}
		StringBuilder name = new StringBuilder(identifier());
		boolean onDemand = false;
		while (eat(".")) {
			if (eat("*")) {
				onDemand = true;
				break;
			}
			name.append('.').append(identifier());
		}
		expect(";");
		return new Import(pos, name.toString(), onDemand, false);
	}

	private String qualifiedName() {
		StringBuilder name = new StringBuilder(identifier());
		while (eat(".")) {
			name.append('.').append(identifier());
		}
		return name.toString();
	}

	/** Skips annotations: they are parsed and dropped. */
	private void annotations() {
		while (at("@") && !peekIs(1, "interface")) {
			this.p++;
			qualifiedName();
			if (at("(")) {
				int depth = 0;
				do {
					if (at("(")) {
						depth++;
					}
					else if (at(")")) {
						depth--;
					}
					else if (current().kind() == Kind.END) {
						throw error("unterminated annotation");
					}
					this.p++;
				}
				while (depth > 0);
			}
		}
	}

	/** Whether a token can follow the contextual modifier {@code sealed}. */
	private boolean startsDeclaration(JavaToken token) {
		return token.kind() == Kind.IDENTIFIER && (MODIFIERS.contains(token.text()) || token.text().equals("class")
				|| token.text().equals("interface") || token.text().equals("sealed") || token.text().equals("non"));
	}

	private Set<String> modifiers() {
		Set<String> modifiers = new LinkedHashSet<>();
		while (true) {
			annotations();
			JavaToken token = current();
			if (token.kind() != Kind.IDENTIFIER) {
				return modifiers;
			}
			if (token.text().equals("default") ? !peekIs(1, ":") && !peekIs(1, "->")
					: MODIFIERS.contains(token.text())) {
				modifiers.add(token.text());
				this.p++;
			}
			else if (token.text().equals("sealed") && startsDeclaration(peek(1))) {
				modifiers.add("sealed");
				this.p++;
			}
			else if (token.text().equals("non") && peekIs(1, "-") && peekIs(2, "sealed")) {
				modifiers.add("non-sealed");
				this.p += 3;
			}
			else if (REFUSED_MODIFIERS.contains(token.text())) {
				throw unsupported("the modifier " + token.text());
			}
			else {
				return modifiers;
			}
		}
	}

	private boolean atTypeDeclaration() {
		if (at("class") || at("interface") || at("enum") || at("@")) {
			return true;
		}
		return at("record") && peek(1).kind() == Kind.IDENTIFIER && (peekIs(2, "(") || peekIs(2, "<"));
	}

	private ClassDeclaration typeDeclaration(Set<String> modifiers) {
		if (at("@")) {
			throw unsupported("an annotation type");
		}
		JavaSyntax.Kind kind = at("enum") ? JavaSyntax.Kind.ENUM : at("record") ? JavaSyntax.Kind.RECORD
				: at("interface") ? JavaSyntax.Kind.INTERFACE : JavaSyntax.Kind.CLASS;
		this.p++;
		int pos = current().offset();
		String name = identifier();
		if (at("<")) {
			throw unsupported(switch (kind) {
				case INTERFACE -> "a generic interface";
				case RECORD -> "a generic record";
				default -> "a generic class";
			});
		}
		List<Parameter> components = kind == JavaSyntax.Kind.RECORD ? parameters(false) : List.of();
		for (Parameter component : components) {
			if (component.varargs()) {
				throw unsupported("a variable arity record component", component.pos());
			}
		}
		TypeName superclass = null;
		List<TypeName> interfaces = new ArrayList<>();
		List<TypeName> permits = new ArrayList<>();
		if (at("extends")) {
			if (kind == JavaSyntax.Kind.ENUM || kind == JavaSyntax.Kind.RECORD) {
				throw error("no extends clause allowed here");
			}
			this.p++;
			if (kind == JavaSyntax.Kind.INTERFACE) {
				do {
					interfaces.add(supertype());
				}
				while (eat(","));
			}
			else {
				superclass = supertype();
			}
		}
		if (eat("implements")) {
			if (kind == JavaSyntax.Kind.INTERFACE) {
				throw error("an interface extends its interfaces");
			}
			do {
				interfaces.add(supertype());
			}
			while (eat(","));
		}
		if (eat("permits")) {
			do {
				permits.add(supertype());
			}
			while (eat(","));
		}
		Body body = body(name, kind);
		return new ClassDeclaration(pos, modifiers, kind, name, superclass, interfaces, permits, components,
				body.constants, body.fields, body.methods, body.initializers, body.nested, false);
	}

	/** A type's body, from its opening brace to its closing one. */
	private Body body(String name, JavaSyntax.Kind kind) {
		expect("{");
		Body body = new Body();
		if (kind == JavaSyntax.Kind.ENUM) {
			while (!at(";") && !at("}")) {
				annotations();
				int pos = current().offset();
				String constant = identifier();
				List<Expression> arguments = at("(") ? arguments() : List.of();
				ClassDeclaration constantBody = null;
				if (at("{")) {
					Body members = body("", JavaSyntax.Kind.CLASS);
					constantBody = new ClassDeclaration(pos, Set.of(), JavaSyntax.Kind.CLASS, "", null, List.of(),
							List.of(), List.of(), List.of(), members.fields, members.methods, members.initializers,
							members.nested, false);
				}
				body.constants.add(new EnumConstant(pos, constant, arguments, constantBody));
				if (!eat(",")) {
					break;
				}
			}
			if (!at("}")) {
				expect(";");
			}
		}
		while (!eat("}")) {
			if (current().kind() == Kind.END) {
				throw error("expected '}' but found the end of the file");
			}
			member(name, kind, body);
		}
		return body;
	}

	private TypeName supertype() {
		int start = current().offset();
		TypeName type = typeName();
		if (!type.arguments().isEmpty() || type.dimensions() > 0) {
			throw unsupported("a parameterized supertype", start);
		}
		return type;
	}

	private void member(String className, JavaSyntax.Kind kind, Body into) {
		if (eat(";")) {
			return;
		}
		boolean inInterface = kind == JavaSyntax.Kind.INTERFACE;
		Set<String> modifiers = modifiers();
		if (at("{")) {
			if (inInterface) {
				throw error("an interface has no initializer block");
			}
			int pos = current().offset();
			into.initializers.add(new Initializer(pos, modifiers.contains("static"), block()));
			return;
		}
		if (atTypeDeclaration()) {
			into.nested.add(typeDeclaration(modifiers));
			return;
		}
		if (at("<")) {
			throw unsupported("a generic method");
		}
		if (atIdentifier() && current().text().equals(className) && (peekIs(1, "(") || peekIs(1, "{"))) {
			if (inInterface) {
				throw error("an interface has no constructor");
			}
			int pos = current().offset();
			this.p++;
			if (at("{")) {
				if (kind != JavaSyntax.Kind.RECORD) {
					throw error("expected '(' but found '{'");
				}
				into.methods.add(new Method(pos, modifiers, null, "<init>", List.of(), block(), true));
				return;
			}
			List<Parameter> parameters = parameters(false);
			throwsClause();
			into.methods.add(new Method(pos, modifiers, null, "<init>", parameters, block()));
			return;
		}
		TypeName type = at("void") ? new TypeName(this.tokens.get(this.p++).offset(), "void", List.of(), false, 0)
				: typeName();
		int pos = current().offset();
		String name = identifier();
		if (at("(")) {
			List<Parameter> parameters = parameters(false);
			if (at("[")) {
				throw unsupported("an array dimension after a method's parameters");
			}
			throwsClause();
			Block body = null;
			if (!eat(";")) {
				body = block();
			}
			into.methods.add(new Method(pos, modifiers, type, name, parameters, body));
			return;
		}
		if (type.name().equals("void")) {
			throw error("expected '(' but found " + current().shown());
		}
		while (true) {
			if (at("[")) {
				throw unsupported("an array dimension after a variable's name");
			}
			Expression initializer = eat("=") ? variableInitializer() : null;
			into.fields.add(new Field(pos, modifiers, type, name, initializer));
			if (eat(";")) {
				return;
			}
			expect(",");
			pos = current().offset();
			name = identifier();
		}
	}

	private void throwsClause() {
		if (eat("throws")) {
			do {
				typeName();
			}
			while (eat(","));
		}
	}

	/**
	 * A parenthesized parameter list.
	 * @param unnamed whether a parameter may be the unnamed {@code _}
	 */
	private List<Parameter> parameters(boolean unnamed) {
		expect("(");
		List<Parameter> parameters = new ArrayList<>();
		while (!eat(")")) {
			if (!parameters.isEmpty()) {
				expect(",");
			}
			boolean isFinal = finalModifier();
			TypeName type = typeName();
			boolean varargs = eat("...");
			if (varargs) {
				type = new TypeName(type.pos(), type.name(), type.arguments(), type.diamond(), type.dimensions() + 1);
			}
			if (at("this")) {
				throw unsupported("a receiver parameter");
			}
			int pos = current().offset();
			String name = unnamed ? identifierOrUnnamed() : identifier();
			if (at("[")) {
				throw unsupported("an array dimension after a variable's name");
			}
			if (varargs && !at(")")) {
				throw error("a variable arity parameter must be the last parameter");
			}
			parameters.add(new Parameter(pos, type, name, varargs, isFinal));
		}
		return parameters;
	}

	/**
	 * Annotations and {@code final} ahead of a variable: whether {@code final} was there.
	 */
	private boolean finalModifier() {
		boolean isFinal = false;
		annotations();
		while (eat("final")) {
			isFinal = true;
			annotations();
		}
		return isFinal;
	}

	private String identifierOrUnnamed() {
		if (at("_")) {
			this.p++;
			return "_";
		}
		return identifier();
	}

	private TypeName typeName() {
		int pos = current().offset();
		if (at("?")) {
			throw unsupported("a wildcard");
		}
		String name;
		if (current().kind() == Kind.IDENTIFIER && PRIMITIVES.contains(current().text())) {
			name = this.tokens.get(this.p++).text();
		}
		else {
			StringBuilder qualified = new StringBuilder(identifier());
			while (at(".") && peek(1).kind() == Kind.IDENTIFIER && !RESERVED.contains(peek(1).text())) {
				this.p++;
				qualified.append('.').append(identifier());
			}
			name = qualified.toString();
		}
		List<TypeName> arguments = new ArrayList<>();
		boolean diamond = false;
		if (at("<") && !PRIMITIVES.contains(name)) {
			this.p++;
			if (eat(">")) {
				diamond = true;
			}
			else {
				do {
					annotations();
					arguments.add(typeName());
				}
				while (eat(","));
				expect(">");
			}
			if (at(".")) {
				throw unsupported("a type nested in a parameterized type");
			}
		}
		int dimensions = 0;
		while (at("[") && peekIs(1, "]")) {
			this.p += 2;
			dimensions++;
		}
		return new TypeName(pos, name, arguments, diamond, dimensions);
	}

	// ---- statements

	private Block block() {
		int pos = expect("{").offset();
		List<Statement> statements = new ArrayList<>();
		while (!eat("}")) {
			if (current().kind() == Kind.END) {
				throw error("expected '}' but found the end of the file");
			}
			statement(statements);
		}
		return new Block(pos, statements);
	}

	private Statement single() {
		int pos = current().offset();
		List<Statement> statements = new ArrayList<>();
		statement(statements);
		if (statements.size() == 1 && !(statements.get(0) instanceof LocalVariable)) {
			return statements.get(0);
		}
		if (!statements.isEmpty() && statements.get(0) instanceof LocalVariable) {
			throw new LispReadException("a variable declaration is not allowed here", location(pos));
		}
		return new Block(pos, statements);
	}

	/**
	 * Whether a local variable declaration starts here; the position is left as it was.
	 */
	private boolean atDeclaration() {
		int save = this.p;
		try {
			finalModifier();
			typeName();
			if (!atIdentifier() && !at("_")) {
				return false;
			}
			return peekIs(1, "=") || peekIs(1, ";") || peekIs(1, ",") || peekIs(1, ":") || peekIs(1, "[");
		}
		catch (Unsupported ex) {
			throw ex;
		}
		catch (LispReadException ex) {
			return false;
		}
		finally {
			this.p = save;
		}
	}

	private LocalVariable localVariable() {
		boolean isFinal = finalModifier();
		int pos = current().offset();
		TypeName type = typeName();
		List<Declarator> declarators = new ArrayList<>();
		do {
			int at = current().offset();
			String name = identifierOrUnnamed();
			if (at("[")) {
				throw unsupported("an array dimension after a variable's name");
			}
			declarators.add(new Declarator(at, name, eat("=") ? variableInitializer() : null));
		}
		while (eat(","));
		return new LocalVariable(pos, type, declarators, isFinal);
	}

	private Expression variableInitializer() {
		return at("{") ? arrayInitializer() : expression();
	}

	private ArrayInitializer arrayInitializer() {
		int pos = expect("{").offset();
		List<Expression> elements = new ArrayList<>();
		while (!eat("}")) {
			elements.add(variableInitializer());
			if (!at("}")) {
				expect(",");
			}
		}
		return new ArrayInitializer(pos, elements);
	}

	private Expression parenthesized() {
		expect("(");
		Expression expression = expression();
		expect(")");
		return expression;
	}

	private void statement(List<Statement> out) {
		JavaToken token = current();
		int pos = token.offset();
		if (at("{")) {
			out.add(block());
			return;
		}
		if (eat(";")) {
			out.add(new Empty(pos));
			return;
		}
		if (token.kind() == Kind.IDENTIFIER) {
			switch (token.text()) {
				case "if": {
					this.p++;
					Expression condition = parenthesized();
					Statement then = single();
					out.add(new If(pos, condition, then, eat("else") ? single() : null));
					return;
				}
				case "while": {
					this.p++;
					Expression condition = parenthesized();
					out.add(new While(pos, condition, single()));
					return;
				}
				case "do": {
					this.p++;
					Statement body = single();
					expect("while");
					Expression condition = parenthesized();
					expect(";");
					out.add(new DoWhile(pos, body, condition));
					return;
				}
				case "for":
					this.p++;
					out.add(forStatement(pos));
					return;
				case "return": {
					this.p++;
					Expression value = at(";") ? null : expression();
					expect(";");
					out.add(new Return(pos, value));
					return;
				}
				case "break":
				case "continue": {
					this.p++;
					String label = at(";") ? null : identifier();
					expect(";");
					out.add(token.text().equals("break") ? new Break(pos, label) : new Continue(pos, label));
					return;
				}
				case "throw": {
					this.p++;
					Expression value = expression();
					expect(";");
					out.add(new Throw(pos, value));
					return;
				}
				case "try":
					this.p++;
					out.add(tryStatement(pos));
					return;
				case "switch":
					out.add(switchBlock());
					return;
				case "synchronized":
					throw unsupported("a synchronized statement");
				case "assert":
					throw unsupported("an assert statement");
				case "yield":
					if (!peekIs(1, "=") && !peekIs(1, ".") && !peekIs(1, "(") && !peekIs(1, "[") && !peekIs(1, "++")
							&& !peekIs(1, "--") && !peekIs(1, ":")) {
						this.p++;
						Expression value = expression();
						expect(";");
						out.add(new JavaSyntax.Yield(pos, value));
						return;
					}
					break;
				default:
			}
			if (atLocalClass()) {
				Set<String> modifiers = modifiers();
				if (modifiers.contains("static")) {
					throw new LispReadException("modifier static not allowed here", location(pos));
				}
				ClassDeclaration declaration = typeDeclaration(modifiers);
				out.add(new LocalClass(declaration.pos(), declaration));
				return;
			}
			if (atIdentifier() && peekIs(1, ":")) {
				String label = identifier();
				this.p++;
				List<Statement> labeled = new ArrayList<>();
				statement(labeled);
				if (labeled.size() != 1 || labeled.get(0) instanceof LocalVariable
						|| labeled.get(0) instanceof LocalClass) {
					throw new LispReadException("a declaration is not allowed here", location(pos));
				}
				out.add(new Labeled(pos, label, labeled.get(0)));
				return;
			}
		}
		if ((token.kind() == Kind.IDENTIFIER || at("@")) && atDeclaration()) {
			out.add(localVariable());
			expect(";");
			return;
		}
		Expression expression = expression();
		expect(";");
		out.add(new ExpressionStatement(pos, expression));
	}

	/** Whether a local class, interface, enum or record is declared here. */
	private boolean atLocalClass() {
		int save = this.p;
		try {
			modifiers();
			return atTypeDeclaration() && !at("@");
		}
		finally {
			this.p = save;
		}
	}

	/** A switch, from its keyword to the brace closing its block. */
	private JavaSyntax.Switch switchBlock() {
		int pos = expect("switch").offset();
		Expression selector = parenthesized();
		expect("{");
		List<JavaSyntax.SwitchCase> cases = new ArrayList<>();
		boolean arrow = false;
		boolean first = true;
		while (!eat("}")) {
			int at = current().offset();
			List<Expression> constants = new ArrayList<>();
			List<Pattern> patterns = new ArrayList<>();
			boolean hasNull = false;
			boolean hasDefault = false;
			Expression guard = null;
			if (eat("default")) {
				hasDefault = true;
			}
			else {
				expect("case");
				do {
					if (at("null")) {
						this.p++;
						hasNull = true;
					}
					else if (at("default")) {
						this.p++;
						hasDefault = true;
					}
					else if (atPattern()) {
						patterns.add(pattern());
					}
					else {
						constants.add(conditional());
					}
				}
				while (eat(","));
				if (at("when") && !patterns.isEmpty()) {
					this.p++;
					guard = binary(0);
				}
			}
			boolean arrowHere = at("->");
			if (!arrowHere && !at(":")) {
				throw error("expected '->' or ':' but found " + current().shown());
			}
			if (!first && arrowHere != arrow) {
				throw error("different case kinds used in the switch");
			}
			arrow = arrowHere;
			first = false;
			this.p++;
			List<Statement> body = new ArrayList<>();
			Expression value = null;
			if (arrow) {
				if (at("{")) {
					body.add(block());
				}
				else if (at("throw")) {
					statement(body);
				}
				else {
					value = expression();
					expect(";");
				}
			}
			else {
				while (!at("case") && !at("default") && !at("}")) {
					if (current().kind() == Kind.END) {
						throw error("expected '}' but found the end of the file");
					}
					statement(body);
				}
			}
			cases.add(new JavaSyntax.SwitchCase(at, constants, patterns, hasNull, hasDefault, guard, body, value));
		}
		return new JavaSyntax.Switch(pos, selector, cases, arrow);
	}

	/** Whether a pattern, and no constant expression, stands here. */
	private boolean atPattern() {
		if (at("final") || at("@")) {
			return true;
		}
		if (at("_")) {
			return true;
		}
		int save = this.p;
		try {
			typeName();
			return atIdentifier() || at("_") || at("(");
		}
		catch (LispReadException ex) {
			return false;
		}
		finally {
			this.p = save;
		}
	}

	private Pattern pattern() {
		int pos = current().offset();
		if (at("_")) {
			this.p++;
			return new JavaSyntax.TypePattern(pos, new TypeName(pos, "var", List.of(), false, 0), "_");
		}
		finalModifier();
		TypeName type = typeName();
		if (at("(")) {
			this.p++;
			List<Pattern> components = new ArrayList<>();
			while (!eat(")")) {
				if (!components.isEmpty()) {
					expect(",");
				}
				components.add(pattern());
			}
			return new RecordPattern(pos, type, components);
		}
		return new JavaSyntax.TypePattern(pos, type, identifierOrUnnamed());
	}

	private Statement forStatement(int pos) {
		expect("(");
		List<Statement> initializers = new ArrayList<>();
		if (!at(";")) {
			if (atDeclaration()) {
				LocalVariable variable = localVariable();
				if (eat(":")) {
					Declarator declarator = variable.declarators().get(0);
					if (variable.declarators().size() != 1 || declarator.initializer() != null) {
						throw new LispReadException("malformed enhanced for", location(variable.pos()));
					}
					Expression iterable = expression();
					expect(")");
					return new ForEach(pos, variable.type(), declarator.name(), iterable, single());
				}
				initializers.add(variable);
			}
			else {
				do {
					int at = current().offset();
					initializers.add(new ExpressionStatement(at, expression()));
				}
				while (eat(","));
			}
		}
		expect(";");
		Expression condition = at(";") ? null : expression();
		expect(";");
		List<Expression> updates = new ArrayList<>();
		if (!at(")")) {
			do {
				updates.add(expression());
			}
			while (eat(","));
		}
		expect(")");
		return new For(pos, initializers, condition, updates, single());
	}

	private Statement tryStatement(int pos) {
		if (at("(")) {
			throw unsupported("a try-with-resources statement");
		}
		Block body = block();
		List<Catch> catches = new ArrayList<>();
		while (at("catch")) {
			int at = this.tokens.get(this.p++).offset();
			expect("(");
			finalModifier();
			TypeName type = typeName();
			if (at("|")) {
				throw unsupported("a multi-catch");
			}
			String name = identifierOrUnnamed();
			expect(")");
			catches.add(new Catch(at, type, name, block()));
		}
		Block finallyBlock = eat("finally") ? block() : null;
		if (catches.isEmpty() && finallyBlock == null) {
			throw error("a try needs a catch or a finally");
		}
		return new Try(pos, body, catches, finallyBlock);
	}

	// ---- expressions

	/**
	 * The operator standing here, adjacent {@code >} tokens joined into one, or
	 * {@code null}; {@link #operatorWidth} is how many tokens it spans.
	 */
	private @Nullable String operator() {
		JavaToken token = current();
		this.operatorWidth = 1;
		if (token.is("instanceof")) {
			return "instanceof";
		}
		if (token.kind() != Kind.OPERATOR) {
			return null;
		}
		if (!token.text().equals(">")) {
			return token.text();
		}
		StringBuilder joined = new StringBuilder(">");
		int width = 1;
		while (width < 3 && peek(width).is(">") && peek(width).offset() == token.offset() + width) {
			joined.append('>');
			width++;
		}
		if (peek(width).is("=") && peek(width).offset() == token.offset() + width) {
			joined.append('=');
			width++;
		}
		this.operatorWidth = width;
		return joined.toString();
	}

	private Expression expression() {
		if (atLambda()) {
			return lambda();
		}
		Expression left = conditional();
		String operator = operator();
		if (operator != null && ASSIGNMENTS.contains(operator)) {
			int pos = current().offset();
			this.p += this.operatorWidth;
			Expression value = expression();
			return new Assign(pos, operator.equals("=") ? "=" : operator.substring(0, operator.length() - 1), left,
					value);
		}
		return left;
	}

	private Expression conditional() {
		Expression condition = binary(0);
		if (at("?")) {
			int pos = this.tokens.get(this.p++).offset();
			Expression whenTrue = atLambda() ? lambda() : expression();
			expect(":");
			Expression whenFalse = atLambda() ? lambda() : conditional();
			return new Conditional(pos, condition, whenTrue, whenFalse);
		}
		return condition;
	}

	private Expression binary(int level) {
		if (level == LEVELS.size()) {
			return unary();
		}
		Expression left = binary(level + 1);
		while (true) {
			String operator = operator();
			if (operator == null || !LEVELS.get(level).contains(operator)) {
				return left;
			}
			int pos = current().offset();
			this.p += this.operatorWidth;
			if (operator.equals("instanceof")) {
				if (atPattern() && !at("_")) {
					left = new InstanceOf(pos, left, null, pattern());
				}
				else {
					left = new InstanceOf(pos, left, typeName(), null);
				}
			}
			else {
				left = new Binary(pos, operator, left, binary(level + 1));
			}
		}
	}

	private Expression unary() {
		JavaToken token = current();
		int pos = token.offset();
		if (token.kind() == Kind.OPERATOR) {
			switch (token.text()) {
				case "-":
					this.p++;
					if (current().kind() == Kind.INT || current().kind() == Kind.LONG) {
						return postfix(integer(true));
					}
					return new Unary(pos, "-", unary(), false);
				case "+", "!", "~", "++", "--":
					this.p++;
					return new Unary(pos, token.text(), unary(), false);
				case "(":
					if (!atLambda() && atCast()) {
						this.p++;
						TypeName type = typeName();
						expect(")");
						return new Cast(pos, type, atLambda() ? lambda() : unary());
					}
					break;
				default:
			}
		}
		return postfix(primary());
	}

	private boolean atCast() {
		int save = this.p;
		try {
			this.p++;
			TypeName type = typeName();
			boolean intersection = at("&");
			while (eat("&")) {
				typeName();
			}
			if (!eat(")")) {
				return false;
			}
			if (PRIMITIVES.contains(type.name()) && type.dimensions() == 0) {
				return true;
			}
			JavaToken next = current();
			if (intersection) {
				// (a & b) is an expression unless an operand follows it
				if (next.kind() == Kind.IDENTIFIER && !next.text().equals("instanceof") || next.is("(")) {
					throw unsupported("an intersection type", this.tokens.get(save).offset());
				}
				return false;
			}
			return switch (next.kind()) {
				case STRING, CHAR, INT, LONG, DOUBLE -> true;
				case IDENTIFIER -> !next.text().equals("instanceof");
				case OPERATOR -> next.text().equals("(") || next.text().equals("!") || next.text().equals("~");
				default -> false;
			};
		}
		catch (Unsupported ex) {
			throw ex;
		}
		catch (LispReadException ex) {
			return false;
		}
		finally {
			this.p = save;
		}
	}

	private boolean atLambda() {
		if (atIdentifier() || at("_")) {
			return peekIs(1, "->");
		}
		if (!at("(")) {
			return false;
		}
		int depth = 0;
		int i = this.p;
		do {
			JavaToken token = this.tokens.get(i);
			if (token.kind() == Kind.END) {
				return false;
			}
			if (token.is("(")) {
				depth++;
			}
			else if (token.is(")")) {
				depth--;
			}
			i++;
		}
		while (depth > 0);
		return this.tokens.get(i).is("->");
	}

	private Expression lambda() {
		int pos = current().offset();
		List<Parameter> parameters = new ArrayList<>();
		if (eat("(")) {
			while (!eat(")")) {
				if (!parameters.isEmpty()) {
					expect(",");
				}
				finalModifier();
				int at = current().offset();
				if ((atIdentifier() || at("_")) && (peekIs(1, ",") || peekIs(1, ")"))) {
					parameters.add(new Parameter(at, null, identifierOrUnnamed()));
				}
				else {
					TypeName type = typeName();
					at = current().offset();
					parameters.add(new Parameter(at, type.name().equals("var") ? null : type, identifierOrUnnamed()));
				}
			}
		}
		else {
			parameters.add(new Parameter(pos, null, identifierOrUnnamed()));
		}
		expect("->");
		return at("{") ? new Lambda(pos, parameters, null, block()) : new Lambda(pos, parameters, expression(), null);
	}

	private List<Expression> arguments() {
		expect("(");
		List<Expression> arguments = new ArrayList<>();
		while (!eat(")")) {
			if (!arguments.isEmpty()) {
				expect(",");
			}
			arguments.add(expression());
		}
		return arguments;
	}

	/** An integer literal at the current token, negated when a minus stood before it. */
	private Expression integer(boolean negative) {
		JavaToken token = this.tokens.get(this.p++);
		boolean isLong = token.kind() == Kind.LONG;
		BigInteger value = java.util.Objects.requireNonNull(token.integer());
		BigInteger limit = isLong ? LONG_LIMIT : INT_LIMIT;
		if (token.decimal()) {
			if (value.compareTo(limit) > 0 || (!negative && value.compareTo(limit) == 0)) {
				throw new LispReadException("integer number too large", location(token.offset()));
			}
			if (negative) {
				value = value.negate();
			}
		}
		else {
			if (value.bitLength() > (isLong ? 64 : 32)) {
				throw new LispReadException("integer number too large", location(token.offset()));
			}
			// a literal in another base may fill the sign bit
			if (value.compareTo(limit) >= 0) {
				value = value.subtract(limit.shiftLeft(1));
			}
			if (negative) {
				value = value.negate();
				if (value.compareTo(limit) == 0) {
					value = value.negate();
				}
			}
		}
		return new IntLiteral(token.offset(), value.longValue(), isLong);
	}

	private Expression primary() {
		JavaToken token = current();
		int pos = token.offset();
		switch (token.kind()) {
			case INT, LONG:
				return integer(false);
			case DOUBLE:
				this.p++;
				return new DoubleLiteral(pos, token.number());
			case CHAR:
				this.p++;
				return new CharLiteral(pos, (int) token.number());
			case STRING:
				this.p++;
				return new StringLiteral(pos, token.text());
			case OPERATOR:
				if (token.text().equals("(")) {
					this.p++;
					Expression expression = expression();
					expect(")");
					return expression;
				}
				throw error("unexpected " + token.shown());
			case END:
				throw error("unexpected end of the file");
			default:
		}
		switch (token.text()) {
			case "true", "false":
				this.p++;
				return new BooleanLiteral(pos, token.text().equals("true"));
			case "null":
				this.p++;
				return new NullLiteral(pos);
			case "this":
				this.p++;
				return at("(") ? new ConstructorCall(pos, false, arguments()) : new This(pos);
			case "super": {
				this.p++;
				if (at("(")) {
					return new ConstructorCall(pos, true, arguments());
				}
				expect(".");
				if (at("<")) {
					throw unsupported("an explicit type argument");
				}
				int at = current().offset();
				String name = identifier();
				if (!at("(")) {
					throw unsupported("a field read through super", at);
				}
				return new Call(at, null, name, arguments(), true);
			}
			case "new":
				this.p++;
				return creation(pos, null);
			case "switch":
				return switchBlock();
			default:
		}
		if (PRIMITIVES.contains(token.text()) || token.text().equals("void")) {
			throw unsupported("a class literal");
		}
		String name = identifier();
		return at("(") ? new Call(pos, null, name, arguments(), false) : new Name(pos, name);
	}

	private Expression creation(int pos, @Nullable Expression outer) {
		annotations();
		TypeName type = typeName();
		if (type.dimensions() > 0) {
			// new T[]{..}
			if (!at("{")) {
				throw error("expected '{' but found " + current().shown());
			}
			return new NewArray(pos, type, List.of(), arrayInitializer());
		}
		if (at("[") && outer == null) {
			List<Expression> lengths = new ArrayList<>();
			int dimensions = 0;
			while (at("[") && !peekIs(1, "]")) {
				this.p++;
				lengths.add(expression());
				expect("]");
				dimensions++;
			}
			while (at("[") && peekIs(1, "]")) {
				this.p += 2;
				dimensions++;
			}
			return new NewArray(pos, new TypeName(type.pos(), type.name(), type.arguments(), false, dimensions),
					lengths, null);
		}
		List<Expression> arguments = arguments();
		ClassDeclaration body = null;
		if (at("{")) {
			Body members = body("", JavaSyntax.Kind.CLASS);
			body = new ClassDeclaration(pos, Set.of(), JavaSyntax.Kind.CLASS, "", null, List.of(), List.of(), List.of(),
					List.of(), members.fields, members.methods, members.initializers, members.nested, false);
		}
		return new New(pos, type, arguments, body, outer);
	}

	private Expression postfix(Expression expression) {
		while (true) {
			int pos = current().offset();
			if (eat(".")) {
				if (at("<")) {
					throw unsupported("an explicit type argument");
				}
				if (at("new")) {
					int at = this.tokens.get(this.p++).offset();
					expression = creation(at, expression);
					continue;
				}
				if (at("this")) {
					expression = new QualifiedThis(this.tokens.get(this.p++).offset(), expression);
					continue;
				}
				if (at("super")) {
					throw unsupported("a method of an interface called through super");
				}
				if (at("class")) {
					throw unsupported("a class literal");
				}
				int at = current().offset();
				String name = identifier();
				expression = at("(") ? new Call(at, expression, name, arguments(), false)
						: new Select(at, expression, name);
			}
			else if (at("[")) {
				if (peekIs(1, "]")) {
					throw unsupported("a class literal");
				}
				this.p++;
				expression = new Index(pos, expression, expression());
				expect("]");
			}
			else if (at("++") || at("--")) {
				expression = new Unary(pos, this.tokens.get(this.p++).text(), expression, true);
			}
			else if (at("::")) {
				throw unsupported("a method reference");
			}
			else {
				return expression;
			}
		}
	}

}
