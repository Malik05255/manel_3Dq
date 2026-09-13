# منزلي HAI

تطبيق Android لتحويل مخطط سكني 2D إلى نموذج منظم قابل للمراجعة والتعديل ثم عرضه ثلاثي الأبعاد.

## المسار الإنتاجي الحالي
1. اختيار نوع المشروع.
2. استيراد صورة أو PDF مع حفظ URI الأصلي للمشروع محليًا.
3. تحليل متعدد القنوات: HAI Vision عند توفره + OCR محلي + Raster + Deep Parser عند توفر Backend.
4. بوابة جودة تمنع اعتماد نتيجة لا تحتوي هندسة قابلة للمراجعة.
5. مراجعة المخطط وتصحيحه باللمس أو HAI.
6. حفظ المشروع محليًا مع سجل نسخ.
7. مزامنة اختيارية عبر Supabase بعد تسجيل دخول OTP.
8. عرض 3D محلي عبر SceneView وGLB مولد من هندسة المشروع.

## ما هو مثبت وما هو غير مثبت
- Android build وunit tests وlint تعمل عبر GitHub Actions.
- Deep Parser يعتمد نموذج CubiCasa-style ويجب تقييمه على بيانات سعودية حقيقية قبل أي ادعاء دقة محلية.
- `benchmarks/saudi-floorplans/manifest.jsonl` هو المرجع الوحيد للحالات السعودية المرخصة. إذا كان بلا حالات فإن ادعاءات الدقة السعودية تبقى معطلة صراحة.
- OCR المحلي الحالي مبني على ML Kit Latin؛ النص العربي يحتاج HAI Vision/مصدر OCR آخر أو مراجعة المستخدم.
- 3D الحالي procedural/PBR وليس محرك photorealistic أو ray-traced.
- 4D مؤجل وغير موجود في مسار الإنتاج حتى يعاد تفعيله بطلب صريح.

## الاتصال
### Backend
الافتراضي:
`https://manzili-hai-deep-parser.onrender.com`

المصادقة تفصل بين:
- Backend service token للاستخدام الخدمي/الاختبارات.
- Supabase access token لجلسة المستخدم والمزامنة السحابية.

### AI على الخادم
`render.yaml` يعرّف:
- `AI_ENDPOINT`
- `AI_MODEL_DEFAULT`
- `AI_API_KEY` كـ secret غير محفوظ في المستودع.

وجود `AI_API_KEY` فعلي في بيئة Render مطلوب حتى يعمل `/v1/ai/chat`.

### Supabase
يلزم:
- `SUPABASE_URL`
- `SUPABASE_PUBLISHABLE_KEY`
- تطبيق migration: `backend/supabase/001_manzili_projects.sql`

التطبيق يدعم OTP بالبريد، ويحفظ access/refresh token مشفرين على الجهاز. جدول المشاريع محمي بـRLS حسب `auth.uid()`.

## هوية الإصدار
- `applicationId = com.manzili.hai` — لا يغير.
- الإصدار الحالي: `0.62.0` / `versionCode 62`.
- أي تحديث مثبت فوق نسخة سابقة يحتاج نفس Android signing key مع زيادة `versionCode`.

## الخصوصية والأمان
- أسرار المزود والجلسة تستخدم `EncryptedSharedPreferences`.
- Cleartext HTTP معطل في Manifest؛ الخدمات الإنتاجية يجب أن تستخدم HTTPS.
- Android backup معطل افتراضيًا لأن المشاريع قد تحتوي مخططات منازل حساسة.
- CodeQL يفحص Kotlin/Python، وAndroid CI يشغل unit tests وlint والبناء.

## البناء
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

الإصدار الإنتاجي الموقع يبنى من workflow `Android Production Release` بعد إعداد أسرار keystore المطلوبة.
