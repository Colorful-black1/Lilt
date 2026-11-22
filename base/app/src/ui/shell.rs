use crate::app::LiltApp;
use crate::icons::{self, Icon};
use crate::model::*;
use crate::theme::{display_font, with_alpha, Palette};
use crate::ui::common::{detail_parent, nav_page};
use crate::widgets;
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Layout, Margin, Rect, RichText, Sense, Stroke,
    StrokeKind, Ui, Vec2,
};

pub fn render(app: &mut LiltApp, ctx: &egui::Context) {
    let palette = app.palette;
    let screen = ctx.screen_rect();
    let compact = screen.width() < 1000.0;

    egui::TopBottomPanel::top("top_bar")
        .exact_height(64.0)
        .frame(
            egui::Frame::NONE
                .fill(palette.bg)
                .inner_margin(Margin::symmetric(18, 0)),
        )
        .show(ctx, |ui| {
            top_bar(app, ui, &palette, compact);
            let rect = ui.max_rect();
            ui.painter().line_segment(
                [
                    egui::pos2(rect.left(), rect.bottom()),
                    egui::pos2(rect.right(), rect.bottom()),
                ],
                Stroke::new(1.0, palette.border),
            );
        });

    egui::TopBottomPanel::bottom("player_bar")
        .exact_height(94.0)
        .frame(
            egui::Frame::NONE
                .fill(palette.surface)
                .inner_margin(Margin::symmetric(18, 7)),
        )
        .show(ctx, |ui| {
            let rect = ui.max_rect();
            ui.painter().line_segment(
                [
                    egui::pos2(rect.left(), rect.top()),
                    egui::pos2(rect.right(), rect.top()),
                ],
                Stroke::new(1.0, palette.border),
            );
            player_bar(app, ctx, ui, &palette, compact);
        });

    egui::SidePanel::left("sidebar")
        .exact_width(if compact { 68.0 } else { 218.0 })
        .resizable(false)
        .frame(
            egui::Frame::NONE
                .fill(palette.sidebar)
                .inner_margin(Margin::symmetric(if compact { 8 } else { 11 }, 12)),
        )
        .show(ctx, |ui| {
            sidebar(app, ui, &palette, compact);
            let rect = ui.max_rect();
            ui.painter().line_segment(
                [
                    egui::pos2(rect.right(), rect.top()),
                    egui::pos2(rect.right(), rect.bottom()),
                ],
                Stroke::new(1.0, palette.border),
            );
        });

    if app.queue_drawer {
        egui::SidePanel::right("queue_drawer")
            .exact_width(310.0)
            .resizable(false)
            .frame(
                egui::Frame::NONE
                    .fill(palette.surface)
                    .inner_margin(Margin::symmetric(14, 12)),
            )
            .show(ctx, |ui| queue_drawer(app, ui, &palette));
    }

    egui::CentralPanel::default()
        .frame(
            egui::Frame::NONE
                .fill(palette.bg)
                .inner_margin(Margin::symmetric(if compact { 18 } else { 26 }, 18)),
        )
        .show(ctx, |ui| {
            crate::ui::pages::render_page(app, ui, &palette, compact);
        });

    if app.now_playing {
        crate::ui::dialogs::now_playing(app, ctx);
    }
    if app.modal.is_some() {
        crate::ui::dialogs::modals(app, ctx);
    }
    draw_toast(app, ctx, &palette);
}

fn draw_logo(painter: &egui::Painter, rect: Rect, palette: &Palette) {
    painter.rect_filled(rect, 9.0, palette.accent);
    let clip = painter.with_clip_rect(rect);
    for i in 0..3 {
        let y = rect.center().y - 5.0 + i as f32 * 5.0;
        let width = 12.0 - (i as f32 * 2.0);
        let start = rect.center().x - width / 2.0;
        clip.line_segment(
            [egui::pos2(start, y), egui::pos2(start + width, y)],
            Stroke::new(2.0, palette.on_accent),
        );
    }
    clip.circle_filled(
        egui::pos2(rect.center().x + 6.0, rect.center().y - 6.0),
        2.0,
        palette.on_accent,
    );
}

