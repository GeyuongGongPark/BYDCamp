# BYDCamp

BYD 전기차 차량 터치스크린에서 직접 실행하는 캠핑 모드 앱.
에어컨을 28분 주기로 자동 재시작하여 배터리가 다 닳기 전까지 차박 환경을 유지합니다.

## 기능

- **캠핑 모드 시작**: 원격으로 에어컨(OPENAIR)을 켜고, 28분마다 자동 재시작
- **배터리 모니터링**: 5분마다 배터리 잔량 체크, 설정된 임계값 이하 시 자동 종료
- **최대 시간 제한**: 설정한 시간(기본 8시간) 초과 시 자동 종료
- **자동 재로그인**: 세션 만료 시 자동으로 재로그인 처리
- **Foreground Service**: 앱 백그라운드에서도 안정적으로 동작

## 스크린샷

| 메인 화면 | 설정 화면 |
|-----------|-----------|
| 배터리%, 경과 시간, 목표 온도 표시 | 계정 로그인, VIN 선택, 온도/배터리/시간 설정 |

## 설치 방법 (사이드로딩)

1. [Releases](../../releases) 에서 최신 `app-debug.apk` 다운로드
2. BYD 차량 디링크 터치스크린에서 **설정 → 개발자 옵션 → 알 수 없는 앱 설치** 허용
3. APK 파일을 USB 또는 파일 관리자를 통해 설치

## 초기 설정

1. 앱 실행 후 **설정** 버튼 탭
2. 지역 선택 (한국: KR)
3. BYD 계정 이메일/비밀번호 입력 후 **로그인 테스트**
4. VIN 선택
5. 제어 PIN 입력 (DiLink 앱 설정의 원격제어 PIN)
6. 목표 온도, 종료 배터리%, 최대 시간 설정 후 저장

## 사용 방법

1. 메인 화면에서 **캠핑 시작** 버튼 탭
2. 에어컨이 켜지면 배터리%와 경과 시간이 실시간으로 업데이트됨
3. 수동 종료: **종료** 버튼 탭 또는 상태바 알림에서 종료

## 자동 종료 조건

| 조건 | 기본값 |
|------|--------|
| 배터리 잔량 | 30% 이하 |
| 최대 운행 시간 | 8시간 |
| 수동 종료 | 언제든지 |

## 지원 지역

`KR` `EU` `JP` `SG` `AU` `BR` `MX` `NO` `IN` `ID` `VN` `SA` `OM`

## 빌드 방법

```bash
# 요구사항: Android SDK, Java 17

git clone https://github.com/GeyuongGongPark/BYDCamp.git
cd BYDCamp
./gradlew assembleDebug

# APK 경로
# app/build/outputs/apk/debug/app-debug.apk
```

## 기술 스택

- **언어**: Kotlin + Coroutines
- **최소 SDK**: Android 8.0 (API 26)
- **빌드**: Gradle 8.9 / AGP 8.5.2
- **암호화**: BangcleCodec (whitebox AES), AES-128-CBC, MD5, SHA-1
- **네트워크**: HttpURLConnection (의존성 최소화)

## 관련 프로젝트

- [BYD](https://github.com/GeyuongGongPark/BYD) — iOS/macOS 버전 (SwiftUI + BYD API)
