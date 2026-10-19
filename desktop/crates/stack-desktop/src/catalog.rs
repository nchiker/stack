use crate::{Message, Stack};
use cosmic::{Element, app, iced, widget};
use stack_core::{StackEdit, StackSummary};

#[derive(Clone, Debug)]
pub enum Action {
    Open,
    New,
    Select(String),
    Name(String),
    Color(String),
    SaveName,
    SaveColor,
    Visibility,
    Move(i32),
    Delete,
    ConfirmDelete,
    CancelDelete,
}
pub struct Editor {
    pub open: bool,
    pub selected: Option<String>,
    pub name: String,
    pub color: String,
    pub confirm_delete: bool,
    name_baseline: String,
    color_baseline: String,
    pending_name: bool,
    pending_color: bool,
}
impl Default for Editor {
    fn default() -> Self {
        Self {
            open: false,
            selected: None,
            name: String::new(),
            color: "B5E2ED".into(),
            confirm_delete: false,
            name_baseline: String::new(),
            color_baseline: "B5E2ED".into(),
            pending_name: false,
            pending_color: false,
        }
    }
}
impl Editor {
    pub fn has_unsaved_changes(&self) -> bool {
        self.name != self.name_baseline || self.color != self.color_baseline
    }
    pub fn saved_result(&mut self) {
        if self.pending_name {
            self.name_baseline = self.name.clone();
        }
        if self.pending_color {
            self.color_baseline = self.color.clone();
        }
        self.pending_name = false;
        self.pending_color = false;
    }
    pub fn mark_saved(&mut self) {
        self.name_baseline = self.name.clone();
        self.color_baseline = self.color.clone();
    }
}

const PALETTE: [(&str, &str); 8] = [
    ("Blue", "B5E2ED"),
    ("Green", "B5DCC1"),
    ("Yellow", "F7E1A3"),
    ("Pink", "F2C5DC"),
    ("Sky", "B9DCED"),
    ("Purple", "DDC4ED"),
    ("Peach", "F4D6BB"),
    ("Rose", "F0BFC6"),
];

fn message(action: Action) -> Message {
    Message::Catalog(action)
}

// Saved opaque RGB colors take precedence, including on standard categories.
// Legacy nonopaque palette codes use category or stable ID accents.
// This is presentation only; saved values are never rewritten.
pub fn accent(stack: &StackSummary) -> u32 {
    let saved = stack.color as u32;
    if saved >> 24 == 0xff {
        return saved;
    }
    let standard = |key: &str| match key.trim().to_lowercase().as_str() {
        "bills" => Some(0xffb5e2ed),
        "banking" => Some(0xffb5dcc1),
        "ids" => Some(0xfff7e1a3),
        "house" => Some(0xfff2c5dc),
        "medical" => Some(0xffb9dced),
        "receipts" => Some(0xffddc4ed),
        "starred" => Some(0xfff4d6bb),
        _ => None,
    };
    if let Some(color) = standard(&stack.id).or_else(|| standard(&stack.name)) {
        return color;
    }
    {
        let hash = stack.id.encode_utf16().fold(0_i32, |hash, c| {
            hash.wrapping_mul(31).wrapping_add(c as i32)
        });
        [
            0xffb5e2ed, 0xffb5dcc1, 0xfff7e1a3, 0xfff2c5dc, 0xffb9dced, 0xffddc4ed, 0xfff4d6bb,
            0xfff0bfc6,
        ][hash.rem_euclid(8) as usize]
    }
}

pub fn icon(stack: &StackSummary) -> widget::icon::Icon {
    color_icon(accent(stack))
}

fn color_icon(color: u32) -> widget::icon::Icon {
    let svg = format!(
        "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24'><circle cx='12' cy='12' r='8' fill='#{:06x}' fill-opacity='{}'/><circle cx='12' cy='12' r='9' fill='none' stroke='#888' stroke-width='1'/></svg>",
        color & 0xffffff,
        (color >> 24) as f32 / 255.
    );
    widget::icon::from_svg_bytes(svg.into_bytes())
        .icon()
        .size(20)
}

