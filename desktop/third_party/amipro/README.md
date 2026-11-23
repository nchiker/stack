# Vendored Ami Pro for Linux crates

`amipro-document` and `amipro-formats` are copied, source only and unmodified (only `Cargo.toml` is rewritten so the
crates build outside their own workspace), from the Ami Pro for Linux project (`crates/document`, `crates/ami-formats`,
commit d80bed8). They are licensed under the Mozilla Public License 2.0 (see `LICENSE` in each folder); the source is part
of Stack's own source archive. Stack uses them only to read the text of a `.SAM` or `.amipro` file on this computer
(`import_sam_path`, `Document::load_native`); it never writes to such a file. They have no dependencies of their own.
To update: copy the two `src` folders again and run Stack's tests.
