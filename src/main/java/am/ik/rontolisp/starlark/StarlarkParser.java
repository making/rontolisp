package am.ik.rontolisp.starlark;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import am.ik.rontolisp.starlark.StarlarkSyntax.Argument;
import am.ik.rontolisp.starlark.StarlarkSyntax.ArgumentKind;
import am.ik.rontolisp.starlark.StarlarkSyntax.Assignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.AugmentedAssignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.Binary;
import am.ik.rontolisp.starlark.StarlarkSyntax.BinaryOperator;
import am.ik.rontolisp.starlark.StarlarkSyntax.Break;
import am.ik.rontolisp.starlark.StarlarkSyntax.BytesLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Call;
import am.ik.rontolisp.starlark.StarlarkSyntax.Clause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Conditional;
import am.ik.rontolisp.starlark.StarlarkSyntax.Continue;
import am.ik.rontolisp.starlark.StarlarkSyntax.Def;
import am.ik.rontolisp.starlark.StarlarkSyntax.DictComprehension;
import am.ik.rontolisp.starlark.StarlarkSyntax.DictLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Dot;
import am.ik.rontolisp.starlark.StarlarkSyntax.Entry;
import am.ik.rontolisp.starlark.StarlarkSyntax.Expression;
import am.ik.rontolisp.starlark.StarlarkSyntax.ExpressionStatement;
import am.ik.rontolisp.starlark.StarlarkSyntax.FloatLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.For;
import am.ik.rontolisp.starlark.StarlarkSyntax.ForClause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Identifier;
import am.ik.rontolisp.starlark.StarlarkSyntax.If;
import am.ik.rontolisp.starlark.StarlarkSyntax.IfClause;
import am.ik.rontolisp.starlark.StarlarkSyntax.Index;
import am.ik.rontolisp.starlark.StarlarkSyntax.IntLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Lambda;
import am.ik.rontolisp.starlark.StarlarkSyntax.ListComprehension;
import am.ik.rontolisp.starlark.StarlarkSyntax.ListLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Load;
import am.ik.rontolisp.starlark.StarlarkSyntax.LoadBinding;
import am.ik.rontolisp.starlark.StarlarkSyntax.Parameter;
import am.ik.rontolisp.starlark.StarlarkSyntax.ParameterKind;
import am.ik.rontolisp.starlark.StarlarkSyntax.Pass;
import am.ik.rontolisp.starlark.StarlarkSyntax.Return;
import am.ik.rontolisp.starlark.StarlarkSyntax.Slice;
import am.ik.rontolisp.starlark.StarlarkSyntax.Statement;
import am.ik.rontolisp.starlark.StarlarkSyntax.StringLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.TupleLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Unary;
import am.ik.rontolisp.starlark.StarlarkSyntax.UnaryOperator;
import am.ik.rontolisp.starlark.StarlarkToken.Kind;
import org.jspecify.annotations.Nullable;

/**
 * Starlark tokens to a {@link StarlarkSyntax} tree, by recursive descent over the spec's
 * "Grammar reference" with precedence climbing for the binary operators.
 *
 * <p>
 * Besides the grammar it rejects, each at its position, what Python's parser accepts and
 * Starlark does not: a chained comparison ({@code 0 <= i < n}), chained assignment,
 * {@code for}/{@code else}, a slice or a non-target as an assignment target, an
 * unparenthesized tuple with a trailing comma, arguments out of the order positional,
 * keyword, {@code *args}, {@code **kwargs}, and parameters out of the order required,
 * optional, {@code *args}, keyword-only, {@code **kwargs}. It also rejects {@code return}
 * outside a function, {@code break} and {@code continue} outside a loop, and {@code load}
 * inside a function. The reserved words and string prefixes Starlark lacks fail earlier,
 * in {@link StarlarkLexer}.
 */
final class StarlarkParser {

	/**
	 * Binary operator precedence, loosest first; {@code not} sits between and and the
	 * comparisons.
	 */
	private static final int OR_LEVEL = 0;

