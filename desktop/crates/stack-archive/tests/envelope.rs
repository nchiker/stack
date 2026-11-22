//! Encrypted envelope: the shared vector and the tamper cases. No real key is used anywhere.
use aes_gcm::aead::generic_array::GenericArray;
use aes_gcm::{AeadInPlace, Aes256Gcm, KeyInit};
use sha2::{Digest, Sha256};
use stack_archive::crypto::*;
use stack_archive::{Error, crypto};
use std::io::{Cursor, Read};
use std::sync::OnceLock;

fn counter(start: u8) -> impl FnMut(&mut [u8]) {
    let mut n = start;
    move |bytes: &mut [u8]| {
        for b in bytes {
            *b = n;
            n = n.wrapping_add(1);
        }
    }
}
fn body(length: usize) -> Vec<u8> {
    (0..length).map(|i| (i * 31 + 7) as u8).collect()
}
fn salt() -> [u8; 16] {
    let mut s = [0u8; 16];
    counter(100)(&mut s);
    s
}
fn recovery() -> RecoveryKey {
    let mut k = [0u8; 32];
    counter(200)(&mut k);
    RecoveryKey::from_bytes(k)
}
const PASS: &str = "correct horse";
fn pass_key() -> &'static [u8; 32] {
    static KEY: OnceLock<[u8; 32]> = OnceLock::new();
    KEY.get_or_init(|| *passphrase_slot_key(PASS, &salt(), MIN_ITERATIONS).unwrap())
}
fn rec_key() -> [u8; 32] {
    *recovery_slot_key(&recovery(), &salt())
}
fn encrypt(data: &[u8], slots: &[Slot<'_>], start: u8) -> Vec<u8> {
    let mut out = Vec::new();
    let mut w = encrypt_to(
        &mut out,
        &salt(),
        MIN_ITERATIONS,
        slots,
        &mut counter(start),
    )
    .unwrap();
    std::io::Write::write_all(&mut w, data).unwrap();
    w.finish().unwrap();
    out
}
fn both(data: &[u8]) -> Vec<u8> {
    let rk = rec_key();
    encrypt(
        data,
        &[
            Slot {
                slot_type: SLOT_PASSPHRASE,
                key: pass_key(),
            },
            Slot {
                slot_type: SLOT_RECOVERY,
                key: &rk,
            },
        ],
        1,
    )
}
fn open_recovery(file: &[u8]) -> Result<Vec<u8>, Error> {
    let key = recovery();
    let mut r = DecryptReader::open(Cursor::new(file), Secret::Recovery(&key))?;
    let mut out = Vec::new();
    r.read_to_end(&mut out)?;
    r.finish()?;
    Ok(out)
}
fn open_pass(file: &[u8], pass: &str) -> Result<Vec<u8>, Error> {
    let mut r = DecryptReader::open(Cursor::new(file), Secret::Passphrase(pass))?;
    let mut out = Vec::new();
    r.read_to_end(&mut out)?;
    r.finish()?;
    Ok(out)
}
fn damaged<T: std::fmt::Debug>(r: Result<T, Error>) -> bool {
    matches!(r, Err(Error::Envelope(EnvelopeError::Damaged(_))))
}
const HEAD: usize = 8 + 1 + 4 + 16 + 1 + 2 * 61 + 32;
const BLOCK: usize = CHUNK + TAG;

#[test]
fn shared_vector() {
    let file = both(&body(70_000));
    assert_eq!(file.len(), 70_216);
    assert_eq!(
        format!("{:x}", Sha256::digest(&file)),
        "d5ca2409e2a27e2ca27a8fbd0322c4bdc8afaff0d04fc18beb40ff6cc27d0c8f"
    );
    assert_eq!(open_recovery(&file).unwrap(), body(70_000));
    assert_eq!(open_pass(&file, PASS).unwrap(), body(70_000));
}

#[test]
fn round_trips_at_chunk_edges() {
    for size in [1, CHUNK, CHUNK + 1] {
        let file = both(&body(size));
        assert_eq!(
            file.len(),
            HEAD + size + TAG * size.div_ceil(CHUNK),
            "{size}"
        );
        assert_eq!(open_recovery(&file).unwrap(), body(size));
        assert_eq!(open_pass(&file, PASS).unwrap(), body(size));
    }
}

#[test]
fn non_ascii_passphrase_uses_nfc() {
    let decomposed = "cafe\u{301} s\u{e9}cret \u{212b}";
    let composed = "caf\u{e9} s\u{e9}cret \u{c5}";
    assert_ne!(decomposed, composed);
    let key = passphrase_slot_key(decomposed, &salt(), MIN_ITERATIONS).unwrap();
    let file = encrypt(
        &body(100),
        &[Slot {
            slot_type: SLOT_PASSPHRASE,
            key: &key,
        }],
        1,
    );
    assert_eq!(open_pass(&file, composed).unwrap(), body(100));
    assert_eq!(open_pass(&file, decomposed).unwrap(), body(100));
    assert!(passphrase_slot_key("", &salt(), MIN_ITERATIONS).is_err());
    assert!(passphrase_slot_key("x", &salt(), MIN_ITERATIONS - 1).is_err());
}

#[test]
fn three_slots_and_unknown_type_skipped() {
    let rk = rec_key();
    let other = [9u8; 32];
    let file = encrypt(
        &body(5),
        &[
            Slot {
                slot_type: SLOT_PASSPHRASE,
                key: pass_key(),
            },
            Slot {
                slot_type: 3,
                key: &other,
            },
            Slot {
                slot_type: SLOT_RECOVERY,
                key: &rk,
            },
        ],
        1,
    );
    assert_eq!(file[8 + 1 + 4 + 16], 3);
    assert_eq!(open_recovery(&file).unwrap(), body(5));
    assert_eq!(open_pass(&file, PASS).unwrap(), body(5));
    // An unknown slot type alone is skipped and leaves no usable slot.
    let only = encrypt(
        &body(5),
        &[Slot {
            slot_type: 7,
            key: &other,
        }],
        1,
    );
    assert!(matches!(
        open_recovery(&only),
        Err(Error::Envelope(EnvelopeError::NoKey(_)))
    ));
}

#[test]
fn key_only_envelope() {
    let rk = rec_key();
    let file = encrypt(
        &body(10),
        &[Slot {
            slot_type: SLOT_RECOVERY,
            key: &rk,
        }],
        1,
    );
    assert_eq!(file[8 + 1 + 4 + 16], 1);
    assert_eq!(file.len(), 8 + 1 + 4 + 16 + 1 + 61 + 32 + 10 + TAG);
    assert_eq!(open_recovery(&file).unwrap(), body(10));
    assert!(matches!(
        open_pass(&file, PASS),
        Err(Error::Envelope(EnvelopeError::NoKey(_)))
    ));
}

#[test]
fn wrong_secrets() {
    let file = both(&body(300));
    assert!(matches!(
        open_pass(&file, "wrong"),
        Err(Error::Envelope(EnvelopeError::WrongKey))
    ));
    let mut bytes = [0u8; 32];
    counter(200)(&mut bytes);
    bytes[0] ^= 1;
    let key = RecoveryKey::from_bytes(bytes);
    assert!(matches!(
        DecryptReader::open(Cursor::new(&file), Secret::Recovery(&key)).map(|_| ()),
        Err(Error::Envelope(EnvelopeError::WrongKey))
    ));
}

#[test]
fn truncation_extension_and_empty_body() {
    let file = both(&body(200_000)); // 4 chunks
    assert!(damaged(open_recovery(&file[..HEAD + BLOCK])));
    assert!(damaged(open_recovery(&file[..HEAD + 2 * BLOCK])));
    assert!(damaged(open_recovery(&file[..file.len() - 1])));
    let mut longer = file.clone();
    longer.push(0);
    assert!(damaged(open_recovery(&longer)));
    // Header only: no chunks at all.
    assert!(damaged(open_recovery(&file[..HEAD])));
    // Writer refuses an empty body.
    let rk = rec_key();
    let mut out = Vec::new();
    let w = encrypt_to(
        &mut out,
        &salt(),
        MIN_ITERATIONS,
        &[Slot {
            slot_type: SLOT_RECOVERY,
            key: &rk,
        }],
        &mut counter(1),
    )
    .unwrap();
    assert!(w.finish().is_err());
}

#[test]
fn reordered_chunks_fail() {
    let file = both(&body(140_000)); // 65536 + 65536 + 8928
    let mut swapped = file.clone();
    swapped[HEAD..HEAD + BLOCK].copy_from_slice(&file[HEAD + BLOCK..HEAD + 2 * BLOCK]);
    swapped[HEAD + BLOCK..HEAD + 2 * BLOCK].copy_from_slice(&file[HEAD..HEAD + BLOCK]);
    assert!(damaged(open_recovery(&swapped)));
    let mut duplicated = file.clone();
    duplicated.splice(
        HEAD + BLOCK..HEAD + 2 * BLOCK,
        file[HEAD..HEAD + BLOCK].to_vec(),
    );
    assert!(damaged(open_recovery(&duplicated)));
}

/// Hand-built chunk using the known data key of `counter(1)` (bytes 1..=32), to forge flags.
fn forged_chunk(plain: &[u8], index: u64, last: bool, header_hash: &[u8]) -> Vec<u8> {
    let mut key = [0u8; 32];
    counter(1)(&mut key);
    let cipher = Aes256Gcm::new(GenericArray::from_slice(&key));
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&index.to_be_bytes());
    let mut aad = header_hash.to_vec();
    aad.extend_from_slice(&index.to_be_bytes());
    aad.push(u8::from(last));
    let mut buffer = plain.to_vec();
    cipher
        .encrypt_in_place(GenericArray::from_slice(&nonce), &aad, &mut buffer)
        .unwrap();
    buffer
}

