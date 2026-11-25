use crate::app::{AlbumEntry, LiltApp};
use crate::i18n::{tr, tracks};
use crate::icons::{self, Icon};
use crate::model::*;
use crate::theme::{display_font, mix, with_alpha, Palette};
use crate::widgets::{self, ghost_button, primary_button};
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Layout, Rect, RichText, Sense, Stroke, StrokeKind, Ui,
    Vec2,
};

pub fn page_header(
    app: &mut LiltApp,
    ui: &mut Ui,
    palette: &Palette,
    eyebrow: &str,
    title: &str,
    subtitle: &str,
    actions: impl FnOnce(&mut LiltApp, &mut Ui),
) {
    let _ = app;
    ui.horizontal(|ui| {
        ui.vertical(|ui| {
            ui.label(
                RichText::new(eyebrow.to_uppercase())
                    .font(FontId::proportional(9.5))
                    .color(palette.faint)
                    .strong(),
            );
            ui.add_space(1.0);
            ui.label(
                RichText::new(title)
                    .font(display_font(30.0))
                    .color(palette.text),
            );
            if !subtitle.is_empty() {
                ui.add_space(2.0);
                ui.label(
                    RichText::new(subtitle)
                        .font(FontId::proportional(12.5))
                        .color(palette.muted),
                );
            }
        });
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            actions(app, ui);
        });
    });
    ui.add_space(14.0);
}

pub fn breadcrumb(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let chain = app.breadcrumb();
    let mut target: Option<Option<std::path::PathBuf>> = None;
    ui.horizontal_wrapped(|ui| {
        icons::paint(
            ui.painter(),
            Rect::from_center_size(ui.cursor().min + Vec2::new(8.0, 8.0), Vec2::splat(14.0)),
            Icon::Home,
            palette.muted,
            1.4,
        );
        ui.add_space(18.0);
        for (index, (name, path)) in chain.iter().enumerate() {
            if index > 0 {
                ui.label(RichText::new("/").size(11.0).color(palette.faint));
            }
            let selected = index + 1 == chain.len();
            let text = RichText::new(name).size(12.0).color(if selected {
                palette.text
            } else {
                palette.muted
            });
            if ui
                .add(
                    egui::Button::new(text)
                        .frame(false)
                        .min_size(Vec2::new(0.0, 24.0)),
                )
                .clicked()
            {
                if index == 0 {
                    target = Some(None);
                } else {
                    target = Some(path.clone());
                }
            }
        }
    });
    if let Some(Some(path)) = target {
        app.open_folder(path);
    } else if let Some(None) = target {
        app.folder_path = None;
    }
    ui.add_space(6.0);
}

pub fn selection_bar(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    if app.selected.is_empty() {
        return;
    }
    let count = app.selected.len();
    let mut clear = false;
    egui::Frame::NONE
        .fill(palette.accent_dim)
        .corner_radius(10.0)
        .inner_margin(egui::Margin::symmetric(12, 7))
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(format!("{} {}", count, app.t("selected", "项已选择")))
                        .size(12.0)
                        .color(palette.text),
                );
                ui.add_space(6.0);
                if ghost_button(ui, app.t("Play", "播放"), Some(Icon::Play), palette).clicked() {
                    let keys = app.selection_or_current();
                    if let Some(first) = keys.first().cloned() {
                        let index = app.songs.iter().position(|s| s.key == first).unwrap_or(0);
                        let name = app.t("Selection", "所选歌曲").to_owned();
                        app.play_context(keys, index, &name);
                    }
                }
                if ghost_button(ui, app.t("Queue", "队列"), Some(Icon::Queue), palette).clicked()
                {
                    let keys = app.selection_or_current();
                    app.modal = Some(crate::app::Modal::AddToQueue {
                        keys,
                        queue_id: app.active_queue().map(|q| q.id),
                        new_name: String::new(),
                    });
                }
                if ghost_button(ui, app.t("Playlist", "播放列表"), Some(Icon::Plus), palette)
                    .clicked()
                {
                    let keys = app.selection_or_current();
                    app.modal = Some(crate::app::Modal::AddToPlaylist {
                        keys,
                        playlist_id: None,
                        new_name: String::new(),
                    });
                }
                if ghost_button(ui, app.t("Tags", "标签"), Some(Icon::Tag), palette).clicked() {
                    app.open_tag_editor();
                }
                if ghost_button(ui, app.t("Clear", "清除"), Some(Icon::Close), palette).clicked()
                {
                    clear = true;
                }
            });
        });
    if clear {
        app.selected.clear();
    }
    ui.add_space(8.0);
}

