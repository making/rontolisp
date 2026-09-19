package am.ik.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import am.ik.wasm.WasmCodeModel.Body;
import am.ik.wasm.WasmCodeModel.Instr;
import am.ik.wasm.WasmCodeModel.TypeSection;
import am.ik.wasm.WasmSections.Ref;
import am.ik.wasm.WasmSections.RefKind;
import am.ik.wasm.WasmSections.Section;
import org.jspecify.annotations.Nullable;

/**
 * Language-independent redirection of calls through a pure forwarder: a defined function
 * whose entire body reads its parameters in order and calls one other function of the
 * same type with them. Calling the forwarder is calling its target, so every {@code call}
 * of it is rewritten to the target (chains resolve to their last link), and the forwarder
 * is then unreferenced -- {@link WasmTreeShaker} drops it unless an export or the start
 * section names it, in which case the export keeps working through the untouched body.
 * <p>
 * Such forwarders are what {@link WasmRefTypeFolder} leaves of a dispatching runtime
 * helper once every arm but one is proved dead ({@code _rat_add} reduced to
 * {@code _big_add}, {@code _rat_cmp_bits} to {@code _rat_cmp}): the fold cannot delete
 * the function, but nothing needs the hop. Same-type is decided canonically, as
 * {@link WasmBodyFolder} decides it, so a redirected call validates against the target's
 * signature exactly as it did against the forwarder's.
 */
public final class WasmCallForwarding {

	private WasmCallForwarding() {
	}

	private static final int SEC_TYPE = 1;

	private static final int SEC_FUNCTION = 3;

	private static final int SEC_TABLE = 4;

	private static final int SEC_ELEMENT = 9;

	private static final int SEC_CODE = 10;

	/**
	 * Redirects every call of a pure forwarder to the function it forwards to.
	 * @param module a core WASM module (the 8-byte header followed by sections)
	 * @return the module with its call immediates redirected; the input itself when it
	 * holds no forwarder
	 */
	public static byte[] redirect(byte[] module) {
		List<Section> sections = WasmSections.parseSections(module);
		@Nullable Section typeSec = null;
		@Nullable Section functionSec = null;
		@Nullable Section codeSec = null;
		for (Section s : sections) {
			if (s.id() == SEC_TABLE || s.id() == SEC_ELEMENT) {
				throw new IllegalStateException("WasmCallForwarding: unhandled section id " + s.id());
			}
			if (s.id() == SEC_TYPE) {
				typeSec = s;
			}
			else if (s.id() == SEC_FUNCTION) {
				functionSec = s;
			}
			else if (s.id() == SEC_CODE) {
				codeSec = s;
			}
		}
		if (typeSec == null || functionSec == null || codeSec == null) {
			return module;
		}
		int numImports = WasmSections.importedFunctionCount(module);
		int[] defTypeIdx = WasmSections.parseFunctionSection(functionSec.payload());
		List<byte[]> codeEntries = WasmSections.parseCodeEntries(codeSec.payload());
		TypeSection types = WasmCodeModel.parseTypeSection(typeSec.payload());
		String[] typeKeys = WasmBodyFolder.typeEquivalenceKeys(typeSec.payload());
		int total = numImports + codeEntries.size();
		// forward[f] = the function f's body forwards to, or -1.
		int[] forward = new int[total];
		java.util.Arrays.fill(forward, -1);
		boolean any = false;
		for (int d = 0; d < codeEntries.size(); d++) {
			int target = forwardTarget(codeEntries.get(d), types, defTypeIdx[d]);
			if (target < 0 || target >= total || target == numImports + d) {
				continue;
			}
			// The target must declare the same canonical type: only a defined function
			// has a type index here (an import's is in the import section), and a
			// forwarder to an import keeps its hop.
			if (target < numImports || !typeKeys[defTypeIdx[target - numImports]].equals(typeKeys[defTypeIdx[d]])) {
				continue;
			}
			forward[numImports + d] = target;
			any = true;
		}
		if (!any) {
			return module;
		}
		// Resolve chains to their last link; a cycle keeps every member as it is.
		int[] resolved = new int[total];
		for (int f = 0; f < total; f++) {
			int at = f;
			int hops = 0;
			while (forward[at] >= 0 && hops <= total) {
				at = forward[at];
				hops++;
			}
			resolved[f] = hops > total ? f : at;
		}
		List<byte[]> rewritten = new ArrayList<>(codeEntries.size());
		boolean changed = false;
		for (byte[] entry : codeEntries) {
			byte[] out = redirectCalls(entry, resolved);
			changed |= out != entry;
			rewritten.add(out);
		}
		if (!changed) {
			return module;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmSections.writeU(body, rewritten.size());
		for (byte[] entry : rewritten) {
			WasmSections.writeU(body, entry.length);
			WasmSections.writeRaw(body, entry);
		}
		List<Section> rebuilt = new ArrayList<>(sections.size());
		for (Section s : sections) {
			rebuilt.add(s.id() == SEC_CODE ? new Section(SEC_CODE, body.toByteArray()) : s);
		}
		return WasmSections.assemble(rebuilt);
	}

	// The function a code entry forwards to, or -1: exactly `local.get 0 .. local.get
	// n-1`, `call g`, `end`, with n the entry's own parameter count (declared locals
	// it no longer touches do not matter).
	private static int forwardTarget(byte[] entry, TypeSection types, int typeIndex) {
		Body body = WasmCodeModel.decode(entry, types);
		int n = types.func(typeIndex).params().size();
		List<Instr> code = body.code();
		if (code.size() != n + 2) {
			return -1;
		}
		for (int k = 0; k < n; k++) {
			Instr in = code.get(k);
			if (in.op != 0x20 || in.a != k) {
				return -1;
			}
		}
		// A forwarder's one call is a tail call, so the emitter spells it return_call
		// (0x12); a plain call (0x10) is the same shape.
		Instr call = code.get(n);
		if ((call.op != 0x10 && call.op != 0x12) || code.get(n + 1).op != 0x0B) {
			return -1;
		}
		return (int) call.a;
	}

	private static byte[] redirectCalls(byte[] entry, int[] resolved) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int cursor = 0;
		boolean any = false;
		for (Ref r : WasmSections.scanBody(entry)) {
			if (r.kind() != RefKind.FUNC || resolved[r.index()] == r.index()) {
				continue;
			}
			WasmSections.writeRaw(out, WasmSections.slice(entry, cursor, r.start()));
			WasmSections.writeU(out, resolved[r.index()]);
			cursor = r.end();
			any = true;
		}
		if (!any) {
			return entry;
		}
		WasmSections.writeRaw(out, WasmSections.slice(entry, cursor, entry.length));
		return out.toByteArray();
	}

}
