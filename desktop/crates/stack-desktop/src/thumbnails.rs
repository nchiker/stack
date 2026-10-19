//! Only the visible result page is cached; one thumbnail is decoded at a time.
use crate::{
    Message, Stack,
    preview_cache::{Cache, SourceStamp},
    repository::{Request, Response},
    worker::Worker,
};
use cosmic::{app, widget};
use stack_core::{DocumentTile, RenderSize};
use stack_render::Renderer;
use std::collections::{BTreeMap, VecDeque};

#[derive(Default)]
pub struct Tiles {
    pub generation: u64,
    pub metadata: BTreeMap<String, DocumentTile>,
    pub images: BTreeMap<String, widget::image::Handle>,
    pub errors: BTreeMap<String, String>,
    pending: VecDeque<DocumentTile>,
    active: Option<String>,
}

pub fn start() -> Worker<DocumentTile, widget::image::Handle> {
    // Retain small decoded tiles across searches, filing filters and viewer trips.
    // Current-page UI handles remain bounded separately to RESULTS_PER_PAGE.
    let mut cache = Cache::new(100);
    Worker::new("stack-thumbnails", move |tile: DocumentTile| {
        let page = tile.page.ok_or("Document has no saved page")?;
        let source = tile.source.ok_or("Original unavailable")?;
        let stamp = SourceStamp::read(&source)?;
        cache.get_or_render((stamp, tile.token.incarnation, page.clone()), || {
            let preview = Renderer::from_environment()
                .render_saved(
                    &source,
                    &page,
                    RenderSize::new(320, 400).expect("bounded thumbnail"),
                )
                .map_err(|e| e.to_string())?;
            // A top-of-page crop makes the heading recognizable in the compact tile.
            // The full saved page remains intact in the viewer and in storage.
            let height = preview
                .image
                .height()
                .min((preview.image.width() * 9 / 16).max(1));
            let image =
                image::imageops::crop_imm(&preview.image, 0, 0, preview.image.width(), height)
                    .to_image();
            Ok(widget::image::Handle::from_rgba(
                image.width(),
                image.height(),
                image.into_raw(),
            ))
        })
    })
}

impl Stack {
    pub fn cancel_thumbnails(&mut self) {
        self.tiles.generation += 1;
        self.tiles.pending.clear();
        self.tiles.active = None;
    }

    pub fn load_tiles(&mut self) -> app::Task<Message> {
        self.cancel_thumbnails();
        self.tiles.metadata.clear();
        self.tiles.images.clear();
        self.tiles.errors.clear();
        if self.selected.is_some() || self.opening_document.is_some() {
            return app::Task::none();
        }
        let ids = self
            .hits
            .iter()
            .skip(self.browse_offset)
            .take(crate::RESULTS_PER_PAGE)
            .map(|h| h.document_id.clone())
            .collect();
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        let receive = worker.submit(Request::Tiles(ids));
        let generation = self.tiles.generation;
        cosmic::task::future(async move {
            Message::Tiles(
                generation,
                receive
                    .await
                    .unwrap_or_else(|_| Err("Tile metadata worker stopped".into())),
            )
        })
    }

    pub fn tiles_loaded(
        &mut self,
        generation: u64,
        result: Result<Response, String>,
    ) -> app::Task<Message> {
        if generation != self.tiles.generation {
            return app::Task::none();
        }
        if let Ok(Response::Tiles(tiles)) = result {
            for tile in tiles {
                self.tiles
                    .metadata
                    .insert(tile.token.id.clone(), tile.clone());
            }
            return self.queue_visible_thumbnails();
        }
        app::Task::none()
    }

    pub fn thumbnail_loaded(
        &mut self,
        generation: u64,
        id: String,
        result: Result<widget::image::Handle, String>,
    ) -> app::Task<Message> {
        if generation != self.tiles.generation {
            return app::Task::none();
        }
        self.tiles.active = None;
        match result {
            Ok(image) => {
                self.tiles.images.insert(id, image);
            }
            Err(error) => {
                self.tiles.errors.insert(id, error);
            }
        }
        self.next_thumbnail()
    }

    pub fn queue_visible_thumbnails(&mut self) -> app::Task<Message> {
        let (columns, _, first_row, rows) = self.visible_tile_rows();
        let first = first_row * columns;
        let count = rows * columns;
        for hit in self
            .hits
            .iter()
            .skip(self.browse_offset + first)
            .take(count)
        {
            let id = &hit.document_id;
            if !self.tiles.images.contains_key(id)
                && !self.tiles.errors.contains_key(id)
                && self.tiles.active.as_ref() != Some(id)
                && !self.tiles.pending.iter().any(|t| &t.token.id == id)
                && let Some(tile) = self.tiles.metadata.get(id)
            {
                self.tiles.pending.push_back(tile.clone());
            }
        }
        if self.tiles.active.is_none() {
            self.next_thumbnail()
        } else {
            app::Task::none()
        }
    }

    pub fn visible_tile_rows(&self) -> (usize, f32, usize, usize) {
        let columns = if !self.grid_view {
            1
        } else if self.narrow {
            2
        } else {
            3
        };
        let height = if self.grid_view { 240. } else { 80. };
        let scroll = self.browse_scroll.max(0.);
        let first = ((scroll / height) as usize).saturating_sub(1);
        // One row before and after the visible range. At the top there is no
        // preceding row: do not add its unused allowance below the viewport.
        let end =
            ((scroll + (self.viewport_height - 140.).max(height)) / height).ceil() as usize + 1;
        let count = end.saturating_sub(first);
        (columns, height, first, count)
    }

    fn next_thumbnail(&mut self) -> app::Task<Message> {
        if self.tiles.pending.is_empty()
            && self.flags.visual_review.is_some()
            && self.smoke_step == 0
        {
            if self.tiles.images.len() != 6 {
                self.library_error = Some("Visual thumbnails did not render".into());
                return cosmic::task::future(async { Message::Close });
            }
            return self.delayed_capture();
        }
        let (Some(tile), Some(worker)) = (self.tiles.pending.pop_front(), &self.thumbnail_worker)
        else {
            return app::Task::none();
        };
        let generation = self.tiles.generation;
        let id = tile.token.id.clone();
        self.tiles.active = Some(id.clone());
        let receive = worker.submit(tile);
        cosmic::task::future(async move {
            Message::Thumbnail(
                generation,
                id,
                receive
                    .await
                    .unwrap_or_else(|_| Err("Thumbnail worker stopped".into())),
            )
        })
    }
}

pub fn added_date(milliseconds: i64) -> String {
    // Gregorian civil date from Unix days; display dates are not editable values.
    let z = milliseconds.div_euclid(86_400_000) + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let mut year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    year += i64::from(month <= 2);
    let months = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    format!("{} {day}, {year}", months[(month - 1) as usize])
}

#[cfg(test)]
mod tests {
    #[test]
    fn added_dates_cover_epoch_pre_epoch_and_leap_day() {
        assert_eq!(super::added_date(0), "Jan 1, 1970");
        assert_eq!(super::added_date(-86_400_000), "Dec 31, 1969");
        assert_eq!(super::added_date(1_709_164_800_000), "Feb 29, 2024");
    }
}
