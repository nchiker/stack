//! Encrypted backup envelope (STACKEN v1): streaming decrypting reader and a writer that takes an
//! injectable random source. Spec: docs/STACK_ARCHIVE_FORMAT.md, "Format 4 and the encrypted envelope".
//! Primitives come from RustCrypto (aes-gcm, hmac, pbkdf2, sha2); nothing is hand-rolled.
//!
//! Commit rule: `DecryptReader` hands out plaintext chunk by chunk, each already tag-checked, but the
//! stream only counts as authentic and complete once [`DecryptReader::finish`] succeeds (the final chunk's
//! tag verified and nothing follows it). Stage while reading; commit only after `finish`.
use crate::{Error, Result};
use aes_gcm::aead::generic_array::GenericArray;
use aes_gcm::{AeadInPlace, Aes256Gcm, KeyInit};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::io::{self, Read, Write};
use unicode_normalization::UnicodeNormalization;
use zeroize::{Zeroize, Zeroizing};

pub const ENVELOPE_MAGIC: &[u8; 8] = b"STACKEN\x01";
pub const CHUNK: usize = 65_536;
pub const TAG: usize = 16;
pub const KDF_PBKDF2: u8 = 1;
pub const SLOT_PASSPHRASE: u8 = 1;
pub const SLOT_RECOVERY: u8 = 2;
pub const MIN_ITERATIONS: u32 = 600_000;
pub const MAX_ITERATIONS: u32 = 2_000_000;
pub const MAX_CHUNK_INDEX: u64 = 131_200;
const SALT: usize = 16;
const WRAPPED: usize = 32 + TAG;
const RECOVERY_LABEL: &[u8] = b"stack-backup-recovery-v1";

/// Envelope failures. Header and chunk failures are "damaged"; a wrong secret is reported together with damage
/// because AES-GCM cannot tell them apart.
#[derive(Debug, thiserror::Error)]
pub enum EnvelopeError {
    #[error("The backup is damaged: {0}")]
    Damaged(&'static str),
    #[error("Wrong passphrase or recovery key, or damaged.")]
    WrongKey,
    #[error("{0}")]
    NoKey(&'static str),
}

fn damaged(why: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, EnvelopeError::Damaged(why))
}

/// The 32-byte recovery key. Never printed (Debug is redacted) and zeroed on drop.
pub struct RecoveryKey([u8; 32]);

impl RecoveryKey {
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }
    /// Hex, case-insensitive, whitespace and dashes ignored, exactly 32 bytes.
    pub fn parse(text: &str) -> Result<Self> {
        const MESSAGE: &str = "The recovery key is 64 hex characters.";
        let mut clean: Zeroizing<String> = Zeroizing::new(String::with_capacity(text.len()));
        clean.extend(text.chars().filter(|c| !c.is_whitespace() && *c != '-'));
        if clean.len() != 64 || !clean.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(Error::Invalid(MESSAGE));
        }
        let mut out = [0u8; 32];
        for (i, pair) in clean.as_bytes().chunks(2).enumerate() {
            let digit = |b: u8| char::from(b).to_digit(16).expect("checked hex") as u8;
            out[i] = digit(pair[0]) << 4 | digit(pair[1]);
        }
        Ok(Self(out))
    }
}
impl Drop for RecoveryKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}
impl std::fmt::Debug for RecoveryKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("RecoveryKey(<redacted>)")
    }
}

/// The secret a reader holds; only the slot type matching it is tried.
pub enum Secret<'a> {
    Passphrase(&'a str),
    Recovery(&'a RecoveryKey),
}

/// PBKDF2-HMAC-SHA256 over the exact NFC UTF-8 bytes (no trimming; empty is refused), 32 bytes.
pub fn passphrase_slot_key(
    passphrase: &str,
    salt: &[u8],
    iterations: u32,
) -> Result<Zeroizing<[u8; 32]>> {
    if passphrase.is_empty() {
        return Err(Error::Invalid("The passphrase is empty."));
    }
    if !(MIN_ITERATIONS..=MAX_ITERATIONS).contains(&iterations) {
        return Err(Error::Invalid("Unsupported iteration count."));
    }
    let mut normalized: Zeroizing<String> = Zeroizing::new(String::with_capacity(passphrase.len()));
    normalized.extend(passphrase.nfc());
    let password: &[u8] = normalized.as_bytes();
    let mut key = Zeroizing::new([0u8; 32]);
    pbkdf2::pbkdf2_hmac::<Sha256>(password, salt, iterations, &mut *key);
    Ok(key)
}

/// HMAC-SHA256(recovery key, "stack-backup-recovery-v1" || salt).
pub fn recovery_slot_key(key: &RecoveryKey, salt: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(&key.0).expect("HMAC takes any key length");
    mac.update(RECOVERY_LABEL);
    mac.update(salt);
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&mac.finalize().into_bytes());
    out
}