pub fn song_table(
    app: &mut LiltApp,
    ui: &mut Ui,
    palette: &Palette,
    indices: &[usize],
    context_name: &str,
    context_keys: &[String],
    show_album: bool,
    playlist_id: Option<u64>,
    queue_id: Option<u64>,
    allow_reorder: bool,
    show_plays: bool,
) {
    if indices.is_empty() {
        return;
    }
    let width = (ui.available_width() - 40.0).max(220.0);
    let title_x = 76.0;
    let album_w = if show_album && width > 650.0 {
        200.0
    } else {
        0.0
    };
    let more_w = 30.0;
    let duration_w = 54.0;
    let plays_w = if show_plays { 64.0 } else { 0.0 };
    let album_x = width - more_w - duration_w - album_w - plays_w - 12.0;
    let duration_x = width - more_w - duration_w - 4.0;
    let plays_x = width - more_w - duration_w - plays_w - 12.0;

    // Header
    let (header_rect, _) = ui.allocate_exact_size(Vec2::new(width, 24.0), Sense::hover());
    let painter = ui.painter();
    painter.text(
        header_rect.left_center() + Vec2::new(title_x, 0.0),
        Align2::LEFT_CENTER,
        app.t("TITLE", "标题"),
        FontId::proportional(9.5),
        palette.faint,
    );
    if show_album && width > 650.0 {
        painter.text(
            egui::pos2(header_rect.left() + album_x, header_rect.center().y),
            Align2::LEFT_CENTER,
            app.t("ALBUM", "专辑"),
            FontId::proportional(9.5),
            palette.faint,
        );
    }
    if show_plays {
        painter.text(
            egui::pos2(header_rect.left() + plays_x, header_rect.center().y),
            Align2::RIGHT_CENTER,
            app.t("PLAYS", "播放"),
            FontId::proportional(9.5),
            palette.faint,
        );
    }
    painter.text(
        egui::pos2(
            header_rect.left() + duration_x + duration_w - 10.0,
            header_rect.center().y,
        ),
        Align2::RIGHT_CENTER,
        app.t("TIME", "时长"),
        FontId::proportional(9.5),
        palette.faint,
    );
    widgets::divider(ui, palette);

    for (position, song_index) in indices.iter().enumerate() {
        let Some(song) = app.songs.get(*song_index).cloned() else {
            continue;
        };
        let is_current = app.current_key.as_deref() == Some(song.key.as_str());
        let selected = app.selected.contains(&song.key);
        let (rect, response) = ui.allocate_exact_size(Vec2::new(width, 48.0), Sense::click());
        let hovered = response.hovered();
        if is_current && app.scroll_to_current {
            ui.scroll_to_rect(rect, Some(Align::Center));
            app.scroll_to_current = false;
        }
        if selected {
            ui.painter()
                .rect_filled(rect, 7.0, with_alpha(palette.accent, 18));
        } else if is_current {
            ui.painter()
                .rect_filled(rect, 7.0, with_alpha(palette.accent, 11));
        } else if hovered {
            ui.painter().rect_filled(rect, 7.0, palette.surface_hi);
        }

        let check_rect = Rect::from_center_size(
            egui::pos2(rect.left() + 16.0, rect.center().y),
            Vec2::splat(15.0),
        );
        if selected {
            ui.painter()
                .circle_filled(check_rect.center(), 7.0, palette.accent);
            icons::paint(
                ui.painter(),
                check_rect.shrink(3.5),
                Icon::Check,
                palette.on_accent,
                1.6,
            );
        } else {
            ui.painter().circle_stroke(
                check_rect.center(),
                6.5,
                Stroke::new(1.0, with_alpha(palette.text, 40)),
            );
        }

        let cover_rect = Rect::from_center_size(
            egui::pos2(rect.left() + 54.0, rect.center().y),
            Vec2::splat(34.0),
        );
        if is_current {
            ui.painter()
                .rect_filled(cover_rect.expand(2.0), 6.0, palette.accent);
        }
        widgets::draw_cover(ui.painter(), cover_rect, &song.album, palette);
        if is_current {
            let bars = Rect::from_min_size(
                egui::pos2(cover_rect.left() + 4.0, cover_rect.bottom() - 10.0),
                Vec2::new(14.0, 8.0),
            );
            for i in 0..3 {
                let h = if app.playing {
                    4.0 + i as f32 * 1.7
                } else {
                    3.0 + i as f32
                };
                ui.painter().rect_filled(
                    Rect::from_min_size(
                        egui::pos2(bars.left() + i as f32 * 4.0, bars.bottom() - h),
                        Vec2::new(2.0, h),
                    ),
                    1.0,
                    palette.on_accent,
                );
            }
        }

        let title_rect = Rect::from_min_max(
            egui::pos2(rect.left() + title_x, rect.top() + 7.0),
            egui::pos2(rect.left() + album_x - 8.0, rect.bottom() - 5.0),
        );
        let clip = ui.painter().with_clip_rect(title_rect);
        clip.text(
            egui::pos2(title_rect.left(), title_rect.top() + 8.0),
            Align2::LEFT_CENTER,
            song.display_title(),
            FontId::proportional(13.0),
            if is_current {
                palette.accent
            } else {
                palette.text
            },
        );
        clip.text(
            egui::pos2(title_rect.left(), title_rect.bottom() - 8.0),
            Align2::LEFT_CENTER,
            format!(
                "{}{}{}",
                song.display_artist(),
                if song.year.is_empty() { "" } else { "  ·  " },
                song.year
            ),
            FontId::proportional(11.0),
            palette.muted,
        );
        if song.favorite {
            icons::paint(
                ui.painter(),
                Rect::from_center_size(
                    egui::pos2(rect.left() + title_x - 12.0, rect.center().y),
                    Vec2::splat(10.0),
                ),
                Icon::Heart,
                palette.accent,
                1.2,
            );
        }

        if show_album && width > 650.0 {
            let album_rect = Rect::from_min_max(
                egui::pos2(rect.left() + album_x, rect.top() + 6.0),
                egui::pos2(rect.left() + album_x + album_w - 12.0, rect.bottom() - 4.0),
            );
            let clip = ui.painter().with_clip_rect(album_rect);
            clip.text(
                egui::pos2(album_rect.left(), album_rect.center().y),
                Align2::LEFT_CENTER,
                song.display_album(),
                FontId::proportional(11.5),
                palette.muted,
            );
        }
        if show_plays {
            ui.painter().text(
                egui::pos2(rect.left() + plays_x, rect.center().y),
                Align2::RIGHT_CENTER,
                song.play_count.to_string(),
                FontId::monospace(11.0),
                palette.muted,
            );
        }
        ui.painter().text(
            egui::pos2(
                rect.left() + duration_x + duration_w - 10.0,
                rect.center().y,
            ),
            Align2::RIGHT_CENTER,
            format_duration(song.duration),
            FontId::monospace(11.0),
            palette.faint,
        );

        // More menu
        let more_rect = Rect::from_center_size(
            egui::pos2(rect.right() - 17.0, rect.center().y),
            Vec2::splat(26.0),
        );
        let mut menu_action: Option<MenuAction> = None;
        ui.allocate_ui_at_rect(more_rect, |ui| {
            ui.with_layout(
                Layout::centered_and_justified(egui::Direction::LeftToRight),
                |ui| {
                    ui.menu_button(
                        RichText::new("···").size(14.0).color(palette.muted),
                        |ui| {
                            ui.set_min_width(190.0);
                            if ui.button(app.t("Play now", "立即播放")).clicked() {
                                let index = context_keys
                                    .iter()
                                    .position(|k| k == &song.key)
                                    .unwrap_or(position);
                                app.play_context(context_keys.to_vec(), index, context_name);
                                ui.close_menu();
                            }
                            if ui.button(app.t("Add to queue", "加入队列")).clicked() {
                                menu_action = Some(MenuAction::Queue(song.key.clone()));
                                ui.close_menu();
                            }
                            if ui
                                .button(app.t("Add to playlist", "加入播放列表"))
                                .clicked()
                            {
                                menu_action = Some(MenuAction::Playlist(song.key.clone()));
                                ui.close_menu();
                            }
                            if ui.button(app.t("Edit tags", "编辑标签")).clicked() {
                                menu_action = Some(MenuAction::Tags(song.key.clone()));
                                ui.close_menu();
                            }
                            if ui.button(app.t("Add bookmark", "添加书签")).clicked() {
                                menu_action = Some(MenuAction::Bookmark(song.key.clone()));
                                ui.close_menu();
                            }
                            if ui
                                .button(if song.favorite {
                                    app.t("Remove favorite", "取消收藏")
                                } else {
                                    app.t("Favorite", "收藏")
                                })
                                .clicked()
                            {
                                menu_action = Some(MenuAction::Favorite(song.key.clone()));
                                ui.close_menu();
                            }
                            if ui.button(app.t("Go to album", "查看专辑")).clicked() {
                                menu_action = Some(MenuAction::Album(song.album.clone()));
                                ui.close_menu();
                            }
                            if ui.button(app.t("Go to folder", "查看文件夹")).clicked() {
                                menu_action = Some(MenuAction::Folder(song.folder().to_path_buf()));
                                ui.close_menu();
                            }
                            if let Some(id) = playlist_id {
                                if ui
                                    .button(app.t("Remove from playlist", "从播放列表移除"))
                                    .clicked()
                                {
                                    menu_action =
                                        Some(MenuAction::RemovePlaylist(id, song.key.clone()));
                                    ui.close_menu();
                                }
                            }
                            if let Some(id) = queue_id {
                                if ui
                                    .button(app.t("Remove from queue", "从队列移除"))
                                    .clicked()
                                {
                                    menu_action =
                                        Some(MenuAction::RemoveQueue(id, song.key.clone()));
                                    ui.close_menu();
                                }
                            }
                            if allow_reorder {
                                if ui.button(app.t("Move up", "上移")).clicked() {
                                    menu_action = Some(MenuAction::MoveUp(position));
                                    ui.close_menu();
                                }
                                if ui.button(app.t("Move down", "下移")).clicked() {
                                    menu_action = Some(MenuAction::MoveDown(position));
                                    ui.close_menu();
                                }
                            }
                        },
                    );
                },
            );
        });

        if let Some(action) = menu_action {
            match action {
                MenuAction::Queue(key) => {
                    app.modal = Some(crate::app::Modal::AddToQueue {
                        keys: vec![key],
                        queue_id: app.active_queue().map(|q| q.id),
                        new_name: String::new(),
                    });
                }
                MenuAction::Playlist(key) => {
                    app.modal = Some(crate::app::Modal::AddToPlaylist {
                        keys: vec![key],
                        playlist_id: None,
                        new_name: String::new(),
                    });
                }
                MenuAction::Tags(key) => {
                    app.selected.clear();
                    app.selected.insert(key);
                    app.open_tag_editor();
                }
                MenuAction::Bookmark(key) => {
                    app.modal = Some(crate::app::Modal::Bookmark {
                        key,
                        note: String::new(),
                    });
                }
                MenuAction::Favorite(key) => app.toggle_favorite(&key),
                MenuAction::Album(name) => {
                    app.page = Page::Albums;
                    app.open_album(name);
                }
                MenuAction::Folder(path) => app.open_folder(path),
                MenuAction::RemovePlaylist(id, key) => app.remove_from_playlist(id, &key),
                MenuAction::RemoveQueue(id, key) => app.remove_from_queue(id, &key),
                MenuAction::MoveUp(position) => {
                    if let Some(id) = playlist_id {
                        if position > 0 {
                            app.move_in_playlist(id, position, position - 1);
                        }
                    }
                    if let Some(id) = queue_id {
                        if position > 0 {
                            app.move_in_queue(id, position, position - 1);
                        }
                    }
                }
                MenuAction::MoveDown(position) => {
                    if let Some(id) = playlist_id {
                        app.move_in_playlist(id, position, position + 1);
                    }
                    if let Some(id) = queue_id {
                        app.move_in_queue(id, position, position + 1);
                    }
                }
            }
        }

        if response.clicked() {
            if let Some(pos) = response.interact_pointer_pos() {
                if app.selection_mode || pos.x <= rect.left() + 34.0 {
                    app.toggle_selected(&song.key);
                } else if pos.x > rect.right() - 36.0 {
                    // More button is handled above.
                } else {
                    let index = context_keys
                        .iter()
                        .position(|k| k == &song.key)
                        .unwrap_or(position);
                    app.play_context(context_keys.to_vec(), index, context_name);
                }
            }
        }
        if app.jump_letter.is_some() {
            let initial = chars_for_index(song.display_title());
            if app.jump_letter == Some(initial) {
                ui.scroll_to_rect(rect, Some(Align::TOP));
                app.jump_letter = None;
            }
        }
    }
}

