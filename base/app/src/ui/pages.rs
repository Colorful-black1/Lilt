use crate::app::{filter_and_sort, LiltApp, Modal};
use crate::audio::AudioEngine;
use crate::i18n::{albums, songs_lower, tracks};
use crate::icons::{self, Icon};
use crate::model::*;
use crate::theme::{display_font, with_alpha, Palette};
use crate::ui::common::{self, card_frame, page_header, setting_row};
use crate::widgets;
use eframe::egui::{
    self, Align, Align2, Color32, FontId, Layout, Rect, RichText, Sense, Stroke, Ui, Vec2,
};

pub fn render_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, compact: bool) {
    if !app.search.trim().is_empty() {
        search_page(app, ui, palette);
        return;
    }
    if let Some(detail) = app.detail.clone() {
        match detail {
            DetailPage::Album(name) => common::album_detail(app, ui, palette, &name),
            DetailPage::Artist(name) => common::artist_detail(app, ui, palette, &name),
            DetailPage::Playlist(id) => playlist_detail(app, ui, palette, id),
            DetailPage::Queue(id) => queue_detail(app, ui, palette, id),
        }
        return;
    }
    match app.page {
        Page::Folders => folders_page(app, ui, palette),
        Page::Playlists => playlists_page(app, ui, palette),
        Page::Songs => songs_page(app, ui, palette),
        Page::Albums => albums_page(app, ui, palette),
        Page::Artists => artists_page(app, ui, palette),
        Page::Queues => queues_page(app, ui, palette),
        Page::MostPlayed => played_page(app, ui, palette, PlayedKind::Most),
        Page::Recent => played_page(app, ui, palette, PlayedKind::Recent),
        Page::History => played_page(app, ui, palette, PlayedKind::History),
        Page::Bookmarks => bookmarks_page(app, ui, palette),
        Page::Equalizer => equalizer_page(app, ui, palette),
        Page::Settings => settings_page(app, ui, palette, compact),
    }
}

fn tabs(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let labels = [
        (app.t("Folders", "文件夹"), app.home_tab == HomeTab::Folders),
        (
            app.t("Playlists", "播放列表"),
            app.home_tab == HomeTab::Playlists,
        ),
    ];
    if let Some(index) = common::segmented(ui, palette, &labels) {
        if index == 0 {
            app.home_tab = HomeTab::Folders;
            app.page = Page::Folders;
            app.detail = None;
        } else {
            app.home_tab = HomeTab::Playlists;
            app.page = Page::Playlists;
            app.detail = None;
        }
    }
}

fn folders_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let title = app
        .folder_path
        .as_ref()
        .and_then(|p| p.file_name())
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| app.t("Folders", "文件夹").to_owned());
    let subtitle = app
        .t(
            "Music, exactly where you put it.",
            "按文件夹浏览，音乐在哪里，这里就在哪里。",
        )
        .to_owned();
    let current_path = app.folder_path.clone();
    let keys = current_path
        .as_ref()
        .map(|path| app.folder_song_keys(path))
        .unwrap_or_else(|| app.songs.iter().map(|s| s.key.clone()).collect());
    let play_name = title.clone();
    page_header(
        app,
        ui,
        palette,
        app.t("LIBRARY", "曲库"),
        &title,
        &subtitle,
        |app, ui| {
            if widgets::primary_button(ui, app.t("Play all", "全部播放"), Some(Icon::Play), palette)
                .clicked()
                && !keys.is_empty()
            {
                app.play_context(keys.clone(), 0, &play_name);
            }
            if widgets::ghost_button(ui, app.t("Shuffle", "随机"), Some(Icon::Shuffle), palette)
                .clicked()
                && !keys.is_empty()
            {
                app.shuffle = true;
                app.play_context(keys.clone(), 0, &play_name);
            }
            if widgets::ghost_button(
                ui,
                app.t("Add folder", "添加文件夹"),
                Some(Icon::Plus),
                palette,
            )
            .clicked()
            {
                app.add_library_folder();
            }
        },
    );
    tabs(app, ui, palette);
    ui.add_space(12.0);

    if app.home_tab == HomeTab::Playlists {
        playlists_home(app, ui, palette);
        return;
    }

    if app.folder_path.is_some() {
        common::breadcrumb(app, ui, palette);
    }

    let scroll_height = ui.available_height();
    egui::ScrollArea::vertical()
        .id_salt("folders_scroll")
        .max_height(scroll_height)
        .auto_shrink([false, false])
        .show(ui, |ui| {
            if app.folder_path.is_none() {
                let roots: Vec<std::path::PathBuf> = app
                    .state
                    .library_roots
                    .iter()
                    .map(|r| canonical_clean(&std::path::PathBuf::from(r)))
                    .filter(|p| app.folders.get(p).is_some() || p.is_dir())
                    .collect();
                if roots.is_empty() {
                    widgets::empty_state(
                        ui,
                        Icon::Folder,
                        app.t("No folders yet", "还没有文件夹"),
                        app.t(
                            "Add a folder to start building your library.",
                            "添加文件夹开始建立曲库。",
                        ),
                        palette,
                    );
                    if widgets::primary_button(
                        ui,
                        app.t("Add folder", "添加文件夹"),
                        Some(Icon::Plus),
                        palette,
                    )
                    .clicked()
                    {
                        app.add_library_folder();
                    }
                    return;
                }
                if !app.state.settings.folder_hierarchical {
                    let mut folders: Vec<std::path::PathBuf> = app
                        .folders
                        .folders
                        .values()
                        .filter(|info| !info.direct.is_empty())
                        .map(|info| info.path.clone())
                        .collect();
                    folders.sort_by_key(|p| p.to_string_lossy().to_lowercase());
                    common::folder_grid(app, ui, palette, &folders);
                } else {
                    common::folder_grid(app, ui, palette, &roots);
                }
                return;
            }
            let path = app.folder_path.clone().unwrap();
            let info = app.folders.get(&path).cloned();
            let children: Vec<std::path::PathBuf> = info
                .as_ref()
                .map(|i| i.children.clone())
                .unwrap_or_default();
            if !children.is_empty() {
                ui.label(
                    RichText::new(app.t("Folders", "文件夹"))
                        .font(display_font(16.0))
                        .color(palette.text),
                );
                ui.add_space(8.0);
                common::folder_grid(app, ui, palette, &children);
                ui.add_space(6.0);
            }
            let direct: Vec<usize> = info.as_ref().map(|i| i.direct.clone()).unwrap_or_default();
            let all_under: Vec<usize> = app
                .songs
                .iter()
                .enumerate()
                .filter(|(_, s)| path_under(&s.path, &path))
                .map(|(i, _)| i)
                .collect();
            let rows = if !direct.is_empty() {
                direct.clone()
            } else {
                all_under.clone()
            };
            if rows.is_empty() {
                widgets::empty_state(
                    ui,
                    Icon::Music,
                    app.t("No audio files here", "这里没有音频文件"),
                    app.t(
                        "Try another folder or rescan.",
                        "试试其他文件夹或重新扫描。",
                    ),
                    palette,
                );
            } else {
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new(if direct.is_empty() {
                            app.t("All tracks here", "此文件夹中的全部歌曲")
                        } else {
                            app.t("Tracks in this folder", "此文件夹中的歌曲")
                        })
                        .font(display_font(16.0))
                        .color(palette.text),
                    );
                });
                ui.add_space(6.0);
                let context_keys: Vec<String> = all_under
                    .iter()
                    .filter_map(|i| app.songs.get(*i).map(|s| s.key.clone()))
                    .collect();
                common::selection_bar(app, ui, palette);
                common::song_table(
                    app,
                    ui,
                    palette,
                    &rows,
                    &title,
                    &context_keys,
                    true,
                    None,
                    None,
                    false,
                    false,
                );
            }
        });
}

fn playlists_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    page_header(
        app,
        ui,
        palette,
        app.t("COLLECTIONS", "收藏"),
        app.t("Playlists", "播放列表"),
        app.t(
            "Hand-built sets that stay on this device.",
            "保存在本机的自定义歌单。",
        ),
        |app, ui| {
            if widgets::primary_button(
                ui,
                app.t("New playlist", "新建播放列表"),
                Some(Icon::Plus),
                palette,
            )
            .clicked()
            {
                app.modal = Some(Modal::NewPlaylist {
                    name: String::new(),
                    songs: app.selected_or_current_keys(),
                });
            }
            if widgets::ghost_button(
                ui,
                app.t("Import M3U", "导入 M3U"),
                Some(Icon::Upload),
                palette,
            )
            .clicked()
            {
                app.import_m3u();
            }
        },
    );
    tabs(app, ui, palette);
    ui.add_space(12.0);
    egui::ScrollArea::vertical()
        .id_salt("playlists_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            common::playlist_grid(app, ui, palette);
        });
}

fn playlists_home(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    ui.horizontal(|ui| {
        ui.label(
            RichText::new(app.t("Playlists", "播放列表"))
                .font(display_font(17.0))
                .color(palette.text),
        );
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if widgets::ghost_button(
                ui,
                app.t("Import M3U", "导入 M3U"),
                Some(Icon::Upload),
                palette,
            )
            .clicked()
            {
                app.import_m3u();
            }
            if widgets::primary_button(
                ui,
                app.t("New playlist", "新建播放列表"),
                Some(Icon::Plus),
                palette,
            )
            .clicked()
            {
                app.modal = Some(Modal::NewPlaylist {
                    name: String::new(),
                    songs: app.selected_or_current_keys(),
                });
            }
        });
    });
    ui.add_space(10.0);
    common::playlist_grid(app, ui, palette);
}

