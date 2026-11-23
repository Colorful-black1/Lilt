//! Duplicate detection for the local library.
//!
//! Two songs are treated as the same recording when their normalised title and
//! artist match and their lengths are within two seconds. The first index in
//! every group is the copy Lilt suggests keeping (highest bitrate, then largest
//! file). Size and duration are never enough on their own: constant-bitrate
//! files of the same length would otherwise look identical.

use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq)]
pub struct DuplicateCandidate {
    pub title: String,
    pub artist: String,
    pub duration: f64,
    pub size: u64,
    pub bitrate: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DuplicateGroup {
    /// Indices into the candidate slice, best copy first.
    pub songs: Vec<usize>,
}

pub fn find_duplicate_groups(candidates: &[DuplicateCandidate]) -> Vec<DuplicateGroup> {
    let mut union = UnionFind::new(candidates.len());
    let mut by_tags: HashMap<(String, String), Vec<usize>> = HashMap::new();
    for (index, candidate) in candidates.iter().enumerate() {
        if candidate.duration <= 0.0 {
            continue;
        }
        let title = normalize_text(&candidate.title);
        if title.chars().count() < 2 {
            continue;
        }
        let artist = normalize_text(&candidate.artist);
        by_tags.entry((title, artist)).or_default().push(index);
    }
    for indices in by_tags.values() {
        union_by_duration(&mut union, indices, candidates, 2.0);
    }

    let mut grouped: HashMap<usize, Vec<usize>> = HashMap::new();
    for index in 0..candidates.len() {
        grouped.entry(union.find(index)).or_default().push(index);
    }
    let mut out: Vec<DuplicateGroup> = grouped
        .into_values()
        .filter(|indices| indices.len() >= 2)
        .map(|mut indices| {
            indices.sort_by(|a, b| {
                keep_order(&candidates[*a], &candidates[*b]).then_with(|| a.cmp(b))
            });
            DuplicateGroup { songs: indices }
        })
        .collect();
    out.sort_by(|a, b| {
        let left = group_label(candidates, a);
        let right = group_label(candidates, b);
        left.cmp(&right).then_with(|| a.songs[0].cmp(&b.songs[0]))
    });
    out
}

fn group_label(candidates: &[DuplicateCandidate], group: &DuplicateGroup) -> (String, String) {
    let first = &candidates[group.songs[0]];
    (first.title.to_lowercase(), first.artist.to_lowercase())
}

fn union_by_duration(
    union: &mut UnionFind,
    indices: &[usize],
    candidates: &[DuplicateCandidate],
    tolerance: f64,
) {
    if indices.len() < 2 {
        return;
    }
    let mut sorted = indices.to_vec();
    sorted.sort_by(|a, b| {
        candidates[*a]
            .duration
            .total_cmp(&candidates[*b].duration)
            .then_with(|| a.cmp(b))
    });
    let anchor = sorted[0];
    for index in sorted.into_iter().skip(1) {
        if (candidates[index].duration - candidates[anchor].duration).abs() <= tolerance {
            union.union(anchor, index);
        }
    }
}

fn normalize_text(value: &str) -> String {
    value
        .chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn keep_order(a: &DuplicateCandidate, b: &DuplicateCandidate) -> std::cmp::Ordering {
    b.bitrate
        .cmp(&a.bitrate)
        .then_with(|| b.size.cmp(&a.size))
        .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
}

pub fn format_size(bytes: u64) -> String {
    let mb = bytes as f64 / (1024.0 * 1024.0);
    if mb >= 0.1 {
        format!("{mb:.1} MB")
    } else {
        format!("{} KB", (bytes / 1024).max(1))
    }
}

struct UnionFind {
    parent: Vec<usize>,
    rank: Vec<u8>,
}

impl UnionFind {
    fn new(len: usize) -> Self {
        Self {
            parent: (0..len).collect(),
            rank: vec![0; len],
        }
    }

    fn find(&mut self, value: usize) -> usize {
        let mut root = value;
        while self.parent[root] != root {
            root = self.parent[root];
        }
        let mut current = value;
        while self.parent[current] != current {
            let next = self.parent[current];
            self.parent[current] = root;
            current = next;
        }
        root
    }

    fn union(&mut self, a: usize, b: usize) {
        let a = self.find(a);
        let b = self.find(b);
        if a == b {
            return;
        }
        match self.rank[a].cmp(&self.rank[b]) {
            std::cmp::Ordering::Less => self.parent[a] = b,
            std::cmp::Ordering::Greater => self.parent[b] = a,
            std::cmp::Ordering::Equal => {
                self.parent[b] = a;
                self.rank[a] += 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(
        title: &str,
        artist: &str,
        duration: f64,
        size: u64,
        bitrate: u32,
    ) -> DuplicateCandidate {
        DuplicateCandidate {
            title: title.to_owned(),
            artist: artist.to_owned(),
            duration,
            size,
            bitrate,
        }
    }

    #[test]
    fn groups_near_identical_metadata_but_not_distant_lengths() {
        let groups = find_duplicate_groups(&[
            candidate("Glass Horizon", "Aurora Drift", 48.0, 1_000, 1_411),
            candidate("glass-horizon!", "Aurora  Drift", 48.4, 1_010, 1_411),
            candidate("Glass Horizon (Live)", "Aurora Drift", 52.0, 1_020, 1_411),
            candidate("Soft Static", "Aurora Drift", 52.0, 1_030, 1_411),
        ]);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].songs, vec![1, 0]);
    }

    #[test]
    fn same_size_and_length_alone_is_not_enough() {
        let groups = find_duplicate_groups(&[
            candidate("First tags", "Artist A", 40.0, 5_000_000, 320),
            candidate("Other tags", "Artist B", 40.1, 5_000_000, 320),
        ]);
        assert!(groups.is_empty());
    }

    #[test]
    fn suggests_the_highest_quality_copy_first() {
        let groups = find_duplicate_groups(&[
            candidate("Same", "Artist", 40.0, 5_000_000, 192),
            candidate("Same", "Artist", 40.2, 9_000_000, 320),
            candidate("Same", "Artist", 40.1, 7_000_000, 256),
        ]);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].songs[0], 1);
        assert_eq!(groups[0].songs[1], 2);
        assert_eq!(groups[0].songs[2], 0);
    }

    #[test]
    fn ignores_singletons_missing_durations_and_tiny_titles() {
        let groups = find_duplicate_groups(&[
            candidate("Solo", "Artist", 30.0, 0, 128),
            candidate("", "Artist", 0.0, 0, 0),
            candidate("a", "Artist", 12.0, 0, 0),
            candidate("a", "Artist", 12.0, 0, 0),
        ]);
        assert!(groups.is_empty());
    }

    #[test]
    fn formats_small_and_large_files() {
        assert_eq!(format_size(512), "1 KB");
        assert_eq!(format_size(10 * 1024 * 1024), "10.0 MB");
    }
}
