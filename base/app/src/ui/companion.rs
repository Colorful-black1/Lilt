//! Desktop lyrics and the compact always-on-top mini player.
//!
//! Both are deferred egui viewports.  The main app owns playback and writes a
//! small snapshot; the companion windows only render that snapshot and enqueue
//! actions, which keeps every playback change on the main UI thread.

use crate::icons::{self, Icon};
use crate::library;
use crate::model::{format_duration, Language, RepeatMode};
use crate::theme::{self, display_font, with_alpha, Palette};
use crate::widgets;
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Layout, Rect, RichText, Sense, Stroke, StrokeKind, Ui,
    Vec2,
};
use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::{Arc, Mutex};

pub const LYRICS_VIEWPORT: &str = "lilt_desktop_lyrics";
pub const LYRICS_UNLOCK_VIEWPORT: &str = "lilt_desktop_lyrics_unlock";
pub const MINI_VIEWPORT: &str = "lilt_mini_player";

const LYRICS_TITLE: &str = "Lilt Lyrics";
const LYRICS_UNLOCK_TITLE: &str = "Lilt Lyrics Unlock";
const MINI_TITLE: &str = "Lilt Mini Player";

#[derive(Clone)]
pub struct CompanionSnapshot {
    pub language: Language,
    pub palette: Palette,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub playing: bool,
    pub has_song: bool,
    pub favorite: bool,
    pub position: f64,
    pub duration: f64,
    pub volume: f32,
    pub shuffle: bool,
    pub repeat: RepeatMode,
    pub lyrics: Vec<(f64, String)>,
    pub lyrics_offset_ms: i64,
    pub lyrics_opacity: u8,
    pub desktop_lyrics_font_size: f32,
    pub lyrics_locked: bool,
    pub cover: Option<egui::TextureHandle>,
}

impl CompanionSnapshot {
    fn t(&self, en: &'static str, zh: &'static str) -> &'static str {
        if self.language == Language::Zh {
            zh
        } else {
            en
        }
    }
}

#[derive(Clone, Debug)]
pub enum CompanionAction {
    TogglePlay,
    Play,
    Pause,
    Previous,
    Next,
    Seek(f64),
    ToggleShuffle,
    CycleRepeat,
    ToggleFavorite,
    ShowMain,
    CloseMini,
    CloseLyrics,
    ToggleLyricsLock,
    OpenNowPlaying,
}

#[derive(Default)]
struct ViewportMemory {
    lyrics_position: Option<egui::Pos2>,
    lyrics_size: Option<Vec2>,
    mini_position: Option<egui::Pos2>,
    mini_size: Option<Vec2>,
}

/// Owns the shared snapshots and the native viewport lifecycle.
pub struct CompanionWindows {
    snapshot: Arc<Mutex<Option<CompanionSnapshot>>>,
    actions: Arc<Mutex<Vec<CompanionAction>>>,
    memory: Arc<Mutex<ViewportMemory>>,
    main_window: Arc<AtomicIsize>,
    lyrics_visible_last: bool,
    mini_visible_last: bool,
    lyrics_needs_position: bool,
    mini_needs_position: bool,
}

impl Default for CompanionWindows {
    fn default() -> Self {
        Self::new()
    }
}

impl CompanionWindows {
    pub fn new() -> Self {
        Self {
            snapshot: Arc::new(Mutex::new(None)),
            actions: Arc::new(Mutex::new(Vec::new())),
            memory: Arc::new(Mutex::new(ViewportMemory::default())),
            main_window: Arc::new(AtomicIsize::new(0)),
            lyrics_visible_last: false,
            mini_visible_last: false,
            lyrics_needs_position: false,
            mini_needs_position: false,
        }
    }

    pub fn set_main_window(&self, hwnd: isize) {
        self.main_window.store(hwnd, Ordering::Relaxed);
    }

    pub fn take_actions(&self) -> Vec<CompanionAction> {
        self.actions
            .lock()
            .map(|mut guard| std::mem::take(&mut *guard))
            .unwrap_or_default()
    }

