use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use memchr::memmem;
use serde_json::Value;
use std::fmt::Write as FmtWrite;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write as IoWrite};

fn append_record(out: &mut String, tag: char, meta: &str, body: &str) {
    let meta_utf16 = meta.encode_utf16().count();
    let body_utf16 = body.encode_utf16().count();
    let _ = writeln!(out, "{} {} {}", tag, meta_utf16, body_utf16);
    out.push_str(meta);
    out.push_str(body);
}

fn is_bullet_line(trimmed: &str) -> Option<&str> {
    let bytes = trimmed.as_bytes();
    if bytes.len() >= 2 && (bytes[0] == b'-' || bytes[0] == b'*' || bytes[0] == b'+') {
        let rest = &trimmed[1..];
        let after_ws = rest.trim_start_matches(|c: char| c == ' ' || c == '\t');
        if after_ws.len() < rest.len() {
            return Some(after_ws);
        }
    }
    None
}

fn is_numbered_line(trimmed: &str) -> Option<(&str, &str)> {
    let bytes = trimmed.as_bytes();
    let mut i = 0;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i > 0 && i + 1 < bytes.len() && bytes[i] == b'.' {
        let rest = &trimmed[i + 1..];
        let after_ws = rest.trim_start_matches(|c: char| c == ' ' || c == '\t');
        if after_ws.len() < rest.len() {
            return Some((&trimmed[..i], after_ws));
        }
    }
    None
}

fn parse_table_cells(line: &str) -> Vec<String> {
    let mut trimmed = line.trim();
    if let Some(stripped) = trimmed.strip_prefix('|') {
        trimmed = stripped;
    }
    if trimmed.ends_with('|') {
        let backslashes = trimmed[..trimmed.len() - 1]
            .bytes()
            .rev()
            .take_while(|&b| b == b'\\')
            .count();
        if backslashes % 2 == 0 {
            trimmed = &trimmed[..trimmed.len() - 1];
        }
    }
    let mut cells = Vec::new();
    let mut current = String::new();
    let mut chars = trimmed.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.peek() {
                Some(&'|') => {
                    chars.next();
                    current.push('|');
                }
                Some(&'\\') => {
                    chars.next();
                    current.push('\\');
                }
                _ => current.push('\\'),
            }
        } else if c == '|' {
            cells.push(current.trim().to_string());
            current.clear();
        } else {
            current.push(c);
        }
    }
    cells.push(current.trim().to_string());
    cells
}

fn parse_table_delimiter(line: &str) -> Option<Vec<char>> {
    let trimmed = line.trim();
    if !trimmed.contains('|') {
        return None;
    }
    let cells = parse_table_cells(trimmed);
    if cells.is_empty() {
        return None;
    }
    let mut aligns = Vec::with_capacity(cells.len());
    for cell in cells {
        if cell.is_empty() {
            return None;
        }
        let left = cell.starts_with(':');
        let right = cell.ends_with(':');
        let inner_start = if left { 1 } else { 0 };
        let inner_end = if right && cell.len() > inner_start {
            cell.len() - 1
        } else {
            cell.len()
        };
        let dashes = &cell[inner_start..inner_end];
        if dashes.is_empty() || !dashes.bytes().all(|b| b == b'-') {
            return None;
        }
        let align = match (left, right) {
            (true, true) => 'C',
            (false, true) => 'R',
            _ => 'L',
        };
        aligns.push(align);
    }
    Some(aligns)
}

fn try_table_start(lines: &[&str], idx: usize) -> Option<(Vec<String>, Vec<char>)> {
    if idx + 1 >= lines.len() {
        return None;
    }
    let header_line = lines[idx].trim();
    if !header_line.contains('|') || header_line.starts_with("```") {
        return None;
    }
    let aligns = parse_table_delimiter(lines[idx + 1])?;
    let headers = parse_table_cells(header_line);
    if headers.is_empty() {
        return None;
    }
    Some((headers, aligns))
}

fn parse_heading_line(trimmed: &str) -> Option<(usize, &str)> {
    if !trimmed.starts_with('#') {
        return None;
    }
    let level = trimmed.bytes().take_while(|&b| b == b'#').count();
    if (1..=6).contains(&level) && trimmed.as_bytes().get(level) == Some(&b' ') {
        Some((level, trimmed[level..].trim()))
    } else {
        None
    }
}

fn is_paragraph_breaker(trimmed: &str) -> bool {
    trimmed.is_empty()
        || trimmed.starts_with("```")
        || parse_heading_line(trimmed).is_some()
        || trimmed.starts_with('>')
        || trimmed == "---"
        || trimmed == "***"
        || trimmed == "___"
        || is_bullet_line(trimmed).is_some()
        || is_numbered_line(trimmed).is_some()
}

fn is_paragraph_breaker_at(lines: &[&str], idx: usize) -> bool {
    let trimmed = lines[idx].trim();
    is_paragraph_breaker(trimmed) || try_table_start(lines, idx).is_some()
}

