# Screenshots of the main screens

Renders real composables at the phone sizes the app targets (Robolectric, dark theme, Indonesian labels, which are the
longest). It is a way to look at the layout, not an automatic pass/fail comparison: there are no reference images yet.
One scenario does fail on its own: `diagnosticsEmptyLog` throws if a tool screen with an empty log box can no longer be
laid out (the crash that force-closed Diagnostics, Build Matrix, SSH and ROM Studio).

## Run it with one click
GitHub > **Actions** > **Screenshots** > **Run workflow**. When it finishes, open the run and download the
**screenshots** artifact (a zip of PNGs named `<screen>-<size>.png`).

## Run it locally
```bash
gradle :app:testDebugUnitTest -PforgeScreenshots --tests "com.nextcraft.forge.ui.Shots*"
# images: app/build/screenshots/
# offline: add -PforgeRoboDir=<dir with android-all-instrumented-*.jar>
```

## Screens covered
Tools (with bottom bar), project header, Projects, Chat, Settings, approval sheet, chat change review, Diagnostics
with an empty log box, and the code viewer diff.

## Turning it into a regression check
Commit reference PNGs and compare them in the test with a small pixel-difference tolerance (generate the references
from the workflow's artifact so fonts match), or adopt Roborazzi's `verify`.