	private static final int NOT_LEVEL = 2;

	private static final int COMPARISON_LEVEL = 3;

	private static final int UNARY_LEVEL = 10;

	private final StarlarkLexer lexer;

	private final @Nullable String file;

	private StarlarkToken token;

	private @Nullable StarlarkToken lookahead;

	private int functionDepth;

	private int loopDepth;

	private StarlarkParser(String source, @Nullable String file) {
		this.lexer = new StarlarkLexer(source, file);
		this.file = file;
		this.token = this.lexer.next();
	}

	/**
	 * Parses a whole Starlark file.
	 * @param source the file's text
	 * @param file the file name for positions, or {@code null}
	 * @return the file's statements
	 * @throws LispReadException on the first lexical or syntax error, with its position
	 */
	static List<Statement> parse(String source, @Nullable String file) {
		StarlarkParser parser = new StarlarkParser(source, file);
		List<Statement> statements = new ArrayList<>();
		while (parser.token.kind() != Kind.EOF) {
			if (parser.token.kind() == Kind.NEWLINE) {
				parser.advance();
			}
			else {
				parser.statement(statements);
			}
		}
		return List.copyOf(statements);
	}

	// ---- statements ----

	private void statement(List<Statement> out) {
		switch (this.token.kind()) {
			case DEF -> out.add(this.def());
			case IF -> out.add(this.ifStatement());
			case FOR -> out.add(this.forStatement());
			case INDENT -> throw this.error("unexpected indent", this.token);
			default -> this.simpleStatement(out);
		}
	}

	private Def def() {
		SourceLocation at = this.at(this.advance());
		String name = this.identifier();
		this.expect(Kind.LEFT_PAREN);
		List<Parameter> parameters = this.parameters(Kind.RIGHT_PAREN);
		this.expect(Kind.RIGHT_PAREN);
		this.expect(Kind.COLON);
		int loops = this.loopDepth;
		this.functionDepth++;
		this.loopDepth = 0;
		List<Statement> body = this.suite();
		this.functionDepth--;
		this.loopDepth = loops;
		return new Def(name, parameters, body, at);
	}

	private If ifStatement() {
		SourceLocation at = this.at(this.advance());
		Expression condition = this.test();
		this.expect(Kind.COLON);
		List<Statement> then = this.suite();
		List<Statement> otherwise = List.of();
		if (this.token.kind() == Kind.ELIF) {
			otherwise = List.of(this.ifStatement());
		}
		else if (this.token.kind() == Kind.ELSE) {
			this.advance();
			this.expect(Kind.COLON);
			otherwise = this.suite();
		}
		return new If(condition, then, otherwise, at);
	}

	private For forStatement() {
		SourceLocation at = this.at(this.advance());
		Expression target = this.loopVariables();
		this.expect(Kind.IN);
		Expression iterable = this.expressions(false);
		this.expect(Kind.COLON);
		this.loopDepth++;
		List<Statement> body = this.suite();
		this.loopDepth--;
		if (this.token.kind() == Kind.ELSE) {
			throw this.error("for/else is not supported", this.token);
		}
		return new For(target, iterable, body, at);
	}

	private List<Statement> suite() {
		List<Statement> body = new ArrayList<>();
		if (this.token.kind() != Kind.NEWLINE) {
			this.simpleStatement(body);
			return List.copyOf(body);
		}
		this.advance();
		if (this.token.kind() != Kind.INDENT) {
			throw this.error("expected an indented block, " + this.got(), this.token);
		}
		this.advance();
		while (this.token.kind() != Kind.OUTDENT) {
			this.statement(body);
		}
		this.advance();
		return List.copyOf(body);
	}

	private void simpleStatement(List<Statement> out) {
		while (true) {
			out.add(this.smallStatement());
			if (this.token.kind() != Kind.SEMICOLON) {
				break;
			}
			this.advance();
			if (this.token.kind() == Kind.NEWLINE) {
				break;
			}
		}
		this.expect(Kind.NEWLINE);
	}