fn sanitize_table_cell(cell: &str) -> String {
    if !cell.contains('\x1E') && !cell.contains('\x1F') {
        return cell.to_string();
    }
    cell.replace(['\x1E', '\x1F'], " ")
}

fn parse_markdown_fast(content: &str) -> String {
    let lines: Vec<&str> = content.lines().collect();
    let mut out = String::with_capacity(content.len() + 64);
    let mut i = 0;

    while i < lines.len() {
        let line = lines[i];
        let trimmed = line.trim();

        // Code fence
        if let Some(rest) = trimmed.strip_prefix("```") {
            let language = rest.trim();
            let mut code = String::new();
            i += 1;
            let mut first = true;
            while i < lines.len() && !lines[i].trim().starts_with("```") {
                if !first {
                    code.push('\n');
                }
                code.push_str(lines[i]);
                first = false;
                i += 1;
            }
            if i < lines.len() {
                i += 1;
            }
            append_record(&mut out, 'C', language, &code);
            continue;
        }

        // Divider
        if trimmed == "---" || trimmed == "***" || trimmed == "___" {
            append_record(&mut out, 'D', "", "");
            i += 1;
            continue;
        }

        // Heading
        if let Some((level, text)) = parse_heading_line(trimmed) {
            let level_str = match level {
                1 => "1",
                2 => "2",
                3 => "3",
                4 => "4",
                5 => "5",
                _ => "6",
            };
            append_record(&mut out, 'H', level_str, text);
            i += 1;
            continue;
        }

        // BlockQuote
        if trimmed.starts_with('>') {
            let mut quote = String::new();
            let mut first = true;
            while i < lines.len() {
                let t = lines[i].trim();
                if let Some(after) = t.strip_prefix('>') {
                    if !first {
                        quote.push(' ');
                    }
                    quote.push_str(after.trim());
                    first = false;
                    i += 1;
                } else {
                    break;
                }
            }
            append_record(&mut out, 'Q', "", &quote);
            continue;
        }

        // Bullet list item & GFM task list (- [ ] / - [x])
        if let Some(text) = is_bullet_line(trimmed) {
            if let Some(rest) = text.strip_prefix("[ ] ") {
                append_record(&mut out, 'X', "0", rest.trim_start());
            } else if let Some(rest) = text.strip_prefix("[x] ").or_else(|| text.strip_prefix("[X] ")) {
                append_record(&mut out, 'X', "1", rest.trim_start());
            } else if text == "[ ]" {
                append_record(&mut out, 'X', "0", "");
            } else if text == "[x]" || text == "[X]" {
                append_record(&mut out, 'X', "1", "");
            } else {
                append_record(&mut out, 'L', "•", text);
            }
            i += 1;
            continue;
        }

        // Numbered list item
        if let Some((num, text)) = is_numbered_line(trimmed) {
            let mut bullet = String::with_capacity(num.len() + 1);
            bullet.push_str(num);
            bullet.push('.');
            append_record(&mut out, 'L', &bullet, text);
            i += 1;
            continue;
        }

        // Empty line
        if trimmed.is_empty() {
            i += 1;
            continue;
        }

        // GFM Table
        if let Some((mut headers, mut aligns)) = try_table_start(&lines, i) {
            let col_count = headers.len().max(aligns.len());
            while headers.len() < col_count {
                headers.push(String::new());
            }
            while aligns.len() < col_count {
                aligns.push('L');
            }
            i += 2; // consume header + delimiter

            let mut encoded_rows = String::new();
            for (c_idx, cell) in headers.iter().take(col_count).enumerate() {
                if c_idx > 0 {
                    encoded_rows.push('\x1F');
                }
                encoded_rows.push_str(&sanitize_table_cell(cell));
            }

            while i < lines.len() {
                let row_trim = lines[i].trim();
                if row_trim.is_empty()
                    || !row_trim.contains('|')
                    || row_trim.starts_with("```")
                    || parse_heading_line(row_trim).is_some()
                    || row_trim.starts_with('>')
                {
                    break;
                }
                let mut row_cells = parse_table_cells(row_trim);
                while row_cells.len() < col_count {
                    row_cells.push(String::new());
                }
                encoded_rows.push('\x1E');
                for (c_idx, cell) in row_cells.iter().take(col_count).enumerate() {
                    if c_idx > 0 {
                        encoded_rows.push('\x1F');
                    }
                    encoded_rows.push_str(&sanitize_table_cell(cell));
                }
                i += 1;
            }

            let mut meta = String::with_capacity(col_count * 2);
            for (c_idx, a) in aligns.iter().enumerate() {
                if c_idx > 0 {
                    meta.push(',');
                }
                meta.push(*a);
            }
            append_record(&mut out, 'T', &meta, &encoded_rows);
            continue;
        }

        // Paragraph
        let mut para = String::new();
        let mut first = true;
        while i < lines.len() {
            let cur_trim = lines[i].trim();
            if !first && is_paragraph_breaker_at(&lines, i) {
                break;
            }
            if !first {
                para.push(' ');
            }
            para.push_str(cur_trim);
            first = false;
            i += 1;
        }
        if !para.is_empty() {
            append_record(&mut out, 'P', "", &para);
        }
    }

    out
}

