//! Full-window now-playing page.
//!
//! The lyrics are the main reading surface: the cover and song facts sit on
//! the left, the lyric sheet fills the right pane, and the transport lives in a
//! slim bar along the bottom. The sheet follows playback and keeps the current
//! line in the middle of its pane.

use crate::app::{AbLoop, LiltApp, LyricsStatus, Modal};
use crate::icons::{self, Icon};
use crate::library;
use crate::model::{format_duration, LyricsSource, Page, RepeatMode, Song};
use crate::theme::{cover_palette, display_font, mix, with_alpha, Palette};
use crate::widgets;
use eframe::egui::{
    self, Align, Color32, FontId, Layout, Pos2, Rect, RichText, ScrollArea, Sense, Stroke, Ui,
    UiBuilder, Vec2,
};

const MAX_CONTENT_WIDTH: f32 = 1180.0;
const OUTER_MARGIN: f32 = 24.0;
const LEFT_GAP: f32 = 20.0;

pub fn now_playing(app: &mut LiltApp, ctx: &egui::Context) {
    let screen = ctx.screen_rect();
    let palette = app.palette;
    let current = app.current_song().cloned();
    let seed = current
        .as_ref()
        .map(|s| s.album.clone())
        .unwrap_or_else(|| "Lilt".to_owned());
    let cp = cover_palette(&seed, palette.accent, true);
    let ink = Color32::from_rgb(0xF8, 0xF3, 0xEA);
    let ink_muted = with_alpha(ink, 155);
    let mut op = palette;
    op.dark = true;
    op.text = ink;
    op.muted = ink_muted;
    op.faint = with_alpha(ink, 95);
    op.surface = with_alpha(Color32::WHITE, 10);
    op.surface_hi = with_alpha(Color32::WHITE, 18);
    op.border = with_alpha(Color32::WHITE, 26);
    op.border_strong = with_alpha(Color32::WHITE, 42);
    op.on_accent = palette.on_accent;

    let texture = current
        .as_ref()
        .and_then(|song| app.load_cover_texture(ctx, song));
    let duration = app.duration();
    let position = app.position().min(duration);
    let fraction = if duration > 0.0 {
        position / duration
    } else {
        0.0
    };
    let queue_total = app.active_queue().map(|q| q.songs.len()).unwrap_or(0);
    let queue_next: Vec<(String, String, String)> = {
        let queue = app.active_queue().cloned();
        queue
            .map(|q| {
                q.songs
                    .iter()
                    .enumerate()
                    .skip(q.index + 1)
                    .take(8)
                    .filter_map(|(_, key)| {
                        app.song_by_key(key).map(|s| {
                            (
                                s.display_title().to_owned(),
                                s.display_artist().to_owned(),
                                s.album.clone(),
                            )
                        })
                    })
                    .collect()
            })
            .unwrap_or_default()
    };
    let lyrics = app.lyrics.clone().unwrap_or_default();
    let lyrics_song_key = current.as_ref().map(|song| song.key.clone());
    let lyrics_status = lyrics_song_key
        .as_deref()
        .map(|key| app.lyrics_status_for(key))
        .unwrap_or(LyricsStatus::Idle);
    let lyrics_source = lyrics_song_key
        .as_deref()
        .map(|key| app.lyrics_source_for(key))
        .unwrap_or(LyricsSource::Local);
    let mut lyrics_offset_ms = lyrics_song_key
        .as_deref()
        .map(|key| app.lyrics_offset_for(key))
        .unwrap_or(0);
    let mut show_lyrics = app.lyrics_open;
    let lyrics_timed = lyrics.iter().any(|(time, _)| *time > 0.0);
    let lyrics_font_size = app.state.settings.lyrics_font_size.clamp(10.0, 24.0);
    let lyrics_opacity = app.state.settings.lyrics_opacity.clamp(30, 100) as f32 / 100.0;
    let lyric_alpha = |value: u8| ((value as f32) * lyrics_opacity).round().clamp(0.0, 255.0) as u8;
    let played_base = app
        .state
        .settings
        .desktop_lyrics_played_color
        .map(|[r, g, b]| Color32::from_rgb(r, g, b))
        .unwrap_or(palette.accent);
    let unplayed_base = app
        .state
        .settings
        .desktop_lyrics_unplayed_color
        .map(|[r, g, b]| Color32::from_rgb(r, g, b))
        .unwrap_or(ink);
    let active_color = with_alpha(played_base, lyric_alpha(255));
    let played_color = with_alpha(played_base, lyric_alpha(115));
    let unplayed_color = with_alpha(unplayed_base, lyric_alpha(185));
    let lyric_size = (lyrics_font_size * 1.55).clamp(18.0, 34.0);

    let mut close = false;
    let mut toggle_play = false;
    let mut next = false;
    let mut prev = false;
    let mut toggle_shuffle = false;
    let mut cycle_repeat = false;
    let mut seek: Option<f64> = None;
    let mut toggle_fav = false;
    let mut open_queue = false;
    let mut open_eq = false;
    let mut open_sleep = false;
    let mut open_tags = false;
    let mut open_speed = false;
    let mut open_lyrics_editor = false;
    let mut toggle_desktop = false;
    let mut locate_current = false;
    let mut toggle_ab = false;
    let mut clear_ab = false;
    let mut toggle_stop_after = false;
    let mut lyric_actions = LyricActions::default();

    egui::Area::new(egui::Id::new("now_playing_overlay"))
        .fixed_pos(screen.min)
        .order(egui::Order::Foreground)
        .show(ctx, |ui| {
            ui.set_min_size(screen.size());
            let rect = ui.max_rect();
            widgets::vertical_gradient(
                ui.painter(),
                rect,
                mix(cp.base, Color32::BLACK, 0.58),
                mix(cp.deep, Color32::BLACK, 0.82),
            );
            let clip = ui.painter().with_clip_rect(rect);
            clip.circle_filled(
                egui::pos2(
                    rect.right() - rect.width() * 0.10,
                    rect.top() + rect.height() * 0.08,
                ),
                rect.width().min(rect.height()) * 0.34,
                with_alpha(cp.tint, 26),
            );
            clip.circle_filled(
                egui::pos2(
                    rect.left() + rect.width() * 0.04,
                    rect.bottom() - rect.height() * 0.04,
                ),
                rect.width().min(rect.height()) * 0.28,
                with_alpha(cp.glow, 22),
            );

            let content_width = (screen.width() - OUTER_MARGIN * 2.0)
                .min(MAX_CONTENT_WIDTH)
                .max(560.0);
            let bottom_h = if content_width >= 820.0 { 166.0 } else { 216.0 };

            ui.allocate_ui_with_layout(
                Vec2::new(screen.width(), screen.height()),
                Layout::top_down(Align::Center),
                |ui| {
                    ui.spacing_mut().item_spacing.y = 0.0;
                    ui.add_space(14.0);

                    ui.allocate_ui_with_layout(
                        Vec2::new(content_width, 26.0),
                        Layout::left_to_right(Align::Center),
                        |ui| {
                            ui.label(
                                RichText::new(app.t("NOW PLAYING", "正在播放"))
                                    .size(9.5)
                                    .color(with_alpha(ink, 150))
                                    .strong(),
                            );
                            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                if icons::icon_button(
                                    ui,
                                    Icon::Close,
                                    28.0,
                                    with_alpha(ink, 190),
                                    with_alpha(ink, 22),
                                    app.t("Close", "关闭"),
                                )
                                .clicked()
                                {
                                    close = true;
                                }
                            });
                        },
                    );
                    ui.add_space(8.0);

                    let body_h = (screen.bottom() - 14.0 - bottom_h - ui.cursor().top()).max(250.0);
                    let left_w = (content_width * 0.35)
                        .clamp(232.0, 400.0)
                        .min(content_width - 292.0);
                    let right_w = content_width - left_w - LEFT_GAP;

                    let (body_rect, _) =
                        ui.allocate_exact_size(Vec2::new(content_width, body_h), Sense::hover());
                    child_ui(ui, body_rect, Layout::left_to_right(Align::Min), |ui| {
                        let (left_rect, _) =
                            ui.allocate_exact_size(Vec2::new(left_w, body_h), Sense::hover());
                        child_ui(ui, left_rect, Layout::top_down(Align::Min), |ui| {
                            left_column(
                                ui,
                                app,
                                current.as_ref(),
                                texture.as_ref(),
                                &seed,
                                &palette,
                                ink,
                                ink_muted,
                                left_w,
                                body_h,
                                &mut toggle_fav,
                                &mut open_queue,
                                &mut locate_current,
                                &mut toggle_desktop,
                            );
                        });

                        ui.add_space(LEFT_GAP);

                        let (pane_rect, _) =
                            ui.allocate_exact_size(Vec2::new(right_w, body_h), Sense::hover());
                        let inner = pane_rect.shrink2(Vec2::new(18.0, 14.0));
                        child_ui(ui, inner, Layout::top_down(Align::Min), |ui| {
                            right_pane(
                                ui,
                                app,
                                current.as_ref(),
                                &lyrics,
                                lyrics_timed,
                                lyrics_offset_ms,
                                lyrics_status.clone(),
                                lyrics_source,
                                &queue_next,
                                &op,
                                ink,
                                ink_muted,
                                active_color,
                                played_color,
                                unplayed_color,
                                lyric_size,
                                &mut show_lyrics,
                                &mut lyrics_offset_ms,
                                &mut seek,
                                duration,
                                &mut open_lyrics_editor,
                                &mut toggle_desktop,
                                &mut lyric_actions,
                            );
                        });
                    });
                    let (bar_rect, _) =
                        ui.allocate_exact_size(Vec2::new(screen.width(), bottom_h), Sense::hover());
                    child_ui(ui, bar_rect, Layout::top_down(Align::Min), |ui| {
                        transport_bar(
                            ui,
                            app,
                            &op,
                            ink,
                            ink_muted,
                            screen.width(),
                            content_width - 32.0,
                            fraction,
                            position,
                            duration,
                            &mut seek,
                            &mut toggle_shuffle,
                            &mut prev,
                            &mut toggle_play,
                            &mut next,
                            &mut cycle_repeat,
                            &mut toggle_ab,
                            &mut clear_ab,
                            &mut toggle_stop_after,
                            &mut open_eq,
                            &mut open_sleep,
                            &mut open_tags,
                            &mut open_speed,
                            &mut toggle_desktop,
                            queue_total,
                        );
                    });
                },
            );
        });

    if close {
        app.now_playing = false;
    }
    if show_lyrics != app.lyrics_open {
        app.lyrics_open = show_lyrics;
    }
    if toggle_play {
        app.toggle_play();
    }
    if prev {
        app.prev_track();
    }
    if next {
        app.next_track(false);
    }
    if toggle_shuffle {
        app.toggle_shuffle();
    }
    if cycle_repeat {
        app.cycle_repeat();
    }
    if let Some(value) = seek {
        app.seek_fraction(value);
    }
    if toggle_fav {
        if let Some(key) = app.current_key.clone() {
            app.toggle_favorite(&key);
        }
    }
    if open_queue {
        app.now_playing = false;
        app.queue_drawer = true;
    }
    if let Some(key) = &lyrics_song_key {
        if lyrics_offset_ms != app.lyrics_offset_for(key) {
            app.set_lyrics_offset(key, lyrics_offset_ms);
        }
        if lyric_actions.start_ai {
            app.start_ai_lyrics(key);
        }
        if lyric_actions.retry {
            app.retry_lyrics_for(key);
        }
        if lyric_actions.candidates {
            app.open_lyrics_candidates(key);
        }
    }
    if open_eq {
        app.now_playing = false;
        app.navigate(Page::Equalizer);
    }
    if open_sleep {
        app.open_sleep_timer();
    }
    if open_tags {
        app.open_tag_editor();
    }
    if open_speed {
        app.modal = Some(Modal::Speed {
            value: app.state.settings.playback_speed,
        });
    }
    if open_lyrics_editor {
        app.open_lyrics_editor();
    }
    if toggle_desktop {
        app.toggle_desktop_lyrics();
    }
    if locate_current {
        app.locate_current_song();
    }
    if toggle_ab {
        app.toggle_ab_loop();
    }
    if clear_ab {
        app.clear_ab_loop();
    }
    if toggle_stop_after {
        app.toggle_stop_after_current();
    }
}