#[test]
fn flipped_last_flag_fails() {
    let file = both(&body(10));
    let hash = file[HEAD - 32..HEAD].to_vec();
    // Sanity: the forging helper reproduces the real final chunk.
    assert_eq!(forged_chunk(&body(10), 0, true, &hash), file[HEAD..]);
    let mut forged = file[..HEAD].to_vec();
    forged.extend(forged_chunk(&body(10), 0, false, &hash));
    assert!(damaged(open_recovery(&forged)));
    // A full first chunk with the last flag set, followed by more data.
    let mut forged = file[..HEAD].to_vec();
    forged.extend(forged_chunk(&body(CHUNK), 0, true, &hash));
    forged.extend(forged_chunk(&body(3), 1, true, &hash));
    assert!(damaged(open_recovery(&forged)));
    // A short final chunk below 17 bytes (no plaintext) is refused.
    let mut empty = file[..HEAD].to_vec();
    empty.extend(forged_chunk(&[], 0, true, &hash));
    assert!(damaged(open_recovery(&empty)));
}

#[test]
fn tampered_header_fails() {
    let file = both(&body(100));
    let positions = [0, 8, 9, 12, 13, 20, 29, 30, 31, 45, 80, 100, 151, 152, 183];
    for position in positions {
        let mut bad = file.clone();
        bad[position] ^= 0x01;
        assert!(open_recovery(&bad).is_err(), "byte {position}");
    }
    // A forged but self-consistent header (hash recomputed) still fails to unwrap or verify chunk 0.
    let mut bad = file.clone();
    bad[13] ^= 1; // salt
    let hash: [u8; 32] = Sha256::digest(&bad[..HEAD - 32]).into();
    bad[HEAD - 32..HEAD].copy_from_slice(&hash);
    assert!(matches!(
        open_recovery(&bad),
        Err(Error::Envelope(EnvelopeError::WrongKey))
    ));
    // A different slot count with a consistent hash: the stream no longer parses.
    let mut bad = file.clone();
    bad[29] = 1;
    assert!(open_recovery(&bad).is_err());
}