    /// Publish the current playback snapshot and create/hide the native
    /// windows. Must be called once per root update, after any UI changes.
    pub fn sync(
        &mut self,
        snapshot: Option<CompanionSnapshot>,
        ctx: &egui::Context,
        lyrics_visible: bool,
        mini_visible: bool,
    ) {
        if lyrics_visible && !self.lyrics_visible_last {
            self.lyrics_needs_position = true;
        }
        if mini_visible && !self.mini_visible_last {
            self.mini_needs_position = true;
        }
        self.lyrics_visible_last = lyrics_visible;
        self.mini_visible_last = mini_visible;

        let Some(snapshot) = snapshot else {
            if let Ok(mut slot) = self.snapshot.lock() {
                *slot = None;
            }
            return;
        };
        let locked = snapshot.lyrics_locked;
        if let Ok(mut slot) = self.snapshot.lock() {
            *slot = Some(snapshot.clone());
        }

        if lyrics_visible {
            let (position, size) = {
                let (stored_position, stored_size) = self
                    .memory
                    .lock()
                    .map(|memory| (memory.lyrics_position, memory.lyrics_size))
                    .unwrap_or((None, None));
                (
                    stored_position.unwrap_or_else(|| default_lyrics_position(ctx)),
                    stored_size.unwrap_or_else(|| Vec2::new(780.0, 176.0)),
                )
            };
            let mut builder = egui::ViewportBuilder::default()
                .with_title(LYRICS_TITLE)
                .with_decorations(false)
                .with_always_on_top()
                .with_taskbar(false)
                .with_resizable(true)
                .with_min_inner_size([420.0, 160.0])
                .with_close_button(false)
                .with_minimize_button(false)
                .with_maximize_button(false)
                .with_active(false);
            #[cfg(not(target_os = "windows"))]
            {
                builder = builder
                    .with_transparent(true)
                    .with_mouse_passthrough(locked);
            }
            if self.lyrics_needs_position {
                builder = builder.with_position(position).with_inner_size(size);
                self.lyrics_needs_position = false;
            }
            let snapshot = self.snapshot.clone();
            let actions = self.actions.clone();
            let memory = self.memory.clone();
            let main_window = self.main_window.clone();
            let viewport_id = egui::ViewportId::from_hash_of(LYRICS_VIEWPORT);
            ctx.show_viewport_deferred(viewport_id, builder, move |ctx, _class| {
                render_lyrics(ctx, &snapshot, &actions, &memory, &main_window);
            });
            ctx.request_repaint_of(viewport_id);

            if unlock_puck_visible(lyrics_visible, locked) {
                let puck_size = 30.0;
                let puck_position =
                    egui::pos2(position.x + (size.x - puck_size) * 0.5, position.y + 4.0);
                let builder = egui::ViewportBuilder::default()
                    .with_title(LYRICS_UNLOCK_TITLE)
                    .with_decorations(false)
                    .with_always_on_top()
                    .with_taskbar(false)
                    .with_resizable(false)
                    .with_close_button(false)
                    .with_minimize_button(false)
                    .with_maximize_button(false)
                    .with_position(puck_position)
                    .with_inner_size([puck_size, puck_size]);
                let snapshot = self.snapshot.clone();
                let actions = self.actions.clone();
                let main_window = self.main_window.clone();
                let viewport_id = egui::ViewportId::from_hash_of(LYRICS_UNLOCK_VIEWPORT);
                ctx.show_viewport_deferred(viewport_id, builder, move |ctx, _class| {
                    render_lyrics_unlock(ctx, &snapshot, &actions, &main_window);
                });
                ctx.request_repaint_of(viewport_id);
            }
        }

        if mini_visible {
            let (position, size) = {
                let (stored_position, stored_size) = self
                    .memory
                    .lock()
                    .map(|memory| (memory.mini_position, memory.mini_size))
                    .unwrap_or((None, None));
                (
                    stored_position.unwrap_or_else(|| default_mini_position(ctx)),
                    stored_size.unwrap_or_else(|| Vec2::new(420.0, 176.0)),
                )
            };
            let mut builder = egui::ViewportBuilder::default()
                .with_title(MINI_TITLE)
                .with_decorations(false)
                .with_always_on_top()
                .with_taskbar(false)
                .with_resizable(false)
                .with_close_button(false)
                .with_minimize_button(false)
                .with_maximize_button(false);
            if self.mini_needs_position {
                builder = builder.with_position(position).with_inner_size(size);
                self.mini_needs_position = false;
            }
            let snapshot = self.snapshot.clone();
            let actions = self.actions.clone();
            let memory = self.memory.clone();
            let main_window = self.main_window.clone();
            let viewport_id = egui::ViewportId::from_hash_of(MINI_VIEWPORT);
            ctx.show_viewport_deferred(viewport_id, builder, move |ctx, _class| {
                render_mini(ctx, &snapshot, &actions, &memory, &main_window);
            });
            ctx.request_repaint_of(viewport_id);
        }
    }
}

fn default_lyrics_position(ctx: &egui::Context) -> egui::Pos2 {
    let monitor = ctx
        .input(|input| input.viewport().monitor_size)
        .unwrap_or(Vec2::new(1920.0, 1080.0));
    egui::pos2((monitor.x - 780.0) * 0.5, monitor.y - 260.0)
}

fn default_mini_position(ctx: &egui::Context) -> egui::Pos2 {
    let monitor = ctx
        .input(|input| input.viewport().monitor_size)
        .unwrap_or(Vec2::new(1920.0, 1080.0));
    egui::pos2((monitor.x - 460.0).max(20.0), 72.0)
}

