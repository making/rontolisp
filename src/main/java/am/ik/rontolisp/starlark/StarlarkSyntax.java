package am.ik.rontolisp.starlark;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import am.ik.rontolisp.SourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * The Starlark syntax tree {@link StarlarkParser} builds, one record per production of
 * the spec's "Grammar reference". Every node carries the {@link SourceLocation} it was
 * read from: the operator of a unary or binary expression, the {@code if} of a
 * conditional, the opening bracket of a call, subscript, slice or collection, the field
 * name of a dot expression, the first token of everything else.
 *
 * <p>
 * Parentheses leave no node: {@code (x)} is {@code x}. An {@code elif} is an {@link If}
 * alone in the enclosing {@code else} branch. {@code True}, {@code False} and
 * {@code None} are identifiers, as in the spec.
 */
final class StarlarkSyntax {

	private StarlarkSyntax() {
	}

	/** Any node: it knows where it was read from. */
	sealed interface Node permits Expression, Statement, Parameter, Argument, Entry, Clause, LoadBinding {

		SourceLocation at();

	}

	/** An expression. */
	sealed interface Expression extends Node permits Identifier, IntLiteral, FloatLiteral, StringLiteral, BytesLiteral,
			Unary, Binary, Conditional, Lambda, ListLiteral, TupleLiteral, DictLiteral, ListComprehension,
			DictComprehension, Call, Dot, Index, Slice {

	}

	/** A statement. */
	sealed interface Statement extends Node permits ExpressionStatement, Assignment, AugmentedAssignment, Def, If, For,
			Return, Break, Continue, Pass, Load {

	}

	record Identifier(String name, SourceLocation at) implements Expression {
	}

	record IntLiteral(BigInteger value, SourceLocation at) implements Expression {
	}

	record FloatLiteral(double value, SourceLocation at) implements Expression {
	}

	record StringLiteral(String value, SourceLocation at) implements Expression {
	}

	/**
	 * A bytes literal. The array is the node's own: the parser hands it over and nothing
	 * writes it, so equality compares contents.
	 *
	 * @param value the bytes
	 * @param at where it was read
	 */
	record BytesLiteral(byte[] value, SourceLocation at) implements Expression {

		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof BytesLiteral that && Arrays.equals(this.value, that.value)
					&& this.at.equals(that.at);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(this.value) + this.at.hashCode();
		}

