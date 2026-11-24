use crate::icons::{self, Icon};
use crate::model::{initials, Song};
use crate::theme::{cover_palette, with_alpha, Palette};
use eframe::egui::{
    self, Align2, Color32, FontId, Rect, Response, RichText, Sense, Shape, Stroke, StrokeKind, Ui,
    Vec2,
};

pub fn section_label(ui: &mut Ui, text: &str, palette: &Palette) {
    ui.add_space(2.0);
    ui.label(
        RichText::new(text.to_uppercase())
            .font(FontId::proportional(10.0))
            .color(palette.faint)
            .strong(),
    );
    ui.add_space(2.0);
}

pub fn nav_item(
    ui: &mut Ui,
    icon: Icon,
    label: &str,
    selected: bool,
    collapsed: bool,
    palette: &Palette,
) -> Response {
    let width = ui.available_width();
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, 32.0), Sense::click());
    let hovered = response.hovered();
    if selected {
        ui.painter().rect_filled(rect, 8.0, palette.accent_dim);
        ui.painter().rect_filled(
            Rect::from_min_size(
                egui::pos2(rect.left(), rect.center().y - 8.0),
                Vec2::new(2.5, 16.0),
            ),
            2.0,
            palette.accent,
        );
    } else if hovered {
        ui.painter().rect_filled(rect, 8.0, palette.surface_hi);
    }
    let icon_color = if selected {
        palette.accent
    } else if hovered {
        palette.text
    } else {
        palette.muted
    };
    if collapsed {
        icons::paint(
            ui.painter(),
            Rect::from_center_size(rect.center(), Vec2::splat(18.0)),
            icon,
            icon_color,
            1.5,
        );
        response.on_hover_text(label)
    } else {
        icons::paint(
            ui.painter(),
            Rect::from_min_size(
                egui::pos2(rect.left() + 12.0, rect.center().y - 8.5),
                Vec2::splat(17.0),
            ),
            icon,
            icon_color,
            1.5,
        );
        ui.painter().text(
            egui::pos2(rect.left() + 38.0, rect.center().y),
            Align2::LEFT_CENTER,
            label,
            FontId::proportional(13.0),
            if selected { palette.text } else { icon_color },
        );
        response
    }
}

pub fn primary_button(ui: &mut Ui, label: &str, icon: Option<Icon>, palette: &Palette) -> Response {
    let mut content = egui::text::LayoutJob::default();
    if icon.is_some() {
        content.append("    ", 0.0, egui::TextFormat::default());
    }
    content.append(
        label,
        0.0,
        egui::TextFormat {
            font_id: FontId::proportional(13.0),
            color: palette.on_accent,
            valign: egui::Align::Center,
            ..Default::default()
        },
    );
    let button = egui::Button::new(content)
        .fill(palette.accent)
        .stroke(Stroke::NONE)
        .corner_radius(9.0)
        .min_size(Vec2::new(0.0, 34.0));
    let response = ui.add(button);
    if let Some(icon) = icon {
        icons::paint(
            ui.painter(),
            Rect::from_center_size(
                egui::pos2(response.rect.left() + 16.0, response.rect.center().y),
                Vec2::splat(14.0),
            ),
            icon,
            palette.on_accent,
            1.6,
        );
    }
    response
}

pub fn ghost_button(ui: &mut Ui, label: &str, icon: Option<Icon>, palette: &Palette) -> Response {
    let mut content = egui::text::LayoutJob::default();
    if icon.is_some() {
        content.append("    ", 0.0, egui::TextFormat::default());
    }
    content.append(
        label,
        0.0,
        egui::TextFormat {
            font_id: FontId::proportional(12.5),
            color: palette.text,
            valign: egui::Align::Center,
            ..Default::default()
        },
    );
    let button = egui::Button::new(content)
        .fill(palette.surface_hi)
        .stroke(Stroke::new(1.0, palette.border))
        .corner_radius(9.0)
        .min_size(Vec2::new(0.0, 32.0));
    let response = ui.add(button);
    if let Some(icon) = icon {
        icons::paint(
            ui.painter(),
            Rect::from_center_size(
                egui::pos2(response.rect.left() + 15.0, response.rect.center().y),
                Vec2::splat(13.0),
            ),
            icon,
            palette.muted,
            1.5,
        );
    }
    response
}

