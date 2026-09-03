# Cloudstream Extensions

مستودع إضافات لـ Cloudstream

## التثبيت

1. افتح Cloudstream
2. اذهب إلى Settings > Extensions
3. اضغط على "Add Repository"
4. أدخل هذا الرابط:
```
https://raw.githubusercontent.com/hamedhani1998/sources-arab/main/repo
```
5. اضغط "OK"
6. اذهب إلى Extensions وثبّت الإضافات المتاحة

## للتطوير

### بناء الإضافة

```bash
cd <اسم المجلد>
gradle build
```

### رفع الإصدار الجديد

1. قم ببناء الإضافة
2. أنشئ إصدار جديد في GitHub Releases
3. ارفع ملف `.cs3` كمرفق