enum MenuAction {
    Queue(String),
    Playlist(String),
    Tags(String),
    Bookmark(String),
    Favorite(String),
    Album(String),
    Folder(std::path::PathBuf),
    RemovePlaylist(u64, String),
    RemoveQueue(u64, String),
    MoveUp(usize),
    MoveDown(usize),
}

pub fn album_grid(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, indices: &[usize]) {
    if indices.is_empty() {
        return;
    }
    let albums: Vec<AlbumEntry> = indices
        .iter()
        .filter_map(|i| app.albums.get(*i).cloned())
        .collect();
    let available = ui.available_width();
    let columns = if available > 840.0 {
        4
    } else if available > 600.0 {
        3
    } else {
        2
    };
    let gap = 14.0;
    let card_w = ((available - gap * (columns - 1) as f32) / columns as f32).max(110.0);
    let mut index = 0;
    while index < albums.len() {
        ui.horizontal(|ui| {
            for _ in 0..columns {
                if index >= albums.len() {
                    break;
                }
                let album = &albums[index];
                let card_h = card_w + 48.0;
                let (rect, response) =
                    ui.allocate_exact_size(Vec2::new(card_w, card_h), Sense::click());
                if response.hovered() {
                    ui.painter().rect_filled(
                        rect.expand(3.0),
                        12.0,
                        with_alpha(palette.accent, 12),
                    );
                }
                let art = Rect::from_min_size(rect.min, Vec2::splat(card_w));
                widgets::draw_cover(ui.painter(), art, &album.name, palette);
                let clip = ui.painter().with_clip_rect(Rect::from_min_size(
                    egui::pos2(rect.left(), rect.top() + card_w + 2.0),
                    Vec2::new(card_w, 44.0),
                ));
                clip.text(
                    egui::pos2(rect.left(), rect.top() + card_w + 15.0),
                    Align2::LEFT_CENTER,
                    &album.name,
                    FontId::proportional(12.5),
                    palette.text,
                );
                clip.text(
                    egui::pos2(rect.left(), rect.top() + card_w + 32.0),
                    Align2::LEFT_CENTER,
                    format!(
                        "{}  ·  {}",
                        album.artist,
                        tracks(app.lang(), album.songs.len())
                    ),
                    FontId::proportional(10.5),
                    palette.muted,
                );
                if response.clicked() {
                    app.page = Page::Albums;
                    app.open_album(album.name.clone());
                }
                ui.add_space(gap);
                index += 1;
            }
        });
        ui.add_space(gap);
    }
}