fn child_ui<R>(ui: &mut Ui, rect: Rect, layout: Layout, add: impl FnOnce(&mut Ui) -> R) -> R {
    let mut child = ui.new_child(UiBuilder::new().max_rect(rect).layout(layout));
    child.set_clip_rect(rect.intersect(ui.clip_rect()));
    add(&mut child)
}

#[allow(clippy::too_many_arguments)]
fn left_column(
    ui: &mut Ui,
    app: &LiltApp,
    current: Option<&Song>,
    texture: Option<&egui::TextureHandle>,
    seed: &str,
    palette: &Palette,
    ink: Color32,
    ink_muted: Color32,
    left_w: f32,
    body_h: f32,
    toggle_fav: &mut bool,
    open_queue: &mut bool,
    locate_current: &mut bool,
    toggle_desktop: &mut bool,
) {
    let cover = (body_h - 118.0).max(120.0).min(body_h * 0.62).min(left_w);
    let (cover_rect, _) = ui.allocate_exact_size(Vec2::splat(cover), Sense::hover());
    ui.painter()
        .rect_filled(cover_rect.expand(6.0), 18.0, with_alpha(Color32::BLACK, 90));
    if let Some(texture) = texture {
        ui.put(
            cover_rect,
            egui::Image::new(texture)
                .fit_to_exact_size(cover_rect.size())
                .corner_radius(14.0),
        );
    } else {
        widgets::draw_cover(ui.painter(), cover_rect, seed, palette);
    }
    ui.add_space(14.0);
    ui.set_width(left_w);

    let title = current
        .map(|s| s.display_title().to_owned())
        .unwrap_or_else(|| app.t("Nothing playing", "暂无播放").to_owned());
    let title_size = (cover * 0.085).clamp(20.0, 34.0);
    ui.add(
        egui::Label::new(
            RichText::new(title.clone())
                .font(display_font(title_size))
                .color(ink),
        )
        .truncate(),
    )
    .on_hover_text(title);
    ui.add_space(2.0);
    let artist = current
        .map(|s| s.display_artist().to_owned())
        .unwrap_or_else(|| "—".to_owned());
    ui.add(
        egui::Label::new(
            RichText::new(artist.clone())
                .size(15.0)
                .color(with_alpha(ink, 215)),
        )
        .truncate(),
    )
    .on_hover_text(artist);
    let meta = current
        .map(|s| {
            format!(
                "{}  ·  {}  ·  {}",
                s.display_album(),
                if s.year.is_empty() {
                    s.format.clone()
                } else {
                    format!("{}  ·  {}", s.year, s.format)
                },
                format_duration(s.duration)
            )
        })
        .unwrap_or_default();
    ui.add(egui::Label::new(RichText::new(meta.clone()).size(10.5).color(ink_muted)).truncate())
        .on_hover_text(meta);
    if let Some(song) = current {
        let quality = format!(
            "{}  ·  {:.1} kHz  ·  {}  ·  {} kb/s",
            if song.channels == 2 { "stereo" } else { "mono" },
            song.sample_rate as f32 / 1000.0,
            song.format,
            song.bitrate
        );
        ui.add(egui::Label::new(RichText::new(quality).size(9.5).color(ink_muted)).truncate());
    }
    ui.add_space(10.0);
    ui.horizontal(|ui| {
        if let Some(song) = current {
            if icons::icon_button(
                ui,
                Icon::Heart,
                30.0,
                if song.favorite {
                    palette.accent
                } else {
                    with_alpha(ink, 140)
                },
                with_alpha(ink, 18),
                app.t("Favorite", "收藏"),
            )
            .clicked()
            {
                *toggle_fav = true;
            }
        }
        if icons::icon_button(
            ui,
            Icon::Queue,
            30.0,
            with_alpha(ink, 165),
            with_alpha(ink, 18),
            &format!(
                "{} · {}",
                app.t("Queue", "队列"),
                app.active_queue().map(|q| q.songs.len()).unwrap_or(0)
            ),
        )
        .clicked()
        {
            *open_queue = true;
        }
        if icons::icon_button(
            ui,
            Icon::Locate,
            30.0,
            with_alpha(ink, 165),
            with_alpha(ink, 18),
            app.t("Locate", "定位"),
        )
        .clicked()
        {
            *locate_current = true;
        }
        if icons::icon_button(
            ui,
            Icon::DesktopLyrics,
            30.0,
            if app.state.settings.desktop_lyrics {
                palette.accent
            } else {
                with_alpha(ink, 165)
            },
            with_alpha(ink, 18),
            app.t("Desktop lyrics", "桌面歌词"),
        )
        .clicked()
        {
            *toggle_desktop = true;
        }
    });
}

