package am.ik.rontolisp.java;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The syntax tree of the Java subset: one record per production the front end reads, each
 * with the offset of the token an error about it points at.
 */
final class JavaSyntax {

	private JavaSyntax() {
	}

	/**
	 * A type as written.
	 *
	 * @param pos its first token
	 * @param name its name, dotted when qualified; {@code var} for an inferred local;
	 * {@code ?}, {@code ? extends} or {@code ? super} for a wildcard
	 * @param arguments its type arguments; a wildcard's bound
	 * @param diamond whether it is written with an empty {@code <>}
	 * @param dimensions how many {@code []} follow it
	 * @param enclosing the type a qualified name's prefix spells when it is written with
	 * type arguments, {@code Outer<String>} of {@code Outer<String>.Inner}, or
	 * {@code null}
	 */
	record TypeName(int pos, String name, List<TypeName> arguments, boolean diamond, int dimensions,
			@Nullable TypeName enclosing) {

		TypeName(int pos, String name, List<TypeName> arguments, boolean diamond, int dimensions) {
			this(pos, name, arguments, diamond, dimensions, null);
		}

		TypeName element() {
			return withDimensions(this.dimensions - 1);
		}

		TypeName withDimensions(int dimensions) {
			return new TypeName(this.pos, this.name, this.arguments, this.diamond, dimensions, this.enclosing);
		}

		boolean isWildcard() {
			return this.name.startsWith("?");
		}

	}

	/**
	 * A type parameter as declared, {@code T extends A & B}.
	 *
	 * @param pos its name
	 * @param name its name
	 * @param bounds what follows {@code extends}
	 */
	record TypeParameter(int pos, String name, List<TypeName> bounds) {
	}

	sealed interface Expression {

		int pos();

	}

	record IntLiteral(int pos, long value, boolean isLong) implements Expression {
	}

	/**
	 * A floating-point literal: a {@code double}, or a {@code float} holding its value.
	 */
	record DoubleLiteral(int pos, double value, boolean isFloat) implements Expression {
	}

	record CharLiteral(int pos, int value) implements Expression {
	}

	record StringLiteral(int pos, String value) implements Expression {
	}

	record BooleanLiteral(int pos, boolean value) implements Expression {
	}

	record NullLiteral(int pos) implements Expression {
	}

	record Name(int pos, String identifier) implements Expression {
	}

	/**
	 * A class literal, {@code Name.class}.
	 *
	 * @param pos the {@code class}
	 * @param type the class it names
	 */
	record ClassLiteral(int pos, TypeName type) implements Expression {
	}

	record This(int pos) implements Expression {
	}

	/**
	 * {@code Outer.this}.
	 *
	 * @param pos the {@code this}
	 * @param qualifier the class named before it
	 */
	record QualifiedThis(int pos, Expression qualifier) implements Expression {
	}

	record Select(int pos, Expression target, String name) implements Expression {
	}

	/**
	 * A method call.
	 *
	 * @param pos the method's name
	 * @param target what it is called on, or {@code null} for an unqualified call
	 * @param name the method's name
	 * @param arguments the arguments
	 * @param onSuper whether it is {@code super.name(..)}
	 * @param typeArguments the type arguments written ahead of the name
	 */
	record Call(int pos, @Nullable Expression target, String name, List<Expression> arguments, boolean onSuper,
			List<TypeName> typeArguments) implements Expression {

		Call(int pos, @Nullable Expression target, String name, List<Expression> arguments, boolean onSuper) {
			this(pos, target, name, arguments, onSuper, List.of());
		}

	}

	/**
	 * A method reference, {@code target::name}.
	 *
	 * @param pos the {@code ::}
	 * @param target what stands before it when that is an expression or a class's name
	 * @param type what stands before it when that is a type no expression spells: an
	 * array type, a parameterized type
	 * @param name the method's name, {@code new} for a constructor
	 * @param onSuper whether it is {@code super::name}
	 * @param typeArguments the method's type arguments as written, {@code ::<T>name}
	 */
	record MethodReference(int pos, @Nullable Expression target, @Nullable TypeName type, String name, boolean onSuper,
			List<TypeName> typeArguments) implements Expression {
	}

	/**
	 * A value the lowering has already: an argument of the call a method reference stands
	 * for.
	 *
	 * @param pos where it is said to be
	 * @param value the lowered value
	 */
	record Lowered(int pos, Object value) implements Expression {
	}