fn top_bar(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, compact: bool) {
    let height = ui.available_height();
    ui.allocate_ui_with_layout(
        Vec2::new(ui.available_width(), height),
        Layout::left_to_right(Align::Center),
        |ui| {
            let (logo_rect, _) = ui.allocate_exact_size(Vec2::splat(30.0), Sense::hover());
            draw_logo(ui.painter(), logo_rect, palette);
            ui.add_space(9.0);
            ui.vertical(|ui| {
                ui.add_space(2.0);
                ui.label(
                    RichText::new("Lilt")
                        .font(display_font(21.0))
                        .color(palette.text),
                );
                if !compact {
                    ui.label(
                        RichText::new(app.t("OFFLINE MUSIC", "离线音乐"))
                            .font(FontId::proportional(8.0))
                            .color(palette.faint)
                            .strong(),
                    );
                }
            });
            ui.add_space(18.0);

            let right_reserve = if compact { 154.0 } else { 244.0 };
            let search_width = (ui.available_width() - right_reserve).clamp(150.0, 410.0);
            let frame = egui::Frame::NONE
                .fill(palette.surface)
                .stroke(Stroke::new(1.0, palette.border))
                .corner_radius(10.0)
                .inner_margin(Margin::symmetric(10, 5));
            frame.show(ui, |ui| {
                ui.set_width(search_width - 20.0);
                ui.horizontal(|ui| {
                    icons::paint(
                        ui.painter(),
                        Rect::from_min_size(
                            ui.cursor().min + Vec2::new(0.0, 5.0),
                            Vec2::splat(15.0),
                        ),
                        Icon::Search,
                        palette.muted,
                        1.5,
                    );
                    ui.add_space(22.0);
                    let hint = app.t("Search your library", "搜索曲库");
                    let response = ui.add(
                        egui::TextEdit::singleline(&mut app.search)
                            .hint_text(hint)
                            .frame(false)
                            .desired_width((search_width - 72.0).max(60.0))
                            .text_color(palette.text),
                    );
                    if app.search_focus {
                        response.request_focus();
                        app.search_focus = false;
                    }
                    if !app.search.is_empty() {
                        if ui
                            .add(
                                egui::Button::new(
                                    RichText::new("×").size(14.0).color(palette.muted),
                                )
                                .frame(false)
                                .min_size(Vec2::splat(18.0)),
                            )
                            .clicked()
                        {
                            app.search.clear();
                        }
                    }
                });
            });

            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if icons::icon_button(
                    ui,
                    Icon::Settings,
                    30.0,
                    palette.muted,
                    palette.surface_hi,
                    app.t("Settings", "设置"),
                )
                .clicked()
                {
                    app.navigate(Page::Settings);
                }
                if icons::icon_button(
                    ui,
                    Icon::Theme,
                    30.0,
                    palette.muted,
                    palette.surface_hi,
                    app.t("Change theme", "切换主题"),
                )
                .clicked()
                {
                    app.state.settings.theme = match app.state.settings.theme {
                        ThemeMode::Light => ThemeMode::Dark,
                        ThemeMode::Dark => ThemeMode::Black,
                        ThemeMode::Black => ThemeMode::DayNight,
                        ThemeMode::DayNight => ThemeMode::System,
                        ThemeMode::System => ThemeMode::Light,
                    };
                    app.mark_dirty();
                }
                let language_label = match app.lang() {
                    Language::En => "中文",
                    Language::Zh => "EN",
                };
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(language_label)
                                .size(11.0)
                                .color(palette.muted),
                        )
                        .fill(Color32::TRANSPARENT)
                        .stroke(Stroke::NONE)
                        .min_size(Vec2::new(30.0, 26.0)),
                    )
                    .clicked()
                {
                    app.state.settings.language = match app.lang() {
                        Language::En => Language::Zh,
                        Language::Zh => Language::En,
                    };
                    app.mark_dirty();
                }
                if app.current_song().is_some()
                    && icons::icon_button(
                        ui,
                        Icon::Locate,
                        30.0,
                        palette.accent,
                        palette.surface_hi,
                        app.t("Locate current song", "定位当前歌曲"),
                    )
                    .clicked()
                {
                    app.locate_current_song();
                }
                if !compact {
                    ui.add_space(6.0);
                    egui::Frame::NONE
                        .fill(with_alpha(palette.good, 16))
                        .corner_radius(10.0)
                        .inner_margin(Margin::symmetric(8, 4))
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                ui.painter().circle_filled(
                                    ui.cursor().min + Vec2::new(3.0, 8.0),
                                    2.5,
                                    palette.good,
                                );
                                ui.add_space(10.0);
                                ui.label(
                                    RichText::new(app.t("OFFLINE", "离线"))
                                        .size(8.5)
                                        .color(palette.good)
                                        .strong(),
                                );
                            });
                        });
                }
            });
        },
    );
}

