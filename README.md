# Termux-Ghostty

An experimental Android terminal app using libghostty-vt as the terminal backend + added opinionated qol features.

> ⚠️ **NOTICE**: This is an **unofficial** fork and is **not affiliated with ghostty-org or termux**. It combines features from both projects under their respective licenses (GPLv3 and MIT).

> 🤖 **AI Assistance**: This project is developed with the assistance of AI coding tools.

## Features

- **Ghostty integration** — Uses Ghostty-backed VT parsing and terminal state management inside the app.
- **Workspaces and tabs** — Each workspace has a root folder and its own tabs: terminals, a file browser, and a Markdown / HTML / text viewer. The tabs grid shows a live preview of every terminal and switches or manages workspaces. Workspaces and tabs are restored after the app is killed, with each shell restarted in its last directory.
- **Floating key bar** — Esc, Ctrl, Alt (tap to arm, tap again to lock) and Tab float above the keyboard, next to the button that opens the tabs grid.
- **Clickable terminal links** — `terminal-onclick-url-open = true` enables tap-to-open links; `terminal-onclick-url-open-when-mouse-tracking-active = true` prefers link taps over terminal mouse tracking.
- **Remember soft keyboard state** — `remember-soft-keyboard-state = true` restores the last soft keyboard visibility when reopening the app.
- **Material You theming** — `material-you-theme = "disabled" | "light" | "dark" | "black" | "system"` themes the app chrome and terminal ANSI palette with wallpaper-derived Material 3 colors on Android 12+. `black` uses dynamic accents on pure black. When enabled, `colors.properties` is ignored. Recreates automatically on wallpaper changes.

### Settings

Settings are edited in the app and stored with Jetpack DataStore. Any key in `~/.termux/termux.toml` overrides the in-app value; run `termux-reload-settings` after editing it. `allow-external-apps` can only be set in this file.

```toml
material-you-theme = "system"
allow-external-apps = true
```

`~/.termux/termux.properties` is no longer read. `colors.properties` and `font.ttf` work as before.

The Customization settings cover the wallpaper, cursor trail, effect frame rate, animations and custom font. Each one is also a `termux.toml` key:

| Key | Default | Values |
| --- | --- | --- |
| `wallpaper` | `true` | `true`, `false`. Shows `~/.termux/wallpaper` when the file exists |
| `wallpaper-background-opacity` | `0.7` | `0` to `1` |
| `wallpaper-scaling` | `"center-crop"` | `"center-crop"`, `"fit-center"`, `"fit-xy"`, `"center"` |
| `cursor-trail` | `"warp"` | `"none"`, `"warp"`, `"sweep"`, `"tail"` |
| `arrow-joystick` | `"swipe"` | `"swipe"`, `"hold"` |
| `visual-effect-frame-rate` | `"vsync"` | `"vsync"`, `"30"`, `"60"`, `"90"`, `"120"` |
| `animations` | `true` | `true`, `false` |
| `custom-font-for-ui` | `false` | `true`, `false` |

Choosing a wallpaper copies the image to `~/.termux/wallpaper`, and choosing a font copies it to `~/.termux/font.ttf`. Removing or resetting either one deletes the file.

## Ecto (`compose-app`)

A vibe coding companion for Android — an SSH client with Herdr integration and a Ghostty terminal backend, built as a separate Jetpack Compose app (`com.mrndtvndv.term`).

### Screenshots

| Local & SSH | Herdr Workspaces | Git Review |
| :---: | :---: | :---: |
| ![Local & SSH](compose-app/screenshots/local-and-ssh.png) | ![Herdr Workspaces](compose-app/screenshots/herdr-workspaces-integration.png) | ![Git Review](compose-app/screenshots/git-tab.png) |
| **Diff Viewer** | **SFTP Browser** | **Cursor Effects** |
| ![Diff Viewer](compose-app/screenshots/diff.png) | ![SFTP Browser](compose-app/screenshots/sftp.png) | ![Cursor Effects](compose-app/screenshots/shaders.png) |

### Features

- **Ghostty terminal backend** — Native VT parsing and terminal state via `libghostty-vt`, rendered with hardware-accelerated OpenGL ES by default.
- **SSH client** — Native SSH sessions with a server manager (add/edit servers, one-tap connect).
- **Herdr integration** — Queries `herdr workspace list` / `herdr pane list` and tracks navigation state per workspace.
- **Git review tab** — Review, stage, and commit changes, edit commit messages, and reset to commits from the app.
- **SFTP tab** — Browse and transfer files on the connected server, synced to the active workspace's directory.
- **Cursor effects** — Warp, Sweep, and Tail cursor trails (adapted from ghostty-cursor-shaders).
- **Local terminal sessions** — Drop into a local shell when you don't need SSH; off by default, turned on in the settings.
- **Split & tabbed workspaces** — Multiple terminals per workspace.
- **Material You theming** — Dynamic wallpaper-derived colors.

### This fork

[lucio-ferrao/termux-ghostty](https://github.com/lucio-ferrao/termux-ghostty) (branch `dev`) adds to Ecto:

- **Keys that never leave the phone** — SSH authenticates only with an ECDSA P-256 key made in the Android Keystore (StrongBox where there is one), not exportable, each use confirmed with the fingerprint; no passwords. The server's host key is shown (SHA-256) and confirmed on the first connection, and a changed one is refused before anything is sent. App data is not backed up.
- **Herdr** — Each herdr pane is a dynamic shortcut while the server is connected (its title, the agent's state, opening Ecto on that pane), so a launcher like [Keyarchy](https://github.com/lucio-ferrao/Keyarchy) lists them. Agent states use herdr's colors (Catppuccin), in the agents list and on the button's dot. The full-UI tab follows herdr's focused pane. No notification when an agent finishes, only when one needs attention. `~/.local/bin` is in the PATH of remote commands, where herdr is usually installed.
- **The keyboard knows what runs** — Each tab tells the keyboard, in `EditorInfo.privateImeOptions` (`org.ferrao.homekey.context=agent:claude`, `shell:bash`, `fullscreen:vim`), so [Keyarchy](https://github.com/lucio-ferrao/Keyarchy) types prose for an agent and commands for a shell. When the program reads a password (herdr's `input_mode`, from the [herdr fork](https://github.com/lucio-ferrao/herdr/tree/password-input)), the field becomes a password field: nothing suggested or learned. In a shell, the server's commands (`compgen -c`) and the files and folders of the pane's folder go to the keyboard in `EditorInfo.extras`, for its suggestions.
- **Key bar as a keyboard row** — Full width and as tall as Keyarchy's suggestion strip, in its dark colors, with the tabs button (Ecto's ghost) on the left, aligned with Keyarchy's key. The keyboard opens whenever you come back to a terminal.
- **JetBrains Mono** — The default font, regular and bold, as in Ghostty, with its ligatures (neighbouring cells are shaped together and drawn by glyph). Noto Sans Symbols 2 and Symbols Nerd Font Mono fill in symbols and icons (`eza --icons`). An imported font still wins.
- **One server, shorter titles** — With a single saved server, pane, tab and shortcut titles drop the `user@host:`.

## Status

Experimental and fast-moving.
