# QwenVoice 음성 키보드 (Android / Qwen3-ASR)

갤럭시(S24 울트라 등)에서 쓰는 **100% 온디바이스 음성 입력 키보드**입니다.
말하면 기기 안에서 Qwen3-ASR(sherpa-onnx)로 인식해 커서 위치에 바로 입력합니다. 음성은 기기 밖으로 나가지 않습니다.

- 음성 인식: **sherpa-onnx v2.0.1** + **Qwen3-ASR** (0.6B / 1.7B int8, 한국어 포함 30개 언어)
- 키보드: 표준 `InputMethodService` → 삼성 키보드에서 ⌨/🌐 아이콘으로 1초 안에 전환
- **플로팅 마이크(1.2)**: 삼성 키보드(천지인 포함)를 그대로 두고, 화면에 떠 있는 동그란 마이크를 눌러 입력 — 오버레이 + 접근성 자동 입력. **1.3부터 키보드가 뜰 때만 표시**(기본 켜짐, 설정에서 항상 표시 전환 가능), **1.4부터 ✕ 닫기 버튼 대신 하단 닫기 존으로 끌어다 놓아 닫기**
- 부가 기능: 기호·편집 키 직접 배치, **단어 치환 사전**(오인식 자동 교정), LLM 후처리 확장 자리

---

## 0. 빠른 시작 — PC에 받고, 앱으로 빌드하기

처음 한 번만 천천히 따라 하면 됩니다. 명령어는 **그대로 복사해서 붙여넣기** 하세요.

### 0-1. 받기 (처음 한 번) — `git clone`

1. **Git 설치 확인** — 윈도우 키 → `cmd` 입력 → 명령 프롬프트 실행 → 아래 입력.
   ```
   git --version
   ```
   `git version 2.xx.x` 처럼 나오면 준비 완료. `'git'은(는) 내부 또는 외부 명령...` 이라고 나오면 <https://git-scm.com/download/win> 에서 설치한 뒤 **cmd 창을 새로 열고** 다시 확인하세요.

2. **내려받을 폴더로 이동** (예: 내 문서 폴더)
   ```
   cd %USERPROFILE%\Documents
   ```

3. **레포지토리 받기**
   ```
   git clone https://github.com/SBiografia/VoiceSTT_pickle.git
   ```
   - 이 프로젝트는 `sherpa-onnx` 파일(약 48MB)이 들어 있어 **1~3분쯤 걸릴 수 있습니다.** 멈춘 것처럼 보여도 창을 닫지 마세요.
   - 다 받으면 `C:\Users\<내 PC 사용자 이름>\Documents\VoiceSTT_pickle` 폴더가 생깁니다. 이 폴더 안에 `settings.gradle.kts`, `app`, `gradlew.bat` 이 보이면 정상입니다.

4. **폴더로 들어가기**
   ```
   cd VoiceSTT_pickle
   ```