#[derive(Default)]
struct LyricActions {
    start_ai: bool,
    retry: bool,
    candidates: bool,
}

#[allow(clippy::too_many_arguments)]
fn right_pane(
    ui: &mut Ui,
    app: &LiltApp,
    current: Option<&Song>,
    lyrics: &[(f64, String)],
    lyrics_timed: bool,
    lyrics_offset_ms: i64,
    lyrics_status: LyricsStatus,
    lyrics_source: LyricsSource,
    queue_next: &[(String, String, String)],
    op: &Palette,
    ink: Color32,
    ink_muted: Color32,
    active_color: Color32,
    played_color: Color32,
    unplayed_color: Color32,
    lyric_size: f32,
    show_lyrics: &mut bool,
    lyrics_offset_ms_out: &mut i64,
    seek: &mut Option<f64>,
    duration: f64,
    open_lyrics_editor: &mut bool,
    toggle_desktop: &mut bool,
    actions: &mut LyricActions,
) {
    ui.horizontal(|ui| {
        if widgets::chip(ui, app.t("Lyrics", "歌词"), *show_lyrics, op).clicked() {
            *show_lyrics = true;
        }
        if widgets::chip(ui, app.t("Details", "详情"), !*show_lyrics, op).clicked() {
            *show_lyrics = false;
        }
        if *show_lyrics && !lyrics.is_empty() {
            ui.add_space(5.0);
            ui.add(
                egui::Label::new(
                    RichText::new(app.t(lyrics_source.label_en(), lyrics_source.label_zh()))
                        .size(9.5)
                        .color(with_alpha(ink, 120)),
                )
                .truncate(),
            );
        }
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if *show_lyrics {
                if icons::icon_button(
                    ui,
                    Icon::Edit,
                    28.0,
                    with_alpha(ink, 175),
                    with_alpha(ink, 18),
                    app.t("Edit lyrics", "编辑歌词"),
                )
                .clicked()
                {
                    *open_lyrics_editor = true;
                }
                if icons::icon_button(
                    ui,
                    Icon::DesktopLyrics,
                    28.0,
                    if app.state.settings.desktop_lyrics {
                        app.palette.accent
                    } else {
                        with_alpha(ink, 175)
                    },
                    with_alpha(ink, 18),
                    app.t("Desktop lyrics", "桌面歌词"),
                )
                .clicked()
                {
                    *toggle_desktop = true;
                }
            }
        });
    });

    if *show_lyrics && lyrics_timed {
        ui.add_space(2.0);
        ui.horizontal_wrapped(|ui| {
            ui.label(
                RichText::new(format!(
                    "{} {:+} ms",
                    app.t("Offset", "偏移"),
                    lyrics_offset_ms
                ))
                .size(10.0)
                .color(ink_muted),
            );
            for delta in [-500i64, -100, 100, 500] {
                if widgets::chip(ui, &format!("{delta:+}"), false, op).clicked() {
                    *lyrics_offset_ms_out =
                        library::clamp_lyrics_offset(*lyrics_offset_ms_out + delta);
                }
            }
            if *lyrics_offset_ms_out != 0
                && widgets::chip(ui, app.t("Reset", "重置"), false, op).clicked()
            {
                *lyrics_offset_ms_out = 0;
            }
        });
    }

    let list_h = ui.available_height().max(80.0);
    if !*show_lyrics {
        ScrollArea::vertical()
            .id_salt("now_playing_details")
            .max_height(list_h)
            .auto_shrink([false, false])
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                details_body(ui, app, current, queue_next, op, ink, ink_muted);
            });
        return;
    }

    if lyrics.is_empty() {
        ScrollArea::vertical()
            .id_salt("now_playing_lyrics_empty")
            .max_height(list_h)
            .auto_shrink([false, false])
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.add_space(6.0);
                empty_lyrics(ui, app, op, ink_muted, &lyrics_status, actions);
            });
        return;
    }

    let active_line = if lyrics_timed {
        let lyric_position = library::lyrics_position(app.position(), *lyrics_offset_ms_out);
        lyrics
            .iter()
            .enumerate()
            .rev()
            .find(|(_, (time, _))| *time <= lyric_position + 0.1)
            .map(|(index, _)| index)
    } else {
        None
    };
    let auto_id = egui::Id::new((
        "now_playing_lyric_auto",
        current.map(|song| song.key.clone()),
    ));
    let last_active: Option<usize> = ui.data(|data| data.get_temp(auto_id));
    let scroll_area = ScrollArea::vertical()
        .id_salt(("now_playing_lyrics", current.map(|song| song.key.clone())))
        .max_height(list_h)
        .auto_shrink([false, false])
        .scroll_bar_visibility(egui::containers::scroll_area::ScrollBarVisibility::AlwaysHidden);
    scroll_area.show(ui, |ui| {
        let width = ui.available_width().max(120.0);
        let pad = (list_h * 0.36).clamp(28.0, 180.0);
        ui.add_space(pad);
        for (index, (time, line)) in lyrics.iter().enumerate() {
            let active = Some(index) == active_line;
            let played = active_line.is_some_and(|current| index < current);
            let color = if active {
                active_color
            } else if played {
                played_color
            } else {
                unplayed_color
            };
            let size = if active { lyric_size + 3.0 } else { lyric_size };
            let response = lyric_line(ui, width, line, size, color, active);
            if response.clicked() && lyrics_timed {
                *seek = Some(
                    (library::lyrics_seek_time(*time, *lyrics_offset_ms_out) / duration.max(1.0))
                        .clamp(0.0, 1.0),
                );
            }
            if active && last_active != Some(index) {
                ui.scroll_to_rect(response.rect, Some(Align::Center));
            }
        }
        ui.add_space(pad);
        ui.data_mut(|data| data.insert_temp(auto_id, active_line));
    });
}

