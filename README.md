# Worta Tank Multiplayer

لعبة دبابات أندرويد باسم الحزمة `com.worta.tanks`. الإصدار `1.0.1`، ورقم البناء `2`. تدعم أندرويد 8.0 أو أحدث، والوضعين الطولي والعرضي.

## التشغيل

ثبّت نفس APK على أجهزة اللاعبين، ثم اتصلوا بنفس شبكة الواي فاي. المضيف يفتح «غرف اللعب»، ويختار العدد والدم وينشئ غرفة. البقية يدخلون الرقم المكوّن من أربع خانات ويضغطون «أنا جاهز». يبدأ المضيف عندما يكتمل العدد والجاهزية.

اللعبة تستضيف خادم الغرفة داخل التطبيق. اكتشاف المضيف يستخدم Android NSD، ونقل الرسائل يستخدم HTTP داخل الشبكة المحلية. الرسومات والأصوات واللعبة مضمّنة في التطبيق، ولا يحتاج اللعب إلى إنترنت أو تنزيل مكتبات. أبقِ تطبيق المضيف مفتوحًا أثناء المعركة. إعداد عزل الأجهزة في بعض الراوترات وشبكات الضيوف قد يمنع اكتشاف الغرف والتواصل.

يدعم اللعب جهازين أو ثلاثة أو أربعة أجهزة، واللعب بالتناوب على جهاز واحد، وخصم الكمبيوتر بثلاثة مستويات.

## المزامنة

الخادم يتحقق من الدور وهوية اللاعب والجاهزية. كل ضربة تحمل وضع الدبابة وقوة الرمي وبذرة الحساب. نتيجة الضرر والتضاريس النهائية تأتي من جهاز المضيف. تُعرض الرمية المتأخرة قبل تطبيق نتيجتها، وتحمل طلبات العميل معرّفًا لتجنب تكرار تنفيذها بعد إعادة المحاولة.

المضيف هو المرجع النهائي لنتيجة المعركة. الغرفة مخصّصة للعب بين أشخاص على شبكة موثوقة. رقم الغرفة يسهّل الانضمام، وتُستخدم رموز جلسة عشوائية بعده. رمز الجلسة ونقل اللعبة يستخدمان HTTP محليًا.

## البناء على GitHub

ارفع محتويات هذا المجلد إلى فرع `main` في مستودع `Tank`. يجب أن تظهر الملفات `settings.gradle` و`app/` و`.github/` في جذر المستودع.

عند الدفع إلى `main` يبدأ Workflow باسم **Build Tank Multiplayer APK**. يمكن تشغيله أيضًا من **Actions → Build Tank Multiplayer APK → Run workflow**.

ينفّذ الاختبارات، ثم البناء وفحص Android Lint. بعد نجاحه، افتح النتيجة ونزّل Artifact باسم `tank-android`. يحتوي ملف APK وZIP للمصدر، وكلاهما يحمل رقم الإصدار.

يزيد رقم البناء تلقائيًا مع رقم تشغيل GitHub Actions، ويُلحقه باسم الإصدار. لتغيير الإصدار الأساسي عدّل `baseVersion` في `app/build.gradle`. احتفظ بمفتاح التوقيع نفسه حتى تقبل الأجهزة التحديث فوق النسخة السابقة.

## التوقيع

كل تحديث يستخدم نفس الحزمة ومفتاح التوقيع. يُحفظ المفتاح خارج المستودع، ويستقبله GitHub Actions عبر Secrets التالية:

- TANK_KEYSTORE_BASE64
- TANK_KEYSTORE_PASSWORD
- TANK_KEY_ALIAS
- TANK_KEY_PASSWORD

عند غياب بيانات التوقيع، يبني Workflow نسخة unsigned للتحقق من التجميع وLint. التثبيت يتطلب توقيع APK بالمفتاح الخاص المحفوظ. تغيير المفتاح لاحقًا يؤثر على التحديث فوق الإصدارات المثبتة.

## التحقق المنفّذ

- اجتازت وحدات Java الحقيقية اختبارات JSON، إنشاء الغرف والجاهزية، الانضمام المتزامن، حصر بدء المعركة بالمضيف، ترتيب الأدوار، رفض الرمي خارج الدور، الطلبات المكررة، إعادة اللعب وانتهاء الجلسة.
- اجتاز خادم HTTP نفسه اختبارًا فعليًا على JVM.
- اتصلت أربع نسخ من منطق اللعبة بخادم Java الفعلي ونجحت حركة الضيف باللمس، والرميات المتأخرة، وتطابق الأرض والدم، وانهيار نقطة الأرض الرفيعة.
- نجح بناء APK وفحص Android Lint على GitHub Actions في التشغيل رقم 2، للإصدار 1.0.1.2 ورقم البناء 102. جرى توقيع APK بالمفتاح المحفوظ خارج المستودع والتحقق منه بواسطة apksigner بنجاح وفق مخططي v2 وv3. لا تزال إضافة Secrets مطلوبة للتوقيع التلقائي للإصدارات المقبلة.
- اكتشاف NSD وWebView على هواتف أندرويد فعلية يحتاجان اختبارًا على الأجهزة. اختبار JVM لا يغطي واجهات Android.

تشغيل اختبارات الخادم محليًا يتطلب JDK 17 أو أحدث:

```bash
bash tests/run.sh
```

لبناء APK محليًا استخدم Android SDK 35 وGradle 8.11.1 وJDK 17:

```bash
gradle :app:assembleRelease :app:lintRelease
```

## 1.1.0 — Landscape arena update

- Android opens in sensor landscape only. Default health is 100%.
- The host simulates all tank movement and approves shots, damage and terrain. Guests send controls, receive smoothed positions and reconcile cumulative terrain history after delayed results.
- Transient transport failures retry the same request ID; sessions survive short interruptions (up to 150 seconds of client retries, server stale-player expiry 180 seconds). Keep the host app open. Closing/restarting the host still ends the room.
- Four-player teams pair red/green against blue/gold. Three-round tournaments keep scores, show rankings and support rematches with the same players. Room settings include trajectory preview visibility.
- Unsupported terrain islands collapse; narrow supports crumble under tanks. A persistent mine arms on the next turn. Shields and drilling shells remain available, including LAN play.
- Adjustable control size, opacity, horizontal/vertical offsets and swapped sides are saved in Android preferences. Quality settings limit visual effects without changing physics; vibration can be disabled. Engine sound, tread motion and damage smoke accompany movement and damage.
- AI uses trajectory search, weapon/repair choices and previous-shot correction, with distinct aiming error by difficulty.

Validation: `bash tests/run.sh` and `npm ci --prefix tests --ignore-scripts && python3 tests/run-game.py`. The latter runs four isolated game clients against the actual Java HTTP server and exercises authoritative movement, delayed/lost packet replies, damage/terrain reconciliation, teams, tournament results, terrain collapse, mines, AI and rendering. Android Wi-Fi discovery and physical-device interactions still need device testing.
