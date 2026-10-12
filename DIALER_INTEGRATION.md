# تطبيق اتصال وتسجيل وتفريغ واحد

تحديث local.2: أُنجز مكون Phone أصلي داخل التطبيق باستخدام Telecom/Compose، بعد طلب المستخدم إضافة تطبيق اتصال. التفاصيل في `PHONE_APP_UPDATE.md`. لم يُنسخ كود Fossify؛ التقييم أدناه يصف خيار الدمج الذي سبق هذا التنفيذ.

التقييم: 2026-10-12. يمكن بناء تطبيق موحد مفتوح المصدر، بشرط إضافة دور تطبيق الاتصال الافتراضي وواجهة المكالمة كاملة، مع إبقاء التسجيل بصلاحيات shell والتفريغ المحلي خلف طبقات مستقلة. لا يتيح دور default dialer وحده تسجيل uplink/downlink؛ [VOICE_CALL يحتاج CAPTURE_AUDIO_OUTPUT](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_CALL).

## القاعدة المرشحة

أرشح **[Fossify Phone](https://github.com/FossifyOrg/Phone)** لهذه المتطلبات: تطبيق اتصال مفتوح المصدر، جهات اتصال، سجل وحظر، تعدد الشرائح واختيار الشريحة لكل جهة اتصال. [سياسة الخصوصية](https://www.fossify.org/policy/phone/) تنص على عدم طلب INTERNET داخل تطبيق Phone وعدم إرسال سجل المكالمات إلى Fossify.

| المشروع | تحقق GitHub الحالي | الترخيص / القرار |
|---|---|---|
| FossifyOrg/Phone | archived=false؛ آخر push 2026-10-11؛ latest release 1.11.1 بتاريخ 2026-02-03 | GPL-3.0؛ مناسب للمراجعة والتكييف مع إبقاء attribution وفحص كل dependency عند الدمج |
| Chooloo/koler | archived=false؛ آخر push 2024-12-27؛ latest release v1.6.1 بتاريخ 2023-06-20 | GitHub يعرض NOASSERTION؛ نشاط أقدم، لا يعتمد قبل فحص ترخيصه تفصيليًا |

راجعت نسخة Fossify عند commit `55de3f3bbb18b33310f91e2e5c046fc181318e26`، لا مجرد وصف متجر. `services/CallService.kt` يطبق InCallService ويستقبل onCallAdded/onCallRemoved، وCallActivity يطابق `Call.details.accountHandle` لعرض SIM. Manifest يعلن DIAL وBIND_INCALL_SERVICE. لم يُنسخ كوده إلى هذا التطبيق ولم تُضاف تبعياته.

## الدمج المقترح بعد بوابة الجهاز

```text
واجهة الاتصال: Dialpad + Contacts + Recents + Favorites + Block list
    -> Android Telecom / InCallService
    -> هوية مستقلة لكل Call + PhoneAccountHandle + اتجاه المكالمة + audio route
    -> Recording adapter -> CallVault recorder backend -> ملف محلي
    -> Room -> Whisper المحلي -> النص -> الملخص والمهام لاحقًا
```

تختار الشريحة قبل الاتصال، ويظهر اسمها أثناء المكالمة وفي سجلها وتسجيلها. الهاتفان/SIM profiles لا يشتركان في متغير session واحد؛ callbacks وcall waiting والمكالمات المتداخلة تتبع هوية المكالمة. direct accountHandle من Telecom أفضل من المطابقة بعد انتهاء المكالمة، بينما يحتفظ سجل Android كمسار دعم عند عدم كون التطبيق default dialer.

الترتيب العملي:

1. إثبات التسجيل والتفريغ وتصنيف SIM على هاتف المستخدم أولًا.
2. ضبط الترخيص والاسم والـpackage، وفحص تبعيات Fossify الفعلية؛ لا دمج ملفات Gradle أو شاشتين متنافستين للمكالمة بلا adapter واضح.
3. إضافة واجهة Dialpad/Contacts/Recents وطلب ROLE_DIALER عبر النظام عند اختيار المستخدم.
4. وصل InCallService بطبقة التسجيل مع معالجة decline، missed، answer، hold، swap، conference وتبدل Bluetooth لكل Call.
5. اختبار SIM/eSIM، البلوتوث، قفل الشاشة، boot، permissions denied، role revoked، notifications والاعتماد على تطبيق النظام للطوارئ. [متطلبات Android لتطبيق الاتصال الافتراضي](https://developer.android.com/reference/android/telecom/InCallService#becoming-the-default-phone-app) تشمل استقبال المكالمات وواجهة الاتصال وإتاحة بدء المكالمات.
6. تسليم APK موحد تجريبي؛ اعتماد release يحتاج اختبارات هاتف حقيقية، signing ومراجعة أمان منفصلة.

التكامل مع تطبيق مغلق يكون عبر الواجهات الرسمية المتاحة له؛ الحصول على كل مزاياه داخل APK الخاص بنا يحتاج كودًا مرخصًا أو تنفيذًا مستقلًا. لذلك مسار Fossify يعطي تحكمًا فعليًا بالكود ويوافق رغبة المستخدم في المعالجة المحلية.

**الحالة الحالية: تقييم مصدر ومعمارية فقط؛ لم يُنشأ default dialer موحد، ولم يتغير تطبيق الاتصال الافتراضي على أي جهاز.**