pub fn album_detail(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, name: &str) {
    let Some(album) = app
        .albums
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case(name))
        .cloned()
    else {
        widgets::empty_state(ui, Icon::Disc, "Album not found", "", palette);
        return;
    };
    let keys: Vec<String> = album
        .songs
        .iter()
        .filter_map(|i| app.songs.get(*i).map(|s| s.key.clone()))
        .collect();
    let mut back = false;
    ui.horizontal(|ui| {
        if icons::icon_button(
            ui,
            Icon::ChevronLeft,
            30.0,
            palette.muted,
            palette.surface_hi,
            app.t("Back", "返回"),
        )
        .clicked()
        {
            back = true;
        }
        ui.add_space(4.0);
        ui.label(
            RichText::new(app.t("Albums", "专辑"))
                .size(11.0)
                .color(palette.faint),
        );
    });
    if back {
        app.detail = None;
        app.page = Page::Albums;
        return;
    }
    let size = (ui.available_width() * 0.24).clamp(150.0, 220.0);
    ui.horizontal(|ui| {
        widgets::draw_cover(
            ui.painter(),
            Rect::from_min_size(ui.cursor().min, Vec2::splat(size)),
            &album.name,
            palette,
        );
        ui.allocate_space(Vec2::splat(size));
        ui.add_space(22.0);
        ui.vertical(|ui| {
            ui.label(
                RichText::new(app.t("ALBUM", "专辑"))
                    .size(9.5)
                    .color(palette.faint)
                    .strong(),
            );
            ui.add_space(2.0);
            ui.label(
                RichText::new(&album.name)
                    .font(display_font(32.0))
                    .color(palette.text),
            );
            ui.add_space(4.0);
            ui.label(
                RichText::new(format!(
                    "{}  ·  {}  ·  {}",
                    album.artist,
                    tracks(app.lang(), album.songs.len()),
                    crate::i18n::total_duration(app.lang(), album.duration)
                ))
                .size(12.0)
                .color(palette.muted),
            );
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if primary_button(
                    ui,
                    app.t("Play album", "播放专辑"),
                    Some(Icon::Play),
                    palette,
                )
                .clicked()
                {
                    let name = album.name.clone();
                    app.play_context(keys.clone(), 0, &name);
                }
                if ghost_button(ui, app.t("Shuffle", "随机"), Some(Icon::Shuffle), palette)
                    .clicked()
                {
                    app.enable_shuffle();
                    let name = album.name.clone();
                    app.play_context(keys.clone(), 0, &name);
                }
                if ghost_button(ui, app.t("Queue", "队列"), Some(Icon::Queue), palette).clicked()
                {
                    app.modal = Some(crate::app::Modal::AddToQueue {
                        keys: keys.clone(),
                        queue_id: app.active_queue().map(|q| q.id),
                        new_name: String::new(),
                    });
                }
            });
        });
    });
    ui.add_space(18.0);
    if keys.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Music,
            app.t("No tracks here", "这里没有歌曲"),
            app.t("Add a folder in Settings.", "请在设置中添加文件夹。"),
            palette,
        );
    } else {
        song_table(
            app,
            ui,
            palette,
            &album.songs,
            &album.name,
            &keys,
            false,
            None,
            None,
            false,
            false,
        );
    }
}

