# نموذج الخصوصية والحدود الحالية

المطلب النهائي: معالجة محلية افتراضيًا، لا Analytics أو Telemetry أو Cloud أو إرسال صوت/نص لخدمة خارجية. هذه الوثيقة تفصل ما في المصدر عن الهدف؛ ليست شهادة أن APK الأصلي يطابق كل المتطلبات.

## الموجود في النسخة المرجعية

| المجال | الواقع في الكود | ما يلزم قبل اعتماد النسخة النهائية |
|---|---|---|
| الصوت | مجلد SAF يختاره المستخدم؛ no default folder URI | تخزين الصوت في App Private Storage، export منفصل اختياري |
| Room / نماذج | قواعد بيانات وmodels في مساحة التطبيق | مراجعة كل الملفات المؤقتة والـsidecars والمهاجرات |
| الاستدلال | whisper.cpp وllama.cpp محليان | اختبار العربية على الجهاز بعد نجاح التسجيل |
| تحديثات | `UPDATE_CHECK_ENABLED=true`؛ GitHub periodic/open checks | تعطيل الاتصالات الخارجية افتراضيًا ومعالجة إعدادات الترقية القديمة |
| تنزيل نماذج | HTTPS إلى Hugging Face بعد اختيار التنزيل | تنزيل/استيراد صريح فقط، SHA256 ومساحة كافية، لا إرسال صوت |
| ADB / Shizuku | loopback/mDNS/wireless debugging لصلاحيات shell | إبقاء مفاتيح pairing خاصة؛ توثيق ports وصلاحيات كل وضع |
| Drive | اختيار LOCAL افتراضيًا، DRIVE/BOTH اختياري عبر SAF | مؤجل؛ لا اختيار مجلد cloud أو تفعيل backup في تجارب التسجيل |
| Android Backup | `allowBackup=false` لكن XML extraction rules تحتاج مراجعة | استبعاد صريح للبيانات ومفاتيح pairing واختبار OEM device transfer |
| Logs | redaction دائم؛ file logging معطل افتراضيًا؛ Logcat calls موجودة وسجل إعدادات | إغلاق logging الحساسة في release؛ مراجعة exceptions، filenames وURI وعمليات JNI |

لا APIs مدفوعة مضافة ولا OpenAI/Google AI/Firebase. لم تُنزّل نماذج ولم تُرفع تسجيلات أو نصوص. تحميل أدوات البناء ومصدر Git من الإنترنت منفصل عن معالجة بيانات المستخدم؛ الـFork المنشور يحتوي كودًا ووثائق فقط.

## بروتوكول اختبار المصدر دون تغيير كوده

قبل أول تشغيل للـAPK المرجعي، اقطع وصول الإنترنت الخارجي عن بيئة الاختبار إن كان منع أي اتصال مطلوبًا. حافظ على شبكة محلية دون WAN عند الحاجة إلى wireless pairing. Wi-Fi قد يحتاج cellular data وإيقاف التحويل التلقائي للبيانات لتبقى البيئة دون WAN. يمكن استخدام Shizuku أو الإعداد عبر USB وفق حالة الجهاز.

بعد الإعداد، عطل Settings → Updates، اختر Local فقط ومجلدًا محليًا على الجهاز، وأبقِ transcription manual دون تنزيل نماذج، وعطل diagnostic logging. لا يكفي تعطيل updater بعد التشغيل لإثبات عدم حدوث أول check قبله. المجلد المحلي SAF أيضًا **ليس** App Private Storage.

## الصلاحيات في manifest الرئيسي

قد يضيف merged manifest عناصر من المكتبات؛ يُفحص APK المبني في `BASELINE_BUILD.md`. لا نطلب هذه الصلاحيات كلها تلقائيًا، ولا نمنح shell permissions أثناء جرد الجهاز.

