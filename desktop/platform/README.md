# Scoped libcosmic native patches

These MIT-licensed Iced subcrates come from the existing libcosmic pin
`dbc62f2d899cdbfbe43d170689ddc0290db663f4`. The original source SHA-256 manifest
and license are retained in `libcosmic-iced/`. All other toolkit code/dependency
revisions remain locked. The local workspace manifest resolves their original
workspace dependencies to that pin. Package manifests preserve upstream features.

The changed production sources are:

- `winit/src/a11y.rs`: build a real window/child tree; route a native focus/click
  to its target, avoiding the upstream button handler's sibling activation.
  Activation asks for a redraw and leaves the initial placeholder to AccessKit.
- `winit/src/lib.rs`: publish the current hierarchy/focus on redraw through the
  actual native adapter; route incoming actions to the window's live interface.
- `runtime/src/window.rs`, `winit/src/lib.rs`: return an owned native window
  handle, keeping raw display/surface handles alive across asynchronous Wayland
  portal-parent export and the chooser request.
- `widget/src/row.rs`: delegate wrapping-row accessibility to the native row's
  existing children. The upstream wrapper drew/updated/operated its children but
  omitted its accessibility traversal.

Headless application tests exercise real wrapped widget nodes, the exact tree
builder used by the adapter, target-only action routing, quit/palette labels and
layout/focus bounds. A private synthetic portal test exercises actual ashpd/D-Bus
requests. Those checks do not observe an AT-SPI consumer on a live desktop or a
native chooser's actual parent/focus behavior. Both remain final user checks.

The portal uses actual X11 IDs or a Wayland handle exported through ashpd's
maintained implementation of xdg-foreign. No string is fabricated as a native
parent. The headless private protocol fixture explicitly has no native parent.
See the [portal identifier specification](https://flatpak.github.io/xdg-desktop-portal/docs/window-identifiers.html).