fn lyric_line(
    ui: &mut Ui,
    width: f32,
    text: &str,
    size: f32,
    color: Color32,
    active: bool,
) -> egui::Response {
    let wrap_width = (width - 28.0).max(80.0);
    let galley = ui.painter().layout(
        text.to_owned(),
        FontId::proportional(size),
        color,
        wrap_width,
    );
    let height = (galley.size().y + size * 0.95).max(24.0);
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, height), Sense::click());
    if !active && response.hovered() {
        ui.painter().rect_filled(
            rect.shrink2(Vec2::new(8.0, 2.0)),
            9.0,
            with_alpha(Color32::WHITE, 5),
        );
    }
    let position = Pos2::new(
        rect.center().x - galley.size().x * 0.5,
        rect.center().y - galley.size().y * 0.5,
    );
    if active {
        let underline_y = position.y + galley.size().y + 2.0;
        let half = (galley.size().x * 0.32).min(110.0);
        ui.painter().line_segment(
            [
                Pos2::new(rect.center().x - half, underline_y),
                Pos2::new(rect.center().x + half, underline_y),
            ],
            Stroke::new(2.0, color),
        );
    }
    ui.painter().galley(position, galley, color);
    response
}

fn empty_lyrics(
    ui: &mut Ui,
    app: &LiltApp,
    op: &Palette,
    ink_muted: Color32,
    status: &LyricsStatus,
    actions: &mut LyricActions,
) {
    let amber = Color32::from_rgb(0xF2, 0xB8, 0x6B);
    let mut spinner = |label: &str| {
        ui.horizontal(|ui| {
            ui.add(egui::Spinner::new().size(13.0));
            ui.label(RichText::new(label).size(12.0).color(ink_muted));
        });
    };
    match status {
        LyricsStatus::Fetching => {
            spinner(app.t("Looking up lyrics on LRCLIB…", "正在从 LRCLIB 获取歌词…"))
        }
        LyricsStatus::AiFetching => spinner(app.t("Asking the AI provider…", "正在请求 AI…")),
        LyricsStatus::NotFound => {
            ui.label(
                RichText::new(app.t(
                    "LRCLIB did not find a verified match for this track.",
                    "LRCLIB 没有找到可靠的匹配。",
                ))
                .size(12.0)
                .color(ink_muted),
            );
            online_actions(ui, app, op, false, actions);
        }
        LyricsStatus::Instrumental => {
            ui.label(
                RichText::new(app.t(
                    "LRCLIB lists this track as instrumental.",
                    "LRCLIB 将这首歌标记为纯音乐。",
                ))
                .size(12.0)
                .color(ink_muted),
            );
            online_actions(ui, app, op, false, actions);
        }
        LyricsStatus::Ambiguous(count) => {
            ui.label(
                RichText::new(format!(
                    "{} {}",
                    app.t(
                        "LRCLIB found several matching versions:",
                        "LRCLIB 找到多个匹配版本："
                    ),
                    count
                ))
                .size(12.0)
                .color(ink_muted),
            );
            online_actions(ui, app, op, true, actions);
        }
        LyricsStatus::Error(message) => {
            ui.add(
                egui::Label::new(
                    RichText::new(format!(
                        "{} {}",
                        app.t("Could not fetch lyrics:", "获取歌词失败："),
                        message
                    ))
                    .size(11.0)
                    .color(with_alpha(amber, 220)),
                )
                .wrap(),
            );
            ui.horizontal_wrapped(|ui| {
                if widgets::ghost_button(ui, app.t("Retry", "重试"), Some(Icon::Refresh), op)
                    .clicked()
                {
                    actions.retry = true;
                }
                if widgets::ghost_button(ui, app.t("AI lyrics", "AI 获取"), Some(Icon::Sparkle), op)
                    .clicked()
                {
                    actions.start_ai = true;
                }
            });
        }
        LyricsStatus::Idle => {
            ui.label(
                RichText::new(app.t(
                    "No lyrics found for this track. Add a .lrc file or write them here.",
                    "这首歌没有歌词。可以添加 .lrc 文件或直接编辑。",
                ))
                .size(12.0)
                .color(ink_muted),
            );
            online_actions(ui, app, op, false, actions);
        }
    }
    if !app.state.settings.online_lyrics {
        ui.add_space(4.0);
        ui.label(
            RichText::new(app.t(
                "Online lyrics are off. Enable them in Settings.",
                "在线歌词已关闭，可在设置中开启。",
            ))
            .size(10.0)
            .color(ink_muted),
        );
    } else if !app.ai_key_saved
        && !matches!(status, LyricsStatus::Fetching | LyricsStatus::AiFetching)
    {
        ui.add_space(4.0);
        ui.label(
            RichText::new(app.t(
                "AI fallback needs an API key in Settings.",
                "AI 兜底需要先在设置中填写 API Key。",
            ))
            .size(10.0)
            .color(ink_muted),
        );
    }
}