fn lyrics_chrome_visible(locked: bool) -> bool {
    !locked
}

fn unlock_puck_visible(lyrics_visible: bool, locked: bool) -> bool {
    lyrics_visible && locked
}

fn push_action(
    actions: &Arc<Mutex<Vec<CompanionAction>>>,
    main_window: &Arc<AtomicIsize>,
    action: CompanionAction,
) {
    if let Ok(mut queue) = actions.lock() {
        queue.push(action);
    }
    crate::desktop::wake_main_window(main_window.load(Ordering::Relaxed));
}

fn remember_viewport(ctx: &egui::Context, memory: &Arc<Mutex<ViewportMemory>>, lyrics: bool) {
    let Some(rect) = ctx.input(|input| input.viewport().outer_rect) else {
        return;
    };
    if let Ok(mut memory) = memory.lock() {
        if lyrics {
            memory.lyrics_position = Some(rect.min);
            memory.lyrics_size = Some(rect.size());
        } else {
            memory.mini_position = Some(rect.min);
            memory.mini_size = Some(rect.size());
        }
    }
}

fn handle_close(
    ctx: &egui::Context,
    actions: &Arc<Mutex<Vec<CompanionAction>>>,
    main_window: &Arc<AtomicIsize>,
    action: CompanionAction,
) {
    if ctx.input(|input| input.viewport().close_requested()) {
        ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
        push_action(actions, main_window, action);
    }
}

fn draw_card(ui: &Ui, rect: Rect, palette: &Palette) {
    let radius = 18.0;
    ui.painter().rect_filled(
        rect.translate(Vec2::new(0.0, 3.0)),
        radius,
        Color32::from_rgba_unmultiplied(0, 0, 0, 110),
    );
    ui.painter().rect_filled(
        rect,
        radius,
        Color32::from_rgba_unmultiplied(13, 11, 10, 255),
    );
    ui.painter().rect_stroke(
        rect,
        radius,
        Stroke::new(1.0, with_alpha(palette.accent, 46)),
        StrokeKind::Inside,
    );
    let highlight = Rect::from_min_max(
        rect.left_top() + Vec2::new(18.0, 1.0),
        egui::pos2(rect.right() - 18.0, rect.top() + 2.0),
    );
    ui.painter().line_segment(
        [highlight.left_center(), highlight.right_center()],
        Stroke::new(1.0, with_alpha(Color32::WHITE, 18)),
    );
}

fn draw_cover(ui: &mut Ui, snapshot: &CompanionSnapshot, size: f32, palette: &Palette) {
    let (rect, _response) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    if let Some(texture) = &snapshot.cover {
        ui.painter()
            .rect_filled(rect.expand(2.0), 11.0, with_alpha(Color32::BLACK, 80));
        ui.put(
            rect,
            egui::Image::new(texture)
                .fit_to_exact_size(rect.size())
                .corner_radius(9.0),
        );
    } else {
        widgets::draw_cover(ui.painter(), rect, &snapshot.album, palette);
    }
}

fn draw_lyrics_card(ui: &Ui, rect: Rect, palette: &Palette) {
    let radius = 18.0;
    ui.painter().rect_filled(
        rect.translate(Vec2::new(0.0, 4.0)),
        radius,
        Color32::from_rgba_unmultiplied(0, 0, 0, 72),
    );
    ui.painter()
        .rect_filled(rect, radius, Color32::from_rgb(10, 9, 8));
    ui.painter().rect_stroke(
        rect,
        radius,
        Stroke::new(1.0, with_alpha(palette.accent, 58)),
        StrokeKind::Inside,
    );
    let highlight = Rect::from_min_max(
        rect.left_top() + Vec2::new(18.0, 1.0),
        egui::pos2(rect.right() - 18.0, rect.top() + 2.0),
    );
    ui.painter().line_segment(
        [highlight.left_center(), highlight.right_center()],
        Stroke::new(1.0, with_alpha(Color32::WHITE, 16)),
    );
}

/// Draws one lyric line with a soft shadow so the transparent overlay stays
/// readable over bright desktops and photographs.
fn shadow_label(ui: &mut Ui, text: &str, font: FontId, color: Color32, shadow: Color32) {
    if text.is_empty() {
        return;
    }
    let width = ui.available_width().max(1.0);
    let galley = ui
        .painter()
        .layout(text.to_owned(), font.clone(), color, width);
    let size = galley.size();
    let (rect, _) = ui.allocate_exact_size(Vec2::new(width, size.y), Sense::hover());
    let position = egui::pos2(rect.center().x - size.x * 0.5, rect.top());
    if shadow.a() > 0 {
        let shadow_galley = ui.painter().layout(text.to_owned(), font, shadow, width);
        ui.painter()
            .galley(position + Vec2::new(0.0, 1.5), shadow_galley, shadow);
    }
    ui.painter().galley(position, galley, color);
}

