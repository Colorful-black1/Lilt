use crate::model::{Accent, ThemeMode};
use eframe::egui::{
    self, Color32, CornerRadius, FontData, FontDefinitions, FontFamily, FontId, Stroke, TextStyle,
    Visuals,
};
use std::sync::Arc;

/// Local wall-clock minute, used by the Day/Night theme. The target reads
/// `k_i_dstt` (default 360 = 06:00) and `k_i_dedt` (default 1079 = 17:59).
#[cfg(windows)]
pub fn local_minute_of_day() -> u32 {
    #[repr(C)]
    struct SystemTime {
        year: u16,
        month: u16,
        day_of_week: u16,
        day: u16,
        hour: u16,
        minute: u16,
        second: u16,
        milliseconds: u16,
    }

    extern "system" {
        fn GetLocalTime(lp_system_time: *mut SystemTime);
    }

    let mut time = SystemTime {
        year: 0,
        month: 0,
        day_of_week: 0,
        day: 0,
        hour: 0,
        minute: 0,
        second: 0,
        milliseconds: 0,
    };
    // SAFETY: GetLocalTime writes one SYSTEMTIME into the pointer we own.
    unsafe { GetLocalTime(&mut time) };
    time.hour as u32 * 60 + time.minute as u32
}

#[cfg(not(windows))]
pub fn local_minute_of_day() -> u32 {
    // Portable fallback: UTC. The desktop target of this rebuild is Windows,
    // where the target's local-time rule is reproduced above.
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    ((secs / 60) % (24 * 60)) as u32
}

#[derive(Clone, Copy, Debug)]
pub struct Palette {
    pub dark: bool,
    pub bg: Color32,
    pub bg_alt: Color32,
    pub sidebar: Color32,
    pub surface: Color32,
    pub surface_hi: Color32,
    pub border: Color32,
    pub border_strong: Color32,
    pub text: Color32,
    pub muted: Color32,
    pub faint: Color32,
    pub accent: Color32,
    pub accent_soft: Color32,
    pub accent_dim: Color32,
    pub on_accent: Color32,
    pub good: Color32,
    pub warn: Color32,
    pub danger: Color32,
}

pub fn accent_color(accent: Accent, dark: bool) -> Color32 {
    if dark {
        match accent {
            Accent::Ember => Color32::from_rgb(0xEA, 0xA5, 0x74),
            Accent::Sage => Color32::from_rgb(0x8E, 0xCD, 0xB4),
            Accent::Rose => Color32::from_rgb(0xE7, 0x95, 0xA3),
            Accent::Cobalt => Color32::from_rgb(0x86, 0xAB, 0xEE),
            Accent::Gold => Color32::from_rgb(0xDE, 0xBB, 0x6E),
        }
    } else {
        match accent {
            Accent::Ember => Color32::from_rgb(0xB7, 0x53, 0x2E),
            Accent::Sage => Color32::from_rgb(0x40, 0x7D, 0x68),
            Accent::Rose => Color32::from_rgb(0xAE, 0x48, 0x5C),
            Accent::Cobalt => Color32::from_rgb(0x3B, 0x62, 0xA3),
            Accent::Gold => Color32::from_rgb(0x95, 0x71, 0x24),
        }
    }
}

