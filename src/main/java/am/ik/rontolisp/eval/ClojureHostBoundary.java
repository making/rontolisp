package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import am.ik.rontolisp.clojure.ClojureBoundary;
import am.ik.rontolisp.compiler.BoundaryType;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.rontolisp.compiler.WitTypeMapper;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The host boundary the Clojure lowering consults while a program lowers
 * ({@code rontolisp.wasm}, {@code rontolisp.wit}), over the compiler's own front ends:
 * {@link BoundaryType} for the designators, {@link WitImportDirective#describe} and
 * {@link WitExportDirective#describe} for a WIT interface's members and a world's
 * exports. So a member a Clojure var names is the member the emitted
 * {@code rontolisp:wit-import} binds, under the same name and with the same refusals, and
 * no WIT is read twice in two dialects. Stateless; one instance serves every read of a
 * target, a Preview 1 core module having its own ({@link #CORE_MODULE}).
 */
public final class ClojureHostBoundary implements ClojureBoundary {

	/**
	 * The boundary a read through the source-language seam lowers against, but for a
	 * Preview 1 core module's.
	 */
	public static final ClojureBoundary INSTANCE = new ClojureHostBoundary(false);

	/**
	 * A Preview 1 core module's boundary, which carries a WIT {@code list<u8>} as
	 * {@code :string} text.
	 */
	public static final ClojureBoundary CORE_MODULE = new ClojureHostBoundary(true);

	private static final Set<String> DESIGNATORS = designatorSet();

	private final boolean bytesAsText;

	private ClojureHostBoundary(boolean bytesAsText) {
		this.bytesAsText = bytesAsText;
	}

	/**
	 * The boundary a read with these features lowers against.
	 * @param features the active reader features
	 * @return {@link #CORE_MODULE} for a WASM core module, else {@link #INSTANCE}
	 */
	public static ClojureBoundary of(Features features) {
		return features.contains("rontolisp-wasm") && !features.contains(Features.COMPONENT) ? CORE_MODULE : INSTANCE;
	}

	@Override
	public boolean bytesCrossAsText() {
		return this.bytesAsText;
	}

	private static Set<String> designatorSet() {
		Set<String> out = new LinkedHashSet<>();
		for (BoundaryType type : BoundaryType.values()) {
			if (type != BoundaryType.VOID && !type.jvmOnly()) {
				out.add(type.designator());
			}
		}
		// the house spellings the vocabulary normalizes (BoundaryType's aliases)
		out.add(":INT");
		out.add(":LONG");
		return Set.copyOf(out);
	}

	@Override
	public Set<String> designators() {
		return DESIGNATORS;
	}

	@Override
	public WitInterface importInterface(String witText, String witPath, String iface) {
		// the reference the emitted directive's parse reads: lowercased, like a bare
		// symbol (WitImportDirective.parse)
		WitImportDirective.Directive directive = new WitImportDirective.Directive(witPath,
				iface.toLowerCase(Locale.ROOT), null, null, WitImportDirective.FieldStyle.CAMEL);
		WitImportDirective.Description described;
		try {
			described = WitImportDirective.describe(directive, witText, witPath);
		}
		catch (UnsupportedOperationException ex) {
			throw new LispReadException(String.valueOf(ex.getMessage()));
		}
		List<Function> members = new ArrayList<>();
		for (WitImportDirective.Member member : described.members()) {
			members.add(new Function(member.name(), Origin.valueOf(member.origin().name()), params(member.params()),
					type(member.result()), member.async(), member.line()));
		}
		return new WitInterface(described.id(), List.copyOf(members));
	}

	@Override
	public WitWorld exportWorld(String witText, String witPath, @Nullable String world) {
		WitExportDirective.WorldDescription described;
		try {
			described = WitExportDirective.describe(new WitExportDirective.Directive(witPath, world), witText, witPath);
		}
		catch (UnsupportedOperationException ex) {
			throw new LispReadException(String.valueOf(ex.getMessage()));
		}
		List<Function> exports = new ArrayList<>();
		for (WitExportDirective.Export export : described.exports()) {
			exports.add(new Function(export.name(), Origin.FUNCTION, params(export.params()), type(export.result()),
					export.async(), export.line()));
		}
		return new WitWorld(described.name(), List.copyOf(exports));
	}

	private static List<Param> params(List<WitTypeMapper.Param> params) {
		List<Param> out = new ArrayList<>();
		for (WitTypeMapper.Param param : params) {
			out.add(new Param(param.name(), java.util.Objects.requireNonNull(type(param.shape()))));
		}
		return List.copyOf(out);
	}

	/**
	 * A compiler shape as the Clojure lowering's type, every nested shape with it: the
	 * representation by name (the two enums spell the same members,
	 * {@code ClojureHostBoundaryTest}).
	 */
	static @Nullable Type type(WitTypeMapper.@Nullable Shape shape) {
		if (shape == null) {
			return null;
		}
		List<Part> parts = new ArrayList<>();
		for (WitTypeMapper.Part part : shape.parts()) {
			parts.add(new Part(part.label(), type(part.shape())));
		}
		return new Type(Rep.valueOf(shape.rep().name()), type(shape.element()), shape.wit(), type(shape.error()),
				List.copyOf(parts));
	}

}
