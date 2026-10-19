//! Authored-only native scenario. Calls the same message handlers as controls;
//! this is message injection, not pointer/keyboard input evidence.
use crate::{Message, Stack, catalog::Action};
use cosmic::{app, iced};

impl Stack {
    pub fn catalog_scenario(&mut self) -> app::Task<Message> {
        let step = self.scenario_step;
        self.scenario_step += 1;
        let send = |message| cosmic::task::future(async move { message });
        match step {
            0 => send(Message::Query("laterpagesentinel".into())),
            1 => {
                if self.hits.len() != 2 {
                    return iced::exit();
                }
                send(Message::SortByName(true))
            }
            2 => send(Message::OpenDocument(stack_archive::synthetic::id(1))),
            // Capture completion advances here; selection has rendered later page.
            3 => send(Message::Catalog(Action::Open)),
            4 => {
                let _ = self.catalog_action(Action::New);
                let _ = self.catalog_action(Action::Name("Synthetic M2 stack".into()));
                let _ = self.catalog_action(Action::Color("123456".into()));
                self.catalog_action(Action::SaveName)
            }
            5 => {
                let Some(stack) = self.stacks.iter().find(|s| s.name == "Synthetic M2 stack")
                else {
                    return iced::exit();
                };
                let id = stack.id.clone();
                let _ = self.catalog_action(Action::Select(id));
                let _ = self.catalog_action(Action::Name("Renamed synthetic stack".into()));
                self.catalog_action(Action::SaveName)
            }
            6 => {
                let _ = self.catalog_action(Action::Color("ABCDEF".into()));
                self.catalog_action(Action::SaveColor)
            }
            7 => self.catalog_action(Action::Visibility),
            8 => self.catalog_action(Action::Move(-1)),
            9 => {
                let _ = self.catalog_action(Action::Delete);
                self.catalog_action(Action::ConfirmDelete)
            }
            10 => {
                let _ = self.catalog_action(Action::Select("custom-last".into()));
                let _ = self.catalog_action(Action::Color("123456".into()));
                self.catalog_action(Action::SaveColor)
            }
            11 => self.catalog_action(Action::Visibility),
            12 => self.delayed_capture(),
            _ => {
                self.flags
                    .completed
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                send(Message::Close)
            }
        }
    }
}