impl Palette {
    pub fn new(mode: ThemeMode, accent: Accent, system_dark: bool, daylight: bool) -> Self {
        let resolved = match mode {
            ThemeMode::Light => ThemeMode::Light,
            ThemeMode::Dark => ThemeMode::Dark,
            ThemeMode::Black => ThemeMode::Black,
            ThemeMode::DayNight => {
                if daylight {
                    ThemeMode::Light
                } else {
                    ThemeMode::Dark
                }
            }
            ThemeMode::System => {
                if system_dark {
                    ThemeMode::Dark
                } else {
                    ThemeMode::Light
                }
            }
        };
        let dark = matches!(resolved, ThemeMode::Dark | ThemeMode::Black);
        let accent = accent_color(accent, dark);
        match resolved {
            ThemeMode::Light => Palette {
                dark: false,
                bg: Color32::from_rgb(0xF5, 0xF1, 0xE9),
                bg_alt: Color32::from_rgb(0xEE, 0xE8, 0xDC),
                sidebar: Color32::from_rgb(0xF0, 0xEB, 0xE1),
                surface: Color32::from_rgb(0xFF, 0xFC, 0xF6),
                surface_hi: Color32::from_rgb(0xF5, 0xEF, 0xE4),
                border: Color32::from_rgb(0xE1, 0xD7, 0xC7),
                border_strong: Color32::from_rgb(0xCF, 0xC2, 0xAE),
                text: Color32::from_rgb(0x24, 0x1F, 0x19),
                muted: Color32::from_rgb(0x78, 0x70, 0x65),
                faint: Color32::from_rgb(0xA6, 0x9D, 0x90),
                accent,
                accent_soft: with_alpha(accent, 26),
                accent_dim: with_alpha(accent, 12),
                on_accent: Color32::from_rgb(0xFF, 0xFC, 0xF6),
                good: Color32::from_rgb(0x3F, 0x7D, 0x5A),
                warn: Color32::from_rgb(0xA0, 0x6B, 0x1F),
                danger: Color32::from_rgb(0xA8, 0x3F, 0x38),
            },
            ThemeMode::Black => Palette {
                dark: true,
                bg: Color32::from_rgb(0x03, 0x03, 0x04),
                bg_alt: Color32::from_rgb(0x00, 0x00, 0x00),
                sidebar: Color32::from_rgb(0x00, 0x00, 0x00),
                surface: Color32::from_rgb(0x0C, 0x0C, 0x0E),
                surface_hi: Color32::from_rgb(0x15, 0x15, 0x18),
                border: Color32::from_rgb(0x23, 0x23, 0x27),
                border_strong: Color32::from_rgb(0x35, 0x35, 0x3B),
                text: Color32::from_rgb(0xF1, 0xEE, 0xE7),
                muted: Color32::from_rgb(0x9A, 0x9A, 0xA1),
                faint: Color32::from_rgb(0x5F, 0x5F, 0x66),
                accent,
                accent_soft: with_alpha(accent, 30),
                accent_dim: with_alpha(accent, 14),
                on_accent: Color32::from_rgb(0x12, 0x0D, 0x0A),
                good: Color32::from_rgb(0x85, 0xC7, 0x9E),
                warn: Color32::from_rgb(0xD8, 0xAF, 0x68),
                danger: Color32::from_rgb(0xE1, 0x7A, 0x72),
            },
            _ => Palette {
                dark: true,
                bg: Color32::from_rgb(0x0F, 0x11, 0x15),
                bg_alt: Color32::from_rgb(0x0B, 0x0D, 0x11),
                sidebar: Color32::from_rgb(0x0B, 0x0D, 0x11),
                surface: Color32::from_rgb(0x17, 0x1A, 0x20),
                surface_hi: Color32::from_rgb(0x1E, 0x22, 0x2B),
                border: Color32::from_rgb(0x27, 0x2C, 0x36),
                border_strong: Color32::from_rgb(0x38, 0x3F, 0x4C),
                text: Color32::from_rgb(0xF2, 0xEF, 0xE8),
                muted: Color32::from_rgb(0x97, 0x9E, 0xAB),
                faint: Color32::from_rgb(0x64, 0x6B, 0x78),
                accent,
                accent_soft: with_alpha(accent, 30),
                accent_dim: with_alpha(accent, 14),
                on_accent: Color32::from_rgb(0x14, 0x0F, 0x0B),
                good: Color32::from_rgb(0x83, 0xC7, 0x9C),
                warn: Color32::from_rgb(0xD9, 0xAE, 0x66),
                danger: Color32::from_rgb(0xE2, 0x7A, 0x72),
            },
        }
    }

    pub fn radius(&self, r: u8) -> CornerRadius {
        CornerRadius::same(r)
    }

    pub fn stroke(&self, width: f32) -> Stroke {
        Stroke::new(width, self.border)
    }
}

pub fn with_alpha(color: Color32, alpha: u8) -> Color32 {
    Color32::from_rgba_unmultiplied(color.r(), color.g(), color.b(), alpha)
}

pub fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let t = t.clamp(0.0, 1.0);
    let lerp = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
    Color32::from_rgba_unmultiplied(
        lerp(a.r(), b.r()),
        lerp(a.g(), b.g()),
        lerp(a.b(), b.b()),
        lerp(a.a(), b.a()),
    )
}

