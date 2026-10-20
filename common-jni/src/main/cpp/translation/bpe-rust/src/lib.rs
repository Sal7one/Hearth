//! Narrow, fallible C bridge to Hugging Face's real ByteLevel BPE tokenizer.
//! Buffers are Rust-owned until hearth_bpe_free_buffer. The C++ owner serializes
//! calls and retires the tokenizer only after all inference leases finish.
use std::{panic::{catch_unwind, AssertUnwindSafe}, ptr, slice, str};
use tokenizers::Tokenizer;

#[repr(C)]
pub struct Buffer { pub data: *mut u8, pub len: usize }

unsafe fn write_buffer(out: *mut Buffer, bytes: Vec<u8>) {
    let boxed = bytes.into_boxed_slice();
    (*out).len = boxed.len();
    (*out).data = Box::into_raw(boxed) as *mut u8;
}

unsafe fn input<'a>(data: *const u8, len: usize) -> Result<&'a str, String> {
    if data.is_null() || len > 8 * 1024 * 1024 { return Err("Invalid tokenizer input buffer".into()); }
    str::from_utf8(slice::from_raw_parts(data, len)).map_err(|_| "Tokenizer input must be UTF-8".into())
}

unsafe fn guarded<T>(error: *mut Buffer, run: impl FnOnce() -> Result<T, String>) -> Option<T> {
    if error.is_null() { return None; }
    *error = Buffer { data: ptr::null_mut(), len: 0 };
    match catch_unwind(AssertUnwindSafe(run)) {
        Ok(Ok(value)) => Some(value),
        Ok(Err(message)) => { write_buffer(error, message.into_bytes()); None },
        Err(_) => { write_buffer(error, b"Tokenizer operation panicked".to_vec()); None },
    }
}