fn songs_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let mut indices = filter_and_sort(&app.songs, "", app.sort, app.sort_desc, None, None);
    if app.favorites_only {
        indices.retain(|i| app.songs[*i].favorite);
    }
    let keys: Vec<String> = indices.iter().map(|i| app.songs[*i].key.clone()).collect();
    let count = indices.len();
    let total: f64 = indices.iter().map(|i| app.songs[*i].duration).sum();
    page_header(
        app,
        ui,
        palette,
        app.t("LIBRARY", "曲库"),
        if app.favorites_only {
            app.t("Favorites", "收藏")
        } else {
            app.t("Songs", "歌曲")
        },
        &format!(
            "{}  ·  {}",
            tracks(app.lang(), count),
            crate::i18n::total_duration(app.lang(), total)
        ),
        |app, ui| {
            if widgets::primary_button(ui, app.t("Play all", "全部播放"), Some(Icon::Play), palette)
                .clicked()
                && !keys.is_empty()
            {
                let name = app.t("All songs", "所有歌曲").to_owned();
                app.play_context(keys.clone(), 0, &name);
            }
            if widgets::ghost_button(ui, app.t("Shuffle", "随机"), Some(Icon::Shuffle), palette)
                .clicked()
                && !keys.is_empty()
            {
                app.shuffle = true;
                let name = app.t("All songs", "所有歌曲").to_owned();
                app.play_context(keys.clone(), 0, &name);
            }
            if app.current_song().is_some()
                && widgets::ghost_button(
                    ui,
                    app.t("Locate", "定位"),
                    Some(Icon::Locate),
                    palette,
                )
                .clicked()
            {
                app.locate_current_song();
            }
            sort_menu(app, ui, palette);
        },
    );
    ui.horizontal(|ui| {
        if widgets::chip(
            ui,
            app.t("All songs", "所有歌曲"),
            !app.favorites_only,
            palette,
        )
        .clicked()
        {
            app.favorites_only = false;
        }
        if widgets::chip(ui, app.t("Favorites", "收藏"), app.favorites_only, palette).clicked() {
            app.favorites_only = true;
        }
        ui.add_space(4.0);
        if widgets::chip(ui, app.t("Select all", "全选"), false, palette).clicked() {
            let all: Vec<String> = indices.iter().map(|i| app.songs[*i].key.clone()).collect();
            app.select_all(all);
        }
        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
            if widgets::chip(
                ui,
                app.t("Selection mode", "多选"),
                app.selection_mode,
                palette,
            )
            .clicked()
            {
                app.selection_mode = !app.selection_mode;
                if !app.selection_mode {
                    app.selected.clear();
                }
            }
        });
    });
    ui.add_space(6.0);
    common::selection_bar(app, ui, palette);
    if indices.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Music,
            app.t("Nothing here yet", "这里还没有歌曲"),
            app.t(
                "Add a music folder in Settings, then rescan.",
                "在设置中添加音乐文件夹，然后重新扫描。",
            ),
            palette,
        );
        return;
    }

    let available_height = ui.available_height();
    ui.horizontal_top(|ui| {
        let rail_width = 20.0;
        let table_width = (ui.available_width() - rail_width - 8.0).max(320.0);
        ui.allocate_ui_with_layout(
            Vec2::new(table_width, available_height),
            Layout::top_down(Align::Min),
            |ui| {
                egui::ScrollArea::vertical()
                    .id_salt("songs_scroll")
                    .max_height(available_height)
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        common::song_table(
                            app,
                            ui,
                            palette,
                            &indices,
                            app.t("Songs", "歌曲"),
                            &keys,
                            true,
                            None,
                            None,
                            false,
                            false,
                        );
                    });
            },
        );
        ui.add_space(4.0);
        ui.vertical(|ui| {
            let mut letters: Vec<char> = Vec::new();
            for index in &indices {
                let letter = chars_for_index(app.songs[*index].display_title());
                if !letters.contains(&letter) {
                    letters.push(letter);
                }
            }
            letters.sort();
            for letter in letters {
                let color = if app
                    .current_song()
                    .map(|s| chars_for_index(s.display_title()) == letter)
                    .unwrap_or(false)
                {
                    palette.accent
                } else {
                    palette.muted
                };
                let response = ui.add(
                    egui::Button::new(RichText::new(letter.to_string()).size(9.0).color(color))
                        .frame(false)
                        .min_size(Vec2::new(16.0, 18.0)),
                );
                if response.clicked() {
                    app.jump_letter = Some(letter);
                }
            }
        });
    });
}

fn alphabet() -> Vec<char> {
    let mut letters: Vec<char> = ('A'..='Z').collect();
    letters.push('#');
    letters
}

fn sort_menu(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let label = sort_label(app, app.sort);
    egui::ComboBox::from_id_salt("sort_combo")
        .selected_text(RichText::new(label).size(11.0).color(palette.muted))
        .width(130.0)
        .show_ui(ui, |ui| {
            for sort in SortMode::ALL {
                if ui
                    .selectable_label(app.sort == sort, sort_label(app, sort))
                    .clicked()
                {
                    app.sort = sort;
                    app.sort_desc = false;
                    ui.close_menu();
                }
            }
            ui.separator();
            if ui
                .selectable_label(app.sort_desc, app.t("Descending", "降序"))
                .clicked()
            {
                app.sort_desc = !app.sort_desc;
                ui.close_menu();
            }
        });
}

fn sort_label(app: &LiltApp, sort: SortMode) -> &'static str {
    match sort {
        SortMode::Title => app.t("Title", "标题"),
        SortMode::Artist => app.t("Artist", "艺术家"),
        SortMode::Album => app.t("Album", "专辑"),
        SortMode::Duration => app.t("Duration", "时长"),
        SortMode::DateAdded => app.t("Date added", "添加时间"),
        SortMode::LastPlayed => app.t("Last played", "最近播放"),
        SortMode::Plays => app.t("Play count", "播放次数"),
        SortMode::Track => app.t("Track number", "音轨号"),
        SortMode::Filename => app.t("Filename", "文件名"),
    }
}

fn albums_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let album_count = app.albums.len();
    page_header(
        app,
        ui,
        palette,
        app.t("LIBRARY", "曲库"),
        app.t("Albums", "专辑"),
        &format!(
            "{}  ·  {}",
            albums(app.lang(), album_count),
            app.t("Covers are drawn locally", "封面由本机生成")
        ),
        |_app, _ui| {},
    );
    egui::ScrollArea::vertical()
        .id_salt("albums_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            let indices: Vec<usize> = (0..app.albums.len()).collect();
            common::album_grid(app, ui, palette, &indices);
            if app.albums.is_empty() {
                widgets::empty_state(
                    ui,
                    Icon::Disc,
                    app.t("No albums yet", "还没有专辑"),
                    app.t(
                        "Scan a folder with tagged music.",
                        "扫描带标签的音乐文件夹。",
                    ),
                    palette,
                );
            }
        });
}

fn artists_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    page_header(
        app,
        ui,
        palette,
        app.t("LIBRARY", "曲库"),
        app.t("Artists", "艺术家"),
        &format!(
            "{}  ·  {}",
            app.artists.len(),
            app.t("Everything grouped by performer", "按表演者归类")
        ),
        |_app, _ui| {},
    );
    egui::ScrollArea::vertical()
        .id_salt("artists_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            common::artist_list(app, ui, palette);
        });
}