		@Override
		public String toString() {
			return "BytesLiteral[value=" + Arrays.toString(this.value) + ", at=" + this.at + "]";
		}

	}

	enum UnaryOperator {

		PLUS("+"), MINUS("-"), INVERT("~"), NOT("not");

		final String spelling;

		UnaryOperator(String spelling) {
			this.spelling = spelling;
		}

	}

	record Unary(UnaryOperator operator, Expression operand, SourceLocation at) implements Expression {
	}

	/** The binary operators, also the operator of an augmented assignment. */
	enum BinaryOperator {

		OR("or"), AND("and"), EQUALS("=="), NOT_EQUALS("!="), LESS("<"), GREATER(">"), LESS_EQUALS("<="),
		GREATER_EQUALS(">="), IN("in"), NOT_IN("not in"), BIT_OR("|"), BIT_XOR("^"), BIT_AND("&"), SHIFT_LEFT("<<"),
		SHIFT_RIGHT(">>"), MINUS("-"), PLUS("+"), MULTIPLY("*"), DIVIDE("/"), FLOOR_DIVIDE("//"), MODULO("%");

		final String spelling;

		BinaryOperator(String spelling) {
			this.spelling = spelling;
		}

	}

	record Binary(BinaryOperator operator, Expression left, Expression right, SourceLocation at) implements Expression {
	}

	/** {@code then if condition else otherwise}. */
	record Conditional(Expression condition, Expression then, Expression otherwise,
			SourceLocation at) implements Expression {
	}

	record Lambda(List<Parameter> parameters, Expression body, SourceLocation at) implements Expression {
	}

	record ListLiteral(List<Expression> elements, SourceLocation at) implements Expression {
	}

	record TupleLiteral(List<Expression> elements, SourceLocation at) implements Expression {
	}

	record DictLiteral(List<Entry> entries, SourceLocation at) implements Expression {
	}

	/** {@code key: value} of a dict literal or comprehension. */
	record Entry(Expression key, Expression value, SourceLocation at) implements Node {
	}

	/** {@code [element for ... if ...]}; the first clause is a {@link ForClause}. */
	record ListComprehension(Expression element, List<Clause> clauses, SourceLocation at) implements Expression {
	}

	/** {@code {key: value for ... if ...}}; the first clause is a {@link ForClause}. */
	record DictComprehension(Entry entry, List<Clause> clauses, SourceLocation at) implements Expression {
	}

	/** A clause of a comprehension. */
	sealed interface Clause extends Node permits ForClause, IfClause {

	}

	record ForClause(Expression target, Expression iterable, SourceLocation at) implements Clause {
	}

	record IfClause(Expression condition, SourceLocation at) implements Clause {
	}

	record Call(Expression function, List<Argument> arguments, SourceLocation at) implements Expression {
	}

	enum ArgumentKind {

		POSITIONAL, KEYWORD, STAR, STAR_STAR

	}

	/**
	 * One argument of a call, in source order: {@code value}, {@code name=value},
	 * {@code *value} or {@code **value}.
	 *
	 * @param kind the form
	 * @param name the keyword of a {@link ArgumentKind#KEYWORD} argument, else
	 * {@code null}
	 * @param value the argument expression
	 * @param at where it starts
	 */
	record Argument(ArgumentKind kind, @Nullable String name, Expression value, SourceLocation at) implements Node {
	}

	record Dot(Expression receiver, String name, SourceLocation at) implements Expression {
	}

	record Index(Expression receiver, Expression index, SourceLocation at) implements Expression {
	}

	/** {@code receiver[start:stop:step]}; an omitted operand is {@code null}. */
	record Slice(Expression receiver, @Nullable Expression start, @Nullable Expression stop, @Nullable Expression step,
			SourceLocation at) implements Expression {
	}

	enum ParameterKind {

		/** {@code name}. */
		REQUIRED,
		/** {@code name=default}. */
		OPTIONAL,
		/** {@code *name}, or the bare {@code *} with no name. */
		STAR,
		/** {@code **name}. */
		STAR_STAR

	}

	/**
	 * One parameter of a {@code def} or {@code lambda}, in source order.
	 *
	 * @param kind the form
	 * @param name the parameter's name; {@code null} only for the bare {@code *}
	 * @param defaultValue the default of an {@link ParameterKind#OPTIONAL} parameter
	 * @param at where it starts
	 */
	record Parameter(ParameterKind kind, @Nullable String name, @Nullable Expression defaultValue,
			SourceLocation at) implements Node {
	}

	record ExpressionStatement(Expression expression, SourceLocation at) implements Statement {
	}

	/**
	 * {@code target = value}; the target is a name, index, dot, or a tuple or list of
	 * targets.
	 */
	record Assignment(Expression target, Expression value, SourceLocation at) implements Statement {
	}

	/** {@code target op= value}; the target is a name, index or dot. */
	record AugmentedAssignment(Expression target, BinaryOperator operator, Expression value,
			SourceLocation at) implements Statement {
	}

	record Def(String name, List<Parameter> parameters, List<Statement> body, SourceLocation at) implements Statement {
	}

	record If(Expression condition, List<Statement> then, List<Statement> otherwise,
			SourceLocation at) implements Statement {
	}

	record For(Expression target, Expression iterable, List<Statement> body, SourceLocation at) implements Statement {
	}

	record Return(@Nullable Expression value, SourceLocation at) implements Statement {
	}

	record Break(SourceLocation at) implements Statement {
	}

	record Continue(SourceLocation at) implements Statement {
	}

	record Pass(SourceLocation at) implements Statement {
	}

	/** {@code load(module, ...)}: at least one binding. */
	record Load(String module, List<LoadBinding> bindings, SourceLocation at) implements Statement {
	}

	/** {@code local="remote"}, or {@code "name"} binding the name to itself. */
	record LoadBinding(String local, String remote, SourceLocation at) implements Node {
	}

}
