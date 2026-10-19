# Forge Design Study

Interactive artboards for every screen (390×844), one per `*.dc.html`, indexed by `canvas.json`.
Each board exposes a `theme` tweak (linear / system / white / glass) whose palette mirrors `ui/Theme.kt`.

Live canvas: https://claude.ai/artifact/Mgq8C7tfQtXSS17fnjhy9Y (private; share from the page's Share menu).

| Board | Tweaks | Notes |
|---|---|---|
| Main | theme, agentRunning, filesChanged | Sliding pill + glow, badges, per-theme bar material |
| Projects | theme | Collapsible "Bring an existing project" |
| Agent | theme | Agent switcher menu, approval card, send/stop |
| Launch | theme, phase | idle / building / failed / succeeded; auto-jump to Issues |
| Files | theme | Tree / Changes / Editor, New file dialog |
| Terminal | theme | Quick commands, prompt, run/stop |
| Tools | theme | Launcher rows + runtime card |
| Settings | theme | 5 sections, functional toggles |
| Onboarding | theme, stage | welcome / installing / done |