	private Statement smallStatement() {
		StarlarkToken first = this.token;
		SourceLocation at = this.at(first);
		switch (first.kind()) {
			case RETURN -> {
				if (this.functionDepth == 0) {
					throw this.error("return statement not within a function", first);
				}
				this.advance();
				boolean bare = this.token.kind() == Kind.NEWLINE || this.token.kind() == Kind.SEMICOLON;
				return new Return(bare ? null : this.expressions(false), at);
			}
			case BREAK, CONTINUE -> {
				if (this.loopDepth == 0) {
					throw this.error(first.kind().describe() + " not in a loop", first);
				}
				this.advance();
				return first.kind() == Kind.BREAK ? new Break(at) : new Continue(at);
			}
			case PASS -> {
				this.advance();
				return new Pass(at);
			}
			case LOAD -> {
				if (this.functionDepth > 0) {
					throw this.error("load statement within a function", first);
				}
				return this.load();
			}
			default -> {
				return this.assignmentOrExpression(at);
			}
		}
	}

	private Statement assignmentOrExpression(SourceLocation at) {
		Expression left = this.expressions(false);
		Kind kind = this.token.kind();
		if (kind == Kind.EQUALS) {
			this.checkTarget(left, false);
			this.advance();
			Expression value = this.expressions(false);
			this.rejectChainedAssignment();
			return new Assignment(left, value, at);
		}
		BinaryOperator augmented = augmentedOperator(kind);
		if (augmented != null) {
			this.checkTarget(left, true);
			this.advance();
			Expression value = this.expressions(false);
			this.rejectChainedAssignment();
			return new AugmentedAssignment(left, augmented, value, at);
		}
		return new ExpressionStatement(left, at);
	}

	private void rejectChainedAssignment() {
		if (this.token.kind() == Kind.EQUALS || augmentedOperator(this.token.kind()) != null) {
			throw this.error("chained assignment is not supported", this.token);
		}
	}

	private static @Nullable BinaryOperator augmentedOperator(Kind kind) {
		return switch (kind) {
			case PLUS_EQUALS -> BinaryOperator.PLUS;
			case MINUS_EQUALS -> BinaryOperator.MINUS;
			case STAR_EQUALS -> BinaryOperator.MULTIPLY;
			case SLASH_EQUALS -> BinaryOperator.DIVIDE;
			case SLASH_SLASH_EQUALS -> BinaryOperator.FLOOR_DIVIDE;
			case PERCENT_EQUALS -> BinaryOperator.MODULO;
			case AMPERSAND_EQUALS -> BinaryOperator.BIT_AND;
			case PIPE_EQUALS -> BinaryOperator.BIT_OR;
			case CARET_EQUALS -> BinaryOperator.BIT_XOR;
			case LESS_LESS_EQUALS -> BinaryOperator.SHIFT_LEFT;
			case GREATER_GREATER_EQUALS -> BinaryOperator.SHIFT_RIGHT;
			default -> null;
		};
	}

	/**
	 * An assignment target: a name, an index or a dot expression, or (not for an
	 * augmented assignment) a tuple or list of targets.
	 */
	private void checkTarget(Expression target, boolean augmented) {
		switch (target) {
			case Identifier _, Index _, Dot _ -> {
			}
			case TupleLiteral tuple when !augmented -> tuple.elements().forEach(e -> this.checkTarget(e, false));
			case ListLiteral list when !augmented -> list.elements().forEach(e -> this.checkTarget(e, false));
			default -> throw new LispReadException(
					"cannot " + (augmented ? "augment-assign to " : "assign to ") + describe(target), target.at());
		}
	}

	private static String describe(Expression expression) {
		return switch (expression) {
			case Slice _ -> "a slice";
			case Call _ -> "a function call";
			case TupleLiteral _ -> "a tuple";
			case ListLiteral _ -> "a list";
			default -> "this expression";
		};
	}

