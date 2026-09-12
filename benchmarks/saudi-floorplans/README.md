# Saudi Floorplan Benchmark

هذا المجلد هو عقد بيانات واختبار قابل للقياس لمخططات سكنية سعودية حقيقية **مملوكة أو مرخصة**. لا يحتوي المستودع العام افتراضيًا على مخططات خاصة، ولا يجوز تحويل أهداف 100/300/500 إلى ادعاء ما لم توجد الحالات فعليًا في `manifest.jsonl` وتنجح في فحص الحقوق وإزالة الهوية.

## ما يقاس

`SaudiPlanBenchmarkEngine` يقيس:

- Room type F1
- Wall F1 ضمن سماحية هندسية
- Opening F1 حسب النوع والموقع
- Dimension accuracy ضمن 5% أو 12 سم أيهما أكبر
- Geometry V3 pass/fail
- Overall weighted score

والـCI يفحص كذلك سلامة dataset governance ويصدر `benchmark-contract-report.json`.

## شروط كل حالة

- حق استخدام/موافقة واضحة في `license`.
- `deidentified=true` بعد إزالة الأسماء وأرقام القطع والهواتف وأي بيانات غير لازمة.
- مدينة ومنطقة ونوع مشروع واضح.
- تقسيم ثابت: `train` أو `validation` أو `test` لمنع تسرب القياس.
- `reference` وسم يدوي مرجعي بنفس بنية FloorPlan المنطقية.
- الأصل يمكن أن يبقى في مخزن خاص إذا كان الترخيص لا يسمح بإعادة نشره.

## manifest.jsonl

```json
{"id":"riyadh-001","city":"الرياض","region":"central","project_type":"villa_two","source":"licensed-private","license":"owner-consent-2026-001","deidentified":true,"split":"test","asset":"assets/riyadh-001.pdf","reference":"labels/riyadh-001.json","floors":2}
```

## بوابات العدد

- 100 حالة: أول Benchmark سعودي قابل للنشر داخليًا.
- 300 حالة: تغطية أوسع للمدن والأنماط.
- 500 حالة: هدف نضج لاحق.

`MIN_SAUDI_BENCHMARK_CASES` يستطيع رفع حد CI عند توفر البيانات. القيمة الافتراضية 0 حتى لا يتم تزوير بيانات غير موجودة. إذا العدد 0، التقرير يصرح بذلك صراحة ولا يعلن أي دقة واقعية.