fn header_with(iterations: u32, count: u8, types: &[u8]) -> Vec<u8> {
    let mut h = ENVELOPE_MAGIC.to_vec();
    h.push(1);
    h.extend(iterations.to_be_bytes());
    h.extend(salt());
    h.push(count);
    for t in types {
        h.push(*t);
        h.extend([0u8; 12 + 48]);
    }
    let hash = Sha256::digest(&h);
    h.extend(hash);
    h.extend([0u8; 40]);
    h
}

#[test]
fn bounds_are_checked_before_key_work() {
    let start = std::time::Instant::now();
    for bad in [
        header_with(MIN_ITERATIONS - 1, 1, &[2]),
        header_with(MAX_ITERATIONS + 1, 1, &[2]),
        header_with(MIN_ITERATIONS, 0, &[]),
        header_with(MIN_ITERATIONS, 5, &[1, 2, 3, 4, 5]),
        header_with(MIN_ITERATIONS, 2, &[2, 2]),
    ] {
        assert!(damaged(open_pass(&bad, PASS)));
    }
    let mut kdf = header_with(MIN_ITERATIONS, 1, &[1]);
    kdf[8] = 2;
    assert!(damaged(open_pass(&kdf, PASS)));
    assert!(damaged(open_pass(b"STACKBK\x01 plain archive", PASS)));
    assert!(damaged(open_pass(b"STACK", PASS)));
    assert!(start.elapsed().as_secs() < 2, "no PBKDF2 should have run");
    let rk = rec_key();
    let mut out = Vec::new();
    assert!(
        encrypt_to(
            &mut out,
            &salt(),
            1,
            &[Slot {
                slot_type: 2,
                key: &rk
            }],
            &mut counter(1)
        )
        .is_err()
    );
}