fn sidebar(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, compact: bool) {
    let current_page = match &app.detail {
        Some(detail) => Some(detail_parent(detail)),
        None => Some(app.page),
    };
    let nav_height = (ui.available_height() - if compact { 100.0 } else { 105.0 }).max(140.0);
    egui::ScrollArea::vertical()
        .id_salt("sidebar_scroll")
        .max_height(nav_height)
        .auto_shrink([false, false])
        .show(ui, |ui| {
            ui.spacing_mut().item_spacing.y = 3.0;
            if compact {
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Folder,
                    "Folders",
                    "文件夹",
                    Page::Folders,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::List,
                    "Playlists",
                    "播放列表",
                    Page::Playlists,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Music,
                    "Songs",
                    "歌曲",
                    Page::Songs,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Disc,
                    "Albums",
                    "专辑",
                    Page::Albums,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Sparkle,
                    "Artists",
                    "艺术家",
                    Page::Artists,
                    current_page,
                    true,
                );
                ui.add_space(8.0);
                widgets::divider(ui, palette);
                ui.add_space(8.0);
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Gauge,
                    "Most played",
                    "最多播放",
                    Page::MostPlayed,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Clock,
                    "Recently added",
                    "最近添加",
                    Page::Recent,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Refresh,
                    "History",
                    "历史",
                    Page::History,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Bookmark,
                    "Bookmarks",
                    "书签",
                    Page::Bookmarks,
                    current_page,
                    true,
                );
                ui.add_space(8.0);
                widgets::divider(ui, palette);
                ui.add_space(8.0);
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Queue,
                    "Queues",
                    "队列",
                    Page::Queues,
                    current_page,
                    true,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Gauge,
                    "Equalizer",
                    "均衡器",
                    Page::Equalizer,
                    current_page,
                    true,
                );
            } else {
                widgets::section_label(ui, app.t("Library", "曲库"), palette);
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Folder,
                    app.t("Folders", "文件夹"),
                    app.t("Folders", "文件夹"),
                    Page::Folders,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::List,
                    app.t("Playlists", "播放列表"),
                    app.t("Playlists", "播放列表"),
                    Page::Playlists,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Music,
                    app.t("Songs", "歌曲"),
                    app.t("Songs", "歌曲"),
                    Page::Songs,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Disc,
                    app.t("Albums", "专辑"),
                    app.t("Albums", "专辑"),
                    Page::Albums,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Sparkle,
                    app.t("Artists", "艺术家"),
                    app.t("Artists", "艺术家"),
                    Page::Artists,
                    current_page,
                    false,
                );
                ui.add_space(10.0);
                widgets::section_label(ui, app.t("Collections", "收藏"), palette);
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Gauge,
                    app.t("Most played", "最多播放"),
                    app.t("Most played", "最多播放"),
                    Page::MostPlayed,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Clock,
                    app.t("Recently added", "最近添加"),
                    app.t("Recently added", "最近添加"),
                    Page::Recent,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Refresh,
                    app.t("History", "播放历史"),
                    app.t("History", "播放历史"),
                    Page::History,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Bookmark,
                    app.t("Bookmarks", "书签"),
                    app.t("Bookmarks", "书签"),
                    Page::Bookmarks,
                    current_page,
                    false,
                );
                ui.add_space(12.0);
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Queue,
                    app.t("Queues", "队列"),
                    app.t("Queues", "队列"),
                    Page::Queues,
                    current_page,
                    false,
                );
                nav_item(
                    app,
                    ui,
                    palette,
                    Icon::Gauge,
                    app.t("Equalizer", "均衡器"),
                    app.t("Equalizer", "均衡器"),
                    Page::Equalizer,
                    current_page,
                    false,
                );
            }
        });

    if compact {
        ui.vertical_centered(|ui| {
            if icons::icon_button(
                ui,
                Icon::Settings,
                34.0,
                palette.muted,
                palette.surface_hi,
                app.t("Settings", "设置"),
            )
            .clicked()
            {
                app.navigate(Page::Settings);
            }
            if icons::icon_button(
                ui,
                Icon::Refresh,
                34.0,
                if app.scanning {
                    palette.accent
                } else {
                    palette.muted
                },
                palette.surface_hi,
                app.t("Rescan library", "重新扫描"),
            )
            .clicked()
            {
                app.start_scan();
            }
            ui.add_space(4.0);
            ui.label(
                RichText::new(app.songs.len().to_string())
                    .size(10.0)
                    .color(palette.faint),
            );
        });
        return;
    }

    ui.vertical(|ui| {
        egui::Frame::NONE
            .fill(palette.surface)
            .stroke(Stroke::new(1.0, palette.border))
            .corner_radius(12.0)
            .inner_margin(Margin::symmetric(12, 10))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(app.t("LIBRARY", "曲库"))
                            .size(8.5)
                            .color(palette.faint)
                            .strong(),
                    );
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if icons::icon_button(
                            ui,
                            Icon::Refresh,
                            24.0,
                            if app.scanning {
                                palette.accent
                            } else {
                                palette.muted
                            },
                            palette.surface_hi,
                            app.t("Rescan library", "重新扫描"),
                        )
                        .clicked()
                        {
                            app.start_scan();
                        }
                    });
                });
                ui.add_space(2.0);
                let total: f64 = app.songs.iter().map(|s| s.duration).sum();
                ui.label(
                    RichText::new(format!(
                        "{}  ·  {}",
                        crate::i18n::tracks(app.lang(), app.songs.len()),
                        crate::i18n::albums(app.lang(), app.albums.len())
                    ))
                    .font(display_font(13.5))
                    .color(palette.text),
                );
                ui.label(
                    RichText::new(match app.last_scan {
                        Some(ts) => format!(
                            "{}  ·  {}",
                            crate::i18n::total_duration(app.lang(), total),
                            crate::i18n::when(app.lang(), Some(ts))
                        ),
                        None => crate::i18n::total_duration(app.lang(), total),
                    })
                    .size(9.0)
                    .color(palette.faint),
                );
                if app.scanning {
                    let response = ui.label(
                        RichText::new(format!(
                            "{} {}",
                            app.t("Scanning", "正在扫描"),
                            app.scan_seen
                        ))
                        .size(9.5)
                        .color(palette.accent),
                    );
                    response.on_hover_text(&app.scan_path);
                }
            });
    });
}

