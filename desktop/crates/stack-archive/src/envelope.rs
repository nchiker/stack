use crate::{
    Error, FILE_LIMIT, MAGIC, MANIFEST_LIMIT, Manifest, Result, original_path, read_regular,
    sync_directory,
};
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::{
    Arc,
    atomic::{AtomicBool, AtomicU64, Ordering},
};

#[derive(Clone, Default)]
pub struct Control {
    canceled: Arc<AtomicBool>,
    bytes: Arc<AtomicU64>,
}

impl Control {
    pub fn cancel(&self) {
        self.canceled.store(true, Ordering::Release);
    }
    pub fn check(&self) -> Result<()> {
        if self.canceled.load(Ordering::Acquire) {
            Err(Error::Canceled)
        } else {
            Ok(())
        }
    }
    pub fn transferred_bytes(&self) -> u64 {
        self.bytes.load(Ordering::Relaxed)
    }
}

pub trait SourceVerifier {
    /// Verify actual bytes/type; PDF sources must have the exact original page count.
    fn verify(&self, path: &Path, expected_mime: &str, pdf_pages: Option<u32>) -> Result<()>;
}

pub struct StagedArchive {
    pub manifest: Manifest,
    pub source_manifest: Vec<u8>,
    pub source_hash: String,
    pub directory: PathBuf,
}