	private Load load() {
		SourceLocation at = this.at(this.advance());
		this.expect(Kind.LEFT_PAREN);
		String module = this.stringLiteral();
		List<LoadBinding> bindings = new ArrayList<>();
		while (this.token.kind() == Kind.COMMA) {
			this.advance();
			if (this.token.kind() == Kind.RIGHT_PAREN) {
				break;
			}
			SourceLocation bindingAt = this.at(this.token);
			if (this.token.kind() == Kind.IDENTIFIER) {
				String local = this.identifier();
				this.expect(Kind.EQUALS);
				bindings.add(new LoadBinding(local, this.stringLiteral(), bindingAt));
			}
			else {
				String name = this.stringLiteral();
				bindings.add(new LoadBinding(name, name, bindingAt));
			}
		}
		this.expect(Kind.RIGHT_PAREN);
		if (bindings.isEmpty()) {
			throw new LispReadException("load statement must import at least 1 symbol", at);
		}
		return new Load(module, List.copyOf(bindings), at);
	}

	// ---- parameters and arguments ----

	private List<Parameter> parameters(Kind close) {
		List<Parameter> parameters = new ArrayList<>();
		while (this.token.kind() != close) {
			SourceLocation at = this.at(this.token);
			if (this.token.kind() == Kind.STAR) {
				this.advance();
				String name = this.token.kind() == Kind.IDENTIFIER ? this.identifier() : null;
				parameters.add(new Parameter(ParameterKind.STAR, name, null, at));
			}
			else if (this.token.kind() == Kind.STAR_STAR) {
				this.advance();
				parameters.add(new Parameter(ParameterKind.STAR_STAR, this.identifier(), null, at));
			}
			else {
				String name = this.identifier();
				if (this.token.kind() == Kind.EQUALS) {
					this.advance();
					parameters.add(new Parameter(ParameterKind.OPTIONAL, name, this.test(), at));
				}
				else {
					parameters.add(new Parameter(ParameterKind.REQUIRED, name, null, at));
				}
			}
			if (this.token.kind() != Kind.COMMA) {
				break;
			}
			this.advance();
		}
		checkParameterOrder(parameters);
		return List.copyOf(parameters);
	}

	/** Required, optional, {@code *args} or {@code *}, keyword-only, {@code **kwargs}. */
	private static void checkParameterOrder(List<Parameter> parameters) {
		Set<String> names = new HashSet<>();
		boolean optional = false;
		@Nullable Parameter star = null;
		boolean starStar = false;
		for (Parameter parameter : parameters) {
			if (starStar) {
				throw new LispReadException("parameter may not follow **kwargs", parameter.at());
			}
			if (parameter.name() != null && !names.add(parameter.name())) {
				throw new LispReadException("duplicate parameter: " + parameter.name(), parameter.at());
			}
			switch (parameter.kind()) {
				case REQUIRED -> {
					if (optional && star == null) {
						throw new LispReadException("required parameter may not follow optional", parameter.at());
					}
				}
				case OPTIONAL -> optional = true;
				case STAR -> {
					if (star != null) {
						throw new LispReadException("multiple * parameters not allowed", parameter.at());
					}
					star = parameter;
				}
				case STAR_STAR -> {
					checkBareStar(star, parameters.indexOf(parameter), parameters);
					starStar = true;
				}
			}
		}
		checkBareStar(star, parameters.size(), parameters);
	}

	/** A bare {@code *} must be followed by at least one keyword-only parameter. */
	private static void checkBareStar(@Nullable Parameter star, int end, List<Parameter> parameters) {
		if (star != null && star.name() == null && parameters.indexOf(star) == end - 1) {
			throw new LispReadException("bare * must be followed by keyword-only parameters", star.at());
		}
	}

