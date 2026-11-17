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
	 * @param name its name, dotted when qualified; {@code var} for an inferred local
	 * @param arguments its type arguments
	 * @param diamond whether it is written with an empty {@code <>}
	 * @param dimensions how many {@code []} follow it
	 */
	record TypeName(int pos, String name, List<TypeName> arguments, boolean diamond, int dimensions) {

		TypeName element() {
			return new TypeName(this.pos, this.name, this.arguments, this.diamond, this.dimensions - 1);
		}

	}

	sealed interface Expression {

		int pos();

	}

	record IntLiteral(int pos, long value, boolean isLong) implements Expression {
	}

	record DoubleLiteral(int pos, double value) implements Expression {
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

	record This(int pos) implements Expression {
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
	 */
	record Call(int pos, @Nullable Expression target, String name, List<Expression> arguments,
			boolean onSuper) implements Expression {
	}

	/**
	 * {@code this(..)} or {@code super(..)}.
	 *
	 * @param pos the keyword
	 * @param onSuper whether it is {@code super(..)}
	 * @param arguments the arguments
	 */
	record ConstructorCall(int pos, boolean onSuper, List<Expression> arguments) implements Expression {
	}

	record New(int pos, TypeName type, List<Expression> arguments) implements Expression {
	}

	/**
	 * An array creation.
	 *
	 * @param pos the {@code new}
	 * @param type the array's type
	 * @param length the length, or {@code null} with an initializer
	 * @param initializer the initializer, or {@code null} with a length
	 */
	record NewArray(int pos, TypeName type, @Nullable Expression length,
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

	record Cast(int pos, TypeName type, Expression operand) implements Expression {
	}

	record InstanceOf(int pos, Expression operand, TypeName type) implements Expression {
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

	record LocalVariable(int pos, TypeName type, List<Declarator> declarators) implements Statement {
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

	record Break(int pos) implements Statement {
	}

	record Continue(int pos) implements Statement {
	}

	record Block(int pos, List<Statement> statements) implements Statement {
	}

	record Throw(int pos, Expression value) implements Statement {
	}

	record Catch(int pos, TypeName type, String name, Block body) {
	}

	record Try(int pos, Block body, List<Catch> catches, @Nullable Block finallyBlock) implements Statement {
	}

	record Empty(int pos) implements Statement {
	}

	record Parameter(int pos, @Nullable TypeName type, String name) {
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
	 */
	record Method(int pos, Set<String> modifiers, @Nullable TypeName returnType, String name,
			List<Parameter> parameters, @Nullable Block body) {
	}

	/**
	 * A class or an interface.
	 *
	 * @param pos its name
	 * @param modifiers its modifiers
	 * @param isInterface whether it is an interface
	 * @param name its name
	 * @param superclass what it extends, or {@code null}
	 * @param interfaces what a class implements or an interface extends
	 * @param fields its fields, in order
	 * @param methods its methods and constructors, in order
	 * @param implicit whether it is the class of a compact source file
	 */
	record ClassDeclaration(int pos, Set<String> modifiers, boolean isInterface, String name,
			@Nullable TypeName superclass, List<TypeName> interfaces, List<Field> fields, List<Method> methods,
			boolean implicit) {
	}

	/**
	 * An import.
	 *
	 * @param pos the {@code import}
	 * @param name the type's qualified name, a package's for an import on demand, a
	 * module's for a module import
	 * @param onDemand whether it ends in {@code .*}
	 * @param module whether it is {@code import module}
	 */
	record Import(int pos, String name, boolean onDemand, boolean module) {
	}

	record CompilationUnit(@Nullable String packageName, List<Import> imports, List<ClassDeclaration> classes) {
	}

}
