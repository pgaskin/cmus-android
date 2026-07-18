# cmus android app

Wrapper for the cmus music player using termux libs.

- Written in Java, not Kotlin.
- minimal app layout remove androidTest folder, gitignore all .idea, etc)
- Minimum Android 14+.
- Patched ndk-based cmus build.
- remove support for remote streams (keep this minimal, it's not a security feature)
- Take cmus from master, put patches in separate dir (with script to update them, see ~/src/vncpatch), clone cmus and deps as submodules.
- Cmus includes plugins: vorbis, opus, wav, wavpack, flac, mad, aaudio, aac
- IPC is done over a unix socket (specifically for this app, look at mpris.c for something similar)
- Adds some basic UI controls (commands are sent over the unix socket)
  - UI chrome matches cmus theme
  - Same for the android status bar
  - Controls can be enabled/disabled on settings page (small faint round settings icon in top-right)
  - View selector (as a compact tab bar at the top, text only, blends with tui)
  - top mini toolbar icons (part of tab bar, icons only, left: quick filter -- turns the main tabs into a search box and focuses library and changes the right icons into an x to clear and exit live-filter mode; right: sleep-timer (shows a duration selector when pressed and icon changes to the time left in minutes
  ), app-settings)
  - hold app settings icon to display theme selector (overlay in middle, no scrim, changes theme as selected, scrollable; two columns, second is font selector)
  - Play/pause/repeat/shuffle/seek-bar/volume-button-which-toggles-vertical-slider/add-selected-to-queue, keyboard-show-button (as compact bottom bar)
  - Mouse (i.e., touch) support like termux; scrolling by sliding finger, tapping, etc, pinch to zoom font
  - Joystick dot (tap, slide up/down, sliding far left is tab key); bottom-right, faint, minecraft-style
  - Basic horizontally scrolling extension keyboard bar when keyboarf visible (shift/ctrl/alt, space, del/esc/tab, space, up/down/left/right, space, ome/end/page-up/page-down)
  - long-press sends right-click
  - right-click is bound to win-remove, shows native confirm dialog with selected item first
  - no text selection support
  - settings ui shows mostly app settings, and a small selection of cmus ones
    - aaudio op settings
    - pause on output change
    - softvol enable/disable (only show the volume slider if this is enabled)
    - option to quit cmus internally when idle (not playing) and app is not focused for X minutes
    - import/export data as tar
    - load tracks from local folder
- use ipc to get cmus settings so it's always up-to-date
- media control integration (by modifying the existing mpris support and adding java-side cover art extraction).
- no full saf support for no, maybe later
- keep cmus changes as self-contained as possible (if you need static funcs, make a minimal non-static wrapper and declare+use it in the android.c file)

development
- store relevant stuff in this notes folder, not your memory
- keep a status.md with a log of your changes, enough to give context
- keep an architecture.md
- before implementing features, create, let me approve, then commit a stage plan in notes/plans
- commit as you (I give you permission for this project)
- use modern java features
- I can provide a real android device to test on, do not use the emulator
- if you need sources for libs to read (not to edit), clone it into ~/srctest/dl if it doesn't already exist there
- android sdk in ~/sdk/android