	/**
	 * What a session shows of a top-level expression or variable: the text
	 * {@code label ==> value}, a {@code String} written by the value's static type. Only
	 * a session's parser writes one.
	 *
	 * @param pos the expression or the variable's name
	 * @param label the name the value is shown under
	 * @param field the session's variable the value is stored in, or {@code null}: an
	 * assignment stores it itself
	 * @param value the value, or {@code null} for a variable declared with none, which
	 * holds its type's default
	 */
	record Echo(int pos, String label, @Nullable String field, @Nullable Expression value) implements Expression {
	}

	/**
	 * {@code this(..)} or {@code super(..)}.
	 *
	 * @param pos the keyword
	 * @param onSuper whether it is {@code super(..)}
	 * @param arguments the arguments
	 * @param outer the instance before {@code .super(..)}, which the superclass, an inner
	 * class, gets as its outer instance; or {@code null}
	 */
	record ConstructorCall(int pos, boolean onSuper, List<Expression> arguments,
			@Nullable Expression outer) implements Expression {

		ConstructorCall(int pos, boolean onSuper, List<Expression> arguments) {
			this(pos, onSuper, arguments, null);
		}

	}

	/**
	 * An instance creation.
	 *
	 * @param pos the {@code new}
	 * @param type the class made, or the one an anonymous class extends or implements
	 * @param arguments the constructor's arguments
	 * @param body the anonymous class's body, or {@code null}
	 * @param outer the instance before {@code .new}, or {@code null}
	 * @param typeArguments the constructor's type arguments written after {@code new}
	 */
	record New(int pos, TypeName type, List<Expression> arguments, @Nullable ClassDeclaration body,
			@Nullable Expression outer, List<TypeName> typeArguments) implements Expression {
	}

	/**
	 * An array creation.
	 *
	 * @param pos the {@code new}
	 * @param type the array's type
	 * @param lengths the lengths written, empty with an initializer
	 * @param initializer the initializer, or {@code null} with lengths
	 */
	record NewArray(int pos, TypeName type, List<Expression> lengths,
			@Nullable ArrayInitializer initializer) implements Expression {
	}

	record ArrayInitializer(int pos, List<Expression> elements) implements Expression {
	}

	record Index(int pos, Expression array, Expression index) implements Expression {
	}

	record Unary(int pos, String operator, Expression operand, boolean postfix) implements Expression {
	}

	record Binary(int pos, String operator, Expression left, Expression right) implements Expression {
	}

	/**
	 * An assignment.
	 *
	 * @param pos the operator
	 * @param operator {@code =} or a compound operator without its {@code =} ({@code +},
	 * {@code >>>} ..)
	 * @param target the place
	 * @param value the right-hand side
	 */
	record Assign(int pos, String operator, Expression target, Expression value) implements Expression {
	}

	record Conditional(int pos, Expression condition, Expression whenTrue, Expression whenFalse) implements Expression {
	}

	/**
	 * A cast.
	 *
	 * @param pos the {@code (}
	 * @param type the type cast to
	 * @param operand what is cast
	 * @param also the further types of a cast to an intersection, {@code (A & B) x}
	 */
	record Cast(int pos, TypeName type, Expression operand, List<TypeName> also) implements Expression {
	}

	/**
	 * {@code instanceof}.
	 *
	 * @param pos the operator
	 * @param operand what is tested
	 * @param type the type tested for, when no pattern is written
	 * @param pattern the pattern, or {@code null}
	 */
	record InstanceOf(int pos, Expression operand, @Nullable TypeName type,
			@Nullable Pattern pattern) implements Expression {
	}

	sealed interface Pattern {

		int pos();

	}

	/**
	 * A type pattern, {@code Circle c}; {@code var c}; the unnamed {@code _}.
	 *
	 * @param pos its first token
	 * @param type the type, {@code var} when inferred
	 * @param name the variable, {@code _} when unnamed
	 */
	record TypePattern(int pos, TypeName type, String name) implements Pattern {
	}

	/**
	 * A record pattern, {@code Point(int x, var y)}.
	 *
	 * @param pos its first token
	 * @param type the record
	 * @param components the patterns of its components
	 */
	record RecordPattern(int pos, TypeName type, List<Pattern> components) implements Pattern {
	}

	/**
	 * One {@code case} of a switch.
	 *
	 * @param pos the {@code case} or {@code default}
	 * @param constants the constants it names
	 * @param patterns the patterns it names
	 * @param hasNull whether it names {@code null}
	 * @param hasDefault whether it is, or includes, {@code default}
	 * @param guard what follows {@code when}, or {@code null}
	 * @param body the statements after a colon; after an arrow the one block or
	 * {@code throw}, or nothing when {@link #value} is the arm
	 * @param value the expression after an arrow, or {@code null}
	 */
	record SwitchCase(int pos, List<Expression> constants, List<Pattern> patterns, boolean hasNull, boolean hasDefault,
			@Nullable Expression guard, List<Statement> body, @Nullable Expression value) {
	}

