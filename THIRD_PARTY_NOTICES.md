# Third-party notices

## Termux terminal libraries
- **What:** `com.github.termux.termux-app:terminal-emulator` and `:terminal-view`, version `v0.118.0`
  (https://github.com/termux/termux-app), used for the interactive terminal (`ForgeTerminalClients.kt`,
  `TerminalScreen.kt`).
- **Licence:** the `termux/termux-app` repository is released under GPLv3 only
  (https://www.gnu.org/licenses/gpl-3.0.html). Its `LICENSE.md` makes an exception for code from
  [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator) by Jack Palevich, released under
  the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0), which it locates in the `terminal-view` and
  `terminal-emulator` libraries. Those libraries have no licence file of their own, so this app treats them as GPLv3.
- **If you distribute this app to other people:** release it under GPLv3 together with its complete source code and this
  notice. Using it yourself or inside one organisation is not distribution. See `docs/TERMINAL.md`.
- **Copyright:** the Termux contributors; Jack Palevich for the Android-Terminal-Emulator portions.