pub fn artist_list(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let artists = app.artists.clone();
    if artists.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Music,
            app.t("No artists yet", "还没有艺术家"),
            app.t("Your artists appear after a scan.", "扫描后会显示艺术家。"),
            palette,
        );
        return;
    }
    for (index, artist) in artists.iter().enumerate() {
        let (rect, response) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 62.0), Sense::click());
        if response.hovered() {
            ui.painter().rect_filled(rect, 9.0, palette.surface_hi);
        }
        let avatar = Rect::from_center_size(
            egui::pos2(rect.left() + 28.0, rect.center().y),
            Vec2::splat(40.0),
        );
        ui.painter()
            .circle_filled(avatar.center(), 20.0, with_alpha(palette.accent, 26));
        ui.painter().text(
            avatar.center(),
            Align2::CENTER_CENTER,
            initials(&artist.name),
            FontId::proportional(14.0),
            palette.accent,
        );
        let clip = ui.painter().with_clip_rect(Rect::from_min_max(
            egui::pos2(rect.left() + 58.0, rect.top() + 8.0),
            egui::pos2(rect.right() - 40.0, rect.bottom() - 4.0),
        ));
        clip.text(
            egui::pos2(rect.left() + 58.0, rect.top() + 22.0),
            Align2::LEFT_CENTER,
            &artist.name,
            FontId::proportional(14.0),
            palette.text,
        );
        clip.text(
            egui::pos2(rect.left() + 58.0, rect.top() + 41.0),
            Align2::LEFT_CENTER,
            format!(
                "{}  ·  {}",
                crate::i18n::albums(app.lang(), artist.album_count),
                tracks(app.lang(), artist.songs.len())
            ),
            FontId::proportional(10.5),
            palette.muted,
        );
        icons::paint(
            ui.painter(),
            Rect::from_center_size(
                egui::pos2(rect.right() - 20.0, rect.center().y),
                Vec2::splat(13.0),
            ),
            Icon::ChevronRight,
            palette.faint,
            1.4,
        );
        if response.clicked() {
            let name = artist.name.clone();
            app.page = Page::Artists;
            app.open_artist(name);
            let _ = index;
        }
        widgets::divider(ui, palette);
    }
}