fn queues_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    page_header(
        app,
        ui,
        palette,
        app.t("PLAYBACK", "播放"),
        app.t("Queues", "队列"),
        &format!(
            "{} / {}  ·  {}",
            app.state.queues.len(),
            MAX_QUEUES,
            app.t(
                "Each queue remembers its own place",
                "每个队列记住自己的播放位置"
            )
        ),
        |app, ui| {
            if widgets::primary_button(
                ui,
                app.t("New queue", "新建队列"),
                Some(Icon::Plus),
                palette,
            )
            .clicked()
            {
                app.new_queue_dialog();
            }
        },
    );
    if app.state.queues.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Queue,
            app.t("No queues yet", "还没有队列"),
            app.t(
                "Play an album, folder or playlist to start one.",
                "播放专辑、文件夹或歌单即可创建。",
            ),
            palette,
        );
        return;
    }
    let queues = app.state.queues.clone();
    egui::ScrollArea::vertical()
        .id_salt("queues_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            for queue in queues {
                let active = queue.id == app.state.active_queue;
                let (rect, response) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), 78.0), Sense::click());
                if active {
                    ui.painter()
                        .rect_filled(rect, 11.0, with_alpha(palette.accent, 12));
                } else if response.hovered() {
                    ui.painter().rect_filled(rect, 11.0, palette.surface_hi);
                }
                let art = Rect::from_min_size(
                    egui::pos2(rect.left() + 14.0, rect.center().y - 24.0),
                    Vec2::splat(48.0),
                );
                widgets::draw_cover(ui.painter(), art, &queue.name, palette);
                let clip = ui.painter().with_clip_rect(Rect::from_min_max(
                    egui::pos2(rect.left() + 74.0, rect.top() + 12.0),
                    egui::pos2(rect.right() - 220.0, rect.bottom() - 8.0),
                ));
                clip.text(
                    egui::pos2(rect.left() + 74.0, rect.top() + 27.0),
                    Align2::LEFT_CENTER,
                    &queue.name,
                    FontId::proportional(13.5),
                    palette.text,
                );
                let current_title = queue
                    .songs
                    .get(queue.index)
                    .and_then(|key| app.song_by_key(key))
                    .map(|s| s.display_title().to_owned())
                    .unwrap_or_else(|| app.t("Empty queue", "空队列").to_owned());
                clip.text(
                    egui::pos2(rect.left() + 74.0, rect.top() + 47.0),
                    Align2::LEFT_CENTER,
                    format!(
                        "{}  ·  {}  ·  {}",
                        tracks(app.lang(), queue.songs.len()),
                        current_title,
                        if active {
                            app.t("Active", "当前")
                        } else {
                            app.t("Saved", "已保存")
                        }
                    ),
                    FontId::proportional(10.0),
                    palette.muted,
                );
                let mut resume = false;
                let mut rename = false;
                let mut delete = false;
                let mut export = false;
                let mut load_playlist = false;
                ui.allocate_ui_at_rect(
                    Rect::from_min_max(
                        egui::pos2(rect.right() - 200.0, rect.top() + 22.0),
                        egui::pos2(rect.right() - 14.0, rect.bottom() - 22.0),
                    ),
                    |ui| {
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            ui.menu_button("···", |ui| {
                                if ui.button(app.t("Rename queue", "重命名队列")).clicked() {
                                    rename = true;
                                    ui.close_menu();
                                }
                                if ui.button(app.t("Export M3U", "导出 M3U")).clicked() {
                                    export = true;
                                    ui.close_menu();
                                }
                                if ui
                                    .button(app.t("Load a playlist", "装载播放列表"))
                                    .clicked()
                                {
                                    load_playlist = true;
                                    ui.close_menu();
                                }
                                if ui.button(app.t("Delete queue", "删除队列")).clicked() {
                                    delete = true;
                                    ui.close_menu();
                                }
                            });
                            if widgets::ghost_button(
                                ui,
                                if active {
                                    app.t("Current", "当前")
                                } else {
                                    app.t("Resume", "继续")
                                },
                                Some(Icon::Play),
                                palette,
                            )
                            .clicked()
                            {
                                resume = true;
                            }
                        });
                    },
                );
                if response.clicked() {
                    app.open_queue(queue.id);
                }
                if resume {
                    app.switch_queue(queue.id, true);
                }
                if rename {
                    app.modal = Some(Modal::RenameQueue {
                        id: queue.id,
                        name: queue.name.clone(),
                    });
                }
                if delete {
                    app.modal = Some(Modal::Confirm {
                        title: app.t("Delete queue?", "删除队列？").to_owned(),
                        body: app
                            .t(
                                "This only removes the queue, not your music.",
                                "只会删除队列，不会删除音乐。",
                            )
                            .to_owned(),
                        action: crate::app::ConfirmAction::DeleteQueue(queue.id),
                    });
                }
                if export {
                    app.export_queue_m3u(queue.id);
                }
                if load_playlist {
                    app.modal = Some(Modal::LoadQueueFromPlaylist { queue_id: queue.id });
                }
                widgets::divider(ui, palette);
            }
        });
}

fn queue_detail(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, id: u64) {
    let Some(queue) = app.state.queue_by_id(id).cloned() else {
        widgets::empty_state(ui, Icon::Queue, "Queue not found", "", palette);
        return;
    };
    let keys: Vec<String> = queue.songs.clone();
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
            RichText::new(format!(
                "{} {} / {}",
                app.t("Queue", "队列"),
                app.state
                    .queues
                    .iter()
                    .position(|q| q.id == id)
                    .unwrap_or(0)
                    + 1,
                app.state.queues.len()
            ))
            .size(10.5)
            .color(palette.faint),
        );
    });
    if back {
        app.detail = None;
        app.page = Page::Queues;
        return;
    }
    ui.add_space(4.0);
    page_header(
        app,
        ui,
        palette,
        app.t("QUEUE", "队列"),
        &queue.name,
        &format!(
            "{}  ·  {}",
            tracks(app.lang(), queue.songs.len()),
            if queue.id == app.state.active_queue {
                app.t("Currently selected", "当前选中")
            } else {
                app.t("Saved for later", "稍后播放")
            }
        ),
        |app, ui| {
            if widgets::primary_button(ui, app.t("Resume", "继续播放"), Some(Icon::Play), palette)
                .clicked()
            {
                app.switch_queue(queue.id, true);
            }
            if widgets::ghost_button(
                ui,
                app.t("Export M3U", "导出 M3U"),
                Some(Icon::Download),
                palette,
            )
            .clicked()
            {
                app.export_queue_m3u(queue.id);
            }
            if icons::icon_button(
                ui,
                Icon::Edit,
                30.0,
                palette.muted,
                palette.surface_hi,
                app.t("Rename", "重命名"),
            )
            .clicked()
            {
                app.modal = Some(Modal::RenameQueue {
                    id: queue.id,
                    name: queue.name.clone(),
                });
            }
        },
    );
    if keys.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Queue,
            app.t("This queue is empty", "这个队列是空的"),
            app.t(
                "Add songs from any list with the ··· menu.",
                "在任意列表的 ··· 菜单中添加歌曲。",
            ),
            palette,
        );
        return;
    }
    let rows: Vec<usize> = queue
        .songs
        .iter()
        .filter_map(|key| app.songs.iter().position(|s| &s.key == key))
        .collect();
    egui::ScrollArea::vertical()
        .id_salt("queue_detail_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            common::song_table(
                app,
                ui,
                palette,
                &rows,
                &queue.name,
                &keys,
                true,
                None,
                Some(queue.id),
                true,
                false,
            );
        });
}