pub fn display_font(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name("display".into()))
}

pub fn body_font(size: f32) -> FontId {
    FontId::proportional(size)
}

pub fn install_fonts(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();
    let mut body: Option<Vec<u8>> = None;
    let mut display: Option<Vec<u8>> = None;
    let mut cjk: Option<Vec<u8>> = None;

    let candidates = [
        (
            "C:/Windows/Fonts/segoeui.ttf",
            "C:/Windows/Fonts/seguisb.ttf",
            "C:/Windows/Fonts/Deng.ttf",
        ),
        (
            "/System/Library/Fonts/Supplemental/Avenir Next.ttc",
            "/System/Library/Fonts/Supplemental/Avenir Next.ttc",
            "/System/Library/Fonts/PingFang.ttc",
        ),
        (
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        ),
    ];
    for (body_path, display_path, cjk_path) in candidates {
        if let Ok(bytes) = std::fs::read(body_path) {
            body = Some(bytes);
        }
        if let Ok(bytes) = std::fs::read(display_path) {
            display = Some(bytes);
        }
        if let Ok(bytes) = std::fs::read(cjk_path) {
            cjk = Some(bytes);
        }
        if body.is_some() {
            break;
        }
    }
    if body.is_none() && cjk.is_none() {
        return;
    }

    if let Some(bytes) = body {
        fonts
            .font_data
            .insert("body".to_owned(), Arc::new(FontData::from_owned(bytes)));
    }
    if let Some(bytes) = display {
        fonts
            .font_data
            .insert("display".to_owned(), Arc::new(FontData::from_owned(bytes)));
    }
    if let Some(bytes) = cjk {
        fonts
            .font_data
            .insert("cjk".to_owned(), Arc::new(FontData::from_owned(bytes)));
    }

    let mut proportional = Vec::new();
    if fonts.font_data.contains_key("body") {
        proportional.push("body".to_owned());
    }
    if fonts.font_data.contains_key("cjk") {
        proportional.push("cjk".to_owned());
    }
    if proportional.is_empty() {
        proportional = fonts
            .families
            .get(&FontFamily::Proportional)
            .cloned()
            .unwrap_or_default();
    }
    fonts
        .families
        .insert(FontFamily::Proportional, proportional.clone());

    let mut display_family = Vec::new();
    if fonts.font_data.contains_key("display") {
        display_family.push("display".to_owned());
    } else {
        display_family.extend(proportional.clone());
    }
    if fonts.font_data.contains_key("cjk") {
        display_family.push("cjk".to_owned());
    }
    fonts
        .families
        .insert(FontFamily::Name("display".into()), display_family);

    let mut mono = fonts
        .families
        .get(&FontFamily::Monospace)
        .cloned()
        .unwrap_or_default();
    if fonts.font_data.contains_key("cjk") {
        mono.push("cjk".to_owned());
    }
    fonts.families.insert(FontFamily::Monospace, mono);

    ctx.set_fonts(fonts);
}

