package am.ik.rontolisp.starlark;

import java.util.List;
import java.util.stream.Collectors;

import am.ik.rontolisp.starlark.StarlarkSyntax.Argument;
import am.ik.rontolisp.starlark.StarlarkSyntax.Assignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.AugmentedAssignment;
import am.ik.rontolisp.starlark.StarlarkSyntax.Binary;
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
import am.ik.rontolisp.starlark.StarlarkSyntax.Parameter;
import am.ik.rontolisp.starlark.StarlarkSyntax.Pass;
import am.ik.rontolisp.starlark.StarlarkSyntax.Return;
import am.ik.rontolisp.starlark.StarlarkSyntax.Slice;
import am.ik.rontolisp.starlark.StarlarkSyntax.Statement;
import am.ik.rontolisp.starlark.StarlarkSyntax.StringLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.TupleLiteral;
import am.ik.rontolisp.starlark.StarlarkSyntax.Unary;
import org.jspecify.annotations.Nullable;

/**
 * A {@link StarlarkSyntax} tree as S-expression text, for pinning trees in tests. The
 * same text is what {@code astdump.py} in the prototype's directory prints from Python's
 * {@code ast} for the same source, which is how the parser was compared with Python's
 * over the conformance corpus ({@code .kb/starlark-frontend.md}). With {@code positions},
 * every name and literal carries {@code @line:column}.
 */
final class StarlarkTrees {

	private final boolean positions;

	private StarlarkTrees(boolean positions) {
		this.positions = positions;
	}

	static String dump(List<Statement> statements) {
		return dump(statements, false);
	}

	static String dump(List<Statement> statements, boolean positions) {
		StarlarkTrees trees = new StarlarkTrees(positions);
		return statements.stream().map(trees::statement).collect(Collectors.joining("\n"));
	}

	private String statements(List<Statement> statements) {
		return statements.stream().map(this::statement).collect(Collectors.joining(" "));
	}

	private String statement(Statement statement) {
		return switch (statement) {
			case ExpressionStatement s -> "(expr " + this.expression(s.expression()) + ")";
			case Assignment s -> "(= " + this.expression(s.target()) + " " + this.expression(s.value()) + ")";
			case AugmentedAssignment s -> "(" + s.operator().spelling + "= " + this.expression(s.target()) + " "
					+ this.expression(s.value()) + ")";
			case Def s -> "(def " + s.name() + " " + this.parameters(s.parameters()) + this.body(s.body()) + ")";
			case If s -> "(if " + this.expression(s.condition()) + " (" + this.statements(s.then()) + ") ("
					+ this.statements(s.otherwise()) + "))";
			case For s ->
				"(for " + this.expression(s.target()) + " " + this.expression(s.iterable()) + this.body(s.body()) + ")";
			case Return s -> s.value() == null ? "(return)" : "(return " + this.expression(s.value()) + ")";
			case Break _ -> "(break)";
			case Continue _ -> "(continue)";
			case Pass _ -> "(pass)";
			case Load s -> "(load " + quote(s.module())
					+ s.bindings()
						.stream()
						.map(b -> " (" + b.local() + " " + quote(b.remote()) + ")")
						.collect(Collectors.joining())
					+ ")";
		};
	}

	private String body(List<Statement> body) {
		return body.isEmpty() ? "" : " " + this.statements(body);
	}

	private String parameters(List<Parameter> parameters) {
		return parameters.stream().map(p -> switch (p.kind()) {
			case REQUIRED -> p.name();
			case OPTIONAL -> "(= " + p.name() + " " + this.expression(p.defaultValue()) + ")";
			case STAR -> p.name() == null ? "*" : "(* " + p.name() + ")";
			case STAR_STAR -> "(** " + p.name() + ")";
		}).collect(Collectors.joining(" ", "(", ")"));
	}

