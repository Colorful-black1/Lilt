//! Windows notification-area icon, tray menu and system-wide hotkeys.
//!
//! The tray and hotkey crates own hidden message windows. Both are created on
//! the same thread as the winit event loop, and their handlers only enqueue an
//! action and wake egui; every playback change still happens in `LiltApp`.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DesktopAction {
    Show,
    TogglePlay,
    Previous,
    Next,
    VolumeUp,
    VolumeDown,
    ToggleLyrics,
    Quit,
}

#[cfg(target_os = "windows")]
mod platform {
    use super::DesktopAction;
    use crate::icon;
    use crate::model::Language;
    use eframe::egui;
    use global_hotkey::{
        hotkey::{Code, HotKey, Modifiers},
        GlobalHotKeyEvent, GlobalHotKeyManager, HotKeyState,
    };
    use std::ffi::c_void;
    use std::fs::OpenOptions;
    use std::io::Write;
    use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread::{self, JoinHandle};
    use std::time::Duration;
    use tray_icon::{
        menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem},
        MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent,
    };

    const MENU_SHOW: &str = "show";
    const MENU_PLAY_PAUSE: &str = "play_pause";
    const MENU_PREVIOUS: &str = "previous";
    const MENU_NEXT: &str = "next";
    const MENU_QUIT: &str = "quit";

    type ActionQueue = Arc<Mutex<Vec<DesktopAction>>>;

    pub struct DesktopIntegration {
        tray: TrayIcon,
        show_item: MenuItem,
        play_pause_item: MenuItem,
        previous_item: MenuItem,
        next_item: MenuItem,
        quit_item: MenuItem,
        actions: ActionQueue,
        main_window: Arc<AtomicIsize>,
        hidden: Arc<AtomicBool>,
        heartbeat: Arc<AtomicBool>,
        stop: Arc<AtomicBool>,
        heartbeat_thread: Option<JoinHandle<()>>,
        manager: Option<GlobalHotKeyManager>,
        hotkeys: Vec<HotKey>,
        hotkeys_active: bool,
        hotkey_error: Option<String>,
        labels: Option<(Language, bool)>,
        tooltip: String,
        tray_ready: bool,
    }

    impl DesktopIntegration {
        pub fn new(
            ctx: &egui::Context,
            language: Language,
            hotkeys_enabled: bool,
        ) -> Result<Self, String> {
            let actions: ActionQueue = Arc::new(Mutex::new(Vec::new()));
            let main_window = Arc::new(AtomicIsize::new(0));

            let menu = Menu::new();
            let show_item = MenuItem::with_id(
                MENU_SHOW,
                label(language, "Show Lilt", "显示 Lilt"),
                true,
                None,
            );
            let play_pause_item =
                MenuItem::with_id(MENU_PLAY_PAUSE, label(language, "Play", "播放"), true, None);
            let previous_item = MenuItem::with_id(
                MENU_PREVIOUS,
                label(language, "Previous", "上一首"),
                true,
                None,
            );
            let next_item =
                MenuItem::with_id(MENU_NEXT, label(language, "Next", "下一首"), true, None);
            let quit_item = MenuItem::with_id(
                MENU_QUIT,
                label(language, "Quit Lilt", "退出 Lilt"),
                true,
                None,
            );
            let separator = PredefinedMenuItem::separator();
            menu.append_items(&[
                &show_item,
                &play_pause_item,
                &previous_item,
                &next_item,
                &separator,
                &quit_item,
            ])
            .map_err(|error| format!("tray menu: {error}"))?;

            let tray_icon =
                tray_icon::Icon::from_rgba(icon::app_icon_rgba(), icon::ICON_SIZE, icon::ICON_SIZE)
                    .map_err(|error| format!("tray icon pixels: {error}"))?;
            let tray = TrayIconBuilder::new()
                .with_tooltip("Lilt")
                .with_icon(tray_icon)
                .with_menu(Box::new(menu))
                .with_menu_on_left_click(false)
                .build()
                .map_err(|error| format!("tray icon: {error}"))?;

            // Install the global handlers only after the tray exists, so a failed
            // build can be retried with a fresh action queue.
            let menu_queue = actions.clone();
            let menu_ctx = ctx.clone();
            let menu_window = main_window.clone();
            MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
                let action = menu_action(event.id.0.as_str());
                if let Some(action) = action {
                    log_line(&format!("tray menu {action:?}"));
                    push_action(&menu_queue, action);
                    wake(&menu_ctx, &menu_window);
                }
            }));

            let click_queue = actions.clone();
            let click_ctx = ctx.clone();
            let click_window = main_window.clone();
            TrayIconEvent::set_event_handler(Some(move |event: TrayIconEvent| {
                if let TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                } = event
                {
                    log_line("tray click Show");
                    push_action(&click_queue, DesktopAction::Show);
                    wake(&click_ctx, &click_window);
                }
            }));

            install_hotkey_handler(ctx, &actions, &main_window);
            let tray_ready = tray.rect().is_some();
            if !tray_ready {
                log_line("tray icon not registered yet");
            }

            let hidden = Arc::new(AtomicBool::new(false));
            let heartbeat = Arc::new(AtomicBool::new(false));
            let stop = Arc::new(AtomicBool::new(false));
            let heartbeat_thread = {
                let window = main_window.clone();
                let hidden = hidden.clone();
                let heartbeat = heartbeat.clone();
                let stop = stop.clone();
                thread::spawn(move || {
                    while !stop.load(Ordering::Relaxed) {
                        if hidden.load(Ordering::Relaxed) && heartbeat.load(Ordering::Relaxed) {
                            let hwnd = window.load(Ordering::Relaxed);
                            if hwnd != 0 {
                                // SAFETY: PostMessage only needs the handle to
                                // stay valid for the duration of the call; a
                                // destroyed window makes it return an error.
                                unsafe {
                                    PostMessageW(hwnd as *mut c_void, WM_PAINT, 0, 0);
                                }
                            }
                        }
                        thread::sleep(Duration::from_millis(100));
                    }
                })
            };

            let mut integration = Self {
                tray,
                show_item,
                play_pause_item,
                previous_item,
                next_item,
                quit_item,
                actions,
                main_window,
                hidden,
                heartbeat,
                stop,
                heartbeat_thread: Some(heartbeat_thread),
                manager: None,
                hotkeys: Vec::new(),
                hotkeys_active: false,
                hotkey_error: None,
                labels: None,
                tooltip: String::new(),
                tray_ready,
            };
            integration.set_hotkeys_enabled(hotkeys_enabled);
            log_line("tray ready");
            Ok(integration)
        }

        /// Register or release the shortcuts. Safe to call every frame.
        pub fn set_hotkeys_enabled(&mut self, enabled: bool) {
            if enabled == self.hotkeys_active {
                return;
            }
            if enabled {
                if self.manager.is_none() {
                    match GlobalHotKeyManager::new() {
                        Ok(manager) => self.manager = Some(manager),
                        Err(error) => {
                            self.hotkey_error = Some(format!("hotkeys: {error}"));
                            log_line("hotkeys unavailable");
                            return;
                        }
                    }
                }
                let Some(manager) = &self.manager else {
                    return;
                };
                self.hotkeys.clear();
                let mut failures = Vec::new();
                for (hotkey, action) in hotkey_definitions() {
                    match manager.register(hotkey) {
                        Ok(()) => self.hotkeys.push(hotkey),
                        Err(error) => failures.push(format!("{}: {error}", hotkey_name(action))),
                    }
                }
                self.hotkeys_active = true;
                self.hotkey_error = if failures.is_empty() {
                    None
                } else {
                    log_line(&format!("hotkey failures: {}", failures.join("; ")));
                    Some(failures.join("; "))
                };
                log_line(&format!("hotkeys registered: {}", self.hotkeys.len()));
            } else {
                if let Some(manager) = &self.manager {
                    for hotkey in &self.hotkeys {
                        let _ = manager.unregister(*hotkey);
                    }
                }
                self.hotkeys.clear();
                self.hotkeys_active = false;
                self.hotkey_error = None;
                log_line("hotkeys released");
            }
        }

        pub fn hotkey_error(&self) -> Option<&str> {
            self.hotkey_error.as_deref()
        }

        /// Close-to-tray stays disabled until the shell actually shows the icon.
        pub fn tray_ready(&self) -> bool {
            self.tray_ready
        }

        /// Called once per frame with the root window handle. Hidden windows do
        /// not receive scheduled redraws on Windows, so native callbacks post a
        /// paint message here to run the queued action.
        pub fn set_main_window(&self, hwnd: isize) {
            if self.main_window.swap(hwnd, Ordering::Relaxed) != hwnd {
                log_line(&format!("main window handle {hwnd}"));
            }
        }

        pub fn set_hidden(&self, hidden: bool) {
            self.hidden.store(hidden, Ordering::Relaxed);
        }

        /// Keep a low-rate paint heartbeat while the hidden app still needs
        /// update ticks (playback, scan progress, sleep timer or a pending save).
        pub fn set_heartbeat(&self, active: bool) {
            self.heartbeat.store(active, Ordering::Relaxed);
        }

        pub fn pending_actions(&self) -> usize {
            self.actions
                .lock()
                .map(|guard| guard.len())
                .unwrap_or_default()
        }

        pub fn poll_actions(&mut self) -> Vec<DesktopAction> {
            let mut guard = self
                .actions
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            std::mem::take(&mut *guard)
        }

        /// Keep the menu wording and tooltip in step with playback and language.
        pub fn sync(&mut self, language: Language, playing: bool, track: Option<&str>) {
            if !self.tray_ready && self.tray.rect().is_some() {
                self.tray_ready = true;
                log_line("tray icon registered");
            }
            if self.labels != Some((language, playing)) {
                self.show_item
                    .set_text(label(language, "Show Lilt", "显示 Lilt"));
                self.play_pause_item.set_text(if playing {
                    label(language, "Pause", "暂停")
                } else {
                    label(language, "Play", "播放")
                });
                self.previous_item
                    .set_text(label(language, "Previous", "上一首"));
                self.next_item.set_text(label(language, "Next", "下一首"));
                self.quit_item
                    .set_text(label(language, "Quit Lilt", "退出 Lilt"));
                self.labels = Some((language, playing));
            }
            let mut tooltip = String::from("Lilt");
            if let Some(track) = track {
                let trimmed: String = track.chars().take(96).collect();
                tooltip.push_str(" — ");
                tooltip.push_str(trimmed.trim());
            }
            if tooltip != self.tooltip {
                let _ = self.tray.set_tooltip(Some(&tooltip));
                self.tooltip = tooltip;
            }
        }
    }

    impl Drop for DesktopIntegration {
        fn drop(&mut self) {
            self.heartbeat.store(false, Ordering::Relaxed);
            self.stop.store(true, Ordering::Relaxed);
            if let Some(thread) = self.heartbeat_thread.take() {
                let _ = thread.join();
            }
            if let Some(manager) = &self.manager {
                for hotkey in &self.hotkeys {
                    let _ = manager.unregister(*hotkey);
                }
            }
            let _ = self.tray.set_visible(false);
            log_line("tray removed");
        }
    }

    fn menu_action(id: &str) -> Option<DesktopAction> {
        match id {
            MENU_SHOW => Some(DesktopAction::Show),
            MENU_PLAY_PAUSE => Some(DesktopAction::TogglePlay),
            MENU_PREVIOUS => Some(DesktopAction::Previous),
            MENU_NEXT => Some(DesktopAction::Next),
            MENU_QUIT => Some(DesktopAction::Quit),
            _ => None,
        }
    }

    fn label(language: Language, en: &'static str, zh: &'static str) -> &'static str {
        if language == Language::Zh {
            zh
        } else {
            en
        }
    }

    fn push_action(queue: &ActionQueue, action: DesktopAction) {
        if let Ok(mut guard) = queue.lock() {
            guard.push(action);
        }
    }

    /// Append one line to the optional test hook file. Never creates the file
    /// unless `LILT_DESKTOP_LOG` points at a usable path.
    pub fn log_line(text: &str) {
        let Ok(path) = std::env::var("LILT_DESKTOP_LOG") else {
            return;
        };
        if path.trim().is_empty() {
            return;
        }
        if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(file, "{text}");
        }
    }

    fn install_hotkey_handler(
        ctx: &egui::Context,
        actions: &ActionQueue,
        main_window: &Arc<AtomicIsize>,
    ) {
        let queue = actions.clone();
        let ctx = ctx.clone();
        let window = main_window.clone();
        let map: Vec<(u32, DesktopAction)> = hotkey_definitions()
            .iter()
            .map(|(hotkey, action)| (hotkey.id(), *action))
            .collect();
        GlobalHotKeyEvent::set_event_handler(Some(move |event: GlobalHotKeyEvent| {
            if event.state != HotKeyState::Pressed {
                return;
            }
            if let Some((_, action)) = map.iter().find(|(id, _)| *id == event.id) {
                log_line(&format!("hotkey {action:?}"));
                push_action(&queue, *action);
                wake(&ctx, &window);
            }
        }));
    }

    /// Wake the UI thread even when the root window is hidden. `WM_PAINT` is
    /// delivered directly to the window procedure, and winit turns it into a
    /// `RedrawRequested` event, which is exactly eframe's update path.
    fn wake(ctx: &egui::Context, main_window: &AtomicIsize) {
        ctx.request_repaint();
        let hwnd = main_window.load(Ordering::Relaxed);
        if hwnd != 0 {
            // SAFETY: `hwnd` is the live root window handle reported by eframe.
            unsafe {
                PostMessageW(hwnd as *mut c_void, WM_PAINT, 0, 0);
            }
        }
    }

    const WM_PAINT: u32 = 0x000F;

    #[link(name = "user32")]
    extern "system" {
        fn PostMessageW(hwnd: *mut c_void, msg: u32, wparam: usize, lparam: isize) -> i32;
    }

    fn modifiers() -> Modifiers {
        Modifiers::CONTROL | Modifiers::ALT
    }

    fn hotkey_definitions() -> [(HotKey, DesktopAction); 6] {
        [
            (
                HotKey::new(Some(modifiers()), Code::Space),
                DesktopAction::TogglePlay,
            ),
            (
                HotKey::new(Some(modifiers()), Code::ArrowLeft),
                DesktopAction::Previous,
            ),
            (
                HotKey::new(Some(modifiers()), Code::ArrowRight),
                DesktopAction::Next,
            ),
            (
                HotKey::new(Some(modifiers()), Code::ArrowUp),
                DesktopAction::VolumeUp,
            ),
            (
                HotKey::new(Some(modifiers()), Code::ArrowDown),
                DesktopAction::VolumeDown,
            ),
            (
                HotKey::new(Some(modifiers()), Code::KeyL),
                DesktopAction::ToggleLyrics,
            ),
        ]
    }

    fn hotkey_name(action: DesktopAction) -> &'static str {
        match action {
            DesktopAction::TogglePlay => "Ctrl+Alt+Space",
            DesktopAction::Previous => "Ctrl+Alt+Left",
            DesktopAction::Next => "Ctrl+Alt+Right",
            DesktopAction::VolumeUp => "Ctrl+Alt+Up",
            DesktopAction::VolumeDown => "Ctrl+Alt+Down",
            DesktopAction::ToggleLyrics => "Ctrl+Alt+L",
            DesktopAction::Show | DesktopAction::Quit => "tray",
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn tray_menu_ids_map_to_actions() {
            assert_eq!(menu_action(MENU_SHOW), Some(DesktopAction::Show));
            assert_eq!(
                menu_action(MENU_PLAY_PAUSE),
                Some(DesktopAction::TogglePlay)
            );
            assert_eq!(menu_action(MENU_PREVIOUS), Some(DesktopAction::Previous));
            assert_eq!(menu_action(MENU_NEXT), Some(DesktopAction::Next));
            assert_eq!(menu_action(MENU_QUIT), Some(DesktopAction::Quit));
            assert_eq!(menu_action("unknown"), None);
        }

        #[test]
        fn six_shortcuts_have_unique_ids() {
            let definitions = hotkey_definitions();
            assert_eq!(definitions.len(), 6);
            let mut ids: Vec<u32> = definitions.iter().map(|(hotkey, _)| hotkey.id()).collect();
            ids.sort_unstable();
            ids.dedup();
            assert_eq!(ids.len(), 6);
            assert_eq!(hotkey_name(DesktopAction::TogglePlay), "Ctrl+Alt+Space");
            assert_eq!(hotkey_name(DesktopAction::ToggleLyrics), "Ctrl+Alt+L");
        }
    }
}

