//! One page at a time; repository commits and native OCR never run on the UI.
use crate::{
    Message, Stack,
    repository::{Request, Response},
    worker::Worker,
};
use cosmic::{app, iced};
use stack_core::{TextJob, TextOutput, TextStatus};

#[derive(Default)]
pub struct Ui {
    pub busy: bool,
    pub statuses: Vec<TextStatus>,
    pub status_document: Option<String>,
    pending_commit: Option<(TextJob, Result<TextOutput, String>)>,
}

#[derive(Clone, Debug)]
pub enum Event {
    Tick,
    Claimed(Result<Response, String>),
    Read(TextJob, Result<TextOutput, String>),
    Committed(Result<Response, String>),
    Retry(String),
    Cancel(String),
}

pub fn worker() -> Worker<TextJob, TextOutput> {
    Worker::new("stack-text-reader", |job: TextJob| {
        stack_render::Renderer::from_environment()
            .read_saved_text(&job.source, &job.page)
            .map_err(|e| e.to_string())
    })
}

impl Stack {
    pub fn text_action(&mut self, event: Event) -> app::Task<Message> {
        match event {
            Event::Tick
                if !self.text.busy
                    && !self.library_loading
                    && !self.saving
                    && !self.transfers.busy() =>
            {
                self.text.busy = true;
                if let Some((job, result)) = &self.text.pending_commit {
                    return self
                        .text_request(Request::TextFinish(job.clone(), result.clone()), true);
                }
                let document = self.selected.as_ref().map(|d| d.token.id.clone());
                return self.text_request(Request::TextClaim(document), false);
            }
            Event::Claimed(Ok(Response::TextClaim {
                job,
                statuses,
                document,
            })) => {
                self.text.statuses = statuses;
                self.text.status_document = document;
                if let Some(job) = job {
                    let Some(worker) = &self.text_worker else {
                        self.text.busy = false;
                        return app::Task::none();
                    };
                    let result = worker.submit(job.clone());
                    return cosmic::task::future(async move {
                        Message::Text(Event::Read(
                            job,
                            result
                                .await
                                .unwrap_or_else(|_| Err("Text reader stopped".into())),
                        ))
                    });
                }
                self.text.busy = false;
            }
            Event::Read(job, result) => {
                self.text.pending_commit = Some((job.clone(), result.clone()));
                return self.text_request(Request::TextFinish(job, result), true);
            }
            Event::Committed(Ok(Response::TextFinished {
                document,
                statuses,
                view,
                based_on,
            })) => {
                self.text.busy = false;
                self.text.pending_commit = None;
                if self
                    .selected
                    .as_ref()
                    .is_some_and(|d| d.token.id == document)
                {
                    self.text.status_document = Some(document.clone());
                    self.text.statuses = statuses;
                    if let Some(view) = view {
                        // Only rebase a token when this exact revision accepted
                        // derived text. Typed strings and parked details remain.
                        if let Some(draft) = self.drafts.get_mut(&document)
                            && based_on.as_ref() == Some(&draft.token)
                        {
                            draft.token = view.token.clone();
                        }
                        self.selected = Some(view);
                    }
                }
                // Next subscription tick claims the next durable queued page.
            }
            Event::Retry(id) => return self.text_request(Request::TextRetry(id), true),
            Event::Cancel(id) => return self.text_request(Request::TextCancel(id), true),
            Event::Claimed(_) | Event::Committed(_) => self.text.busy = false,
            _ => {}
        }
        app::Task::none()
    }

    fn text_request(&self, request: Request, commit: bool) -> app::Task<Message> {
        let Some(repository) = &self.repository else {
            return app::Task::none();
        };
        let receive = repository.submit_required(request);
        cosmic::task::future(async move {
            let result = receive
                .await
                .unwrap_or_else(|_| Err("Text repository stopped".into()));
            Message::Text(if commit {
                Event::Committed(result)
            } else {
                Event::Claimed(result)
            })
        })
    }
}

pub fn subscription(app: &Stack) -> iced::Subscription<Message> {
    if app.repository.is_none() || app.closing {
        return iced::Subscription::none();
    }
    iced::time::every(std::time::Duration::from_secs(2)).map(|_| Message::Text(Event::Tick))
}