#[allow(clippy::too_many_arguments)]
fn nav_item(
    app: &mut LiltApp,
    ui: &mut Ui,
    palette: &Palette,
    icon: Icon,
    label: &str,
    tooltip: &str,
    page: Page,
    current: Option<Page>,
    compact: bool,
) {
    let selected = current == Some(nav_page(page));
    if widgets::nav_item(ui, icon, label, selected, compact, palette)
        .on_hover_text(tooltip)
        .clicked()
    {
        app.navigate(page);
        if page == Page::Folders {
            app.folder_path = None;
        }
    }
}

fn player_bar(
    app: &mut LiltApp,
    ctx: &egui::Context,
    ui: &mut Ui,
    palette: &Palette,
    compact: bool,
) {
    let current = app.current_song().cloned();
    let width = ui.available_width();
    let left_w = if compact { 200.0 } else { 300.0 };
    let right_w = if width > 980.0 {
        235.0
    } else if width > 800.0 {
        150.0
    } else {
        86.0
    };
    let center_w = (width - left_w - right_w - 20.0).max(220.0);
    ui.allocate_ui_with_layout(
        Vec2::new(width, 80.0),
        Layout::left_to_right(Align::Center),
        |ui| {
            // Left: artwork + title
            let cover_size = 54.0;
            let texture = current
                .as_ref()
                .and_then(|song| app.load_cover_texture(ctx, song));
            let (cover_rect, cover_response) =
                ui.allocate_exact_size(Vec2::splat(cover_size), Sense::click());
            if let Some(texture) = &texture {
                ui.put(
                    cover_rect,
                    egui::Image::new(texture)
                        .fit_to_exact_size(cover_rect.size())
                        .corner_radius(7.0),
                );
            } else {
                widgets::draw_cover(
                    ui.painter(),
                    cover_rect,
                    current.as_ref().map(|s| s.album.as_str()).unwrap_or("Lilt"),
                    palette,
                );
            }
            if cover_response.clicked() {
                app.now_playing = true;
            }
            ui.add_space(10.0);
            let text_width = (left_w - 92.0).max(90.0);
            ui.allocate_ui_with_layout(
                Vec2::new(text_width, 54.0),
                Layout::top_down(Align::LEFT),
                |ui| {
                    ui.add_space(2.0);
                    let title = current
                        .as_ref()
                        .map(|s| s.display_title().to_owned())
                        .unwrap_or_else(|| app.t("Choose a song", "选择一首歌").to_owned());
                    let title_response = ui.add(
                        egui::Label::new(
                            RichText::new(title.clone())
                                .size(12.8)
                                .color(palette.text)
                                .strong(),
                        )
                        .truncate()
                        .sense(Sense::click()),
                    );
                    if title_response.clicked() {
                        app.now_playing = true;
                    }
                    let subtitle = current
                        .as_ref()
                        .map(|s| s.display_artist().to_owned())
                        .unwrap_or_else(|| app.t("Your local library", "你的本地曲库").to_owned());
                    ui.label(RichText::new(subtitle).size(10.5).color(palette.muted));
                },
            );
            if !compact && current.as_ref().map(|s| s.favorite).unwrap_or(false) {
                UIHeart(ui, palette, 14.0);
            }

            // Center: transport + seek
            ui.add_space(12.0);
            ui.allocate_ui_with_layout(
                Vec2::new(center_w, 76.0),
                Layout::top_down(Align::Center),
                |ui| {
                    ui.add_space(2.0);
                    ui.horizontal(|ui| {
                        let group = 230.0;
                        let space = ((ui.available_width() - group) / 2.0).max(0.0);
                        ui.add_space(space);
                        let shuffle_color = if app.shuffle {
                            palette.accent
                        } else {
                            palette.muted
                        };
                        if icons::icon_button(
                            ui,
                            Icon::Shuffle,
                            28.0,
                            shuffle_color,
                            palette.surface_hi,
                            app.t("Shuffle", "随机"),
                        )
                        .clicked()
                        {
                            app.toggle_shuffle();
                        }
                        if icons::icon_button(
                            ui,
                            Icon::Prev,
                            30.0,
                            palette.text,
                            palette.surface_hi,
                            app.t("Previous", "上一首"),
                        )
                        .clicked()
                        {
                            app.prev_track();
                        }
                        ui.add_space(2.0);
                        if widgets::play_button(ui, 40.0, app.playing, palette).clicked() {
                            app.toggle_play();
                        }
                        ui.add_space(2.0);
                        if icons::icon_button(
                            ui,
                            Icon::Next,
                            30.0,
                            palette.text,
                            palette.surface_hi,
                            app.t("Next", "下一首"),
                        )
                        .clicked()
                        {
                            app.next_track(false);
                        }
                        let repeat_icon = if app.repeat == RepeatMode::One {
                            Icon::RepeatOne
                        } else {
                            Icon::Repeat
                        };
                        let repeat_color = if app.repeat != RepeatMode::Off {
                            palette.accent
                        } else {
                            palette.muted
                        };
                        if icons::icon_button(
                            ui,
                            repeat_icon,
                            28.0,
                            repeat_color,
                            palette.surface_hi,
                            app.t("Repeat", "循环"),
                        )
                        .clicked()
                        {
                            app.cycle_repeat();
                        }
                    });
                    ui.add_space(2.0);
                    let duration = app.duration();
                    let position = app.position().min(duration);
                    let fraction = if duration > 0.0 {
                        position / duration
                    } else {
                        0.0
                    };
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new(format_duration(position))
                                .font(FontId::monospace(9.5))
                                .color(palette.faint),
                        );
                        let seek_width = (ui.available_width() - 76.0).max(80.0);
                        ui.allocate_ui_with_layout(
                            Vec2::new(seek_width, 16.0),
                            Layout::left_to_right(Align::Center),
                            |ui| {
                                let seek = widgets::seek_bar(ui, fraction, palette, 16.0);
                                if let Some(value) = seek.changed {
                                    app.seek_fraction(value);
                                }
                            },
                        );
                        ui.label(
                            RichText::new(format_duration(duration))
                                .font(FontId::monospace(9.5))
                                .color(palette.faint),
                        );
                    });
                },
            );

            // Right: volume, sleep, queue, expand
            ui.allocate_ui_with_layout(
                Vec2::new(right_w, 76.0),
                Layout::right_to_left(Align::Center),
                |ui| {
                    if icons::icon_button(
                        ui,
                        Icon::Expand,
                        28.0,
                        palette.muted,
                        palette.surface_hi,
                        app.t("Open player", "打开播放页"),
                    )
                    .clicked()
                    {
                        app.now_playing = true;
                    }
                    let queue_count = app.active_queue().map(|q| q.songs.len()).unwrap_or(0);
                    let queue_response = icons::icon_button(
                        ui,
                        Icon::Queue,
                        28.0,
                        if app.queue_drawer {
                            palette.accent
                        } else {
                            palette.muted
                        },
                        palette.surface_hi,
                        app.t("Queue", "队列"),
                    );
                    if queue_count > 0 {
                        let badge = Rect::from_center_size(
                            queue_response.rect.right_top() + Vec2::new(-3.0, 5.0),
                            Vec2::splat(14.0),
                        );
                        ui.painter()
                            .circle_filled(badge.center(), 7.0, palette.accent);
                        ui.painter().text(
                            badge.center(),
                            Align2::CENTER_CENTER,
                            queue_count.to_string(),
                            FontId::monospace(8.0),
                            palette.on_accent,
                        );
                    }
                    if queue_response.clicked() {
                        app.queue_drawer = !app.queue_drawer;
                    }
                    if let Some(remaining) = app.sleep_remaining() {
                        let minutes = (remaining + 59) / 60;
                        if icons::icon_button(
                            ui,
                            Icon::Clock,
                            28.0,
                            palette.accent,
                            palette.surface_hi,
                            app.t("Sleep timer", "睡眠定时"),
                        )
                        .clicked()
                        {
                            app.open_sleep_timer();
                        }
                        if !compact {
                            ui.label(
                                RichText::new(format!("{minutes}m"))
                                    .size(9.5)
                                    .color(palette.accent),
                            );
                        }
                    } else if icons::icon_button(
                        ui,
                        Icon::Clock,
                        28.0,
                        palette.muted,
                        palette.surface_hi,
                        app.t("Sleep timer", "睡眠定时"),
                    )
                    .clicked()
                    {
                        app.open_sleep_timer();
                    }
                    if width > 980.0 {
                        ui.add_space(4.0);
                        let mut volume = app.state.settings.volume;
                        let response = ui.allocate_ui_with_layout(
                            Vec2::new(128.0, 20.0),
                            Layout::left_to_right(Align::Center),
                            |ui| widgets::volume_slider(ui, volume, palette),
                        );
                        if let Some(value) = response.inner {
                            volume = value;
                            app.set_volume(volume);
                        }
                    }
                },
            );
        },
    );
}

