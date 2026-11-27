//! Stack's phone native library. Today: Ami Pro text for `org.stackdocs.app.types.NativeText` (I2).
//!
//! Also the PDF text layer (`pdftext`, I3 PdfText).
//!
//! The JNI table is used directly (no dependencies). Every entry point catches panics and answers "damaged".
//! No file name, key or document text is ever logged.

use amipro_text::{Extracted, Limits, Reason};
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};

pub mod pdftext;

type Env = *mut *const *const c_void;
type Obj = *mut c_void;

// Indices in the JNI function table (JNI specification).
const FIND_CLASS: usize = 6;
const NEW_STRING: usize = 163;
const DELETE_LOCAL_REF: usize = 23;
const GET_ARRAY_LENGTH: usize = 171;
const NEW_OBJECT_ARRAY: usize = 172;
const SET_OBJECT_ARRAY_ELEMENT: usize = 174;
const GET_BYTE_ARRAY_REGION: usize = 200;

macro_rules! jni {
    ($env:expr, $index:expr, $ty:ty) => {
        std::mem::transmute::<*const c_void, $ty>(*(*$env).add($index))
    };
}

/// Characters kept when the caller gives no usable limit (zero or negative).
pub const DEFAULT_MAX_CHARS: usize = 1_000_000;
/// How long the Ami Pro reader may run before the caller gives up on it.
const AMI_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

/// The result as [status, title, text] in Rust terms. `status` is one of: "text", "protected", "too_large", "not_amipro",
/// "damaged", and "timeout" (the reader did not answer in `AMI_TIMEOUT` and is still running on its thread: the caller
/// must stop the process that holds it, which is what `NativeText`'s status list says too).
pub struct Answer {
    pub status: &'static str,
    pub title: String,
    pub text: String,
}

fn answer(status: &'static str) -> Answer {
    Answer { status, title: String::new(), text: String::new() }
}

/// The whole job, without JNI: used by the JNI entry point and by this crate's tests.
pub fn read(bytes: &[u8], max_chars: i32) -> Answer {
    let mut limits = Limits::default();
    limits.max_chars = if max_chars > 0 { max_chars as usize } else { DEFAULT_MAX_CHARS };
    match amipro_text::extract_text(bytes, &limits) {
        Extracted::Text { title, text, .. } => Answer { status: "text", title: title.unwrap_or_default(), text },
        Extracted::Protected => answer("protected"),
        Extracted::Unreadable(Reason::TooLarge) => answer("too_large"),
        Extracted::Unreadable(Reason::NotAmiPro) => answer("not_amipro"),
        Extracted::Unreadable(Reason::Damaged) => answer("damaged"),
    }
}

/// Never panics outward: a panic inside the reader is "damaged".
pub fn read_caught(bytes: &[u8], max_chars: i32) -> Answer {
    catch_unwind(AssertUnwindSafe(|| read(bytes, max_chars))).unwrap_or_else(|_| answer("damaged"))
}

/// `read_caught` on its own thread with a fixed 8 MB stack (the reader's recursion must not depend on the caller's thread:
/// on Android a binder or worker thread can be much smaller). A reader that does not answer in time is left behind
/// (it holds only its own copy of the bytes) and the file counts as damaged.
pub fn read_threaded(bytes: Vec<u8>, max_chars: i32) -> Answer {
    let (tx, rx) = std::sync::mpsc::channel();
    let spawned = std::thread::Builder::new().name("stack-ami-text".into()).stack_size(8 * 1024 * 1024).spawn(move || {
        let _ = tx.send(read_caught(&bytes, max_chars));
    });
    if spawned.is_err() {
        return answer("damaged");
    }
    match rx.recv_timeout(AMI_TIMEOUT) {
        Ok(done) => done,
        // Still running: the thread cannot be stopped from here, so say so and let the caller end the process.
        Err(std::sync::mpsc::RecvTimeoutError::Timeout) => answer("timeout"),
        Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => answer("damaged"),
    }
}

unsafe fn delete_local(env: Env, obj: Obj) {
    unsafe {
        let delete = jni!(env, DELETE_LOCAL_REF, unsafe extern "C" fn(Env, Obj));
        delete(env, obj);
    }
}

unsafe fn new_string(env: Env, value: &str) -> Obj {
    unsafe {
        let units: Vec<u16> = value.encode_utf16().collect();
        let make = jni!(env, NEW_STRING, unsafe extern "C" fn(Env, *const u16, i32) -> Obj);
        make(env, units.as_ptr(), units.len() as i32)
    }
}

