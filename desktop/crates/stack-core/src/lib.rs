//! Desktop-independent domain bounds, persistent repository and archive operations.
mod browsing;
mod editing;
mod interchange;
pub mod library;
mod schema;
mod search;
mod sync;
mod text;
pub use browsing::{DocumentTile, DocumentView, SavedPage, StackSummary};
pub use editing::{DocumentEdit, StackEdit};
pub use interchange::{
    FrozenSnapshot, IdMappings, ImportWork, OutputWork, RestorePlan, RestoreReceipt, SnapshotWork,
    StagedImport,
};
pub use search::{SearchHit, Sort};
pub use text::{TEXT_CONFIG, TextJob, TextOutput, TextStatus};

pub const APP_ID: &str = "org.stackdocs.desktop";
pub const MAX_SOURCE_BYTES: u64 = 256 * 1024 * 1024;
pub const MAX_PAGE_COUNT: u32 = 1_000;
pub const MAX_RENDER_EDGE: u32 = 4_096;
pub const MAX_RENDER_PIXELS: u64 = 16 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum ValidationError {
    #[error("Preview size must be between 1 and {MAX_RENDER_EDGE} pixels per edge")]
    RenderSize,
    #[error("Page rotation must be 0, 90, 180 or 270 degrees")]
    Rotation,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RenderSize {
    width: u32,
    height: u32,
}

impl RenderSize {
    pub fn new(width: u32, height: u32) -> Result<Self, ValidationError> {
        if width == 0
            || height == 0
            || width > MAX_RENDER_EDGE
            || height > MAX_RENDER_EDGE
            || u64::from(width) * u64::from(height) > MAX_RENDER_PIXELS
        {
            return Err(ValidationError::RenderSize);
        }
        Ok(Self { width, height })
    }

    pub fn width(self) -> u32 {
        self.width
    }
    pub fn height(self) -> u32 {
        self.height
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Rotation {
    None,
    Clockwise90,
    Clockwise180,
    Clockwise270,
}

impl TryFrom<u16> for Rotation {
    type Error = ValidationError;
    fn try_from(degrees: u16) -> Result<Self, Self::Error> {
        match degrees {
            0 => Ok(Self::None),
            90 => Ok(Self::Clockwise90),
            180 => Ok(Self::Clockwise180),
            270 => Ok(Self::Clockwise270),
            _ => Err(ValidationError::Rotation),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn untrusted_render_dimensions_are_bounded_without_overflow() {
        for (w, h) in [(0, 100), (100, 0), (u32::MAX, u32::MAX), (4097, 10)] {
            assert!(RenderSize::new(w, h).is_err());
        }
        assert!(RenderSize::new(4096, 4096).is_ok());
        assert!(Rotation::try_from(45).is_err());
        assert_eq!(Rotation::try_from(90).unwrap(), Rotation::Clockwise90);
    }
}
