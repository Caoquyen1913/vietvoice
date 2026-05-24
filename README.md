# VietVoice – Dịch giọng nói tiếng Trung trong game sang tiếng Việt

App Android bắt âm thanh game đang phát (PUBG, ...), nhận dạng tiếng Trung và đọc lại tiếng Việt trực tiếp.

## Công nghệ dùng (tất cả miễn phí)

| Thành phần | Thư viện |
|---|---|
| Bắt âm thanh game | Android AudioPlaybackCapture API |
| Nhận dạng giọng nói (STT) | [Vosk](https://alphacephei.com/vosk/) – offline, Apache-2.0 |
| Dịch ZH→VI | Google ML Kit Translation – offline, miễn phí |
| Đọc tiếng Việt (TTS) | Android TextToSpeech (Google TTS) |
| Overlay bubble | Android WindowManager + SYSTEM_ALERT_WINDOW |

## Yêu cầu

- **Android 10+** (API 29) — yêu cầu của AudioPlaybackCapture
- **Android Studio** Hedgehog (2023.1.1) trở lên
- **Google TTS** đã cài tiếng Việt trên máy (Settings → Accessibility → TTS → Tiếng Việt)

## Cách build

### 1. Mở project
```
File → Open → chọn thư mục "translate app"
```
Android Studio sẽ tự tải Gradle wrapper và sync dependencies.

### 2. Sync Gradle
Khi Android Studio báo "Gradle files have changed" → nhấn **Sync Now**.
Lần đầu sẽ tải ~200MB dependencies (Vosk, ML Kit, v.v.).

### 3. Build & Install
- Kết nối thiết bị Android thật (emulator không bắt được audio game)
- Nhấn **Run** (Shift+F10)

## Cách dùng

### Lần đầu
1. Mở app → nhấn **"⬇ Tải Model"** → chờ tải ~42MB (cần WiFi)
2. Cấp các quyền được yêu cầu:
   - **RECORD_AUDIO** – bắt buộc
   - **Hiển thị trên ứng dụng khác** (SYSTEM_ALERT_WINDOW) – cho overlay bubble
   - **Bắt màn hình** (MediaProjection) – để bắt âm thanh game

### Dịch trong game
1. Nhấn **"▶ Bắt đầu"** → cấp quyền bắt màn hình
2. Thoát app → vào game
3. Nút nổi xuất hiện trên màn hình → dùng để Start/Stop và xem transcript
4. Khi đối phương nói tiếng Trung → app tự động dịch và đọc tiếng Việt

## Giới hạn đã biết

- **Tiếng game trộn vào STT**: âm thanh súng/nhạc lẫn vào giọng nói → chất lượng kém lúc giao tranh ồn
- **Một số game chặn capture**: nếu không nghe thấy gì → game đó dùng `ALLOW_CAPTURE_BY_NONE`
- **Không dịch được cuộc gọi điện thoại**: Android chặn hoàn toàn, không giải quyết được
- **Độ trễ**: ~1–3 giây (capture → STT → dịch → đọc)

## Cấu trúc source code

```
app/src/main/java/com/vietvoice/app/
├── MainActivity.kt              – UI chính, xin quyền
├── TranslationService.kt        – Foreground Service, điều phối pipeline
├── audio/AudioCaptureManager.kt – Bắt âm thanh game qua AudioPlaybackCapture
├── stt/VoskTranscriber.kt       – Nhận dạng giọng nói Vosk
├── translate/TranslatorManager.kt – Dịch ZH→VI qua ML Kit
├── tts/TtsManager.kt            – Đọc tiếng Việt
├── overlay/OverlayController.kt – Nút nổi + bảng transcript
└── model/ModelDownloader.kt     – Tải model Vosk từ alphacephei.com
```

## Thêm tiếng Anh (giai đoạn sau)
1. Tải thêm model Vosk EN: `vosk-model-small-en-us-0.15`
2. Thêm `TranslateLanguage.ENGLISH` vào `TranslatorManager`
3. Thêm bộ chọn ngôn ngữ trong UI
4. `VoskTranscriber` đã nhận tham số `modelPath` → chỉ cần truyền path model EN