fn slot_aad(slot_type: u8, salt: &[u8], iterations: u32) -> Vec<u8> {
    let mut aad = Vec::with_capacity(30);
    aad.extend_from_slice(ENVELOPE_MAGIC);
    aad.push(KDF_PBKDF2);
    aad.extend_from_slice(&iterations.to_be_bytes());
    aad.extend_from_slice(salt);
    aad.push(slot_type);
    aad
}

fn chunk_nonce(index: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&index.to_be_bytes());
    nonce
}

fn chunk_aad(header_hash: &[u8; 32], index: u64, last: bool) -> [u8; 41] {
    let mut aad = [0u8; 41];
    aad[..32].copy_from_slice(header_hash);
    aad[32..40].copy_from_slice(&index.to_be_bytes());
    aad[40] = u8::from(last);
    aad
}

fn aes(key: &[u8; 32]) -> Aes256Gcm {
    Aes256Gcm::new(GenericArray::from_slice(key))
}

// ---- reading -------------------------------------------------------------------------------------------

/// Streaming decrypting reader over the outer envelope; yields the inner archive stream.
pub struct DecryptReader<R: Read> {
    inner: R,
    cipher: Aes256Gcm,
    header_hash: [u8; 32],
    plain: Vec<u8>,
    pos: usize,
    index: u64,
    peeked: Option<u8>,
    last: bool,
    failed: bool,
}

struct HashedRead<'a, R: Read> {
    inner: &'a mut R,
    hash: Sha256,
}
impl<R: Read> HashedRead<'_, R> {
    fn take<const N: usize>(&mut self) -> Result<[u8; N]> {
        let mut bytes = [0u8; N];
        read_header(self.inner, &mut bytes)?;
        self.hash.update(bytes);
        Ok(bytes)
    }
}
fn read_header(inner: &mut impl Read, bytes: &mut [u8]) -> Result<()> {
    inner.read_exact(bytes).map_err(|error| {
        if error.kind() == io::ErrorKind::UnexpectedEof {
            Error::Envelope(EnvelopeError::Damaged("the header is cut short"))
        } else {
            Error::Io(error)
        }
    })
}

impl<R: Read> DecryptReader<R> {
    /// Reads and checks the header (bounds, duplicate slots, `header_hash`), derives the one slot key for the
    /// given secret, unwraps the data key and returns a reader positioned at chunk 0.
    pub fn open(mut inner: R, secret: Secret<'_>) -> Result<Self> {
        let mut head = HashedRead {
            inner: &mut inner,
            hash: Sha256::new(),
        };
        if &head.take::<8>()? != ENVELOPE_MAGIC {
            return Err(Error::Envelope(EnvelopeError::Damaged(
                "this is not an encrypted Stack backup",
            )));
        }
        let kdf = head.take::<1>()?[0];
        let iterations = u32::from_be_bytes(head.take::<4>()?);
        let salt = head.take::<SALT>()?;
        let count = head.take::<1>()?[0];
        if kdf != KDF_PBKDF2
            || !(MIN_ITERATIONS..=MAX_ITERATIONS).contains(&iterations)
            || !(1..=4).contains(&count)
        {
            return Err(Error::Envelope(EnvelopeError::Damaged(
                "the header is damaged or unsupported",
            )));
        }
        let mut slots: Vec<(u8, [u8; 12], [u8; WRAPPED])> = Vec::new();
        for _ in 0..count {
            let slot_type = head.take::<1>()?[0];
            let nonce = head.take::<12>()?;
            let wrapped = head.take::<WRAPPED>()?;
            if slots.iter().any(|slot| slot.0 == slot_type) {
                return Err(Error::Envelope(EnvelopeError::Damaged(
                    "the header repeats a key slot",
                )));
            }
            slots.push((slot_type, nonce, wrapped));
        }
        let expected: [u8; 32] = head.hash.finalize_reset().into();
        let mut stored = [0u8; 32];
        read_header(head.inner, &mut stored)?;
        if expected != stored {
            return Err(Error::Envelope(EnvelopeError::Damaged(
                "the header checksum does not match",
            )));
        }
        let (wanted, what) = match secret {
            Secret::Passphrase(_) => (
                SLOT_PASSPHRASE,
                "This backup has no key for that passphrase.",
            ),
            Secret::Recovery(_) => (
                SLOT_RECOVERY,
                "This backup has no key for that recovery key.",
            ),
        };
        let (_, nonce, wrapped) = slots
            .iter()
            .find(|slot| slot.0 == wanted)
            .ok_or(Error::Envelope(EnvelopeError::NoKey(what)))?;
        let slot_key = match secret {
            Secret::Passphrase(text) => passphrase_slot_key(text, &salt, iterations)?,
            Secret::Recovery(key) => recovery_slot_key(key, &salt),
        };
        let mut buffer = Zeroizing::new(wrapped.to_vec());
        aes(&slot_key)
            .decrypt_in_place(
                GenericArray::from_slice(nonce),
                &slot_aad(wanted, &salt, iterations),
                &mut *buffer,
            )
            .map_err(|_| Error::Envelope(EnvelopeError::WrongKey))?;
        let data_key: Zeroizing<[u8; 32]> = Zeroizing::new(
            buffer[..]
                .try_into()
                .map_err(|_| Error::Envelope(EnvelopeError::WrongKey))?,
        );
        Ok(Self {
            inner,
            cipher: aes(&data_key),
            header_hash: stored,
            plain: Vec::new(),
            pos: 0,
            index: 0,
            peeked: None,
            last: false,
            failed: false,
        })
    }