	private String expression(@Nullable Expression expression) {
		if (expression == null) {
			return "_";
		}
		return switch (expression) {
			case Identifier e -> this.leaf("name " + e.name(), e);
			case IntLiteral e -> this.leaf("int " + e.value(), e);
			case FloatLiteral e -> this.leaf("float " + e.value(), e);
			case StringLiteral e -> this.leaf("string " + quote(e.value()), e);
			case BytesLiteral e -> this.leaf("bytes " + quoteBytes(e.value()), e);
			case Unary e -> "(" + e.operator().spelling + " " + this.expression(e.operand()) + ")";
			case Binary e -> "(" + e.operator().spelling.replace(' ', '-') + " " + this.expression(e.left()) + " "
					+ this.expression(e.right()) + ")";
			case Conditional e -> "(if-expr " + this.expression(e.condition()) + " " + this.expression(e.then()) + " "
					+ this.expression(e.otherwise()) + ")";
			case Lambda e -> "(lambda " + this.parameters(e.parameters()) + " " + this.expression(e.body()) + ")";
			case ListLiteral e -> this.collection("list", e.elements());
			case TupleLiteral e -> this.collection("tuple", e.elements());
			case DictLiteral e ->
				"(dict" + e.entries().stream().map(x -> " " + this.entry(x)).collect(Collectors.joining()) + ")";
			case ListComprehension e -> "(list-comp " + this.expression(e.element()) + this.clauses(e.clauses()) + ")";
			case DictComprehension e -> "(dict-comp " + this.entry(e.entry()) + this.clauses(e.clauses()) + ")";
			case Call e -> "(call " + this.expression(e.function())
					+ e.arguments().stream().map(a -> " " + this.argument(a)).collect(Collectors.joining()) + ")";
			case Dot e -> "(dot " + this.expression(e.receiver()) + " " + e.name() + ")";
			case Index e -> "(index " + this.expression(e.receiver()) + " " + this.expression(e.index()) + ")";
			case Slice e -> "(slice " + this.expression(e.receiver()) + " " + this.expression(e.start()) + " "
					+ this.expression(e.stop()) + " " + this.expression(e.step()) + ")";
		};
	}

	private String leaf(String text, Expression expression) {
		return this.positions ? "(" + text + " @" + expression.at().line() + ":" + expression.at().column() + ")"
				: "(" + text + ")";
	}

	private String collection(String name, List<Expression> elements) {
		return "(" + name + elements.stream().map(x -> " " + this.expression(x)).collect(Collectors.joining()) + ")";
	}

	private String entry(Entry entry) {
		return "(: " + this.expression(entry.key()) + " " + this.expression(entry.value()) + ")";
	}

	private String clauses(List<Clause> clauses) {
		return clauses.stream().map(c -> switch (c) {
			case ForClause f -> " (for " + this.expression(f.target()) + " " + this.expression(f.iterable()) + ")";
			case IfClause i -> " (if " + this.expression(i.condition()) + ")";
		}).collect(Collectors.joining());
	}

	private String argument(Argument argument) {
		return switch (argument.kind()) {
			case POSITIONAL -> this.expression(argument.value());
			case KEYWORD -> "(= " + argument.name() + " " + this.expression(argument.value()) + ")";
			case STAR -> "(* " + this.expression(argument.value()) + ")";
			case STAR_STAR -> "(** " + this.expression(argument.value()) + ")";
		};
	}

	private static String quote(String text) {
		StringBuilder out = new StringBuilder("\"");
		text.chars().forEach(c -> out.append(escape(c, c >= 0x20 && c != 0x7f)));
		return out.append('"').toString();
	}

	private static String quoteBytes(byte[] bytes) {
		StringBuilder out = new StringBuilder("\"");
		for (byte b : bytes) {
			int c = b & 0xff;
			out.append(escape(c, c >= 0x20 && c < 0x7f));
		}
		return out.append('"').toString();
	}

	private static String escape(int c, boolean printable) {
		return switch (c) {
			case '\\' -> "\\\\";
			case '"' -> "\\\"";
			case '\n' -> "\\n";
			case '\t' -> "\\t";
			case '\r' -> "\\r";
			default -> printable ? String.valueOf((char) c) : String.format("\\x%02x", c);
		};
	}

}
