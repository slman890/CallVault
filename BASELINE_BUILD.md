# النسخة المرجعية ونتائج التحقق

تاريخ التنفيذ: 2026-10-11. هذه نسخة debug مبنية محليًا من المصدر الأصلي، وليست إصدارًا نهائيًا أو دليل تسجيل فعلي.

## مرجع المصدر

- Upstream: `madkongo/CallVault`؛ غير archived وقت التحقق، آخر push `2026-10-06T10:11:11Z`.
- Fork: https://github.com/slman890/CallVault — كود مصدر عام، دون بيانات الجهاز أو مكالمات.
- Commit: `9933d929e21cf8b65d16d6a47847d1fe1a033cae`.
- `git describe`: `v2.4.5-11-g9933d929`؛ ليس الادعاء بأن هذا APK هو binary الإصدار المنشور في Releases.
- branch: `feature/local-call-assistant`.
- لم تتغير ملفات التطبيق أو الواجهة أو إعدادات بناء APK أو native code أو إصدارات التبعيات. الإضافات وثائق وأداة جرد read-only وإعداد مستقل لاختبارات Windows واستثناء أدلة الجهاز من Git.

## Toolchain

| العنصر | القيمة الفعلية |
|---|---|
| النظام | Windows / PowerShell |
| Java launcher | Microsoft JDK 17.0.20.1 |
| Gradle daemon JVM | Eclipse Adoptium JDK 21.0.12.1، وفق gradle-daemon-jvm.properties |
| Gradle wrapper | 9.4.1، مع distributionSha256Sum الموجود في الأصل |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.3.21 |
| compileSdk / targetSdk | 36 / 36 (Android 16) |
| minSdk | 30 (Android 11) |
| build-tools | 36.0.0 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| APK ABI | arm64-v8a فقط |
| whisper.cpp / llama.cpp license | MIT؛ المشروع GPL-3.0 مع شروط LICENSE الإضافية |

احتاجت البيئة تثبيت SDK platform 36 وNDK وCMake، لا تغيير compileSdk إلى SDK 37 الموجود سابقًا. عولجت مشكلة Git Windows `Filename too long` بخيار `core.longpaths=true` محليًا وإعادة checkout للـsubmodules الجديدة، دون تعديل مصدرها أو إعداد Git العام.

## APK الأصلي

- الملف بجانب المشروع: `../CallVault-original-2.4.5-debug.apk`.
- package: `com.baba.callvault`؛ versionName `2.4.5`؛ versionCode `20451`.
- الحجم: **87,985,110 bytes**.
- SHA256: `e2e879c4d4cce769e00a390f8fd7d1fa0cabdcf23dfa762da852b4679a5adf5c`.
- `apksigner verify --verbose --print-certs`: PASS؛ APK Signature Scheme v2.
- توقيع Android Debug للتجارب فقط؛ cert SHA256: `7cb8f5475ed04973a62650b5b760253229d03d047bfecd3118fc8a87ae227c0b`.
- debuggable=true؛ R8 off كما في المصدر. لا release signing key جديد، ولا ادعاء صلاحية release نهائي.
- إذا كان التطبيق المثبت موقّعًا بمفتاح upstream مختلف، فلا تحذفه ولا تستبدله لتجاوز اختلاف التوقيع؛ ذلك قد يفقد البيانات وADB pairing.

## التحقق

| الفحص | النتيجة |
|---|---|
| `:app:assembleDebug` من المصدر الأصلي | PASS، exit 0؛ BUILD SUCCESSFUL in 3m 31s؛ 47 tasks executed |
| ملفات التطبيق/toolchain/submodules مقارنة بالمرجع | PASS؛ `git diff --exit-code <baseline> -- app build.gradle.kts settings.gradle.kts gradle.properties gradle third_party` دون فروق |
| توقيع/metadata/ABI الـAPK | PASS باستخدام apksigner وaapt2 |
| JVM tests لطبقة التسجيل/ADB/scrcpy/call/redaction | PASS؛ 580 tests في 69 suites، 0 failures/errors/skipped، مع إعداد Windows الموضح أدناه |
| lintDebug | FAIL في المصدر الأصلي: 31 errors و258 warnings و1 hint؛ لم تُخفَ بنتيجة baseline أو تعطيل lint |
| script الجرد | PowerShell parse PASS؛ رفض عدم وجود هاتف دون إنشاء تقرير PASS |
| ملفات pairing/signing/build/device-evidence مستثناة من Git | PASS باستخدام git check-ignore |
| تثبيت وفتح التطبيق على هاتف | NOT_RUN؛ لا هاتف ADB متصل |
| مكالمة عادية/Bluetooth/headset/car/USB-C/LE Audio | NOT_RUN؛ راجع TEST_MATRIX.md |

