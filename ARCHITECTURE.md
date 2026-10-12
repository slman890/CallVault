# المعمارية — الواقع الحالي والهدف بعد بوابة التسجيل

تحديث 2026-10-12: أضيفت snapshots للشريحة داخل RecordingDatabase v4، وحماية من تعليق فك الصوت والنموذج الناقص ومراحل التفريغ. لم تُفصل Modules ولم يُدمج dialer. الهوية المثبتة أصبحت `io.github.slman890.localcallassistant`؛ source namespace بقي `com.baba.callvault` لحماية أسماء JNI/AIDL. الوثيقة أدناه توثق baseline السابق؛ التغييرات الحالية في `LOCAL_ASSISTANT_UPDATE.md`.

المراجعة عند commit `9933d929e21cf8b65d16d6a47847d1fe1a033cae`. لا Modules جديدة حاليًا؛ `settings.gradle.kts` يضم `:app` فقط. فصل الوحدات مؤجل إلى نجاح Bluetooth.

## مسار التسجيل الموجود

```text
PHONE_STATE / call monitoring
  -> services/call/PhoneStateReceiver
  -> services/recording/RecordingForegroundService
  -> services/recording/AudioRecordingEngine
  -> privileged RecorderBackend (embedded ADB OR Shizuku)
  -> server/RecorderServiceImpl
  -> DirectAudioRecorderSession OR scrcpy capture/muxing
     [optional: services/recording/handoff + audiohandoff JNI]
  -> finalized audio document
  -> local recordings catalog / routing / existing optional after-call transcription
```

`RecordingForegroundService` ينهي الجلسة ويجمع metadata قبل تشغيل وظائف ما بعد المكالمة. `AudioRecordingEngine` يختار المسار ويتولى الـFD والـmuxer والتنظيف. `RecorderServer` يبدأ بواسطة `app_process` تحت shell UID ويصل إلى التطبيق عبر AIDL/Binder. الـAPK يحتاج استخراج المكتبات إلى `nativeLibraryDir`؛ لذلك `useLegacyPackaging=true` مقصود.

الـaudio source الافتراضي `voice-call`، والـcodec الافتراضي Opus. الالتقاط المباشر وscrcpy يستخدمان 48 kHz في المصدر. تفضيل stereo ثم الرجوع إلى mono ممكن؛ لا يعني stereo فصلًا مؤكدًا لكل طرف على جميع الأجهزة. الاختبار الحقيقي يسجل format الفعلي.

## الوظائف الموجودة التي يعاد استخدامها

| المجال | مواقع المصدر الحالية | الفرق عن الطلب |
|---|---|---|
| كشف المكالمات والتسجيل | `services/call`, `services/recording`, `server`, `integrations` | تتطلب نجاحًا حقيقيًا على الجهاز والبلوتوث |
| فهرس الملفات | `data/recordings/db/RecordingDatabase.kt`, `RecordingEntry.kt` | جدول `recordings` keyed by filename/URI؛ metadata الكاملة المطلوبة ليست جدول `calls` حاليًا |
| النص والبحث | `data/transcripts/db/TranscriptDatabase.kt` | Room وFTS ومقاطع بتوقيت موجودة؛ الهجرات الصريحة تحفظ النصوص |
| التفريغ | `transcription`, `app/src/main/cpp/whispercv.cpp`, `third_party/whisper.cpp` | small q5_1 وturbo q5_0/q8_0 موجودة؛ medium يضاف لاحقًا |
| التلخيص | `summary`, `app/src/main/cpp/llamacv.cpp`, `third_party/llama.cpp` | إعادة استخدام المحرك؛ اختبار مخرجات العربية والمهام مطلوب لاحقًا |
| النماذج | `transcription/model/ModelRepository.kt` | تنزيل اختياري مع checksums، تخزين محلي وحذف؛ لا تنزيل الآن |
| التخزين | `system/storage`, `StorageTarget`, SAF folder preference | الصوت في مجلد SAF يحدده المستخدم؛ ليس App Private Storage حاليًا |
| الواجهة | `ui` | تبقى دون تعديل في المرحلة الحالية |

## الفصل المستهدف بعد النجاح

`call-recorder` يملك كشف البدء/النهاية والرقم/جهة الاتصال والصوت وإغلاق الملف. يعلن `RecordingCompleted` مرة واحدة بعد نجاح إنهاء الملف، مع ID، الاتجاه، البداية والنهاية، route، معلومات codec/Hz/channels ومرجع الملف. لا يحتوي Whisper أو summary أو HTTP upload. يحفظ أخطاء الإغلاق كأخطاء تسجيل ولا يعلن completion لملف فارغ.

`local-transcription` يملك فك PCM، Whisper، اللغة `ar`/auto، قاموس المصطلحات والمقاطع بتوقيت. `local-ai` يستقبل النص المحلي ويعيد الملخص والمهام والمواعيد والأرقام مع مراجع إلى مقاطع المصدر. Repository في طبقة التطبيق يربط الجداول ويحدد إعادة المحاولة؛ الوحدات لا تتصل مباشرة بالواجهة.

يُوسع Room الموجود بمهاجرات تحفظ البيانات بدل إنشاء قاعدة موازية تُكرر الفهرس. يجب استبدال اشتقاق الأرقام/الأسماء من filenames بمفاتيح عشوائية عند تحقيق التخزين الخاص. النسخ الاحتياطي والـLAN خيارات منفصلة مع موافقة صريحة، ولا يغير غياب LAN الوجهة إلى خدمة خارجية.

## مخاطر البناء والتوافق

- R8 معطل في الأصل لأن daemon وhidden APIs وJNI تُستدعى بأسماء نصية؛ تفعيله يتطلب keep rules واختبارًا حقيقيًا للـdaemon والتسجيل.
- ABI الحالية `arm64-v8a` فقط؛ محاكي x86 لا يثبت التشغيل ولا جودة Bluetooth.
- whisper.cpp pinned: `371b5a7561823ab2bb32142d2751e35e7534727b`; llama.cpp pinned: `a298422da78eb75e440a7de0ca408af64d323d93`. لا تحديث للتبعيات في هذه المرحلة.
- الحفاظ على attribution وGPL-3.0 وشروط LICENSE الإضافية. لا مكتبات جديدة مضافة.