struct IncompleteDirectory(PathBuf, bool);
impl Drop for IncompleteDirectory {
    fn drop(&mut self) {
        if self.1 {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}

pub fn read_into(
    input: &mut impl Read,
    directory: &Path,
    verifier: &dyn SourceVerifier,
    control: &Control,
) -> Result<StagedArchive> {
    stage(input, directory, verifier, control, false)
}

/// Reads an encrypted backup (outer envelope, inner archive of format 4) into a staging directory. The staging
/// directory is removed unless the envelope's final chunk verified, so a caller may commit the result as soon as
/// this returns `Ok`; nothing from a damaged, truncated or extended file survives.
pub fn read_encrypted_into(
    input: impl Read,
    secret: crate::crypto::Secret<'_>,
    directory: &Path,
    verifier: &dyn SourceVerifier,
    control: &Control,
) -> Result<StagedArchive> {
    let mut reader = crate::crypto::DecryptReader::open(input, secret)?;
    let staged = stage(&mut reader, directory, verifier, control, true)?;
    if let Err(error) = reader.finish() {
        let _ = fs::remove_dir_all(directory);
        return Err(error);
    }
    Ok(staged)
}

fn stage(
    input: &mut impl Read,
    directory: &Path,
    verifier: &dyn SourceVerifier,
    control: &Control,
    require_v4: bool,
) -> Result<StagedArchive> {
    control.check()?;
    fs::create_dir(directory)?;
    fs::set_permissions(directory, fs::Permissions::from_mode(0o700))?;
    let mut guard = IncompleteDirectory(directory.to_owned(), true);
    let mut magic = [0; 8];
    input.read_exact(&mut magic)?;
    if &magic != MAGIC {
        return Err(Error::Invalid(
            "Choose a Stack .stackbackup file, formats 1 to 4",
        ));
    }
    let mut length = [0; 4];
    input.read_exact(&mut length)?;
    let length = i32::from_be_bytes(length);
    if length < 2 || length as usize > MANIFEST_LIMIT {
        return Err(Error::Invalid("Manifest length must be 2 bytes–32 MiB"));
    }
    let mut expected = [0; 32];
    input.read_exact(&mut expected)?;
    let mut source_manifest = vec![0; length as usize];
    input.read_exact(&mut source_manifest)?;
    control.check()?;
    if Sha256::digest(&source_manifest).as_slice() != expected {
        return Err(Error::Invalid("Manifest checksum does not match"));
    }
    let source_hash = crate::digest(&source_manifest);
    let manifest = Manifest::parse(&source_manifest)?;
    if require_v4 && manifest.source_version() != 4 {
        return Err(Error::Invalid(
            "An encrypted backup must contain archive format 4",
        ));
    }
    fs::create_dir(directory.join("originals"))?;
    fs::set_permissions(
        directory.join("originals"),
        fs::Permissions::from_mode(0o700),
    )?;
    for entry in manifest.rows("files") {
        control.check()?;
        let declared = entry["path"].as_str().expect("validated path");
        let mut size = [0; 2];
        input.read_exact(&mut size)?;
        let size = usize::from(u16::from_be_bytes(size));
        if !(1..=160).contains(&size) {
            return Err(Error::Invalid("Invalid original path byte length"));
        }
        let mut name = vec![0; size];
        input.read_exact(&mut name)?;
        let path = std::str::from_utf8(&name)
            .map_err(|_| Error::Invalid("Original path must be UTF-8"))?;
        let preview = path.starts_with("previews/");
        let document = if preview {
            crate::links::preview_id(path)?
        } else {
            original_path(path)?.0
        };
        let mut length = [0; 8];
        input.read_exact(&mut length)?;
        let length = i64::from_be_bytes(length);
        if path != declared || Some(length) != entry["length"].as_i64() {
            return Err(Error::Invalid(
                "Original entry order/path/length disagrees with manifest",
            ));
        }
        let parent = if preview {
            directory.join("previews")
        } else {
            directory.join("originals").join(document)
        };
        if !parent.exists() {
            fs::create_dir(&parent)?;
            fs::set_permissions(&parent, fs::Permissions::from_mode(0o700))?;
        }
        crate::read_directory(&parent)?;
        reserve_space(&parent, length as u64)?;
        let target = directory.join(path);
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&target)?;
        let hash = transfer(input, &mut output, length as u64, control)?;
        output.flush()?;
        output.sync_all()?;
        if hash != entry["sha256"].as_str().expect("validated digest") {
            return Err(Error::Invalid("Original checksum does not match"));
        }
        fs::set_permissions(&target, fs::Permissions::from_mode(0o400))?;
        sync_directory(&parent)?;
    }
    let mut trailing = [0];
    if input.read(&mut trailing)? != 0 {
        return Err(Error::Invalid(
            "Unexpected or duplicated data after archive",
        ));
    }
    for entry in manifest.rows("files") {
        control.check()?;
        let path = entry["path"].as_str().expect("validated path");
        if path.starts_with("previews/") {
            let mut head = [0u8; 5];
            let mut file = read_regular(&directory.join(path))?;
            if file.read_exact(&mut head).is_err() || &head != b"%PDF-" {
                return Err(Error::Invalid("A preview is not a PDF"));
            }
            drop(file);
            verifier.verify(&directory.join(path), "application/pdf", None)?;
            continue;
        }
        let (id, filename) = original_path(path)?;
        let doc = manifest
            .rows("documents")
            .iter()
            .find(|d| d["id"] == id)
            .expect("validated owner");
        let mime = if filename.ends_with(".jpg") {
            "image/jpeg"
        } else {
            doc["mime"].as_str().expect("validated MIME")
        };
        let pages = if mime == "application/pdf" {
            Some(doc["initial_page_count"].as_u64().expect("validated count") as u32)
        } else {
            None
        };
        verifier.verify(&directory.join(path), mime, pages)?;
    }
    sync_directory(&directory.join("originals"))?;
    if directory.join("previews").exists() {
        sync_directory(&directory.join("previews"))?;
    }
    sync_directory(directory)?;
    guard.1 = false;
    Ok(StagedArchive {
        manifest,
        source_manifest,
        source_hash,
        directory: directory.to_owned(),
    })
}

pub fn write(
    manifest: &Manifest,
    directory: &Path,
    output: &mut impl Write,
    control: &Control,
) -> Result<()> {
    control.check()?;
    let bytes = manifest.bytes()?;
    output.write_all(MAGIC)?;
    output.write_all(&(bytes.len() as i32).to_be_bytes())?;
    output.write_all(&Sha256::digest(&bytes))?;
    output.write_all(&bytes)?;
    for entry in manifest.rows("files") {
        control.check()?;
        let path = entry["path"].as_str().expect("validated path");
        if path.starts_with("previews/") {
            crate::links::preview_id(path)?;
            crate::read_directory(&directory.join("previews"))?;
        } else {
            let (doc, _) = original_path(path)?;
            crate::read_directory(&directory.join("originals"))?;
            crate::read_directory(&directory.join("originals").join(doc))?;
        }
        let length = entry["length"].as_u64().expect("validated length");
        let mut input = read_regular(&directory.join(path))?;
        if input.metadata()?.len() != length {
            return Err(Error::Invalid("Pinned original length changed"));
        }
        output.write_all(&(path.len() as u16).to_be_bytes())?;
        output.write_all(path.as_bytes())?;
        output.write_all(&(length as i64).to_be_bytes())?;
        let hash = transfer(&mut input, output, length, control)?;
        let mut end = [0];
        if hash != entry["sha256"].as_str().expect("validated digest") || input.read(&mut end)? != 0
        {
            return Err(Error::Invalid("Pinned original bytes changed"));
        }
    }
    control.check()?;
    output.flush()?;
    Ok(())
}

pub fn file_hash(path: &Path, expected_length: u64, control: &Control) -> Result<String> {
    let mut source = read_regular(path)?;
    if source.metadata()?.len() != expected_length || expected_length > FILE_LIMIT {
        return Err(Error::Invalid("Original length changed"));
    }
    let hash = transfer(&mut source, &mut std::io::sink(), expected_length, control)?;
    let mut end = [0];
    if source.read(&mut end)? != 0 {
        return Err(Error::Invalid("Original grew during validation"));
    }
    Ok(hash)
}

pub fn reserve_space(directory: &Path, bytes: u64) -> Result<()> {
    let stats = rustix::fs::statvfs(directory).map_err(std::io::Error::from)?;
    let available = u128::from(stats.f_bavail) * u128::from(stats.f_frsize);
    if available < u128::from(bytes) + 16 * 1024 * 1024 {
        return Err(Error::Invalid(
            "Not enough local storage; 16 MiB reserve is required",
        ));
    }
    Ok(())
}

pub fn transfer(
    input: &mut impl Read,
    output: &mut impl Write,
    length: u64,
    control: &Control,
) -> Result<String> {
    let mut remaining = length;
    let mut hash = Sha256::new();
    let mut buffer = [0; 64 * 1024];
    while remaining > 0 {
        control.check()?;
        let wanted = remaining.min(buffer.len() as u64) as usize;
        let count = input.read(&mut buffer[..wanted])?;
        if count == 0 {
            return Err(Error::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "Incomplete original",
            )));
        }
        output.write_all(&buffer[..count])?;
        hash.update(&buffer[..count]);
        remaining -= count as u64;
        control.bytes.fetch_add(count as u64, Ordering::Relaxed);
    }
    Ok(format!("{:x}", hash.finalize()))
}