fn render_lyrics(
    ctx: &egui::Context,
    shared: &Arc<Mutex<Option<CompanionSnapshot>>>,
    actions: &Arc<Mutex<Vec<CompanionAction>>>,
    memory: &Arc<Mutex<ViewportMemory>>,
    main_window: &Arc<AtomicIsize>,
) {
    let Some(snapshot) = shared.lock().map(|slot| slot.clone()).unwrap_or_default() else {
        return;
    };
    theme::apply_style(ctx, snapshot.palette);
    crate::desktop::configure_companion_window(
        LYRICS_TITLE,
        crate::desktop::CompanionWindowKind::Lyrics,
        snapshot.lyrics_locked,
    );
    handle_close(ctx, actions, main_window, CompanionAction::CloseLyrics);
    remember_viewport(ctx, memory, true);

    let palette = snapshot.palette;
    let ink = Color32::from_rgb(0xF8, 0xF3, 0xEA);
    let opacity = (snapshot.lyrics_opacity as f32 / 100.0).clamp(0.3, 1.0);
    let alpha = |value: u8| ((value as f32) * opacity).round() as u8;
    let locked = snapshot.lyrics_locked;
    let shadow = if locked {
        Color32::TRANSPARENT
    } else {
        Color32::from_rgba_unmultiplied(6, 5, 4, alpha(190))
    };
    let active_size = snapshot.desktop_lyrics_font_size.clamp(18.0, 56.0);
    let toolbar_button = 32.0;

    egui::CentralPanel::default()
        .frame(egui::Frame::NONE.fill(Color32::TRANSPARENT))
        .show(ctx, |ui| {
            let card = ui.max_rect().shrink2(Vec2::new(6.0, 5.0));
            if lyrics_chrome_visible(locked) {
                draw_lyrics_card(ui, card, &palette);
            }
            let inset = if locked {
                Vec2::new(20.0, 16.0)
            } else {
                Vec2::new(16.0, 7.0)
            };
            let inner = card.shrink2(inset);
            let mut body = inner;
            let header_height = if locked { 24.0 } else { 34.0 };
            let header = Rect::from_min_size(inner.min, Vec2::new(inner.width(), header_height));

            if lyrics_chrome_visible(locked) {
                ui.allocate_ui_at_rect(header, |ui| {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 2.0;
                        let controls_width = 7.0 * toolbar_button + 6.0 * 2.0;
                        let drag_width = (ui.available_width() - controls_width - 6.0).max(56.0);
                        let (drag_rect, drag) =
                            ui.allocate_exact_size(Vec2::new(drag_width, 30.0), Sense::drag());
                        icons::paint(
                            ui.painter(),
                            Rect::from_center_size(
                                egui::pos2(drag_rect.left() + 6.0, drag_rect.center().y),
                                Vec2::splat(12.0),
                            ),
                            Icon::Grip,
                            with_alpha(ink, 110),
                            1.2,
                        );
                        if inner.width() >= 560.0 {
                            let clip = ui.painter().with_clip_rect(drag_rect);
                            clip.text(
                                egui::pos2(drag_rect.left() + 17.0, drag_rect.center().y),
                                Align2::LEFT_CENTER,
                                snapshot.t("LILT LYRICS", "LILT 桌面歌词"),
                                FontId::proportional(9.0),
                                with_alpha(ink, 140),
                            );
                        }
                        if drag.drag_started() {
                            ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
                        }
                        if drag.double_clicked() {
                            push_action(actions, main_window, CompanionAction::OpenNowPlaying);
                        }
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            ui.spacing_mut().item_spacing.x = 2.0;
                            if icons::icon_button(
                                ui,
                                Icon::Close,
                                toolbar_button,
                                with_alpha(ink, 210),
                                with_alpha(ink, 30),
                                snapshot.t("Close desktop lyrics", "关闭桌面歌词"),
                            )
                            .clicked()
                            {
                                push_action(actions, main_window, CompanionAction::CloseLyrics);
                            }
                            if icons::icon_button(
                                ui,
                                Icon::Lock,
                                toolbar_button,
                                with_alpha(ink, 210),
                                with_alpha(ink, 30),
                                snapshot.t("Lock and click through", "锁定并鼠标穿透"),
                            )
                            .clicked()
                            {
                                push_action(
                                    actions,
                                    main_window,
                                    CompanionAction::ToggleLyricsLock,
                                );
                            }
                            if icons::icon_button(
                                ui,
                                Icon::Expand,
                                toolbar_button,
                                with_alpha(ink, 210),
                                with_alpha(ink, 30),
                                snapshot.t("Open player", "打开播放页"),
                            )
                            .clicked()
                            {
                                push_action(actions, main_window, CompanionAction::OpenNowPlaying);
                            }
                            let control_color = if snapshot.has_song {
                                with_alpha(ink, 220)
                            } else {
                                with_alpha(ink, 64)
                            };
                            if icons::icon_button(
                                ui,
                                Icon::Next,
                                toolbar_button,
                                control_color,
                                with_alpha(ink, 30),
                                snapshot.t("Next", "下一首"),
                            )
                            .clicked()
                                && snapshot.has_song
                            {
                                push_action(actions, main_window, CompanionAction::Next);
                            }
                            let play_icon = if snapshot.playing {
                                Icon::Pause
                            } else {
                                Icon::Play
                            };
                            let play_label = if snapshot.playing {
                                snapshot.t("Pause", "暂停")
                            } else {
                                snapshot.t("Play", "播放")
                            };
                            if icons::icon_button(
                                ui,
                                play_icon,
                                toolbar_button,
                                control_color,
                                with_alpha(ink, 30),
                                play_label,
                            )
                            .clicked()
                                && snapshot.has_song
                            {
                                push_action(actions, main_window, CompanionAction::TogglePlay);
                            }
                            if icons::icon_button(
                                ui,
                                Icon::Prev,
                                toolbar_button,
                                control_color,
                                with_alpha(ink, 30),
                                snapshot.t("Previous", "上一首"),
                            )
                            .clicked()
                                && snapshot.has_song
                            {
                                push_action(actions, main_window, CompanionAction::Previous);
                            }
                            let heart_color = if snapshot.favorite {
                                palette.accent
                            } else if snapshot.has_song {
                                with_alpha(ink, 220)
                            } else {
                                with_alpha(ink, 64)
                            };
                            let heart_label = if snapshot.favorite {
                                snapshot.t("Remove from favorites", "取消收藏")
                            } else {
                                snapshot.t("Add to favorites", "添加到我喜欢")
                            };
                            if icons::icon_button(
                                ui,
                                Icon::Heart,
                                toolbar_button,
                                heart_color,
                                with_alpha(ink, 30),
                                heart_label,
                            )
                            .clicked()
                                && snapshot.has_song
                            {
                                push_action(actions, main_window, CompanionAction::ToggleFavorite);
                            }
                        });
                    });
                });
                body.min.y = header.bottom() + 4.0;
            } else {
                // The separate unlock puck sits in this strip; keep the lyric
                // lines below it.
                body.min.y = header.bottom() + 2.0;
            }

            body.max.y -= if locked { 8.0 } else { 5.0 };
            ui.allocate_ui_at_rect(body, |ui| {
                ui.set_width(body.width());
                ui.set_clip_rect(body);
                ui.spacing_mut().item_spacing.y = 2.0;
                ui.vertical_centered(|ui| {
                    ui.add_space(1.0);
                    let has_lyrics = !snapshot.lyrics.is_empty();
                    let show_meta = inner.width() >= 560.0;
                    if has_lyrics && !locked && show_meta {
                        let meta = if snapshot.artist.is_empty() {
                            snapshot.title.clone()
                        } else {
                            format!("{}  ·  {}", snapshot.title, snapshot.artist)
                        };
                        shadow_label(
                            ui,
                            &meta,
                            FontId::proportional(9.5),
                            with_alpha(ink, alpha(120)),
                            shadow,
                        );
                        ui.add_space(2.0);
                    } else if !has_lyrics && !locked && show_meta {
                        let meta = if snapshot.artist.is_empty() {
                            snapshot.album.clone()
                        } else if snapshot.album.is_empty() {
                            snapshot.artist.clone()
                        } else {
                            format!("{}  ·  {}", snapshot.artist, snapshot.album)
                        };
                        if !meta.is_empty() {
                            shadow_label(
                                ui,
                                &meta,
                                FontId::proportional(9.5),
                                with_alpha(ink, alpha(120)),
                                shadow,
                            );
                            ui.add_space(2.0);
                        }
                    }

                    if !has_lyrics {
                        shadow_label(
                            ui,
                            &snapshot.title,
                            display_font(active_size * 0.78),
                            with_alpha(ink, alpha(235)),
                            shadow,
                        );
                        shadow_label(
                            ui,
                            snapshot.t("No lyrics for this track", "这首歌没有歌词"),
                            FontId::proportional(10.5),
                            with_alpha(ink, alpha(110)),
                            shadow,
                        );
                    } else {
                        let timed = snapshot.lyrics.iter().any(|(time, _)| *time > 0.0);
                        let lyric_position =
                            library::lyrics_position(snapshot.position, snapshot.lyrics_offset_ms);
                        let active_line = if timed {
                            snapshot
                                .lyrics
                                .iter()
                                .enumerate()
                                .rev()
                                .find(|(_, (time, line))| {
                                    *time <= lyric_position + 0.1 && !line.trim().is_empty()
                                })
                                .map(|(index, _)| index)
                        } else {
                            snapshot
                                .lyrics
                                .iter()
                                .position(|(_, line)| !line.trim().is_empty())
                        };
                        let center = active_line.unwrap_or(0);
                        let neighbor_size = (active_size * 0.50).clamp(12.0, 20.0);
                        let active_height = active_size * 1.22;
                        let neighbor_height = neighbor_size * 1.22;
                        let lyrics_room = (body.height() - 18.0).max(20.0);
                        let mut indices = vec![center];
                        if active_height + neighbor_height * 2.0 + 4.0 <= lyrics_room {
                            if center > 0 {
                                indices.insert(0, center - 1);
                            }
                            if center + 1 < snapshot.lyrics.len() {
                                indices.push(center + 1);
                            }
                        } else if active_height + neighbor_height + 2.0 <= lyrics_room {
                            if center + 1 < snapshot.lyrics.len() {
                                indices.push(center + 1);
                            } else if center > 0 {
                                indices.insert(0, center - 1);
                            }
                        }
                        let mut painted = 0;
                        for index in indices {
                            let Some((_, line)) = snapshot.lyrics.get(index) else {
                                continue;
                            };
                            if line.trim().is_empty() {
                                continue;
                            }
                            let active = Some(index) == active_line;
                            let color = if active {
                                with_alpha(palette.accent, alpha(255))
                            } else {
                                with_alpha(ink, alpha(150))
                            };
                            shadow_label(
                                ui,
                                line,
                                display_font(if active { active_size } else { neighbor_size }),
                                color,
                                shadow,
                            );
                            painted += 1;
                            if active {
                                ui.add_space(1.0);
                            }
                        }
                        if painted == 0 {
                            shadow_label(
                                ui,
                                snapshot.t("Waiting for the first line", "等待第一句歌词"),
                                FontId::proportional(13.0),
                                with_alpha(ink, alpha(150)),
                                shadow,
                            );
                        }
                    }
                });
            });

            if lyrics_chrome_visible(locked) {
                let duration = snapshot.duration.max(0.001);
                let fraction = (snapshot.position / duration).clamp(0.0, 1.0) as f32;
                let rail = Rect::from_min_size(
                    egui::pos2(card.left() + 20.0, card.bottom() - 4.0),
                    Vec2::new((card.width() - 40.0).max(1.0), 2.0),
                );
                ui.painter().rect_filled(rail, 1.0, with_alpha(ink, 28));
                ui.painter().rect_filled(
                    Rect::from_min_size(
                        rail.min,
                        Vec2::new(rail.width() * fraction, rail.height()),
                    ),
                    1.0,
                    with_alpha(palette.accent, 190),
                );
            }
        });
}

