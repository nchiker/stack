//! Android-compatible Stack archive metadata and streaming envelope. No UI or database.
mod envelope;
pub mod strict_json;
pub mod synthetic;
pub mod validation;

pub use envelope::{
    Control, SourceVerifier, StagedArchive, file_hash, read_into, reserve_space, transfer, write,
};
pub use validation::{Manifest, clean_name, java_case_key, utf16_len};

pub const MAGIC: &[u8; 8] = b"STACKBK\x01";
pub const MANIFEST_LIMIT: usize = 32 * 1024 * 1024;
pub const FILE_LIMIT: u64 = 256 * 1024 * 1024;
pub const TOTAL_BYTES: u64 = 8 * 1024 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Invalid(&'static str),
    #[error("Archive metadata is invalid: {0}")]
    Metadata(String),
    #[error("Archive I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("Archive JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("Original could not be validated: {0}")]
    Source(String),
    #[error("Operation canceled before commit")]
    Canceled,
}

pub type Result<T> = std::result::Result<T, Error>;

pub fn digest(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(bytes))
}

pub fn uuid(value: &str) -> Result<()> {
    if uuid::Uuid::parse_str(value).is_ok_and(|id| id.to_string() == value) {
        Ok(())
    } else {
        Err(Error::Invalid("Expected a canonical lowercase UUID"))
    }
}

pub fn original_path(path: &str) -> Result<(&str, &str)> {
    let parts: Vec<_> = path.split('/').collect();
    if parts.len() != 3 || parts[0] != "originals" {
        return Err(Error::Invalid("Unsupported original path"));
    }
    uuid(parts[1])?;
    if parts[2] != "source" {
        let name = parts[2]
            .strip_suffix(".jpg")
            .ok_or(Error::Invalid("Unsupported original filename"))?;
        uuid(name)?;
    }
    Ok((parts[1], parts[2]))
}

pub fn read_regular(path: &std::path::Path) -> Result<std::fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    let file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags((rustix::fs::OFlags::NOFOLLOW | rustix::fs::OFlags::NONBLOCK).bits() as i32)
        .open(path)?;
    if !file.metadata()?.is_file() {
        return Err(Error::Invalid(
            "Expected a regular file, not a link or directory",
        ));
    }
    Ok(file)
}

pub fn sync_directory(path: &std::path::Path) -> Result<()> {
    read_directory(path)?.sync_all()?;
    Ok(())
}

pub fn read_directory(path: &std::path::Path) -> Result<std::fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    Ok(std::fs::OpenOptions::new()
        .read(true)
        .custom_flags((rustix::fs::OFlags::NOFOLLOW | rustix::fs::OFlags::DIRECTORY).bits() as i32)
        .open(path)?)
}