pub fn chip(ui: &mut Ui, text: &str, selected: bool, palette: &Palette) -> Response {
    let font = FontId::proportional(12.0);
    let galley = ui.painter().layout_no_wrap(
        text.to_owned(),
        font,
        if selected {
            palette.on_accent
        } else {
            palette.muted
        },
    );
    let size = Vec2::new(galley.size().x + 22.0, 27.0);
    let (rect, response) = ui.allocate_exact_size(size, Sense::click());
    let fill = if selected {
        palette.accent
    } else if response.hovered() {
        palette.surface_hi
    } else {
        Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, 13.0, fill);
    if !selected {
        ui.painter().rect_stroke(
            rect,
            13.0,
            Stroke::new(1.0, palette.border),
            StrokeKind::Inside,
        );
    }
    ui.painter()
        .galley(rect.center() - galley.size() / 2.0, galley, palette.text);
    response
}

pub fn toggle_switch(ui: &mut Ui, value: &mut bool, palette: &Palette) -> Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::new(38.0, 22.0), Sense::click());
    if response.clicked() {
        *value = !*value;
    }
    let fill = if *value {
        palette.accent
    } else {
        palette.surface_hi
    };
    ui.painter().rect_filled(rect, 11.0, fill);
    if !*value {
        ui.painter().rect_stroke(
            rect,
            11.0,
            Stroke::new(1.0, palette.border_strong),
            StrokeKind::Inside,
        );
    }
    let knob_x = if *value {
        rect.right() - 11.0
    } else {
        rect.left() + 11.0
    };
    ui.painter().circle_filled(
        egui::pos2(knob_x, rect.center().y),
        8.0,
        if *value {
            palette.on_accent
        } else {
            palette.muted
        },
    );
    response
}

pub fn play_button(ui: &mut Ui, size: f32, playing: bool, palette: &Palette) -> Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
    let hovered = response.hovered();
    let radius = size * 0.5;
    if hovered {
        ui.painter()
            .circle_filled(rect.center(), radius + 2.0, with_alpha(palette.accent, 38));
    }
    ui.painter()
        .circle_filled(rect.center(), radius, palette.accent);
    icons::paint(
        ui.painter(),
        Rect::from_center_size(rect.center(), Vec2::splat(size * 0.46)),
        if playing { Icon::Pause } else { Icon::Play },
        palette.on_accent,
        2.0,
    );
    response
}

pub fn cover_widget(ui: &mut Ui, size: f32, seed: &str, palette: &Palette) -> Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    draw_cover(ui.painter(), rect, seed, palette);
    response
}

