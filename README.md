# cmus-android

Port of [cmus](https://github.com/cmus/cmus) for Android with system integration and some extra UI features.

I mostly vibe-coded this since it's pretty much all glue code, the extra features are relatively simple and well-defined, and the rest is mostly custom UI components and gesture handling, which are annoying to do by hand.

Although the code is almost entirely written and maintained by Claude, I made most of the higher-level architectural decisions and came up with the features to implement myself, and I read almost all of the thinking output.

I already did most of the actual porting work earlier to make it work well on Termux, including the AAudio output plugin, portability and build fixes, playlist env var stuff, and so on. This project is mostly the UI and system integration.

### Features

- Supports Android 14+.
- Additional touch-friendly UI components.
  - Top bar with live-filter, views, and settings.
  - Bottom bar with play/repeat/shuffle/seek/volume/queue/keyboard.
  - Long-press tracks/playlists to add/remove.
  - Joystick for scrolling and switching panes/views.
  - Graphical settings view for most relevant settings.
- System integration.
  - System media controls and metadata.
  - Stops the cmus process after being idle for a bit to save power, automatically restarting it when focused or media buttons are used.
  - Material You color scheme.
  - Music from external storage.
  - UI colors match cmus theme.
  - Audio ducking.
  - Library paths are relative to data dirs so import/export works correctly.
- Extra features.
  - Album art support.
  - Sleep timer.
  - Font options.
  - Data import/export.
  - Sub-second seeking accuracy.
  - Continuously saves state.
- Optimized for power efficiency.

<!-- TODO: screenshots -->
<!-- TODO: document building, updating lib patches, development, generated files, etc -->
