//! Bounded streaming exchange, distinct from STACKBK. No executable paths, local
//! database, credentials or device policy can occur in this envelope.
use crate::{Error, Frontier, MAX_JSON, MAX_OPERATIONS, Operation, Result};
use serde::{Deserialize, Serialize};
use std::os::unix::fs::OpenOptionsExt;
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};

pub const MAGIC: &[u8; 8] = b"STACKEX\x01";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Blob {
    pub sha256: String,
    pub length: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Header {
    pub format: String,
    pub version: u32,
    pub library: String,
    pub sender: String,
    pub acknowledged: Frontier,
    pub operations: Vec<Operation>,
    pub blobs: Vec<Blob>,
}

impl Header {
    pub fn validate(&self) -> Result<()> {
        if self.format != "org.stackdocs.exchange" || self.version != 1 {
            return Err(Error::Invalid("unsupported exchange version"));
        }
        stack_archive::uuid(&self.library)?;
        stack_archive::uuid(&self.sender)?;
        super::valid_frontier(&self.acknowledged)?;
        if self.operations.len() > MAX_OPERATIONS || self.blobs.len() > 10000 {
            return Err(Error::Invalid("exchange count limit"));
        }
        for op in &self.operations {
            op.validate()?;
            if op.library != self.library {
                return Err(Error::Invalid("foreign operation in bundle"));
            }
        }
        let mut hashes = std::collections::BTreeSet::new();
        let mut total = 0_u64;
        for blob in &self.blobs {
            if blob.sha256.len() != 64
                || !blob
                    .sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                || !hashes.insert(&blob.sha256)
                || blob.length > stack_archive::FILE_LIMIT
            {
                return Err(Error::Invalid("invalid blob digest, length or duplicate"));
            }
            total = total
                .checked_add(blob.length)
                .ok_or(Error::Invalid("blob length overflow"))?;
        }
        if total > stack_archive::TOTAL_BYTES {
            return Err(Error::Invalid("exchange byte limit"));
        }
        Ok(())
    }
}

/// Write only to caller-owned unpublished storage; caller makes publication durable.
pub fn write(header: &Header, blobs: &Path, out: &mut impl Write) -> Result<()> {
    write_control(header, blobs, out, &stack_archive::Control::default())
}
pub fn write_control(
    header: &Header,
    blobs: &Path,
    out: &mut impl Write,
    control: &stack_archive::Control,
) -> Result<()> {
    control.check()?;
    header.validate()?;
    let bytes = serde_json::to_vec(header)?;
    if bytes.len() > MAX_JSON {
        return Err(Error::Invalid("exchange metadata too large"));
    }
    out.write_all(MAGIC)?;
    out.write_all(&(bytes.len() as u32).to_be_bytes())?;
    out.write_all(&bytes)?;
    for blob in &header.blobs {
        let path = blobs.join(&blob.sha256);
        let digest = stack_archive::transfer(
            &mut stack_archive::read_regular(&path)?,
            out,
            blob.length,
            control,
        )?;
        if digest != blob.sha256 {
            return Err(Error::Invalid("outgoing blob changed"));
        }
    }
    out.flush()?;
    Ok(())
}

/// A fresh caller-owned directory is mandatory. Failed or partial inputs never
/// reach the repository. Caller retains/removes only this owned staging directory.
pub fn read(input: &mut impl Read, destination: &Path) -> Result<Header> {
    read_control(input, destination, &stack_archive::Control::default())
}
pub fn read_control(
    input: &mut impl Read,
    destination: &Path,
    control: &stack_archive::Control,
) -> Result<Header> {
    control.check()?;
    if !destination.is_dir() || fs::read_dir(destination)?.next().is_some() {
        return Err(Error::Invalid("staging must be a fresh empty directory"));
    }
    let mut magic = [0_u8; 8];
    input.read_exact(&mut magic)?;
    if &magic != MAGIC {
        return Err(Error::Invalid("not a Stack exchange bundle"));
    }
    let mut size = [0_u8; 4];
    input.read_exact(&mut size)?;
    let size = u32::from_be_bytes(size) as usize;
    if size > MAX_JSON {
        return Err(Error::Invalid("exchange metadata too large"));
    }
    let mut bytes = vec![0; size];
    input.read_exact(&mut bytes)?;
    let header: Header = serde_json::from_value(stack_archive::strict_json::object(&bytes)?)?;
    header.validate()?;
    for blob in &header.blobs {
        let path = destination.join(&blob.sha256);
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)?;
        let hash = stack_archive::transfer(input, &mut output, blob.length, control)?;
        output.sync_all()?;
        if hash != blob.sha256 {
            return Err(Error::Invalid("missing or corrupt blob"));
        }
    }
    let mut trailing = [0_u8; 1];
    if input.read(&mut trailing)? != 0 {
        return Err(Error::Invalid("trailing exchange bytes"));
    }
    stack_archive::sync_directory(destination)?;
    Ok(header)
}

