//! Bounded session caches on the decoding workers, never on the UI executor.
use crate::{RenderJob, worker::Worker};
use stack_core::RenderSize;
use stack_render::{Preview, Renderer};
use std::{collections::VecDeque, path::PathBuf, sync::Arc, time::SystemTime};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceStamp {
    path: PathBuf,
    modified: SystemTime,
    length: u64,
    device: u64,
    inode: u64,
}

impl SourceStamp {
    pub fn read(path: &std::path::Path) -> Result<Self, String> {
        use std::os::unix::fs::MetadataExt;
        let file = stack_archive::read_regular(path).map_err(|e| e.to_string())?;
        let metadata = file.metadata().map_err(|e| e.to_string())?;
        Ok(Self {
            path: path.to_owned(),
            modified: metadata.modified().map_err(|e| e.to_string())?,
            length: metadata.len(),
            device: metadata.dev(),
            inode: metadata.ino(),
        })
    }
}

pub struct Cache<K, V> {
    capacity: usize,
    entries: VecDeque<(K, V)>,
}

impl<K: PartialEq, V: Clone> Cache<K, V> {
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0);
        Self {
            capacity,
            entries: VecDeque::new(),
        }
    }

    pub fn get_or_render(
        &mut self,
        key: K,
        render: impl FnOnce() -> Result<V, String>,
    ) -> Result<V, String> {
        if let Some(index) = self.entries.iter().position(|(k, _)| k == &key) {
            let entry = self.entries.remove(index).expect("cached entry");
            let value = entry.1.clone();
            self.entries.push_back(entry);
            return Ok(value);
        }
        let value = render()?;
        if self.entries.len() == self.capacity {
            self.entries.pop_front();
        }
        self.entries.push_back((key, value.clone()));
        Ok(value)
    }
}

pub fn start() -> Worker<RenderJob, Arc<Preview>> {
    // At most three 1200 × 1600 RGBA pages (about 22 MiB), in addition to
    // the current displayed page. Saved appearance/source changes miss the cache.
    let mut cache = Cache::new(3);
    Worker::new("stack-preview", move |job: RenderJob| {
        let stamp = SourceStamp::read(&job.source)?;
        cache.get_or_render((stamp, job.clone()), || {
            let renderer = Renderer::from_environment();
            let size = RenderSize::new(1200, 1600).expect("bounded preview");
            match &job.saved {
                Some(saved) => renderer.render_saved(&job.source, saved, size),
                None => renderer.render(&job.source, job.page, size, job.rotation),
            }
            .map(Arc::new)
            .map_err(|e| e.to_string())
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reuses_recent_work_evicts_least_recent_and_retries_errors() {
        let mut cache = Cache::new(2);
        for key in [1, 2] {
            cache.get_or_render(key, || Ok(key)).unwrap();
        }
        assert_eq!(
            cache
                .get_or_render(1, || panic!("redecoded cached page"))
                .unwrap(),
            1
        );
        cache.get_or_render(3, || Ok(3)).unwrap();
        assert!(cache.get_or_render(2, || Err("retry".into())).is_err());
        assert_eq!(cache.get_or_render(2, || Ok(22)).unwrap(), 22);
        assert_eq!(cache.entries.len(), 2);
    }

    #[test]
    fn replacing_or_removing_an_original_cannot_reuse_cached_pixels() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("source");
        std::fs::write(&path, "old").unwrap();
        let old = SourceStamp::read(&path).unwrap();
        let replacement = root.path().join("replacement");
        std::fs::write(&replacement, "new").unwrap();
        std::fs::rename(replacement, &path).unwrap();
        assert_ne!(SourceStamp::read(&path).unwrap(), old);
        std::fs::remove_file(&path).unwrap();
        assert!(SourceStamp::read(&path).is_err());
    }
}