pub fn artist_detail(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, name: &str) {
    let Some(artist) = app
        .artists
        .iter()
        .find(|a| a.name.eq_ignore_ascii_case(name))
        .cloned()
    else {
        widgets::empty_state(ui, Icon::Music, "Artist not found", "", palette);
        return;
    };
    let keys: Vec<String> = artist
        .songs
        .iter()
        .filter_map(|i| app.songs.get(*i).map(|s| s.key.clone()))
        .collect();
    let mut back = false;
    ui.horizontal(|ui| {
        if icons::icon_button(
            ui,
            Icon::ChevronLeft,
            30.0,
            palette.muted,
            palette.surface_hi,
            app.t("Back", "返回"),
        )
        .clicked()
        {
            back = true;
        }
        ui.label(
            RichText::new(app.t("Artists", "艺术家"))
                .size(11.0)
                .color(palette.faint),
        );
    });
    if back {
        app.detail = None;
        app.page = Page::Artists;
        return;
    }
    ui.add_space(6.0);
    ui.horizontal(|ui| {
        let (rect, _) = ui.allocate_exact_size(Vec2::splat(96.0), Sense::hover());
        ui.painter()
            .circle_filled(rect.center(), 48.0, with_alpha(palette.accent, 24));
        ui.painter().text(
            rect.center(),
            Align2::CENTER_CENTER,
            initials(&artist.name),
            display_font(32.0),
            palette.accent,
        );
        ui.add_space(20.0);
        ui.vertical(|ui| {
            ui.label(
                RichText::new(app.t("ARTIST", "艺术家"))
                    .size(9.5)
                    .color(palette.faint),
            );
            ui.label(
                RichText::new(&artist.name)
                    .font(display_font(30.0))
                    .color(palette.text),
            );
            ui.label(
                RichText::new(format!(
                    "{}  ·  {}",
                    crate::i18n::albums(app.lang(), artist.album_count),
                    tracks(app.lang(), artist.songs.len())
                ))
                .size(12.0)
                .color(palette.muted),
            );
            ui.add_space(8.0);
            if primary_button(
                ui,
                app.t("Play artist", "播放艺术家"),
                Some(Icon::Play),
                palette,
            )
            .clicked()
            {
                let name = artist.name.clone();
                app.play_context(keys.clone(), 0, &name);
            }
        });
    });
    ui.add_space(16.0);
    let album_names: Vec<String> = {
        let mut names: Vec<String> = Vec::new();
        for index in &artist.songs {
            if let Some(song) = app.songs.get(*index) {
                if !names.contains(&song.album) {
                    names.push(song.album.clone());
                }
            }
        }
        names
    };
    for album_name in album_names {
        ui.label(
            RichText::new(album_name.clone())
                .font(display_font(16.0))
                .color(palette.text),
        );
        let album_indices: Vec<usize> = app
            .albums
            .iter()
            .position(|a| a.name == album_name)
            .map(|i| vec![i])
            .unwrap_or_default();
        album_grid(app, ui, palette, &album_indices);
    }
    song_table(
        app,
        ui,
        palette,
        &artist.songs,
        &artist.name,
        &keys,
        false,
        None,
        None,
        false,
        false,
    );
}

