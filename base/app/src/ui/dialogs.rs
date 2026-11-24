use crate::app::{AbLoop, ConfirmAction, LiltApp, LyricsStatus, Modal, TagEditorState};
use crate::duplicates;
use crate::icons::{self, Icon};
use crate::library;
use crate::lyrics_fetch;
use crate::model::*;
use crate::theme::{cover_palette, display_font, mix, with_alpha, Palette};
use crate::widgets;
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Layout, Margin, Rect, RichText, ScrollArea, Sense,
    Stroke, StrokeKind, Ui, Vec2,
};

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
                    .take(4)
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
    let lyrics_open = app.lyrics_open;
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
    let lyrics_font_size = app.state.settings.lyrics_font_size.clamp(10.0, 24.0);
    let lyrics_opacity = app.state.settings.lyrics_opacity.clamp(30, 100) as f32 / 100.0;
    let lyric_alpha = |value: u8| ((value as f32) * lyrics_opacity).round() as u8;

    let mut close = false;
    let mut toggle_play = false;
    let mut next = false;
    let mut prev = false;
    let mut toggle_shuffle = false;
    let mut cycle_repeat = false;
    let mut seek: Option<f64> = None;
    let mut toggle_fav = false;
    let mut open_queue = false;
    let mut toggle_lyrics = false;
    let mut open_eq = false;
    let mut open_sleep = false;
    let mut open_tags = false;
    let mut open_speed = false;
    let mut open_lyrics_editor = false;
    let mut start_ai = false;
    let mut retry_lyrics = false;
    let mut open_candidates = false;
    let mut locate_current = false;
    let mut toggle_ab = false;
    let mut clear_ab = false;
    let mut toggle_stop_after = false;

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
                match mix(cp.deep, Color32::BLACK, 0.82) {
                    c => c,
                },
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

            let content_width = (screen.width() - 70.0).min(1080.0);
            ui.allocate_ui_with_layout(
                Vec2::new(screen.width(), screen.height()),
                Layout::top_down(Align::Center),
                |ui| {
                    ui.add_space(18.0);
                    ui.allocate_ui_with_layout(
                        Vec2::new(content_width, 36.0),
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
                                    30.0,
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
                    ui.add_space(6.0);
                    let scroll_height = screen.height() - 74.0;
                    ScrollArea::vertical()
                        .id_salt("now_playing_scroll")
                        .max_height(scroll_height)
                        .auto_shrink([false, false])
                        .show(ui, |ui| {
                            ui.allocate_ui_with_layout(
                                Vec2::new(content_width, 0.0),
                                Layout::top_down(Align::Min),
                                |ui| {
                                    let cover_size = (screen.height() * 0.40)
                                        .clamp(190.0, 370.0)
                                        .min(content_width * 0.38);
                                    ui.horizontal(|ui| {
                                        let (cover_rect, _) = ui.allocate_exact_size(
                                            Vec2::splat(cover_size),
                                            Sense::hover(),
                                        );
                                        ui.painter().rect_filled(
                                            cover_rect.expand(6.0),
                                            16.0,
                                            with_alpha(Color32::BLACK, 90),
                                        );
                                        if let Some(texture) = &texture {
                                            ui.put(
                                                cover_rect,
                                                egui::Image::new(texture)
                                                    .fit_to_exact_size(cover_rect.size())
                                                    .corner_radius(12.0),
                                            );
                                        } else {
                                            widgets::draw_cover(
                                                ui.painter(),
                                                cover_rect,
                                                &seed,
                                                &palette,
                                            );
                                        }
                                        ui.add_space(32.0);
                                        ui.vertical(|ui| {
                                            ui.set_width(
                                                (content_width - cover_size - 40.0).max(260.0),
                                            );
                                            ui.add_space(4.0);
                                            let title = current
                                                .as_ref()
                                                .map(|s| s.display_title().to_owned())
                                                .unwrap_or_else(|| {
                                                    app.t("Nothing playing", "暂无播放")
                                                        .to_owned()
                                                });
                                            ui.add(
                                                egui::Label::new(
                                                    RichText::new(title)
                                                        .font(display_font(
                                                            (cover_size * 0.10)
                                                                .clamp(24.0, 38.0),
                                                        ))
                                                        .color(ink),
                                                )
                                                .wrap(),
                                            );
                                            ui.add_space(2.0);
                                            ui.label(
                                                RichText::new(
                                                    current
                                                        .as_ref()
                                                        .map(|s| s.display_artist().to_owned())
                                                        .unwrap_or_else(|| "—".to_owned()),
                                                )
                                                .size(16.0)
                                                .color(with_alpha(ink, 215)),
                                            );
                                            ui.label(
                                                RichText::new(
                                                    current
                                                        .as_ref()
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
                                                        .unwrap_or_default(),
                                                )
                                                .size(10.5)
                                                .color(ink_muted),
                                            );
                                            ui.add_space(12.0);
                                            ui.horizontal(|ui| {
                                                if let Some(song) = &current {
                                                    if icons::icon_button(
                                                        ui,
                                                        Icon::Heart,
                                                        28.0,
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
                                                        toggle_fav = true;
                                                    }
                                                }
                                                ui.add_space(6.0);
                                                if widgets::ghost_button(
                                                    ui,
                                                    &format!(
                                                        "{} · {}",
                                                        app.t("Queue", "队列"),
                                                        queue_total
                                                    ),
                                                    Some(Icon::Queue),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    open_queue = true;
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    app.t("Lyrics", "歌词"),
                                                    Some(Icon::List),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    toggle_lyrics = true;
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    app.t("Locate", "定位"),
                                                    Some(Icon::Locate),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    locate_current = true;
                                                }
                                            });
                                            ui.add_space(12.0);
                                            let seek_response =
                                                widgets::seek_bar(ui, fraction, &op, 22.0);
                                            if let Some(value) = seek_response.changed {
                                                seek = Some(value);
                                            }
                                            ui.horizontal(|ui| {
                                                ui.label(
                                                    RichText::new(format_duration(position))
                                                        .font(FontId::monospace(10.0))
                                                        .color(ink_muted),
                                                );
                                                ui.with_layout(
                                                    Layout::right_to_left(Align::Center),
                                                    |ui| {
                                                        ui.label(
                                                            RichText::new(format_duration(duration))
                                                                .font(FontId::monospace(10.0))
                                                                .color(ink_muted),
                                                        );
                                                    },
                                                );
                                            });
                                            ui.add_space(4.0);
                                            // A fixed-height row keeps every control on the same centre
                                            // line: a plain `horizontal` only centres items that fit in
                                            // its 18 px default height, so the taller play button used to
                                            // push the following buttons down by 8 px.
                                            let play_size = 54.0;
                                            let skip_size = 38.0;
                                            let edge_size = 34.0;
                                            let spacing = ui.spacing().item_spacing.x;
                                            let group = edge_size * 2.0
                                                + skip_size * 2.0
                                                + play_size
                                                + spacing * 4.0;
                                            ui.allocate_ui_with_layout(
                                                Vec2::new(ui.available_width(), play_size),
                                                Layout::left_to_right(Align::Center),
                                                |ui| {
                                                ui.add_space(
                                                    ((ui.available_width() - group) / 2.0)
                                                        .max(0.0),
                                                );
                                                if icons::icon_button(
                                                    ui,
                                                    Icon::Shuffle,
                                                    edge_size,
                                                    if app.shuffle {
                                                        palette.accent
                                                    } else {
                                                        with_alpha(ink, 150)
                                                    },
                                                    with_alpha(ink, 18),
                                                    app.t("Shuffle", "随机"),
                                                )
                                                .clicked()
                                                {
                                                    toggle_shuffle = true;
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
                                                    prev = true;
                                                }
                                                if widgets::play_button(
                                                    ui,
                                                    play_size,
                                                    app.playing,
                                                    &palette,
                                                )
                                                .clicked()
                                                {
                                                    toggle_play = true;
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
                                                    next = true;
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
                                                        palette.accent
                                                    } else {
                                                        with_alpha(ink, 150)
                                                    },
                                                    with_alpha(ink, 18),
                                                    app.t("Repeat", "循环"),
                                                )
                                                .clicked()
                                                {
                                                    cycle_repeat = true;
                                                }
                                            });
                                            ui.add_space(10.0);
                                            ui.horizontal_wrapped(|ui| {
                                                let ab_label = match app.ab_loop {
                                                    Some(AbLoop { a, b: Some(b) }) => format!(
                                                        "A-B {} – {}",
                                                        format_duration(a),
                                                        format_duration(b)
                                                    ),
                                                    Some(AbLoop { a, b: None }) => format!(
                                                        "{} {} · {}",
                                                        app.t("A", "A"),
                                                        format_duration(a),
                                                        app.t("set B", "设置 B")
                                                    ),
                                                    None => {
                                                        app.t("A-B loop", "A-B 循环").to_owned()
                                                    }
                                                };
                                                if widgets::ghost_button(
                                                    ui,
                                                    &ab_label,
                                                    Some(Icon::Repeat),
                                                    &op,
                                                )
                                                .on_hover_text(app.t(
                                                    "Tap once for A, again for B, once more to clear.",
                                                    "按一次设 A，再按设 B，再按关闭。",
                                                ))
                                                .clicked()
                                                {
                                                    toggle_ab = true;
                                                }
                                                if app.ab_loop.is_some()
                                                    && widgets::ghost_button(
                                                        ui,
                                                        app.t("Clear A-B", "清除 A-B"),
                                                        Some(Icon::Close),
                                                        &op,
                                                    )
                                                    .clicked()
                                                {
                                                    clear_ab = true;
                                                }
                                                let stop_label = if app.stop_after_current {
                                                    format!(
                                                        "{} ✓",
                                                        app.t("Stop after this song", "本曲结束后停止")
                                                    )
                                                } else {
                                                    app.t("Stop after this song", "本曲结束后停止")
                                                        .to_owned()
                                                };
                                                if widgets::ghost_button(
                                                    ui,
                                                    &stop_label,
                                                    Some(Icon::Pause),
                                                    &op,
                                                )
                                                .on_hover_text(app.t(
                                                    "Pause when the current track ends instead of playing the next one.",
                                                    "当前歌曲播完后暂停，不播放下一首。",
                                                ))
                                                .clicked()
                                                {
                                                    toggle_stop_after = true;
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    app.t("Equalizer", "均衡器"),
                                                    Some(Icon::Gauge),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    open_eq = true;
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    app.t("Sleep timer", "睡眠定时"),
                                                    Some(Icon::Clock),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    open_sleep = true;
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    app.t("Lyrics overlay", "词"),
                                                    Some(Icon::DesktopLyrics),
                                                    &op,
                                                )
                                                .on_hover_text(app.t(
                                                    "Show or hide desktop lyrics. Size, opacity and lock are in Settings.",
                                                    "显示或隐藏桌面歌词；字号、透明度和锁定在设置中调整。",
                                                ))
                                                .clicked()
                                                {
                                                    app.toggle_desktop_lyrics();
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    app.t("Tags", "标签"),
                                                    Some(Icon::Tag),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    open_tags = true;
                                                }
                                                if widgets::ghost_button(
                                                    ui,
                                                    &format!(
                                                        "{} {:.2}×",
                                                        app.t("Speed", "速度"),
                                                        app.state.settings.playback_speed
                                                    ),
                                                    Some(Icon::Gauge),
                                                    &op,
                                                )
                                                .clicked()
                                                {
                                                    open_speed = true;
                                                }
                                            });
                                        });
                                    });
                                    ui.add_space(20.0);
                                    if lyrics_open {
                                        egui::Frame::NONE
                                            .fill(with_alpha(ink, 10))
                                            .stroke(Stroke::new(1.0, with_alpha(ink, 20)))
                                            .corner_radius(14.0)
                                            .inner_margin(Margin::symmetric(18, 14))
                                            .show(ui, |ui| {
                                                ui.set_width(content_width - 36.0);
                                                ui.horizontal(|ui| {
                                                    ui.label(
                                                        RichText::new(app.t("Lyrics", "歌词"))
                                                            .font(display_font(17.0))
                                                            .color(ink),
                                                    );
                                                    if !lyrics.is_empty() {
                                                        ui.label(
                                                            RichText::new(app.t(
                                                                lyrics_source.label_en(),
                                                                lyrics_source.label_zh(),
                                                            ))
                                                            .size(9.5)
                                                            .color(with_alpha(ink, 120)),
                                                        );
                                                    }
                                                    ui.with_layout(
                                                        Layout::right_to_left(Align::Center),
                                                        |ui| {
                                                            if widgets::ghost_button(
                                                                ui,
                                                                app.t("Edit", "编辑"),
                                                                Some(Icon::Edit),
                                                                &op,
                                                            )
                                                            .clicked()
                                                            {
                                                                open_lyrics_editor = true;
                                                            }
                                                            if widgets::ghost_button(
                                                                ui,
                                                                app.t("Desktop", "桌面"),
                                                                Some(Icon::DesktopLyrics),
                                                                &op,
                                                            )
                                                            .clicked()
                                                            {
                                                                app.toggle_desktop_lyrics();
                                                            }
                                                        },
                                                    );
                                                });
                                                ui.add_space(6.0);
                                                let timed =
                                                    lyrics.iter().any(|(time, _)| *time > 0.0);
                                                if timed {
                                                    ui.horizontal_wrapped(|ui| {
                                                        ui.label(
                                                            RichText::new(format!(
                                                                "{} {:+} ms",
                                                                app.t("Offset", "偏移"),
                                                                lyrics_offset_ms
                                                            ))
                                                            .size(10.5)
                                                            .color(ink_muted),
                                                        );
                                                        for delta in [-500i64, -100, 100, 500] {
                                                            if widgets::chip(
                                                                ui,
                                                                &format!("{delta:+}"),
                                                                false,
                                                                &op,
                                                            )
                                                            .clicked()
                                                            {
                                                                lyrics_offset_ms =
                                                                    library::clamp_lyrics_offset(
                                                                        lyrics_offset_ms + delta,
                                                                    );
                                                            }
                                                        }
                                                        if lyrics_offset_ms != 0
                                                            && widgets::chip(
                                                                ui,
                                                                app.t("Reset", "重置"),
                                                                false,
                                                                &op,
                                                            )
                                                            .clicked()
                                                        {
                                                            lyrics_offset_ms = 0;
                                                        }
                                                    });
                                                    ui.add_space(4.0);
                                                }
                                                if lyrics.is_empty() {
                                                    match &lyrics_status {
                                                        LyricsStatus::Fetching => {
                                                            ui.horizontal(|ui| {
                                                                ui.add(egui::Spinner::new().size(12.0));
                                                                ui.label(
                                                                    RichText::new(app.t(
                                                                        "Looking up lyrics on LRCLIB…",
                                                                        "正在从 LRCLIB 获取歌词…",
                                                                    ))
                                                                    .size(11.0)
                                                                    .color(ink_muted),
                                                                );
                                                            });
                                                        }
                                                        LyricsStatus::AiFetching => {
                                                            ui.horizontal(|ui| {
                                                                ui.add(egui::Spinner::new().size(12.0));
                                                                ui.label(
                                                                    RichText::new(app.t(
                                                                        "Asking the AI provider…",
                                                                        "正在请求 AI…",
                                                                    ))
                                                                    .size(11.0)
                                                                    .color(ink_muted),
                                                                );
                                                            });
                                                        }
                                                        LyricsStatus::NotFound => {
                                                            ui.label(
                                                                RichText::new(app.t(
                                                                    "LRCLIB did not find a verified match for this track.",
                                                                    "LRCLIB 没有找到可靠的匹配。",
                                                                ))
                                                                .size(11.0)
                                                                .color(ink_muted),
                                                            );
                                                            if app.state.settings.online_lyrics {
                                                                ui.horizontal_wrapped(|ui| {
                                                                    if widgets::ghost_button(
                                                                        ui,
                                                                        app.t("AI lyrics", "AI 获取"),
                                                                        Some(Icon::Sparkle),
                                                                        &op,
                                                                    )
                                                                    .clicked()
                                                                    {
                                                                        start_ai = true;
                                                                    }
                                                                    if widgets::ghost_button(
                                                                        ui,
                                                                        app.t("Search again", "重新搜索"),
                                                                        Some(Icon::Refresh),
                                                                        &op,
                                                                    )
                                                                    .clicked()
                                                                    {
                                                                        retry_lyrics = true;
                                                                    }
                                                                });
                                                            }
                                                        }
                                                        LyricsStatus::Instrumental => {
                                                            ui.label(
                                                                RichText::new(app.t(
                                                                    "LRCLIB lists this track as instrumental.",
                                                                    "LRCLIB 将这首歌标记为纯音乐。",
                                                                ))
                                                                .size(11.0)
                                                                .color(ink_muted),
                                                            );
                                                            ui.horizontal_wrapped(|ui| {
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("AI lyrics", "AI 获取"),
                                                                    Some(Icon::Sparkle),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    start_ai = true;
                                                                }
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("Search again", "重新搜索"),
                                                                    Some(Icon::Refresh),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    retry_lyrics = true;
                                                                }
                                                            });
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
                                                                .size(11.0)
                                                                .color(ink_muted),
                                                            );
                                                            ui.horizontal_wrapped(|ui| {
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("Choose version", "选择版本"),
                                                                    Some(Icon::List),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    open_candidates = true;
                                                                }
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("AI lyrics", "AI 获取"),
                                                                    Some(Icon::Sparkle),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    start_ai = true;
                                                                }
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("Search again", "重新搜索"),
                                                                    Some(Icon::Refresh),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    retry_lyrics = true;
                                                                }
                                                            });
                                                        }
                                                        LyricsStatus::Error(message) => {
                                                            ui.label(
                                                                RichText::new(format!(
                                                                    "{} {}",
                                                                    app.t(
                                                                        "Could not fetch lyrics:",
                                                                        "获取歌词失败："
                                                                    ),
                                                                    message
                                                                ))
                                                                .size(10.5)
                                                                .color(with_alpha(
                                                                    Color32::from_rgb(
                                                                        0xF2, 0xB8, 0x6B,
                                                                    ),
                                                                    220,
                                                                )),
                                                            );
                                                            ui.horizontal_wrapped(|ui| {
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("Retry", "重试"),
                                                                    Some(Icon::Refresh),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    retry_lyrics = true;
                                                                }
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("AI lyrics", "AI 获取"),
                                                                    Some(Icon::Sparkle),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    start_ai = true;
                                                                }
                                                            });
                                                        }
                                                        LyricsStatus::Idle => {
                                                            ui.label(
                                                                RichText::new(app.t(
                                                                    "No lyrics found for this track. Add a .lrc file or write them here.",
                                                                    "这首歌没有歌词。可以添加 .lrc 文件或直接编辑。",
                                                                ))
                                                                .size(11.0)
                                                                .color(ink_muted),
                                                            );
                                                            if app.state.settings.online_lyrics {
                                                                ui.horizontal_wrapped(|ui| {
                                                                    if widgets::ghost_button(
                                                                        ui,
                                                                        app.t("Get lyrics", "获取歌词"),
                                                                        Some(Icon::Sparkle),
                                                                        &op,
                                                                    )
                                                                    .clicked()
                                                                    {
                                                                        retry_lyrics = true;
                                                                    }
                                                                    if app.ai_key_saved
                                                                        && widgets::ghost_button(
                                                                            ui,
                                                                            app.t("AI lyrics", "AI 获取"),
                                                                            Some(Icon::Sparkle),
                                                                            &op,
                                                                        )
                                                                        .clicked()
                                                                    {
                                                                        start_ai = true;
                                                                    }
                                                                });
                                                            }
                                                        }
                                                    }
                                                    if !app.state.settings.online_lyrics {
                                                        ui.label(
                                                            RichText::new(app.t(
                                                                "Online lyrics are off. Enable them in Settings.",
                                                                "在线歌词已关闭，可在设置中开启。",
                                                            ))
                                                            .size(10.0)
                                                            .color(ink_muted),
                                                        );
                                                    } else if !app.ai_key_saved
                                                        && !matches!(
                                                            lyrics_status,
                                                            LyricsStatus::Fetching
                                                                | LyricsStatus::AiFetching
                                                        )
                                                    {
                                                        ui.label(
                                                            RichText::new(app.t(
                                                                "AI fallback needs an API key in Settings.",
                                                                "AI 兜底需要先在设置中填写 API Key。",
                                                            ))
                                                            .size(10.0)
                                                            .color(ink_muted),
                                                        );
                                                    }
                                                } else {
                                                    let lyric_position =
                                                        library::lyrics_position(
                                                            position,
                                                            lyrics_offset_ms,
                                                        );
                                                    let active_line = lyrics
                                                        .iter()
                                                        .enumerate()
                                                        .rev()
                                                        .find(|(_, (time, _))| {
                                                            *time <= lyric_position + 0.1
                                                        })
                                                        .map(|(index, _)| index);
                                                    ScrollArea::vertical()
                                                        .id_salt("now_playing_lyrics")
                                                        .max_height(180.0)
                                                        .show(ui, |ui| {
                                                            for (index, (time, line)) in
                                                                lyrics.iter().enumerate()
                                                            {
                                                                let active =
                                                                    Some(index) == active_line;
                                                                let response = ui.add(
                                                                    egui::Button::new(
                                                                        RichText::new(line)
                                                                            .size(if active {
                                                                                lyrics_font_size
                                                                                    + 1.5
                                                                            } else {
                                                                                lyrics_font_size
                                                                            })
                                                                            .color(if active {
                                                                                with_alpha(
                                                                                    palette.accent,
                                                                                    lyric_alpha(255),
                                                                                )
                                                                            } else {
                                                                                with_alpha(
                                                                                    ink,
                                                                                    lyric_alpha(165),
                                                                                )
                                                                            }),
                                                                    )
                                                                    .frame(false),
                                                                );
                                                                if response.clicked() {
                                                                    seek = Some(
                                                                        (library::lyrics_seek_time(
                                                                            *time,
                                                                            lyrics_offset_ms,
                                                                        ) / duration
                                                                            .max(1.0))
                                                                        .clamp(0.0, 1.0),
                                                                    );
                                                                }
                                                            }
                                                        });
                                                }
                                            });
                                    } else {
                                        ui.horizontal_top(|ui| {
                                            let card_width = if content_width > 760.0 {
                                                (content_width - 14.0) * 0.56
                                            } else {
                                                content_width
                                            };
                                            ui.allocate_ui_with_layout(
                                                Vec2::new(card_width, 0.0),
                                                Layout::top_down(Align::Min),
                                                |ui| {
                                                    egui::Frame::NONE
                                                        .fill(with_alpha(ink, 9))
                                                        .stroke(Stroke::new(1.0, with_alpha(ink, 18)))
                                                        .corner_radius(14.0)
                                                        .inner_margin(Margin::symmetric(18, 12))
                                                        .show(ui, |ui| {
                                                            ui.set_width(card_width - 36.0);
                                                            ui.horizontal(|ui| {
                                                                ui.label(
                                                                    RichText::new(app.t("Up next", "接下来"))
                                                                        .font(display_font(16.0))
                                                                        .color(ink),
                                                                );
                                                                ui.with_layout(
                                                                    Layout::right_to_left(Align::Center),
                                                                    |ui| {
                                                                        ui.label(
                                                                            RichText::new(format!(
                                                                                "{}",
                                                                                queue_next.len()
                                                                            ))
                                                                            .size(10.0)
                                                                            .color(ink_muted),
                                                                        );
                                                                    },
                                                                );
                                                            });
                                                            ui.add_space(4.0);
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
                                                                        let (cover, _) = ui.allocate_exact_size(
                                                                            Vec2::splat(32.0),
                                                                            Sense::hover(),
                                                                        );
                                                                        widgets::draw_cover(
                                                                            ui.painter(),
                                                                            cover,
                                                                            &album,
                                                                            &palette,
                                                                        );
                                                                        ui.add_space(8.0);
                                                                        ui.vertical(|ui| {
                                                                            ui.label(
                                                                                RichText::new(title)
                                                                                    .size(11.5)
                                                                                    .color(ink),
                                                                            );
                                                                            ui.label(
                                                                                RichText::new(artist)
                                                                                    .size(9.5)
                                                                                    .color(ink_muted),
                                                                            );
                                                                        });
                                                                    });
                                                                }
                                                            }
                                                        });
                                                },
                                            );
                                            if content_width > 760.0 {
                                                ui.add_space(14.0);
                                                let detail_width =
                                                    content_width - card_width - 14.0;
                                                ui.allocate_ui_with_layout(
                                                    Vec2::new(detail_width, 0.0),
                                                    Layout::top_down(Align::Min),
                                                    |ui| {
                                                        egui::Frame::NONE
                                                            .fill(with_alpha(ink, 6))
                                                            .stroke(Stroke::new(
                                                                1.0,
                                                                with_alpha(ink, 14),
                                                            ))
                                                            .corner_radius(14.0)
                                                            .inner_margin(Margin::symmetric(16, 12))
                                                            .show(ui, |ui| {
                                                                ui.set_width(detail_width - 32.0);
                                                                ui.label(
                                                                    RichText::new(app.t("FILE", "文件"))
                                                                        .size(8.5)
                                                                        .color(ink_muted)
                                                                        .strong(),
                                                                );
                                                                if let Some(song) = &current {
                                                                    ui.label(
                                                                        RichText::new(format!(
                                                                            "{}  ·  {:.0} kHz  ·  {}  ·  {} kb/s",
                                                                            song.format,
                                                                            song.sample_rate as f32
                                                                                / 1000.0,
                                                                            if song.channels == 2 {
                                                                                "stereo"
                                                                            } else {
                                                                                "mono"
                                                                            },
                                                                            song.bitrate
                                                                        ))
                                                                        .size(10.0)
                                                                        .color(ink),
                                                                    );
                                                                    let clip = ui.painter().with_clip_rect(
                                                                        Rect::from_min_size(
                                                                            ui.cursor().min,
                                                                            Vec2::new(
                                                                                detail_width - 32.0,
                                                                                28.0,
                                                                            ),
                                                                        ),
                                                                    );
                                                                    clip.text(
                                                                        ui.cursor().min,
                                                                        Align2::LEFT_TOP,
                                                                        song.path.to_string_lossy(),
                                                                        FontId::proportional(9.0),
                                                                        ink_muted,
                                                                    );
                                                                    ui.add_space(28.0);
                                                                }
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("Show in folder", "打开所在文件夹"),
                                                                    Some(Icon::Folder),
                                                                    &op,
                                                                )
                                                                .clicked()
                                                                {
                                                                    if let Some(song) = &current {
                                                                        open_folder_in_explorer(song.path.as_path());
                                                                    }
                                                                }
                                                            });
                                                    },
                                                );
                                            }
                                        });
                                    }
                                    ui.add_space(24.0);
                                },
                            );
                        });
                },
            );
        });

    if close {
        app.now_playing = false;
    }
    if toggle_play {
        app.toggle_play();
    }
    if let Some(key) = &lyrics_song_key {
        if lyrics_offset_ms != app.lyrics_offset_for(key) {
            app.set_lyrics_offset(key, lyrics_offset_ms);
        }
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
    if toggle_lyrics {
        app.lyrics_open = !app.lyrics_open;
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
    if open_lyrics_editor {
        app.open_lyrics_editor();
    }
    if let Some(key) = &lyrics_song_key {
        if start_ai {
            app.start_ai_lyrics(key);
        }
        if retry_lyrics {
            app.retry_lyrics_for(key);
        }
        if open_candidates {
            app.open_lyrics_candidates(key);
        }
    }
    if locate_current {
        app.locate_current_song();
    }
    if open_speed {
        app.modal = Some(Modal::Speed {
            value: app.state.settings.playback_speed,
        });
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

enum ModalAction {
    CreatePlaylist(String, Vec<String>),
    RenamePlaylist(u64, String),
    CreateQueue(String, Vec<String>),
    RenameQueue(u64, String),
    AddQueue(u64, Vec<String>),
    AddPlaylist(u64, Vec<String>),
    LoadQueue(u64, u64),
    Bookmark(String, String),
    Tags(TagEditorState),
    Lyrics(String, String),
    SaveAiLyrics(String, String),
    UseLyricsCandidate(String, String),
    Sleep {
        hours: u32,
        minutes: u32,
        songs: u32,
        mode: crate::app::SleepMode,
        wait_till_end: bool,
        fade: bool,
    },
    SleepStop,
    Speed(f32),
    Confirm(ConfirmAction),
}

pub fn modals(app: &mut LiltApp, ctx: &egui::Context) {
    let palette = app.palette;
    let screen = ctx.screen_rect();
    egui::Area::new(egui::Id::new("modal_dim"))
        .fixed_pos(screen.min)
        .order(egui::Order::Foreground)
        .show(ctx, |ui| {
            ui.set_min_size(screen.size());
            let (rect, _) = ui.allocate_exact_size(screen.size(), Sense::click());
            ui.painter()
                .rect_filled(rect, 0.0, Color32::from_black_alpha(150));
        });

    let Some(mut modal) = app.modal.take() else {
        return;
    };
    let mut close = false;
    let mut action: Option<ModalAction> = None;

    egui::Area::new(egui::Id::new("modal_window"))
        .anchor(Align2::CENTER_CENTER, [0.0, 0.0])
        .order(egui::Order::Tooltip)
        .show(ctx, |ui| {
            egui::Frame::NONE
                .fill(palette.surface)
                .stroke(Stroke::new(1.0, palette.border_strong))
                .corner_radius(16.0)
                .inner_margin(Margin::symmetric(22, 18))
                .show(ui, |ui| {
                    ui.set_width(460.0);
                    match &mut modal {
                        Modal::NewPlaylist { name, songs } => {
                            modal_header(ui, &palette, app.t("New playlist", "新建播放列表"), &mut close);
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Give the set a name. It stays on this device.",
                                    "给歌单起个名字，只保存在本机。",
                                ))
                                .size(11.0)
                                .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            text_field(ui, &palette, app.t("Name", "名称"), name);
                            ui.add_space(6.0);
                            ui.label(
                                RichText::new(format!(
                                    "{} {}",
                                    songs.len(),
                                    app.t("songs selected", "首歌曲已选择")
                                ))
                                .size(10.0)
                                .color(palette.faint),
                            );
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(
                                    ui,
                                    app.t("Create", "创建"),
                                    Some(Icon::Plus),
                                    &palette,
                                )
                                .clicked()
                                {
                                    action = Some(ModalAction::CreatePlaylist(
                                        name.clone(),
                                        songs.clone(),
                                    ));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette)
                                    .clicked()
                                {
                                    close = true;
                                }
                            });
                        }
                        Modal::RenamePlaylist { id, name } => {
                            modal_header(ui, &palette, app.t("Rename playlist", "重命名播放列表"), &mut close);
                            ui.add_space(8.0);
                            text_field(ui, &palette, app.t("Name", "名称"), name);
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Save", "保存"), None, &palette).clicked() {
                                    action = Some(ModalAction::RenamePlaylist(*id, name.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::NewQueue { name, songs } => {
                            modal_header(ui, &palette, app.t("New queue", "新建队列"), &mut close);
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Queues are temporary. Up to twenty live side by side.",
                                    "队列是临时的，最多可同时保存二十个。",
                                ))
                                .size(11.0)
                                .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            text_field(ui, &palette, app.t("Name", "名称"), name);
                            ui.add_space(6.0);
                            ui.label(
                                RichText::new(format!(
                                    "{} {}",
                                    songs.len(),
                                    app.t("songs ready", "首歌曲待加入")
                                ))
                                .size(10.0)
                                .color(palette.faint),
                            );
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Create", "创建"), Some(Icon::Plus), &palette).clicked() {
                                    action = Some(ModalAction::CreateQueue(name.clone(), songs.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::RenameQueue { id, name } => {
                            modal_header(ui, &palette, app.t("Rename queue", "重命名队列"), &mut close);
                            ui.add_space(8.0);
                            text_field(ui, &palette, app.t("Name", "名称"), name);
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Save", "保存"), None, &palette).clicked() {
                                    action = Some(ModalAction::RenameQueue(*id, name.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::AddToQueue {
                            keys,
                            queue_id,
                            new_name,
                        } => {
                            modal_header(ui, &palette, app.t("Add to queue", "加入队列"), &mut close);
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(format!("{} {}", keys.len(), app.t("songs", "首歌曲")))
                                    .size(11.0)
                                    .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            let queues = app.state.queues.clone();
                            for queue in &queues {
                                let selected = *queue_id == Some(queue.id);
                                let label = format!(
                                    "{}  ·  {}",
                                    queue.label(),
                                    crate::i18n::tracks(app.lang(), queue.songs.len())
                                );
                                if ui
                                    .selectable_label(selected, label)
                                    .clicked()
                                {
                                    action = Some(ModalAction::AddQueue(queue.id, keys.clone()));
                                    close = true;
                                }
                            }
                            ui.add_space(10.0);
                            ui.label(
                                RichText::new(app.t("Or make a new queue", "或者新建队列"))
                                    .size(10.5)
                                    .color(palette.faint),
                            );
                            ui.add_space(4.0);
                            text_field(ui, &palette, app.t("Name", "名称"), new_name);
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Add", "加入"), Some(Icon::Plus), &palette).clicked() {
                                    action = Some(ModalAction::CreateQueue(new_name.clone(), keys.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::AddToPlaylist {
                            keys,
                            playlist_id,
                            new_name,
                        } => {
                            modal_header(ui, &palette, app.t("Add to playlist", "加入播放列表"), &mut close);
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(format!("{} {}", keys.len(), app.t("songs", "首歌曲")))
                                    .size(11.0)
                                    .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            let playlists = app.state.playlists.clone();
                            if playlists.is_empty() {
                                ui.label(
                                    RichText::new(app.t(
                                        "No playlists yet. Name one below.",
                                        "还没有播放列表，在下面创建一个。",
                                    ))
                                    .size(10.5)
                                    .color(palette.faint),
                                );
                            }
                            for playlist in &playlists {
                                let selected = *playlist_id == Some(playlist.id);
                                let label = format!(
                                    "{}  ·  {}",
                                    playlist.name,
                                    crate::i18n::tracks(app.lang(), playlist.songs.len())
                                );
                                if ui.selectable_label(selected, label).clicked() {
                                    action = Some(ModalAction::AddPlaylist(playlist.id, keys.clone()));
                                    close = true;
                                }
                            }
                            ui.add_space(10.0);
                            ui.label(
                                RichText::new(app.t("Or make a new playlist", "或者新建播放列表"))
                                    .size(10.5)
                                    .color(palette.faint),
                            );
                            ui.add_space(4.0);
                            text_field(ui, &palette, app.t("Name", "名称"), new_name);
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Add", "加入"), Some(Icon::Plus), &palette).clicked() {
                                    action = Some(ModalAction::CreatePlaylist(new_name.clone(), keys.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::LoadQueueFromPlaylist { queue_id } => {
                            modal_header(ui, &palette, app.t("Load a playlist", "装载播放列表"), &mut close);
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "The queue's current name-conflict setting decides merge or replace.",
                                    "按设置中的同名策略合并或替换。",
                                ))
                                .size(10.5)
                                .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            let playlists = app.state.playlists.clone();
                            if playlists.is_empty() {
                                ui.label(
                                    RichText::new(app.t("No playlists to load.", "没有可装载的播放列表。"))
                                        .size(11.0)
                                        .color(palette.faint),
                                );
                            }
                            for playlist in playlists {
                                if ui
                                    .button(format!(
                                        "{}  ·  {}",
                                        playlist.name,
                                        crate::i18n::tracks(app.lang(), playlist.songs.len())
                                    ))
                                    .clicked()
                                {
                                    action = Some(ModalAction::LoadQueue(*queue_id, playlist.id));
                                    close = true;
                                }
                            }
                        }
                        Modal::Bookmark { key, note } => {
                            modal_header(ui, &palette, app.t("Bookmark or note", "书签或笔记"), &mut close);
                            ui.add_space(4.0);
                            let title = app
                                .song_by_key(key)
                                .map(|s| s.display_title().to_owned())
                                .unwrap_or_default();
                            ui.label(
                                RichText::new(title)
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            ui.add_space(8.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Saved at the current moment, if this is the playing song.",
                                    "如果这是正在播放的歌曲，将记录当前时间点。",
                                ))
                                .size(10.0)
                                .color(palette.faint),
                            );
                            ui.add_space(6.0);
                            text_field(ui, &palette, app.t("Note", "笔记"), note);
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Save", "保存"), Some(Icon::Bookmark), &palette).clicked() {
                                    action = Some(ModalAction::Bookmark(key.clone(), note.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::TagEditor(state) => {
                            modal_header(ui, &palette, app.t("Edit tags", "编辑标签"), &mut close);
                            ui.add_space(2.0);
                            ui.label(
                                RichText::new(format!(
                                    "{} {}",
                                    state.keys.len(),
                                    app.t(
                                        "song(s). Empty fields are left unchanged.",
                                        "首歌曲。留空的字段不会改动。"
                                    )
                                ))
                                .size(10.5)
                                .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            egui::Grid::new("tag_editor_grid")
                                .num_columns(2)
                                .spacing([12.0, 8.0])
                                .show(ui, |ui| {
                                    field(ui, &palette, app.t("Title", "标题"), &mut state.title);
                                    ui.end_row();
                                    field(ui, &palette, app.t("Artist", "艺术家"), &mut state.artist);
                                    ui.end_row();
                                    field(ui, &palette, app.t("Album", "专辑"), &mut state.album);
                                    ui.end_row();
                                    field(ui, &palette, app.t("Genre", "流派"), &mut state.genre);
                                    ui.end_row();
                                    field(ui, &palette, app.t("Year", "年份"), &mut state.year);
                                    ui.end_row();
                                    field(ui, &palette, app.t("Track", "音轨号"), &mut state.track);
                                    ui.end_row();
                                });
                            ui.add_space(6.0);
                            ui.horizontal(|ui| {
                                ui.label(
                                    RichText::new(app.t("Cover", "封面"))
                                        .size(11.0)
                                        .color(palette.text),
                                );
                                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                    if widgets::ghost_button(ui, app.t("Choose image", "选择图片"), Some(Icon::Upload), &palette).clicked() {
                                        if let Some(path) = rfd::FileDialog::new()
                                            .add_filter("Images", &["png", "jpg", "jpeg", "webp", "bmp"])
                                            .pick_file()
                                        {
                                            state.cover = Some(path.to_string_lossy().to_string());
                                        }
                                    }
                                    if state.cover.is_some() && widgets::ghost_button(ui, app.t("Clear", "清除"), None, &palette).clicked() {
                                        state.cover = None;
                                    }
                                });
                            });
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Save tags", "保存标签"), Some(Icon::Check), &palette).clicked() {
                                    action = Some(ModalAction::Tags(state.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::LyricsEditor { key, text } => {
                            modal_header(ui, &palette, app.t("Edit lyrics", "编辑歌词"), &mut close);
                            ui.add_space(2.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Plain text or LRC with [mm:ss.xx] timestamps. Saved as a .lrc beside the audio.",
                                    "支持纯文本或带 [mm:ss.xx] 时间轴的 LRC，保存为音频旁的 .lrc 文件。",
                                ))
                                .size(10.0)
                                .color(palette.muted),
                            );
                            ui.add_space(6.0);
                            egui::ScrollArea::vertical()
                                .id_salt("lyrics_editor_scroll")
                                .max_height(240.0)
                                .show(ui, |ui| {
                                    ui.add(
                                        egui::TextEdit::multiline(text)
                                            .desired_rows(12)
                                            .desired_width(f32::INFINITY)
                                            .font(FontId::monospace(11.0))
                                            .text_color(palette.text),
                                    );
                                });
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Save lyrics", "保存歌词"), Some(Icon::Check), &palette).clicked() {
                                    action = Some(ModalAction::Lyrics(key.clone(), text.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::AiLyricsPreview {
                            key,
                            title,
                            provider,
                            text,
                        } => {
                            modal_header(ui, &palette, app.t("AI lyrics", "AI 歌词"), &mut close);
                            ui.add_space(2.0);
                            ui.label(
                                RichText::new(format!("{} · {}", title, provider))
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            ui.label(
                                RichText::new(app.t(
                                    "AI results can be incomplete or inaccurate. Check the text before saving. Only verified LRC should keep sync.",
                                    "AI 结果可能不完整或不准确，请检查后再保存。只有带时间轴的 LRC 才能同步高亮。",
                                ))
                                .size(10.0)
                                .color(palette.warn),
                            );
                            ui.add_space(6.0);
                            let duration = app
                                .song_by_key(key)
                                .map(|song| song.duration)
                                .unwrap_or(0.0);
                            let check = lyrics_fetch::inspect_lyrics(text, duration);
                            ui.horizontal_wrapped(|ui| {
                                ui.label(
                                    RichText::new(format!(
                                        "{} {} · {}",
                                        check.lines,
                                        app.t("line(s)", "行"),
                                        if check.timed {
                                            app.t("timed LRC", "带时间轴")
                                        } else {
                                            app.t("plain text", "纯文本")
                                        }
                                    ))
                                    .size(10.5)
                                    .color(if check.timed {
                                        palette.accent
                                    } else {
                                        palette.muted
                                    }),
                                );
                                for warning in &check.warnings {
                                    ui.label(
                                        RichText::new(warning)
                                            .size(10.0)
                                            .color(palette.warn),
                                    );
                                }
                            });
                            ui.add_space(6.0);
                            egui::ScrollArea::vertical()
                                .id_salt("ai_lyrics_preview_scroll")
                                .max_height(260.0)
                                .show(ui, |ui| {
                                    ui.add(
                                        egui::TextEdit::multiline(text)
                                            .desired_rows(14)
                                            .desired_width(f32::INFINITY)
                                            .font(FontId::monospace(11.0))
                                            .text_color(palette.text),
                                    );
                                });
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                ui.add_enabled_ui(!text.trim().is_empty(), |ui| {
                                    if widgets::primary_button(
                                        ui,
                                        app.t("Save AI lyrics", "保存 AI 歌词"),
                                        Some(Icon::Check),
                                        &palette,
                                    )
                                    .clicked()
                                    {
                                        action = Some(ModalAction::SaveAiLyrics(
                                            key.clone(),
                                            text.trim().to_owned(),
                                        ));
                                        close = true;
                                    }
                                });
                                if widgets::ghost_button(
                                    ui,
                                    app.t("Cancel", "取消"),
                                    None,
                                    &palette,
                                )
                                .clicked()
                                {
                                    close = true;
                                }
                            });
                        }
                        Modal::LyricsCandidates {
                            key,
                            title,
                            candidates,
                            selected,
                        } => {
                            modal_header(
                                ui,
                                &palette,
                                app.t("Choose a lyrics version", "选择歌词版本"),
                                &mut close,
                            );
                            ui.add_space(2.0);
                            ui.label(
                                RichText::new(title.as_str())
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            ui.label(
                                RichText::new(app.t(
                                    "LRCLIB returned several close matches. Pick the version that matches this recording.",
                                    "LRCLIB 返回了多个相近版本，请选择与当前录音匹配的一版。",
                                ))
                                .size(10.0)
                                .color(palette.muted),
                            );
                            ui.add_space(6.0);
                            egui::ScrollArea::vertical()
                                .id_salt("lyrics_candidates_scroll")
                                .max_height(170.0)
                                .show(ui, |ui| {
                                    for (index, candidate) in candidates.iter().enumerate() {
                                        let synced = if candidate.record.is_synced() {
                                            app.t("LRC", "LRC")
                                        } else {
                                            app.t("Text", "文本")
                                        };
                                        let label = format!(
                                            "{} — {} · {} · {} · {}",
                                            candidate.record.track_name,
                                            candidate.record.artist_name,
                                            candidate.record.album_name,
                                            format_duration(candidate.record.duration),
                                            synced
                                        );
                                        if ui
                                            .selectable_label(*selected == index, label)
                                            .clicked()
                                        {
                                            *selected = index;
                                        }
                                    }
                                });
                            ui.add_space(6.0);
                            let preview = candidates
                                .get(*selected)
                                .and_then(|candidate| candidate.record.text())
                                .unwrap_or("")
                                .to_owned();
                            egui::Frame::NONE
                                .fill(palette.surface_hi)
                                .corner_radius(8.0)
                                .inner_margin(Margin::symmetric(10, 8))
                                .show(ui, |ui| {
                                    egui::ScrollArea::vertical()
                                        .id_salt("lyrics_candidate_preview")
                                        .max_height(150.0)
                                        .show(ui, |ui| {
                                            ui.add(
                                                egui::Label::new(
                                                    RichText::new(if preview.is_empty() {
                                                        app.t("No text for this version.", "该版本没有歌词文本。")
                                                    } else {
                                                        preview.as_str()
                                                    })
                                                    .font(FontId::monospace(10.5))
                                                    .color(palette.text),
                                                )
                                                .wrap(),
                                            );
                                        });
                                });
                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                ui.add_enabled_ui(!preview.is_empty(), |ui| {
                                    if widgets::primary_button(
                                        ui,
                                        app.t("Use this version", "使用这个版本"),
                                        Some(Icon::Check),
                                        &palette,
                                    )
                                    .clicked()
                                    {
                                        action = Some(ModalAction::UseLyricsCandidate(
                                            key.clone(),
                                            preview.clone(),
                                        ));
                                        close = true;
                                    }
                                });
                                if widgets::ghost_button(
                                    ui,
                                    app.t("Cancel", "取消"),
                                    None,
                                    &palette,
                                )
                                .clicked()
                                {
                                    close = true;
                                }
                            });
                        }
                        Modal::SleepTimer {
                            hours,
                            minutes,
                            songs,
                            mode,
                            wait_till_end,
                            fade,
                        } => {
                            modal_header(ui, &palette, app.t("Sleep timer", "睡眠定时"), &mut close);
                            ui.add_space(4.0);
                            if let Some(remaining) = app.sleep_remaining() {
                                ui.label(
                                    RichText::new(format!(
                                        "{} {}",
                                        app.t("Time remaining:", "剩余时间："),
                                        format_duration(remaining as f64)
                                    ))
                                    .size(12.0)
                                    .color(palette.accent),
                                );
                            } else if let Some(left) = app.sleep_songs_left {
                                ui.label(
                                    RichText::new(format!(
                                        "{} {left} {}",
                                        app.t("Stops after", "剩余"),
                                        app.t("songs", "首后停止")
                                    ))
                                    .size(12.0)
                                    .color(palette.accent),
                                );
                            } else {
                                ui.label(
                                    RichText::new(app.t(
                                        "The target remembers the last five times and song counts.",
                                        "目标版本会记住最近五次时长与歌曲数。",
                                    ))
                                    .size(11.0)
                                    .color(palette.muted),
                                );
                            }
                            ui.add_space(8.0);
                            ui.horizontal(|ui| {
                                if widgets::chip(
                                    ui,
                                    app.t("After time", "按时长"),
                                    *mode == crate::app::SleepMode::AfterTime,
                                    &palette,
                                )
                                .clicked()
                                {
                                    *mode = crate::app::SleepMode::AfterTime;
                                }
                                if widgets::chip(
                                    ui,
                                    app.t("After N songs", "按歌曲数"),
                                    *mode == crate::app::SleepMode::AfterSongs,
                                    &palette,
                                )
                                .clicked()
                                {
                                    *mode = crate::app::SleepMode::AfterSongs;
                                }
                            });
                            ui.add_space(8.0);
                            if *mode == crate::app::SleepMode::AfterTime {
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(app.t("Hours", "小时"))
                                            .size(11.0)
                                            .color(palette.text),
                                    );
                                    ui.add(egui::DragValue::new(hours).range(0..=23));
                                    ui.add_space(12.0);
                                    ui.label(
                                        RichText::new(app.t("Minutes", "分钟"))
                                            .size(11.0)
                                            .color(palette.text),
                                    );
                                    ui.add(egui::DragValue::new(minutes).range(0..=59));
                                    ui.label(
                                        RichText::new(app.t(
                                            "The target adds 1 second to the request.",
                                            "目标版本会额外加 1 秒。",
                                        ))
                                        .size(9.5)
                                        .color(palette.faint),
                                    );
                                });
                                ui.add_space(6.0);
                                ui.horizontal_wrapped(|ui| {
                                    let history = app.state.settings.sleep_history_hm.clone();
                                    for (h, m) in history {
                                        let label = format!("{h}:{m:02}");
                                        if widgets::chip(
                                            ui,
                                            &label,
                                            *hours == h && *minutes == m,
                                            &palette,
                                        )
                                        .clicked()
                                        {
                                            *hours = h;
                                            *minutes = m;
                                        }
                                    }
                                });
                            } else {
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(app.t("Songs", "歌曲数"))
                                            .size(11.0)
                                            .color(palette.text),
                                    );
                                    ui.add(egui::DragValue::new(songs).range(1..=99));
                                });
                                ui.add_space(6.0);
                                ui.horizontal_wrapped(|ui| {
                                    let history = app.state.settings.sleep_history_n.clone();
                                    for n in history {
                                        if widgets::chip(
                                            ui,
                                            &format!("N = {n}"),
                                            *songs == n,
                                            &palette,
                                        )
                                        .clicked()
                                        {
                                            *songs = n;
                                        }
                                    }
                                });
                            }
                            ui.add_space(8.0);
                            if *mode == crate::app::SleepMode::AfterTime {
                                ui.checkbox(
                                    wait_till_end,
                                    app.t(
                                        "Wait until the current song ends",
                                        "等待当前歌曲播完",
                                    ),
                                );
                            }
                            ui.checkbox(
                                fade,
                                app.t(
                                    "Gradually fade out the last song",
                                    "最后一首歌曲逐渐淡出",
                                ),
                            );
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(
                                    ui,
                                    app.t("Start timer", "开始计时"),
                                    Some(Icon::Clock),
                                    &palette,
                                )
                                .clicked()
                                {
                                    action = Some(ModalAction::Sleep {
                                        hours: *hours,
                                        minutes: *minutes,
                                        songs: *songs,
                                        mode: *mode,
                                        wait_till_end: *wait_till_end,
                                        fade: *fade,
                                    });
                                    close = true;
                                }
                                if (app.sleep_deadline.is_some() || app.sleep_songs_left.is_some())
                                    && widgets::ghost_button(
                                        ui,
                                        app.t("Stop timer", "停止计时"),
                                        None,
                                        &palette,
                                    )
                                    .clicked()
                                {
                                    action = Some(ModalAction::SleepStop);
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette)
                                    .clicked()
                                {
                                    close = true;
                                }
                            });
                        }
                        Modal::Speed { value } => {
                            modal_header(ui, &palette, app.t("Play speed", "播放速度"), &mut close);
                            ui.add_space(6.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Pitch follows the speed in this desktop build.",
                                    "此桌面版本中音调会随速度变化。",
                                ))
                                .size(10.5)
                                .color(palette.muted),
                            );
                            ui.add_space(8.0);
                            // `h71.onClick`: 0.50..=3.00 in 0.01 steps.
                            ui.add(
                                egui::Slider::new(value, 0.50..=3.00)
                                    .step_by(0.01)
                                    .suffix("×"),
                            );
                            ui.add_space(8.0);
                            ui.horizontal_wrapped(|ui| {
                                // `h71.t`: 0.50, 0.75, 0.90, 1.00, 1.25, 1.50, 2.00.
                                for speed in [0.50f32, 0.75, 0.90, 1.0, 1.25, 1.50, 2.0] {
                                    if widgets::chip(
                                        ui,
                                        &format!("{speed:.2}×"),
                                        (*value - speed).abs() < 0.01,
                                        &palette,
                                    )
                                    .clicked()
                                    {
                                        *value = speed;
                                    }
                                }
                            });
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Apply", "应用"), Some(Icon::Check), &palette).clicked() {
                                    action = Some(ModalAction::Speed(*value));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::Confirm {
                            title,
                            body,
                            action: confirm_action,
                        } => {
                            modal_header(ui, &palette, title, &mut close);
                            ui.add_space(6.0);
                            ui.label(
                                RichText::new(body.as_str())
                                    .size(11.5)
                                    .color(palette.muted),
                            );
                            ui.add_space(14.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(ui, app.t("Confirm", "确认"), Some(Icon::Check), &palette).clicked() {
                                    action = Some(ModalAction::Confirm(confirm_action.clone()));
                                    close = true;
                                }
                                if widgets::ghost_button(ui, app.t("Cancel", "取消"), None, &palette).clicked() {
                                    close = true;
                                }
                            });
                        }
                        Modal::Duplicates(groups) => {
                            modal_header(
                                ui,
                                &palette,
                                app.t("Duplicate songs", "重复歌曲"),
                                &mut close,
                            );
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Groups share a normalised title and artist with a similar length. The best copy is marked Keep. Lilt only reports duplicates; it never deletes files.",
                                    "分组依据是规范化后的标题、艺术家与相近时长。最佳副本标记为“保留”。Lilt 只报告重复，不会删除文件。",
                                ))
                                .size(10.5)
                                .color(palette.muted),
                            );
                            ui.add_space(10.0);
                            if groups.is_empty() {
                                ui.label(
                                    RichText::new(app.t(
                                        "No duplicate songs found.",
                                        "没有找到重复歌曲。",
                                    ))
                                    .size(11.5)
                                    .color(palette.text),
                                );
                            } else {
                                let group_count = groups.len();
                                let file_count = groups.iter().map(Vec::len).sum::<usize>();
                                let group_text = if group_count == 1 {
                                    format!("1 {}", app.t("duplicate group", "组重复"))
                                } else {
                                    format!(
                                        "{group_count} {}",
                                        app.t("duplicate groups", "组重复")
                                    )
                                };
                                let file_text = if file_count == 1 {
                                    format!("1 {}", app.t("file", "个文件"))
                                } else {
                                    format!("{file_count} {}", app.t("files", "个文件"))
                                };
                                ui.label(
                                    RichText::new(format!("{group_text} · {file_text}"))
                                        .size(10.5)
                                        .color(palette.muted),
                                );
                                ui.add_space(6.0);
                                ScrollArea::vertical()
                                    .id_salt("duplicate_groups_scroll")
                                    .max_height(350.0)
                                    .auto_shrink([false, false])
                                    .show(ui, |ui| {
                                        for group in groups.iter() {
                                            let Some(first) = group.first() else {
                                                continue;
                                            };
                                            egui::Frame::NONE
                                                .fill(palette.surface_hi)
                                                .corner_radius(10.0)
                                                .inner_margin(Margin::symmetric(10, 8))
                                                .show(ui, |ui| {
                                                    ui.set_width(400.0);
                                                    ui.label(
                                                        RichText::new(first.display_title())
                                                            .size(12.0)
                                                            .color(palette.text)
                                                            .strong(),
                                                    );
                                                    ui.label(
                                                        RichText::new(format!(
                                                            "{} · {}",
                                                            first.artist, first.album
                                                        ))
                                                        .size(9.5)
                                                        .color(palette.muted),
                                                    );
                                                    ui.add_space(4.0);
                                                    let keys: Vec<String> =
                                                        group.iter().map(|s| s.key.clone()).collect();
                                                    let name =
                                                        app.t("Duplicate songs", "重复歌曲").to_owned();
                                                    for (index, song) in group.iter().enumerate() {
                                                        ui.horizontal(|ui| {
                                                            ui.vertical(|ui| {
                                                                ui.set_width(270.0);
                                                                ui.horizontal(|ui| {
                                                                    if index == 0 {
                                                                        ui.label(
                                                                            RichText::new(
                                                                                app.t("KEEP", "保留"),
                                                                            )
                                                                            .size(8.5)
                                                                            .color(palette.accent)
                                                                            .strong(),
                                                                        );
                                                                    }
                                                                    ui.label(
                                                                        RichText::new(
                                                                            song.display_title(),
                                                                        )
                                                                        .size(10.5)
                                                                        .color(palette.text),
                                                                    );
                                                                });
                                                                let format = if song
                                                                    .format
                                                                    .is_empty()
                                                                {
                                                                    app.t("Audio", "音频").to_owned()
                                                                } else {
                                                                    song.format.to_uppercase()
                                                                };
                                                                let bitrate = if song.bitrate > 0 {
                                                                    format!("{} kbps", song.bitrate)
                                                                } else {
                                                                    "—".to_owned()
                                                                };
                                                                ui.label(
                                                                    RichText::new(format!(
                                                                        "{} · {} · {} · {}",
                                                                        format,
                                                                        bitrate,
                                                                        duplicates::format_size(
                                                                            song.size
                                                                        ),
                                                                        format_duration(
                                                                            song.duration
                                                                        )
                                                                    ))
                                                                    .size(9.0)
                                                                    .color(palette.muted),
                                                                );
                                                                ui.label(
                                                                    RichText::new(
                                                                        song.path
                                                                            .to_string_lossy()
                                                                            .to_string(),
                                                                    )
                                                                    .size(8.5)
                                                                    .color(palette.faint),
                                                                );
                                                            });
                                                            ui.with_layout(
                                                                Layout::right_to_left(
                                                                    Align::Center,
                                                                ),
                                                                |ui| {
                                                                    if widgets::ghost_button(
                                                                        ui,
                                                                        app.t("Show", "打开"),
                                                                        Some(Icon::Folder),
                                                                        &palette,
                                                                    )
                                                                    .clicked()
                                                                    {
                                                                        open_folder_in_explorer(
                                                                            &song.path,
                                                                        );
                                                                    }
                                                                    if widgets::ghost_button(
                                                                        ui,
                                                                        app.t("Play", "播放"),
                                                                        Some(Icon::Play),
                                                                        &palette,
                                                                    )
                                                                    .clicked()
                                                                    {
                                                                        app.play_context(
                                                                            keys.clone(),
                                                                            index,
                                                                            &name,
                                                                        );
                                                                    }
                                                                },
                                                            );
                                                        });
                                                        if index + 1 < group.len() {
                                                            ui.add_space(4.0);
                                                        }
                                                    }
                                                });
                                            ui.add_space(6.0);
                                        }
                                    });
                            }
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(
                                    ui,
                                    app.t("Close", "关闭"),
                                    Some(Icon::Check),
                                    &palette,
                                )
                                .clicked()
                                {
                                    close = true;
                                }
                            });
                        }
                        Modal::MissingFiles(missing) => {
                            let mut refresh_missing = false;
                            modal_header(
                                ui,
                                &palette,
                                app.t("Missing files", "失效文件"),
                                &mut close,
                            );
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Playlists and saved song data keep their place when a file moves. Choose the new file to reconnect it; audio files are never changed.",
                                    "文件移动后，播放列表和歌曲数据仍会保留。选择新文件即可重新关联；不会修改音频文件本身。",
                                ))
                                .size(10.5)
                                .color(palette.muted),
                            );
                            ui.add_space(10.0);
                            if missing.is_empty() {
                                ui.label(
                                    RichText::new(app.t(
                                        "No missing files. Everything in the library resolves.",
                                        "没有失效文件，曲库中的引用都能找到。",
                                    ))
                                    .size(11.5)
                                    .color(palette.text),
                                );
                            } else {
                                ScrollArea::vertical()
                                    .id_salt("missing_files_scroll")
                                    .max_height(330.0)
                                    .auto_shrink([false, false])
                                    .show(ui, |ui| {
                                        for file in missing.iter() {
                                            egui::Frame::NONE
                                                .fill(palette.surface_hi)
                                                .corner_radius(10.0)
                                                .inner_margin(Margin::symmetric(10, 8))
                                                .show(ui, |ui| {
                                                    ui.set_width(400.0);
                                                    ui.horizontal(|ui| {
                                                        ui.vertical(|ui| {
                                                            ui.label(
                                                                RichText::new(&file.title)
                                                                    .size(12.0)
                                                                    .color(palette.text)
                                                                    .strong(),
                                                            );
                                                            ui.label(
                                                                RichText::new(&file.path)
                                                                    .size(9.0)
                                                                    .color(palette.faint),
                                                            );
                                                            let mut refs = Vec::new();
                                                            if !file.playlists.is_empty() {
                                                                refs.push(format!(
                                                                    "{}: {}",
                                                                    app.t("Playlists", "播放列表"),
                                                                    file.playlists.join(", ")
                                                                ));
                                                            }
                                                            if !file.queues.is_empty() {
                                                                refs.push(format!(
                                                                    "{}: {}",
                                                                    app.t("Queues", "队列"),
                                                                    file.queues.join(", ")
                                                                ));
                                                            }
                                                            if file.favorite {
                                                                refs.push(
                                                                    app.t("Favourite", "收藏")
                                                                        .to_owned(),
                                                                );
                                                            }
                                                            if file.lyrics {
                                                                refs.push(
                                                                    app.t("Lyrics", "歌词")
                                                                        .to_owned(),
                                                                );
                                                            }
                                                            if file.bookmarks > 0 {
                                                                refs.push(format!(
                                                                    "{} {}",
                                                                    file.bookmarks,
                                                                    app.t("bookmarks", "个书签")
                                                                ));
                                                            }
                                                            if file.plays > 0 {
                                                                refs.push(format!(
                                                                    "{} {}",
                                                                    file.plays,
                                                                    app.t("plays", "次播放")
                                                                ));
                                                            }
                                                            if !refs.is_empty() {
                                                                ui.label(
                                                                    RichText::new(refs.join(" · "))
                                                                        .size(9.5)
                                                                        .color(palette.muted),
                                                                );
                                                            }
                                                        });
                                                        ui.with_layout(
                                                            Layout::right_to_left(Align::Center),
                                                            |ui| {
                                                                if widgets::ghost_button(
                                                                    ui,
                                                                    app.t("Locate…", "定位…"),
                                                                    Some(Icon::Locate),
                                                                    &palette,
                                                                )
                                                                .clicked()
                                                                {
                                                                    app.relink_missing_file(
                                                                        &file.key,
                                                                    );
                                                                    refresh_missing = true;
                                                                }
                                                            },
                                                        );
                                                    });
                                                });
                                            ui.add_space(6.0);
                                        }
                                    });
                            }
                            if refresh_missing {
                                *missing = app.missing_files();
                            }
                            ui.add_space(12.0);
                            ui.horizontal(|ui| {
                                if widgets::primary_button(
                                    ui,
                                    app.t("Close", "关闭"),
                                    Some(Icon::Check),
                                    &palette,
                                )
                                .clicked()
                                {
                                    close = true;
                                }
                            });
                        }
                        Modal::About => {
                            modal_header(ui, &palette, app.t("About Lilt", "关于 Lilt"), &mut close);
                            ui.add_space(6.0);
                            ui.label(
                                RichText::new(format!("Lilt {}", APP_VERSION))
                                    .font(display_font(18.0))
                                    .color(palette.text),
                            );
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "A local, offline music player built for a calm library. It reads audio files from folders you choose, stores playlists and preferences on this machine, and never makes a network request.",
                                    "一个安静、纯本地的音乐播放器。只读取你选择的文件夹，播放列表与偏好都保存在本机，不发起任何网络请求。",
                                ))
                                .size(11.0)
                                .color(palette.muted),
                            );
                            ui.add_space(10.0);
                            ui.label(
                                RichText::new(app.t(
                                    "All artwork is drawn in code. No original app assets are used.",
                                    "所有封面均由代码绘制，不使用任何原应用素材。",
                                ))
                                .size(10.0)
                                .color(palette.faint),
                            );
                        }
                        Modal::Shortcuts => {
                            modal_header(ui, &palette, app.t("Keyboard shortcuts", "键盘快捷键"), &mut close);
                            ui.add_space(8.0);
                            for (keys, label) in [
                                ("Space", app.t("Play / pause", "播放 / 暂停")),
                                ("Ctrl + →", app.t("Next track", "下一首")),
                                ("Ctrl + ←", app.t("Previous track", "上一首")),
                                ("Ctrl + F", app.t("Search", "搜索")),
                                ("Esc", app.t("Close a panel", "关闭面板")),
                            ] {
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(keys)
                                            .font(FontId::monospace(10.5))
                                            .color(palette.accent),
                                    );
                                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                        ui.label(
                                            RichText::new(label)
                                                .size(11.0)
                                                .color(palette.text),
                                        );
                                    });
                                });
                                ui.add_space(3.0);
                            }
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(app.t(
                                    "Global shortcuts, also while Lilt is in the tray",
                                    "全局快捷键，Lilt 在托盘后台时同样有效",
                                ))
                                .size(10.0)
                                .color(palette.muted),
                            );
                            ui.add_space(4.0);
                            for (keys, label) in [
                                ("Ctrl + Alt + Space", app.t("Play / pause", "播放 / 暂停")),
                                ("Ctrl + Alt + →", app.t("Next track", "下一首")),
                                ("Ctrl + Alt + ←", app.t("Previous track", "上一首")),
                                ("Ctrl + Alt + ↑ / ↓", app.t("Volume ±5%", "音量 ±5%")),
                                ("Ctrl + Alt + L", app.t("Desktop lyrics", "桌面歌词")),
                            ] {
                                ui.horizontal(|ui| {
                                    ui.label(
                                        RichText::new(keys)
                                            .font(FontId::monospace(10.5))
                                            .color(palette.accent),
                                    );
                                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                        ui.label(
                                            RichText::new(label)
                                                .size(11.0)
                                                .color(palette.text),
                                        );
                                    });
                                });
                                ui.add_space(3.0);
                            }
                        }
                    }
                });
        });

    if !close {
        app.modal = Some(modal);
    }
    if let Some(action) = action {
        apply_modal_action(app, action);
    }
}

