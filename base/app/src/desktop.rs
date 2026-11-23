//! Windows notification-area icon, tray menu, system-wide hotkeys and SMTC.
//!
//! The tray and hotkey crates own hidden message windows. Both are created on
//! the same thread as the winit event loop, and their handlers only enqueue an
//! action and wake egui; every playback change still happens in `LiltApp`.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DesktopAction {
    Show,
    Play,
    Pause,
    TogglePlay,
    Previous,
    Next,
    VolumeUp,
    VolumeDown,
    ToggleDesktopLyrics,
    ToggleLyricsLock,
    ToggleMiniPlayer,
    Quit,
}

/// Metadata and timeline for the Windows system media flyout.
#[derive(Clone, Debug, Default)]
pub struct MediaInfo {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub position: f64,
    pub duration: f64,
    pub shuffle: bool,
    pub repeat: crate::model::RepeatMode,
}

#[cfg(target_os = "windows")]
mod platform {
    use super::{DesktopAction, MediaInfo};
    use crate::icon;
    use crate::model::{Language, RepeatMode};
    use eframe::egui;
    use global_hotkey::{
        hotkey::{Code, HotKey, Modifiers},
        GlobalHotKeyEvent, GlobalHotKeyManager, HotKeyState,
    };
    use std::ffi::c_void;
    use std::fs::OpenOptions;
    use std::io::Write;
    use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU64, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread::{self, JoinHandle};
    use std::time::{Duration, Instant};
    use tray_icon::{
        menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem},
        MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent,
    };
    use windows::core::HSTRING;
    use windows::Foundation::{TimeSpan, TypedEventHandler};
    use windows::Media::{
        MediaPlaybackAutoRepeatMode, MediaPlaybackStatus, MediaPlaybackType,
        SystemMediaTransportControls, SystemMediaTransportControlsButton,
        SystemMediaTransportControlsButtonPressedEventArgs,
        SystemMediaTransportControlsTimelineProperties,
    };
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::WinRT::{
        ISystemMediaTransportControlsInterop, RoInitialize, RO_INIT_MULTITHREADED,
    };

    const MENU_SHOW: &str = "show";
    const MENU_PLAY_PAUSE: &str = "play_pause";
    const MENU_PREVIOUS: &str = "previous";
    const MENU_NEXT: &str = "next";
    const MENU_DESKTOP_LYRICS: &str = "desktop_lyrics";
    const MENU_LYRICS_LOCK: &str = "lyrics_lock";
    const MENU_MINI_PLAYER: &str = "mini_player";
    const MENU_QUIT: &str = "quit";

    type ActionQueue = Arc<Mutex<Vec<DesktopAction>>>;

    #[repr(C)]
    struct WinRect {
        left: i32,
        top: i32,
        right: i32,
        bottom: i32,
    }

    static LYRICS_LAYER_HWND: AtomicIsize = AtomicIsize::new(0);
    static LYRICS_LAYER_SIZE: AtomicU64 = AtomicU64::new(0);
    static MINI_LAYER_HWND: AtomicIsize = AtomicIsize::new(0);
    static MINI_LAYER_SIZE: AtomicU64 = AtomicU64::new(0);

    pub struct DesktopIntegration {
        tray: TrayIcon,
        show_item: MenuItem,
        play_pause_item: MenuItem,
        previous_item: MenuItem,
        next_item: MenuItem,
        desktop_lyrics_item: MenuItem,
        lyrics_lock_item: MenuItem,
        mini_player_item: MenuItem,
        quit_item: MenuItem,
        actions: ActionQueue,
        ctx: egui::Context,
        main_window: Arc<AtomicIsize>,
        hidden: Arc<AtomicBool>,
        heartbeat: Arc<AtomicBool>,
        stop: Arc<AtomicBool>,
        heartbeat_thread: Option<JoinHandle<()>>,
        manager: Option<GlobalHotKeyManager>,
        hotkeys: Vec<HotKey>,
        hotkeys_active: bool,
        hotkey_error: Option<String>,
        media: Option<MediaControl>,
        media_error: Option<String>,
        media_attempted: bool,
        labels: Option<(Language, bool, bool, bool, bool)>,
        tooltip: String,
        tray_ready: bool,
    }

    struct MediaControl {
        controls: SystemMediaTransportControls,
        button_token: i64,
        last_title: String,
        last_artist: String,
        last_album: String,
        last_status: Option<MediaPlaybackStatus>,
        last_shuffle: Option<bool>,
        last_repeat: Option<RepeatMode>,
        last_duration: f64,
        last_position: f64,
        last_timeline: Instant,
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
            let desktop_lyrics_item = MenuItem::with_id(
                MENU_DESKTOP_LYRICS,
                label(language, "Desktop lyrics", "桌面歌词"),
                true,
                None,
            );
            let lyrics_lock_item = MenuItem::with_id(
                MENU_LYRICS_LOCK,
                label(language, "Lock desktop lyrics", "锁定桌面歌词"),
                true,
                None,
            );
            let mini_player_item = MenuItem::with_id(
                MENU_MINI_PLAYER,
                label(language, "Mini player", "迷你播放器"),
                true,
                None,
            );
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
                &desktop_lyrics_item,
                &lyrics_lock_item,
                &mini_player_item,
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
                desktop_lyrics_item,
                lyrics_lock_item,
                mini_player_item,
                quit_item,
                actions,
                ctx: ctx.clone(),
                main_window,
                hidden,
                heartbeat,
                stop,
                heartbeat_thread: Some(heartbeat_thread),
                manager: None,
                hotkeys: Vec::new(),
                hotkeys_active: false,
                hotkey_error: None,
                media: None,
                media_error: None,
                media_attempted: false,
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

        pub fn media_error(&self) -> Option<&str> {
            self.media_error.as_deref()
        }

        /// Close-to-tray stays disabled until the shell actually shows the icon.
        pub fn tray_ready(&self) -> bool {
            self.tray_ready
        }

        /// Called once per frame with the root window handle. Hidden windows do
        /// not receive scheduled redraws on Windows, so native callbacks post a
        /// paint message here to run the queued action.
        pub fn set_main_window(&mut self, hwnd: isize) {
            if self.main_window.swap(hwnd, Ordering::Relaxed) != hwnd {
                log_line(&format!("main window handle {hwnd}"));
            }
            if !self.media_attempted && hwnd != 0 {
                self.media_attempted = true;
                match MediaControl::new(
                    hwnd,
                    self.ctx.clone(),
                    self.actions.clone(),
                    self.main_window.clone(),
                ) {
                    Ok(media) => {
                        log_line("SMTC enabled");
                        self.media = Some(media);
                        self.media_error = None;
                    }
                    Err(error) => {
                        log_line(&format!("SMTC unavailable: {error}"));
                        self.media_error = Some(error);
                    }
                }
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
        pub fn sync(
            &mut self,
            language: Language,
            playing: bool,
            track: Option<&str>,
            desktop_lyrics: bool,
            lyrics_locked: bool,
            mini_player: bool,
        ) {
            if !self.tray_ready && self.tray.rect().is_some() {
                self.tray_ready = true;
                log_line("tray icon registered");
            }
            let labels = (
                language,
                playing,
                desktop_lyrics,
                lyrics_locked,
                mini_player,
            );
            if self.labels != Some(labels) {
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
                self.desktop_lyrics_item.set_text(if desktop_lyrics {
                    label(language, "Hide desktop lyrics", "隐藏桌面歌词")
                } else {
                    label(language, "Desktop lyrics", "显示桌面歌词")
                });
                self.lyrics_lock_item.set_text(if lyrics_locked {
                    label(language, "Unlock desktop lyrics", "解锁桌面歌词")
                } else {
                    label(language, "Lock desktop lyrics", "锁定桌面歌词")
                });
                self.mini_player_item.set_text(if mini_player {
                    label(language, "Hide mini player", "隐藏迷你播放器")
                } else {
                    label(language, "Mini player", "显示迷你播放器")
                });
                self.quit_item
                    .set_text(label(language, "Quit Lilt", "退出 Lilt"));
                self.labels = Some(labels);
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

        pub fn sync_media(&mut self, info: &MediaInfo) {
            let Some(media) = &mut self.media else {
                return;
            };
            if let Err(error) = media.sync(info) {
                log_line(&format!("SMTC sync error: {error}"));
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
            if let Some(media) = &self.media {
                let _ = media.controls.SetIsEnabled(false);
                let _ = media.controls.RemoveButtonPressed(media.button_token);
            }
            let _ = self.tray.set_visible(false);
            log_line("tray removed");
        }
    }

    impl MediaControl {
        fn new(
            hwnd: isize,
            ctx: egui::Context,
            actions: ActionQueue,
            main_window: Arc<AtomicIsize>,
        ) -> Result<Self, String> {
            // winit has usually initialized COM for its windows. RoInitialize
            // returns RPC_E_CHANGED_MODE when that was an STA; the later WinRT
            // calls still work in that case.
            unsafe {
                let _ = RoInitialize(RO_INIT_MULTITHREADED);
            }
            let interop: ISystemMediaTransportControlsInterop = windows::core::factory::<
                SystemMediaTransportControls,
                ISystemMediaTransportControlsInterop,
            >()
            .map_err(|error| format!("SMTC factory: {error}"))?;
            let controls: SystemMediaTransportControls =
                unsafe { interop.GetForWindow(HWND(hwnd as *mut c_void)) }
                    .map_err(|error| format!("SMTC window: {error}"))?;
            controls
                .SetIsEnabled(true)
                .map_err(|error| format!("SMTC enable: {error}"))?;
            for result in [
                controls.SetIsPlayEnabled(true),
                controls.SetIsPauseEnabled(true),
                controls.SetIsStopEnabled(false),
                controls.SetIsNextEnabled(true),
                controls.SetIsPreviousEnabled(true),
                controls.SetIsFastForwardEnabled(false),
                controls.SetIsRewindEnabled(false),
            ] {
                result.map_err(|error| format!("SMTC buttons: {error}"))?;
            }
            if let Ok(updater) = controls.DisplayUpdater() {
                let _ = updater.SetType(MediaPlaybackType::Music);
                let _ = updater.SetAppMediaId(&HSTRING::from("Lilt"));
                let _ = updater.Update();
            }

            let queue = actions.clone();
            let window = main_window.clone();
            let ctx = ctx.clone();
            let handler = TypedEventHandler::<
                SystemMediaTransportControls,
                SystemMediaTransportControlsButtonPressedEventArgs,
            >::new(move |_sender, args| {
                let action = match args.as_ref().and_then(|args| args.Button().ok()) {
                    Some(button) if button == SystemMediaTransportControlsButton::Play => {
                        Some(DesktopAction::Play)
                    }
                    Some(button) if button == SystemMediaTransportControlsButton::Pause => {
                        Some(DesktopAction::Pause)
                    }
                    Some(button) if button == SystemMediaTransportControlsButton::Stop => {
                        Some(DesktopAction::Pause)
                    }
                    Some(button) if button == SystemMediaTransportControlsButton::Next => {
                        Some(DesktopAction::Next)
                    }
                    Some(button) if button == SystemMediaTransportControlsButton::Previous => {
                        Some(DesktopAction::Previous)
                    }
                    _ => None,
                };
                if let Some(action) = action {
                    log_line(&format!("SMTC button {action:?}"));
                    push_action(&queue, action);
                    wake(&ctx, &window);
                }
                Ok(())
            });
            let button_token = controls
                .ButtonPressed(&handler)
                .map_err(|error| format!("SMTC handler: {error}"))?;

            Ok(Self {
                controls,
                button_token,
                last_title: String::new(),
                last_artist: String::new(),
                last_album: String::new(),
                last_status: None,
                last_shuffle: None,
                last_repeat: None,
                last_duration: -1.0,
                last_position: -1.0,
                last_timeline: Instant::now() - Duration::from_secs(5),
            })
        }

        fn sync(&mut self, info: &MediaInfo) -> windows::core::Result<()> {
            let has_track = !info.title.is_empty();
            let title = if has_track {
                info.title.clone()
            } else {
                "Lilt".to_owned()
            };
            let artist = if info.artist.is_empty() {
                "Lilt".to_owned()
            } else {
                info.artist.clone()
            };
            let album = info.album.clone();
            let metadata_changed =
                self.last_title != title || self.last_artist != artist || self.last_album != album;
            if metadata_changed {
                let updater = self.controls.DisplayUpdater()?;
                if has_track {
                    updater.SetType(MediaPlaybackType::Music)?;
                    updater.SetAppMediaId(&HSTRING::from("Lilt"))?;
                    let music = updater.MusicProperties()?;
                    music.SetTitle(&HSTRING::from(title.as_str()))?;
                    music.SetArtist(&HSTRING::from(artist.as_str()))?;
                    if !album.is_empty() {
                        let _ = music.SetAlbumTitle(&HSTRING::from(album.as_str()));
                    }
                    updater.Update()?;
                } else {
                    updater.ClearAll()?;
                    updater.Update()?;
                }
                self.last_title = title;
                self.last_artist = artist;
                self.last_album = album;
            }

            let status = if !has_track {
                MediaPlaybackStatus::Stopped
            } else if info.playing {
                MediaPlaybackStatus::Playing
            } else {
                MediaPlaybackStatus::Paused
            };
            if self.last_status != Some(status) {
                self.controls.SetPlaybackStatus(status)?;
                self.last_status = Some(status);
            }

            if self.last_shuffle != Some(info.shuffle) {
                let _ = self.controls.SetShuffleEnabled(info.shuffle);
                self.last_shuffle = Some(info.shuffle);
            }
            if self.last_repeat != Some(info.repeat) {
                let repeat = match info.repeat {
                    RepeatMode::Off => MediaPlaybackAutoRepeatMode::None,
                    RepeatMode::All => MediaPlaybackAutoRepeatMode::List,
                    RepeatMode::One => MediaPlaybackAutoRepeatMode::Track,
                };
                let _ = self.controls.SetAutoRepeatMode(repeat);
                self.last_repeat = Some(info.repeat);
            }

            let duration = info.duration.max(0.0);
            let position = info.position.clamp(0.0, duration);
            let timeline_due = metadata_changed
                || Instant::now().duration_since(self.last_timeline) > Duration::from_millis(900)
                || (duration - self.last_duration).abs() > 0.2
                || (position - self.last_position).abs() > 1.0;
            if timeline_due {
                let timeline = SystemMediaTransportControlsTimelineProperties::new()?;
                let end = TimeSpan {
                    Duration: seconds_to_ticks(duration),
                };
                timeline.SetStartTime(TimeSpan { Duration: 0 })?;
                timeline.SetMinSeekTime(TimeSpan { Duration: 0 })?;
                timeline.SetMaxSeekTime(end)?;
                timeline.SetEndTime(end)?;
                timeline.SetPosition(TimeSpan {
                    Duration: seconds_to_ticks(position),
                })?;
                self.controls.UpdateTimelineProperties(&timeline)?;
                self.last_duration = duration;
                self.last_position = position;
                self.last_timeline = Instant::now();
            }
            Ok(())
        }
    }

    fn seconds_to_ticks(seconds: f64) -> i64 {
        (seconds.max(0.0) * 10_000_000.0).round() as i64
    }

    fn menu_action(id: &str) -> Option<DesktopAction> {
        match id {
            MENU_SHOW => Some(DesktopAction::Show),
            MENU_PLAY_PAUSE => Some(DesktopAction::TogglePlay),
            MENU_PREVIOUS => Some(DesktopAction::Previous),
            MENU_NEXT => Some(DesktopAction::Next),
            MENU_DESKTOP_LYRICS => Some(DesktopAction::ToggleDesktopLyrics),
            MENU_LYRICS_LOCK => Some(DesktopAction::ToggleLyricsLock),
            MENU_MINI_PLAYER => Some(DesktopAction::ToggleMiniPlayer),
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

    /// Apply a rounded window region and, for the lyric overlay, a small
    /// window-wide alpha. The companion content is still rendered by egui;
    /// this only removes the rectangle outside the rounded card and gives the
    /// desktop lyric panel its translucent look.
    pub fn configure_companion_window(title: &str, alpha: Option<u8>) {
        let wide: Vec<u16> = title.encode_utf16().chain(std::iter::once(0)).collect();
        let hwnd = unsafe { FindWindowW(std::ptr::null(), wide.as_ptr()) };
        if hwnd.is_null() {
            return;
        }
        let mut rect = WinRect {
            left: 0,
            top: 0,
            right: 0,
            bottom: 0,
        };
        if unsafe { GetClientRect(hwnd, &mut rect) } == 0 {
            return;
        }
        let width = (rect.right - rect.left).max(1);
        let height = (rect.bottom - rect.top).max(1);
        let size_key = ((width as u64) << 32) | height as u64;
        let (last_hwnd, last_size) = if alpha.is_some() {
            (&LYRICS_LAYER_HWND, &LYRICS_LAYER_SIZE)
        } else {
            (&MINI_LAYER_HWND, &MINI_LAYER_SIZE)
        };
        let hwnd_id = hwnd as isize;
        unsafe {
            if let Some(alpha) = alpha {
                let ex_style = GetWindowLongW(hwnd, -20);
                let mut key = 0u32;
                let mut current_alpha = 255u8;
                let mut flags = 0u32;
                let has_alpha = GetLayeredWindowAttributes(
                    hwnd,
                    &mut key,
                    &mut current_alpha,
                    &mut flags,
                ) != 0
                    && flags & 0x0000_0002 != 0;
                if ex_style & 0x0008_0000 == 0 || !has_alpha || current_alpha != alpha {
                    SetWindowLongW(hwnd, -20, ex_style | 0x0008_0000);
                    SetLayeredWindowAttributes(hwnd, 0, alpha, 0x0000_0002);
                }
            }
        }
        if last_hwnd.load(Ordering::Relaxed) == hwnd_id
            && last_size.load(Ordering::Relaxed) == size_key
        {
            return;
        }
        last_hwnd.store(hwnd_id, Ordering::Relaxed);
        last_size.store(size_key, Ordering::Relaxed);

        unsafe {
            let dpi = GetDpiForWindow(hwnd).max(96) as f32 / 96.0;
            let margin_x = (6.0 * dpi).round() as i32;
            let margin_y = (5.0 * dpi).round() as i32;
            let radius = (18.0 * dpi).round() as i32;
            let region = CreateRoundRectRgn(
                margin_x,
                margin_y,
                width - margin_x + 1,
                height - margin_y + 1,
                radius * 2,
                radius * 2,
            );
            if !region.is_null() {
                SetWindowRgn(hwnd, region, 1);
            }
        }
    }

    /// Wake the root window from the companion viewports or another callback.
    /// A hidden window still receives `WM_PAINT`, which winit turns into an
    /// update even when scheduled repaints are not delivered.
    pub fn wake_main_window(hwnd: isize) {
        if hwnd != 0 {
            // SAFETY: the handle is the live root window reported by eframe;
            // if it has already been destroyed PostMessage simply fails.
            unsafe {
                PostMessageW(hwnd as *mut c_void, WM_PAINT, 0, 0);
            }
        }
    }

    /// Wake the UI thread even when the root window is hidden. `WM_PAINT` is
    /// delivered directly to the window procedure, and winit turns it into a
    /// `RedrawRequested` event, which is exactly eframe's update path.
    fn wake(ctx: &egui::Context, main_window: &AtomicIsize) {
        ctx.request_repaint();
        wake_main_window(main_window.load(Ordering::Relaxed));
    }

    const WM_PAINT: u32 = 0x000F;

    #[link(name = "user32")]
    extern "system" {
        fn PostMessageW(hwnd: *mut c_void, msg: u32, wparam: usize, lparam: isize) -> i32;
        fn FindWindowW(class_name: *const u16, window_name: *const u16) -> *mut c_void;
        fn GetWindowLongW(hwnd: *mut c_void, index: i32) -> i32;
        fn SetWindowLongW(hwnd: *mut c_void, index: i32, value: i32) -> i32;
        fn SetLayeredWindowAttributes(
            hwnd: *mut c_void,
            color_key: u32,
            alpha: u8,
            flags: u32,
        ) -> i32;
        fn GetLayeredWindowAttributes(
            hwnd: *mut c_void,
            color_key: *mut u32,
            alpha: *mut u8,
            flags: *mut u32,
        ) -> i32;
        fn GetClientRect(hwnd: *mut c_void, rect: *mut WinRect) -> i32;
        fn CreateRoundRectRgn(
            left: i32,
            top: i32,
            right: i32,
            bottom: i32,
            width: i32,
            height: i32,
        ) -> *mut c_void;
        fn SetWindowRgn(hwnd: *mut c_void, region: *mut c_void, redraw: i32) -> i32;
        fn GetDpiForWindow(hwnd: *mut c_void) -> u32;
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
                DesktopAction::ToggleDesktopLyrics,
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
            DesktopAction::ToggleDesktopLyrics => "Ctrl+Alt+L",
            DesktopAction::Show
            | DesktopAction::Play
            | DesktopAction::Pause
            | DesktopAction::ToggleLyricsLock
            | DesktopAction::ToggleMiniPlayer
            | DesktopAction::Quit => "tray",
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
            assert_eq!(
                menu_action(MENU_DESKTOP_LYRICS),
                Some(DesktopAction::ToggleDesktopLyrics)
            );
            assert_eq!(
                menu_action(MENU_LYRICS_LOCK),
                Some(DesktopAction::ToggleLyricsLock)
            );
            assert_eq!(
                menu_action(MENU_MINI_PLAYER),
                Some(DesktopAction::ToggleMiniPlayer)
            );
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
            assert_eq!(
                hotkey_name(DesktopAction::ToggleDesktopLyrics),
                "Ctrl+Alt+L"
            );
        }

        #[test]
        fn media_timeline_uses_100ns_ticks() {
            assert_eq!(seconds_to_ticks(0.0), 0);
            assert_eq!(seconds_to_ticks(1.5), 15_000_000);
            assert_eq!(seconds_to_ticks(-3.0), 0);
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

        pub fn set_main_window(&mut self, _hwnd: isize) {}

        pub fn set_hidden(&self, _hidden: bool) {}

        pub fn set_heartbeat(&self, _active: bool) {}

        pub fn hotkey_error(&self) -> Option<&str> {
            None
        }

        pub fn media_error(&self) -> Option<&str> {
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

        pub fn sync(
            &mut self,
            _language: Language,
            _playing: bool,
            _track: Option<&str>,
            _desktop_lyrics: bool,
            _lyrics_locked: bool,
            _mini_player: bool,
        ) {
        }

        pub fn sync_media(&mut self, _info: &super::MediaInfo) {}
    }

    pub fn log_line(_text: &str) {}

    pub fn wake_main_window(_hwnd: isize) {}

    pub fn configure_companion_window(_title: &str, _alpha: Option<u8>) {}
}

pub use platform::*;

/// Wake the root window from companion viewports on any platform.
pub fn wake_main_window(hwnd: isize) {
    platform::wake_main_window(hwnd);
}

/// Apply the native rounded/window translucency used by the companion windows.
pub fn configure_companion_window(title: &str, alpha: Option<u8>) {
    platform::configure_companion_window(title, alpha);
}