fn playlist_detail(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, id: u64) {
    let Some(playlist) = app.state.playlist_by_id(id).cloned() else {
        widgets::empty_state(ui, Icon::List, "Playlist not found", "", palette);
        return;
    };
    let keys: Vec<String> = playlist.songs.clone();
    let rows: Vec<usize> = playlist
        .songs
        .iter()
        .filter_map(|key| app.songs.iter().position(|s| &s.key == key))
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
            RichText::new(app.t("Playlists", "播放列表"))
                .size(10.5)
                .color(palette.faint),
        );
    });
    if back {
        app.detail = None;
        app.page = Page::Playlists;
        return;
    }
    let size = (ui.available_width() * 0.19).clamp(120.0, 180.0);
    ui.horizontal(|ui| {
        widgets::draw_cover(
            ui.painter(),
            Rect::from_min_size(ui.cursor().min, Vec2::splat(size)),
            &playlist.name,
            palette,
        );
        ui.allocate_space(Vec2::splat(size));
        ui.add_space(22.0);
        ui.vertical(|ui| {
            ui.label(
                RichText::new(app.t("PLAYLIST", "播放列表"))
                    .size(9.5)
                    .color(palette.faint)
                    .strong(),
            );
            ui.label(
                RichText::new(&playlist.name)
                    .font(display_font(30.0))
                    .color(palette.text),
            );
            ui.label(
                RichText::new(format!(
                    "{}  ·  {}",
                    tracks(app.lang(), playlist.songs.len()),
                    crate::i18n::total_duration(
                        app.lang(),
                        rows.iter().map(|i| app.songs[*i].duration).sum(),
                    )
                ))
                .size(11.5)
                .color(palette.muted),
            );
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                if widgets::primary_button(ui, app.t("Play", "播放"), Some(Icon::Play), palette)
                    .clicked()
                    && !keys.is_empty()
                {
                    let name = playlist.name.clone();
                    app.play_context(keys.clone(), 0, &name);
                }
                if widgets::ghost_button(ui, app.t("Export", "导出"), Some(Icon::Download), palette)
                    .clicked()
                {
                    app.export_playlist_m3u(playlist.id);
                }
                if icons::icon_button(
                    ui,
                    Icon::Edit,
                    30.0,
                    palette.muted,
                    palette.surface_hi,
                    app.t("Rename", "重命名"),
                )
                .clicked()
                {
                    app.modal = Some(Modal::RenamePlaylist {
                        id: playlist.id,
                        name: playlist.name.clone(),
                    });
                }
                if icons::icon_button(
                    ui,
                    Icon::Trash,
                    30.0,
                    palette.danger,
                    palette.surface_hi,
                    app.t("Delete", "删除"),
                )
                .clicked()
                {
                    app.modal = Some(Modal::Confirm {
                        title: app.t("Delete playlist?", "删除播放列表？").to_owned(),
                        body: app
                            .t("Songs stay in your library.", "歌曲仍会保留在曲库中。")
                            .to_owned(),
                        action: crate::app::ConfirmAction::DeletePlaylist(playlist.id),
                    });
                }
            });
        });
    });
    ui.add_space(14.0);
    if rows.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Music,
            app.t("This playlist is empty", "这个播放列表是空的"),
            app.t(
                "Select songs in Songs, then choose Add to playlist.",
                "在歌曲页选择歌曲，然后选择加入播放列表。",
            ),
            palette,
        );
    } else {
        egui::ScrollArea::vertical()
            .id_salt("playlist_detail_scroll")
            .max_height(ui.available_height())
            .auto_shrink([false, false])
            .show(ui, |ui| {
                common::song_table(
                    app,
                    ui,
                    palette,
                    &rows,
                    &playlist.name,
                    &keys,
                    true,
                    Some(playlist.id),
                    None,
                    true,
                    false,
                );
            });
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum PlayedKind {
    Most,
    Recent,
    History,
}

fn played_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, kind: PlayedKind) {
    let (eyebrow, title, subtitle) = match kind {
        PlayedKind::Most => (
            app.t("COLLECTIONS", "收藏"),
            app.t("Most played", "最多播放"),
            app.t("The songs you keep coming back to.", "你最常回到的那些歌。"),
        ),
        PlayedKind::Recent => (
            app.t("COLLECTIONS", "收藏"),
            app.t("Recently added", "最近添加"),
            app.t("Newest files in the library.", "曲库中最新加入的文件。"),
        ),
        PlayedKind::History => (
            app.t("COLLECTIONS", "收藏"),
            app.t("History", "播放历史"),
            app.t(
                "A local record of what you played.",
                "仅保存在本机的播放记录。",
            ),
        ),
    };
    let (sort, desc) = match kind {
        PlayedKind::Most => (SortMode::Plays, true),
        PlayedKind::Recent => (SortMode::DateAdded, true),
        PlayedKind::History => (SortMode::LastPlayed, true),
    };
    let mut indices = filter_and_sort(&app.songs, "", sort, desc, None, None);
    if kind == PlayedKind::History {
        indices.retain(|i| app.songs[*i].last_played.is_some());
    }
    if kind == PlayedKind::Most {
        indices.retain(|i| app.songs[*i].play_count > 0);
    }
    let keys: Vec<String> = indices.iter().map(|i| app.songs[*i].key.clone()).collect();
    let count = indices.len();
    page_header(
        app,
        ui,
        palette,
        eyebrow,
        title,
        &format!("{}  ·  {}", tracks(app.lang(), count), subtitle),
        |app, ui| {
            if kind == PlayedKind::Most {
                if widgets::ghost_button(
                    ui,
                    app.t("Export CSV", "导出 CSV"),
                    Some(Icon::Download),
                    palette,
                )
                .clicked()
                {
                    app.export_most_played_csv();
                }
            }
            if kind == PlayedKind::History {
                if widgets::ghost_button(
                    ui,
                    app.t("Clear history", "清除历史"),
                    Some(Icon::Trash),
                    palette,
                )
                .clicked()
                {
                    app.modal = Some(Modal::Confirm {
                        title: app
                            .t("Clear playback history?", "清除播放历史？")
                            .to_owned(),
                        body: app
                            .t(
                                "Play counts and last positions will be reset.",
                                "播放次数和上次位置将被清空。",
                            )
                            .to_owned(),
                        action: crate::app::ConfirmAction::ClearHistory,
                    });
                }
            }
        },
    );
    if indices.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Gauge,
            app.t("Not enough listening yet", "还没有足够的播放记录"),
            app.t("Play a few songs and come back.", "播放几首歌后再来看看。"),
            palette,
        );
        return;
    }
    egui::ScrollArea::vertical()
        .id_salt(format!("played_scroll_{:?}", kind))
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            common::song_table(
                app,
                ui,
                palette,
                &indices,
                title,
                &keys,
                true,
                None,
                None,
                false,
                kind == PlayedKind::Most,
            );
        });
}

fn bookmarks_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    struct Entry {
        key: String,
        title: String,
        artist: String,
        position: f64,
        note: String,
        created: u64,
    }
    let mut entries: Vec<Entry> = Vec::new();
    for song in &app.songs {
        if let Some(extra) = app.state.songs.get(&song.key) {
            for bookmark in &extra.bookmarks {
                entries.push(Entry {
                    key: song.key.clone(),
                    title: song.display_title().to_owned(),
                    artist: song.display_artist().to_owned(),
                    position: bookmark.position,
                    note: bookmark.note.clone(),
                    created: bookmark.created,
                });
            }
        }
    }
    entries.sort_by(|a, b| b.created.cmp(&a.created));
    page_header(
        app,
        ui,
        palette,
        app.t("COLLECTIONS", "收藏"),
        app.t("Bookmarks & notes", "书签与笔记"),
        &format!("{} saved moments", entries.len()),
        |_app, _ui| {},
    );
    if entries.is_empty() {
        widgets::empty_state(
            ui,
            Icon::Bookmark,
            app.t("No bookmarks yet", "还没有书签"),
            app.t(
                "Use the ··· menu on a song to leave a note at a moment.",
                "在歌曲的 ··· 菜单里记录一段笔记。",
            ),
            palette,
        );
        return;
    }
    egui::ScrollArea::vertical()
        .id_salt("bookmarks_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            for entry in entries {
                let (rect, response) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), 66.0), Sense::click());
                if response.hovered() {
                    ui.painter().rect_filled(rect, 9.0, palette.surface_hi);
                }
                icons::paint(
                    ui.painter(),
                    Rect::from_center_size(
                        egui::pos2(rect.left() + 24.0, rect.center().y),
                        Vec2::splat(18.0),
                    ),
                    Icon::Bookmark,
                    palette.accent,
                    1.5,
                );
                let clip = ui.painter().with_clip_rect(Rect::from_min_max(
                    egui::pos2(rect.left() + 48.0, rect.top() + 8.0),
                    egui::pos2(rect.right() - 70.0, rect.bottom() - 4.0),
                ));
                clip.text(
                    egui::pos2(rect.left() + 48.0, rect.top() + 20.0),
                    Align2::LEFT_CENTER,
                    format!("{} — {}", entry.title, entry.artist),
                    FontId::proportional(12.0),
                    palette.text,
                );
                let note_text = if entry.note.is_empty() {
                    app.t("No note", "无笔记")
                } else {
                    entry.note.as_str()
                };
                clip.text(
                    egui::pos2(rect.left() + 48.0, rect.top() + 39.0),
                    Align2::LEFT_CENTER,
                    format!("{}  ·  {}", format_duration(entry.position), note_text),
                    FontId::proportional(10.5),
                    palette.muted,
                );
                ui.painter().text(
                    egui::pos2(rect.right() - 16.0, rect.center().y),
                    Align2::RIGHT_CENTER,
                    format_duration(entry.position),
                    FontId::monospace(10.0),
                    palette.faint,
                );
                if response.clicked() {
                    app.jump_to_bookmark(&entry.key, entry.position);
                }
                widgets::divider(ui, palette);
            }
        });
}