fn render_lyrics_unlock(
    ctx: &egui::Context,
    shared: &Arc<Mutex<Option<CompanionSnapshot>>>,
    actions: &Arc<Mutex<Vec<CompanionAction>>>,
    main_window: &Arc<AtomicIsize>,
) {
    let Some(snapshot) = shared.lock().map(|slot| slot.clone()).unwrap_or_default() else {
        return;
    };
    theme::apply_style(ctx, snapshot.palette);
    crate::desktop::configure_companion_window(
        LYRICS_UNLOCK_TITLE,
        crate::desktop::CompanionWindowKind::Unlock,
        false,
    );
    egui::CentralPanel::default()
        .frame(egui::Frame::NONE.fill(Color32::TRANSPARENT))
        .show(ctx, |ui| {
            let rect = ui.max_rect().shrink(2.0);
            let response = ui.allocate_rect(rect, Sense::click());
            let background = if response.hovered() {
                Color32::from_rgba_unmultiplied(24, 21, 18, 238)
            } else {
                Color32::from_rgba_unmultiplied(10, 9, 8, 210)
            };
            ui.painter().rect_filled(rect, 9.0, background);
            ui.painter().rect_stroke(
                rect,
                9.0,
                Stroke::new(1.0, with_alpha(Color32::WHITE, 30)),
                StrokeKind::Inside,
            );
            icons::paint(
                ui.painter(),
                Rect::from_center_size(rect.center(), Vec2::splat(13.0)),
                Icon::Lock,
                with_alpha(Color32::WHITE, 225),
                1.5,
            );
            if response.clicked() {
                push_action(actions, main_window, CompanionAction::ToggleLyricsLock);
            }
        });
}

