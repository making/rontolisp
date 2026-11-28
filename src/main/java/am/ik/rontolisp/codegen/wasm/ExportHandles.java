package am.ik.rontolisp.codegen.wasm;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitExportDirective;
import am.ik.wit.WitItem;
import am.ik.wit.WitResolver;

import org.jspecify.annotations.Nullable;

/**
 * What the wrapper of a world's export calls where a handle of a resource the world
 * exports crosses: the slot store that keeps the value behind each handle
 * ({@code wit-resource.lisp}), and the three canonical built-ins of each resource, which
 * the component defines over its own resource type and the core module imports.
 *
 * <p>
 * The canonical ABI passes a handle of a resource the component itself defines in two
 * ways: a borrowed one as its representation, an owned one as an index into the
 * instance's handle table. The representation is the index of the value's slot.
 *
 * @param repNew the function index of {@code rontolisp::%wit-rep-new}
 * @param repValue the function index of {@code rontolisp::%wit-rep-value}
 * @param repTake the function index of {@code rontolisp::%wit-rep-take}
 * @param resources the built-ins of each exported resource, by
 * {@code <interface id>#<resource>}
 * @param ids the id of each exported interface, by the scope its members' type names
 * resolve in
 * @param resolver the resolver over the world's document
 */
record ExportHandles(int repNew, int repValue, int repTake, Map<String, Resource> resources,
		IdentityHashMap<WitItem.InterfaceDef, String> ids, WitResolver resolver) {

	/**
	 * The fields the core module imports a resource's built-ins under, before the
	 * resource's name; the module is {@link #module}.
	 */
	static final List<String> FIELDS = List.of(WitExportDirective.RESOURCE_NEW_PREFIX,
			WitExportDirective.RESOURCE_REP_PREFIX, WitExportDirective.RESOURCE_DROP_PREFIX);

	/** The position of {@code canon resource.new} in {@link #FIELDS}. */
	static final int NEW = 0;

	/** The position of {@code canon resource.rep} in {@link #FIELDS}. */
	static final int REP = 1;

	/** The position of {@code canon resource.drop} in {@link #FIELDS}. */
	static final int DROP = 2;

	/**
	 * The placeholder ordinals of one resource's built-ins.
	 *
	 * @param resourceNew {@code canon resource.new}: a representation to an owned handle
	 * @param resourceRep {@code canon resource.rep}: a handle to its representation
	 * @param resourceDrop {@code canon resource.drop}: releases a handle
	 */
	record Resource(int resourceNew, int resourceRep, int resourceDrop) {
	}

	/**
	 * The module the core module imports the built-ins of an exported interface's
	 * resources from.
	 * @param ifaceId the exported interface's id
	 * @return the import module name
	 */
	static String module(String ifaceId) {
		return WitExportDirective.EXPORTED_MODULE_PREFIX + ifaceId;
	}

	/**
	 * Whether a host import is one of the built-ins of an exported interface's resources
	 * ({@link #module}), which the component defines rather than imports: what the scalar
	 * backend's {@code rontolisp:wit-export} declares them as.
	 * @param module the import module name
	 * @return whether it names such a built-in's module
	 */
	static boolean builtin(String module) {
		return module.startsWith(WitExportDirective.EXPORTED_MODULE_PREFIX);
	}

	/**
	 * The name the core module exports a resource's destructor under.
	 * @param ifaceId the exported interface's id
	 * @param resource the resource's name
	 * @return the core export name
	 */
	static String destructor(String ifaceId, String resource) {
		return ifaceId + "#" + WitExportDirective.DESTRUCTOR_PREFIX + resource;
	}

	/**
	 * Gathers what the wrappers of a program's exports call.
	 * @param resources the built-ins of each exported resource
	 * @param decls the program's exports, which say what their world is
	 * @param callIndex the function index of a top-level defun
	 * @return the handles
	 */
	static ExportHandles of(Map<String, Resource> resources, List<WasmExportCompiler.Decl> decls,
			ToIntFunction<String> callIndex) {
		IdentityHashMap<WitItem.InterfaceDef, String> ids = new IdentityHashMap<>();
		WitResolver resolver = null;
		for (WasmExportCompiler.Decl decl : decls) {
			WitExportSignature typing = decl.typing();
			if (typing != null) {
				for (WitExportDirective.InterfaceExport exported : typing.worldInterfaces()) {
					ids.put(exported.scope(), exported.id());
				}
				resolver = typing.abi().resolver();
				break;
			}
		}
		if (resolver == null) {
			throw new UnsupportedOperationException("rontolisp:wasm-export names the destructor of a resource"
					+ " that no world declares: only rontolisp:wit-export writes one, for a resource of an"
					+ " interface its world exports");
		}
		return new ExportHandles(callIndex.applyAsInt(internal(LispNames.WIT_REP_NEW)),
				callIndex.applyAsInt(internal(LispNames.WIT_REP_VALUE)),
				callIndex.applyAsInt(internal(LispNames.WIT_REP_TAKE)), Map.copyOf(resources), ids, resolver);
	}

	private static String internal(String name) {
		return PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, name);
	}

	/**
	 * Finds the built-ins of a resource.
	 * @param owner the interface that declares the resource, as a type name resolved to
	 * it
	 * @param name the resource's name there
	 * @return the built-ins, or {@code null} for a resource the world does not export
	 */
	@Nullable Resource of(WitItem.InterfaceDef owner, String name) {
		String id = this.ids.get(owner);
		if (id == null) {
			// an interface another one names in a use clause: the document's own
			id = this.resolver.canonicalId(owner);
		}
		return id == null ? null : this.resources.get(id + "#" + name);
	}

}