fn equalizer_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let mut profile = app.active_eq.clone();
    let mut changed = false;
    let mut enabled = app.state.settings.eq_enabled;
    page_header(
        app,
        ui,
        palette,
        app.t("AUDIO", "音频"),
        app.t("Equalizer", "均衡器"),
        app.t(
            "Ten bands, shaped in real time. Profiles follow the output device.",
            "十段实时调整，按输出设备保存。",
        ),
        |app, ui| {
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(if enabled {
                        app.t("On", "开")
                    } else {
                        app.t("Off", "关")
                    })
                    .size(11.0)
                    .color(if enabled {
                        palette.accent
                    } else {
                        palette.muted
                    }),
                );
                if widgets::toggle_switch(ui, &mut enabled, palette).clicked() {
                    app.set_eq_enabled(enabled);
                    changed = true;
                }
            });
        },
    );
    let wide_devices = ui.available_width() >= 620.0;
    if wide_devices {
        ui.horizontal(|ui| {
            for device in DeviceProfile::ALL {
                let selected = app.state.settings.eq_device == device;
                if widgets::chip(ui, device.label_en(), selected, palette).clicked() {
                    app.switch_eq_device(device);
                    profile = app.active_eq.clone();
                    enabled = app.state.settings.eq_enabled;
                    changed = true;
                }
            }
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if widgets::ghost_button(ui, app.t("Reset", "重置"), Some(Icon::Refresh), palette)
                    .clicked()
                {
                    profile.apply_preset("Flat");
                    profile.preamp_mode = PreampMode::Off;
                    profile.headroom_db = 6.0;
                    profile.boost_db = 2.0;
                    profile.bass = 0.0;
                    profile.virtualizer = 0.0;
                    profile.balance = 100.0;
                    changed = true;
                }
            });
        });
    } else {
        ui.horizontal_wrapped(|ui| {
            for device in DeviceProfile::ALL {
                let selected = app.state.settings.eq_device == device;
                if widgets::chip(ui, device.label_en(), selected, palette).clicked() {
                    app.switch_eq_device(device);
                    profile = app.active_eq.clone();
                    enabled = app.state.settings.eq_enabled;
                    changed = true;
                }
            }
        });
        ui.horizontal(|ui| {
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if widgets::ghost_button(ui, app.t("Reset", "重置"), Some(Icon::Refresh), palette)
                    .clicked()
                {
                    profile.apply_preset("Flat");
                    profile.preamp_mode = PreampMode::Off;
                    profile.headroom_db = 6.0;
                    profile.boost_db = 2.0;
                    profile.bass = 0.0;
                    profile.virtualizer = 0.0;
                    profile.balance = 100.0;
                    changed = true;
                }
            });
        });
    }
    ui.add_space(10.0);
    egui::ScrollArea::vertical()
        .id_salt("equalizer_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            egui::Frame::NONE
                .fill(palette.surface)
                .stroke(Stroke::new(1.0, palette.border))
                .corner_radius(14.0)
                .inner_margin(egui::Margin::symmetric(18, 16))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new(app.t("Response", "响应曲线"))
                                .font(display_font(15.0))
                                .color(palette.text),
                        );
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            let label = format!(
                                "{}  {:+.1} dB",
                                app.t("Preamp", "前级"),
                                profile.preamp_db()
                            );
                            ui.label(RichText::new(label).size(10.0).color(palette.muted));
                        });
                    });
                    ui.add_space(4.0);
                    widgets::eq_curve(ui, &profile.gains, enabled, palette, 64.0);
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new(app.t("PRESETS", "预设"))
                            .size(9.0)
                            .color(palette.faint)
                            .strong(),
                    );
                    ui.add_space(4.0);
                    ui.horizontal_wrapped(|ui| {
                        // The target's 22 ten-band presets from `c50.<clinit>`.
                        for preset in EQ_PRESETS {
                            let selected = profile.preset == preset;
                            if widgets::chip(ui, preset, selected, palette).clicked() {
                                profile.apply_preset(preset);
                                changed = true;
                            }
                        }
                    });
                    ui.add_space(8.0);
                    ui.horizontal_top(|ui| {
                        ui.spacing_mut().item_spacing.x = 0.0;
                        let group_width = 520.0;
                        ui.add_space(((ui.available_width() - group_width) / 2.0).max(0.0));
                        for band in 0..EQ_BANDS {
                            ui.vertical(|ui| {
                                ui.set_width(46.0);
                                ui.label(
                                    RichText::new(format!("{:+.1}", profile.gains[band]))
                                        .size(8.5)
                                        .color(palette.muted),
                                );
                                if widgets::vertical_fader(
                                    ui,
                                    &mut profile.gains[band],
                                    palette,
                                    72.0,
                                ) {
                                    profile.preset = "Custom".to_owned();
                                    changed = true;
                                }
                                let freq = EQ_FREQUENCIES[band];
                                let label = if freq >= 1000.0 {
                                    format!("{:.0}k", freq / 1000.0)
                                } else if (freq.fract()).abs() > 0.01 {
                                    format!("{freq:.1}")
                                } else {
                                    format!("{freq:.0}")
                                };
                                ui.label(RichText::new(label).size(8.0).color(palette.faint));
                            });
                            ui.add_space(6.0);
                        }
                    });
                    ui.add_space(6.0);
                    ui.horizontal_wrapped(|ui| {
                        ui.spacing_mut().slider_width = if ui.available_width() < 620.0 {
                            70.0
                        } else {
                            110.0
                        };
                        ui.vertical(|ui| {
                            ui.set_width(if wide_devices { 200.0 } else { 160.0 });
                            ui.spacing_mut().item_spacing.x = 4.0;
                            ui.label(
                                RichText::new(app.t("Preamp", "前级增益"))
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            ui.horizontal_wrapped(|ui| {
                                if widgets::chip(
                                    ui,
                                    app.t("Off", "关闭"),
                                    profile.preamp_mode == PreampMode::Off,
                                    palette,
                                )
                                .clicked()
                                {
                                    profile.preamp_mode = PreampMode::Off;
                                    changed = true;
                                }
                                if widgets::chip(
                                    ui,
                                    app.t("Headroom", "余量"),
                                    profile.preamp_mode == PreampMode::Headroom,
                                    palette,
                                )
                                .clicked()
                                {
                                    profile.preamp_mode = PreampMode::Headroom;
                                    changed = true;
                                }
                                if widgets::chip(
                                    ui,
                                    app.t("Boost", "提升"),
                                    profile.preamp_mode == PreampMode::Boost,
                                    palette,
                                )
                                .clicked()
                                {
                                    profile.preamp_mode = PreampMode::Boost;
                                    changed = true;
                                }
                            });
                            match profile.preamp_mode {
                                PreampMode::Off => {}
                                PreampMode::Headroom => {
                                    // k_i_hdrmg_: 1..=30 dB, default 6.
                                    let mut headroom = profile.headroom_db;
                                    if ui
                                        .add(
                                            egui::Slider::new(&mut headroom, 1.0..=30.0)
                                                .custom_formatter(|v, _| format!("-{v:.0} dB"))
                                                .show_value(true),
                                        )
                                        .changed()
                                    {
                                        profile.headroom_db = headroom;
                                        changed = true;
                                    }
                                }
                                PreampMode::Boost => {
                                    // k_i_vbg_: 1..=9 dB, default 2.
                                    let mut boost = profile.boost_db;
                                    if ui
                                        .add(
                                            egui::Slider::new(&mut boost, 1.0..=9.0)
                                                .custom_formatter(|v, _| format!("+{v:.0} dB"))
                                                .show_value(true),
                                        )
                                        .changed()
                                    {
                                        profile.boost_db = boost;
                                        changed = true;
                                    }
                                }
                            }
                        });
                        ui.add_space(10.0);
                        ui.vertical(|ui| {
                            ui.set_width(130.0);
                            ui.label(
                                RichText::new(app.t("Bass boost", "低音增强"))
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            let response = ui.add(
                                egui::Slider::new(&mut profile.bass, 0.0..=1.0)
                                    .custom_formatter(|v, _| format!("{:.0}%", v * 100.0))
                                    .show_value(true),
                            );
                            if response.changed() {
                                changed = true;
                            }
                        });
                        ui.add_space(10.0);
                        ui.vertical(|ui| {
                            ui.set_width(130.0);
                            ui.label(
                                RichText::new(app.t("Virtualizer", "虚拟环绕"))
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            let response = ui.add(
                                egui::Slider::new(&mut profile.virtualizer, 0.0..=1.0)
                                    .custom_formatter(|v, _| format!("{:.0}%", v * 100.0))
                                    .show_value(true),
                            );
                            if response.changed() {
                                changed = true;
                            }
                        });
                        ui.add_space(10.0);
                        ui.vertical(|ui| {
                            ui.set_width(130.0);
                            ui.label(
                                RichText::new(app.t("Balance", "左右平衡"))
                                    .size(11.0)
                                    .color(palette.text),
                            );
                            let response = ui.add(
                                egui::Slider::new(&mut profile.balance, 0.0..=200.0)
                                    .custom_formatter(|v, _| {
                                        let v = v.round() as i32;
                                        match v {
                                            100 => "Center".to_owned(),
                                            v if v < 100 => format!("L {v}"),
                                            v => format!("R {}", v - 100),
                                        }
                                    })
                                    .show_value(true),
                            );
                            if response.changed() {
                                changed = true;
                            }
                        });
                    });
                    if let Some(suggested) = profile.suggested_headroom_db() {
                        ui.add_space(6.0);
                        ui.horizontal(|ui| {
                            ui.label(
                                RichText::new(format!(
                                    "{} -{suggested:.0} dB ({})",
                                    app.t("Suggested headroom", "建议余量"),
                                    app.t("to keep peaks at +6 dB", "峰值保持在 +6 dB 以内")
                                ))
                                .size(10.0)
                                .color(palette.muted),
                            );
                            if widgets::ghost_button(
                                ui,
                                app.t("Use", "使用"),
                                Some(Icon::Check),
                                palette,
                            )
                            .clicked()
                            {
                                profile.preamp_mode = PreampMode::Headroom;
                                profile.headroom_db = suggested.clamp(1.0, 30.0);
                                changed = true;
                            }
                        });
                    }
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        let color = if app.playing && enabled {
                            palette.good
                        } else {
                            palette.faint
                        };
                        ui.painter().circle_filled(
                            ui.cursor().min + Vec2::new(4.0, 8.0),
                            3.0,
                            color,
                        );
                        ui.add_space(10.0);
                        ui.label(
                            RichText::new(if app.playing && enabled {
                                app.t("Applied to the current output", "已应用到当前输出")
                            } else {
                                app.t("Changes are remembered for this device", "改动会按设备保存")
                            })
                            .size(10.0)
                            .color(color),
                        );
                    });
                });
        });
    if changed {
        app.set_eq_profile(profile);
    }
}

