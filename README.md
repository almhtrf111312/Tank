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

## 1.1.1 — Named lobbies, explicit teams and live damage

- Nearby LAN rooms are listed by their UTF-8 names using Android NSD service attributes. Refresh runs while the room picker is open; tapping a room sends a join request without typing a code. The numeric room ID remains an optional fallback.
- Hosts approve or reject pending requests and can remove guests before starting. Pending guests cannot mark themselves ready or start playing.
- Players choose team one or team two before readiness. Each team holds at most two players; three-person rooms support 2 versus 1, and free-for-all remains available. Changing teams clears that player's readiness. Everyone receives the exact roster, names and team assignments at match start.
- The host now broadcasts each damage/heal event during the turn. Guests update HP and floating damage numbers immediately on receipt; final turn reconciliation still verifies HP and terrain.

Tests cover named room listing, click-to-join, admission restrictions/rejection, explicit team assignments and capacity, 2-versus-1 starts, and health events arriving exactly once before turn end. Physical Android Wi-Fi discovery still requires device testing. All players should install this version for the updated lobby protocol.

## 1.1.2 — Adaptive rendering, weapon balance and match lifecycle

- Auto quality starts at a maximum 1.25 device pixel ratio. Sustained slow RAF intervals switch to 1x resolution, 30 FPS drawing, fewer flat particles and less ambient weather. Fixed physics remains 60 Hz, and the minimap is refreshed less frequently. Explicit high quality remains available.
- The missile barrage is now named `وابل صواريخ`, fires three missiles for at most 30 direct damage per tank per attack, and uses 10 damage per missile. Cluster, plasma, volcano, meteor, laser, drill, sticky and triple attacks have lower damage and per-target attack budgets. Death bursts with zero damage no longer cause the previous minimum six damage. Fall damage remains independent.
- Android Back (including the modern gesture callback) asks for confirmation. Home/app-switch preserves the live WebView state and runs native room heartbeats for up to two minutes. This does not survive force-stop, process reclamation or closing the host. If the host is backgrounded, host-side physics may wait for its return.
- Each round lasts ten minutes. Room polls share the server deadline. At expiry the host ends the match and broadcasts the winner: highest remaining HP in free-for-all, or greatest total team HP; equal leaders draw.

Validation includes barrage count and accumulated damage, multi-hit weapon budgets, adaptive render scale/effect counts, timer expiry/ties and four-client agreement on the team winner. Actual Snapdragon 685 frame rate, physical-device background behavior and Wi-Fi discovery remain unmeasured.

## LAN protocol v2 (debug / PR only)

See [LAN_RECOVERY.md](docs/LAN_RECOVERY.md) for measured regressions, protocol changes, known limits and physical-device acceptance steps. This branch keeps named local rooms and host approval, adds manual local IPv4:port joining, and rejects protocol mismatches. IDs are six digits; NSD registration detects conflicting advertisements. No passwords or accounts are added.

Guest gameplay is rendered from authoritative host state (including projectiles and deaths); only the host simulates network physics/damage. Reconnection uses `/room/snapshot`, which includes a full RLE collision mask and atomic event cursor. Events are bounded to 512 entries / 512 KiB; regular terrain packets are acknowledged deltas, not match history. Offline and defeated player seats remain reserved until the room is explicitly closed. HTTP/1.1 connections are reused, with 700 ms long-poll, bounded response batches and a 10-second idle socket lifetime.

Debug measurements: `window.tankNetworkMetrics()` in the WebView and local-only `/room/diagnostics` on debuggable Android builds. Counters are bounded and contain sizes, durations, queue lengths and event retention; they do not contain player tokens or signing keys.

Run `bash tests/run.sh`, `npm ci --prefix tests --ignore-scripts`, `python3 tests/run-game.py`, and `TANK_FAKE_CLOCK=1 TANK_GAME_TEST=lan-recovery.cjs python3 tests/run-game.py`. The new workflow builds **debug**, signs with the existing repository key, and runs Android lint. It does not publish or merge a release.
