package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Definition for WASM type section entries.
 */
public class TypeDef extends CountingDef<TypeDef> {

	/** Creates a new empty type definition. */
	public TypeDef() {
	}

	/**
	 * Add a function type signature.
	 * @param params the parameter types
	 * @param results the result types
	 * @return this instance for chaining
	 */
	public TypeDef addFunc(Type[] params, Type[] results) {
		return this.add(type -> type.write(Type.FUNC, params.length, params, results.length, results));
	}

	/**
	 * Add a rec type group.
	 * @param consumer a consumer that defines the rec group types
	 * @return this instance for chaining
	 */
	public TypeDef addRecGroup(Consumer<RecTypeDef> consumer) {
		RecTypeDef recTypeDef = new RecTypeDef();
		consumer.accept(recTypeDef);
		// A rec group counts as N types (one per sub type in the group)
		// but as a single entry in the type section
		return this.add(type -> {
			type.write(Type.REC);
			type.write(recTypeDef.count());
			type.write((Object) recTypeDef.toByteArray());
		});
	}

}