fn settings_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette, compact: bool) {
    page_header(
        app,
        ui,
        palette,
        app.t("PREFERENCES", "偏好设置"),
        app.t("Settings", "设置"),
        app.t(
            "Small choices, kept on this device.",
            "所有偏好都保存在本机。",
        ),
        |_app, _ui| {},
    );
    let mut rescan = false;
    let screenshot_offset = std::env::var("LILT_SETTINGS_SCROLL")
        .ok()
        .and_then(|value| value.parse::<f32>().ok());
    let mut settings_scroll = egui::ScrollArea::vertical()
        .id_salt("settings_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false]);
    if let Some(offset) = screenshot_offset {
        settings_scroll = settings_scroll.vertical_scroll_offset(offset);
    }
    settings_scroll
        .show(ui, |ui| {
            card_frame(palette).show(ui, |ui| {
                common::section_title(ui, palette, app.t("Appearance", "外观"), "");
                ui.add_space(8.0);
                // `option_base_theme` order: Light, Dark, Black, Day/Night, Follow system.
                let theme_labels = [
                    (
                        app.t("Light", "浅色"),
                        app.state.settings.theme == ThemeMode::Light,
                    ),
                    (
                        app.t("Dark", "深色"),
                        app.state.settings.theme == ThemeMode::Dark,
                    ),
                    (
                        app.t("Black", "纯黑"),
                        app.state.settings.theme == ThemeMode::Black,
                    ),
                    (
                        app.t("Day/Night", "昼夜"),
                        app.state.settings.theme == ThemeMode::DayNight,
                    ),
                    (
                        app.t("System", "跟随系统"),
                        app.state.settings.theme == ThemeMode::System,
                    ),
                ];
                if let Some(index) = common::segmented(ui, palette, &theme_labels) {
                    app.state.settings.theme = [
                        ThemeMode::Light,
                        ThemeMode::Dark,
                        ThemeMode::Black,
                        ThemeMode::DayNight,
                        ThemeMode::System,
                    ][index];
                    app.mark_dirty();
                }
                ui.add_space(10.0);
                setting_row(
                    ui,
                    palette,
                    app.t("Accent", "强调色"),
                    app.t(
                        "Used for highlights, controls and cover art.",
                        "用于高亮、控件和封面。",
                    ),
                    |ui| {
                        for accent in Accent::ALL {
                            let color = crate::theme::accent_color(accent, palette.dark);
                            let (rect, response) =
                                ui.allocate_exact_size(Vec2::splat(24.0), Sense::click());
                            if app.state.settings.accent == accent {
                                ui.painter().circle_stroke(
                                    rect.center(),
                                    11.0,
                                    Stroke::new(1.5, palette.text),
                                );
                            }
                            ui.painter().circle_filled(rect.center(), 8.0, color);
                            if response.clicked() {
                                app.state.settings.accent = accent;
                                app.mark_dirty();
                            }
                        }
                    },
                );
                setting_row(
                    ui,
                    palette,
                    app.t("Language", "语言"),
                    app.t("Switch all interface copy.", "切换全部界面文案。"),
                    |ui| {
                        let labels = [
                            ("English", app.state.settings.language == Language::En),
                            ("中文", app.state.settings.language == Language::Zh),
                        ];
                        if let Some(index) = common::segmented(ui, palette, &labels) {
                            app.state.settings.language = if index == 0 {
                                Language::En
                            } else {
                                Language::Zh
                            };
                            app.mark_dirty();
                        }
                    },
                );
            });
            ui.add_space(12.0);
            sound_output_card(app, ui, palette);
            ui.add_space(12.0);
            card_frame(palette).show(ui, |ui| {
                common::section_title(
                    ui,
                    palette,
                    app.t("Library", "曲库"),
                    app.t(
                        "Where Lilt looks for audio files.",
                        "Lilt 从哪里查找音频文件。",
                    ),
                );
                ui.add_space(8.0);
                let roots = app.state.library_roots.clone();
                for root in &roots {
                    let path = std::path::PathBuf::from(root);
                    let name = path
                        .file_name()
                        .map(|n| n.to_string_lossy().to_string())
                        .unwrap_or_else(|| root.clone());
                    ui.horizontal(|ui| {
                        icons::paint(
                            ui.painter(),
                            Rect::from_center_size(
                                ui.cursor().min + Vec2::new(8.0, 9.0),
                                Vec2::splat(15.0),
                            ),
                            Icon::Folder,
                            palette.muted,
                            1.4,
                        );
                        ui.add_space(22.0);
                        ui.vertical(|ui| {
                            ui.label(RichText::new(name).size(11.5).color(palette.text));
                            ui.label(RichText::new(root).size(9.0).color(palette.faint));
                        });
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            if icons::icon_button(
                                ui,
                                Icon::Trash,
                                24.0,
                                palette.danger,
                                palette.surface_hi,
                                app.t("Remove folder", "移除文件夹"),
                            )
                            .clicked()
                            {
                                app.modal = Some(Modal::Confirm {
                                    title: app
                                        .t("Remove this folder?", "移除此文件夹？")
                                        .to_owned(),
                                    body: app
                                        .t(
                                            "The files stay on disk. They leave the library view.",
                                            "文件仍在磁盘上，只会从曲库视图中移除。",
                                        )
                                        .to_owned(),
                                    action: crate::app::ConfirmAction::RemoveLibraryRoot(
                                        root.clone(),
                                    ),
                                });
                            }
                        });
                    });
                    widgets::divider(ui, palette);
                }
                ui.horizontal(|ui| {
                    if widgets::ghost_button(
                        ui,
                        app.t("Add folder", "添加文件夹"),
                        Some(Icon::Plus),
                        palette,
                    )
                    .clicked()
                    {
                        app.add_library_folder();
                    }
                    if widgets::ghost_button(
                        ui,
                        app.t("Rescan now", "立即重扫"),
                        Some(Icon::Refresh),
                        palette,
                    )
                    .clicked()
                    {
                        rescan = true;
                    }
                });
                ui.add_space(8.0);
                let mut hierarchical = app.state.settings.folder_hierarchical;
                let before = hierarchical;
                setting_row(
                    ui,
                    palette,
                    app.t("Folder structure", "文件夹结构"),
                    app.t(
                        "Hierarchical shows folders within folders.",
                        "层级模式显示子文件夹。",
                    ),
                    |ui| {
                        let labels = [
                            (app.t("Hierarchical", "层级"), hierarchical),
                            (app.t("Linear", "平铺"), !hierarchical),
                        ];
                        if let Some(index) = common::segmented(ui, palette, &labels) {
                            hierarchical = index == 0;
                        }
                    },
                );
                if hierarchical != before {
                    app.state.settings.folder_hierarchical = hierarchical;
                    app.mark_dirty();
                }
                let mut hidden = app.state.settings.scan_hidden;
                let before = hidden;
                setting_row(
                    ui,
                    palette,
                    app.t("Scan hidden folders", "扫描隐藏文件夹"),
                    app.t(
                        "Include folders whose names start with a dot.",
                        "包括名称以点开头的文件夹。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut hidden, palette).clicked() {
                            rescan = true;
                        }
                    },
                );
                if hidden != before {
                    app.state.settings.scan_hidden = hidden;
                    app.mark_dirty();
                }
                let mut autoscan = app.state.settings.scan_on_start;
                setting_row(
                    ui,
                    palette,
                    app.t("Scan on start", "启动时扫描"),
                    app.t(
                        "Refresh the library when the app opens.",
                        "打开应用时刷新曲库。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut autoscan, palette).clicked() {
                            app.state.settings.scan_on_start = autoscan;
                            app.mark_dirty();
                        }
                    },
                );
                let mut only_included = app.state.settings.only_included;
                let before = only_included;
                setting_row(
                    ui,
                    palette,
                    app.t("Scan only chosen folders", "仅扫描指定文件夹"),
                    app.t(
                        "When on, only the include list is scanned.",
                        "开启后只扫描包含列表。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut only_included, palette).clicked() {
                            rescan = true;
                        }
                    },
                );
                if only_included != before {
                    app.state.settings.only_included = only_included;
                    app.mark_dirty();
                }
                ui.add_space(8.0);
                ui.label(
                    RichText::new(app.t("Excluded folders", "排除文件夹"))
                        .size(11.0)
                        .color(palette.text)
                        .strong(),
                );
                let excluded = app.state.excluded.clone();
                for path in excluded {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(&path).size(9.5).color(palette.muted));
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            if icons::icon_button(
                                ui,
                                Icon::Close,
                                20.0,
                                palette.danger,
                                palette.surface_hi,
                                app.t("Remove", "移除"),
                            )
                            .clicked()
                            {
                                app.state.excluded.retain(|p| p != &path);
                                rescan = true;
                            }
                        });
                    });
                }
                if widgets::ghost_button(
                    ui,
                    app.t("Exclude a folder", "排除文件夹"),
                    Some(Icon::Close),
                    palette,
                )
                .clicked()
                {
                    app.add_excluded_folder();
                }
                ui.add_space(6.0);
                ui.label(
                    RichText::new(app.t("Included folders", "包含文件夹"))
                        .size(11.0)
                        .color(palette.text)
                        .strong(),
                );
                let included = app.state.included.clone();
                for path in included {
                    ui.horizontal(|ui| {
                        ui.label(RichText::new(&path).size(9.5).color(palette.muted));
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            if icons::icon_button(
                                ui,
                                Icon::Close,
                                20.0,
                                palette.danger,
                                palette.surface_hi,
                                app.t("Remove", "移除"),
                            )
                            .clicked()
                            {
                                app.state.included.retain(|p| p != &path);
                                rescan = true;
                            }
                        });
                    });
                }
                if widgets::ghost_button(
                    ui,
                    app.t("Include a folder", "包含文件夹"),
                    Some(Icon::Plus),
                    palette,
                )
                .clicked()
                {
                    app.add_included_folder();
                }
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    let before = app.state.settings.min_duration_seconds;
                    ui.label(
                        RichText::new(app.t("Skip files shorter than", "忽略短于"))
                            .size(11.0)
                            .color(palette.text),
                    );
                    let response = ui.add(
                        egui::DragValue::new(&mut app.state.settings.min_duration_seconds)
                            .range(0..=120)
                            .suffix(" s"),
                    );
                    if response.changed() && before != app.state.settings.min_duration_seconds {
                        app.mark_dirty();
                        rescan = true;
                    }
                });
            });
            ui.add_space(12.0);
            card_frame(palette).show(ui, |ui| {
                common::section_title(ui, palette, app.t("Playback", "播放"), "");
                ui.add_space(6.0);
                let mut gapless = app.state.settings.gapless;
                setting_row(
                    ui,
                    palette,
                    app.t("Gapless playback", "无缝播放"),
                    app.t(
                        "Queue the next file before this one ends.",
                        "在当前歌曲结束前预载下一首。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut gapless, palette).clicked() {
                            app.state.settings.gapless = gapless;
                            app.mark_dirty();
                        }
                    },
                );
                let mut prev_always = app.state.settings.previous_always;
                setting_row(
                    ui,
                    palette,
                    app.t("Previous button", "上一首按钮"),
                    app.t(
                        "Always go back, or restart after 10 seconds.",
                        "总是上一首，或播放超过 10 秒后重播。",
                    ),
                    |ui| {
                        let labels = [
                            (app.t("Restart after 10s", "10 秒后重播"), !prev_always),
                            (app.t("Always previous", "总是上一首"), prev_always),
                        ];
                        if let Some(index) = common::segmented(ui, palette, &labels) {
                            prev_always = index == 1;
                            app.state.settings.previous_always = prev_always;
                            app.mark_dirty();
                        }
                    },
                );
                let mut fade_in = app.state.settings.fade_in_ms;
                setting_row(
                    ui,
                    palette,
                    app.t("Fade in on play", "播放时淡入"),
                    app.t(
                        "Off. The original's default is 0 ms.",
                        "关闭。原应用默认 0 毫秒。",
                    ),
                    |ui| {
                        let response = ui.add(
                            egui::Slider::new(&mut fade_in, 0..=FADE_MAX_MS)
                                .step_by(FADE_STEP_MS as f64)
                                .suffix(" ms"),
                        );
                        if response.changed() {
                            app.state.settings.fade_in_ms = fade_in;
                            app.mark_dirty();
                        }
                    },
                );
                let mut fade_out = app.state.settings.fade_out_ms;
                setting_row(
                    ui,
                    palette,
                    app.t("Fade out on pause", "暂停时淡出"),
                    app.t(
                        "Off. The original's default is 0 ms.",
                        "关闭。原应用默认 0 毫秒。",
                    ),
                    |ui| {
                        let response = ui.add(
                            egui::Slider::new(&mut fade_out, 0..=FADE_MAX_MS)
                                .step_by(FADE_STEP_MS as f64)
                                .suffix(" ms"),
                        );
                        if response.changed() {
                            app.state.settings.fade_out_ms = fade_out;
                            app.mark_dirty();
                        }
                    },
                );
                let mut queue_policy = app.state.settings.queue_conflict;
                setting_row(
                    ui,
                    palette,
                    app.t("Queue name conflict", "队列同名时"),
                    app.t(
                        "When creating a queue with an existing name.",
                        "新建同名队列时的处理方式。",
                    ),
                    |ui| {
                        // `jl1.i1()`: value 0 = Replace, value 1 = Merge.
                        let labels = [
                            (
                                app.t("Replace", "替换"),
                                queue_policy == ConflictPolicy::Replace,
                            ),
                            (
                                app.t("Merge", "合并"),
                                queue_policy == ConflictPolicy::Merge,
                            ),
                        ];
                        if let Some(index) = common::segmented(ui, palette, &labels) {
                            queue_policy = if index == 0 {
                                ConflictPolicy::Replace
                            } else {
                                ConflictPolicy::Merge
                            };
                            app.state.settings.queue_conflict = queue_policy;
                            app.mark_dirty();
                        }
                    },
                );
                setting_row(
                    ui,
                    palette,
                    app.t("Play counts after", "计入播放的阈值"),
                    app.t(
                        "A play is counted after this much of a track.",
                        "播放到该比例后计入一次播放。",
                    ),
                    |ui| {
                        for value in [10u32, 30, 50, 90] {
                            if widgets::chip(
                                ui,
                                &format!("{value}%"),
                                app.state.settings.min_play_percent == value,
                                palette,
                            )
                            .clicked()
                            {
                                app.state.settings.min_play_percent = value;
                                app.mark_dirty();
                            }
                        }
                    },
                );
                let mut resume = app.state.settings.resume_other_queues;
                setting_row(
                    ui,
                    palette,
                    app.t("Remember queue positions", "记住队列位置"),
                    app.t(
                        "Return to each queue where you left it.",
                        "切换队列时回到上次播放位置。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut resume, palette).clicked() {
                            app.state.settings.resume_other_queues = resume;
                            app.mark_dirty();
                        }
                    },
                );
                let mut jump = app.state.settings.jump_queues;
                setting_row(
                    ui,
                    palette,
                    app.t("Jump to the next queue", "跳转到下一个队列"),
                    app.t(
                        "When one queue ends, continue into the next.",
                        "一个队列结束后继续播放下一个。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut jump, palette).clicked() {
                            app.state.settings.jump_queues = jump;
                            app.mark_dirty();
                        }
                    },
                );
            });
            ui.add_space(12.0);
            card_frame(palette).show(ui, |ui| {
                common::section_title(
                    ui,
                    palette,
                    app.t("Lyrics", "歌词"),
                    app.t(
                        "Timed lyrics in the now-playing panel. Each song keeps its own offset.",
                        "正在播放页的同步歌词。每首歌单独保存偏移。",
                    ),
                );
                ui.add_space(6.0);
                let mut font_size = app.state.settings.lyrics_font_size;
                setting_row(
                    ui,
                    palette,
                    app.t("Font size", "字号"),
                    app.t(
                        "Base size for lyric lines; the active line is slightly larger.",
                        "歌词基础字号；当前行略大。",
                    ),
                    |ui| {
                        let response = ui.add(
                            egui::Slider::new(&mut font_size, 10.0..=24.0)
                                .step_by(0.5)
                                .suffix(" px"),
                        );
                        if response.changed() {
                            app.state.settings.lyrics_font_size = font_size;
                            app.mark_dirty();
                        }
                    },
                );
                let mut opacity = app.state.settings.lyrics_opacity;
                setting_row(
                    ui,
                    palette,
                    app.t("Opacity", "透明度"),
                    app.t("Text opacity for lyric lines.", "歌词文字的透明度。"),
                    |ui| {
                        let response =
                            ui.add(egui::Slider::new(&mut opacity, 30..=100).suffix(" %"));
                        if response.changed() {
                            app.state.settings.lyrics_opacity = opacity;
                            app.mark_dirty();
                        }
                    },
                );
                ui.add_space(2.0);
                let alpha = ((opacity as f32 / 100.0) * 255.0).round() as u8;
                ui.label(
                    RichText::new(app.t("Glass horizon, open wide", "玻璃地平线，向远处展开"))
                        .size(font_size + 1.5)
                        .color(with_alpha(palette.accent, alpha)),
                );
                ui.label(
                    RichText::new(app.t("A steady pulse beneath the floor", "地板下稳定的脉搏"))
                        .size(font_size)
                        .color(with_alpha(palette.text, alpha)),
                );
            });
            ui.add_space(12.0);
            card_frame(palette).show(ui, |ui| {
                common::section_title(ui, palette, app.t("Audio", "音频"), "");
                ui.add_space(6.0);
                let mut normalize = app.state.settings.normalize;
                setting_row(
                    ui,
                    palette,
                    app.t("ReplayGain volume", "ReplayGain 音量"),
                    app.t(
                        "Use embedded track gain tags when present.",
                        "读取文件中的音量标签。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut normalize, palette).clicked() {
                            app.state.settings.normalize = normalize;
                            app.mark_dirty();
                        }
                    },
                );
                let mut per_device = app.state.settings.eq_per_device;
                setting_row(
                    ui,
                    palette,
                    app.t("Equalizer per device", "按设备保存均衡器"),
                    app.t(
                        "Keep separate curves for speakers and headphones.",
                        "为扬声器和耳机保存不同的曲线。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut per_device, palette).clicked() {
                            app.state.settings.eq_per_device = per_device;
                            app.mark_dirty();
                        }
                    },
                );
            });
            ui.add_space(12.0);
            card_frame(palette).show(ui, |ui| {
                common::section_title(
                    ui,
                    palette,
                    app.t("Developer", "开发者"),
                    app.t(
                        "Diagnostics. Leave this off for normal use.",
                        "诊断选项，正常使用时保持关闭。",
                    ),
                );
                ui.add_space(6.0);
                let mut developer_mode = app.state.settings.developer_mode;
                setting_row(
                    ui,
                    palette,
                    app.t("Developer mode", "开发者模式"),
                    app.t(
                        "Show a console window for diagnostics. Closing it does not stop playback.",
                        "显示用于诊断的控制台窗口。关闭控制台不会停止播放。",
                    ),
                    |ui| {
                        if widgets::toggle_switch(ui, &mut developer_mode, palette).clicked() {
                            app.state.settings.developer_mode = developer_mode;
                            if developer_mode {
                                crate::console::set_enabled(true);
                                crate::console::log("Developer console enabled");
                            } else {
                                crate::console::log("Developer console disabled");
                                crate::console::set_enabled(false);
                            }
                            app.mark_dirty();
                        }
                    },
                );
            });
            ui.add_space(12.0);
            card_frame(palette).show(ui, |ui| {
                common::section_title(ui, palette, app.t("About", "关于"), "");
                ui.add_space(6.0);
                ui.label(
                    RichText::new(format!("Lilt {}", APP_VERSION))
                        .font(display_font(15.0))
                        .color(palette.text),
                );
                ui.label(
                    RichText::new(app.t(
                        "A local music player. No account, no network, no tracking.",
                        "纯本地音乐播放器。不需要账号，不联网，不追踪。",
                    ))
                    .size(11.0)
                    .color(palette.muted),
                );
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    if widgets::ghost_button(
                        ui,
                        app.t("Keyboard shortcuts", "键盘快捷键"),
                        Some(Icon::Gauge),
                        palette,
                    )
                    .clicked()
                    {
                        app.modal = Some(Modal::Shortcuts);
                    }
                    if widgets::ghost_button(
                        ui,
                        app.t("About this build", "关于此版本"),
                        Some(Icon::Sparkle),
                        palette,
                    )
                    .clicked()
                    {
                        app.modal = Some(Modal::About);
                    }
                });
                let _ = compact;
            });
        });
    if rescan {
        app.start_scan();
    }
}

