//! Gives the shared library a location-independent name: its default install name on
//! macOS is the cargo build path.
fn main() {
    match std::env::var("CARGO_CFG_TARGET_OS").as_deref() {
        Ok("macos") => println!("cargo:rustc-cdylib-link-arg=-Wl,-install_name,@rpath/librlprecomp.dylib"),
        Ok("linux") => println!("cargo:rustc-cdylib-link-arg=-Wl,-soname,librlprecomp.so"),
        _ => {}
    }
}