	/**
	 * A switch, as a statement or as an expression.
	 *
	 * @param pos the {@code switch}
	 * @param selector what it switches over
	 * @param cases its cases, in order
	 * @param arrow whether its cases are written with {@code ->}
	 * @param end its closing brace
	 */
	record Switch(int pos, Expression selector, List<SwitchCase> cases, boolean arrow,
			int end) implements Expression, Statement {

		Switch(int pos, Expression selector, List<SwitchCase> cases, boolean arrow) {
			this(pos, selector, cases, arrow, pos);
		}

	}

	/**
	 * A lambda expression.
	 *
	 * @param pos its first token
	 * @param parameters the parameters, a type {@code null} when not written
	 * @param expression the body when it is an expression
	 * @param block the body when it is a block
	 */
	record Lambda(int pos, List<Parameter> parameters, @Nullable Expression expression,
			@Nullable Block block) implements Expression {
	}

	sealed interface Statement {

		int pos();

	}

	record Declarator(int pos, String name, @Nullable Expression initializer) {
	}

	record LocalVariable(int pos, TypeName type, List<Declarator> declarators, boolean isFinal) implements Statement {
	}

	record LocalClass(int pos, ClassDeclaration declaration) implements Statement {
	}

	record Labeled(int pos, String label, Statement body) implements Statement {
	}

	record Yield(int pos, Expression value) implements Statement {
	}

	record ExpressionStatement(int pos, Expression expression) implements Statement {
	}

	record If(int pos, Expression condition, Statement then, @Nullable Statement otherwise) implements Statement {
	}

	record While(int pos, Expression condition, Statement body) implements Statement {
	}

	record DoWhile(int pos, Statement body, Expression condition) implements Statement {
	}

	record For(int pos, List<Statement> initializers, @Nullable Expression condition, List<Expression> updates,
			Statement body) implements Statement {
	}

	record ForEach(int pos, TypeName type, String name, Expression iterable, Statement body) implements Statement {
	}

	record Return(int pos, @Nullable Expression value) implements Statement {
	}

	record Break(int pos, @Nullable String label) implements Statement {
	}

	record Continue(int pos, @Nullable String label) implements Statement {
	}

	/**
	 * A block.
	 *
	 * @param pos its opening brace
	 * @param statements its statements
	 * @param end its closing brace, where what is said of its end points
	 */
	record Block(int pos, List<Statement> statements, int end) implements Statement {

		Block(int pos, List<Statement> statements) {
			this(pos, statements, pos);
		}

	}

	record Throw(int pos, Expression value) implements Statement {
	}

	/**
	 * A {@code catch} clause.
	 *
	 * @param pos the {@code catch}
	 * @param types the types it takes: several for a multi-catch
	 * @param name its parameter
	 * @param body its block
	 */
	record Catch(int pos, List<TypeName> types, String name, Block body) {
	}

	/**
	 * What a {@code try}-with-resources does with a resource on its way out: closes it
	 * when it is no {@code null}, an exception of {@code close} suppressed by the one the
	 * body threw. The parser writes the statement as the language specifies it
	 * ({@code JavaParser.withResources}); only this part is left to the lowering, which
	 * checks the resource's type.
	 *
	 * @param pos the resource
	 * @param resource the variable holding it
	 * @param primary the variable holding what the body threw, or {@code null}
	 */
	record Close(int pos, String resource, String primary) implements Statement {
	}

	record Try(int pos, Block body, List<Catch> catches, @Nullable Block finallyBlock) implements Statement {
	}

	/**
	 * A {@code synchronized} statement.
	 *
	 * @param pos the keyword
	 * @param lock the object whose monitor the body holds, or {@code null}
	 * @param type the class whose monitor it holds when the lock is written as the class
	 * literal {@code Name.class}, or {@code null}
	 * @param body the block
	 */
	record Synchronized(int pos, @Nullable Expression lock, @Nullable TypeName type, Block body) implements Statement {
	}

	record Empty(int pos) implements Statement {
	}

	/**
	 * A parameter of a method, a constructor, a lambda or a record.
	 *
	 * @param pos its name
	 * @param type its type, {@code null} when a lambda leaves it out; an array for a
	 * variable arity parameter
	 * @param name its name, {@code _} when unnamed
	 * @param varargs whether it is written with {@code ...}
	 * @param isFinal whether it is declared {@code final}
	 */
	record Parameter(int pos, @Nullable TypeName type, String name, boolean varargs, boolean isFinal) {

		Parameter(int pos, @Nullable TypeName type, String name) {
			this(pos, type, name, false, false);
		}

	}