fn sound_output_card(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    card_frame(palette).show(ui, |ui| {
        common::section_title(
            ui,
            palette,
            app.t("Sound output", "声音输出"),
            app.t(
                "Pick the speaker or headphones that should make sound.",
                "选择真正发声的扬声器或耳机。",
            ),
        );
        ui.add_space(8.0);
        let mut chosen_device = app.state.settings.output_device.clone();
        let default_name = AudioEngine::default_output_device_name()
            .unwrap_or_else(|| app.t("System default", "系统默认").to_owned());
        let default_label = format!("{} ({default_name})", app.t("System default", "系统默认"));
        let current_label = if chosen_device.is_empty() {
            default_label.clone()
        } else {
            chosen_device.clone()
        };
        let mut test_output = false;
        let devices = app.output_devices.clone();
        setting_row(
            ui,
            palette,
            app.t("Device", "输出设备"),
            app.t(
                "If music is silent, choose another device and press Test.",
                "如果没有声音，换一个设备再点“试听”。",
            ),
            |ui| {
                ui.horizontal(|ui| {
                    egui::ComboBox::from_id_salt("sound_output_device_top")
                        .selected_text(current_label.clone())
                        .width(210.0)
                        .show_ui(ui, |ui| {
                            ui.selectable_value(
                                &mut chosen_device,
                                String::new(),
                                default_label.clone(),
                            );
                            for device in &devices {
                                ui.selectable_value(&mut chosen_device, device.clone(), device.as_str());
                            }
                        });
                    if widgets::ghost_button(
                        ui,
                        app.t("Test", "试听"),
                        Some(Icon::Volume),
                        palette,
                    )
                    .clicked()
                    {
                        test_output = true;
                    }
                });
            },
        );
        if chosen_device != app.state.settings.output_device {
            app.set_output_device(chosen_device);
        }
        if test_output {
            app.test_audio_output();
        }
    });
}