fn modal_header(ui: &mut Ui, palette: &Palette, title: &str, close: &mut bool) {
    ui.horizontal(|ui| {
        ui.label(
            RichText::new(title)
                .font(display_font(20.0))
                .color(palette.text),
        );
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if icons::icon_button(
                ui,
                Icon::Close,
                28.0,
                palette.muted,
                palette.surface_hi,
                "Close",
            )
            .clicked()
            {
                *close = true;
            }
        });
    });
    widgets::divider(ui, palette);
}

fn text_field(ui: &mut Ui, palette: &Palette, label: &str, value: &mut String) {
    ui.label(RichText::new(label).size(10.0).color(palette.faint));
    ui.add(
        egui::TextEdit::singleline(value)
            .desired_width(f32::INFINITY)
            .text_color(palette.text),
    );
}

fn field(ui: &mut Ui, palette: &Palette, label: &str, value: &mut String) {
    ui.label(RichText::new(label).size(10.5).color(palette.muted));
    ui.add(
        egui::TextEdit::singleline(value)
            .desired_width(280.0)
            .text_color(palette.text),
    );
}

fn apply_modal_action(app: &mut LiltApp, action: ModalAction) {
    match action {
        ModalAction::CreatePlaylist(name, songs) => {
            if name.trim().is_empty() {
                app.notify(
                    app.t("Give the playlist a name", "请给播放列表起个名字")
                        .to_owned(),
                );
                return;
            }
            app.create_playlist(name, songs);
        }
        ModalAction::RenamePlaylist(id, name) => app.rename_playlist(id, name),
        ModalAction::CreateQueue(name, songs) => {
            let name = if name.trim().is_empty() {
                app.t("New queue", "新队列").to_owned()
            } else {
                name
            };
            let policy = app.state.settings.queue_conflict;
            let id = app.create_or_replace_queue(&name, songs, policy);
            app.state.active_queue = id;
            app.notify(app.t("Queue ready", "队列已就绪").to_owned());
        }
        ModalAction::RenameQueue(id, name) => app.rename_queue(id, name),
        ModalAction::AddQueue(id, keys) => {
            app.add_to_queue(id, keys);
            app.state.active_queue = id;
        }
        ModalAction::AddPlaylist(id, keys) => app.add_to_playlist(id, keys),
        ModalAction::LoadQueue(queue_id, playlist_id) => {
            app.load_playlist_into_queue(queue_id, playlist_id);
        }
        ModalAction::Bookmark(key, note) => app.set_bookmark(&key, note),
        ModalAction::Tags(state) => app.set_tags(state.keys.clone(), state),
        ModalAction::Lyrics(key, text) => app.save_lyrics_for(&key, text),
        ModalAction::SaveAiLyrics(key, text) => {
            app.save_fetched_lyrics(&key, &text, LyricsSource::Ai, true);
            app.notify(app.t("AI lyrics saved", "AI 歌词已保存").to_owned());
        }
        ModalAction::UseLyricsCandidate(key, text) => {
            app.save_fetched_lyrics(&key, &text, LyricsSource::Lrclib, true);
            app.notify(app.t("Lyrics saved", "歌词已保存").to_owned());
        }
        ModalAction::Sleep {
            hours,
            minutes,
            songs,
            mode,
            wait_till_end,
            fade,
        } => {
            app.state.settings.sleep_wait_till_end = wait_till_end;
            app.state.settings.sleep_fade = fade;
            match mode {
                crate::app::SleepMode::AfterTime => {
                    app.set_sleep_time(hours, minutes, wait_till_end, fade)
                }
                crate::app::SleepMode::AfterSongs => {
                    app.set_sleep_songs(songs, wait_till_end, fade)
                }
            }
        }
        ModalAction::SleepStop => app.stop_sleep_timer(),
        ModalAction::Speed(value) => app.set_speed(value),
        ModalAction::Confirm(action) => match action {
            ConfirmAction::DeletePlaylist(id) => app.delete_playlist(id),
            ConfirmAction::DeleteQueue(id) => app.delete_queue(id),
            ConfirmAction::ClearQueue(id) => {
                if let Some(queue) = app.state.queues.iter_mut().find(|q| q.id == id) {
                    queue.songs.clear();
                    queue.index = 0;
                    queue.position = 0.0;
                }
                app.mark_dirty();
            }
            ConfirmAction::RemoveLibraryRoot(path) => {
                app.state.library_roots.retain(|p| p != &path);
                app.mark_dirty();
                app.start_scan();
            }
            ConfirmAction::RemoveExcluded(path) => {
                app.state.excluded.retain(|p| p != &path);
                app.mark_dirty();
                app.start_scan();
            }
            ConfirmAction::RemoveIncluded(path) => {
                app.state.included.retain(|p| p != &path);
                app.mark_dirty();
                app.start_scan();
            }
            ConfirmAction::ClearHistory => {
                for extra in app.state.songs.values_mut() {
                    extra.play_count = 0;
                    extra.last_played = None;
                    extra.last_position = 0.0;
                }
                for song in &mut app.songs {
                    song.play_count = 0;
                    song.last_played = None;
                    song.last_position = 0.0;
                }
                app.counted_current = false;
                app.mark_dirty();
                app.save_now();
                app.notify(
                    app.t("Playback history cleared", "播放历史已清除")
                        .to_owned(),
                );
            }
            ConfirmAction::RestoreBackup(path) => app.restore_backup(&path),
            ConfirmAction::ResetStarter => {
                app.notify(
                    app.t("Starter library already present", "初始曲库已存在")
                        .to_owned(),
                );
            }
        },
    }
}