fn quick_tail_file(path: &str, max_bytes: usize) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let len = file.metadata().ok()?.len() as usize;
    let to_read = len.min(max_bytes);
    if len > to_read {
        file.seek(SeekFrom::End(-(to_read as i64))).ok()?;
    }
    let mut buf = vec![0u8; to_read];
    file.read_exact(&mut buf).ok()?;
    Some(String::from_utf8_lossy(&buf).into_owned())
}

/// Single-pass SIMD-accelerated binary patcher for the 4 core Android/glibc fixes.
/// Returns a bitmask of applied patches (bits 0..3), or Err on I/O failure.
fn patch_core_binary(path: &str) -> Result<i32, std::io::Error> {
    const CHUNK_SIZE: usize = 4 * 1024 * 1024;
    const OVERLAP: usize = 15; // max pattern length (16) - 1
    const PATTERNS: [(&[u8], &[u8], i32); 4] = [
        (&[0xe0, 0x36, 0x80, 0xd2], &[0x00, 0x06, 0x80, 0xd2], 1 << 0), // faccessat2 -> faccessat
        (&[0x80, 0x38, 0x80, 0xd2], &[0xa0, 0x06, 0x80, 0xd2], 1 << 1), // fchmodat2 -> fchmodat
        (b"/bin/bash", b"/bin///sh", 1 << 2),                            // PTY shell
        (b"/etc/resolv.conf", b"etc//resolv.conf", 1 << 3),              // resolv.conf
    ];

    let finders: Vec<memmem::Finder<'static>> = PATTERNS
        .iter()
        .map(|(target, _, _)| memmem::Finder::new(target))
        .collect();

    let mut file = OpenOptions::new().read(true).write(true).open(path)?;
    let file_len = file.metadata()?.len();
    if file_len < 4 {
        return Ok(0);
    }

    let buf_size = ((CHUNK_SIZE + OVERLAP) as u64).min(file_len) as usize;
    let mut buf = vec![0u8; buf_size];
    let mut offset: u64 = 0;
    let mut mask: i32 = 0;
    let mut writes: Vec<(u64, &'static [u8])> = Vec::new();

    while offset < file_len {
        file.seek(SeekFrom::Start(offset))?;
        let to_read = (buf_size as u64).min(file_len - offset) as usize;
        let mut total_read = 0usize;
        while total_read < to_read {
            let n = file.read(&mut buf[total_read..to_read])?;
            if n == 0 {
                break;
            }
            total_read += n;
        }
        if total_read == 0 {
            break;
        }

        let slice = &buf[..total_read];
        writes.clear();
        for (idx, (_, repl, bit)) in PATTERNS.iter().enumerate() {
            for pos in finders[idx].find_iter(slice) {
                writes.push((offset + pos as u64, *repl));
                mask |= *bit;
            }
        }

        if !writes.is_empty() {
            for &(write_pos, repl) in &writes {
                file.seek(SeekFrom::Start(write_pos))?;
                file.write_all(repl)?;
            }
        }

        if offset + (total_read as u64) >= file_len || total_read <= OVERLAP {
            break;
        }
        offset += (total_read - OVERLAP) as u64;
    }

    Ok(mask)
}

/// Zero-regex O(n) ANSI escape & carriage-return stripper matching TerminalSessionManager.ANSI_REGEX.
fn strip_ansi_fast(input: &str) -> String {
    let bytes = input.as_bytes();
    let len = bytes.len();
    let mut out = Vec::with_capacity(len);
    let mut i = 0usize;

    while i < len {
        let b = bytes[i];
        if b == b'\r' {
            i += 1;
            continue;
        }
        if b == 0x1B && i + 1 < len {
            let next = bytes[i + 1];
            if next == b'[' {
                // CSI: ESC [ [0-?]* [ -/]* [@-~]
                let mut j = i + 2;
                while j < len && (0x30..=0x3F).contains(&bytes[j]) {
                    j += 1;
                }
                while j < len && (0x20..=0x2F).contains(&bytes[j]) {
                    j += 1;
                }
                if j < len && (0x40..=0x7E).contains(&bytes[j]) {
                    i = j + 1;
                    continue;
                }
            } else if next == b']' {
                // OSC: ESC ] [^\x07\x1b]* (\x07 | ESC \)
                let mut j = i + 2;
                while j < len && bytes[j] != 0x07 && bytes[j] != 0x1B {
                    j += 1;
                }
                if j < len && bytes[j] == 0x07 {
                    i = j + 1;
                    continue;
                } else if j + 1 < len && bytes[j] == 0x1B && bytes[j + 1] == b'\\' {
                    i = j + 2;
                    continue;
                }
            } else if (b'@'..=b'Z').contains(&next) || (b'\\' ..= b'_').contains(&next) {
                // 2-byte Fe escape sequence
                i += 2;
                continue;
            }
        }
        out.push(b);
        i += 1;
    }

    // Only ASCII bytes (< 0x80) are ever removed, so valid UTF-8 remains valid UTF-8.
    String::from_utf8(out).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned())
}

