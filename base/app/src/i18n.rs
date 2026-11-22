use crate::model::Language;

pub fn tr(lang: Language, en: &'static str, zh: &'static str) -> &'static str {
    match lang {
        Language::En => en,
        Language::Zh => zh,
    }
}

pub fn tracks(lang: Language, count: usize) -> String {
    match lang {
        Language::En => {
            if count == 1 {
                "1 track".to_owned()
            } else {
                format!("{count} tracks")
            }
        }
        Language::Zh => format!("{count} 首"),
    }
}

pub fn albums(lang: Language, count: usize) -> String {
    match lang {
        Language::En => {
            if count == 1 {
                "1 album".to_owned()
            } else {
                format!("{count} albums")
            }
        }
        Language::Zh => format!("{count} 张专辑"),
    }
}

pub fn playlists(lang: Language, count: usize) -> String {
    match lang {
        Language::En => {
            if count == 1 {
                "1 playlist".to_owned()
            } else {
                format!("{count} playlists")
            }
        }
        Language::Zh => format!("{count} 个播放列表"),
    }
}

pub fn queues_word(lang: Language, count: usize) -> String {
    match lang {
        Language::En => {
            if count == 1 {
                "1 queue".to_owned()
            } else {
                format!("{count} queues")
            }
        }
        Language::Zh => format!("{count} 个队列"),
    }
}

pub fn songs_lower(lang: Language, count: usize) -> String {
    match lang {
        Language::En => {
            if count == 1 {
                "1 song".to_owned()
            } else {
                format!("{count} songs")
            }
        }
        Language::Zh => format!("{count} 首歌曲"),
    }
}

pub fn total_duration(lang: Language, seconds: f64) -> String {
    let total = seconds.max(0.0).round() as u64;
    let h = total / 3600;
    let m = (total % 3600) / 60;
    match lang {
        Language::En => {
            if h > 0 {
                format!("{h} hr {m} min")
            } else {
                format!("{m} min")
            }
        }
        Language::Zh => {
            if h > 0 {
                format!("{h} 小时 {m} 分")
            } else {
                format!("{m} 分钟")
            }
        }
    }
}

pub fn when(lang: Language, ts: Option<u64>) -> String {
    let Some(ts) = ts else {
        return match lang {
            Language::En => "Not yet".to_owned(),
            Language::Zh => "尚未扫描".to_owned(),
        };
    };
    let secs = crate::model::now_secs().saturating_sub(ts);
    match lang {
        Language::En => {
            if secs < 60 {
                "Just now".to_owned()
            } else if secs < 3600 {
                format!("{} min ago", secs / 60)
            } else if secs < 86_400 {
                format!("{} hr ago", secs / 3600)
            } else {
                crate::model::format_date(Some(ts))
            }
        }
        Language::Zh => {
            if secs < 60 {
                "刚刚".to_owned()
            } else if secs < 3600 {
                format!("{} 分钟前", secs / 60)
            } else if secs < 86_400 {
                format!("{} 小时前", secs / 3600)
            } else {
                crate::model::format_date(Some(ts))
            }
        }
    }
}