    /// True once the final chunk's tag has verified (and nothing followed it).
    pub fn is_final_verified(&self) -> bool {
        self.last && self.pos >= self.plain.len()
    }

    /// Drains the stream and verifies the final chunk. Call this before committing anything staged from it.
    pub fn finish(&mut self) -> Result<()> {
        let mut sink = [0u8; 8192];
        while self.read(&mut sink)? > 0 {}
        if !self.last {
            return Err(Error::Envelope(EnvelopeError::Damaged("it was cut short")));
        }
        Ok(())
    }

    fn read_block(&mut self, block: &mut [u8]) -> io::Result<usize> {
        let mut got = 0;
        if let Some(byte) = self.peeked.take() {
            block[0] = byte;
            got = 1;
        }
        while got < block.len() {
            match self.inner.read(&mut block[got..]) {
                Ok(0) => break,
                Ok(n) => got += n,
                Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        Ok(got)
    }

    fn fill(&mut self) -> io::Result<bool> {
        if self.failed {
            return Err(damaged("an earlier read failed"));
        }
        if self.last {
            return Ok(false);
        }
        if self.index > MAX_CHUNK_INDEX {
            return Err(damaged("too many blocks"));
        }
        let mut block = vec![0u8; CHUNK + TAG];
        let n = self.read_block(&mut block)?;
        let mut is_last = n < block.len();
        if !is_last {
            let mut one = [0u8; 1];
            loop {
                match self.inner.read(&mut one) {
                    Ok(0) => is_last = true,
                    Ok(_) => self.peeked = Some(one[0]),
                    Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                    Err(error) => return Err(error),
                }
                break;
            }
        }
        if n < TAG + 1 {
            return Err(damaged("it was cut short"));
        }
        block.truncate(n);
        self.cipher
            .decrypt_in_place(
                GenericArray::from_slice(&chunk_nonce(self.index)),
                &chunk_aad(&self.header_hash, self.index, is_last),
                &mut block,
            )
            .map_err(|_| damaged("a block failed its check"))?;
        self.plain = block;
        self.pos = 0;
        self.index += 1;
        self.last = is_last;
        Ok(true)
    }
}

impl<R: Read> Read for DecryptReader<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() {
            return Ok(0);
        }
        if self.failed {
            return Err(damaged("an earlier read failed"));
        }
        while self.pos >= self.plain.len() {
            match self.fill() {
                Ok(true) => {}
                Ok(false) => return Ok(0),
                Err(error) => {
                    self.failed = true;
                    return Err(error);
                }
            }
        }
        let n = out.len().min(self.plain.len() - self.pos);
        out[..n].copy_from_slice(&self.plain[self.pos..self.pos + n]);
        self.pos += n;
        Ok(n)
    }
}

// ---- writing -------------------------------------------------------------------------------------------

/// A key slot to write: its type and the already derived 32-byte slot key.
pub struct Slot<'a> {
    pub slot_type: u8,
    pub key: &'a [u8; 32],
}