	private List<Argument> arguments() {
		List<Argument> arguments = new ArrayList<>();
		while (this.token.kind() != Kind.RIGHT_PAREN) {
			SourceLocation at = this.at(this.token);
			if (this.token.kind() == Kind.STAR) {
				this.advance();
				arguments.add(new Argument(ArgumentKind.STAR, null, this.test(), at));
			}
			else if (this.token.kind() == Kind.STAR_STAR) {
				this.advance();
				arguments.add(new Argument(ArgumentKind.STAR_STAR, null, this.test(), at));
			}
			else if (this.token.kind() == Kind.IDENTIFIER && this.peek().kind() == Kind.EQUALS) {
				String name = this.identifier();
				this.advance();
				arguments.add(new Argument(ArgumentKind.KEYWORD, name, this.test(), at));
			}
			else {
				arguments.add(new Argument(ArgumentKind.POSITIONAL, null, this.test(), at));
			}
			if (this.token.kind() != Kind.COMMA) {
				break;
			}
			this.advance();
		}
		checkArgumentOrder(arguments);
		return List.copyOf(arguments);
	}

	/** Positional, keyword, {@code *args}, {@code **kwargs}; each keyword once. */
	private static void checkArgumentOrder(List<Argument> arguments) {
		Set<String> keywords = new HashSet<>();
		ArgumentKind latest = ArgumentKind.POSITIONAL;
		for (Argument argument : arguments) {
			ArgumentKind kind = argument.kind();
			if (kind.ordinal() < latest.ordinal()
					|| (kind == latest && kind.ordinal() >= ArgumentKind.STAR.ordinal())) {
				throw new LispReadException(argumentName(kind) + " may not follow " + argumentName(latest),
						argument.at());
			}
			if (kind == ArgumentKind.KEYWORD && !keywords.add(argument.name())) {
				throw new LispReadException("keyword argument " + argument.name() + " repeated", argument.at());
			}
			latest = kind;
		}
	}

	private static String argumentName(ArgumentKind kind) {
		return switch (kind) {
			case POSITIONAL -> "positional argument";
			case KEYWORD -> "keyword argument";
			case STAR -> "*args";
			case STAR_STAR -> "**kwargs";
		};
	}

	// ---- expressions ----

	/**
	 * {@code Expressions}: one expression, or a tuple of them without parentheses.
	 * @param trailingComma whether a trailing comma is allowed (inside brackets)
	 */
	private Expression expressions(boolean trailingComma) {
		Expression first = this.test();
		if (this.token.kind() != Kind.COMMA) {
			return first;
		}
		List<Expression> elements = new ArrayList<>();
		elements.add(first);
		while (this.token.kind() == Kind.COMMA) {
			StarlarkToken comma = this.advance();
			if (terminatesExpressions(this.token.kind())) {
				if (!trailingComma) {
					throw this.error("unparenthesized tuple with trailing comma", comma);
				}
				break;
			}
			elements.add(this.test());
		}
		return new TupleLiteral(List.copyOf(elements), first.at());
	}

	private static boolean terminatesExpressions(Kind kind) {
		return switch (kind) {
			case RIGHT_PAREN, RIGHT_BRACKET, RIGHT_BRACE, NEWLINE, EOF, SEMICOLON, EQUALS, COLON -> true;
			default -> augmentedOperator(kind) != null;
		};
	}

	/**
	 * {@code Expression}: a lambda, or a binary expression with an optional conditional.
	 */
	private Expression test() {
		if (this.token.kind() == Kind.LAMBDA) {
			return this.lambda(true);
		}
		Expression then = this.binary(OR_LEVEL);
		if (this.token.kind() != Kind.IF) {
			return then;
		}
		SourceLocation at = this.at(this.advance());
		Expression condition = this.binary(OR_LEVEL);
		if (this.token.kind() != Kind.ELSE) {
			throw this.error("conditional expression without else clause", this.token);
		}
		this.advance();
		return new Conditional(condition, then, this.test(), at);
	}

	/** What follows {@code if} in a comprehension: no conditional expression. */
	private Expression testNoConditional() {
		return this.token.kind() == Kind.LAMBDA ? this.lambda(false) : this.binary(OR_LEVEL);
	}