fn search_page(app: &mut LiltApp, ui: &mut Ui, palette: &Palette) {
    let results = app.search_results();
    let query = app.search.clone();
    page_header(
        app,
        ui,
        palette,
        app.t("SEARCH", "搜索"),
        &format!("“{}”", query),
        &format!(
            "{}  ·  {}  ·  {}",
            songs_lower(app.lang(), results.songs.len()),
            albums(app.lang(), results.albums.len()),
            app.t("from the local library", "来自本地曲库")
        ),
        |_app, _ui| {},
    );
    if results.songs.is_empty()
        && results.albums.is_empty()
        && results.artists.is_empty()
        && results.folders.is_empty()
        && results.playlists.is_empty()
    {
        widgets::empty_state(
            ui,
            Icon::Search,
            app.t("No matches", "没有匹配项"),
            app.t(
                "Try another title, artist or folder name.",
                "试试其他标题、艺术家或文件夹名。",
            ),
            palette,
        );
        return;
    }
    egui::ScrollArea::vertical()
        .id_salt("search_scroll")
        .max_height(ui.available_height())
        .auto_shrink([false, false])
        .show(ui, |ui| {
            if !results.songs.is_empty() {
                common::section_title(ui, palette, app.t("Songs", "歌曲"), "");
                ui.add_space(5.0);
                let indices: Vec<usize> = results.songs.iter().take(12).copied().collect();
                let keys: Vec<String> = indices.iter().map(|i| app.songs[*i].key.clone()).collect();
                common::song_table(
                    app,
                    ui,
                    palette,
                    &indices,
                    app.t("Search results", "搜索结果"),
                    &keys,
                    true,
                    None,
                    None,
                    false,
                    false,
                );
                ui.add_space(12.0);
            }
            if !results.albums.is_empty() {
                common::section_title(ui, palette, app.t("Albums", "专辑"), "");
                ui.add_space(5.0);
                let albums = app.albums.clone();
                for index in results.albums.iter().take(8) {
                    let album = &albums[*index];
                    let (rect, response) = ui
                        .allocate_exact_size(Vec2::new(ui.available_width(), 56.0), Sense::click());
                    if response.hovered() {
                        ui.painter().rect_filled(rect, 9.0, palette.surface_hi);
                    }
                    let art = Rect::from_center_size(
                        egui::pos2(rect.left() + 26.0, rect.center().y),
                        Vec2::splat(40.0),
                    );
                    widgets::draw_cover(ui.painter(), art, &album.name, palette);
                    let clip = ui.painter().with_clip_rect(Rect::from_min_max(
                        egui::pos2(rect.left() + 54.0, rect.top() + 6.0),
                        egui::pos2(rect.right() - 14.0, rect.bottom() - 4.0),
                    ));
                    clip.text(
                        egui::pos2(rect.left() + 54.0, rect.top() + 21.0),
                        Align2::LEFT_CENTER,
                        &album.name,
                        FontId::proportional(12.5),
                        palette.text,
                    );
                    clip.text(
                        egui::pos2(rect.left() + 54.0, rect.top() + 39.0),
                        Align2::LEFT_CENTER,
                        format!(
                            "{}  ·  {}",
                            album.artist,
                            tracks(app.lang(), album.songs.len())
                        ),
                        FontId::proportional(10.0),
                        palette.muted,
                    );
                    if response.clicked() {
                        app.page = Page::Albums;
                        app.open_album(album.name.clone());
                        app.search.clear();
                    }
                }
                ui.add_space(8.0);
            }
            if !results.artists.is_empty() {
                common::section_title(ui, palette, app.t("Artists", "艺术家"), "");
                ui.add_space(4.0);
                let artists = app.artists.clone();
                for index in results.artists.iter().take(8) {
                    let artist = &artists[*index];
                    if ui
                        .add(
                            egui::Button::new(
                                RichText::new(format!(
                                    "{}  ·  {}",
                                    artist.name,
                                    tracks(app.lang(), artist.songs.len())
                                ))
                                .size(11.5)
                                .color(palette.text),
                            )
                            .fill(Color32::TRANSPARENT)
                            .frame(false),
                        )
                        .clicked()
                    {
                        app.page = Page::Artists;
                        app.open_artist(artist.name.clone());
                        app.search.clear();
                    }
                }
                ui.add_space(8.0);
            }
            if !results.folders.is_empty() {
                common::section_title(ui, palette, app.t("Folders", "文件夹"), "");
                ui.add_space(4.0);
                let folders = results.folders.clone();
                for path in folders.iter().take(8) {
                    let name = path
                        .file_name()
                        .map(|n| n.to_string_lossy().to_string())
                        .unwrap_or_else(|| path.to_string_lossy().to_string());
                    if ui
                        .add(
                            egui::Button::new(RichText::new(name).size(11.5).color(palette.text))
                                .fill(Color32::TRANSPARENT)
                                .frame(false),
                        )
                        .clicked()
                    {
                        app.open_folder(path.clone());
                        app.search.clear();
                    }
                }
                ui.add_space(8.0);
            }
            if !results.playlists.is_empty() {
                common::section_title(ui, palette, app.t("Playlists", "播放列表"), "");
                ui.add_space(4.0);
                let playlists = app.state.playlists.clone();
                for id in &results.playlists {
                    if let Some(playlist) = playlists.iter().find(|p| p.id == *id) {
                        if ui
                            .add(
                                egui::Button::new(
                                    RichText::new(format!(
                                        "{}  ·  {}",
                                        playlist.name,
                                        tracks(app.lang(), playlist.songs.len())
                                    ))
                                    .size(11.5)
                                    .color(palette.text),
                                )
                                .fill(Color32::TRANSPARENT)
                                .frame(false),
                            )
                            .clicked()
                        {
                            app.page = Page::Playlists;
                            app.open_playlist(playlist.id);
                            app.search.clear();
                        }
                    }
                }
            }
        });
}
