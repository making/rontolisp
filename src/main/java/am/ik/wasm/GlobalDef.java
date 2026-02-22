package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Definition for WASM global section entries.
 */
public class GlobalDef extends CountingDef<GlobalDef> {

	/** Creates a new empty global definition. */
	public GlobalDef() {
	}

	/**
	 * Add a global variable entry.
	 * @param type the value type
	 * @param mutability the mutability
	 * @param consumer a consumer that writes the init expression
	 * @return this instance for chaining
	 */
	public GlobalDef addGlobal(Type type, Mutability mutability, Consumer<WasmWriter> consumer) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		consumer.accept(new WasmWriter(stream));
		return this.add(global -> global.write(type, mutability, stream.toByteArray(), Instruction.END));
	}

}
