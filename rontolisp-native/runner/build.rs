//! Maps the macOS payload segment read-only: the module embedded there is data the runner
//! only reads (`rlabi::payload`), and the linker would otherwise make it writable.
fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-link-arg-bins=-Wl,-segprot,__RLPAYLOAD,r,r");
    }
}
