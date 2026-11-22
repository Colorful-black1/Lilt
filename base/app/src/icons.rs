use eframe::egui::{
    self, Align2, Color32, FontId, Painter, Pos2, Rect, Shape, Stroke, StrokeKind, Vec2,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Icon {
    Play,
    Pause,
    Prev,
    Next,
    Shuffle,
    Repeat,
    RepeatOne,
    Search,
    Settings,
    Heart,
    Folder,
    Music,
    Queue,
    Volume,
    VolumeMute,
    Close,
    ChevronLeft,
    ChevronRight,
    ChevronUp,
    ChevronDown,
    Plus,
    More,
    Theme,
    Refresh,
    Check,
    Grip,
    Clock,
    Tag,
    Trash,
    List,
    Grid,
    Expand,
    Download,
    Upload,
    Bookmark,
    Disc,
    Gauge,
    Edit,
    Home,
    Sparkle,
    Library,
    Locate,
}

pub fn paint(painter: &Painter, rect: Rect, icon: Icon, color: Color32, thickness: f32) {
    let c = rect.center();
    let s = rect.width().min(rect.height());
    let h = s * 0.5;
    let stroke = Stroke::new(thickness.max(1.0), color);
    let line = |a: (f32, f32), b: (f32, f32)| {
        painter.line_segment(
            [
                Pos2::new(c.x + a.0 * h, c.y + a.1 * h),
                Pos2::new(c.x + b.0 * h, c.y + b.1 * h),
            ],
            stroke,
        );
    };
    let poly = |points: &[(f32, f32)]| {
        let points: Vec<Pos2> = points
            .iter()
            .map(|(x, y)| Pos2::new(c.x + x * h, c.y + y * h))
            .collect();
        painter.add(Shape::convex_polygon(points, color, Stroke::NONE));
    };
    match icon {
        Icon::Play => poly(&[(-0.36, -0.52), (0.52, 0.0), (-0.36, 0.52)]),
        Icon::Pause => {
            painter.rect_filled(
                Rect::from_min_max(
                    Pos2::new(c.x - 0.42 * h, c.y - 0.48 * h),
                    Pos2::new(c.x - 0.12 * h, c.y + 0.48 * h),
                ),
                2.0,
                color,
            );
            painter.rect_filled(
                Rect::from_min_max(
                    Pos2::new(c.x + 0.12 * h, c.y - 0.48 * h),
                    Pos2::new(c.x + 0.42 * h, c.y + 0.48 * h),
                ),
                2.0,
                color,
            );
        }
        Icon::Prev => {
            poly(&[(-0.48, 0.0), (0.24, -0.44), (0.24, 0.44)]);
            painter.rect_filled(
                Rect::from_min_max(
                    Pos2::new(c.x - 0.54 * h, c.y - 0.44 * h),
                    Pos2::new(c.x - 0.42 * h, c.y + 0.44 * h),
                ),
                1.5,
                color,
            );
        }
        Icon::Next => {
            poly(&[(0.48, 0.0), (-0.24, -0.44), (-0.24, 0.44)]);
            painter.rect_filled(
                Rect::from_min_max(
                    Pos2::new(c.x + 0.42 * h, c.y - 0.44 * h),
                    Pos2::new(c.x + 0.54 * h, c.y + 0.44 * h),
                ),
                1.5,
                color,
            );
        }
        Icon::Shuffle => {
            let path1 = [
                Pos2::new(c.x - 0.62 * h, c.y - 0.36 * h),
                Pos2::new(c.x - 0.26 * h, c.y - 0.36 * h),
                Pos2::new(c.x + 0.30 * h, c.y + 0.34 * h),
                Pos2::new(c.x + 0.54 * h, c.y + 0.34 * h),
            ];
            let path2 = [
                Pos2::new(c.x - 0.62 * h, c.y + 0.36 * h),
                Pos2::new(c.x - 0.26 * h, c.y + 0.36 * h),
                Pos2::new(c.x + 0.30 * h, c.y - 0.34 * h),
                Pos2::new(c.x + 0.54 * h, c.y - 0.34 * h),
            ];
            painter.add(Shape::line(path1.to_vec(), stroke));
            painter.add(Shape::line(path2.to_vec(), stroke));
            for (x, y, dir) in [(0.54, 0.34, 1.0), (0.54, -0.34, 1.0)] {
                let tip = Pos2::new(c.x + x * h, c.y + y * h);
                painter.add(Shape::convex_polygon(
                    vec![
                        tip,
                        Pos2::new(tip.x - 0.15 * h, tip.y - 0.14 * h * dir),
                        Pos2::new(tip.x - 0.15 * h, tip.y + 0.14 * h * dir),
                    ],
                    color,
                    Stroke::NONE,
                ));
            }
        }
        Icon::Repeat | Icon::RepeatOne => {
            let r = Rect::from_center_size(c, Vec2::splat(s * 0.7));
            painter.rect_stroke(r, 5.0, stroke, StrokeKind::Inside);
            let tip = Pos2::new(r.right() - 1.0, r.top() + 1.0);
            painter.add(Shape::convex_polygon(
                vec![
                    tip,
                    Pos2::new(tip.x - 0.20 * h, tip.y + 0.02 * h),
                    Pos2::new(tip.x + 0.02 * h, tip.y + 0.24 * h),
                ],
                color,
                Stroke::NONE,
            ));
            if icon == Icon::RepeatOne {
                painter.text(
                    c,
                    Align2::CENTER_CENTER,
                    "1",
                    FontId::monospace(s * 0.34),
                    color,
                );
            }
        }
        Icon::Search => {
            painter.circle_stroke(Pos2::new(c.x - 0.10 * h, c.y - 0.10 * h), h * 0.38, stroke);
            line((0.20, 0.20), (0.55, 0.55));
        }
        Icon::Settings => {
            let radius = h * 0.32;
            painter.circle_stroke(c, radius, stroke);
            painter.circle_stroke(c, h * 0.10, stroke);
            for i in 0..8 {
                let angle = i as f32 * std::f32::consts::TAU / 8.0;
                let (sin, cos) = angle.sin_cos();
                line((cos * 0.46, sin * 0.46), (cos * 0.63, sin * 0.63));
            }
        }
        Icon::Heart => {
            let mut points = Vec::new();
            for i in 0..48 {
                let t = i as f32 / 48.0 * std::f32::consts::TAU;
                let x = 16.0 * t.sin().powi(3);
                let y = -(13.0 * t.cos()
                    - 5.0 * (2.0 * t).cos()
                    - 2.0 * (3.0 * t).cos()
                    - (4.0 * t).cos());
                points.push(Pos2::new(
                    c.x + x / 16.0 * h * 0.68,
                    c.y + y / 17.0 * h * 0.68,
                ));
            }
            painter.add(Shape::convex_polygon(points, color, Stroke::NONE));
        }
        Icon::Folder => {
            let tab = Rect::from_min_size(
                Pos2::new(c.x - 0.58 * h, c.y - 0.45 * h),
                Vec2::new(0.42 * h, 0.18 * h),
            );
            painter.rect_filled(tab, 2.0, color);
            let body = Rect::from_min_max(
                Pos2::new(c.x - 0.58 * h, c.y - 0.34 * h),
                Pos2::new(c.x + 0.58 * h, c.y + 0.40 * h),
            );
            painter.rect_filled(body, 3.0, color);
            painter.rect_stroke(
                body.shrink(0.0),
                3.0,
                Stroke::new(thickness * 0.8, Color32::from_black_alpha(40)),
                StrokeKind::Inside,
            );
        }
        Icon::Music => {
            let stem_x = c.x + 0.16 * h;
            painter.line_segment(
                [
                    Pos2::new(stem_x, c.y - 0.44 * h),
                    Pos2::new(stem_x, c.y + 0.24 * h),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    Pos2::new(stem_x, c.y - 0.44 * h),
                    Pos2::new(stem_x + 0.34 * h, c.y - 0.32 * h),
                ],
                stroke,
            );
            painter.circle_filled(Pos2::new(c.x - 0.04 * h, c.y + 0.28 * h), h * 0.18, color);
        }
        Icon::Queue => {
            for (i, y) in [-0.34, 0.0, 0.34].iter().enumerate() {
                let x = -0.50;
                painter.circle_filled(Pos2::new(c.x + x * h, c.y + y * h), h * 0.055, color);
                line((x + 0.18, *y), (0.50, *y));
                let _ = i;
            }
        }
        Icon::Volume | Icon::VolumeMute => {
            poly(&[
                (-0.56, -0.18),
                (-0.32, -0.18),
                (-0.06, -0.42),
                (-0.06, 0.42),
                (-0.32, 0.18),
                (-0.56, 0.18),
            ]);
            if icon == Icon::Volume {
                for radius in [0.36, 0.55] {
                    let mut points = Vec::new();
                    for i in 0..12 {
                        let angle = -0.75 + i as f32 * 1.5 / 11.0;
                        points.push(Pos2::new(
                            c.x + angle.cos() * h * radius - 0.02 * h,
                            c.y + angle.sin() * h * radius,
                        ));
                    }
                    painter.add(Shape::line(points, Stroke::new(thickness * 0.8, color)));
                }
            } else {
                line((0.18, -0.16), (0.52, 0.16));
                line((0.52, -0.16), (0.18, 0.16));
            }
        }
        Icon::Close => {
            line((-0.40, -0.40), (0.40, 0.40));
            line((0.40, -0.40), (-0.40, 0.40));
        }
        Icon::ChevronLeft => {
            line((0.16, -0.40), (-0.22, 0.0));
            line((-0.22, 0.0), (0.16, 0.40));
        }
        Icon::ChevronRight => {
            line((-0.16, -0.40), (0.22, 0.0));
            line((0.22, 0.0), (-0.16, 0.40));
        }
        Icon::ChevronUp => {
            line((-0.40, 0.16), (0.0, -0.22));
            line((0.0, -0.22), (0.40, 0.16));
        }
        Icon::ChevronDown => {
            line((-0.40, -0.16), (0.0, 0.22));
            line((0.0, 0.22), (0.40, -0.16));
        }
        Icon::Plus => {
            line((-0.36, 0.0), (0.36, 0.0));
            line((0.0, -0.36), (0.0, 0.36));
        }
        Icon::More => {
            for x in [-0.36, 0.0, 0.36] {
                painter.circle_filled(Pos2::new(c.x + x * h, c.y), h * 0.075, color);
            }
        }
        Icon::Theme => {
            painter.circle_stroke(c, h * 0.26, stroke);
            for i in 0..8 {
                let angle = i as f32 * std::f32::consts::TAU / 8.0;
                let (sin, cos) = angle.sin_cos();
                line((cos * 0.40, sin * 0.40), (cos * 0.58, sin * 0.58));
            }
        }
        Icon::Refresh => {
            let mut points = Vec::new();
            for i in 0..28 {
                let angle = 0.5 + i as f32 * 5.2 / 27.0;
                points.push(Pos2::new(
                    c.x + angle.cos() * h * 0.44,
                    c.y + angle.sin() * h * 0.44,
                ));
            }
            painter.add(Shape::line(points, stroke));
            let tip = Pos2::new(c.x + 0.44 * h, c.y + 0.22 * h);
            painter.add(Shape::convex_polygon(
                vec![
                    tip,
                    Pos2::new(tip.x - 0.18 * h, tip.y - 0.06 * h),
                    Pos2::new(tip.x - 0.03 * h, tip.y - 0.20 * h),
                ],
                color,
                Stroke::NONE,
            ));
        }
        Icon::Check => {
            line((-0.42, 0.02), (-0.10, 0.34));
            line((-0.10, 0.34), (0.44, -0.34));
        }
        Icon::Grip => {
            for x in [-0.20, 0.20] {
                for y in [-0.34, 0.0, 0.34] {
                    painter.circle_filled(Pos2::new(c.x + x * h, c.y + y * h), h * 0.06, color);
                }
            }
        }
        Icon::Clock => {
            painter.circle_stroke(c, h * 0.46, stroke);
            line((0.0, 0.0), (0.0, -0.30));
            line((0.0, 0.0), (0.24, 0.12));
        }
        Icon::Tag => {
            let mut points = Vec::new();
            points.push(Pos2::new(c.x - 0.52 * h, c.y - 0.52 * h));
            points.push(Pos2::new(c.x + 0.06 * h, c.y - 0.52 * h));
            points.push(Pos2::new(c.x + 0.52 * h, c.y - 0.04 * h));
            points.push(Pos2::new(c.x + 0.04 * h, c.y + 0.52 * h));
            points.push(Pos2::new(c.x - 0.52 * h, c.y + 0.02 * h));
            painter.add(Shape::convex_polygon(
                points,
                Color32::TRANSPARENT,
                Stroke::new(thickness, color),
            ));
            painter.circle_filled(Pos2::new(c.x - 0.26 * h, c.y - 0.26 * h), h * 0.07, color);
        }
        Icon::Trash => {
            let body = Rect::from_min_max(
                Pos2::new(c.x - 0.34 * h, c.y - 0.24 * h),
                Pos2::new(c.x + 0.34 * h, c.y + 0.48 * h),
            );
            painter.rect_stroke(body, 3.0, stroke, StrokeKind::Inside);
            line((-0.46, -0.34), (0.46, -0.34));
            line((-0.16, -0.48), (0.16, -0.48));
            line((-0.14, -0.14), (-0.14, 0.36));
            line((0.14, -0.14), (0.14, 0.36));
        }
        Icon::List => {
            for y in [-0.34, 0.0, 0.34] {
                line((-0.44, y), (0.46, y));
            }
        }
        Icon::Grid => {
            let size = Vec2::splat(h * 0.34);
            for x in [-0.42, 0.08] {
                for y in [-0.42, 0.08] {
                    painter.rect_filled(
                        Rect::from_min_size(Pos2::new(c.x + x * h, c.y + y * h), size),
                        2.0,
                        color,
                    );
                }
            }
        }
        Icon::Expand => {
            line((-0.42, -0.04), (-0.42, -0.42));
            line((-0.42, -0.42), (-0.04, -0.42));
            line((0.42, 0.04), (0.42, 0.42));
            line((0.42, 0.42), (0.04, 0.42));
        }
        Icon::Download => {
            line((0.0, -0.46), (0.0, 0.22));
            line((-0.26, -0.02), (0.0, 0.24));
            line((0.26, -0.02), (0.0, 0.24));
            line((-0.44, 0.42), (0.44, 0.42));
        }
        Icon::Upload => {
            line((0.0, 0.44), (0.0, -0.24));
            line((-0.26, 0.02), (0.0, -0.28));
            line((0.26, 0.02), (0.0, -0.28));
            line((-0.44, -0.44), (0.44, -0.44));
        }
        Icon::Bookmark => {
            let points = [
                (-0.34, -0.46),
                (0.34, -0.46),
                (0.34, 0.44),
                (0.0, 0.18),
                (-0.34, 0.44),
            ];
            poly(&points);
        }
        Icon::Disc => {
            painter.circle_stroke(c, h * 0.46, stroke);
            painter.circle_stroke(c, h * 0.12, stroke);
            painter.circle_filled(c, h * 0.04, color);
        }
        Icon::Gauge => {
            let mut points = Vec::new();
            for i in 0..20 {
                let angle = std::f32::consts::PI + i as f32 * std::f32::consts::PI / 19.0;
                points.push(Pos2::new(
                    c.x + angle.cos() * h * 0.46,
                    c.y + angle.sin() * h * 0.46 + 0.12 * h,
                ));
            }
            painter.add(Shape::line(points, stroke));
            line((0.0, 0.12), (0.26, -0.22));
        }
        Icon::Edit => {
            line((-0.44, 0.44), (-0.32, 0.10));
            line((-0.32, 0.10), (0.24, -0.46));
            line((0.24, -0.46), (0.44, -0.26));
            line((0.44, -0.26), (-0.12, 0.30));
            line((-0.12, 0.30), (-0.44, 0.44));
        }
        Icon::Home => {
            poly(&[(0.0, -0.48), (0.52, -0.02), (-0.52, -0.02)]);
            painter.rect_stroke(
                Rect::from_min_max(
                    Pos2::new(c.x - 0.34 * h, c.y - 0.04 * h),
                    Pos2::new(c.x + 0.34 * h, c.y + 0.46 * h),
                ),
                2.0,
                stroke,
                StrokeKind::Inside,
            );
        }
        Icon::Sparkle => {
            for (rot, scale) in [(0.0f32, 0.48f32), (0.785f32, 0.24f32)] {
                let points = [
                    Pos2::new(c.x + rot.cos() * scale * h, c.y + rot.sin() * scale * h),
                    Pos2::new(
                        c.x + (rot + 1.57).cos() * scale * 0.28 * h,
                        c.y + (rot + 1.57).sin() * scale * 0.28 * h,
                    ),
                    Pos2::new(c.x - rot.cos() * scale * h, c.y - rot.sin() * scale * h),
                    Pos2::new(
                        c.x - (rot + 1.57).cos() * scale * 0.28 * h,
                        c.y - (rot + 1.57).sin() * scale * 0.28 * h,
                    ),
                ];
                painter.add(Shape::convex_polygon(points.to_vec(), color, Stroke::NONE));
            }
        }
        Icon::Library => {
            painter.rect_filled(
                Rect::from_min_max(
                    Pos2::new(c.x - 0.48 * h, c.y - 0.44 * h),
                    Pos2::new(c.x - 0.32 * h, c.y + 0.44 * h),
                ),
                1.5,
                color,
            );
            painter.rect_filled(
                Rect::from_min_max(
                    Pos2::new(c.x - 0.20 * h, c.y - 0.44 * h),
                    Pos2::new(c.x - 0.04 * h, c.y + 0.44 * h),
                ),
                1.5,
                color,
            );
            line((0.14, -0.44), (0.34, 0.44));
        }
        Icon::Locate => {
            painter.circle_stroke(c, h * 0.34, stroke);
            painter.circle_filled(c, h * 0.09, color);
            line((-0.62, 0.0), (-0.40, 0.0));
            line((0.40, 0.0), (0.62, 0.0));
            line((0.0, -0.62), (0.0, -0.40));
            line((0.0, 0.40), (0.0, 0.62));
        }
    }
}

pub fn icon_button(
    ui: &mut egui::Ui,
    icon: Icon,
    size: f32,
    color: Color32,
    hover: Color32,
    tooltip: &str,
) -> egui::Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::splat(size), egui::Sense::click());
    let active = response.hovered() || response.has_focus();
    if active {
        ui.painter().rect_filled(rect.shrink(1.0), 7.0, hover);
    }
    paint(
        ui.painter(),
        Rect::from_center_size(rect.center(), Vec2::splat(size * 0.52)),
        icon,
        color,
        1.6,
    );
    if !tooltip.is_empty() {
        response.clone().on_hover_text(tooltip)
    } else {
        response
    }
}
