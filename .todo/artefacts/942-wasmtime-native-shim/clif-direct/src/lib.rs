//! Minimal C ABI over Cranelift: CLIF text + symbol table in, relocatable object out.
use cranelift_codegen::ir::UserFuncName;
use cranelift_codegen::settings::{self, Configurable};
use cranelift_codegen::Context;
use cranelift_module::{Linkage, Module};
use cranelift_object::{ObjectBuilder, ObjectModule};
use std::slice;

fn compile(clif: &str, names: &str) -> Result<Vec<u8>, String> {
    let mut flags = settings::builder();
    flags.set("opt_level", "speed").map_err(|e| e.to_string())?;
    flags.set("is_pic", "true").map_err(|e| e.to_string())?;
    let isa = cranelift_native::builder()
        .map_err(|e| e.to_string())?
        .finish(settings::Flags::new(flags))
        .map_err(|e| e.to_string())?;
    let builder = ObjectBuilder::new(isa, "rl", cranelift_module::default_libcall_names())
        .map_err(|e| e.to_string())?;
    let mut module = ObjectModule::new(builder);
    // One line per symbol, "<linkage> <name>"; line i is referenced from CLIF as u0:i.
    let funcs = cranelift_reader::parse_functions(clif).map_err(|e| e.to_string())?;
    let mut ids = Vec::new();
    for line in names.lines().filter(|l| !l.is_empty()) {
        let (kind, name) = line.split_once(' ').ok_or("bad name line")?;
        let linkage = match kind {
            "export" => Linkage::Export,
            "local" => Linkage::Local,
            "import" => Linkage::Import,
            _ => return Err(format!("bad linkage {kind}")),
        };
        // The signature of an import is taken from the first function that uses it; for
        // a definition it is the definition's own.
        let sig = funcs
            .iter()
            .find(|f| matches!(&f.name, UserFuncName::User(u) if u.index as usize == ids.len()))
            .map(|f| f.signature.clone())
            .or_else(|| {
                funcs.iter().find_map(|f| {
                    f.dfg.ext_funcs.values().find_map(|ef| match &ef.name {
                        cranelift_codegen::ir::ExternalName::User(r) => {
                            let u = &f.params.user_named_funcs()[*r];
                            (u.index as usize == ids.len())
                                .then(|| f.dfg.signatures[ef.signature].clone())
                        }
                        _ => None,
                    })
                })
            })
            .ok_or(format!("no signature for {name}"))?;
        ids.push(module.declare_function(name, linkage, &sig).map_err(|e| e.to_string())?);
    }
    let mut ctx = Context::new();
    for f in funcs {
        let idx = match &f.name {
            UserFuncName::User(u) => u.index as usize,
            _ => return Err("functions must be named u0:N".into()),
        };
        ctx.func = f;
        module
            .define_function(ids[idx], &mut ctx)
            .map_err(|e| format!("{e:?}"))?;
        ctx.clear();
    }
    let mut product = module.finish();
    if cfg!(target_os = "macos") {
        let mut bv = object::write::MachOBuildVersion::default();
        bv.platform = object::macho::PLATFORM_MACOS;
        bv.minos = 14 << 16;
        bv.sdk = 14 << 16;
        product.object.set_macho_build_version(bv);
    }
    product.emit().map_err(|e| e.to_string())
}

/// Returns 0 on success; *out holds the object (or the error text on failure), freed by rl_free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_compile(
    clif: *const u8, clif_len: usize, names: *const u8, names_len: usize,
    out: *mut *mut u8, out_len: *mut usize,
) -> i32 {
    let (clif, names) = unsafe {
        (slice::from_raw_parts(clif, clif_len), slice::from_raw_parts(names, names_len))
    };
    let r = match (std::str::from_utf8(clif), std::str::from_utf8(names)) {
        (Ok(c), Ok(n)) => compile(c, n),
        _ => Err("not utf-8".into()),
    };
    let (code, bytes) = match r { Ok(b) => (0, b), Err(e) => (1, e.into_bytes()) };
    let mut b = bytes.into_boxed_slice();
    unsafe { *out_len = b.len(); *out = b.as_mut_ptr(); }
    std::mem::forget(b);
    code
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn rl_free(p: *mut u8, len: usize) {
    drop(unsafe { Box::from_raw(slice::from_raw_parts_mut(p, len)) });
}