impl Stack {
    pub fn catalog_action(&mut self, action: Action) -> app::Task<Message> {
        if self.saving {
            return app::Task::none();
        }
        match action {
            Action::Open => {
                self.catalog.open = true;
                self.rebuild_navigation();
                return self.browse();
            }
            Action::New => {
                self.catalog.selected = None;
                self.catalog.name.clear();
                self.catalog.color = "B5E2ED".into();
                self.catalog.confirm_delete = false;
                self.catalog.mark_saved();
            }
            Action::Select(id) => {
                if let Some(stack) = self.stacks.iter().find(|s| s.id == id) {
                    self.catalog.selected = Some(id);
                    self.catalog.name = stack.name.clone();
                    self.catalog.color = format!("{:06X}", accent(stack) & 0xffffff);
                    self.catalog.confirm_delete = false;
                    self.catalog.mark_saved();
                }
            }
            Action::Name(name) => {
                self.catalog.name = name;
                self.save_status = None;
            }
            Action::Color(color) => {
                self.catalog.color = color;
                self.save_status = None;
            }
            Action::Delete => self.catalog.confirm_delete = true,
            Action::CancelDelete => self.catalog.confirm_delete = false,
            action if !self.saving => {
                let selected = self.catalog.selected.clone();
                let chosen = selected
                    .as_ref()
                    .and_then(|id| self.stacks.iter().find(|s| s.id == *id));
                let edit = match action {
                    Action::SaveName => {
                        if let Some(id) = selected {
                            StackEdit::Rename {
                                id,
                                name: self.catalog.name.clone(),
                            }
                        } else {
                            let Some(color) = self.catalog_color() else {
                                return app::Task::none();
                            };
                            StackEdit::Create {
                                name: self.catalog.name.clone(),
                                color,
                                visible: true,
                            }
                        }
                    }
                    Action::SaveColor => {
                        let Some(id) = selected else {
                            return app::Task::none();
                        };
                        let Some(color) = self.catalog_color() else {
                            return app::Task::none();
                        };
                        StackEdit::Color { id, color }
                    }
                    Action::Visibility => {
                        let Some(stack) = chosen else {
                            return app::Task::none();
                        };
                        StackEdit::Visibility {
                            id: stack.id.clone(),
                            visible: !stack.visible,
                        }
                    }
                    Action::Move(delta) => {
                        let Some(id) = selected else {
                            return app::Task::none();
                        };
                        let mut order: Vec<_> = self.stacks.iter().map(|s| s.id.clone()).collect();
                        let Some(index) = order.iter().position(|s| *s == id) else {
                            return app::Task::none();
                        };
                        let target = index as i32 + delta;
                        if target < 0 || target >= order.len() as i32 {
                            return app::Task::none();
                        }
                        order.swap(index, target as usize);
                        StackEdit::Order(order)
                    }
                    Action::ConfirmDelete if self.catalog.confirm_delete => {
                        let Some(id) = selected else {
                            return app::Task::none();
                        };
                        StackEdit::Delete(id)
                    }
                    _ => return app::Task::none(),
                };
                self.catalog.pending_name =
                    matches!(edit, StackEdit::Rename { .. } | StackEdit::Create { .. });
                self.catalog.pending_color =
                    matches!(edit, StackEdit::Color { .. } | StackEdit::Create { .. });
                return self.catalog_save(edit);
            }
            _ => {}
        }
        app::Task::none()
    }

    fn catalog_color(&mut self) -> Option<i32> {
        let value = self.catalog.color.trim().trim_start_matches('#');
        if value.len() != 6 {
            self.save_status = Some("Enter a six-digit RGB color, such as B5E2ED.".into());
            return None;
        }
        match u32::from_str_radix(value, 16) {
            Ok(color) => Some((0xff000000 | color) as i32),
            Err(_) => {
                self.save_status = Some("Enter a six-digit RGB color, such as B5E2ED.".into());
                None
            }
        }
    }