fn render_mini(
    ctx: &egui::Context,
    shared: &Arc<Mutex<Option<CompanionSnapshot>>>,
    actions: &Arc<Mutex<Vec<CompanionAction>>>,
    memory: &Arc<Mutex<ViewportMemory>>,
    main_window: &Arc<AtomicIsize>,
) {
    let Some(snapshot) = shared.lock().map(|slot| slot.clone()).unwrap_or_default() else {
        return;
    };
    theme::apply_style(ctx, snapshot.palette);
    crate::desktop::configure_companion_window(
        MINI_TITLE,
        crate::desktop::CompanionWindowKind::Mini,
        false,
    );
    handle_close(ctx, actions, main_window, CompanionAction::CloseMini);
    remember_viewport(ctx, memory, false);

    let palette = snapshot.palette;
    egui::CentralPanel::default()
        .frame(egui::Frame::NONE.fill(Color32::TRANSPARENT))
        .show(ctx, |ui| {
            let card = ui.max_rect().shrink2(Vec2::new(6.0, 5.0));
            draw_card(ui, card, &palette);
            let inner = card.shrink2(Vec2::new(14.0, 9.0));

            let header = Rect::from_min_size(inner.min, Vec2::new(inner.width(), 20.0));
            ui.allocate_ui_at_rect(header, |ui| {
                ui.horizontal(|ui| {
                    let drag_width = (ui.available_width() - 58.0).max(80.0);
                    let (drag_rect, drag) =
                        ui.allocate_exact_size(Vec2::new(drag_width, 20.0), Sense::drag());
                    icons::paint(
                        ui.painter(),
                        Rect::from_center_size(
                            egui::pos2(drag_rect.left() + 5.0, drag_rect.center().y),
                            Vec2::splat(11.0),
                        ),
                        Icon::Grip,
                        palette.faint,
                        1.1,
                    );
                    ui.painter().text(
                        egui::pos2(drag_rect.left() + 15.0, drag_rect.center().y),
                        Align2::LEFT_CENTER,
                        "LILT",
                        FontId::proportional(8.5),
                        palette.muted,
                    );
                    if drag.drag_started() {
                        ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
                    }
                    if drag.double_clicked() {
                        push_action(actions, main_window, CompanionAction::ShowMain);
                    }
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if icons::icon_button(
                            ui,
                            Icon::Close,
                            22.0,
                            palette.muted,
                            palette.surface_hi,
                            snapshot.t("Close mini player", "关闭迷你播放器"),
                        )
                        .clicked()
                        {
                            push_action(actions, main_window, CompanionAction::CloseMini);
                        }
                        if icons::icon_button(
                            ui,
                            Icon::Expand,
                            22.0,
                            palette.muted,
                            palette.surface_hi,
                            snapshot.t("Open full player", "打开完整播放器"),
                        )
                        .clicked()
                        {
                            push_action(actions, main_window, CompanionAction::ShowMain);
                        }
                    });
                });
            });

            let body = Rect::from_min_max(
                egui::pos2(inner.left(), header.bottom() + 5.0),
                egui::pos2(inner.right(), header.bottom() + 69.0),
            );
            ui.allocate_ui_at_rect(body, |ui| {
                ui.horizontal(|ui| {
                    draw_cover(ui, &snapshot, 62.0, &palette);
                    ui.add_space(2.0);
                    ui.vertical(|ui| {
                        ui.set_width(ui.available_width());
                        ui.add(
                            egui::Label::new(
                                RichText::new(&snapshot.title)
                                    .size(14.0)
                                    .strong()
                                    .color(palette.text),
                            )
                            .truncate()
                            .selectable(false),
                        );
                        ui.add(
                            egui::Label::new(
                                RichText::new(&snapshot.artist)
                                    .size(10.5)
                                    .color(palette.muted),
                            )
                            .truncate()
                            .selectable(false),
                        );
                        if !snapshot.album.is_empty() {
                            ui.add(
                                egui::Label::new(
                                    RichText::new(&snapshot.album)
                                        .size(9.5)
                                        .color(palette.faint),
                                )
                                .truncate()
                                .selectable(false),
                            );
                        }
                    });
                });
            });

            let controls_top = body.bottom() + 1.0;
            let controls = Rect::from_min_size(
                egui::pos2(inner.left(), controls_top),
                Vec2::new(inner.width(), 34.0),
            );
            ui.allocate_ui_at_rect(controls, |ui| {
                ui.horizontal(|ui| {
                    let group = 158.0;
                    ui.add_space(((ui.available_width() - group) * 0.5).max(0.0));
                    let shuffle_color = if snapshot.shuffle {
                        palette.accent
                    } else {
                        palette.muted
                    };
                    if icons::icon_button(
                        ui,
                        Icon::Shuffle,
                        24.0,
                        shuffle_color,
                        palette.surface_hi,
                        snapshot.t("Shuffle", "随机"),
                    )
                    .clicked()
                    {
                        push_action(actions, main_window, CompanionAction::ToggleShuffle);
                    }
                    if icons::icon_button(
                        ui,
                        Icon::Prev,
                        27.0,
                        palette.text,
                        palette.surface_hi,
                        snapshot.t("Previous", "上一首"),
                    )
                    .clicked()
                    {
                        push_action(actions, main_window, CompanionAction::Previous);
                    }
                    if widgets::play_button(ui, 34.0, snapshot.playing, &palette).clicked() {
                        push_action(actions, main_window, CompanionAction::TogglePlay);
                    }
                    if icons::icon_button(
                        ui,
                        Icon::Next,
                        27.0,
                        palette.text,
                        palette.surface_hi,
                        snapshot.t("Next", "下一首"),
                    )
                    .clicked()
                    {
                        push_action(actions, main_window, CompanionAction::Next);
                    }
                    let repeat_icon = if snapshot.repeat == RepeatMode::One {
                        Icon::RepeatOne
                    } else {
                        Icon::Repeat
                    };
                    let repeat_color = if snapshot.repeat != RepeatMode::Off {
                        palette.accent
                    } else {
                        palette.muted
                    };
                    if icons::icon_button(
                        ui,
                        repeat_icon,
                        24.0,
                        repeat_color,
                        palette.surface_hi,
                        snapshot.t("Repeat", "循环"),
                    )
                    .clicked()
                    {
                        push_action(actions, main_window, CompanionAction::CycleRepeat);
                    }
                });
            });

            let progress = Rect::from_min_size(
                egui::pos2(inner.left(), controls.bottom() + 1.0),
                Vec2::new(inner.width(), 18.0),
            );
            ui.allocate_ui_at_rect(progress, |ui| {
                let position = snapshot.position.min(snapshot.duration);
                let fraction = if snapshot.duration > 0.0 {
                    position / snapshot.duration
                } else {
                    0.0
                };
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(format_duration(position))
                            .font(FontId::monospace(8.5))
                            .color(palette.faint),
                    );
                    let width = (ui.available_width() - 66.0).max(70.0);
                    ui.allocate_ui_with_layout(
                        Vec2::new(width, 14.0),
                        Layout::left_to_right(Align::Center),
                        |ui| {
                            let response = widgets::seek_bar(ui, fraction, &palette, 14.0);
                            if let Some(value) = response.changed {
                                push_action(
                                    actions,
                                    main_window,
                                    CompanionAction::Seek(value * snapshot.duration),
                                );
                            }
                        },
                    );
                    ui.label(
                        RichText::new(format_duration(snapshot.duration))
                            .font(FontId::monospace(8.5))
                            .color(palette.faint),
                    );
                });
            });

            let hint = if snapshot.volume <= 0.001 {
                snapshot.t("Muted", "静音")
            } else {
                ""
            };
            if !hint.is_empty() {
                ui.painter().text(
                    egui::pos2(card.right() - 18.0, card.bottom() - 10.0),
                    Align2::RIGHT_CENTER,
                    hint,
                    FontId::proportional(8.5),
                    palette.warn,
                );
            }
        });
}