pub fn draw_cover(painter: &egui::Painter, rect: Rect, seed: &str, palette: &Palette) {
    let cp = cover_palette(seed, palette.accent, palette.dark);
    let radius = (rect.width() * 0.075).clamp(4.0, 18.0);
    painter.rect_filled(rect, radius, cp.deep);
    let clip = painter.with_clip_rect(rect.shrink(0.5));
    let w = rect.width();
    let h = rect.height();
    let center = egui::pos2(rect.left() + w * 0.68, rect.top() + h * 0.30);
    clip.circle_filled(center, w * 0.38, with_alpha(cp.tint, 210));
    clip.circle_filled(
        egui::pos2(rect.left() + w * 0.77, rect.top() + h * 0.24),
        w * 0.16,
        with_alpha(cp.glow, 150),
    );
    clip.circle_stroke(
        egui::pos2(rect.left() + w * 0.24, rect.top() + h * 0.74),
        w * 0.27,
        Stroke::new(w * 0.012, with_alpha(cp.ink, 60)),
    );
    clip.line_segment(
        [
            egui::pos2(rect.left() + w * 0.12, rect.top() + h * 0.64),
            egui::pos2(rect.left() + w * 0.88, rect.top() + h * 0.64),
        ],
        Stroke::new(w * 0.008, with_alpha(cp.ink, 50)),
    );
    for i in 0..4 {
        for j in 0..3 {
            let x = rect.left() + w * (0.66 + i as f32 * 0.055);
            let y = rect.top() + h * (0.72 + j as f32 * 0.055);
            clip.circle_filled(
                egui::pos2(x, y),
                (w * 0.007).max(1.0),
                with_alpha(cp.ink, 90),
            );
        }
    }
    clip.text(
        egui::pos2(rect.left() + w * 0.10, rect.bottom() - h * 0.13),
        Align2::LEFT_BOTTOM,
        initials(seed),
        crate::theme::display_font((w * 0.17).clamp(9.0, 40.0)),
        with_alpha(cp.ink, 225),
    );
    painter.rect_stroke(
        rect,
        radius,
        Stroke::new(1.0, with_alpha(Color32::WHITE, 18)),
        StrokeKind::Inside,
    );
}

pub fn cover_from_texture(
    ui: &mut Ui,
    size: f32,
    texture: &egui::TextureHandle,
    palette: &Palette,
) -> Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::splat(size), Sense::hover());
    ui.put(
        rect,
        egui::Image::new(texture)
            .fit_to_exact_size(rect.size())
            .corner_radius(8.0),
    );
    ui.painter().rect_stroke(
        rect,
        8.0,
        Stroke::new(1.0, with_alpha(Color32::WHITE, 20)),
        StrokeKind::Inside,
    );
    let _ = palette;
    response
}

pub struct SeekResponse {
    pub response: Response,
    pub changed: Option<f64>,
}

pub fn seek_bar(ui: &mut Ui, fraction: f64, palette: &Palette, height: f32) -> SeekResponse {
    let width = ui.available_width().max(10.0);
    let (rect, response) =
        ui.allocate_exact_size(Vec2::new(width, height.max(12.0)), Sense::click_and_drag());
    let rail = Rect::from_min_size(
        egui::pos2(rect.left(), rect.center().y - 1.6),
        Vec2::new(rect.width(), 3.2),
    );
    ui.painter()
        .rect_filled(rail, 2.0, with_alpha(palette.text, 24));
    let fraction = fraction.clamp(0.0, 1.0);
    let filled = Rect::from_min_size(
        rail.min,
        Vec2::new(rail.width() * fraction as f32, rail.height()),
    );
    ui.painter().rect_filled(filled, 2.0, palette.accent);
    let thumb = egui::pos2(
        rect.left() + rect.width() * fraction as f32,
        rect.center().y,
    );
    if response.hovered() || response.dragged() {
        ui.painter().circle_filled(thumb, 6.0, palette.accent);
        ui.painter().circle_filled(thumb, 2.4, palette.on_accent);
    } else {
        ui.painter().circle_filled(thumb, 4.0, palette.accent);
    }
    let mut changed = None;
    if response.clicked() || response.dragged() {
        if let Some(pos) = response.interact_pointer_pos() {
            let value = ((pos.x - rect.left()) / rect.width()).clamp(0.0, 1.0) as f64;
            changed = Some(value);
        }
    }
    SeekResponse { response, changed }
}