pub fn playlist_grid(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let playlists = app.state.playlists.clone();
    if playlists.is_empty() {
        widgets::empty_state(
            ui,
            Icon::List,
            app.t("No playlists yet", "还没有播放列表"),
            app.t(
                "Make one for a mood, a trip, or a favourite set.",
                "按心情或场景创建一个播放列表。",
            ),
            palette,
        );
        return;
    }
    let available = ui.available_width();
    let columns = if available > 840.0 {
        3
    } else if available > 560.0 {
        2
    } else {
        1
    };
    let gap = 14.0;
    let card_w = ((available - gap * (columns - 1) as f32) / columns as f32).max(200.0);
    let mut index = 0;
    while index < playlists.len() {
        ui.horizontal(|ui| {
            for _ in 0..columns {
                if index >= playlists.len() {
                    break;
                }
                let playlist = &playlists[index];
                let (rect, response) =
                    ui.allocate_exact_size(Vec2::new(card_w, 84.0), Sense::click());
                ui.painter().rect_filled(rect, 11.0, palette.surface);
                ui.painter().rect_stroke(
                    rect,
                    11.0,
                    Stroke::new(
                        1.0,
                        if response.hovered() {
                            palette.border_strong
                        } else {
                            palette.border
                        },
                    ),
                    StrokeKind::Inside,
                );
                if response.hovered() {
                    ui.painter()
                        .rect_filled(rect, 11.0, with_alpha(palette.accent, 8));
                }
                let art = Rect::from_min_size(
                    egui::pos2(rect.left() + 10.0, rect.center().y - 28.0),
                    Vec2::splat(56.0),
                );
                let seeds: Vec<String> = playlist
                    .songs
                    .iter()
                    .take(4)
                    .filter_map(|key| app.song_by_key(key).map(|s| s.album.clone()))
                    .collect();
                if seeds.is_empty() {
                    widgets::draw_cover(ui.painter(), art, &playlist.name, palette);
                } else {
                    for (i, seed) in seeds.iter().enumerate() {
                        let size = 27.0;
                        let sub = Rect::from_min_size(
                            egui::pos2(
                                art.left() + if i % 2 == 0 { 0.0 } else { 28.0 },
                                art.top() + if i < 2 { 0.0 } else { 28.0 },
                            ),
                            Vec2::splat(size),
                        );
                        widgets::draw_cover(ui.painter(), sub, seed, palette);
                    }
                }
                let clip = ui.painter().with_clip_rect(Rect::from_min_max(
                    egui::pos2(rect.left() + 78.0, rect.top() + 12.0),
                    egui::pos2(rect.right() - 16.0, rect.bottom() - 8.0),
                ));
                clip.text(
                    egui::pos2(rect.left() + 78.0, rect.top() + 28.0),
                    Align2::LEFT_CENTER,
                    &playlist.name,
                    FontId::proportional(13.5),
                    palette.text,
                );
                clip.text(
                    egui::pos2(rect.left() + 78.0, rect.top() + 48.0),
                    Align2::LEFT_CENTER,
                    tracks(app.lang(), playlist.songs.len()),
                    FontId::proportional(10.5),
                    palette.muted,
                );
                if response.clicked() {
                    app.open_playlist(playlist.id);
                    app.page = Page::Playlists;
                }
                ui.add_space(gap);
                index += 1;
            }
        });
        ui.add_space(gap);
    }
}

pub fn folder_grid(
    app: &mut LiltApp,
    ui: &mut Ui,
    palette: &Palette,
    paths: &[std::path::PathBuf],
) {
    if paths.is_empty() {
        return;
    }
    let available = ui.available_width();
    let columns = if available > 900.0 {
        3
    } else if available > 560.0 {
        2
    } else {
        1
    };
    let gap = 12.0;
    let card_w = ((available - gap * (columns - 1) as f32) / columns as f32).max(210.0);
    let mut index = 0;
    while index < paths.len() {
        ui.horizontal(|ui| {
            for _ in 0..columns {
                if index >= paths.len() {
                    break;
                }
                let path = &paths[index];
                let info = app.folders.get(path).cloned();
                let name = path
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_else(|| path.to_string_lossy().to_string());
                let count = info.as_ref().map(|i| i.total_songs).unwrap_or(0);
                let duration = info.as_ref().map(|i| i.total_duration).unwrap_or(0.0);
                let (rect, response) =
                    ui.allocate_exact_size(Vec2::new(card_w, 78.0), Sense::click());
                let hovered = response.hovered();
                ui.painter().rect_filled(
                    rect,
                    12.0,
                    if hovered {
                        palette.surface_hi
                    } else {
                        palette.surface
                    },
                );
                ui.painter().rect_stroke(
                    rect,
                    12.0,
                    Stroke::new(
                        1.0,
                        if hovered {
                            palette.border_strong
                        } else {
                            palette.border
                        },
                    ),
                    StrokeKind::Inside,
                );
                let art = Rect::from_min_size(
                    egui::pos2(rect.left() + 12.0, rect.center().y - 24.0),
                    Vec2::splat(48.0),
                );
                widgets::draw_cover(ui.painter(), art, &name, palette);
                icons::paint(
                    ui.painter(),
                    Rect::from_center_size(art.center(), Vec2::splat(19.0)),
                    Icon::Folder,
                    with_alpha(palette.text, 190),
                    1.5,
                );
                let clip = ui.painter().with_clip_rect(Rect::from_min_max(
                    egui::pos2(rect.left() + 72.0, rect.top() + 8.0),
                    egui::pos2(rect.right() - 28.0, rect.bottom() - 6.0),
                ));
                clip.text(
                    egui::pos2(rect.left() + 72.0, rect.top() + 26.0),
                    Align2::LEFT_CENTER,
                    &name,
                    FontId::proportional(13.5),
                    palette.text,
                );
                clip.text(
                    egui::pos2(rect.left() + 72.0, rect.top() + 45.0),
                    Align2::LEFT_CENTER,
                    format!(
                        "{}  ·  {}",
                        tracks(app.lang(), count),
                        crate::i18n::total_duration(app.lang(), duration)
                    ),
                    FontId::proportional(10.5),
                    palette.muted,
                );
                icons::paint(
                    ui.painter(),
                    Rect::from_center_size(
                        egui::pos2(rect.right() - 20.0, rect.center().y),
                        Vec2::splat(13.0),
                    ),
                    Icon::ChevronRight,
                    palette.faint,
                    1.4,
                );
                if response.clicked() {
                    app.open_folder(path.clone());
                }
                ui.add_space(gap);
                index += 1;
            }
        });
        ui.add_space(gap);
    }
}

