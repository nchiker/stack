# M0 dependency decisions — 2026-10-17

Target: native Linux x86-64 COSMIC/Wayland source build. These pins belong to the
M0 foundation; they do not select future sync transport or archive storage APIs.

| Component | Pin / license record | Decision |
| --- | --- | --- |
| Rust | 1.99.0, exact toolchain file | Existing installed stable reports the same compiler; use `RUSTUP_TOOLCHAIN=stable` in this environment to avoid a duplicate toolchain installation. |
| libcosmic | `dbc62f2d899cdbfbe43d170689ddc0290db663f4`, MPL-2.0 | Independently cached official toolkit; `winit`, `wayland`, `tokio`, `xdg-portal`, `a11y`; default features disabled. Follow the pinned source API. |
| Toolkit graph | Cargo.lock | Exact transitive Git and registry sources/checksums. Some toolkit features pull GPU-related build dependencies; runtime renderer evidence must come from the actual run. |
| PDF wrapper | pdfium-render 0.9.4, MIT OR Apache-2.0 | Explicit `pdfium_7881`, `image_025`, `thread_safe`; no runtime engine download. Global bindings require one persistent worker per process. |
| PDF engine | bblanchon/pdfium-binaries `chromium/8100`, Linux x64 | Download/install only through explicit setup; complete package `LICENSE` and `licenses/` preserved. V8/XFA disabled in packaged build arguments. |
| Image codecs | image 0.25.10, MIT OR Apache-2.0 | JPEG/PNG/WebP; limits applied before decoding, EXIF orientation plus explicit quarter-turn rotation, bounded output without upscaling images. |
| Other direct crates | thiserror 2.0.21; serde 1.0.229; serde_json 1.0.154; sha2 0.10.9; tokio 1.53.3 | Pinned in workspace; exact dependency license declarations and sources are recorded in the inventory. |

PDFium archive URL:
`https://github.com/bblanchon/pdfium-binaries/releases/download/chromium/8100/pdfium-linux-x64.tgz`

- Archive SHA-256: `1c1894a12d4d96ee01400c3874444126254180c5c7d2ab628576b67f8120320a`.
- Shared library SHA-256: `b8784cbd652ad2993332b7695e2c80f3a797f877fc47d9be97b66bca9639539e`.
- The downloaded archive digest matches the GitHub release asset's published digest.
- The package MIT license covers distribution tooling; PDFium itself and its
  bundled components have the separate terms under `licenses/`. Do not replace
  that directory with a single MIT notice. Setup retains all 17 component notice files.
- `ldd` resolves the pinned engine against installed libc/libm/libgcc/pthread,
  with no missing library reported in this environment.

The generated [inventory](M0_DEPENDENCIES.json) includes 487 upstream packages in
the Linux Cargo graph (including build/dev dependencies, not a binary payload list).
Nine upstream manifests omit license metadata. Their nearest upstream license
texts were inspected; the inventory records the reviewed identifier and text digest:
libcosmic/config/theme/DBus bindings use MPL-2.0; iced accessibility and clipboard
helpers use MIT; apply uses the Unlicense. Other packages keep upstream declarations,
including alternative-license expressions; no legal/compliance certification is implied.

Official sources consulted:

- [libcosmic source and build guidance](https://github.com/pop-os/libcosmic).
- [COSMIC application API](https://pop-os.github.io/libcosmic/cosmic/).
- [pdfium-render documentation and binding/lifecycle guidance](https://github.com/ajrcarey/pdfium-render).
- [PDFium binary packaging project](https://github.com/bblanchon/pdfium-binaries).
- [PDFium upstream licensing](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE).

The absent fontconfig/freetype `pkg-config` entries do not require changes for this
configuration: the Rust font stack can build using the available environment.
SQLite and Poppler are not linked by Stack's M0 code. No OS package installation,
global linker shim or external PDF viewer is part of the rendering solution.