pub fn volume_slider(ui: &mut Ui, value: f32, palette: &Palette) -> Option<f32> {
    let width = ui.available_width().clamp(60.0, 130.0);
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, 20.0), Sense::click_and_drag());
    let rail = Rect::from_min_size(
        egui::pos2(rect.left() + 22.0, rect.center().y - 1.5),
        Vec2::new(rect.width() - 28.0, 3.0),
    );
    ui.painter()
        .rect_filled(rail, 2.0, with_alpha(palette.text, 24));
    let filled = Rect::from_min_size(
        rail.min,
        Vec2::new(rail.width() * value.clamp(0.0, 1.0), rail.height()),
    );
    ui.painter().rect_filled(filled, 2.0, palette.accent);
    ui.painter().circle_filled(
        egui::pos2(
            rail.left() + rail.width() * value.clamp(0.0, 1.0),
            rail.center().y,
        ),
        3.5,
        palette.accent,
    );
    let icon = if value <= 0.001 {
        Icon::VolumeMute
    } else {
        Icon::Volume
    };
    icons::paint(
        ui.painter(),
        Rect::from_center_size(
            egui::pos2(rect.left() + 8.0, rect.center().y),
            Vec2::splat(15.0),
        ),
        icon,
        palette.muted,
        1.4,
    );
    if response.dragged() || response.clicked() {
        if let Some(pos) = response.interact_pointer_pos() {
            return Some(((pos.x - rail.left()) / rail.width()).clamp(0.0, 1.0));
        }
    }
    None
}

pub fn vertical_fader(ui: &mut Ui, value: &mut f32, palette: &Palette, height: f32) -> bool {
    let (rect, response) = ui.allocate_exact_size(Vec2::new(26.0, height), Sense::click_and_drag());
    let track = Rect::from_min_size(
        egui::pos2(rect.center().x - 1.5, rect.top() + 8.0),
        Vec2::new(3.0, rect.height() - 16.0),
    );
    ui.painter()
        .rect_filled(track, 2.0, with_alpha(palette.text, 26));
    // The target's fallback band range is -1500..+1500 millibels = ±15 dB.
    let frac = ((*value + 15.0) / 30.0).clamp(0.0, 1.0);
    let y = track.bottom() - track.height() * frac;
    let zero = track.bottom() - track.height() * 0.5;
    ui.painter().line_segment(
        [
            egui::pos2(track.center().x, y),
            egui::pos2(track.center().x, zero),
        ],
        Stroke::new(3.0, with_alpha(palette.accent, 150)),
    );
    ui.painter().line_segment(
        [
            egui::pos2(track.left() - 2.0, zero),
            egui::pos2(track.right() + 2.0, zero),
        ],
        Stroke::new(1.0, palette.border_strong),
    );
    let thumb = egui::pos2(track.center().x, y);
    ui.painter().circle_filled(
        thumb,
        if response.hovered() || response.dragged() {
            6.0
        } else {
            5.0
        },
        palette.accent,
    );
    let mut changed = false;
    if response.dragged() || response.clicked() {
        if let Some(pos) = response.interact_pointer_pos() {
            let frac = ((track.bottom() - pos.y) / track.height()).clamp(0.0, 1.0);
            let new_value = (frac as f32 * 30.0 - 15.0).clamp(-15.0, 15.0);
            if (new_value - *value).abs() > 0.01 {
                *value = new_value;
                changed = true;
            }
        }
    }
    changed
}

pub fn eq_curve(
    ui: &mut Ui,
    gains: &[f32],
    enabled: bool,
    palette: &Palette,
    height: f32,
) -> Response {
    let width = ui.available_width();
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, height), Sense::hover());
    let bg = Rect::from_min_size(rect.min, rect.size());
    ui.painter().rect_filled(bg, 12.0, palette.surface_hi);
    let clip = ui.painter().with_clip_rect(bg);
    for i in 0..=4 {
        let y = rect.top() + rect.height() * i as f32 / 4.0;
        clip.line_segment(
            [
                egui::pos2(rect.left() + 16.0, y),
                egui::pos2(rect.right() - 16.0, y),
            ],
            Stroke::new(1.0, with_alpha(palette.text, 14)),
        );
    }
    let zero_y = rect.center().y;
    clip.line_segment(
        [
            egui::pos2(rect.left() + 16.0, zero_y),
            egui::pos2(rect.right() - 16.0, zero_y),
        ],
        Stroke::new(1.0, with_alpha(palette.text, 34)),
    );
    let inner = rect.shrink2(Vec2::new(20.0, 16.0));
    if gains.len() >= 2 {
        let mut points = Vec::new();
        for (index, gain) in gains.iter().enumerate() {
            let x = inner.left() + inner.width() * index as f32 / (gains.len() - 1) as f32;
            let y = inner.center().y - (gain / 15.0) * inner.height() * 0.48;
            points.push(egui::pos2(x, y));
        }
        let color = if enabled {
            palette.accent
        } else {
            palette.faint
        };
        clip.add(Shape::line(points.clone(), Stroke::new(2.0, color)));
        for (index, point) in points.iter().enumerate() {
            clip.circle_filled(*point, if index % 2 == 0 { 3.0 } else { 2.0 }, color);
        }
    }
    response
}