pub fn apply_style(ctx: &egui::Context, palette: Palette) {
    let mut style = (*ctx.style()).clone();
    style.text_styles = [
        (TextStyle::Heading, display_font(22.0)),
        (TextStyle::Body, body_font(13.5)),
        (TextStyle::Monospace, FontId::monospace(12.5)),
        (TextStyle::Button, body_font(13.0)),
        (TextStyle::Small, body_font(11.5)),
    ]
    .into();
    style.spacing.item_spacing = egui::vec2(9.0, 8.0);
    style.spacing.button_padding = egui::vec2(12.0, 7.0);
    style.spacing.interact_size = egui::vec2(30.0, 30.0);
    style.spacing.slider_width = 150.0;
    style.spacing.slider_rail_height = 4.0;
    style.spacing.scroll.bar_width = 6.0;
    style.spacing.scroll.floating = true;
    style.visuals = Visuals::dark();
    style.visuals.dark_mode = palette.dark;
    style.visuals.override_text_color = Some(palette.text);
    style.visuals.panel_fill = palette.bg;
    style.visuals.window_fill = palette.surface;
    style.visuals.extreme_bg_color = palette.surface_hi;
    style.visuals.faint_bg_color = palette.surface_hi;
    style.visuals.code_bg_color = palette.surface_hi;
    style.visuals.selection.bg_fill = palette.accent_soft;
    style.visuals.selection.stroke = Stroke::new(1.0, palette.accent);
    style.visuals.hyperlink_color = palette.accent;
    style.visuals.window_stroke = Stroke::new(1.0, palette.border);
    style.visuals.window_corner_radius = CornerRadius::same(14);
    style.visuals.window_shadow = egui::epaint::Shadow::NONE;
    style.visuals.popup_shadow = egui::epaint::Shadow::NONE;

    let radius = CornerRadius::same(9);
    style.visuals.widgets.noninteractive.bg_fill = Color32::TRANSPARENT;
    style.visuals.widgets.noninteractive.weak_bg_fill = Color32::TRANSPARENT;
    style.visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0, palette.border);
    style.visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.noninteractive.corner_radius = radius;
    style.visuals.widgets.inactive.bg_fill = palette.surface_hi;
    style.visuals.widgets.inactive.weak_bg_fill = palette.surface_hi;
    style.visuals.widgets.inactive.bg_stroke = Stroke::new(1.0, palette.border);
    style.visuals.widgets.inactive.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.inactive.corner_radius = radius;
    style.visuals.widgets.hovered.bg_fill = palette.accent_dim;
    style.visuals.widgets.hovered.weak_bg_fill = palette.accent_soft;
    style.visuals.widgets.hovered.bg_stroke = Stroke::new(1.0, palette.border_strong);
    style.visuals.widgets.hovered.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.hovered.corner_radius = radius;
    style.visuals.widgets.active.bg_fill = palette.accent_soft;
    style.visuals.widgets.active.weak_bg_fill = palette.accent_soft;
    style.visuals.widgets.active.bg_stroke = Stroke::new(1.0, palette.accent);
    style.visuals.widgets.active.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.active.corner_radius = radius;
    style.visuals.widgets.open.bg_fill = palette.surface_hi;
    style.visuals.widgets.open.weak_bg_fill = palette.surface_hi;
    style.visuals.widgets.open.bg_stroke = Stroke::new(1.0, palette.border_strong);
    style.visuals.widgets.open.fg_stroke = Stroke::new(1.0, palette.text);
    style.visuals.widgets.open.corner_radius = radius;
    style.animation_time = 0.12;
    ctx.set_style(style);
}

#[derive(Clone, Copy, Debug)]
pub struct CoverPalette {
    pub base: Color32,
    pub deep: Color32,
    pub tint: Color32,
    pub glow: Color32,
    pub ink: Color32,
    pub stripe: Color32,
}

fn hash_str(text: &str) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in text.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100_0000_01b3);
    }
    hash
}

fn hsv(h: f32, s: f32, v: f32) -> Color32 {
    let h = h.rem_euclid(360.0);
    let c = v * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = v - c;
    let (r, g, b) = if h < 60.0 {
        (c, x, 0.0)
    } else if h < 120.0 {
        (x, c, 0.0)
    } else if h < 180.0 {
        (0.0, c, x)
    } else if h < 240.0 {
        (0.0, x, c)
    } else if h < 300.0 {
        (x, 0.0, c)
    } else {
        (c, 0.0, x)
    };
    Color32::from_rgb(
        ((r + m) * 255.0) as u8,
        ((g + m) * 255.0) as u8,
        ((b + m) * 255.0) as u8,
    )
}

pub fn cover_palette(seed: &str, accent: Color32, dark: bool) -> CoverPalette {
    let hash = hash_str(seed);
    let hue = (hash % 360) as f32;
    let hue2 = (hue + 38.0 + ((hash >> 9) % 40) as f32) % 360.0;
    let base = hsv(hue, 0.32, if dark { 0.20 } else { 0.24 });
    let deep = hsv(hue, 0.42, if dark { 0.11 } else { 0.14 });
    let tint = hsv(hue2, 0.58, if dark { 0.82 } else { 0.68 });
    let glow = mix(tint, accent, 0.35);
    CoverPalette {
        base,
        deep,
        tint,
        glow,
        ink: Color32::from_rgb(0xF7, 0xF2, 0xE9),
        stripe: with_alpha(Color32::from_rgb(0xFF, 0xFF, 0xFF), 26),
    }
}