/// Writes the header (data key and nonces drawn from `random` in order: data key, then one nonce per slot)
/// and returns a writer for the body. The caller derives slot keys with [`passphrase_slot_key`] and
/// [`recovery_slot_key`]. Production use needs a real random source; tests pass a counter.
pub fn encrypt_to<W: Write>(
    mut out: W,
    salt: &[u8; 16],
    iterations: u32,
    slots: &[Slot<'_>],
    random: &mut dyn FnMut(&mut [u8]),
) -> Result<EncryptWriter<W>> {
    if !(MIN_ITERATIONS..=MAX_ITERATIONS).contains(&iterations) || !(1..=4).contains(&slots.len()) {
        return Err(Error::Invalid("Bad envelope parameters."));
    }
    if slots
        .iter()
        .enumerate()
        .any(|(i, a)| slots[..i].iter().any(|b| b.slot_type == a.slot_type))
    {
        return Err(Error::Invalid("Duplicate key slot type."));
    }
    let mut data_key = Zeroizing::new([0u8; 32]);
    random(&mut *data_key);
    let mut header = Vec::new();
    header.extend_from_slice(ENVELOPE_MAGIC);
    header.push(KDF_PBKDF2);
    header.extend_from_slice(&iterations.to_be_bytes());
    header.extend_from_slice(salt);
    header.push(slots.len() as u8);
    for slot in slots {
        let mut nonce = [0u8; 12];
        random(&mut nonce);
        let mut wrapped = Zeroizing::new(data_key.to_vec());
        aes(slot.key)
            .encrypt_in_place(
                GenericArray::from_slice(&nonce),
                &slot_aad(slot.slot_type, salt, iterations),
                &mut *wrapped,
            )
            .map_err(|_| Error::Invalid("Encryption failed."))?;
        header.push(slot.slot_type);
        header.extend_from_slice(&nonce);
        header.extend_from_slice(&wrapped);
    }
    let hash: [u8; 32] = Sha256::digest(&header).into();
    out.write_all(&header)?;
    out.write_all(&hash)?;
    Ok(EncryptWriter {
        out,
        cipher: aes(&data_key),
        header_hash: hash,
        buffer: Vec::with_capacity(CHUNK),
        index: 0,
        finished: false,
    })
}

/// Fills `buffer` from the operating system's random source (the `getrandom` call). Fails rather than falling back.
pub fn os_random(buffer: &mut [u8]) -> io::Result<()> {
    let mut filled = 0;
    while filled < buffer.len() {
        match rustix::rand::getrandom(&mut buffer[filled..], rustix::rand::GetRandomFlags::empty())
        {
            Ok(0) => {
                return Err(io::Error::other(
                    "The system random source returned nothing.",
                ));
            }
            Ok(n) => filled += n,
            Err(rustix::io::Errno::INTR) => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

/// Starts a key-only envelope (one recovery slot, `slot_count` 1) with a fresh random salt and data key from the
/// operating system. The slot key is derived here and zeroed on return.
pub fn encrypt_with_recovery_key<W: Write>(out: W, key: &RecoveryKey) -> Result<EncryptWriter<W>> {
    let mut failure: Option<io::Error> = None;
    let mut random = |buffer: &mut [u8]| {
        if failure.is_none()
            && let Err(error) = os_random(buffer)
        {
            buffer.fill(0);
            failure = Some(error);
        }
    };
    let mut salt = [0u8; SALT];
    random(&mut salt);
    let slot_key = recovery_slot_key(key, &salt);
    let slots = [Slot {
        slot_type: SLOT_RECOVERY,
        key: &slot_key,
    }];
    let writer = encrypt_to(out, &salt, MIN_ITERATIONS, &slots, &mut random);
    match failure {
        Some(error) => Err(error.into()),
        None => writer,
    }
}

/// Holds back one chunk so the final one can carry the last flag. Call [`EncryptWriter::finish`].
pub struct EncryptWriter<W: Write> {
    out: W,
    cipher: Aes256Gcm,
    header_hash: [u8; 32],
    buffer: Vec<u8>,
    index: u64,
    finished: bool,
}

impl<W: Write> EncryptWriter<W> {
    fn emit(&mut self, last: bool) -> Result<()> {
        if self.index > MAX_CHUNK_INDEX {
            return Err(Error::Invalid("The backup is too large."));
        }
        self.cipher
            .encrypt_in_place(
                GenericArray::from_slice(&chunk_nonce(self.index)),
                &chunk_aad(&self.header_hash, self.index, last),
                &mut self.buffer,
            )
            .map_err(|_| Error::Invalid("Encryption failed."))?;
        self.out.write_all(&self.buffer)?;
        self.buffer.clear();
        self.index += 1;
        Ok(())
    }
    /// Writes the final chunk and returns the sink. An empty body is refused.
    pub fn finish(mut self) -> Result<W> {
        if self.buffer.is_empty() {
            return Err(Error::Invalid("An empty backup cannot be encrypted."));
        }
        self.emit(true)?;
        self.finished = true;
        self.out.flush()?;
        Ok(self.out)
    }
}

impl<W: Write> Write for EncryptWriter<W> {
    fn write(&mut self, mut bytes: &[u8]) -> io::Result<usize> {
        let total = bytes.len();
        if self.finished {
            return Err(io::Error::other("The envelope is finished."));
        }
        while !bytes.is_empty() {
            if self.buffer.len() == CHUNK {
                self.emit(false)
                    .map_err(|e| io::Error::other(e.to_string()))?;
            }
            let n = bytes.len().min(CHUNK - self.buffer.len());
            self.buffer.extend_from_slice(&bytes[..n]);
            bytes = &bytes[n..];
        }
        Ok(total)
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
