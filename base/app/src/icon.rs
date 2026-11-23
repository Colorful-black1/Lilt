//! The Lilt mark, drawn in code so the window and tray icon share one
//! original asset. Five soft bars over a rounded tile: an equalizer, not a
//! copy of any existing player logo.

pub const ICON_SIZE: u32 = 32;

const TILE: (u8, u8, u8) = (36, 24, 19);
const BARS: (u8, u8, u8) = (234, 165, 116);

fn inside_round_rect(
    x: f32,
    y: f32,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    radius: f32,
) -> bool {
    let center_x = (left + right) * 0.5;
    let center_y = (top + bottom) * 0.5;
    let half_w = (right - left) * 0.5;
    let half_h = (bottom - top) * 0.5;
    let qx = (x - center_x).abs() - (half_w - radius);
    let qy = (y - center_y).abs() - (half_h - radius);
    let outside = qx.max(0.0).hypot(qy.max(0.0));
    outside + qx.max(qy).min(0.0) - radius <= 0.0
}

fn bar_height(index: usize) -> f32 {
    match index {
        0 => 7.5,
        1 => 12.0,
        2 => 18.0,
        3 => 12.0,
        _ => 7.5,
    }
}

/// Row-major RGBA pixels, ready for both `egui::IconData` and `tray_icon::Icon`.
pub fn app_icon_rgba() -> Vec<u8> {
    let size = ICON_SIZE as f32;
    let samples = 4;
    let mut rgba = Vec::with_capacity((ICON_SIZE * ICON_SIZE * 4) as usize);
    let centers = [9.5f32, 13.0, 16.5, 20.0, 23.5];
    for py in 0..ICON_SIZE {
        for px in 0..ICON_SIZE {
            let mut tile_coverage = 0.0f32;
            let mut bar_coverage = 0.0f32;
            for sy in 0..samples {
                for sx in 0..samples {
                    let x = px as f32 + (sx as f32 + 0.5) / samples as f32;
                    let y = py as f32 + (sy as f32 + 0.5) / samples as f32;
                    let in_tile = inside_round_rect(x, y, 1.5, 1.5, size - 1.5, size - 1.5, 7.5);
                    let mut in_bar = false;
                    if in_tile {
                        for (index, cx) in centers.iter().enumerate() {
                            let half = bar_height(index) * 0.5;
                            if inside_round_rect(
                                x,
                                y,
                                cx - 1.15,
                                size * 0.5 - half,
                                cx + 1.15,
                                size * 0.5 + half,
                                1.15,
                            ) {
                                in_bar = true;
                                break;
                            }
                        }
                    }
                    if in_tile {
                        tile_coverage += 1.0;
                    }
                    if in_bar {
                        bar_coverage += 1.0;
                    }
                }
            }
            let total = (samples * samples) as f32;
            let tile = tile_coverage / total;
            let bar = bar_coverage / total;
            let alpha = tile;
            if alpha <= 0.0 {
                rgba.extend_from_slice(&[0, 0, 0, 0]);
                continue;
            }
            // The bars sit on top of the tile, so blend their coverage into
            // the tile colour before un-multiplying by the final alpha.
            let tile_only = (tile - bar).max(0.0);
            let mix = |tile_channel: u8, bar_channel: u8| {
                let value = (tile_channel as f32 * tile_only + bar_channel as f32 * bar)
                    / alpha.max(0.0001);
                value.round().clamp(0.0, 255.0) as u8
            };
            rgba.extend_from_slice(&[
                mix(TILE.0, BARS.0),
                mix(TILE.1, BARS.1),
                mix(TILE.2, BARS.2),
                (alpha * 255.0).round().clamp(0.0, 255.0) as u8,
            ]);
        }
    }
    rgba
}

pub fn app_icon() -> egui::IconData {
    egui::IconData {
        rgba: app_icon_rgba(),
        width: ICON_SIZE,
        height: ICON_SIZE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn icon_has_opaque_center_and_transparent_corners() {
        let rgba = app_icon_rgba();
        assert_eq!(rgba.len(), (ICON_SIZE * ICON_SIZE * 4) as usize);
        let center = ((ICON_SIZE / 2 * ICON_SIZE + ICON_SIZE / 2) * 4) as usize;
        assert!(rgba[center + 3] > 200);
        assert_eq!(rgba[3], 0);
        assert!(rgba.iter().skip(3).step_by(4).any(|alpha| *alpha > 0));
    }
}