> **명령어가 부담스러우면**: GitHub 페이지(<https://github.com/SBiografia/VoiceSTT_pickle>) → 초록색 **Code** 버튼 → **Download ZIP** → 받은 ZIP을 풀어도 빌드는 됩니다. 다만 ZIP에는 git 기록이 없어서 **아래 0-2 업데이트(`git pull`)는 쓸 수 없고, 새 ZIP을 다시 받아 덮어써야** 합니다.

### 0-2. 업데이트 받기 (그 다음부터) — `git pull`

새 버전이 올라오면 폴더를 지우고 다시 받을 필요 없이, **받아 둔 폴더에서** 아래 두 줄만 실행하면 됩니다.

```
cd %USERPROFILE%\Documents\VoiceSTT_pickle
git pull
```

- `Already up to date.` = 이미 최신입니다.
- 파일이 내려받아졌다면 `Fast-forward` 같은 문구가 나옵니다. 그다음 Android Studio에서 빌드만 다시 하면 됩니다.
- **내가 폴더 안의 파일을 직접 고친 경우**에는 `git pull` 이 `local changes would be overwritten` 으로 막힐 수 있습니다. 그때는 고친 파일을 다른 곳에 복사해 백업한 뒤 `git checkout .` 로 원래대로 돌린 다음 `git pull` 하세요(내 수정은 백업본에서 다시 적용).
- 지금 폴더가 최신인지 궁금하면:
  ```
  git log -1 --oneline
  ```

### 0-3. Android 앱 빌드 — 방법 ①: Android Studio에서 APK 만들기 (권장)

1. **Android Studio 설치** — <https://developer.android.com/studio> 에서 받아 기본 설정으로 설치합니다. JDK가 함께 들어 있어 따로 설치할 필요 없습니다.
2. Android Studio 실행 → 시작 화면에서 **Open** (이미 프로젝트가 열려 있으면 메뉴 **File → Open**).
3. 폴더 선택 창에서 **`settings.gradle.kts` 가 보이는 폴더**를 고릅니다 — 즉 `C:\Users\<내 PC 사용자 이름>\Documents\VoiceSTT_pickle` **폴더 자체**를 선택하고 **OK**. (안쪽 `app` 폴더를 고르면 안 됩니다.)
4. 처음 열면 오른쪽 아래에 **Gradle Sync** 진행 표시가 뜹니다. 인터넷이 필요하고 **처음 한 번은 5~10분** 걸릴 수 있습니다. `Gradle sync finished` 가 나올 때까지 기다리세요.
5. 위쪽 메뉴 **Build → Generate App Bundles or APKs → Build APK(s)** 를 누릅니다.
   - Android Studio 버전에 따라 메뉴 이름이 다릅니다. 그런 항목이 없으면 **Build → Build Bundle(s)/APK(s) → Build APK(s)** 를 누르세요.
6. 아래쪽에 `APK(s) generated successfully` 알림이 뜨면 **locate** 를 클릭 → 탐색기에서 APK 파일이 있는 폴더가 열립니다. 경로는 다음과 같습니다.
   ```
   app\build\outputs\apk\debug\app-debug.apk
   ```
7. 그 `app-debug.apk` 를 USB 케이블·카카오톡 나에게 보내기·클라우드 등으로 갤럭시에 옮겨 실행 → 설치를 허용하면 끝입니다(처음엔 *출처를 알 수 없는 앱 설치 허용*을 물어볼 수 있습니다).

> 폰을 USB로 연결하고 **USB 디버깅**을 켠 상태라면, APK를 따로 옮기지 않고 위쪽 **Run ▶** 버튼으로 바로 설치·실행할 수도 있습니다.

### 0-4. Android 앱 빌드 — 방법 ②: cmd에서 `gradlew.bat assembleDebug`

Android Studio를 열지 않고 명령어로 APK만 뽑는 방법입니다.

1. **cmd 열기** (윈도우 키 → `cmd`).
2. **프로젝트 폴더로 이동**
   ```
   cd %USERPROFILE%\Documents\VoiceSTT_pickle
   ```
3. **빌드 실행**
   ```
   gradlew.bat assembleDebug
   ```
   - 처음 실행하면 Gradle 본체와 빌드 도구를 자동으로 내려받기 때문에 **5~15분** 걸릴 수 있습니다. `BUILD SUCCESSFUL` 이 나오면 성공입니다.
   - 중간에 멈춘 것처럼 보여도 `Downloading https://...` 줄이 계속 움직이면 정상 진행 중입니다.
4. **결과물 위치**
   ```
   app\build\outputs\apk\debug\app-debug.apk
   ```
   탐색기 주소창에 위 경로를 붙여넣으면 바로 열립니다(프로젝트 폴더 기준 상대 경로).

> **`JAVA_HOME is not set` / `java` 를 찾을 수 없다는 오류가 나면** — Android Studio를 설치했다면 그 안에 JDK가 들어 있습니다. 같은 cmd 창에서 아래를 먼저 실행한 뒤 3번을 다시 실행하세요.
> ```
> set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
> gradlew.bat assembleDebug
> ```
> (Android Studio를 다른 경로에 설치했다면 `jbr` 폴더가 있는 곳으로 바꿔 주세요. cmd 창을 닫으면 이 설정은 사라지므로, 다시 빌드할 때는 `set JAVA_HOME=...` 부터 다시 실행하면 됩니다.)

---

## 1. 빌드 설정 요약

Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21, **JDK 17** 기준입니다(Android Studio에 내장된 JDK 사용).

`sherpa-onnx` 는 Maven Central/JitPack에 배포되지 않아 **`app/libs/sherpa-onnx-2.0.1.aar` 파일을 프로젝트에 동봉**했습니다. 따라서 별도 저장소 설정 없이 빌드됩니다. (최신 버전으로 올리려면 <https://github.com/k2-fsa/sherpa-onnx/releases> 에서 `sherpa-onnx-<버전>.aar` 를 받아 같은 이름 규칙으로 교체하고 `app/build.gradle.kts` 의 파일명을 맞춰 주세요.)

| 항목 | 값 |
| --- | --- |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| 언어 | Kotlin 2.0.21, ViewBinding + Material3 (Compose 미사용) |
| sherpa-onnx | v2.0.1 AAR (arm64-v8a, armeabi-v7a, x86, x86_64 포함) |

---

## 2. 첫 실행 — 모델 내려받기 (약 941MB 또는 약 2.24GB)

앱을 열고 **3. 모델** 에서 하나를 고릅니다. 앱이 직접 HuggingFace에서 파일을 받아 기기에 저장합니다.

| 모델 | 파일 | 크기 | 성격 |
| --- | --- | --- | --- |
| **0.6B int8** | conv_frontend 44.1MB + encoder.int8 182.5MB + decoder.int8 755.9MB + tokenizer 4.5MB | **약 941MB** | 빠름, 메모리 부담 적음 |
| **1.7B int8** | conv_frontend 48.1MB + encoder.int8 314.2MB + decoder.int8 2,037.5MB + tokenizer 4.5MB | **약 2.24GB** | 더 정확, 로딩·추론 느림 |

- 다운로드 출처(둘 다 공개 리포지토리, 실제 파일 크기 확인 완료)
  - 0.6B: `huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`
  - 1.7B: `huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8`
- 공식 문서는 0.6B만 `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`(GitHub 릴리스)로 제공하고, **1.7B는 ModelScope `zengshuishui/Qwen3-ASR-onnx` 의 `model_1.7B`(fp32 분할 파일, decoder 합계 8.1GB)** 를 안내합니다. 앱에서는 tar/분할 파일을 다룰 수 없어, **같은 int8 파일 구성**을 개별 파일로 받을 수 있는 위 리포지토리를 사용합니다(파일 크기가 ModelScope int8 파일과 동일).
- 받은 파일은 `<앱 데이터>/files/models/<모델 id>/` 에 저장되고, 앱의 **삭제** 버튼으로 지울 수 있습니다.
- 1.7B를 쓰려면 저장 공간 약 2.5GB 이상이 필요합니다. 5G/LTE에서 받으면 요금이 크게 나올 수 있으니 **Wi‑Fi** 를 권합니다. 다운로드 중에는 화면이 꺼지지 않게 앱이 유지하며, 중간에 끊겨도 **이어받기**가 됩니다.

---

## 3. 갤럭시에서 키보드 켜기 · 전환

1. **설정 → 일반 관리 → 키보드 목록 및 기본 → 키보드 목록** 에서 **QwenVoice 음성 키보드** 를 켭니다.
   (앱의 **키보드 설정 열기** 버튼으로 바로 들어갈 수 있습니다. 기기마다 경로 표현이 조금 다릅니다: `언어 및 입력 방식 → 화면 키보드`)
2. 아무 입력창(메시지, 메모 등)에서 삼성 키보드의 **⌨/🌐 전환 아이콘을 길게 누르거나 눌러** QwenVoice를 고릅니다.
3. 키보드 화면 오른쪽 위 **⌨ 키보드 전환** 을 누르면 삼성 키보드로 바로 돌아갑니다(길게 누르면 키보드 선택 창).

> 처음 한 번은 마이크 권한을 허용해야 합니다. 앱 첫 화면의 **마이크 권한 허용** 버튼을 눌러 주세요.

---

## 4. 키보드 사용법

| 버튼 | 동작 |
| --- | --- |
| 🎤 **마이크(가운데 원형 버튼)** | **탭** = 녹음 시작 → 다시 탭하면 종료·인식. **길게 눌렀다 놓기** = 누르고 있는 동안만 녹음 |
| 파형 · 상태 | 녹음 중 음량 파형과 경과 시간, 인식이 돌고 있으면 `녹음 중 00:12 · 변환 중…` 표시 |
| `.` `,` `?` `!` `(` `)` `…` | 탭 한 번으로 커서 위치에 기호 입력 |
| **스페이스** | 공백 입력 |
| **⌫** | 탭 = 1글자 삭제, **길게 누르면 연속 삭제**(선택 영역이 있으면 선택 삭제) |
| **⏎** | 입력창의 `imeOptions` 에 액션이 있으면 그 액션 실행(검색·보내기 등), 여러 줄 입력창이면 줄바꿈 |
| **전체지우기** | 입력창 내용 전체 삭제 |
| **0.6B / 1.7B 칩** | 사용할 모델 전환(설치된 모델만 선택 가능, 선택 시 백그라운드로 미리 로딩) |
| **⌨ 키보드 전환** | 이전(삼성) 키보드로 복귀 |

- 녹음은 최대 **10분(600초)** 까지 이어집니다. 말이 끊길 때마다(약 1.3초 무음, 길게 말하면 10~13초) 그 구간을 **녹음을 멈추지 않고** 바로 인식해 커서 위치에 꽂습니다. 말하는 도중에 글자가 올라오고, 마이크를 다시 누르면 남은 구간을 마저 변환한 뒤(`마무리 변환 중…`) 대기 상태로 돌아갑니다.
- 마이크 버튼은 상태를 색과 아이콘으로 보여 줍니다: 대기 = 파란 원 + 마이크, 녹음 중 = 빨간 원 + 정지 사각형, 마무리 변환 중 = 회색 원(비활성).
- 첫 인식 전에는 모델 로딩에 시간이 걸립니다(0.6B 수 초, 1.7B 십여 초 이상). 한 번 올라가면 계속 메모리에 유지됩니다.

---

## 5. 플로팅 마이크 — 삼성 키보드와 함께 쓰기

삼성 키보드(천지인 포함)를 그대로 두고, 화면에 떠 있는 동그란 마이크로 말하는 방식입니다. 키보드를 바꾸지 않아도 되고, 인식 결과가 **지금 커서가 있는 입력창**에 바로 들어갑니다.

### 5-1. 준비 — 권한 2개

앱 첫 화면의 **2. 플로팅 마이크** 카드에서 아래 두 가지를 허용합니다.

| 준비 | 방법 |
| --- | --- |
| **다른 앱 위에 표시** | 카드의 **'다른 앱 위에 표시' 설정 열기** → 목록에서 **QwenVoice 음성 키보드** 켜기 |
| **접근성(자동 입력)** | 카드의 **접근성 설정 열기** → 설치된 앱/다운로드한 앱 → **QwenVoice 자동 입력** → 사용 설정 |

- 안드로이드 13 이상에서 직접 설치한 APK는 접근성을 켤 때 **"제한된 설정"** 으로 막힐 수 있습니다. 그때는 설정 → 앱 → **QwenVoice 음성 키보드** → 오른쪽 위 ⋮ → **제한된 설정 허용**을 한 번 누른 뒤 다시 켜면 됩니다.
- 마이크 권한과(안드로이드 13+) 알림 권한도 함께 확인합니다. 알림을 거부해도 플로팅 마이크는 동작하지만, 녹음 중 알림만 보이지 않습니다.

### 5-2. 켜고 쓰기

1. 카드의 **플로팅 마이크 켜기** 를 누르면 화면에 원형 마이크가 뜹니다(처음엔 오른쪽 가운데쯤).
   기본 설정(**키보드가 뜰 때만 표시**)에서는 **키보드가 올라온 앱 화면에서만** 마이크가 보입니다.
2. 아무 앱의 입력창을 누르고 **플로팅 마이크를 탭** → 말하기 → 다시 탭하면 그 자리에 글자가 들어갑니다.
3. **끌면 위치가 옮겨집니다.** 마지막 위치를 기억하므로 다음에 켜도 그 자리에 뜹니다.
4. 녹음 중에는 원이 빨갛게 바뀌고 위쪽 배지에 **듣는 중 00:12** 가 표시됩니다.
5. 끄는 방법은 **아래 5-3(끌어다 놓아 닫기)** 과 알림창의 **닫기**, 앱의 **플로팅 마이크 끄기** 입니다.

### 5-3. 끌어다 놓아 닫기 (1.4)

마이크 위의 작은 **✕** 버튼은 없앴습니다. 마이크를 누르려다 옆의 ✕ 를 눌러 플로팅 마이크가 꺼지는 일을 막기 위해서입니다. 대신 **끌기 시작하면** 화면 아래 중앙에 원형 **✕ 닫기 존**이 나타납니다.

| 동작 | 결과 |
| --- | --- |
| 마이크를 **끌기 시작** | 화면 아래 중앙에 ✕ 원이 톡 나타납니다(평소에는 보이지 않음) |
| 마이크를 **✕ 원 안으로** 끌어 넣기 | 원이 **빨간색**으로 바뀌고 살짝 커지며 **짧게 진동**합니다 |
| ✕ 원 **안에서 손을 떼기** | 플로팅 마이크가 **꺼집니다**(앱 설정도 꺼짐으로 바뀝니다) |
| ✕ 원 **밖에서 손을 떼기** | 닫기 존만 사라지고 **그 자리에 마이크가 놓입니다**(위치 저장) |
| 그냥 **탭**(거의 안 움직임) | 예전처럼 녹음 시작/정지 |

- 닫기 존은 **터치를 가로채지 않는** 창입니다. 손가락은 계속 마이크가 받으므로, 마이크를 원 위에 올려 둔 상태에서도 그대로 끌다가 놓으면 됩니다.
- 원 안에 있는지는 **화면 좌표**로 판정합니다. 그래서 제스처·3버튼 내비게이션 바 높이나 화면 해상도가 달라도 원하는 자리에서 닫힙니다.
- 녹음 중에 끌어다 놓으면 녹음을 마무리 변환한 뒤 끕니다(이미 들은 말은 그대로 입력됩니다).

### 5-4. 키보드가 뜰 때만 표시 (1.3, 기본 켜짐)

카드의 **키보드가 뜰 때만 표시** 스위치가 켜져 있으면(기본값):

| 상황 | 마이크 |
| --- | --- |
| 키보드가 올라와 있을 때 | 보입니다 |
| 키보드가 내려가 있을 때 | 숨깁니다 |
| **녹음 중이거나 인식(마무리 변환) 중** | 키보드가 내려가도 인식이 끝날 때까지 보입니다 — 중간에 사라지지 않습니다 |
| 접근성(QwenVoice 자동 입력)이 꺼져 있을 때 | 키보드를 알 수 없으므로 예전처럼 항상 보입니다 |
| 스위치를 **끕** 때 | 화면에 항상 떠 있습니다 (접근성 필요) |

- 키보드 표시 여부는 접근성 서비스가 창 목록에서 **입력 방식(키보드) 창**을 확인해 알려 줍니다. 그래서 이 기능을 쓰려면 **접근성(자동 입력)** 이 켜져 있어야 합니다.
- 스위치를 바꾸면 **켜져 있는 플로팅 마이크에 바로 반영**됩니다(끄고 켤 필요 없음).
- 숨김은 오버레이 창을 붙였다 뗐다 하지 않고 보이기만 바꾸므로 깜박임이나 위치 초기화가 없습니다. 숨은 동안 키보드가 다시 올라오면 **마지막 위치 그대로** 다시 나타납니다.

### 5-5. 글자가 안 들어가는 화면

- 접근성이 꺼져 있거나, 은행·비밀번호 입력창처럼 자동 입력이 막힌 화면에서는 인식 결과를 **클립보드에 복사**하고 토스트로 알려 줍니다. 입력창을 길게 눌러 **붙여넣기** 하면 됩니다.
- 키보드 모드와 플로팅 모드는 인식 파이프라인(10분 연속 · 1.3초 무음 컷 · 단어 치환 · 한자 정리)을 그대로 공유합니다. 모델 선택(0.6B/1.7B)과 치환 사전도 함께 씁니다.

---

## 6. 단어 치환 사전 (오인식 자동 교정)

앱의 **4. 단어 치환 사전** 에서 `인식되는 말 → 바꿀 말` 쌍을 자유롭게 추가·수정·삭제할 수 있습니다. 기본 예시로 **강연 → 강현** 1건이 들어 있고, 지우거나 고쳐도 됩니다(**예시 되돌리기** 버튼으로 다시 넣을 수 있음).

두 곳에 동시에 적용됩니다.

1. **인식 힌트(hotwords)** — 등록한 `바꿀 말` 목록을 쉼표로 이어 `OfflineStream.setOption("hotwords", ...)` 로 전달합니다.
   sherpa-onnx v2.0.1의 Qwen3-ASR 구현은 이 값을 **파일 경로가 아니라 쉼표 구분 문자열**로 보고 시스템 프롬프트에 넣습니다(`Qwen3FormatHotwordsForPrompt()` 가 `,` 로 나눠 공백으로 연결). 그래서 경로 오류 위험이 없습니다. 단, 프롬프트가 길어지면 `maxTotalLen` 여유가 줄어드므로 앱은 최대 16개 단어만 전달합니다.
2. **인식 직후 후처리** — `CustomDictionaryPostProcessor` 가 체인의 **맨 앞**에서 결과 문자열을 무조건 치환합니다(긴 표현 우선). 이후 필요하면 LLM 단계로 넘어갑니다.

---

## 7. 문장 다듬기(LLM) 확장 자리

지금은 로컬 STT 결과만 입력합니다. 나중에 Typeless처럼 추임새 제거·말실수 교정·톤 맞춤을 붙일 수 있도록 구조를 미리 잡아 두었습니다.

```
raw STT text
   └─ CustomDictionaryPostProcessor   (항상, 단어 치환 사전)
        └─ 선택 단계: RawPassThroughProcessor(기본) | LlmApiPostProcessor(현재 비활성 스텁)
             └─ ChineseCleanupPostProcessor   (항상, 한자·중국어 구두점 제거)
                       └─ 접근성 자동 입력 또는 commitText(text)
```

- **중국어 문자·부호 정리**: Qwen3-ASR 이 한국어 발화에서도 간혹 섞어 내보내는 한자와 전각 부호를 마지막 단계에서 걷어냅니다. `。`→`.`, `？`→`?`, `！`→`!`, `，`·`、`→`,`, `：`→`:`, `；`→`;`, `「」『』`→`"`, `（`→`(`, `）`→`)` 로 바꾸고, 한자(`[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]`)는 모두 지운 뒤 남은 공백을 하나로 정리합니다(`음. .` → `음.`). 한자를 지우고 나서 한글·영문·숫자가 하나도 없이 구두점만 남는 청크는 **입력하지 않고 버립니다**. 바꿀 것이 없는 평범한 결과는 손대지 않습니다.
- 인터페이스: `postprocess/TranscriptPostProcessor.kt`
- 체인 진입점: `postprocess/PostProcessors.kt`
- LLM 스텁: `postprocess/LlmApiPostProcessor.kt` (`LlmConfig` 에 endpoint·apiKey·model·프롬프트 자리, `isAvailable = false`)
- 설정 UI 자리: 앱 **5. 문장 다듬기** 의 라디오 버튼(LLM 항목은 비활성 표시)

LLM을 붙일 때는 `LlmApiPostProcessor.process()` 에서 네트워크 호출만 구현하고 `isAvailable = true`, 라디오 버튼을 활성화하면 됩니다. 그때부터 해당 단계만 인터넷을 사용합니다.

---

## 8. 프로젝트 구조

```
app/src/main/java/com/sbiografia/qwenvoice/
  MainActivity.kt                     설정 화면(권한, 플로팅 마이크, 모델, 단어 사전, 후처리 자리)
  ime/VoiceImeService.kt              음성 입력 키보드(IME 본체)
  floating/FloatingMicService.kt      플로팅 마이크(오버레이·포그라운드 서비스·실시간 입력)
  floating/VoiceAccessibilityService.kt  포커스된 입력창에 인식 결과 넣기(접근성) + 키보드 표시 감지
  asr/AsrEngine.kt                    sherpa-onnx OfflineRecognizer 래퍼(Qwen3-ASR)
  asr/VoiceRecorder.kt                AudioRecord 16kHz 모노 PCM16 녹음기
  model/ModelSpec.kt                  모델 카탈로그(URL·파일·크기)
  model/ModelRepository.kt            설치 여부/경로/부분 다운로드 계산
  model/ModelDownloader.kt            OkHttp 이어받기 다운로드(+진행률 StateFlow)
  core/AppSettings.kt                 SharedPreferences 설정(플로팅 위치·켜짐 상태·키보드일 때만 표시 포함)
  core/DictionaryStore.kt             단어 치환 사전(JSON 저장, hotwords 생성)
  core/ClipboardSupport.kt            자동 입력이 막힐 때 클립보드 복사
  core/Formats.kt                     바이트/시간 표기
  postprocess/                        후처리 체인(사전 → 기본/LLM → 한자·중국어 부호 정리)
  ui/WaveformView.kt                  음량 파형 뷰
app/src/main/res/layout/keyboard_view.xml   키보드 화면(칩·파형·마이크·기호·편집 키)
app/src/main/res/layout/view_floating_mic.xml  플로팅 마이크 오버레이(원형 버튼·배지)
app/src/main/res/layout/view_floating_drop_zone.xml  끌기 중에만 뜨는 하단 ✕ 닫기 존
app/src/main/res/layout/activity_main.xml   설정 화면
app/src/main/res/xml/method.xml             IME 선언(서브타입)
app/src/main/res/xml/accessibility_service_config.xml  접근성 서비스 선언
app/src/main/res/drawable/bg_floating_badge.xml  플로팅 상태 배지 배경
app/libs/sherpa-onnx-2.0.1.aar              sherpa-onnx 공식 prebuilt AAR
settings.gradle.kts / build.gradle.kts      프로젝트 설정(이 폴더가 Android Studio에서 여는 루트)
.gitignore                                  빌드 산출물·local.properties·서명키 제외
```

---

## 9. sherpa-onnx API 근거 (추측 없이 확인한 값)

AAR를 직접 열어 `classes.jar` 의 바이트코드와 v2.0.1 소스로 확인했습니다.

| 항목 | 값 |
| --- | --- |
| AAR | `sherpa-onnx-2.0.1.aar` (GitHub 릴리스 v2.0.1, 2026‑10‑26) — Maven Central/JitPack 미배포 |
| Kotlin API 클래스 | `com.k2fsa.sherpa.onnx.OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig`, `OfflineQwen3AsrModelConfig`, `OfflineStream`, `OfflineRecognizerResult` |
| `OfflineQwen3AsrModelConfig` 필드 | `convFrontend, encoder, decoder, tokenizer, maxTotalLen, maxNewTokens, temperature, topP, seed, hotwords` |
| 사용값 | `maxTotalLen = 1024`(모델 한계값으로 내부 클램프), `maxNewTokens = 512`, `temperature = 1e-6`, `topP = 0.8`, `seed = 42`, `numThreads = 2~4`, `provider = "cpu"` |
| 호출 순서 | `createStream()` → (`setOption("hotwords", …)`) → `acceptWaveform(samples, 16000)` → `decode(stream)` → `getResult(stream).text` → `stream.release()` |
| tokenizer | 모델 폴더의 `tokenizer` **디렉터리** 경로(내부에 `vocab.json`, `merges.txt`, `tokenizer_config.json`) |
| 특징 추출 | Qwen3-ASR은 멜 128빈 Whisper식 특징을 내부에서 사용(`kQwen3MelDim = 128`, STFT center 정렬) — 앱은 16kHz 샘플만 넘기면 됩니다 |
| 버전 선택 이유 | 1.7B가 반복 루프에 빠지던 문제(k2-fsa/sherpa-onnx#3535) 수정이 v2.0.1에 포함 |

참고 링크: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1> · <https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/> · <https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx>

---

## 10. 제한사항 · 주의

- **CPU 추론만** 사용합니다(GPU/NNAPI 미사용). S24 울트라 기준 0.6B가 실사용 가능한 속도, 1.7B는 정확하지만 체감이 느립니다.
- **문장 단위 스트리밍**입니다. 말이 끊긴 구간마다 순서대로(FIFO) 하나씩 인식해 바로 입력하므로 긴 발화도 중간중간 글자가 올라옵니다. 다만 아직 말하는 중인 구간의 단어는 확정되지 않습니다.
- 녹음은 **최대 10분(600초)**. 그보다 긴 발화는 나눠서 말하는 편이 안전합니다(모델 시퀀스 길이 제한).
- 1.7B는 모델 자체가 2.24GB라 **로딩이 길고 메모리 사용이 큽니다**(앱에 `largeHeap` 설정). 다른 무거운 앱과 동시에 쓰면 시스템이 프로세스를 회수할 수 있고, 그 경우 다음 사용 때 다시 로딩합니다.
- 모델 다운로드 중에는 **앱을 열어 두세요**(앱이 화면 꺼짐을 막습니다). 끊기면 이어받기를 누르면 됩니다.
- HuggingFace 접속이 막힌 네트워크에서는 다운로드가 실패합니다. PC에서 받아 `Android/data/com.sbiografia.qwenvoice/files/models/<모델 id>/` 로 직접 넣어도 됩니다(파일명·경로 동일하게).
- 앱은 인터넷을 **모델 다운로드에만** 사용합니다. 인식·치환은 전부 기기 안에서 처리합니다.
- **플로팅 마이크**는 접근성 API 로 지금 포커스된 입력창을 찾아 글자를 넣습니다. 화면 구조에 따라 입력창을 못 찾거나, 비밀번호 칸처럼 자동 입력이 막힌 화면에서는 **클립보드로 복사**만 하고 토스트로 알려 줍니다.
- 플로팅 마이크는 안드로이드 규칙상 **녹음 중 알림(포그라운드 서비스)** 이 필요합니다. 알림을 지우면 녹음도 함께 끝납니다(고정 알림은 지워지지 않습니다).
- 접근성 서비스는 **포커스·입력창 관련 이벤트만** 받고, 화면 내용이나 인식 결과를 기기 밖으로 보내지 않습니다. 끄고 싶으면 설정 → 접근성에서 **QwenVoice 자동 입력** 을 끄면 됩니다(자동 입력만 막히고 클립보드 복사는 계속 동작).

---

## 11. 문제 해결

| 증상 | 확인 |
| --- | --- |
| 키보드 목록에 안 보임 | 앱을 한 번 실행 → 설정의 **키보드 설정 열기** → 목록에서 QwenVoice 켜기 |
| 마이크를 눌러도 반응 없음 | 앱에서 **마이크 권한 허용** 확인(설정 → 앱 → 권한 → 마이크) |
| `설치된 모델이 없습니다` | 앱에서 모델 다운로드 후 칩(0.6B/1.7B) 선택 |
| 인식이 안 되고 오류 문구 | 앱 데이터의 모델 파일 크기가 표와 같은지 확인(다운로드 중단 시 이어받기) |
| 빌드 시 `Could not find sherpa-onnx-2.0.1.aar` | `app/libs/` 안에 AAR이 있는지 확인 |
| 첫 실행이 오래 걸림 | 정상입니다(모델 로딩). 0.6B로 먼저 테스트 권장 |
| 플로팅 마이크가 안 뜸 | **'다른 앱 위에 표시'** 허용 확인 → 앱에서 **플로팅 마이크 끄기 → 켜기** |
| 키보드가 없을 때 마이크가 안 보임 | 정상입니다(**키보드가 뜰 때만 표시**가 기본). 항상 띄우려면 앱의 **2. 플로팅 마이크** 카드에서 스위치를 끄세요. 키보드가 올라와 있는데도 안 보이면 **접근성(QwenVoice 자동 입력)** 이 켜져 있는지 확인하세요 |
| 플로팅 마이크로 말했는데 글자가 안 들어감 | 설정 → 접근성에서 **QwenVoice 자동 입력** 이 켜져 있는지 확인(안드로이드 13+ 직접 설치본은 **제한된 설정 허용** 필요). 막힌 화면에서는 클립보드로 복사되므로 길게 눌러 붙여넣기 |
| 플로팅 마이크 위치가 화면 밖으로 갔음 | 앱에서 **끄기 → 켜기**(처음 위치로 돌아갑니다). 끌어서 다시 옮길 수도 있습니다 |
| 플로팅 마이크를 끄는 방법을 모르겠음 | **끌기 시작하면** 화면 아래 중앙에 ✕ 원이 나타납니다. 마이크를 그 위로 끌어다 놓으면 꺼집니다(1.4). 알림창의 **닫기** 로도 됩니다 |
