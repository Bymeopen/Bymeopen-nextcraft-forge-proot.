# On-device test checklist

CI proves the app compiles and the unit tests pass. These checks need a real device.

## Accessibility
- [ ] TalkBack on: every screen reads in a sensible order; icon buttons are named; the terminal key row reads "Escape", "Control (toggle)", etc.
- [ ] Settings > Display > Font size and Display size at maximum (fontScale 2.0): no clipped or overlapping text in the bottom bar, chat, Settings, Tools.
- [ ] Landscape on every tab: the composer and the key row stay reachable above the keyboard.

## Chat polish (this release)
- [ ] Scroll up in a long chat: the "Terbaru / Latest" pill is opaque and does not show text through it.
- [ ] Short user message: the bubble wraps its text and sits at the right edge.
- [ ] Indonesian: the bottom bar shows "Setelan" without truncation.

## Language
- [ ] Switch EN <-> ID: Claude and Google (Antigravity) sign-in, Snapshots and CI/CD messages change language.
