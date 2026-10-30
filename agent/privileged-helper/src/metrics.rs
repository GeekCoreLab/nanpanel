//! Read-only host metrics from `/proc`. The helper never guesses: it returns the raw
//! counters it read, and the control plane derives percentages from consecutive samples, so
//! a single sample can never be misinterpreted as a percentage.

use std::{fs, path::PathBuf};

use anyhow::{Context, bail};

#[derive(Clone, Debug)]
pub struct MetricsConfig {
    proc_root: PathBuf,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CpuTimes {
    pub user: u64,
    pub nice: u64,
    pub system: u64,
    pub idle: u64,
    pub iowait: u64,
    pub irq: u64,
    pub softirq: u64,
    pub steal: u64,
}

impl CpuTimes {
    pub fn total(&self) -> u64 {
        self.user
            + self.nice
            + self.system
            + self.idle
            + self.iowait
            + self.irq
            + self.softirq
            + self.steal
    }

    pub fn idle_total(&self) -> u64 {
        self.idle + self.iowait
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct MemoryInfo {
    pub total_kib: u64,
    pub available_kib: u64,
    pub free_kib: u64,
    pub buffers_kib: u64,
    pub cached_kib: u64,
}

impl MetricsConfig {
    pub fn from_environment() -> anyhow::Result<Option<Self>> {
        let root = match std::env::var_os("NANPANEL_PROC_ROOT") {
            Some(value) => PathBuf::from(value),
            None => PathBuf::from("/proc"),
        };
        if !root.is_absolute() {
            bail!("NANPANEL_PROC_ROOT must be an absolute path");
        }
        Ok(Some(Self { proc_root: root }))
    }

    #[cfg(test)]
    pub fn at(root: &std::path::Path) -> Self {
        Self {
            proc_root: root.to_path_buf(),
        }
    }

    pub fn read(&self) -> Result<String, String> {
        let stat = self.read_file("stat")?;
        let meminfo = self.read_file("meminfo")?;
        let loadavg = self.read_file("loadavg")?;
        let uptime = self.read_file("uptime")?;

        let cpu = parse_cpu_stat(&stat).ok_or("DENIED: /proc/stat has no cpu line")?;
        let memory = parse_meminfo(&meminfo).ok_or("DENIED: /proc/meminfo is missing fields")?;
        let (load1, load5, load15) =
            parse_loadavg(&loadavg).ok_or("DENIED: /proc/loadavg is invalid")?;
        let uptime_seconds = parse_uptime(&uptime).ok_or("DENIED: /proc/uptime is invalid")?;

        Ok(format!(
            "{{\"cpu\":{{\"user\":{},\"nice\":{},\"system\":{},\"idle\":{},\"iowait\":{},\
             \"irq\":{},\"softirq\":{},\"steal\":{},\"total\":{},\"idleTotal\":{}}},\
             \"memory\":{{\"totalKib\":{},\"availableKib\":{},\"freeKib\":{},\"buffersKib\":{},\
             \"cachedKib\":{}}},\"load\":{{\"one\":{},\"five\":{},\"fifteen\":{}}},\
             \"uptimeSeconds\":{}}}",
            cpu.user,
            cpu.nice,
            cpu.system,
            cpu.idle,
            cpu.iowait,
            cpu.irq,
            cpu.softirq,
            cpu.steal,
            cpu.total(),
            cpu.idle_total(),
            memory.total_kib,
            memory.available_kib,
            memory.free_kib,
            memory.buffers_kib,
            memory.cached_kib,
            load1,
            load5,
            load15,
            uptime_seconds
        ))
    }

    fn read_file(&self, name: &str) -> Result<String, String> {
        fs::read_to_string(self.proc_root.join(name))
            .with_context(|| format!("read {name}"))
            .map_err(|_| format!("DENIED: /proc/{name} cannot be read"))
    }
}

/// Reads the aggregate `cpu` line; per-core lines are intentionally ignored.
pub fn parse_cpu_stat(content: &str) -> Option<CpuTimes> {
    let line = content
        .lines()
        .find(|line| line.starts_with("cpu ") || line == &"cpu")?;
    let values: Vec<u64> = line
        .split_whitespace()
        .skip(1)
        .map(|value| value.parse::<u64>().ok())
        .collect::<Option<Vec<u64>>>()?;
    if values.len() < 4 {
        return None;
    }
    let at = |index: usize| values.get(index).copied().unwrap_or(0);
    Some(CpuTimes {
        user: at(0),
        nice: at(1),
        system: at(2),
        idle: at(3),
        iowait: at(4),
        irq: at(5),
        softirq: at(6),
        steal: at(7),
    })
}

pub fn parse_meminfo(content: &str) -> Option<MemoryInfo> {
    let field = |name: &str| -> Option<u64> {
        content
            .lines()
            .find(|line| line.starts_with(&format!("{name}:")))
            .and_then(|line| line.split_whitespace().nth(1))
            .and_then(|value| value.parse::<u64>().ok())
    };
    let total_kib = field("MemTotal")?;
    if total_kib == 0 {
        return None;
    }
    Some(MemoryInfo {
        total_kib,
        // MemAvailable is the only figure that accounts for reclaimable cache; fall back to
        // free memory on very old kernels that do not export it.
        available_kib: field("MemAvailable")
            .or_else(|| field("MemFree"))
            .unwrap_or(0),
        free_kib: field("MemFree").unwrap_or(0),
        buffers_kib: field("Buffers").unwrap_or(0),
        cached_kib: field("Cached").unwrap_or(0),
    })
}

pub fn parse_loadavg(content: &str) -> Option<(f64, f64, f64)> {
    let parts: Vec<&str> = content.split_whitespace().collect();
    if parts.len() < 3 {
        return None;
    }
    Some((
        parts[0].parse().ok()?,
        parts[1].parse().ok()?,
        parts[2].parse().ok()?,
    ))
}

pub fn parse_uptime(content: &str) -> Option<f64> {
    content.split_whitespace().next()?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const STAT: &str = "cpu  120 5 80 900 20 0 3 2 0 0\n\
                        cpu0 60 2 40 450 10 0 1 1 0 0\n\
                        intr 12345 0 0\n";

    const MEMINFO: &str = "MemTotal:       16384000 kB\n\
                           MemFree:         2048000 kB\n\
                           MemAvailable:    8192000 kB\n\
                           Buffers:          512000 kB\n\
                           Cached:          4096000 kB\n\
                           SwapTotal:             0 kB\n";

    #[test]
    fn the_aggregate_cpu_line_is_parsed_and_sums_make_sense() {
        let cpu = parse_cpu_stat(STAT).expect("cpu line");
        assert_eq!(cpu.user, 120);
        assert_eq!(cpu.iowait, 20);
        assert_eq!(cpu.steal, 2);
        assert_eq!(cpu.total(), 1130);
        assert_eq!(cpu.idle_total(), 920);
        // A per-core line must never win over the aggregate.
        assert_ne!(cpu.user, 60);
    }

    #[test]
    fn short_or_missing_cpu_lines_are_rejected() {
        assert!(parse_cpu_stat("").is_none());
        assert!(parse_cpu_stat("intr 1 2 3\n").is_none());
        assert!(parse_cpu_stat("cpu 1 2\n").is_none());
        // Kernels older than 2.6.11 do not export iowait/irq/softirq/steal: padded with zeros.
        let old = parse_cpu_stat("cpu 10 0 5 100\n").expect("cpu line");
        assert_eq!(old.iowait, 0);
        assert_eq!(old.total(), 115);
    }

    #[test]
    fn memory_uses_memavailable_and_falls_back_to_memfree() {
        let memory = parse_meminfo(MEMINFO).expect("meminfo");
        assert_eq!(memory.total_kib, 16_384_000);
        assert_eq!(memory.available_kib, 8_192_000);
        assert_eq!(memory.cached_kib, 4_096_000);

        let old = parse_meminfo("MemTotal: 1000 kB\nMemFree: 400 kB\n").expect("meminfo");
        assert_eq!(old.available_kib, 400);
        assert_eq!(old.buffers_kib, 0);
    }

    #[test]
    fn a_zero_total_memory_is_not_a_valid_sample() {
        assert!(parse_meminfo("MemTotal: 0 kB\nMemFree: 0 kB\n").is_none());
        assert!(parse_meminfo("MemFree: 10 kB\n").is_none());
    }

    #[test]
    fn load_and_uptime_are_tolerant_of_extra_columns() {
        assert_eq!(
            parse_loadavg("0.42 0.31 0.25 2/345 6789\n"),
            Some((0.42, 0.31, 0.25))
        );
        assert_eq!(parse_loadavg("0.42 0.31\n"), None);
        assert_eq!(parse_uptime("12345.67 98765.43\n"), Some(12345.67));
        assert_eq!(parse_uptime("not-a-number 1\n"), None);
    }

    #[test]
    fn a_snapshot_is_read_from_a_proc_directory() {
        let directory =
            std::env::temp_dir().join(format!("nanpanel-proc-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&directory).expect("temp dir");
        fs::write(directory.join("stat"), STAT).expect("stat");
        fs::write(directory.join("meminfo"), MEMINFO).expect("meminfo");
        fs::write(directory.join("loadavg"), "0.5 0.4 0.3 1/100 4242\n").expect("loadavg");
        fs::write(directory.join("uptime"), "1000.5 2000.0\n").expect("uptime");

        let snapshot = MetricsConfig::at(&directory).read().expect("snapshot");
        assert!(snapshot.contains("\"user\":120"));
        assert!(snapshot.contains("\"totalKib\":16384000"));
        assert!(snapshot.contains("\"one\":0.5"));
        assert!(snapshot.contains("\"uptimeSeconds\":1000.5"));
        // The JSON must parse, otherwise the control plane cannot read it.
        let parsed: serde_json::Value = serde_json::from_str(&snapshot).expect("valid json");
        assert_eq!(parsed["cpu"]["idleTotal"], 920);
        assert_eq!(parsed["memory"]["availableKib"], 8_192_000);
        let _ = fs::remove_dir_all(&directory);
    }

    #[test]
    fn a_missing_proc_file_is_refused_rather_than_zero_filled() {
        let directory =
            std::env::temp_dir().join(format!("nanpanel-proc-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&directory).expect("temp dir");
        fs::write(directory.join("stat"), STAT).expect("stat");
        let error = MetricsConfig::at(&directory).read().unwrap_err();
        assert!(error.contains("/proc/meminfo"));
        let _ = fs::remove_dir_all(&directory);
    }
}
