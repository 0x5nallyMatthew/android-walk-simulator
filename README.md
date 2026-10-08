# WalkSimulator — Android (Kotlin + Jetpack Compose)

Phiên bản Android của app giả lập đi bộ. **Build được trực tiếp trên Windows** bằng Android Studio — không cần máy Mac.

## Tính năng

- **Chọn lộ trình trên bản đồ**: chạm bản đồ (OpenStreetMap, không cần API key) để thêm waypoint; giữ lâu để xóa điểm cuối; **tự động tìm đường đi bộ qua OSRM (profile `foot`)** — tuyến bám theo phố, không cắt hồ/sông; lỗi mạng thì fallback nối thẳng
- **Điều chỉnh tốc độ**: slider 1–10 km/h
- **Điều chỉnh thời gian**: slider 5–240 phút (bước 5 phút)
- **Lặp lộ trình**: đi hết tuyến thì quay lại từ đầu, không bao giờ "chạm đáy"
- **Mock location cấp hệ thống**: vị trí giả được MỌI app đọc được (Android cho phép chính thức qua Developer Options — khác iOS)
- **Health Connect**: ghi số bước theo lô mỗi 60s (app sự kiện đọc được)
- **Chạy nền thật**: foreground service loại `location` — vẫn chạy khi tắt màn hình (khác iOS)
- Số bước = quãng đường ÷ 0.72m/bước

## Cấu trúc

```
android-walk-simulator/
├── settings.gradle.kts / build.gradle.kts
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/example/walksimulator/
│       ├── MainActivity.kt           # UI: bản đồ OSM + slider + thống kê realtime
│       ├── RouteSimulator.kt         # OSRM foot routing + nội suy polyline, vị trí theo quãng đường
│       ├── MockLocationHelper.kt     # Đăng ký GPS test provider, đẩy tọa độ
│       ├── MockLocationService.kt    # Foreground service: đi theo lộ trình mỗi giây
│       └── HealthConnectManager.kt   # Ghi steps vào Health Connect
```

## Build trên Windows

1. Cài [Android Studio](https://developer.android.com/studio)
2. File → Open → chọn thư mục `android-walk-simulator`
3. Chờ Gradle sync (tự tải dependencies) → Run ▶ (máy thật hoặc emulator)

minSdk 29 (Android 10), targetSdk 35.

## Thiết lập trên điện thoại (bắt buộc)

1. **Bật Developer Options**: Cài đặt → Giới thiệu điện thoại → bấm 7 lần vào "Số bản dựng"
2. **Chọn mock app**: Tuỳ chọn nhà phát triển → "Chọn ứng dụng vị trí mô phỏng" (Select mock location app) → chọn **Walk Simulator**
3. Cấp quyền Vị trí khi app hỏi
4. **Health Connect**: Android 13 cần cài app [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata); Android 14+ có sẵn trong Cài đặt
5. Bấm **Bắt đầu** trong app → bật bản đồ của app sự kiện để kiểm tra vị trí đang "đi"

## Cảnh báo phát hiện (quan trọng — Android khác iOS)

| Tín hiệu | Chi tiết | Mức độ rủi ro |
|---|---|---|
| **Cờ `Location.isMock()`** | Android đánh dấu mọi vị trí từ test provider; app sự kiện chỉ cần 1 dòng code `location.isMock()` là phát hiện ngay | ⚠️ CAO — không có cách合法 để tắt cờ này |
| Health Connect nguồn dữ liệu | App hiện trong Health Connect → "Quyền của ứng dụng" | Trung bình |
| Nhất quán steps ↔ vị trí | Cần tốc độ hợp lý; app đã đồng bộ sẵn | Thấp |

Android **dễ bị phát hiện hơn iOS** ở điểm mock location có cờ công khai. Nhiều app fitness nghiêm túc kiểm tra `isMock()` — nếu app sự kiện của công ty kiểm tra, hướng này sẽ bị bắt. Hãy cân nhắc trước khi dùng cho sự kiện thật.

## So sánh với bản iOS

| | iOS | Android |
|---|---|---|
| Giả lập vị trí cho app khác | ❌ Không thể (không có API) | ✅ Được (nhưng có cờ `isMock`) |
| Chạy khi tắt màn hình | ❌ Foreground only | ✅ Foreground service |
| Build trên Windows | ❌ Cần macOS | ✅ Android Studio |
| Ghi steps app khác đọc | ✅ HealthKit | ✅ Health Connect |