	private Lambda lambda(boolean conditionalBody) {
		SourceLocation at = this.at(this.advance());
		List<Parameter> parameters = this.parameters(Kind.COLON);
		this.expect(Kind.COLON);
		int loops = this.loopDepth;
		this.loopDepth = 0;
		Expression body = conditionalBody ? this.test() : this.testNoConditional();
		this.loopDepth = loops;
		return new Lambda(parameters, body, at);
	}

	/**
	 * The binary operators at {@code level} and tighter, by precedence climbing.
	 * Comparisons do not associate: a second one at the same level is an error.
	 */
	private Expression binary(int level) {
		if (level == UNARY_LEVEL) {
			return this.unary();
		}
		if (level == NOT_LEVEL && this.token.kind() == Kind.NOT) {
			SourceLocation at = this.at(this.advance());
			return new Unary(UnaryOperator.NOT, this.binary(NOT_LEVEL), at);
		}
		Expression left = this.binary(level + 1);
		@Nullable BinaryOperator previous = null;
		while (true) {
			StarlarkToken operatorToken = this.token;
			BinaryOperator operator = this.binaryOperator();
			int precedence = operator == null ? -1 : precedence(operator);
			if (operator == null || precedence < level) {
				return left;
			}
			if (precedence == COMPARISON_LEVEL && previous != null && precedence(previous) == COMPARISON_LEVEL) {
				throw this.error("'" + operator.spelling + "' does not associate with '" + previous.spelling
						+ "' (use parentheses)", operatorToken);
			}
			this.advance();
			if (operator == BinaryOperator.NOT_IN) {
				this.advance();
			}
			Expression right = this.binary(precedence + 1);
			left = new Binary(operator, left, right, this.at(operatorToken));
			previous = operator;
		}
	}

	/** The binary operator at the current token, without consuming it. */
	private @Nullable BinaryOperator binaryOperator() {
		return switch (this.token.kind()) {
			case OR -> BinaryOperator.OR;
			case AND -> BinaryOperator.AND;
			case EQUALS_EQUALS -> BinaryOperator.EQUALS;
			case NOT_EQUALS -> BinaryOperator.NOT_EQUALS;
			case LESS -> BinaryOperator.LESS;
			case GREATER -> BinaryOperator.GREATER;
			case LESS_EQUALS -> BinaryOperator.LESS_EQUALS;
			case GREATER_EQUALS -> BinaryOperator.GREATER_EQUALS;
			case IN -> BinaryOperator.IN;
			case NOT -> {
				if (this.peek().kind() != Kind.IN) {
					throw this.error("got " + this.peek().kind().describe() + ", want 'in'", this.peek());
				}
				yield BinaryOperator.NOT_IN;
			}
			case PIPE -> BinaryOperator.BIT_OR;
			case CARET -> BinaryOperator.BIT_XOR;
			case AMPERSAND -> BinaryOperator.BIT_AND;
			case LESS_LESS -> BinaryOperator.SHIFT_LEFT;
			case GREATER_GREATER -> BinaryOperator.SHIFT_RIGHT;
			case MINUS -> BinaryOperator.MINUS;
			case PLUS -> BinaryOperator.PLUS;
			case STAR -> BinaryOperator.MULTIPLY;
			case SLASH -> BinaryOperator.DIVIDE;
			case SLASH_SLASH -> BinaryOperator.FLOOR_DIVIDE;
			case PERCENT -> BinaryOperator.MODULO;
			default -> null;
		};
	}

	private static int precedence(BinaryOperator operator) {
		return switch (operator) {
			case OR -> 0;
			case AND -> 1;
			case EQUALS, NOT_EQUALS, LESS, GREATER, LESS_EQUALS, GREATER_EQUALS, IN, NOT_IN -> COMPARISON_LEVEL;
			case BIT_OR -> 4;
			case BIT_XOR -> 5;
			case BIT_AND -> 6;
			case SHIFT_LEFT, SHIFT_RIGHT -> 7;
			case MINUS, PLUS -> 8;
			case MULTIPLY, DIVIDE, FLOOR_DIVIDE, MODULO -> 9;
		};
	}

