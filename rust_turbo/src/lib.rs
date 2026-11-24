use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::fmt::Write as FmtWrite;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

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

fn is_paragraph_breaker(trimmed: &str) -> bool {
    trimmed.is_empty()
        || trimmed.starts_with("```")
        || trimmed.starts_with('#')
        || trimmed.starts_with('>')
        || trimmed == "---"
        || trimmed == "***"
        || trimmed == "___"
        || is_bullet_line(trimmed).is_some()
        || is_numbered_line(trimmed).is_some()
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
        if trimmed.starts_with('#') {
            let level = trimmed.bytes().take_while(|&b| b == b'#').count();
            if (1..=6).contains(&level) && trimmed.as_bytes().get(level) == Some(&b' ') {
                let text = trimmed[level..].trim();
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

        // Bullet list item
        if let Some(text) = is_bullet_line(trimmed) {
            append_record(&mut out, 'L', "•", text);
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

        // Paragraph
        let mut para = String::new();
        let mut first = true;
        while i < lines.len() {
            let cur_trim = lines[i].trim();
            if is_paragraph_breaker(cur_trim) {
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
pub extern "system" fn Java_id_vern_antigravity_nativebridge_RustTurboBridge_nativeEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string("antigravity-turbo-rust 1.0.1 (native)") {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
