#![forbid(unsafe_code)]

const METADATA_BATCH_MAGIC: u32 = 0x3146_504d; // "MPF1" in little-endian bytes.
const METADATA_FIELD_COUNT: usize = 5;

/// Normalized, reusable password-list search query.
///
/// The Android boundary only passes display metadata into this module. Secret
/// fields and ciphertext never cross the JNI boundary for list filtering.
pub(crate) struct SearchQuery {
    normalized: String,
    ascii: bool,
}

impl SearchQuery {
    pub(crate) fn new(query: &str) -> Self {
        let normalized = query.trim().to_lowercase();
        let ascii = normalized.is_ascii();
        Self { normalized, ascii }
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.normalized.is_empty()
    }

    pub(crate) fn matches_value(&self, value: &str) -> bool {
        if self.is_empty() {
            return true;
        }
        if self.ascii {
            let query = self.normalized.as_bytes();
            return value
                .as_bytes()
                .windows(query.len())
                .any(|window| window.eq_ignore_ascii_case(query));
        }

        value.to_lowercase().contains(&self.normalized)
    }
}

/// Filters a versioned, secret-free metadata frame produced by Android.
///
/// Layout (little endian):
/// - u32 magic: `MPF1`
/// - u32 row count
/// - for every row, five UTF-8 fields (title, username, website, app name,
///   package name), each encoded as `u32 byte_len` followed by raw bytes.
///
/// Parsing is allocation-free for field values: every candidate is borrowed
/// directly from the single JNI byte buffer. This keeps JNI traffic constant
/// per search instead of crossing the boundary once per field and row.
pub(crate) fn filter_metadata_batch(payload: &[u8], query: &SearchQuery) -> Option<Vec<i32>> {
    let mut cursor = BatchCursor::new(payload);
    if cursor.read_u32()? != METADATA_BATCH_MAGIC {
        return None;
    }

    let row_count = cursor.read_u32()? as usize;
    if row_count > i32::MAX as usize {
        return None;
    }

    let mut selected = Vec::with_capacity(row_count);
    for index in 0..row_count {
        let mut matches = query.is_empty();
        for _ in 0..METADATA_FIELD_COUNT {
            let value = cursor.read_utf8()?;
            if !matches && query.matches_value(value) {
                matches = true;
            }
        }
        if matches {
            selected.push(index as i32);
        }
    }

    if !cursor.is_finished() {
        return None;
    }
    Some(selected)
}

struct BatchCursor<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl<'a> BatchCursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, position: 0 }
    }

    fn read_u32(&mut self) -> Option<u32> {
        let bytes = self.read_bytes(4)?;
        Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn read_utf8(&mut self) -> Option<&'a str> {
        let len = self.read_u32()? as usize;
        std::str::from_utf8(self.read_bytes(len)?).ok()
    }

    fn read_bytes(&mut self, len: usize) -> Option<&'a [u8]> {
        let end = self.position.checked_add(len)?;
        let bytes = self.bytes.get(self.position..end)?;
        self.position = end;
        Some(bytes)
    }

    fn is_finished(&self) -> bool {
        self.position == self.bytes.len()
    }
}

#[cfg(test)]
mod tests {
    use super::{filter_metadata_batch, SearchQuery, METADATA_BATCH_MAGIC};

    fn encode_rows(rows: &[[&str; 5]]) -> Vec<u8> {
        let mut payload = Vec::new();
        payload.extend_from_slice(&METADATA_BATCH_MAGIC.to_le_bytes());
        payload.extend_from_slice(&(rows.len() as u32).to_le_bytes());
        for row in rows {
            for value in row {
                let bytes = value.as_bytes();
                payload.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
                payload.extend_from_slice(bytes);
            }
        }
        payload
    }

    #[test]
    fn blank_query_matches_without_metadata_work() {
        let query = SearchQuery::new("   ");
        assert!(query.is_empty());
        assert!(query.matches_value(""));
    }

    #[test]
    fn ascii_query_is_case_insensitive() {
        let query = SearchQuery::new("GITHUB");
        assert!(query.matches_value("GitHub"));
        assert!(!query.matches_value("example.cn"));
    }

    #[test]
    fn ascii_query_matches_inside_unicode_text() {
        let query = SearchQuery::new("MUN");
        assert!(query.matches_value("账号 MUN-01 / München"));
    }

    #[test]
    fn unicode_query_uses_lowercase_fallback() {
        let query = SearchQuery::new("münchen");
        assert!(query.matches_value("MÜNCHEN"));
        assert!(!query.matches_value("Berlin"));
    }

    #[test]
    fn metadata_batch_returns_original_indices() {
        let rows = [
            ["GitHub", "octocat", "github.com", "", ""],
            ["Mail", "alice", "mail.example", "", ""],
            ["Android", "", "", "Monica", "takagi.ru.monica"],
        ];
        let payload = encode_rows(&rows);

        assert_eq!(
            filter_metadata_batch(&payload, &SearchQuery::new("GITHUB")),
            Some(vec![0])
        );
        assert_eq!(
            filter_metadata_batch(&payload, &SearchQuery::new("monica")),
            Some(vec![2])
        );
    }

    #[test]
    fn malformed_metadata_batch_is_rejected() {
        let mut payload = encode_rows(&[["one", "two", "three", "four", "five"]]);
        payload.pop();
        assert_eq!(
            filter_metadata_batch(&payload, &SearchQuery::new("one")),
            None
        );

        let mut payload = encode_rows(&[]);
        payload.extend_from_slice(&[0]);
        assert_eq!(
            filter_metadata_batch(&payload, &SearchQuery::new("anything")),
            None
        );
    }
}