#[no_mangle]
pub unsafe extern "C" fn hearth_bpe_create(data: *const u8, len: usize, error: *mut Buffer) -> *mut Tokenizer {
    guarded(error, || {
        let mut tokenizer = Tokenizer::from_bytes(input(data, len)?.as_bytes()).map_err(|e| e.to_string())?;
        // Publisher JSON serializes truncation=256. Long input must be rejected
        // by the session's explicit token limit, never silently truncated.
        tokenizer.with_truncation(None).map_err(|e| e.to_string())?;
        tokenizer.with_padding(None);
        Ok(Box::into_raw(Box::new(tokenizer)))
    }).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn hearth_bpe_encode(handle: *const Tokenizer, data: *const u8, len: usize,
                                           output: *mut Buffer, error: *mut Buffer) -> i32 {
    if output.is_null() { return 0; }
    *output = Buffer { data: ptr::null_mut(), len: 0 };
    guarded(error, || {
        let tokenizer = handle.as_ref().ok_or("Tokenizer is closed")?;
        let encoded = tokenizer.encode(input(data, len)?, false).map_err(|e| e.to_string())?;
        let json = serde_json::json!({"ids": encoded.get_ids(), "tokens": encoded.get_tokens()});
        write_buffer(output, serde_json::to_vec(&json).map_err(|e| e.to_string())?);
        Ok(1)
    }).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn hearth_bpe_decode(handle: *const Tokenizer, data: *const u8, len: usize,
                                           output: *mut Buffer, error: *mut Buffer) -> i32 {
    if output.is_null() { return 0; }
    *output = Buffer { data: ptr::null_mut(), len: 0 };
    guarded(error, || {
        let tokenizer = handle.as_ref().ok_or("Tokenizer is closed")?;
        let tokens: Vec<String> = serde_json::from_str(input(data, len)?).map_err(|e| e.to_string())?;
        if tokens.len() > 1024 { return Err("Tokenizer output exceeds token limit".into()); }
        let ids: Vec<u32> = tokens.iter().map(|t| tokenizer.token_to_id(t).ok_or_else(|| "Model returned a token outside its vocabulary".to_string())).collect::<Result<_, _>>()?;
        let text = tokenizer.decode(&ids, true).map_err(|e| e.to_string())?;
        write_buffer(output, text.into_bytes());
        Ok(1)
    }).unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn hearth_bpe_destroy(handle: *mut Tokenizer) {
    if !handle.is_null() { drop(Box::from_raw(handle)); }
}

#[no_mangle]
pub unsafe extern "C" fn hearth_bpe_free_buffer(buffer: Buffer) {
    if !buffer.data.is_null() {
        drop(Box::from_raw(ptr::slice_from_raw_parts_mut(buffer.data, buffer.len)));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn empty() -> Buffer { Buffer { data: ptr::null_mut(), len: 0 } }
    unsafe fn text(buffer: &Buffer) -> String {
        if buffer.data.is_null() { String::new() }
        else { str::from_utf8(slice::from_raw_parts(buffer.data, buffer.len)).unwrap().to_owned() }
    }
    fn fixture() -> Vec<u8> {
        let mut chars: Vec<char> = tokenizers::pre_tokenizers::byte_level::ByteLevel::alphabet().into_iter().collect();
        chars.sort();
        let vocab: serde_json::Map<String, serde_json::Value> = chars.iter().enumerate()
            .map(|(i, c)| (c.to_string(), serde_json::json!(i))).collect();
        serde_json::to_vec(&serde_json::json!({"version":"1.0", "added_tokens":[],
            "truncation":{"direction":"Right","max_length":1,"strategy":"LongestFirst","stride":0},
            "padding":null,"normalizer":null,"post_processor":null,
            "pre_tokenizer":{"type":"ByteLevel","add_prefix_space":false,"trim_offsets":true,"use_regex":true},
            "decoder":{"type":"ByteLevel","add_prefix_space":false,"trim_offsets":true,"use_regex":true},
            "model":{"type":"BPE","dropout":null,"unk_token":null,"continuing_subword_prefix":"",
                "end_of_word_suffix":"","fuse_unk":false,"byte_fallback":false,"ignore_merges":false,
                "vocab":vocab,"merges":[]}})).unwrap()
    }
    #[test]
    fn arbitrary_unicode_roundtrips_without_serialized_truncation() { unsafe {
        let bytes=fixture();let mut error=empty();
        let handle=hearth_bpe_create(bytes.as_ptr(),bytes.len(),&mut error);
        assert!(!handle.is_null(),"{}",text(&error));
        let input="  Hello العربية 🚀\nقَرَأَ ٢٥\t".as_bytes();let mut output=empty();
        assert_eq!(1,hearth_bpe_encode(handle,input.as_ptr(),input.len(),&mut output,&mut error));
        let encoded: serde_json::Value=serde_json::from_str(&text(&output)).unwrap();
        assert!(encoded["ids"].as_array().unwrap().len()>1);
        let tokens=serde_json::to_vec(&encoded["tokens"]).unwrap();hearth_bpe_free_buffer(output);
        let mut decoded=empty();
        assert_eq!(1,hearth_bpe_decode(handle,tokens.as_ptr(),tokens.len(),&mut decoded,&mut error));
        assert_eq!(str::from_utf8(input).unwrap(),text(&decoded));
        hearth_bpe_free_buffer(decoded);hearth_bpe_free_buffer(error);hearth_bpe_destroy(handle);
    }}
    #[test]
    fn malformed_model_reports_error_without_panic_or_handle() { unsafe {
        let mut error=empty();let bytes=b"not JSON";
        assert!(hearth_bpe_create(bytes.as_ptr(),bytes.len(),&mut error).is_null());
        assert!(!text(&error).is_empty());hearth_bpe_free_buffer(error);
    }}
    #[test]
    fn invalid_utf8_and_unknown_output_tokens_are_errors() { unsafe {
        let bytes=fixture();let mut error=empty();let mut output=empty();
        let handle=hearth_bpe_create(bytes.as_ptr(),bytes.len(),&mut error);assert!(!handle.is_null());
        let invalid=[255u8];
        assert_eq!(0,hearth_bpe_encode(handle,invalid.as_ptr(),invalid.len(),&mut output,&mut error));
        assert!(text(&error).contains("UTF-8"));assert!(output.data.is_null());hearth_bpe_free_buffer(error);
        error=empty();let unknown=b"[\"not-a-token\"]";
        assert_eq!(0,hearth_bpe_decode(handle,unknown.as_ptr(),unknown.len(),&mut output,&mut error));
        assert!(text(&error).contains("outside its vocabulary"));assert!(output.data.is_null());
        hearth_bpe_free_buffer(error);hearth_bpe_destroy(handle);
    }}
}