#[cfg(not(target_os = "windows"))]
mod platform {
    use super::DesktopAction;
    use crate::model::Language;
    use eframe::egui;

    /// The desktop target of this build is Windows. Other platforms still
    /// compile with the same API, without a tray icon or global shortcuts.
    pub struct DesktopIntegration;

    impl DesktopIntegration {
        pub fn new(
            _ctx: &egui::Context,
            _language: Language,
            _hotkeys_enabled: bool,
        ) -> Result<Self, String> {
            Err("tray integration is built for Windows only".to_owned())
        }

        pub fn set_hotkeys_enabled(&mut self, _enabled: bool) {}

        pub fn set_main_window(&self, _hwnd: isize) {}

        pub fn set_hidden(&self, _hidden: bool) {}

        pub fn set_heartbeat(&self, _active: bool) {}

        pub fn hotkey_error(&self) -> Option<&str> {
            None
        }

        pub fn tray_ready(&self) -> bool {
            false
        }

        pub fn pending_actions(&self) -> usize {
            0
        }

        pub fn poll_actions(&mut self) -> Vec<DesktopAction> {
            Vec::new()
        }

        pub fn sync(&mut self, _language: Language, _playing: bool, _track: Option<&str>) {}
    }

    pub fn log_line(_text: &str) {}
}

pub use platform::*;