unsafe fn to_java(env: Env, a: &Answer) -> Obj {
    unsafe {
        let find = jni!(env, FIND_CLASS, unsafe extern "C" fn(Env, *const u8) -> Obj);
        let class = find(env, b"java/lang/String\0".as_ptr());
        if class.is_null() {
            return std::ptr::null_mut();
        }
        let new_array = jni!(env, NEW_OBJECT_ARRAY, unsafe extern "C" fn(Env, i32, Obj, Obj) -> Obj);
        let set = jni!(env, SET_OBJECT_ARRAY_ELEMENT, unsafe extern "C" fn(Env, Obj, i32, Obj));
        let array = new_array(env, 3, class, std::ptr::null_mut());
        delete_local(env, class);
        if array.is_null() {
            return std::ptr::null_mut();
        }
        for (i, s) in [a.status, a.title.as_str(), a.text.as_str()].iter().enumerate() {
            let js = new_string(env, s);
            if js.is_null() {
                return std::ptr::null_mut();
            }
            set(env, array, i as i32, js);
            delete_local(env, js);
        }
        array
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_org_stackdocs_app_types_NativeText_amiPro(env: Env, _class: Obj, bytes: Obj, max_chars: i32) -> Obj {
    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
        if env.is_null() || bytes.is_null() {
            return to_java(env, &answer("damaged"));
        }
        let length = jni!(env, GET_ARRAY_LENGTH, unsafe extern "C" fn(Env, Obj) -> i32);
        let region = jni!(env, GET_BYTE_ARRAY_REGION, unsafe extern "C" fn(Env, Obj, i32, i32, *mut i8));
        let n = length(env, bytes);
        if n < 0 {
            return to_java(env, &answer("damaged"));
        }
        if n as usize > Limits::default().max_bytes {
            return to_java(env, &answer("too_large"));
        }
        let mut buffer = vec![0u8; n as usize];
        region(env, bytes, 0, n, buffer.as_mut_ptr() as *mut i8);
        to_java(env, &read_threaded(buffer, max_chars))
    }));
    result.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_org_stackdocs_app_types_NativeText_version(_env: Env, _class: Obj) -> i32 {
    amipro_text::VERSION as i32
}

/// PDF text layer version: bump when extraction output changes, so indexed PDFs are read again.
pub const PDF_TEXT_VERSION: i32 = 1;
/// Time allowed when the caller gives none (zero or negative), and the most any caller can ask for.
pub const PDF_DEFAULT_BUDGET_MS: i32 = 60_000;
const PDF_MAX_BUDGET_MS: i32 = 3_600_000;

/// Per page: Some(text) or None (needs OCR). None overall = not read (damaged, protected, too large, or a panic).
/// Runs on its own thread with a fixed stack; a panic inside is caught.
///
/// `budget_ms` is the caller's remaining time for this file (the Java side passes what is left of its own limit minus a
/// margin); zero or negative means `PDF_DEFAULT_BUDGET_MS`.
pub fn pdf_texts(data: &[u8], max_pages: i32, max_chars: i32, budget_ms: i32) -> Option<Vec<Option<String>>> {
    let cfg = pdftext::Config {
        max_pages: max_pages.max(0) as usize,
        max_chars: if max_chars > 0 { max_chars as usize } else { DEFAULT_MAX_CHARS },
        budget: std::time::Duration::from_millis(
            if budget_ms <= 0 { PDF_DEFAULT_BUDGET_MS } else { budget_ms.clamp(100, PDF_MAX_BUDGET_MS) } as u64,
        ),
    };
    std::thread::scope(|scope| {
        let worker = std::thread::Builder::new()
            .name("stack-pdf-text".into())
            .stack_size(8 * 1024 * 1024)
            .spawn_scoped(scope, || catch_unwind(AssertUnwindSafe(|| pdftext::page_texts(data, &cfg))));
        match worker.map(|w| w.join()) {
            Ok(Ok(Ok(Ok(pages)))) => Some(pages),
            _ => None,
        }
    })
}

unsafe extern "C" {
    fn dup(fd: i32) -> i32;
}

