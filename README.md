# Blackhole Screensaver (Android)

갤럭시/Android 전용 블랙홀 스크린세이버 MVP입니다.  
유휴 시간이 지나면 오버레이로 블랙홀이 떠다니며, MediaProjection으로 캡처한 실시간 화면을 GLSL 중력렌즈로 왜곡합니다.

## 요구 사항

- Android Studio Ladybug(2024.2+) 또는 동급
- JDK 17+ (Android Studio 내장 JBR 권장)
- Android SDK Platform 35, Build-Tools 35.x
- 실기기: Android 7.0+(API 24), 갤럭시 권장

## Android Studio에서 실행

1. **File → Open** 으로 `blackhole-android` 폴더를 연다.
2. SDK가 비어 있으면 Studio가 Platform 35 / Build-Tools 설치를 안내한다 → 설치.
3. Gradle Sync 완료 후, 갤럭시를 USB 디버깅으로 연결.
4. Run ▶ (`app`).

## 첫 실행 설정 순서

1. **다른 앱 위에 표시** 허용
2. **알림** 허용
3. **접근성**에서 `블랙홀 스크린세이버` / `Blackhole Screensaver` 켜기 (유휴 감지)
4. 기능 토글을 **ON** → 화면 캡처 동의 팝업 허용
5. 홈으로 나가도 상시 알림이 유지되면 정상
6. 설정한 분(기본 3분) 동안 입력 없이 기다리면 블랙홀 오버레이 표시
7. 화면을 터치하면 즉시 사라지고 idle 타이머 리셋

## 홈 화면 위젯

1. 홈 화면 길게 누르기 → 위젯 → **Blackhole** / **블랙홀** (가로 2칸 × 세로 1칸)
2. 탭으로 ON/OFF  
   - OFF: 즉시 오버레이 제거 + 서비스 중지  
   - ON: 캡처가 살아 있지 않으면 앱을 열어 권한/캡처를 다시 받음

## 프로젝트 구조

```
app/src/main/java/com/blackhole/screensaver/
  ui/MainActivity.kt          # 설정 UI (ON/OFF, idle 분, 권한)
  service/BlackholeForegroundService.kt  # FGS + idle 틱
  capture/ScreenCapturer.kt   # MediaProjection → ImageReader
  overlay/OverlayController.kt
  gl/BlackholeRenderer.kt     # OpenGL ES 2.0 + LENS_FRAG
  idle/IdleAccessibilityService.kt
  widget/BlackholeWidgetProvider.kt
  prefs/AppPrefs.kt
```

## 참고

- MediaProjection 토큰은 Android 14+에서 사실상 1회용이라, 기능을 켤 때마다 캡처 동의를 다시 받을 수 있다.
- 재부팅 후에는 캡처 동의가 사라져 알림/앱에서 다시 허용해야 한다.
- 보안·DRM 화면은 시스템 정책상 검게 캡처된다.
- iOS는 지원하지 않는다.
- 오버레이가 떠 있는 동안은 MediaProjection이 오버레이 자신을 다시 캡처하는 피드백을 피하기 위해, 발동 직전 실시간 프레임을 고정한다. 블랙홀 이동·포톤링 flicker는 계속 애니메이션된다.
- 캡처 텍스처 Y플립은 `BlackholeRenderer` 프래그먼트 셰이더에서 처리했다. 기기에 따라 상하가 뒤집히면 `uv.y` 플립을 조정한다.

## MVP 체크리스트

- [x] 설정에서 블랙홀 ON/OFF
- [x] 설정에서 idle 분 (기본 3, 1~60)
- [x] 홈 위젯 2×1 ON/OFF + 상태 동기화
- [x] 앱 종료 후에도 ON이면 idle 후 오버레이
- [x] 터치 시 즉시 제거 + idle 리셋
- [x] MediaProjection + 오버레이 + GLSL 렌즈
- [x] KO / EN / JA 문자열
