//! Where the packaged PDFium and OCR files live. The installed package keeps them next to the program
//! (`<prefix>/bin/<program>` and `<prefix>/lib/stack-docs/runtime/...`), so nothing needs an environment variable.
//! The `STACK_*` variables stay as an override (tests, the Flatpak build).
use std::path::{Path, PathBuf};

/// Folder under `<prefix>/lib` that holds `runtime/`.
const PACKAGE_DIR: &str = "stack-docs";

/// `<prefix>/lib/stack-docs/runtime` for a program at `<prefix>/bin/<program>`.
pub fn runtime_dir_for(exe: &Path) -> Option<PathBuf> {
    let prefix = exe.parent()?.parent()?;
    Some(prefix.join("lib").join(PACKAGE_DIR).join("runtime"))
}

fn installed_runtime() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    runtime_dir_for(&exe)
}

/// The override when given, otherwise the packaged file when it exists.
pub fn pick(env: Option<PathBuf>, packaged: Option<PathBuf>) -> Option<PathBuf> {
    env.or_else(|| packaged.filter(|p| p.exists()))
}

pub fn pdfium_library() -> Option<PathBuf> {
    pick(
        std::env::var_os("STACK_PDFIUM_LIBRARY").map(PathBuf::from),
        installed_runtime().map(|r| r.join("pdfium/lib/libpdfium.so")),
    )
}

pub fn tesseract_library() -> Option<PathBuf> {
    pick(
        std::env::var_os("STACK_TESSERACT_LIBRARY").map(PathBuf::from),
        installed_runtime().map(|r| r.join("ocr/lib/libtesseract.so.5")),
    )
}

pub fn tessdata_dir() -> Option<PathBuf> {
    pick(
        std::env::var_os("STACK_TESSDATA_DIR").map(PathBuf::from),
        installed_runtime().map(|r| r.join("ocr/tessdata")),
    )
}

/// OpenMP (inside the OCR engine) must use one thread. Call first thing in `main`, before any thread starts.
/// An explicit value from the caller is kept.
pub fn limit_ocr_threads() {
    if std::env::var_os("OMP_THREAD_LIMIT").is_none() {
        // SAFETY: called at the very start of `main`, before this program starts any other thread.
        unsafe { std::env::set_var("OMP_THREAD_LIMIT", "1") };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runtime_sits_beside_the_program_prefix() {
        assert_eq!(
            runtime_dir_for(Path::new("/usr/bin/stack-desktop")).unwrap(),
            Path::new("/usr/lib/stack-docs/runtime")
        );
        assert!(runtime_dir_for(Path::new("stack-desktop")).is_none());
    }

    #[test]
    fn thread_limit_is_set_and_an_existing_value_is_kept() {
        // Single test touching this variable.
        // SAFETY: the other tests in this module do not read or write the environment.
        unsafe { std::env::remove_var("OMP_THREAD_LIMIT") };
        limit_ocr_threads();
        assert_eq!(std::env::var("OMP_THREAD_LIMIT").unwrap(), "1");
        unsafe { std::env::set_var("OMP_THREAD_LIMIT", "3") };
        limit_ocr_threads();
        assert_eq!(std::env::var("OMP_THREAD_LIMIT").unwrap(), "3");
    }

    #[test]
    fn override_wins_and_a_missing_packaged_file_is_ignored() {
        let dir = tempfile::tempdir().unwrap();
        let present = dir.path().join("lib.so");
        std::fs::write(&present, b"x").unwrap();
        let other = PathBuf::from("/elsewhere/lib.so");
        assert_eq!(
            pick(Some(other.clone()), Some(present.clone())),
            Some(other)
        );
        assert_eq!(pick(None, Some(present.clone())), Some(present));
        assert_eq!(pick(None, Some(dir.path().join("none"))), None);
        assert_eq!(pick(None, None), None);
    }
}