| Permission | الاستخدام | طريقة/توقيت الاستخدام |
|---|---|---|
| READ_PHONE_STATE | حالة مكالمات شركة الاتصالات | runtime عند تفعيل التسجيل |
| READ_CALL_LOG | اتجاه/أرقام/سجل المكالمة | runtime منفصل؛ الأرقام بيانات حساسة |
| READ_CONTACTS | عرض الاسم وربط الرقم | runtime اختياري للعرض/قواعد جهات الاتصال |
| POST_NOTIFICATIONS | إشعارات التسجيل والـpairing | runtime على Android 13+ |
| moe.shizuku.manager.permission.API_V23 | خدمة Shizuku | يُستخدم فقط عند اختيار وضع Shizuku |
| WRITE_SECURE_SETTINGS | إدارة wireless debugging | privileged عبر shell؛ ليس runtime prompt عاديًا |
| RECEIVE_BOOT_COMPLETED | إعادة الاتصال/التهيئة بعد boot | normal permission؛ اختبر أول مكالمة بعد reboot |
| FOREGROUND_SERVICE / SPECIAL_USE | الخدمات اللازمة للتسجيل والـpairing | manifest، مع إشعار خدمة ظاهر |
| FOREGROUND_SERVICE_DATA_SYNC | نوع خدمة في الأصل | مراجعة Android 15+ timeouts والخدمات |
| INTERNET | ADB networking، updater وتنزيل نماذج | normal، لا يعني وجود upload موافق عليه |
| REQUEST_INSTALL_PACKAGES | تحديث APK بطلب المستخدم | special app access؛ لا يلزم لتسجيل الصوت |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | تقليل قتل خدمات التسجيل | special access عند الحاجة وبعد شرح السبب |
| VIBRATE | اهتزاز الإشعارات | normal |

لا RECORD_AUDIO أو BLUETOOTH_CONNECT أو storage permission عامة في manifest الرئيسي. الالتقاط عبر privileged shell يختلف عن تسجيل الميكروفون تحت UID التطبيق. SAF يعتمد على URI grants. إضافة route metadata لاحقًا تحتاج مراجعة APIs والصلاحيات الأقل لكل إصدار؛ لا نضيفها الآن على افتراض الحاجة.

فحص الـAPK الفعلي كشف أيضًا MANAGE_EXTERNAL_STORAGE من debug manifest، وREAD/WRITE_EXTERNAL_STORAGE يضيفهما merger بسبب مكتبة ADB القديمة، وصلاحيات AndroidX للعمل والبصمة والشبكة. هذه التفاصيل موثقة في `BASELINE_BUILD.md`؛ عدم ظهور الصلاحية في main manifest وحده لا يثبت غيابها من APK.

## حدود الأمان الواجب إكمالها

قبل الاعتماد، يلزم التحقق من caller UID ومن موثوقية الـservice في كل مسار داخل `RecorderBinderProvider`، واختبار رفض المتصل غير المصرح. لا يُفترض أن exported provider آمن لأنه لا يعرض ملفات؛ مراجعة IPC واختبارها على الجهاز لم تكتمل.

MainActivity وShareImportActivity مستقبِلان exported؛ مراجعة Intents/URI grants والحجم ومحتوى الصوت، مع إلغاء أي معالجة غير مأذون بها. FileProvider غير exported ويعرض `cache/logs/` و`cache/exports/` بواسطة grants محددة. PhoneStateReceiver وBootReceiver exported لاستقبال أحداث النظام؛ مراجعة صحة الحدث وإعادة التشغيل والـIPC.

تبقى بيانات الاختبارات الفعلية في `test-evidence/private/` المستثنى من Git. لا يُحفظ رقم أو contact name أو transcript أو MAC أو serial في وثائق عامة. لا logcat dumps واسعة تلقائيًا، ولا رفع bug reports أو screenshots. حفظ نسخة محلية على هذا الكمبيوتر لإجراء الاختبار لا يصرح بإرسالها إلى Cloud.