/// Used by the main window for the lyric header preview and for tests.
pub fn active_lyric(
    lyrics: &[(f64, String)],
    position: f64,
    offset_ms: i64,
) -> Option<(usize, String)> {
    let lyric_position = library::lyrics_position(position, offset_ms);
    lyrics
        .iter()
        .enumerate()
        .rev()
        .find(|(_, (time, line))| *time <= lyric_position + 0.1 && !line.trim().is_empty())
        .map(|(index, (_, line))| (index, line.clone()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locked_lyrics_hide_the_card_and_controls() {
        assert!(lyrics_chrome_visible(false));
        assert!(!lyrics_chrome_visible(true));
    }

    #[test]
    fn unlock_puck_only_appears_for_visible_locked_lyrics() {
        assert!(unlock_puck_visible(true, true));
        assert!(!unlock_puck_visible(true, false));
        assert!(!unlock_puck_visible(false, true));
        assert!(!unlock_puck_visible(false, false));
    }

    #[test]
    fn active_line_uses_offset_and_skips_blank_lines() {
        let lyrics = vec![
            (0.0, String::new()),
            (2.0, "first".to_owned()),
            (4.0, "second".to_owned()),
        ];
        assert_eq!(
            active_lyric(&lyrics, 3.0, 0).map(|(_, line)| line),
            Some("first".to_owned())
        );
        assert_eq!(
            active_lyric(&lyrics, 4.0, 2000).map(|(_, line)| line),
            Some("first".to_owned())
        );
        assert_eq!(
            active_lyric(&lyrics, 6.0, 2000).map(|(_, line)| line),
            Some("second".to_owned())
        );
        assert_eq!(active_lyric(&lyrics, 0.5, 0).map(|(_, line)| line), None);
    }
}
