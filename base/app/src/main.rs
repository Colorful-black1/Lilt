#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

mod app;
mod audio;
mod backup;
mod console;
mod desktop;
mod duplicates;
mod i18n;
mod icon;
mod icons;
mod library;
mod lyrics_fetch;
mod model;
mod playback_source;
mod secret;
mod theme;
mod ui;
mod update;
mod widgets;

use eframe::egui;
use std::path::PathBuf;

fn main() -> eframe::Result {
    if std::env::args_os().len() > 1 {
        // Release builds use the Windows GUI subsystem, so attach to the calling
        // terminal for CLI flags such as --list-audio and --help.
        console::attach_parent_for_cli();
    }

    let mut screenshot: Option<PathBuf> = None;
    let mut size = [1180.0f32, 780.0f32];
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--screenshot" => {
                let Some(path) = args.next() else {
                    eprintln!("--screenshot needs a file path");
                    std::process::exit(2);
                };
                screenshot = Some(PathBuf::from(path));
            }
            "--size" => {
                let Some(value) = args.next() else {
                    eprintln!("--size needs a value such as 720x1280");
                    std::process::exit(2);
                };
                let parts: Vec<&str> = value.split(['x', 'X']).collect();
                if parts.len() != 2 {
                    eprintln!("--size must look like 720x1280");
                    std::process::exit(2);
                }
                let width = parts[0].parse::<f32>().unwrap_or(1180.0);
                let height = parts[1].parse::<f32>().unwrap_or(780.0);
                size = [width.max(720.0), height.max(540.0)];
            }
            "--make-demo" => {
                let Some(dir) = args.next() else {
                    eprintln!("--make-demo needs a folder");
                    std::process::exit(2);
                };
                library::generate_starter_library(std::path::Path::new(&dir));
                println!("wrote demo library to {dir}");
                return Ok(());
            }
            "--list-audio" => {
                println!("Sound outputs on this computer:");
                for (name, is_default) in audio::AudioEngine::describe_outputs() {
                    println!(
                        "{}{}",
                        name,
                        if is_default { "  (system default)" } else { "" }
                    );
                }
                return Ok(());
            }
            "--help" | "-h" => {
                println!("Lilt - offline local music player");
                println!("Usage: lilt [--screenshot FILE.png] [--size WIDTHxHEIGHT]");
                return Ok(());
            }
            other => {
                eprintln!("Unknown argument: {other}");
                std::process::exit(2);
            }
        }
    }

    let mut viewport = egui::ViewportBuilder::default()
        .with_inner_size(size)
        .with_min_inner_size([720.0, 540.0])
        .with_title("Lilt")
        .with_icon(icon::app_icon())
        .with_resizable(true);
    if screenshot.is_some() {
        viewport = viewport.with_active(false);
        #[cfg(target_os = "windows")]
        {
            viewport = viewport.with_window_level(egui::WindowLevel::AlwaysOnBottom);
        }
    }

    let options = eframe::NativeOptions {
        viewport,
        vsync: true,
        ..Default::default()
    };

    let shot = screenshot.clone();
    eframe::run_native(
        "Lilt",
        options,
        Box::new(move |cc| {
            let app = app::LiltApp::new(cc, shot.clone());
            Ok(Box::new(app))
        }),
    )
}