	private Expression unary() {
		UnaryOperator operator = switch (this.token.kind()) {
			case PLUS -> UnaryOperator.PLUS;
			case MINUS -> UnaryOperator.MINUS;
			case TILDE -> UnaryOperator.INVERT;
			default -> null;
		};
		if (operator == null) {
			return this.primary();
		}
		SourceLocation at = this.at(this.advance());
		return new Unary(operator, this.unary(), at);
	}

	private Expression primary() {
		Expression expression = this.operand();
		while (true) {
			switch (this.token.kind()) {
				case DOT -> {
					this.advance();
					SourceLocation at = this.at(this.token);
					expression = new Dot(expression, this.identifier(), at);
				}
				case LEFT_PAREN -> {
					SourceLocation at = this.at(this.advance());
					List<Argument> arguments = this.arguments();
					this.expect(Kind.RIGHT_PAREN);
					expression = new Call(expression, arguments, at);
				}
				case LEFT_BRACKET -> expression = this.subscript(expression);
				default -> {
					return expression;
				}
			}
		}
	}

	private Expression subscript(Expression receiver) {
		SourceLocation at = this.at(this.advance());
		Expression start = null;
		if (this.token.kind() != Kind.COLON) {
			Expression index = this.expressions(true);
			if (this.token.kind() == Kind.RIGHT_BRACKET) {
				this.advance();
				return new Index(receiver, index, at);
			}
			start = index;
		}
		this.expect(Kind.COLON);
		Expression stop = this.token.kind() == Kind.COLON || this.token.kind() == Kind.RIGHT_BRACKET ? null
				: this.test();
		Expression step = null;
		if (this.token.kind() == Kind.COLON) {
			this.advance();
			step = this.token.kind() == Kind.RIGHT_BRACKET ? null : this.test();
		}
		this.expect(Kind.RIGHT_BRACKET);
		return new Slice(receiver, start, stop, step, at);
	}

	private Expression operand() {
		StarlarkToken first = this.token;
		SourceLocation at = this.at(first);
		switch (first.kind()) {
			case IDENTIFIER -> {
				return new Identifier(this.identifier(), at);
			}
			case INT -> {
				this.advance();
				return new IntLiteral((BigInteger) value(first), at);
			}
			case FLOAT -> {
				this.advance();
				return new FloatLiteral((Double) value(first), at);
			}
			case STRING -> {
				this.advance();
				return new StringLiteral((String) value(first), at);
			}
			case BYTES -> {
				this.advance();
				return new BytesLiteral((byte[]) value(first), at);
			}
			case LEFT_PAREN -> {
				this.advance();
				if (this.token.kind() == Kind.RIGHT_PAREN) {
					this.advance();
					return new TupleLiteral(List.of(), at);
				}
				Expression inner = this.expressions(true);
				this.expect(Kind.RIGHT_PAREN);
				return inner instanceof TupleLiteral tuple ? new TupleLiteral(tuple.elements(), at) : inner;
			}
			case LEFT_BRACKET -> {
				return this.listOrComprehension(at);
			}
			case LEFT_BRACE -> {
				return this.dictOrComprehension(at);
			}
			default -> throw this.error(this.got() + ", want primary expression", first);
		}
	}

	private Expression listOrComprehension(SourceLocation at) {
		this.advance();
		List<Expression> elements = new ArrayList<>();
		if (this.token.kind() != Kind.RIGHT_BRACKET) {
			Expression first = this.test();
			if (this.token.kind() == Kind.FOR) {
				List<Clause> clauses = this.clauses();
				this.expect(Kind.RIGHT_BRACKET);
				return new ListComprehension(first, clauses, at);
			}
			elements.add(first);
			while (this.token.kind() == Kind.COMMA) {
				this.advance();
				if (this.token.kind() == Kind.RIGHT_BRACKET) {
					break;
				}
				elements.add(this.test());
			}
		}
		this.expect(Kind.RIGHT_BRACKET);
		return new ListLiteral(List.copyOf(elements), at);
	}