/// Fast native classifier/extractor for ACP JSON-RPC stdout lines.
/// Returns:
/// - `Some("T\n<text>")` for `agent_thought_chunk`
/// - `Some("M\n<text>")` for `agent_message_chunk`
/// - `Some("C")` for `tool_call` / `tool_call_update` (caller parses full JSONObject)
/// - `Some("R\n<id>")` for JSON-RPC response containing integer `id`
/// - `Some("I")` for valid JSON objects that can be ignored
/// - `None` for non-JSON / invalid lines
fn parse_acp_line_fast(line: &str) -> Option<String> {
    let val: Value = serde_json::from_str(line).ok()?;
    let obj = val.as_object()?;

    if let Some(method) = obj.get("method").and_then(|v| v.as_str()) {
        if method == "session/update" {
            let Some(upd) = obj.get("params").and_then(|p| p.get("update")) else {
                return Some("I".to_string());
            };
            let kind = upd.get("sessionUpdate").and_then(|v| v.as_str()).unwrap_or("");
            return match kind {
                "agent_thought_chunk" => {
                    let text = upd
                        .get("content")
                        .and_then(|c| c.get("text"))
                        .and_then(|t| t.as_str())
                        .unwrap_or("");
                    let mut out = String::with_capacity(text.len() + 2);
                    out.push_str("T\n");
                    out.push_str(text);
                    Some(out)
                }
                "agent_message_chunk" => {
                    let text = upd
                        .get("content")
                        .and_then(|c| c.get("text"))
                        .and_then(|t| t.as_str())
                        .unwrap_or("");
                    let mut out = String::with_capacity(text.len() + 2);
                    out.push_str("M\n");
                    out.push_str(text);
                    Some(out)
                }
                "tool_call" | "tool_call_update" => Some("C".to_string()),
                _ => Some("I".to_string()),
            };
        }
        return Some("I".to_string());
    }

    if let Some(id) = obj.get("id").and_then(|v| v.as_i64()) {
        return Some(format!("R\n{}", id));
    }

    Some("I".to_string())
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeParseMarkdown(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let rust_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let encoded = parse_markdown_fast(&rust_str);
    match env.new_string(encoded) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeQuickTailFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    max_bytes: jint,
) -> jstring {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let limit = if max_bytes <= 0 { 65536 } else { max_bytes as usize };
    let content = match quick_tail_file(&path_str, limit) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    match env.new_string(content) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativePatchCoreBinary(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jint {
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    match patch_core_binary(&path_str) {
        Ok(mask) => mask as jint,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeStripAnsi(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let rust_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let cleaned = strip_ansi_fast(&rust_str);
    match env.new_string(cleaned) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn append_acp_batch_record(out: &mut String, tag: char, line_idx: usize, body: &str) {
    let body_utf16 = body.encode_utf16().count();
    let _ = writeln!(out, "{} {} {}", tag, line_idx, body_utf16);
    out.push_str(body);
}

/// Batch classifier/extractor for newline-delimited ACP JSON-RPC stdout chunks.
/// Emits length-framed records (`<TAG> <LINE_IDX> <UTF16_LEN>\n<BODY>`) and skips
/// ignored / non-JSON lines so Kotlin pays a single JNI crossing per buffered burst.
fn parse_acp_batch_fast(batch: &str) -> String {
    let mut out = String::with_capacity(batch.len().min(65536) + 64);
    for (idx, raw_line) in batch.split('\n').enumerate() {
        let trimmed = raw_line.trim();
        if !trimmed.starts_with('{') {
            continue;
        }
        let Ok(val) = serde_json::from_str::<Value>(trimmed) else {
            continue;
        };
        let Some(obj) = val.as_object() else {
            continue;
        };
        if let Some(method) = obj.get("method").and_then(|v| v.as_str()) {
            if method == "session/update" {
                let Some(upd) = obj.get("params").and_then(|p| p.get("update")) else {
                    continue;
                };
                let kind = upd.get("sessionUpdate").and_then(|v| v.as_str()).unwrap_or("");
                match kind {
                    "agent_thought_chunk" => {
                        let text = upd
                            .get("content")
                            .and_then(|c| c.get("text"))
                            .and_then(|t| t.as_str())
                            .unwrap_or("");
                        append_acp_batch_record(&mut out, 'T', idx, text);
                    }
                    "agent_message_chunk" => {
                        let text = upd
                            .get("content")
                            .and_then(|c| c.get("text"))
                            .and_then(|t| t.as_str())
                            .unwrap_or("");
                        append_acp_batch_record(&mut out, 'M', idx, text);
                    }
                    "tool_call" | "tool_call_update" => {
                        append_acp_batch_record(&mut out, 'C', idx, "");
                    }
                    _ => {}
                }
            }
            continue;
        }
        if let Some(id) = obj.get("id").and_then(|v| v.as_i64()) {
            let id_str = id.to_string();
            append_acp_batch_record(&mut out, 'R', idx, &id_str);
        }
    }
    out
}

/// Computes `(big_mask, all_mask, min_khz, max_khz)` from `(cpu_id, cpuinfo_max_freq_khz)`.
///
/// Selects ALL non-LITTLE cores (`freq > min_khz`) instead of only the single highest frequency
/// so tri-cluster SoCs (e.g. 4x A510 @ 2.2 GHz + 3x A715 @ 3.2 GHz + 1x A715 Prime @ 3.35 GHz)
/// pin across all 4 performance cores rather than choking onto a single prime core.
fn compute_big_core_mask(freqs: &[(usize, u64)]) -> (u64, u64, u64, u64) {
    let mut all_mask = 0u64;
    let mut min_khz = u64::MAX;
    let mut max_khz = 0u64;

    for &(cpu, khz) in freqs {
        if cpu >= 64 || khz == 0 {
            continue;
        }
        all_mask |= 1u64 << cpu;
        if khz < min_khz {
            min_khz = khz;
        }
        if khz > max_khz {
            max_khz = khz;
        }
    }

    if all_mask == 0 {
        return (0, 0, 0, 0);
    }

    if max_khz <= min_khz {
        // Homogeneous SMP cores: no separate performance cluster to pin.
        return (0, all_mask, min_khz, max_khz);
    }

    let mut big_mask = 0u64;
    for &(cpu, khz) in freqs {
        if cpu < 64 && khz > min_khz {
            big_mask |= 1u64 << cpu;
        }
    }
    (big_mask, all_mask, min_khz, max_khz)
}

fn format_core_list(mask: u64) -> String {
    let mut out = String::new();
    for cpu in 0..64 {
        if (mask & (1u64 << cpu)) != 0 {
            if !out.is_empty() {
                out.push(',');
            }
            let _ = write!(out, "{}", cpu);
        }
    }
    out
}

fn detect_cpu_topology_string() -> String {
    let mut freqs: Vec<(usize, u64)> = Vec::new();
    if let Ok(entries) = fs::read_dir("/sys/devices/system/cpu") {
        for entry in entries.flatten() {
            let name = entry.file_name();
            let Some(name_str) = name.to_str() else {
                continue;
            };
            let Some(suffix) = name_str.strip_prefix("cpu") else {
                continue;
            };
            if suffix.is_empty() || !suffix.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            let Ok(cpu_id) = suffix.parse::<usize>() else {
                continue;
            };
            if cpu_id >= 64 {
                continue;
            }
            let freq_path = entry.path().join("cpufreq/cpuinfo_max_freq");
            let khz = fs::read_to_string(&freq_path)
                .ok()
                .and_then(|s| s.trim().parse::<u64>().ok())
                .unwrap_or(0);
            if khz > 0 {
                freqs.push((cpu_id, khz));
            }
        }
    }
    freqs.sort_by_key(|&(cpu, _)| cpu);
    let (big_mask, all_mask, min_khz, max_khz) = compute_big_core_mask(&freqs);
    let big_cores = format_core_list(big_mask);
    format!(
        "all_mask={:x};big_mask={:x};min_khz={};max_khz={};big_cores={};total_cpus={}",
        all_mask,
        big_mask,
        min_khz,
        max_khz,
        big_cores,
        freqs.len()
    )
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn set_tid_affinity(tid: i32, core_mask: u64) -> bool {
    if tid <= 0 || core_mask == 0 {
        return false;
    }
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        let max_cpus = (libc::CPU_SETSIZE as usize).min(64);
        let mut set_any = false;
        for cpu in 0..max_cpus {
            if (core_mask & (1u64 << cpu)) != 0 {
                libc::CPU_SET(cpu, &mut set);
                set_any = true;
            }
        }
        if !set_any {
            return false;
        }
        libc::sched_setaffinity(
            tid as libc::pid_t,
            std::mem::size_of::<libc::cpu_set_t>(),
            &set,
        ) == 0
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn set_tid_affinity(_tid: i32, _core_mask: u64) -> bool {
    false
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn get_tid_affinity(tid: i32) -> i64 {
    if tid <= 0 {
        return -1;
    }
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        if libc::sched_getaffinity(
            tid as libc::pid_t,
            std::mem::size_of::<libc::cpu_set_t>(),
            &mut set,
        ) != 0
        {
            return -1;
        }
        let mut mask = 0u64;
        let max_cpus = (libc::CPU_SETSIZE as usize).min(64);
        for cpu in 0..max_cpus {
            if libc::CPU_ISSET(cpu, &set) {
                mask |= 1u64 << cpu;
            }
        }
        mask as i64
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn get_tid_affinity(_tid: i32) -> i64 {
    -1
}

/// Applies `sched_setaffinity` across ALL threads of `pid` (iterating `/proc/<pid>/task/*`)
/// so multi-threaded runtimes like Go (`agy acp`) are actually pinned across all OS threads.
/// Returns the number of threads pinned (`0` if rejected by cpuset/permissions, `-1` if invalid).
fn set_process_affinity_all_threads(pid: i32, core_mask: u64) -> i32 {
    if pid <= 0 || core_mask == 0 {
        return -1;
    }
    let mut tids: Vec<i32> = Vec::new();
    let task_dir = format!("/proc/{}/task", pid);
    if let Ok(entries) = fs::read_dir(&task_dir) {
        for entry in entries.flatten() {
            if let Some(tid) = entry
                .file_name()
                .to_str()
                .and_then(|s| s.parse::<i32>().ok())
            {
                if tid > 0 {
                    tids.push(tid);
                }
            }
        }
    }
    if tids.is_empty() {
        tids.push(pid);
    }

    let mut applied = 0i32;
    for tid in tids {
        if set_tid_affinity(tid, core_mask) {
            applied += 1;
        }
    }
    applied
}

fn parse_proc_status_fields(status_text: &str) -> (String, String, u64, u32, String) {
    let mut name = String::new();
    let mut state = String::new();
    let mut vm_rss_kb = 0u64;
    let mut threads = 0u32;
    let mut cpus_allowed = String::new();

    for line in status_text.lines() {
        if let Some(val) = line.strip_prefix("Name:") {
            name = val.trim().to_string();
        } else if let Some(val) = line.strip_prefix("State:") {
            state = val.trim().to_string();
        } else if let Some(val) = line.strip_prefix("VmRSS:") {
            vm_rss_kb = val
                .split_whitespace()
                .next()
                .and_then(|n| n.parse::<u64>().ok())
                .unwrap_or(0);
        } else if let Some(val) = line.strip_prefix("Threads:") {
            threads = val.trim().parse::<u32>().unwrap_or(0);
        } else if let Some(val) = line.strip_prefix("Cpus_allowed_list:") {
            cpus_allowed = val.trim().to_string();
        }
    }

    (name, state, vm_rss_kb, threads, cpus_allowed)
}

fn read_process_stats(pid: i32) -> Option<String> {
    if pid <= 0 {
        return None;
    }
    let status_text = fs::read_to_string(format!("/proc/{}/status", pid)).ok()?;
    let (name, state, vm_rss_kb, threads, cpus_allowed) = parse_proc_status_fields(&status_text);
    let affinity_mask = get_tid_affinity(pid);
    let mut out = String::with_capacity(160);
    let _ = writeln!(out, "pid={}", pid);
    let _ = writeln!(out, "name={}", name);
    let _ = writeln!(out, "state={}", state);
    let _ = writeln!(out, "rss_kb={}", vm_rss_kb);
    let _ = writeln!(out, "threads={}", threads);
    let _ = writeln!(out, "cpus_allowed={}", cpus_allowed);
    if affinity_mask >= 0 {
        let _ = writeln!(out, "affinity_mask={:x}", affinity_mask as u64);
    }
    Some(out)
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeParseAcpLine(
    mut env: JNIEnv,
    _class: JClass,
    line: JString,
) -> jstring {
    let line_str: String = match env.get_string(&line) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let parsed = match parse_acp_line_fast(&line_str) {
        Some(s) => s,
        None => return std::ptr::null_mut(),
    };
    match env.new_string(parsed) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeParseAcpBatch(
    mut env: JNIEnv,
    _class: JClass,
    batch: JString,
) -> jstring {
    let batch_str: String = match env.get_string(&batch) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let encoded = parse_acp_batch_fast(&batch_str);
    match env.new_string(encoded) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeDetectCpuTopology(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let topo = detect_cpu_topology_string();
    match env.new_string(topo) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeSetProcessAffinity(
    _env: JNIEnv,
    _class: JClass,
    pid: jint,
    core_mask: jlong,
) -> jint {
    if core_mask <= 0 {
        return -1;
    }
    set_process_affinity_all_threads(pid, core_mask as u64) as jint
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeGetProcessAffinity(
    _env: JNIEnv,
    _class: JClass,
    pid: jint,
) -> jlong {
    get_tid_affinity(pid) as jlong
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeReadProcessStats(
    env: JNIEnv,
    _class: JClass,
    pid: jint,
) -> jstring {
    let Some(stats) = read_process_stats(pid) else {
        return std::ptr::null_mut();
    };
    match env.new_string(stats) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string("antigravity-turbo-rust 1.3.0 (native)") {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn decode_records(encoded: &str) -> Vec<(char, String, String)> {
        let utf16: Vec<u16> = encoded.encode_utf16().collect();
        let mut out = Vec::new();
        let mut cur = 0usize;
        while cur < utf16.len() {
            let nl = utf16[cur..].iter().position(|&u| u == b'\n' as u16).unwrap();
            let header = String::from_utf16(&utf16[cur..cur + nl]).unwrap();
            cur += nl + 1;
            let mut parts = header.split(' ');
            let tag = parts.next().unwrap().chars().next().unwrap();
            let meta_len: usize = parts.next().unwrap().parse().unwrap();
            let body_len: usize = parts.next().unwrap().parse().unwrap();
            let meta = String::from_utf16(&utf16[cur..cur + meta_len]).unwrap();
            cur += meta_len;
            let body = String::from_utf16(&utf16[cur..cur + body_len]).unwrap();
            cur += body_len;
            out.push((tag, meta, body));
        }
        out
    }

    #[test]
    fn parses_gfm_table_with_alignments_escapes_and_emojis() {
        let md = "Intro line\n| Kolom | Rata | Status |\n| :--- | :---: | ---: |\n| a \\| b | ok 🚀 | 100 |\n";
        let recs = decode_records(&parse_markdown_fast(md));
        assert_eq!(recs.len(), 2);
        assert_eq!(recs[0], ('P', "".to_string(), "Intro line".to_string()));
        assert_eq!(recs[1].0, 'T');
        assert_eq!(recs[1].1, "L,C,R");
        let rows: Vec<&str> = recs[1].2.split('\x1E').collect();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].split('\x1F').collect::<Vec<_>>(), vec!["Kolom", "Rata", "Status"]);
        assert_eq!(rows[1].split('\x1F').collect::<Vec<_>>(), vec!["a | b", "ok 🚀", "100"]);
    }

    #[test]
    fn parses_task_lists_and_regular_bullets() {
        let md = "- [ ] Todo\n* [x] Done lower\n+ [X] Done upper 🚀\n- Normal bullet\n";
        let recs = decode_records(&parse_markdown_fast(md));
        assert_eq!(
            recs,
            vec![
                ('X', "0".to_string(), "Todo".to_string()),
                ('X', "1".to_string(), "Done lower".to_string()),
                ('X', "1".to_string(), "Done upper 🚀".to_string()),
                ('L', "•".to_string(), "Normal bullet".to_string()),
            ]
        );
    }

    #[test]
    fn strips_ansi_escapes_preserving_utf8() {
        let raw = "\x1B[32mPASS\x1B[0m \x1B]0;title\x07Halo 🚀\r\n";
        assert_eq!(strip_ansi_fast(raw), "PASS Halo 🚀\n");
    }

    #[test]
    fn parses_acp_lines() {
        let msg = r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"text":"halo"}}}}"#;
        assert_eq!(parse_acp_line_fast(msg).as_deref(), Some("M\nhalo"));
        let thought = r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_thought_chunk","content":{"text":"hmm"}}}}"#;
        assert_eq!(parse_acp_line_fast(thought).as_deref(), Some("T\nhmm"));
        let tool = r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"tool_call","toolCallId":"tc1"}}}"#;
        assert_eq!(parse_acp_line_fast(tool).as_deref(), Some("C"));
        let resp = r#"{"jsonrpc":"2.0","id":7,"result":{}}"#;
        assert_eq!(parse_acp_line_fast(resp).as_deref(), Some("R\n7"));
    }

    #[test]
    fn patches_core_binary_all_four_patterns() {
        let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("agy_patch_test_{}.bin", ts));
        let mut payload = Vec::new();
        payload.extend_from_slice(&[0xe0, 0x36, 0x80, 0xd2]);
        payload.extend_from_slice(b"____");
        payload.extend_from_slice(&[0x80, 0x38, 0x80, 0xd2]);
        payload.extend_from_slice(b"/bin/bash");
        payload.extend_from_slice(b"/etc/resolv.conf");
        std::fs::write(&path, &payload).unwrap();

        let mask = patch_core_binary(path.to_str().unwrap()).unwrap();
        assert_eq!(mask, 0b1111);
        let patched = std::fs::read(&path).unwrap();
        let _ = std::fs::remove_file(&path);
        assert!(memmem::find(&patched, b"/bin///sh").is_some());
        assert!(memmem::find(&patched, b"etc//resolv.conf").is_some());
    }

    #[test]
    fn parses_partial_streaming_headings_and_hashtags_without_hanging() {
        for sample in ["#", "##", "###", "### ", "####\t", "#hashtag", "#1 item", "####### seven hashes"] {
            let recs = decode_records(&parse_markdown_fast(sample));
            assert_eq!(recs.len(), 1);
            assert_eq!(recs[0].0, 'P');
            assert_eq!(recs[0].2, sample.trim());
        }

        let intro_partial = decode_records(&parse_markdown_fast("Intro\n###"));
        assert_eq!(intro_partial, vec![('P', "".to_string(), "Intro ###".to_string())]);

        let mixed = decode_records(&parse_markdown_fast("Intro\n#tag\n### Real Heading\nBody"));
        assert_eq!(
            mixed,
            vec![
                ('P', "".to_string(), "Intro #tag".to_string()),
                ('H', "3".to_string(), "Real Heading".to_string()),
                ('P', "".to_string(), "Body".to_string()),
            ]
        );
    }

    #[test]
    fn streaming_char_by_char_full_markdown_terminates_cleanly() {
        let doc = "### Ringkasan\n\nParagraf #1 dengan #tag\n\n| Col | Val |\n| :--- | ---: |\n| #a | 10 |\n\n- [x] Done\n\n```rust\n# comment\nfn main() {}\n```\n> quote\n---\n####### literal\n";
        for (end_idx, _) in doc.char_indices() {
            let encoded = parse_markdown_fast(&doc[..end_idx]);
            let _ = decode_records(&encoded);
        }
        let recs = decode_records(&parse_markdown_fast(doc));
        assert!(recs
            .iter()
            .any(|(tag, meta, body)| *tag == 'H' && meta == "3" && body == "Ringkasan"));
        assert!(recs
            .iter()
            .any(|(tag, _, body)| *tag == 'P' && body == "####### literal"));
    }

    #[test]
    fn parses_acp_batch_framed_records_with_unicode_and_ignores_noise() {
        let batch = [
            "not json line",
            r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_thought_chunk","content":{"text":"berpikir 🚀\nbaris 2"}}}}"#,
            r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"text":"halo"}}}}"#,
            r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"tool_call","toolCallId":"t1"}}}"#,
            r#"{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"unknown_evt"}}}"#,
            r#"{"jsonrpc":"2.0","id":42,"result":{"ok":true}}"#,
        ]
        .join("\n");

        let encoded = parse_acp_batch_fast(&batch);
        let utf16: Vec<u16> = encoded.encode_utf16().collect();
        let mut cur = 0usize;
        let mut recs = Vec::new();
        while cur < utf16.len() {
            let nl = utf16[cur..].iter().position(|&u| u == b'\n' as u16).unwrap();
            let header = String::from_utf16(&utf16[cur..cur + nl]).unwrap();
            cur += nl + 1;
            let mut parts = header.split(' ');
            let tag = parts.next().unwrap().chars().next().unwrap();
            let idx: usize = parts.next().unwrap().parse().unwrap();
            let len: usize = parts.next().unwrap().parse().unwrap();
            let body = String::from_utf16(&utf16[cur..cur + len]).unwrap();
            cur += len;
            recs.push((tag, idx, body));
        }

        assert_eq!(
            recs,
            vec![
                ('T', 1, "berpikir 🚀\nbaris 2".to_string()),
                ('M', 2, "halo".to_string()),
                ('C', 3, "".to_string()),
                ('R', 5, "42".to_string()),
            ]
        );
    }

    #[test]
    fn computes_big_core_mask_for_tricluster_dimensity_and_smp() {
        // Dimensity 8350 Ultimate (GT 30 Pro): 4x A510 (2.2G) + 3x A715 (3.2G) + 1x A715 Prime (3.35G)
        let gt30 = [
            (0, 2_200_000),
            (1, 2_200_000),
            (2, 2_200_000),
            (3, 2_200_000),
            (4, 3_200_000),
            (5, 3_200_000),
            (6, 3_200_000),
            (7, 3_350_000),
        ];
        let (big, all, min_f, max_f) = compute_big_core_mask(&gt30);
        assert_eq!(all, 0xFF);
        assert_eq!(big, 0xF0); // cores 4, 5, 6, 7 (all 4 A715 cores, NOT just cpu7!)
        assert_eq!(min_f, 2_200_000);
        assert_eq!(max_f, 3_350_000);
        assert_eq!(format_core_list(big), "4,5,6,7");

        // Homogeneous SMP: no big cluster (big_mask == 0)
        let smp = [(0, 2_000_000), (1, 2_000_000), (2, 2_000_000), (3, 2_000_000)];
        let (smp_big, smp_all, _, _) = compute_big_core_mask(&smp);
        assert_eq!(smp_all, 0x0F);
        assert_eq!(smp_big, 0);
    }

    #[test]
    fn parses_proc_status_fields_accurately() {
        let sample = "Name:\tagy\nState:\tS (sleeping)\nThreads:\t18\nVmRSS:\t   94208 kB\nCpus_allowed_list:\t4-7\n";
        let (name, state, rss, threads, cpus) = parse_proc_status_fields(sample);
        assert_eq!(name, "agy");
        assert_eq!(state, "S (sleeping)");
        assert_eq!(rss, 94208);
        assert_eq!(threads, 18);
        assert_eq!(cpus, "4-7");
    }

    #[test]
    fn reads_current_process_stats_on_linux() {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        {
            let pid = std::process::id() as i32;
            let stats = read_process_stats(pid).expect("proc status should be readable");
            assert!(stats.contains(&format!("pid={}", pid)));
            assert!(stats.contains("threads="));
            assert!(stats.contains("cpus_allowed="));
            let mask = get_tid_affinity(pid);
            assert!(mask > 0);
        }
    }
}
