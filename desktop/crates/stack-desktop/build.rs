// Single source of truth for the app version: the newest <release> in the
// packaging metainfo (the same file tools/package_deb.py reads).
fn main() {
    let path = "../../packaging/org.stackdocs.desktop.metainfo.xml";
    println!("cargo:rerun-if-changed={path}");
    let xml = std::fs::read_to_string(path).expect("read metainfo");
    let rest = &xml[xml.find("<release ").expect("no <release> in metainfo")..];
    let rest = &rest[rest.find("version=\"").expect("release has no version") + 9..];
    let version = &rest[..rest.find('"').expect("unterminated version")];
    println!("cargo:rustc-env=STACK_APP_VERSION={version}");
}
