//! One active job and one replaceable pending job, with no blocking UI sends.
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use tokio::sync::oneshot;

type Reply<T> = oneshot::Sender<Result<T, String>>;
struct State<Q, R> {
    pending: Option<(Q, Reply<R>, bool)>,
    closing: bool,
}
pub struct Worker<Q, R> {
    state: Arc<(Mutex<State<Q, R>>, Condvar)>,
    thread: Option<JoinHandle<()>>,
}

impl<Q: Send + 'static, R: Send + 'static> Worker<Q, R> {
    pub fn new(name: &str, mut run: impl FnMut(Q) -> Result<R, String> + Send + 'static) -> Self {
        let state = Arc::new((
            Mutex::new(State {
                pending: None,
                closing: false,
            }),
            Condvar::new(),
        ));
        let shared = state.clone();
        let thread = thread::Builder::new()
            .name(name.into())
            .spawn(move || {
                loop {
                    let (lock, ready) = &*shared;
                    let job = {
                        let mut state = lock.lock().expect("worker state");
                        while state.pending.is_none() && !state.closing {
                            state = ready.wait(state).expect("worker wait");
                        }
                        if state.closing {
                            break;
                        }
                        state.pending.take()
                    };
                    if let Some((request, reply, _)) = job {
                        let _ = reply.send(run(request));
                    }
                }
            })
            .expect("background worker thread");
        Self {
            state,
            thread: Some(thread),
        }
    }

    pub fn submit(&self, request: Q) -> oneshot::Receiver<Result<R, String>> {
        self.enqueue(request, true)
    }

    /// User decisions may fail with busy before acceptance, but are never replaced
    /// by subsequent queries or selections once queued.
    pub fn submit_required(&self, request: Q) -> oneshot::Receiver<Result<R, String>> {
        self.enqueue(request, false)
    }

    fn enqueue(&self, request: Q, replaceable: bool) -> oneshot::Receiver<Result<R, String>> {
        let (reply, receive) = oneshot::channel();
        let (lock, ready) = &*self.state;
        let mut state = lock.lock().expect("worker state");
        if state.closing
            || state
                .pending
                .as_ref()
                .is_some_and(|(_, _, old)| !old || !replaceable)
        {
            let _ = reply.send(Err("Library worker is busy; retry this action".into()));
            return receive;
        }
        if let Some((_, old, _)) = state.pending.replace((request, reply, replaceable)) {
            let _ = old.send(Err("Superseded by a newer request".into()));
        }
        ready.notify_one();
        receive
    }
}

impl<Q, R> Drop for Worker<Q, R> {
    fn drop(&mut self) {
        let (lock, ready) = &*self.state;
        {
            let mut state = lock.lock().expect("worker state");
            state.closing = true;
            state.pending.take();
            ready.notify_one();
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepted_decisions_cannot_be_replaced_by_later_reads_or_saves() {
        let (started, wait_started) = std::sync::mpsc::channel();
        let (release, wait_release) = std::sync::mpsc::channel();
        let worker = Worker::new("decision-queue", move |n| {
            if n == 1 {
                started.send(()).unwrap();
                wait_release.recv().unwrap();
            }
            Ok(n)
        });
        let first = worker.submit(1);
        wait_started.recv().unwrap();
        let decision = worker.submit_required(2);
        assert!(
            worker
                .submit(3)
                .blocking_recv()
                .unwrap()
                .unwrap_err()
                .contains("busy")
        );
        assert!(
            worker
                .submit_required(4)
                .blocking_recv()
                .unwrap()
                .unwrap_err()
                .contains("busy")
        );
        release.send(()).unwrap();
        assert_eq!(first.blocking_recv().unwrap().unwrap(), 1);
        assert_eq!(decision.blocking_recv().unwrap().unwrap(), 2);
    }
    #[test]
    fn replaces_pending_work_and_joins_active_work() {
        let (started, wait_started) = std::sync::mpsc::channel();
        let (release, wait_release) = std::sync::mpsc::channel();
        let worker = Worker::new("bounded-test", move |n| {
            if n == 1 {
                started.send(()).unwrap();
                wait_release.recv().unwrap();
            }
            Ok(n)
        });
        let first = worker.submit(1);
        wait_started.recv().unwrap();
        let second = worker.submit(2);
        let third = worker.submit(3);
        assert!(
            second
                .blocking_recv()
                .unwrap()
                .unwrap_err()
                .contains("Superseded")
        );
        release.send(()).unwrap();
        assert_eq!(first.blocking_recv().unwrap().unwrap(), 1);
        assert_eq!(third.blocking_recv().unwrap().unwrap(), 3);
        drop(worker);
    }
}
