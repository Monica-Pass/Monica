#![forbid(unsafe_code)]

use aes::cipher::{BlockEncrypt, KeyInit};
use argon2::{Algorithm, Argon2, Params, Version};
use pbkdf2::pbkdf2_hmac;
use sha2::{Digest, Sha256};
use std::error::Error;
use std::fmt;
use zeroize::Zeroize;

pub fn derive_keepass_aes(key: &[u8], seed: &[u8], rounds: u64) -> Result<[u8; 32], KdfError> {
    if key.len() != 32 || seed.len() != 32 {
        return Err(KdfError::InvalidAesParameters);
    }
    let cipher = aes::Aes256::new_from_slice(seed).map_err(|_| KdfError::InvalidAesParameters)?;
    let mut blocks = [
        aes::Block::clone_from_slice(&key[..16]),
        aes::Block::clone_from_slice(&key[16..]),
    ];
    for _ in 0..rounds {
        cipher.encrypt_blocks(&mut blocks);
    }
    let mut digest = Sha256::new();
    digest.update(&blocks[0]);
    digest.update(&blocks[1]);
    let result = digest.finalize().into();
    for block in &mut blocks {
        block.as_mut_slice().zeroize();
    }
    Ok(result)
}

/// Bitwarden derives a 256-bit master key for both PBKDF2-SHA256 and Argon2id.
pub const MASTER_KEY_LEN: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KdfError {
    InvalidAesParameters,
    ZeroIterations,
    ZeroParallelism,
    InvalidArgon2Parameters,
    Argon2DerivationFailed,
}

impl fmt::Display for KdfError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::InvalidAesParameters => "invalid AES-KDF parameters",
            Self::ZeroIterations => "KDF iterations must be greater than zero",
            Self::ZeroParallelism => "Argon2 parallelism must be greater than zero",
            Self::InvalidArgon2Parameters => "invalid Argon2id parameters",
            Self::Argon2DerivationFailed => "Argon2id derivation failed",
        };
        formatter.write_str(message)
    }
}

impl Error for KdfError {}

/// Derives the 32-byte Bitwarden master key with PBKDF2-HMAC-SHA256.
///
/// Inputs are bytes on purpose: the Android boundary is responsible for the
/// exact UTF-8 normalization required by the Bitwarden protocol. Keeping the
/// primitive byte-oriented prevents accidental Java/Rust string conversions in
/// a hot, security-sensitive path.
pub fn derive_pbkdf2_sha256(
    password: &[u8],
    salt: &[u8],
    iterations: u32,
) -> Result<[u8; MASTER_KEY_LEN], KdfError> {
    if iterations == 0 {
        return Err(KdfError::ZeroIterations);
    }

    let mut output = [0_u8; MASTER_KEY_LEN];
    pbkdf2_hmac::<Sha256>(password, salt, iterations, &mut output);
    Ok(output)
}

/// Derives the 32-byte Bitwarden master key with Argon2id v1.3.
///
/// `memory_kib` deliberately uses KiB, matching both the RustCrypto Argon2 API
/// and Bitwarden's KDF memory parameter. No unit conversion is hidden here.
pub fn derive_argon2id(
    password: &[u8],
    salt: &[u8],
    iterations: u32,
    memory_kib: u32,
    parallelism: u32,
) -> Result<[u8; MASTER_KEY_LEN], KdfError> {
    if iterations == 0 {
        return Err(KdfError::ZeroIterations);
    }
    if parallelism == 0 {
        return Err(KdfError::ZeroParallelism);
    }

    let params = Params::new(memory_kib, iterations, parallelism, Some(MASTER_KEY_LEN))
        .map_err(|_| KdfError::InvalidArgon2Parameters)?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut output = [0_u8; MASTER_KEY_LEN];
    argon2
        .hash_password_into(password, salt, &mut output)
        .map_err(|_| KdfError::Argon2DerivationFailed)?;
    Ok(output)
}

