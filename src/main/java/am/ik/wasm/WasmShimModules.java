/*
 * Copyright (C) 2025 Toshiaki Maki <makingx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The two tiny core modules that break a component's instantiation cycle the way
 * wit-component does.
 *
 * <p>
 * A {@code canon lower}ed import that touches linear memory names the importing module's
 * OWN memory (and realloc) in its options, so it cannot exist before that module is
 * instantiated -- while the module cannot be instantiated before every import it names
 * exists. The <strong>shim</strong> is instantiated first: it exports one trampoline per
 * import signature (named {@code "0"}, {@code "1"}, ...) that forwards through slot
 * {@code k} of an exported funcref table, and the module instantiates against those. Once
 * the module's memory is aliased and the real lowered functions are defined, the
 * <strong>fixup</strong> is instantiated against the table and the real functions, and
 * its active element segment writes them into the slots. Both modules are a pure function
 * of the signature list, so a builder derives them from the imports it wires rather than
 * shipping fixed blobs.
 */
public final class WasmShimModules {

	/** The shim's exported table, which the fixup imports and patches. */
	public static final String TABLE_EXPORT = "$imports";

	private WasmShimModules() {
	}

	/**
	 * The export name of trampoline / fixup import slot {@code k}.
	 * @param k the slot index
	 * @return the name ({@code "0"}, {@code "1"}, ...)
	 */
	public static String slotName(int k) {
		return Integer.toString(k);
	}

	/**
	 * The shim module: a funcref table of {@code n} slots exported as
	 * {@link #TABLE_EXPORT}, and one exported trampoline per signature whose body is
	 * {@code local.get 0..; i32.const k; call_indirect (type k)}.
	 * @param params the parameter types of each slot's signature, in slot order
	 * @param results the result types of each slot's signature, in slot order
	 * @return the module bytes
	 */
	public static byte[] shim(List<Type[]> params, List<Type[]> results) {
		requireSameLength(params, results);
		final int n = params.size();
		ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write("\0asm").writeLittleEndian4(1).writeTypeSection(types -> {
			for (int k = 0; k < n; k++) {
				types.addFunc(params.get(k), results.get(k));
			}
		}).writeFunction(funcs -> {
			for (int k = 0; k < n; k++) {
				funcs.addFunction(k);
			}
		});
		w.writeSection(Section.TABLE, (Entries table) -> table.add(t -> {
			t.write(0x70); // funcref
			t.write(0x01).writeUnsignedLeb128(n).writeUnsignedLeb128(n); // limits: min =
																			// max = n
		}), Entries::new);
		w.writeExport(exports -> {
			for (int k = 0; k < n; k++) {
				exports.addExport(slotName(k), ExternalKind.FUNCTION, k);
			}
			exports.addExport(TABLE_EXPORT, ExternalKind.TABLE, 0);
		}).writeCode(code -> {
			for (int k = 0; k < n; k++) {
				ByteArrayOutputStream body = new UnsynchronizedByteArrayOutputStream();
				WasmWriter b = new WasmWriter(body);
				b.writeUnsignedLeb128(0); // no locals
				for (int p = 0; p < params.get(k).length; p++) {
					b.write(Instruction.GET_LOCAL).writeUnsignedLeb128(p);
				}
				b.write(Instruction.I32_CONST).writeSignedLeb128(k);
				b.write(Instruction.CALL_INDIRECT).writeUnsignedLeb128(k).writeUnsignedLeb128(0);
				b.write(Instruction.END);
				code.addFunction(body.toByteArray());
			}
		});
		return out.toByteArray();
	}

	/**
	 * The fixup module: imports the real function of every slot (module {@code ""}, field
	 * {@link #slotName}) and the shim's table (module {@code ""}, field
	 * {@link #TABLE_EXPORT}), and holds one active element segment writing the functions
	 * into slots {@code 0..n-1}.
	 * @param params the parameter types of each slot's signature, in slot order
	 * @param results the result types of each slot's signature, in slot order
	 * @return the module bytes
	 */
	public static byte[] fixup(List<Type[]> params, List<Type[]> results) {
		requireSameLength(params, results);
		final int n = params.size();
		ByteArrayOutputStream out = new UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(out);
		w.write("\0asm").writeLittleEndian4(1).writeTypeSection(types -> {
			for (int k = 0; k < n; k++) {
				types.addFunc(params.get(k), results.get(k));
			}
		});
		w.writeImportSection(imports -> {
			for (int k = 0; k < n; k++) {
				final int type = k;
				imports.add(i -> {
					writeName(i, "");
					writeName(i, slotName(type));
					i.write(ExternalKind.FUNCTION).writeUnsignedLeb128(type);
				});
			}
			imports.add(i -> {
				writeName(i, "");
				writeName(i, TABLE_EXPORT);
				i.write(ExternalKind.TABLE);
				i.write(0x70); // funcref
				i.write(0x01).writeUnsignedLeb128(n).writeUnsignedLeb128(n);
			});
		});
		w.writeSection(Section.ELEMENT, (Entries elements) -> elements.add(e -> {
			e.write(0x00); // active, table 0, funcref indices
			e.write(Instruction.I32_CONST).writeSignedLeb128(0).write(Instruction.END);
			e.writeUnsignedLeb128(n);
			for (int k = 0; k < n; k++) {
				e.writeUnsignedLeb128(k);
			}
		}), Entries::new);
		return out.toByteArray();
	}

	// A counted vector of raw entries, for the sections the writer has no typed
	// definition for (table, element).
	private static final class Entries extends CountingDef<Entries> {

	}

	// A name is its byte length then its bytes; every name here is ASCII.
	private static void writeName(WasmWriter w, String name) {
		w.writeUnsignedLeb128(name.length()).write(name);
	}

	private static void requireSameLength(List<Type[]> params, List<Type[]> results) {
		if (params.size() != results.size()) {
			throw new IllegalArgumentException(
					"one result list per parameter list: " + params.size() + " vs " + results.size());
		}
		if (params.isEmpty()) {
			throw new IllegalArgumentException("a shim needs at least one slot");
		}
	}

}