pub fn segmented(ui: &mut Ui, palette: &Palette, labels: &[(&str, bool)]) -> Option<usize> {
    let mut clicked = None;
    egui::Frame::NONE
        .fill(palette.surface_hi)
        .corner_radius(10.0)
        .inner_margin(egui::Margin::symmetric(4, 3))
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                for (index, (label, selected)) in labels.iter().enumerate() {
                    let text = RichText::new(*label).size(12.0).color(if *selected {
                        palette.text
                    } else {
                        palette.muted
                    });
                    let response = ui.add(
                        egui::Button::new(text)
                            .fill(if *selected {
                                palette.surface
                            } else {
                                Color32::TRANSPARENT
                            })
                            .stroke(if *selected {
                                Stroke::new(1.0, palette.border)
                            } else {
                                Stroke::NONE
                            })
                            .corner_radius(8.0)
                            .min_size(Vec2::new(0.0, 26.0)),
                    );
                    if response.clicked() {
                        clicked = Some(index);
                    }
                }
            });
        });
    clicked
}

pub fn setting_row(
    ui: &mut Ui,
    palette: &Palette,
    title: &str,
    subtitle: &str,
    control: impl FnOnce(&mut Ui),
) {
    ui.horizontal(|ui| {
        let control_width = 260.0;
        let text_width = (ui.available_width() - control_width).max(150.0);
        ui.allocate_ui_with_layout(
            Vec2::new(text_width, 46.0),
            Layout::top_down(Align::LEFT),
            |ui| {
                ui.add_space(5.0);
                ui.label(RichText::new(title).size(12.8).color(palette.text).strong());
                if !subtitle.is_empty() {
                    ui.add_space(1.0);
                    ui.label(RichText::new(subtitle).size(10.5).color(palette.muted));
                }
            },
        );
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            control(ui);
        });
    });
    ui.add_space(3.0);
    widgets::divider(ui, palette);
    ui.add_space(3.0);
}

pub fn card_frame(palette: &Palette) -> egui::Frame {
    egui::Frame::NONE
        .fill(palette.surface)
        .stroke(Stroke::new(1.0, palette.border))
        .corner_radius(14.0)
        .inner_margin(egui::Margin::symmetric(16, 14))
}

pub fn section_title(ui: &mut Ui, palette: &Palette, title: &str, subtitle: &str) {
    ui.label(
        RichText::new(title)
            .font(display_font(17.0))
            .color(palette.text),
    );
    if !subtitle.is_empty() {
        ui.label(RichText::new(subtitle).size(11.0).color(palette.muted));
    }
}

pub fn nav_page(page: Page) -> Page {
    match page {
        Page::Folders => Page::Folders,
        Page::Playlists => Page::Playlists,
        Page::Songs => Page::Songs,
        Page::Albums => Page::Albums,
        Page::Artists => Page::Artists,
        Page::Queues => Page::Queues,
        Page::MostPlayed => Page::MostPlayed,
        Page::Recent => Page::Recent,
        Page::History => Page::History,
        Page::Bookmarks => Page::Bookmarks,
        Page::Equalizer => Page::Equalizer,
        Page::Settings => Page::Settings,
    }
}

pub fn detail_parent(detail: &DetailPage) -> Page {
    match detail {
        DetailPage::Album(_) => Page::Albums,
        DetailPage::Artist(_) => Page::Artists,
        DetailPage::Playlist(_) => Page::Playlists,
        DetailPage::Queue(_) => Page::Queues,
    }
}

pub fn title_for_page(app: &LiltApp, page: Page) -> String {
    match page {
        Page::Folders => app.t("Folders", "文件夹").to_owned(),
        Page::Playlists => app.t("Playlists", "播放列表").to_owned(),
        Page::Songs => app.t("Songs", "歌曲").to_owned(),
        Page::Albums => app.t("Albums", "专辑").to_owned(),
        Page::Artists => app.t("Artists", "艺术家").to_owned(),
        Page::Queues => app.t("Queues", "队列").to_owned(),
        Page::MostPlayed => app.t("Most played", "最多播放").to_owned(),
        Page::Recent => app.t("Recently added", "最近添加").to_owned(),
        Page::History => app.t("History", "播放历史").to_owned(),
        Page::Bookmarks => app.t("Bookmarks & notes", "书签与笔记").to_owned(),
        Page::Equalizer => app.t("Equalizer", "均衡器").to_owned(),
        Page::Settings => app.t("Settings", "设置").to_owned(),
    }
}

pub fn tag_line(song: &Song, lang: Language) -> String {
    let album = song.display_album();
    let genre = if song.genre.is_empty() {
        tr(lang, "", "")
    } else {
        &song.genre
    };
    if genre.is_empty() {
        album.to_owned()
    } else {
        format!("{album}  ·  {genre}")
    }
}

pub fn time_align(ui: &Ui, palette: &Palette, text: &str) {
    let _ = ui;
    let _ = palette;
    let _ = text;
}

pub fn mix_accent(palette: &Palette) -> Color32 {
    mix(palette.accent, palette.text, 0.30)
}
