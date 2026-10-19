use crate::Control;
use crate::futures::futures::channel::mpsc;

use iced_accessibility::accesskit::{
    ActivationHandler, Node, NodeId, Role, Tree, TreeId, TreeUpdate,
};
use iced_accessibility::accesskit_winit::Adapter;
use iced_runtime::core;

pub struct WinitActivationHandler {
    pub proxy: mpsc::UnboundedSender<Control>,
    pub title: String,
    pub root: NodeId,
}

impl ActivationHandler for WinitActivationHandler {
    fn request_initial_tree(
        &mut self,
    ) -> Option<iced_accessibility::accesskit::TreeUpdate> {
        let _ = self
            .proxy
            .unbounded_send(Control::AccessibilityEnabled(true));
        // The real hierarchy is produced on the redraw requested by the
        // activation event. AccessKit owns its initial placeholder; publishing
        // an application placeholder here would overwrite useful semantics.
        None
    }
}

pub struct WinitActionHandler {
    pub id: core::window::Id,
    pub proxy: mpsc::UnboundedSender<Control>,
}

impl iced_accessibility::accesskit::ActionHandler for WinitActionHandler {
    fn do_action(
        &mut self,
        request: iced_accessibility::accesskit::ActionRequest,
    ) {
        let _ = self
            .proxy
            .unbounded_send(Control::Accessibility(self.id, request));
    }
}

pub struct WinitDeactivationHandler {
    pub proxy: mpsc::UnboundedSender<Control>,
}

impl iced_accessibility::accesskit::DeactivationHandler
    for WinitDeactivationHandler
{
    fn deactivate_accessibility(&mut self) {
        let _ = self
            .proxy
            .unbounded_send(Control::AccessibilityEnabled(false));
    }
}

/// Converts the current widget hierarchy to the tree sent to the native adapter.
/// The root is shared with activation, so updates replace the placeholder.
pub fn tree_update(root: NodeId, title: &str, widgets: iced_accessibility::A11yTree, focused: Option<NodeId>) -> TreeUpdate {
    let mut node = Node::new(Role::Window);
    node.set_label(title.to_owned());
    node.set_children(widgets.root().iter().map(|node| node.id().clone().into()).collect::<Vec<_>>());
    let mut nodes: Vec<(NodeId, Node)> = widgets.into();
    let focus = focused.filter(|id| nodes.iter().any(|(node, _)| node == id)).unwrap_or(root);
    nodes.push((root, node));
    TreeUpdate { tree_id: TreeId::ROOT, nodes, tree: Some(Tree::new(root)), focus }
}

pub(crate) fn focused_node<Message, Theme, Renderer: core::Renderer>(ui: &mut iced_runtime::UserInterface<'_, Message, Theme, Renderer>, renderer: &Renderer) -> Option<NodeId> {
    use core::widget::Operation;
    struct Focused(Option<core::widget::Id>);
    impl Operation for Focused {
        fn traverse(&mut self, operate: &mut dyn FnMut(&mut dyn Operation)) { operate(self); }
        fn focusable(&mut self, id: Option<&core::widget::Id>, _: core::Rectangle, state: &mut dyn core::widget::operation::Focusable) {
            if state.is_focused() { self.0 = id.cloned(); }
        }
    }
    let mut operation = Focused(None);
    ui.operate(renderer, &mut operation);
    operation.0.map(|id| NodeId(id.into()))
}

/// Route native actions through focus operations and normal keyboard activation.
/// The pinned COSMIC button's generic A11y handler does not check its target ID;
/// broadcasting that event could invoke a sibling button. Keyboard activation after
/// verified target focus preserves the control's own enabled/action behavior.
pub fn route_action<Message, Theme, Renderer: core::Renderer>(ui: &mut iced_runtime::UserInterface<'_, Message, Theme, Renderer>, renderer: &Renderer, request: &iced_accessibility::accesskit::ActionRequest) -> Vec<core::Event> {
    use iced_accessibility::accesskit::{Action, TreeId};
    use core::keyboard::{self, Key, Location, Modifiers, key};
    if request.target_tree != TreeId::ROOT || !matches!(request.action, Action::Focus | Action::Click) {
        return Vec::new();
    }
    let mut focus = core::widget::operation::focusable::focus(core::widget::Id::from(request.target_node.0));
    ui.operate(renderer, &mut focus);
    if focused_node(ui, renderer) != Some(request.target_node) || request.action == Action::Focus {
        return Vec::new();
    }
    let enter = Key::Named(key::Named::Enter);
    vec![
        core::Event::Keyboard(keyboard::Event::KeyPressed { key: enter.clone(), modified_key: enter.clone(), physical_key: key::Physical::Code(key::Code::Enter), location: Location::Standard, modifiers: Modifiers::default(), text: None, repeat: false }),
        core::Event::Keyboard(keyboard::Event::KeyReleased { key: enter.clone(), modified_key: enter, physical_key: key::Physical::Code(key::Code::Enter), location: Location::Standard, modifiers: Modifiers::default() }),
    ]
}