#[test]
fn recovery_key_parsing() {
    let text = "00-11 22 33 44 55 66 77 88 99 aa BB cc dd ee ff 00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff";
    assert!(RecoveryKey::parse(text).is_ok());
    let compact = "00112233445566778899AABBCCDDEEFF00112233445566778899aabbccddeeff";
    let a = recovery_slot_key(&RecoveryKey::parse(compact).unwrap(), &salt());
    let b = recovery_slot_key(
        &RecoveryKey::parse(&compact.to_lowercase()).unwrap(),
        &salt(),
    );
    assert_eq!(*a, *b);
    for bad in [
        "",
        &compact[..62],
        &format!("{compact}00"),
        &compact.replace('0', "g"),
    ] {
        assert!(RecoveryKey::parse(bad).is_err());
    }
    assert_eq!(
        format!("{:?}", RecoveryKey::parse(compact).unwrap()),
        "RecoveryKey(<redacted>)"
    );
    let _ = crypto::CHUNK;
}

#[test]
fn duplicate_slot_types_are_refused() {
    let rk = rec_key();
    let mut out = Vec::new();
    let slots = [
        Slot {
            slot_type: SLOT_RECOVERY,
            key: &rk,
        },
        Slot {
            slot_type: SLOT_RECOVERY,
            key: &rk,
        },
    ];
    let result = encrypt_to(&mut out, &salt(), MIN_ITERATIONS, &slots, &mut counter(1));
    assert!(matches!(result, Err(Error::Invalid(_))));
    assert!(out.is_empty());
}

#[test]
fn reads_after_a_failure_keep_failing() {
    let rk = rec_key();
    let mut file = encrypt(
        &body(10),
        &[Slot {
            slot_type: SLOT_RECOVERY,
            key: &rk,
        }],
        1,
    );
    let last = file.len() - 1;
    file[last] ^= 1;
    let key = recovery();
    let mut reader = DecryptReader::open(Cursor::new(file), Secret::Recovery(&key)).unwrap();
    let mut buffer = [0u8; 16];
    assert!(reader.read(&mut buffer).is_err());
    for _ in 0..3 {
        assert!(reader.read(&mut buffer).is_err());
    }
    assert!(reader.finish().is_err());
}