	/**
	 * An initializer block.
	 *
	 * @param pos its brace
	 * @param isStatic whether it is {@code static}
	 * @param body the block
	 */
	record Initializer(int pos, boolean isStatic, Block body) {
	}

	/**
	 * An enum constant.
	 *
	 * @param pos its name
	 * @param name its name
	 * @param arguments the constructor's arguments
	 * @param body its class body, or {@code null}
	 */
	record EnumConstant(int pos, String name, List<Expression> arguments, @Nullable ClassDeclaration body) {
	}

	record Field(int pos, Set<String> modifiers, TypeName type, String name, @Nullable Expression initializer) {
	}

	/**
	 * A method or a constructor.
	 *
	 * @param pos its name
	 * @param modifiers its modifiers
	 * @param returnType its return type, {@code null} for a constructor
	 * @param name its name
	 * @param parameters its parameters
	 * @param body its body, or {@code null} for an abstract method
	 * @param compact whether it is a record's compact constructor
	 * @param typeParameters the type parameters it declares
	 */
	record Method(int pos, Set<String> modifiers, @Nullable TypeName returnType, String name,
			List<Parameter> parameters, @Nullable Block body, boolean compact, List<TypeParameter> typeParameters) {

		Method(int pos, Set<String> modifiers, @Nullable TypeName returnType, String name, List<Parameter> parameters,
				@Nullable Block body) {
			this(pos, modifiers, returnType, name, parameters, body, false, List.of());
		}

		boolean varargs() {
			return !this.parameters.isEmpty() && this.parameters.get(this.parameters.size() - 1).varargs();
		}

	}

	/** What a type declaration declares. */
	enum Kind {

		CLASS, INTERFACE, ENUM, RECORD

	}

	/**
	 * A class, an interface, an enum or a record.
	 *
	 * @param pos its name; the {@code new} of an anonymous class
	 * @param modifiers its modifiers
	 * @param kind what it declares
	 * @param name its name, empty for an anonymous class
	 * @param superclass what it extends, or {@code null}
	 * @param interfaces what a class implements or an interface extends
	 * @param permits what a sealed type permits
	 * @param components a record's components
	 * @param constants an enum's constants
	 * @param fields its fields, in order
	 * @param methods its methods and constructors, in order
	 * @param initializers its initializer blocks, in order
	 * @param nested the types declared in its body
	 * @param implicit whether it is the class of a compact source file
	 * @param typeParameters the type parameters it declares
	 */
	record ClassDeclaration(int pos, Set<String> modifiers, Kind kind, String name, @Nullable TypeName superclass,
			List<TypeName> interfaces, List<TypeName> permits, List<Parameter> components, List<EnumConstant> constants,
			List<Field> fields, List<Method> methods, List<Initializer> initializers, List<ClassDeclaration> nested,
			boolean implicit, List<TypeParameter> typeParameters) {

		ClassDeclaration(int pos, Set<String> modifiers, Kind kind, String name, @Nullable TypeName superclass,
				List<TypeName> interfaces, List<TypeName> permits, List<Parameter> components,
				List<EnumConstant> constants, List<Field> fields, List<Method> methods, List<Initializer> initializers,
				List<ClassDeclaration> nested, boolean implicit) {
			this(pos, modifiers, kind, name, superclass, interfaces, permits, components, constants, fields, methods,
					initializers, nested, implicit, List.of());
		}

		boolean isInterface() {
			return this.kind == Kind.INTERFACE;
		}

	}

	/**
	 * An import.
	 *
	 * @param pos the {@code import}
	 * @param name the type's qualified name, a package's for an import on demand, a
	 * module's for a module import; for a static import the member's, or the type's on
	 * demand
	 * @param onDemand whether it ends in {@code .*}
	 * @param module whether it is {@code import module}
	 * @param isStatic whether it is {@code import static}
	 */
	record Import(int pos, String name, boolean onDemand, boolean module, boolean isStatic) {
	}

	/**
	 * A file.
	 *
	 * @param packageName its package, or {@code null}
	 * @param imports its imports
	 * @param classes its top-level classes
	 * @param unchecked where the unchecked warnings are suppressed: the declarations
	 * annotated {@code @SuppressWarnings("unchecked")}
	 */
	record CompilationUnit(@Nullable String packageName, List<Import> imports, List<ClassDeclaration> classes,
			List<Span> unchecked) {
	}

	/**
	 * A stretch of a file's text.
	 *
	 * @param from where it starts
	 * @param to where it ends, exclusive
	 */
	record Span(int from, int to) {

		boolean contains(int pos) {
			return pos >= this.from && pos < this.to;
		}

	}

}