pub fn top_progress(ui: &mut Ui, value: f32, palette: &Palette) {
    let width = ui.available_width();
    let (rect, _) = ui.allocate_exact_size(Vec2::new(width, 3.0), Sense::hover());
    ui.painter()
        .rect_filled(rect, 2.0, with_alpha(palette.text, 18));
    let fill = Rect::from_min_size(
        rect.min,
        Vec2::new(rect.width() * value.clamp(0.0, 1.0), rect.height()),
    );
    ui.painter().rect_filled(fill, 2.0, palette.accent);
}

pub fn empty_state(ui: &mut Ui, icon: Icon, title: &str, body: &str, palette: &Palette) {
    ui.add_space(26.0);
    let (rect, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 100.0), Sense::hover());
    let center = egui::pos2(rect.center().x, rect.top() + 28.0);
    ui.painter()
        .circle_filled(center, 26.0, with_alpha(palette.accent, 18));
    icons::paint(
        ui.painter(),
        Rect::from_center_size(center, Vec2::splat(24.0)),
        icon,
        palette.accent,
        1.5,
    );
    ui.painter().text(
        egui::pos2(rect.center().x, rect.top() + 72.0),
        Align2::CENTER_CENTER,
        title,
        crate::theme::display_font(16.0),
        palette.text,
    );
    ui.painter().text(
        egui::pos2(rect.center().x, rect.top() + 92.0),
        Align2::CENTER_CENTER,
        body,
        FontId::proportional(12.0),
        palette.muted,
    );
    ui.add_space(24.0);
}

pub fn album_label(ui: &mut Ui, song: &Song, palette: &Palette) {
    let _ = ui;
    let _ = song;
    let _ = palette;
}

pub fn muted_text(text: &str, size: f32, palette: &Palette) -> RichText {
    RichText::new(text).size(size).color(palette.muted)
}

pub fn pill_text(text: &str, palette: &Palette, fill: Color32, color: Color32) -> RichText {
    let _ = (palette, fill);
    RichText::new(text).size(10.5).color(color).strong()
}

pub fn divider(ui: &mut Ui, palette: &Palette) {
    let width = ui.available_width();
    let (rect, _) = ui.allocate_exact_size(Vec2::new(width, 1.0), Sense::hover());
    ui.painter().line_segment(
        [rect.left_center(), rect.right_center()],
        Stroke::new(1.0, palette.border),
    );
}

pub fn set_tooltip(response: Response, text: &str) -> Response {
    if text.is_empty() {
        response
    } else {
        response.on_hover_text(text)
    }
}

pub fn gradient_background(painter: &egui::Painter, rect: Rect, top: Color32, bottom: Color32) {
    vertical_gradient(painter, rect, top, bottom);
}

pub fn vertical_gradient(painter: &egui::Painter, rect: Rect, top: Color32, bottom: Color32) {
    let mut mesh = egui::Mesh::default();
    mesh.colored_vertex(rect.left_top(), top);
    mesh.colored_vertex(rect.right_top(), top);
    mesh.colored_vertex(rect.right_bottom(), bottom);
    mesh.colored_vertex(rect.left_bottom(), bottom);
    mesh.add_triangle(0, 1, 2);
    mesh.add_triangle(0, 2, 3);
    painter.add(egui::Shape::mesh(mesh));
}