/// KeePass Argon2d/Argon2id, preserving the original variant, version and cost.
/// Optional secret/associated data stays with Kotpass until separately supported.
pub fn derive_keepass_argon2(
    password: &[u8],
    salt: &[u8],
    iterations: u32,
    memory_kib: u32,
    parallelism: u32,
    variant: u32,
    version: u32,
) -> Result<[u8; MASTER_KEY_LEN], KdfError> {
    let algorithm = match variant {
        0 => Algorithm::Argon2d,
        2 => Algorithm::Argon2id,
        _ => return Err(KdfError::InvalidArgon2Parameters),
    };
    let version = match version {
        0x10 => Version::V0x10,
        0x13 => Version::V0x13,
        _ => return Err(KdfError::InvalidArgon2Parameters),
    };
    let params = Params::new(memory_kib, iterations, parallelism, Some(MASTER_KEY_LEN))
        .map_err(|_| KdfError::InvalidArgon2Parameters)?;
    // Reserve fallibly: a native allocation failure must not abort the app.
    let mut blocks = Vec::new();
    blocks
        .try_reserve_exact(params.block_count())
        .map_err(|_| KdfError::Argon2DerivationFailed)?;
    blocks.resize(params.block_count(), argon2::Block::default());
    let mut output = [0_u8; MASTER_KEY_LEN];
    let result = Argon2::new(algorithm, version, params).hash_password_into_with_memory(
        password,
        salt,
        &mut output,
        &mut blocks,
    );
    blocks.zeroize();
    if result.is_err() {
        output.zeroize();
        return Err(KdfError::Argon2DerivationFailed);
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode_hex(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 2, 0);
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let pair = std::str::from_utf8(pair).expect("test vector must be ASCII");
                u8::from_str_radix(pair, 16).expect("test vector must be valid hex")
            })
            .collect()
    }

    #[test]
    fn pbkdf2_sha256_matches_known_vector() {
        let derived = derive_pbkdf2_sha256(b"password", b"salt", 1).unwrap();
        let expected =
            decode_hex("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");

        assert_eq!(derived.as_slice(), expected.as_slice());
    }

    #[test]
    fn argon2id_v13_matches_known_vector() {
        let derived = derive_argon2id(b"password", b"somesalt", 2, 32, 1).unwrap();
        let expected =
            decode_hex("31111cc053ba0a799c0884148fd7ec9dc3631f3e8cf476cca9521d4ccc5136e8");

        assert_eq!(derived.as_slice(), expected.as_slice());
    }

    #[test]
    fn zero_pbkdf2_iterations_are_rejected() {
        assert_eq!(
            derive_pbkdf2_sha256(b"password", b"salt", 0),
            Err(KdfError::ZeroIterations)
        );
    }

    #[test]
    fn invalid_argon2_work_factors_are_rejected() {
        assert_eq!(
            derive_argon2id(b"password", b"somesalt", 0, 32, 1),
            Err(KdfError::ZeroIterations)
        );
        assert_eq!(
            derive_argon2id(b"password", b"somesalt", 2, 32, 0),
            Err(KdfError::ZeroParallelism)
        );
    }

    #[test]
    fn keepass_argon2id_matches_existing_bitwarden_primitive() {
        assert_eq!(
            derive_keepass_argon2(b"password", b"somesalt", 2, 32, 1, 2, 0x13).unwrap(),
            derive_argon2id(b"password", b"somesalt", 2, 32, 1).unwrap()
        );
        assert!(derive_keepass_argon2(b"password", b"somesalt", 2, 32, 1, 1, 0x13).is_err());
        assert!(derive_keepass_argon2(b"password", b"somesalt", 2, 32, 1, 2, 0x12).is_err());
        assert!(derive_keepass_argon2(b"password", b"short", 2, 32, 1, 2, 0x13).is_err());
    }

    #[test]
    fn keepass_aes_zero_rounds_hashes_the_unchanged_composite_and_rejects_bad_lengths() {
        let key = [7_u8; 32];
        assert_eq!(
            derive_keepass_aes(&key, &[3; 32], 0).unwrap().as_slice(),
            Sha256::digest(key).as_slice()
        );
        assert_eq!(
            derive_keepass_aes(&key[..16], &[3; 32], 1),
            Err(KdfError::InvalidAesParameters)
        );
        assert_eq!(
            derive_keepass_aes(&key, &[3; 16], 1),
            Err(KdfError::InvalidAesParameters)
        );
    }
}
