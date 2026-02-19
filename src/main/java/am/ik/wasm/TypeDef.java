package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

public class TypeDef extends CountingDef<TypeDef> {

	public TypeDef addFunc(Type[] params, Type[] results) {
		return this.add(type -> type.write(Type.FUNC, params.length, params, results.length, results));
	}

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
