# Vendored Ami Pro for Linux crates (phone native library)

`amipro-document`, `amipro-formats` and `amipro-text` are copied, source only (each `src` folder unmodified; only
`Cargo.toml` is rewritten so the crates build outside their own workspace, and `tests/` is left out), from the Ami Pro for
Linux project (`crates/document`, `crates/ami-formats`, `crates/text`).

Source: AmiPro-Linux commit 24605e1 (branch folder-sharing, local; "Reading core: bound the escape search to the longest
escape (quadratic on a line of unterminated <), amipro-text VERSION 3"; `cargo test --workspace` passes there). It follows 6d3623b
("bound note nesting, cap styles, sections, flows, frames and paragraphs, amipro-text stops collecting at max_chars"). The first commit with `crates/text` was 6f10818. `amipro-text` is I1 of
`docs/FOLDER_SHARING_INTERFACES.md`, `VERSION = 3`. The text crate's own tests
(which need `ami-formats/tests/fixtures`) are not vendored; Stack's `TypeChecks` use their own synthetic files.

Licensed under the Mozilla Public License 2.0 (see `LICENSE` in each folder); the source is part of Stack's own source archive.
They have no third-party dependencies. Stack uses them only through `stacknative` (`org.stackdocs.app.types.NativeText`) to
read the text of a `.SAM` or `.amipro` file; nothing is ever written to such a file.
To update: copy the three `src` folders again and run Stack's checks (`-e types true`).