pub fn publish(header: &Header, blobs: &Path, destination: &Path) -> Result<()> {
    publish_control(
        header,
        blobs,
        destination,
        &stack_archive::Control::default(),
    )
}
pub fn publish_control(
    header: &Header,
    blobs: &Path,
    destination: &Path,
    control: &stack_archive::Control,
) -> Result<()> {
    publish_with(header, blobs, destination, control, &mut |_| Ok(()))
}
/// Hook observes durable publication boundaries; production callers normally use
/// publish_control. A stop before publication leaves only an ignored partial.
pub fn publish_with(
    header: &Header,
    blobs: &Path,
    destination: &Path,
    control: &stack_archive::Control,
    hook: &mut impl FnMut(&str) -> std::io::Result<()>,
) -> Result<()> {
    control.check()?;
    let parent = destination
        .parent()
        .filter(|p| p.is_absolute())
        .ok_or(Error::Invalid("absolute exchange output required"))?;
    stack_archive::read_directory(parent)?;
    let partial: PathBuf = parent.join(format!(".stack-exchange-{}.part", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut out = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&partial)?;
        write_control(header, blobs, &mut out, control)?;
        out.sync_all()?;
        hook("sync_bundle_written")?;
        control.check()?;
        fs::hard_link(&partial, destination)?;
        stack_archive::sync_directory(parent)?;
        hook("sync_bundle_published")?;
        Ok(())
    })();
    let _ = fs::remove_file(partial);
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stream_rejects_truncation_corruption_paths_and_existing_outputs() {
        let dir = tempfile::tempdir().unwrap();
        let blobs = dir.path().join("blobs");
        fs::create_dir(&blobs).unwrap();
        let bytes = b"independently authored immutable original";
        let sha = stack_archive::digest(bytes);
        fs::write(blobs.join(&sha), bytes).unwrap();
        let header = Header {
            format: "org.stackdocs.exchange".into(),
            version: 1,
            library: uuid::Uuid::new_v4().to_string(),
            sender: uuid::Uuid::new_v4().to_string(),
            acknowledged: Frontier::new(),
            operations: vec![],
            blobs: vec![Blob {
                sha256: sha.clone(),
                length: bytes.len() as u64,
            }],
        };
        let output = dir.path().join("exchange.stackexchange");
        publish(&header, &blobs, &output).unwrap();
        assert!(publish(&header, &blobs, &output).is_err());
        let all = fs::read(&output).unwrap();
        for cut in [0, 8, 11, all.len() - 1] {
            let stage = dir.path().join(format!("cut-{cut}"));
            fs::create_dir(&stage).unwrap();
            assert!(read(&mut &all[..cut], &stage).is_err());
        }
        let mut corrupt = all.clone();
        *corrupt.last_mut().unwrap() ^= 1;
        let stage = dir.path().join("corrupt");
        fs::create_dir(&stage).unwrap();
        assert!(read(&mut &corrupt[..], &stage).is_err());
        let stage = dir.path().join("good");
        fs::create_dir(&stage).unwrap();
        read(&mut &all[..], &stage).unwrap();
        assert_eq!(fs::read(stage.join(sha)).unwrap(), bytes);
        let mut bad = header;
        bad.blobs[0].sha256 = "../../private".into();
        assert!(bad.validate().is_err());
    }
}