fn online_actions(
    ui: &mut Ui,
    app: &LiltApp,
    op: &Palette,
    can_choose: bool,
    actions: &mut LyricActions,
) {
    if !app.state.settings.online_lyrics {
        return;
    }
    ui.add_space(4.0);
    ui.horizontal_wrapped(|ui| {
        if can_choose
            && widgets::ghost_button(
                ui,
                app.t("Choose version", "选择版本"),
                Some(Icon::List),
                op,
            )
            .clicked()
        {
            actions.candidates = true;
        }
        if app.ai_key_saved
            && widgets::ghost_button(ui, app.t("AI lyrics", "AI 获取"), Some(Icon::Sparkle), op)
                .clicked()
        {
            actions.start_ai = true;
        }
        if widgets::ghost_button(
            ui,
            app.t("Search again", "重新搜索"),
            Some(Icon::Refresh),
            op,
        )
        .clicked()
        {
            actions.retry = true;
        }
    });
}

fn details_body(
    ui: &mut Ui,
    app: &LiltApp,
    current: Option<&Song>,
    queue_next: &[(String, String, String)],
    op: &Palette,
    ink: Color32,
    ink_muted: Color32,
) {
    ui.label(
        RichText::new(app.t("Up next", "接下来"))
            .font(display_font(16.0))
            .color(ink),
    );
    if queue_next.is_empty() {
        ui.label(
            RichText::new(app.t(
                "The queue is empty. Play a list to fill it.",
                "队列是空的。播放一个列表来填充。",
            ))
            .size(11.0)
            .color(ink_muted),
        );
    } else {
        for (title, artist, album) in queue_next {
            ui.horizontal(|ui| {
                let (cover, _) = ui.allocate_exact_size(Vec2::splat(34.0), Sense::hover());
                widgets::draw_cover(ui.painter(), cover, album, &app.palette);
                ui.add_space(6.0);
                ui.vertical(|ui| {
                    ui.add(egui::Label::new(RichText::new(title).size(11.5).color(ink)).truncate());
                    ui.add(
                        egui::Label::new(RichText::new(artist).size(9.5).color(ink_muted))
                            .truncate(),
                    );
                });
            });
            ui.add_space(7.0);
        }
    }
    ui.add_space(14.0);
    ui.label(
        RichText::new(app.t("FILE", "文件"))
            .size(8.5)
            .color(ink_muted)
            .strong(),
    );
    if let Some(song) = current {
        ui.label(
            RichText::new(format!(
                "{}  ·  {:.1} kHz  ·  {}  ·  {} kb/s",
                song.format,
                song.sample_rate as f32 / 1000.0,
                if song.channels == 2 { "stereo" } else { "mono" },
                song.bitrate
            ))
            .size(10.0)
            .color(ink),
        );
        ui.add(
            egui::Label::new(
                RichText::new(song.path.to_string_lossy())
                    .size(9.0)
                    .color(ink_muted),
            )
            .truncate(),
        );
        ui.add_space(8.0);
        if widgets::ghost_button(
            ui,
            app.t("Show in folder", "打开所在文件夹"),
            Some(Icon::Folder),
            op,
        )
        .clicked()
        {
            open_folder_in_explorer(song.path.as_path());
        }
    }
}

