# Interactive terminal

The Terminal screen is a full VT100/xterm terminal, so editors, pagers and TUIs (`vim`, `nano`, `htop`, `tmux`,
`mc`, `lazygit`…) run in it.

## How it works
- **One persistent shell per project.** With the root chroot installed and root granted it is
  `su -mm -c <chroot-exec.sh … /bin/bash -l>` (built by `RuntimeInstaller.prepareChrootShellCommand`, so the
  environment, workspace mount and secrets handling are the same as every other chroot launch). Without them it is
  the Android shell (`/system/bin/sh`) in the project workspace.
- **PTY + emulator:** the Termux `terminal-emulator` library spawns the process on its own PTY and parses its output
  into a character grid; `terminal-view` draws it. `TerminalView` resizes the PTY to the view (and to the soft
  keyboard), so full-screen programs reflow.
- **Input:** the soft keyboard types straight into the shell. The key row adds Esc, Tab, Ctrl/Alt (armed for the next
  key), arrows, Home/End/PgUp/PgDn, `^C` and `- / | ~`. Pinch zooms the font.
- **Chat integration:** "Run in terminal" on a code block and the quick-command chips type the command and Enter
  into the shell (queued until the shell is up).
- Code: `ui/TerminalScreen.kt` (screen), `ui/ForgeTerminalClients.kt` (emulator callbacks, theming),
  `MainViewModel` ("Interactive terminal" section: sessions, restart, input queue).

## Dependency and licence
`com.github.termux.termux-app:terminal-emulator` and `:terminal-view`, pinned to `v0.118.0`, resolved from JitPack
(the repository is restricted to that one group in `settings.gradle.kts`).

**Licence needs confirming before this ships in a public release.** The `termux/termux-app` repository is GPLv3-only
with an exception for code derived from Jack Palevich's Android-Terminal-Emulator (Apache 2.0); its LICENSE points
at the `terminal-view` and `terminal-emulator` libraries without a licence file of their own, and the published POM
says "Other". Decide with the licence in hand whether linking them is acceptable for this app's distribution; if not,
swap the emulator (JediTerm, or a Compose-native one) behind `ForgeTerminalClients.kt` / `TerminalScreen.kt`.

## Known limits
- Closing a session kills its `su` process; children inside the chroot normally get SIGHUP from the closed PTY, but
  that depends on the root manager's `su` and has not been verified on a device.
- Not verified on a device yet: rendering, keyboard behaviour, colour theming per theme, resize with the keyboard.
