# Local Call Assistant / مساعد المكالمات المحلي

نسخة تطوير مستقلة من [CallVault](https://github.com/madkongo/CallVault)، وهو مشتق من [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder). الترخيص GPL-3.0 مع الشروط الإضافية في [LICENSE](LICENSE)، والنسب في [NOTICE.md](NOTICE.md). لا يمثل هذا المشروع المؤلفين الأصليين.

الإصدار الحالي: `2.4.5-local.1`، package: `io.github.slman890.localcallassistant`، Android 11+ وARM64. يحتفظ بمصدر JNI/AIDL الأصلي ويستخدم اسمًا وأيقونة وهوية تثبيت مستقلة.

## التغييرات الحالية

- إنهاء انتظار فك الصوت الذي لا ينتج أي خرج لمدة دقيقتين، مع إبقاء التسجيل ورسالة لإعادة المحاولة.
- فشل واضح للنموذج غير المثبت بالكامل بدل انتظار دائم؛ عدد محدود لمحاولات العمل الآلي.
- مراحل التفريغ في المؤشر الموجود.
- حفظ وعرض الشريحة التي استقبلت أو أجرت المكالمة الجديدة، من حساب المكالمة في Android. حالات الغموض تبقى غير معروفة.
- Room migration 3→4 يحفظ معلومات المكالمات دون destructive fallback.
- فحص التحديثات الخارجية معطل افتراضيًا؛ لا نماذج جديدة ولا APIs أو مكتبات مضافة.

**الحالة:** APK تجريبي مبني وموقّع بمفتاح debug. اختبارات الكمبيوتر ليست دليل تفريغ Whisper أو تصنيف SIM أو تسجيل Bluetooth على هاتف. أخطاء lint الأصلية ما زالت مفتوحة. يُختبر التسجيل الحقيقي قبل اعتماد أي مرحلة لاحقة.

## المستندات

- [الإصدار الجديد وطريقة تجربته](LOCAL_ASSISTANT_UPDATE.md)
- [تقييم الدمج مع تطبيق اتصال](DIALER_INTEGRATION.md)
- [خطة المشروع](PROJECT_PLAN.md)
- [مصفوفة الاختبارات الفعلية](TEST_MATRIX.md)
- [المعمارية](ARCHITECTURE.md)
- [الخصوصية وحدود النسخة الأصلية](PRIVACY_MODEL.md)
- [بناء النسخة المرجعية الأصلية](BASELINE_BUILD.md)
- [أدلة التحقق الحالية](test-evidence/LOCAL_ASSISTANT_CHECKS.json)

الصوت ما زال في مجلد SAF يحدده المستخدم. النماذج تنزّل بطلب صريح ومعالجتها محلية؛ بيانات النموذج الخاصة بـCallVault لا تنتقل تلقائيًا إلى التطبيق الجديد. لا تشغل التسجيل في التطبيقين في الوقت نفسه أثناء الاختبار.

## البناء

تحتاج SDK 36، build-tools 36.0.0، NDK 27.2.12479018 وCMake 3.22.1. Gradle wrapper 9.4.1 يحدد JDK 21 للـdaemon، ويمكن تشغيل wrapper من JDK 17. استرجع الـsubmodules المثبتة أولًا.

```powershell
git -c core.longpaths=true submodule update --init --recursive
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

الـAPK في `app/build/outputs/apk/debug/app-debug.apk`. release يحتاج مفتاح توقيع مستقل؛ لا تفعّل R8 قبل حماية entry points الخاصة بالـdaemon وJNI وفحصها على الجهاز.

المرجع الأصلي محفوظ في tag `baseline-original-build`. [توثيق المصدر الأصلي](https://github.com/madkongo/CallVault/tree/9933d929e21cf8b65d16d6a47847d1fe1a033cae) يشرح مسارات ADB/Shizuku والتسجيل التي يعاد استخدامها هنا.