fn divider_seek(
    ui: &mut Ui,
    fraction: f64,
    palette: &Palette,
    ink: Color32,
    width: f32,
) -> Option<f64> {
    let (rect, response) =
        ui.allocate_exact_size(Vec2::new(width.max(10.0), 16.0), Sense::click_and_drag());
    let y = rect.top() + 1.0;
    let fraction = fraction.clamp(0.0, 1.0);
    ui.painter().line_segment(
        [Pos2::new(rect.left(), y), Pos2::new(rect.right(), y)],
        Stroke::new(2.0, with_alpha(ink, 32)),
    );
    let x = rect.left() + rect.width() * fraction as f32;
    if fraction > 0.0 {
        ui.painter().line_segment(
            [Pos2::new(rect.left(), y), Pos2::new(x, y)],
            Stroke::new(2.0, palette.accent),
        );
    }
    if response.hovered() || response.dragged() {
        ui.painter()
            .circle_filled(Pos2::new(x, y), 4.0, palette.accent);
        ui.painter()
            .circle_filled(Pos2::new(x, y), 1.8, palette.on_accent);
    }
    if response.clicked() || response.dragged() {
        response
            .interact_pointer_pos()
            .map(|pos| (((pos.x - rect.left()) / rect.width()).clamp(0.0, 1.0)) as f64)
    } else {
        None
    }
}