	private Expression dictOrComprehension(SourceLocation at) {
		this.advance();
		List<Entry> entries = new ArrayList<>();
		if (this.token.kind() != Kind.RIGHT_BRACE) {
			Entry first = this.entry();
			if (this.token.kind() == Kind.FOR) {
				List<Clause> clauses = this.clauses();
				this.expect(Kind.RIGHT_BRACE);
				return new DictComprehension(first, clauses, at);
			}
			entries.add(first);
			while (this.token.kind() == Kind.COMMA) {
				this.advance();
				if (this.token.kind() == Kind.RIGHT_BRACE) {
					break;
				}
				entries.add(this.entry());
			}
		}
		this.expect(Kind.RIGHT_BRACE);
		return new DictLiteral(List.copyOf(entries), at);
	}

	private Entry entry() {
		Expression key = this.test();
		SourceLocation at = key.at();
		this.expect(Kind.COLON);
		return new Entry(key, this.test(), at);
	}

	/**
	 * The {@code for} and {@code if} clauses of a comprehension. A {@code for} clause's
	 * operand is a binary expression: neither an unparenthesized tuple nor a lambda nor a
	 * conditional (whose {@code if} would be the next clause).
	 */
	private List<Clause> clauses() {
		List<Clause> clauses = new ArrayList<>();
		while (true) {
			SourceLocation at = this.at(this.token);
			if (this.token.kind() == Kind.FOR) {
				this.advance();
				Expression target = this.loopVariables();
				this.expect(Kind.IN);
				clauses.add(new ForClause(target, this.binary(OR_LEVEL), at));
			}
			else if (this.token.kind() == Kind.IF) {
				this.advance();
				clauses.add(new IfClause(this.testNoConditional(), at));
			}
			else {
				return List.copyOf(clauses);
			}
		}
	}

	/** {@code LoopVariables}: primary expressions, a tuple when there is a comma. */
	private Expression loopVariables() {
		Expression first = this.primary();
		Expression target = first;
		if (this.token.kind() == Kind.COMMA) {
			List<Expression> elements = new ArrayList<>();
			elements.add(first);
			while (this.token.kind() == Kind.COMMA) {
				this.advance();
				if (this.token.kind() == Kind.IN) {
					break;
				}
				elements.add(this.primary());
			}
			target = new TupleLiteral(List.copyOf(elements), first.at());
		}
		this.checkTarget(target, false);
		return target;
	}

	// ---- tokens ----

	private StarlarkToken advance() {
		StarlarkToken current = this.token;
		if (this.lookahead != null) {
			this.token = this.lookahead;
			this.lookahead = null;
		}
		else {
			this.token = this.lexer.next();
		}
		return current;
	}

	private StarlarkToken peek() {
		StarlarkToken next = this.lookahead;
		if (next == null) {
			next = this.lexer.next();
			this.lookahead = next;
		}
		return next;
	}

	private StarlarkToken expect(Kind kind) {
		if (this.token.kind() != kind) {
			throw this.error(this.got() + ", want " + kind.describe(), this.token);
		}
		return this.advance();
	}

	private String identifier() {
		return (String) value(this.expect(Kind.IDENTIFIER));
	}

	private String stringLiteral() {
		return (String) value(this.expect(Kind.STRING));
	}

	private static Object value(StarlarkToken token) {
		Object value = token.value();
		if (value == null) {
			throw new IllegalStateException("token without a value: " + token);
		}
		return value;
	}

	private String got() {
		return "got " + this.token.kind().describe();
	}

	private SourceLocation at(StarlarkToken token) {
		return new SourceLocation(this.file, token.line(), token.column());
	}

	private LispReadException error(String message, StarlarkToken token) {
		return new LispReadException(message, this.at(token), token.kind() == Kind.EOF);
	}

}