fn UIHeart(ui: &mut Ui, palette: &Palette, size: f32) {
    let _ = ui;
    let _ = palette;
    let _ = size;
}

fn queue_drawer(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    ui.horizontal(|ui| {
        ui.label(
            RichText::new(app.t("Play queue", "播放队列"))
                .font(display_font(18.0))
                .color(palette.text),
        );
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if icons::icon_button(
                ui,
                Icon::Close,
                26.0,
                palette.muted,
                palette.surface_hi,
                app.t("Close", "关闭"),
            )
            .clicked()
            {
                app.queue_drawer = false;
            }
        });
    });
    let queue_info = app
        .active_queue()
        .map(|q| (q.id, q.name.clone(), q.index, q.songs.len()));
    let Some((queue_id, queue_name, current_index, total)) = queue_info else {
        widgets::empty_state(
            ui,
            Icon::Queue,
            app.t("No queue", "没有队列"),
            app.t("Play something to start one.", "播放歌曲即可创建队列。"),
            palette,
        );
        if crate::widgets::primary_button(
            ui,
            app.t("New queue", "新建队列"),
            Some(Icon::Plus),
            palette,
        )
        .clicked()
        {
            app.new_queue_dialog();
        }
        return;
    };
    ui.add_space(2.0);
    ui.label(
        RichText::new(format!(
            "{}  ·  {} / {}",
            queue_name,
            if total == 0 { 0 } else { current_index + 1 },
            total
        ))
        .size(10.5)
        .color(palette.muted),
    );
    ui.add_space(8.0);
    let keys = app
        .state
        .queue_by_id(queue_id)
        .map(|q| q.songs.clone())
        .unwrap_or_default();
    let scroll_height = (ui.available_height() - 90.0).max(120.0);
    egui::ScrollArea::vertical()
        .id_salt("queue_drawer_scroll")
        .max_height(scroll_height)
        .auto_shrink([false, false])
        .show(ui, |ui| {
            for (index, key) in keys.iter().enumerate() {
                let Some(song) = app.song_by_key(key).cloned() else {
                    continue;
                };
                let (rect, response) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), 50.0), Sense::click());
                let is_current = index == current_index;
                if is_current {
                    ui.painter()
                        .rect_filled(rect, 8.0, with_alpha(palette.accent, 14));
                } else if response.hovered() {
                    ui.painter().rect_filled(rect, 8.0, palette.surface_hi);
                }
                let art = Rect::from_center_size(
                    egui::pos2(rect.left() + 22.0, rect.center().y),
                    Vec2::splat(32.0),
                );
                widgets::draw_cover(ui.painter(), art, &song.album, palette);
                let clip = ui.painter().with_clip_rect(Rect::from_min_max(
                    egui::pos2(rect.left() + 44.0, rect.top() + 5.0),
                    egui::pos2(rect.right() - 32.0, rect.bottom() - 3.0),
                ));
                clip.text(
                    egui::pos2(rect.left() + 44.0, rect.top() + 19.0),
                    Align2::LEFT_CENTER,
                    song.display_title(),
                    FontId::proportional(11.5),
                    if is_current {
                        palette.accent
                    } else {
                        palette.text
                    },
                );
                clip.text(
                    egui::pos2(rect.left() + 44.0, rect.top() + 35.0),
                    Align2::LEFT_CENTER,
                    song.display_artist(),
                    FontId::proportional(9.5),
                    palette.muted,
                );
                if response.clicked() {
                    if let Some(pos) = response.interact_pointer_pos() {
                        if pos.x > rect.right() - 30.0 {
                            app.remove_from_queue(queue_id, key);
                        } else {
                            app.switch_queue(queue_id, false);
                            app.play_active_queue_at(index);
                        }
                    }
                }
                icons::paint(
                    ui.painter(),
                    Rect::from_center_size(
                        egui::pos2(rect.right() - 16.0, rect.center().y),
                        Vec2::splat(11.0),
                    ),
                    Icon::Close,
                    with_alpha(palette.muted, 110),
                    1.2,
                );
            }
        });
    ui.add_space(6.0);
    if crate::widgets::ghost_button(
        ui,
        app.t("All queues", "全部队列"),
        Some(Icon::Queue),
        palette,
    )
    .clicked()
    {
        app.queue_drawer = false;
        app.navigate(Page::Queues);
    }
    if crate::widgets::ghost_button(
        ui,
        app.t("New queue", "新建队列"),
        Some(Icon::Plus),
        palette,
    )
    .clicked()
    {
        app.new_queue_dialog();
    }
}

fn draw_toast(app: &mut LiltApp, ctx: &egui::Context, palette: &Palette) {
    let Some(toast) = &app.toast else { return };
    let text = toast.text.clone();
    egui::Area::new(egui::Id::new("toast"))
        .anchor(Align2::LEFT_BOTTOM, [18.0, -108.0])
        .order(egui::Order::Tooltip)
        .show(ctx, |ui| {
            egui::Frame::NONE
                .fill(palette.surface)
                .stroke(Stroke::new(1.0, palette.border_strong))
                .corner_radius(10.0)
                .inner_margin(Margin::symmetric(12, 8))
                .show(ui, |ui| {
                    ui.set_max_width(400.0);
                    ui.horizontal(|ui| {
                        icons::paint(
                            ui.painter(),
                            Rect::from_center_size(
                                ui.cursor().min + Vec2::new(7.0, 8.0),
                                Vec2::splat(13.0),
                            ),
                            Icon::Check,
                            palette.good,
                            1.5,
                        );
                        ui.add_space(20.0);
                        ui.label(RichText::new(text).size(11.5).color(palette.text));
                    });
                });
        });
}