/// The whole file behind a descriptor (read from offset 0, the descriptor's position is not touched), None over the cap.
fn read_fd(fd: i32) -> Option<Vec<u8>> {
    use std::os::unix::fs::FileExt;
    use std::os::unix::io::FromRawFd;
    if fd < 0 {
        return None;
    }
    let copy = unsafe { dup(fd) };
    if copy < 0 {
        return None;
    }
    let file = unsafe { std::fs::File::from_raw_fd(copy) };
    let len = file.metadata().ok()?.len();
    if len > pdftext::MAX_INPUT as u64 {
        return None;
    }
    let mut buffer = vec![0u8; len as usize];
    let mut done = 0usize;
    while done < buffer.len() {
        match file.read_at(&mut buffer[done..], done as u64) {
            Ok(0) => break,
            Ok(n) => done += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return None,
        }
    }
    buffer.truncate(done);
    Some(buffer)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_org_stackdocs_app_types_NativeText_pdfPageTexts(env: Env, _class: Obj, fd: i32, max_pages: i32, max_chars: i32, budget_ms: i32) -> Obj {
    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
        if env.is_null() {
            return std::ptr::null_mut();
        }
        let Some(bytes) = read_fd(fd) else { return std::ptr::null_mut() };
        let Some(pages) = pdf_texts(&bytes, max_pages, max_chars, budget_ms) else { return std::ptr::null_mut() };
        drop(bytes);
        let find = jni!(env, FIND_CLASS, unsafe extern "C" fn(Env, *const u8) -> Obj);
        let class = find(env, b"java/lang/String\0".as_ptr());
        if class.is_null() {
            return std::ptr::null_mut();
        }
        let new_array = jni!(env, NEW_OBJECT_ARRAY, unsafe extern "C" fn(Env, i32, Obj, Obj) -> Obj);
        let set = jni!(env, SET_OBJECT_ARRAY_ELEMENT, unsafe extern "C" fn(Env, Obj, i32, Obj));
        let array = new_array(env, pages.len() as i32, class, std::ptr::null_mut());
        delete_local(env, class);
        if array.is_null() {
            return std::ptr::null_mut();
        }
        for (i, page) in pages.iter().enumerate() {
            if let Some(text) = page {
                let js = new_string(env, text);
                if js.is_null() {
                    return std::ptr::null_mut();
                }
                set(env, array, i as i32, js);
                // One local reference per page would overflow the table (about 512 on ART) on a long document.
                delete_local(env, js);
            }
        }
        array
    }));
    result.unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn Java_org_stackdocs_app_types_NativeText_pdfVersion(_env: Env, _class: Obj) -> i32 {
    PDF_TEXT_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn junk_and_empty_are_not_text() {
        assert_ne!(read_caught(b"", 0).status, "text");
        assert_ne!(read_caught(b"not an ami pro file", 0).status, "text");
        assert_ne!(read_caught(&[0xff, 0xfe, 0x00, 0x01, 0x80], 0).status, "text");
    }

    #[test]
    fn the_threaded_reader_answers_like_the_plain_one() {
        assert_eq!(read_threaded(b"[encrypt]\nsecret".to_vec(), 0).status, "protected");
        assert_ne!(read_threaded(b"junk".to_vec(), -5).status, "text");
    }

    /// Review reproducers for the Ami Pro path (synthetic): deeply nested notes and note storms must end quickly and without
    /// a crash, even on a thread with a small stack (the reader runs on its own 8 MB thread).
    #[test]
    fn ami_pro_hostile_files_are_bounded() {
        let started = std::time::Instant::now();
        for count in [1_000usize, 20_000, 100_000] {
            let note = format!("[ver]\n\t4\n[edoc]\n{}", "<:N1\n".repeat(count));
            let nest = format!("[ver]\n\t4\n[edoc]\n{}", "<:H<*->\n".repeat(count));
            let deep = format!("[ver]\n\t4\n[edoc]\n{}", "<:H<:H".repeat(count));
            for file in [note, nest, deep] {
                let a = read_threaded(file.into_bytes(), 0);
                assert!(["text", "damaged", "too_large", "not_amipro"].contains(&a.status), "{}", a.status);
                assert!(a.text.chars().count() <= DEFAULT_MAX_CHARS);
            }
        }
        assert!(started.elapsed() < std::time::Duration::from_secs(10), "{:?}", started.elapsed());
    }

    /// Review reproducer: a style name of many unterminated `<` (8 tags, 7.9 MB) took over 30 s; it must stay quick.
    #[test]
    fn ami_pro_unterminated_escapes_are_linear() {
        let mut sam = b"[ver]\r\n\t4\r\n".to_vec();
        for _ in 0..8 {
            sam.extend(b"[tag]\r\n\tBody");
            sam.extend(b"<:B".repeat(330_000));
            sam.extend(b"\r\n");
        }
        sam.extend(b"[edoc]\r\n@Body@x<:B\r\n");
        let started = std::time::Instant::now();
        let a = read_threaded(sam, 0);
        assert!(["text", "damaged", "too_large", "not_amipro"].contains(&a.status), "{}", a.status);
        assert!(started.elapsed() < std::time::Duration::from_secs(10), "{:?}", started.elapsed()); // debug build; release 0.2 s, the quadratic one 30 s or more
    }

    #[test]
    fn protected_has_no_text() {
        let a = read_caught(b"[encrypt]\nsecret", 0);
        assert_eq!(a.status, "protected");
        assert!(a.text.is_empty());
    }
}