#[allow(clippy::too_many_arguments)]
fn transport_bar(
    ui: &mut Ui,
    app: &LiltApp,
    op: &Palette,
    ink: Color32,
    ink_muted: Color32,
    screen_width: f32,
    width: f32,
    fraction: f64,
    position: f64,
    duration: f64,
    seek: &mut Option<f64>,
    toggle_shuffle: &mut bool,
    prev: &mut bool,
    toggle_play: &mut bool,
    next: &mut bool,
    cycle_repeat: &mut bool,
    toggle_ab: &mut bool,
    clear_ab: &mut bool,
    toggle_stop_after: &mut bool,
    open_eq: &mut bool,
    open_sleep: &mut bool,
    open_tags: &mut bool,
    open_speed: &mut bool,
    toggle_desktop: &mut bool,
    queue_total: usize,
) {
    ui.spacing_mut().item_spacing.y = 0.0;
    if let Some(value) = divider_seek(ui, fraction, &app.palette, ink, screen_width) {
        *seek = Some(value);
    }
    ui.add_space(2.0);

    let outer_pad = ((screen_width - width) / 2.0).max(16.0);
    let content_rect = Rect::from_min_max(
        Pos2::new(ui.cursor().left() + outer_pad, ui.cursor().top()),
        Pos2::new(
            ui.cursor().left() + screen_width - outer_pad,
            (ui.max_rect().bottom() - 10.0).max(ui.cursor().top()),
        ),
    );
    child_ui(ui, content_rect, Layout::top_down(Align::Min), |ui| {
        ui.spacing_mut().item_spacing.y = 0.0;
        ui.horizontal(|ui| {
            ui.label(
                RichText::new(format_duration(position))
                    .font(FontId::monospace(10.0))
                    .color(ink_muted),
            );
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                ui.label(
                    RichText::new(format_duration(duration))
                        .font(FontId::monospace(10.0))
                        .color(ink_muted),
                );
            });
        });
        ui.add_space(3.0);

        let play_size = 54.0;
        let skip_size = 38.0;
        let edge_size = 34.0;
        let spacing = ui.spacing().item_spacing.x;
        let group = edge_size * 2.0 + skip_size * 2.0 + play_size + spacing * 4.0;
        ui.allocate_ui_with_layout(
            Vec2::new(width, play_size),
            Layout::left_to_right(Align::Center),
            |ui| {
                ui.add_space(((ui.available_width() - group) / 2.0).max(0.0));
                if icons::icon_button(
                    ui,
                    Icon::Shuffle,
                    edge_size,
                    if app.shuffle {
                        app.palette.accent
                    } else {
                        with_alpha(ink, 150)
                    },
                    with_alpha(ink, 18),
                    app.t("Shuffle", "随机"),
                )
                .clicked()
                {
                    *toggle_shuffle = true;
                }
                if icons::icon_button(
                    ui,
                    Icon::Prev,
                    skip_size,
                    ink,
                    with_alpha(ink, 18),
                    app.t("Previous", "上一首"),
                )
                .clicked()
                {
                    *prev = true;
                }
                if widgets::play_button(ui, play_size, app.playing, &app.palette).clicked() {
                    *toggle_play = true;
                }
                if icons::icon_button(
                    ui,
                    Icon::Next,
                    skip_size,
                    ink,
                    with_alpha(ink, 18),
                    app.t("Next", "下一首"),
                )
                .clicked()
                {
                    *next = true;
                }
                let repeat_icon = if app.repeat == RepeatMode::One {
                    Icon::RepeatOne
                } else {
                    Icon::Repeat
                };
                if icons::icon_button(
                    ui,
                    repeat_icon,
                    edge_size,
                    if app.repeat != RepeatMode::Off {
                        app.palette.accent
                    } else {
                        with_alpha(ink, 150)
                    },
                    with_alpha(ink, 18),
                    app.t("Repeat", "循环"),
                )
                .clicked()
                {
                    *cycle_repeat = true;
                }
            },
        );
        ui.add_space(6.0);

        ui.horizontal_wrapped(|ui| {
            let ab_label = match app.ab_loop {
                Some(AbLoop { a, b: Some(b) }) => {
                    format!("A-B {} – {}", format_duration(a), format_duration(b))
                }
                Some(AbLoop { a, b: None }) => format!(
                    "{} {} · {}",
                    app.t("A", "A"),
                    format_duration(a),
                    app.t("set B", "设置 B")
                ),
                None => app.t("A-B loop", "A-B 循环").to_owned(),
            };
            if widgets::ghost_button(ui, &ab_label, Some(Icon::Repeat), op)
                .on_hover_text(app.t(
                    "Tap once for A, again for B, once more to clear.",
                    "按一次设 A，再按设 B，再按关闭。",
                ))
                .clicked()
            {
                *toggle_ab = true;
            }
            if app.ab_loop.is_some()
                && widgets::ghost_button(ui, app.t("Clear A-B", "清除 A-B"), Some(Icon::Close), op)
                    .clicked()
            {
                *clear_ab = true;
            }
            let stop_label = if app.stop_after_current {
                format!("{} ✓", app.t("Stop after this song", "本曲结束后停止"))
            } else {
                app.t("Stop after this song", "本曲结束后停止").to_owned()
            };
            if widgets::ghost_button(ui, &stop_label, Some(Icon::Pause), op)
                .on_hover_text(app.t(
                    "Pause when the current track ends instead of playing the next one.",
                    "当前歌曲播完后暂停，不播放下一首。",
                ))
                .clicked()
            {
                *toggle_stop_after = true;
            }
            if widgets::ghost_button(ui, app.t("Equalizer", "均衡器"), Some(Icon::Gauge), op)
                .clicked()
            {
                *open_eq = true;
            }
            if widgets::ghost_button(ui, app.t("Sleep timer", "睡眠定时"), Some(Icon::Clock), op)
                .clicked()
            {
                *open_sleep = true;
            }
            let desktop_label = if app.state.settings.desktop_lyrics {
                app.t("Desktop lyrics ✓", "桌面歌词 ✓")
            } else {
                app.t("Desktop lyrics", "桌面歌词")
            };
            if widgets::ghost_button(ui, desktop_label, Some(Icon::DesktopLyrics), op)
                .on_hover_text(app.t(
                    "Show or hide desktop lyrics. Size, opacity and lock are in Settings.",
                    "显示或隐藏桌面歌词；字号、透明度和锁定在设置中调整。",
                ))
                .clicked()
            {
                *toggle_desktop = true;
            }
            if widgets::ghost_button(ui, app.t("Tags", "标签"), Some(Icon::Tag), op).clicked() {
                *open_tags = true;
            }
            if widgets::ghost_button(
                ui,
                &format!(
                    "{} {:.2}×",
                    app.t("Speed", "速度"),
                    app.state.settings.playback_speed
                ),
                Some(Icon::Gauge),
                op,
            )
            .clicked()
            {
                *open_speed = true;
            }
            ui.label(
                RichText::new(format!("{} · {}", app.t("Queue", "队列"), queue_total))
                    .size(10.0)
                    .color(ink_muted),
            );
        });
    });
}

fn open_folder_in_explorer(path: &std::path::Path) {
    let target = path.parent().unwrap_or(path);
    #[cfg(target_os = "windows")]
    {
        let _ = std::process::Command::new("explorer").arg(target).spawn();
    }
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open").arg(target).spawn();
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let _ = std::process::Command::new("xdg-open").arg(target).spawn();
    }
}