    pub fn catalog_view(&self) -> Element<'_, Message> {
        let mut content = widget::column::with_capacity(self.stacks.len() + 8)
            .spacing(12)
            .push(widget::text::heading("Manage stacks"))
            .push(
                self.button("New stack")
                    .on_press_maybe((!self.saving).then_some(message(Action::New))),
            );
        if let Some(status) = &self.save_status {
            content = content.push(self.body(status.as_str()));
        }
        if let Some(error) = &self.library_error {
            content = content.push(self.body(error.as_str()));
        }
        for stack in &self.stacks {
            content = content.push(
                widget::row::with_capacity(2)
                    .push(icon(stack))
                    .push(
                        widget::button::text(format!(
                            "{} · {}",
                            stack.name,
                            if stack.visible {
                                "Shown in sidebar"
                            } else {
                                "Hidden"
                            }
                        ))
                        .on_press_maybe(
                            (!self.saving).then_some(message(Action::Select(stack.id.clone()))),
                        ),
                    )
                    .spacing(8),
            );
        }
        content = content
            .push(self.body("Stack name"))
            .push(
                widget::text_input("Name", &self.catalog.name)
                    .on_input(|s| message(Action::Name(s))),
            )
            .push(self.body("Color"));
        let mut palette = widget::row::with_capacity(PALETTE.len()).spacing(4);
        for (name, value) in PALETTE {
            let color = u32::from_str_radix(value, 16).expect("palette RGB");
            palette = palette.push(
                widget::button::custom(
                    widget::row::with_capacity(2)
                        .spacing(4)
                        .push(color_icon(0xff000000 | color))
                        .push(widget::text::caption(name)),
                )
                .name(name)
                .class(if self.catalog.color.eq_ignore_ascii_case(value) {
                    cosmic::theme::Button::Suggested
                } else {
                    cosmic::theme::Button::Standard
                })
                .on_press_maybe((!self.saving).then_some(message(Action::Color(value.into())))),
            );
        }
        content = content
            .push(palette.wrap())
            .push(widget::text::caption(
                "Optional custom color · six-digit RGB",
            ))
            .push(
                widget::text_input("B5E2ED", &self.catalog.color)
                    .on_input(|s| message(Action::Color(s))),
            )
            .push(widget::text::caption(
                "Save color to update the stack's sidebar and document accents.",
            ));
        if let Ok(color) =
            u32::from_str_radix(self.catalog.color.trim().trim_start_matches('#'), 16)
            && self.catalog.color.trim().trim_start_matches('#').len() == 6
        {
            content = content.push(
                widget::row::with_capacity(2)
                    .spacing(8)
                    .push(color_icon(0xff000000 | color))
                    .push(widget::text::caption("Color preview · save to apply")),
            );
        }
        let mut actions = widget::row::with_capacity(6).spacing(8).push(
            self.button(if self.catalog.selected.is_some() {
                "Save name"
            } else {
                "Create stack"
            })
            .on_press_maybe((!self.saving).then_some(message(Action::SaveName))),
        );
        if self.catalog.selected.is_some() {
            actions = actions
                .push(
                    self.button("Save color")
                        .on_press_maybe((!self.saving).then_some(message(Action::SaveColor))),
                )
                .push(
                    self.button("Show / hide in sidebar")
                        .on_press_maybe((!self.saving).then_some(message(Action::Visibility))),
                )
                .push(
                    self.button("Move up")
                        .on_press_maybe((!self.saving).then_some(message(Action::Move(-1)))),
                )
                .push(
                    self.button("Move down")
                        .on_press_maybe((!self.saving).then_some(message(Action::Move(1)))),
                )
                .push(
                    self.button("Delete stack…")
                        .on_press_maybe((!self.saving).then_some(message(Action::Delete))),
                );
        }
        content = content.push(actions.wrap());
        if self.catalog.confirm_delete {
            content = content
                .push(self.body("Delete this stack? Documents remain in All documents."))
                .push(
                    widget::row::with_capacity(2)
                        .spacing(8)
                        .push(self.button("Delete stack").on_press_maybe(
                            (!self.saving).then_some(message(Action::ConfirmDelete)),
                        ))
                        .push(
                            self.button("Keep stack")
                                .on_press(message(Action::CancelDelete)),
                        ),
                );
        }
        widget::container(widget::scrollable(content).id("stack-catalog"))
            .padding(16)
            .height(iced::Length::Fill)
            .into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn standard_imported_names_and_explicit_custom_colors_have_stable_accents() {
        let mut stack = StackSummary {
            id: "opaque-imported".into(),
            name: "Receipts".into(),
            color: 42,
            visible: true,
            sort_order: 0,
        };
        assert_eq!(accent(&stack), 0xffddc4ed);
        stack.id = "bills".into();
        stack.name = "Renamed".into();
        assert_eq!(accent(&stack), 0xffb5e2ed);
        stack.color = 0xff123456_u32 as i32;
        assert_eq!(
            accent(&stack),
            0xff123456,
            "a saved override also wins for a standard ID"
        );
        stack.id = "custom".into();
        stack.name = "Receipts".into();
        assert_eq!(
            accent(&stack),
            0xff123456,
            "a saved override also wins for a category name"
        );
        stack.name = "Custom".into();
        stack.color = 0xffe4ede2_u32 as i32;
        assert_eq!(accent(&stack), 0xffe4ede2);
    }
}