نتائج قابلة للقراءة الآلية مع مواقع أخطاء lint: `test-evidence/BASELINE_CHECKS.json`. سجل البناء الخام وتقرير الاختبارات الكامل موجودان محليًا في `work/` و`app/build/`، ولا يُرفعان إلى GitHub.

أول تشغيل للاختبارات بنفس filters دون إعداد Windows فشل في 39 من 580 اختبارًا أثناء setup، بخطأ `UnsatisfiedLinkError: no conscrypt_jni`. مكتبة Android Conscrypt ضمن تبعيات التطبيق لا تعمل كـJNI على Windows. تستخدم إعادة الاختبار init script يمرر `robolectric.conscryptMode=OFF` إلى JVM الاختبار فقط، وهو وضع [موثق في Robolectric](https://robolectric.org/configuring/#conscryptmode). لا يغير APK أو مزود TLS الحقيقي، ولا يختبر صحة pairing/TLS على الهاتف، ولم تُستبعد اختبارات. جميع الـ580 نجحت في إعادة التشغيل. الأمر المشترك انتهى exit 1 بسبب lint فقط.

أخطاء lint الأصلية: MissingPermission (1)، NewApi (5)، WrongConstant (2)، MissingQuantity (16)، LocalContextGetResourceValueCall (5)، NonObservableLocale (2). تبقى هذه مراجعة وإصلاحات مطلوبة، ولا توصف النسخة بأنها اجتازت جميع الفحوص أو أنها production-ready. لا تعديل لكود التطبيق قبل اختبار المصدر على الهاتف كما اشترط المستخدم.

الـAPK يحتوي 18 مكتبة native لـARM64، وملف model واحد صغير هو `assets/ggml-silero-v5.1.2.bin` (885,098 bytes، VAD أصلي). لم تُضمّن نماذج Whisper/LLM كبيرة ولم تُنزّل.

## صلاحيات إضافية ظهرت في APK المدمج

إضافة إلى manifest الرئيسي الموثق في `PRIVACY_MODEL.md`:

| Permission | المصدر / الملاحظة |
|---|---|
| MANAGE_EXTERNAL_STORAGE | `app/src/debug/AndroidManifest.xml` لاختبارات benchmark الأصلية؛ لا تمنحها أثناء اختبارات التسجيل؛ ليست في main manifest |
| USE_BIOMETRIC / USE_FINGERPRINT | AndroidX Biometric لقفل التطبيق |
| WAKE_LOCK / ACCESS_NETWORK_STATE | مكتبات العمل/جدولة المهام؛ راجع manifest-merger report |
| com.baba.callvault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION | AndroidX Core؛ signature permission |
| WRITE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE | أضافهما manifest merger بسبب targetSdk القديم لمكتبة `io.github.muntashirakon.adb`؛ لا تُستنتج الحاجة إليهما لتسجيل SAF، وتراجع إزالتهما بعد بوابة الجهاز |

وجود INTERNET/updater وSAF يعني أن الـAPK المرجعي لا يحقق بعد السياسة النهائية المطلوبة. راجع `PRIVACY_MODEL.md` قبل أول تشغيل. لا تنزيل نماذج ولا استدلال Whisper/llama ولا call data uploads في هذه المرحلة.

## إعادة البناء على Windows

استخدم JDK 17 مثبتًا وSDK بهذه الحزم؛ Gradle يختار JDK 21 وفق الملف الأصلي. هذه أوامر تطوير، لا تمنح صلاحيات للهاتف.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
git -c core.longpaths=true submodule update --init --recursive
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat -I scripts/recording-tests-windows.init.gradle :app:testDebugUnitTest --tests 'com.baba.callvault.server.*' --tests 'com.baba.callvault.services.call.*' --tests 'com.baba.callvault.services.recording.*' --tests 'com.baba.callvault.integrations.adb.*' --tests 'com.baba.callvault.integrations.scrcpy.*' --tests 'com.baba.callvault.utils.LogRedactionTest' --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain # Known baseline failure: see findings JSON
```

لا تعلن `milestone-recording` أو `milestone-bluetooth` أو `milestone-whisper` من هذه النتائج. المرحلة الحالية هي تجهيز واختبار baseline على الكمبيوتر؛ الاستمرار في تطوير ميزات التطبيق ينتظر دليل الهاتف الحقيقي.
