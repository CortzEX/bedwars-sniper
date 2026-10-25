# Bedwars Sniper

Client-side Forge 1.8.9 mod + external Python engine for tracking a Bedwars
player's location and joining the same instance.

Two independent halves:

| Part | Runs where | What it does |
|---|---|---|
| `src/` | Minecraft (Forge 1.8.9) | reads the tab list, the sidebar and the tab header; renders a HUD; executes queue commands from a bridge file |
| `tools/snipe_engine.py` | Desktop (Python 3.9+) | polls `api.hypixel.net/v2/status`, detects the in-game → lobby transition, writes the matching `/play` command |

---

## الحقيقة اللي لازم تعرفها الأول

**مفيش طريقة تدخل جيم شغّال.** Hypixel بتقفل الانضمام بعد بدء اللعبة. باب الدخول
الوحيد هو:

1. **البارتي** — `/p join <name>` لو التارجت في بارتي مفتوحة.
2. **نفس الـ queue قبل بدء الجيم** — انت وهو في نفس المود في نفس الثانية.

كل حاجة تانية (تاب ليست، API، هوبينج لوبيز) هي مجرد وسيلة توصلك للباب.

### الحالات اللي بيشتغل فيها الـ engine

| حالة الـ API عند التارجت | النتيجة |
|---|---|
| مفتوح | `mode` + `map` كاملين — أفضل حالة |
| مقفول | الـ engine بيطبع `session hidden` ومش بيعمل حاجة |
| نيكد | أحيانًا `mode` بس من غير `map` |

لو التارجت قافل الـ API: الـ **mod** لسه بيشتغل — التاب ليست بيتقرا من اللعبة
نفسها، مش من الـ API. بس بيشوف التارجت **بس** لو هو في نفس اللإنستانس بتاعك.

---

## البناء

### المتطلبات

- **JDK 8** (مش 17، مش 21 — ForgeGradle 2.1 بيرفضهم)
- Gradle — الأسهل تنسخ الـ wrapper من الـ Forge MDK (شوف تحت)

### الطريقة

```bash
git clone https://github.com/CortzEX/bedwars-sniper.git
cd bedwars-sniper
gradlew.bat build
```

الناتج: `build/libs/bedwars-sniper-1.2.0.jar`

الـ wrapper موجود جوه الريبو (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) ومثبّت
على **Gradle 2.14.1** — أعلى نسخة يقبلها ForgeGradle 2.1. مش محتاج تنزّل Gradle
خلاص، بس JDK 8.

### لو الـ build وقع

أول run بينزّل Minecraft + Forge + mappings (~200MB) وبيعمل decompile —
محتاج **2GB رام فاضية على الأقل**. الرسالة `build daemon disappeared` معناها
الرام خلصت، مش خطأ في الكود.

```bash
set GRADLE_OPTS=-Xmx2G
gradlew.bat build --no-daemon
```

أو استخدم الـ CI: كل push على `main` بيبني الـ jar ويرفعه في **Actions → build →
Artifacts → bedwars-sniper**. نفس الـ workflow بيبني `blsnip-loader` (نسخة Badlion)
و `badlion-injector` (الحاقن)، وبيشغّل smoke test على الـ loader بكلاينت وهمي.

### التركيب في اللعبة

- **Forge 1.8.9 عادي:** حط الـ jar في `%appdata%\.minecraft\mods\`
- **Badlion:** الـ core client مش بيحمّل jars خارجية ولا فيه Forge runtime. شغّل
  `badlion/loader/blsnip-loader.jar` كـ `-javaagent` (`%JAVA_TOOL_OPTIONS%`) أو
  احقنه بـ `badlion/native/inject.exe`. بعد كده **F6** جوه اللعبة = قائمة تكتب فيها
  اسم التارجت و `API key` وتشوف الـ `mode` + `map` كإشعار في الشات وتوست في نص
  الشاشة. التفاصيل في `badlion/README.md`.
- **Lunar:** نفس قاعدة Badlion.

---

## الـ Engine

```bash
cd tools
pip install -r requirements.txt   # لا شيء مطلوب، stdlib فقط
```

### المفاتيح

```bash
set HYPIXEL_API_KEY=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
set BRIDGE_FILE=%appdata%\.minecraft\config\snipe_cmd.txt
set POLL_SECONDS=4
python snipe_engine.py
```

المفتاح من <https://developer.hypixel.net/dashboard> — مجاني.
حد الـ rate limit: **١٢٠ طلب/دقيقة**.

### إعداد التارجتس

افتح `tools/snipe_engine.py` وعدّل:

```python
TARGETS: list[str] = [
    "TargetPlayer1",
    "TargetPlayer2",
]
```

**حد آمن:** ٥ تارجتس على `POLL_SECONDS=4` = ٧٥ طلب/دقيقة. فوق كده ارفع الفاصل.

---

## الإعداد جوّه اللعبة

الملف بيتولد أوتوماتيك في `.minecraft/config/bedwars-sniper.json`:

```json
{
  "targets": ["TargetPlayer1"],
  "autoJoin": true,
  "autoQueueBedwars": false,
  "queueCommand": "play bedwars_eight_one",
  "scanIntervalMs": 500,
  "lobbyHop": false,
  "hopMax": 0,
  "friendProbe": true
}
```

| المفتاح | معناه |
|---|---|
| `targets` | اللستة اللي المود بيدور عليها |
| `autoJoin` | يبعت `/p join` لحظة ما التارجت يظهر في نفس الإنستانس |
| `scanIntervalMs` | كل قد إيه يقرا التاب ليست |
| `lobbyHop` | **سيبها `false`** — على الحساب الأساسي دي ماكرو مكشوف |
| `friendProbe` | `/f list` كل دقيقة عشان يشوف التارجت بيلعب إيه |

**الحساب الأساسي:** `lobbyHop` مقفول. المود بيشتغل passive بس — بيقرا، بيبلّغ،
و بيبعت join لما التارجت يبقى معاك في نفس اللإنستانس.

---

## الاستخدام

1. ادخل **Bedwars Lobby** في اللعبة.
2. شغّل الـ engine على جهازك.
3. Plays عادي.
4. الـ HUD في أعلى الشمال بيوريك:
   ```
   [sniper]
   targets: 1
   state: mini42AB
   server: mini42AB
   map: Lighthouse
   seen: TargetPlayer1 (tab, 12s)
   ```
5. لما التارجت يظهر معاك → المود بيزعّق في الشات + HUD بيبقى أحمر.
6. لما جيمه يخلص → الـ engine بيكتب `/play ...` في `snipe_cmd.txt` → المود
   بينفّذه في أقل من ثانية.

### الهوتكي

**Right Shift** → يفتح لستة التارجتس.

---

## نسب النجاح (تقديرية)

```
P(نجاح) ≈ P(لقيت النافذة) × P(التارجت أعاد الكيو) × P(نفس الجيم)
```

| المرحلة | poll 15s | poll 4s |
|---|---|---|
| لقيت نافذة اللوبي | ٣٠-٤٥٪ | ٧٥-٨٥٪ |
| أعاد الكيو بعد الجيم | ٥٠-٦٠٪ | ٥٠-٦٠٪ |
| نفس الجيم — Solo (٨ سلات) | ٣٥-٤٥٪ | ٣٥-٤٥٪ |
| نفس الجيم — 4v4v4v4 (١٦ سلة) | ٥٥-٦٥٪ | ٥٥-٦٥٪ |

**الإجمالي لكل محاولة:** ٨٪ (poll 15s، سولو) → ٢٦٪ (poll 4s، مود كبير).

**تراكميًا:** ٥ محاولات = ٣٤٪ / ٧٨٪. ٨ محاولات = ٤٩٪ / ٩١٪.

المودات مرتبة من الأسهل:

| المود | السلات |
|---|---|
| Castle | ٨٠ |
| 4v4v4v4 / Doubles | ١٦ |
| 3v3v3v3 | ١٢ |
| Solo / 4v4 | ٨ |

---

## حدود معروفة

- **مفيش join لجيم شغّال.** `/rejoin` بيرجّعك انت بس لجيمك القديم.
- **`/v2/status` و `/v2/recentGames` بيقفوا** لو التارجت قافل الـ API.
- **التاب ليست بيشوف بس اللي في نفس الإنستانس** (٨٠ لاعب في اللوبي).
- **`/swaplobby` مش أمر عام** — بيشتغل جوّه اللوبي بس.
- **`BEDWARS_TWO_FOUR` مش موجود دايمًا** — بس في بعض الدورات.

## البند

- التاب ليست والسايدبار = قراءة شاشة. صفر خطر.
- `/f list` كل دقيقة = تحت الـ rate limit بكتير.
- `/p join` متكرر = ده اللي بيتقفل عليه. الكود بيحدّه ٣ ثواني و٥ محاولات لكل
  تارجت كل ١٠ دقايق.
- `lobbyHop=true` = ماكرو. على حساب أساسي ده أعلى خطر في المشروع كله.
- **مفيش ضمان.** البند بتتاخد في موجات.

---

## بنية المشروع

```
bedwars-sniper/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── tools/
│   ├── snipe_engine.py
│   ├── requirements.txt
│   └── .env.example
└── src/main/
    ├── resources/mcmod.info
    └── java/fx/sniper/
        ├── SniperMod.java        نقطة الدخول
        ├── SniperConfig.java     إعدادات JSON
        ├── TargetManager.java    لستة التارجتس
        ├── TabScanner.java       قراءة التاب ليست
        ├── ScoreboardProbe.java  server id + map
        ├── FriendProbe.java      /f list parsing
        ├── JoinHandler.java      /p join مع guard rails
        ├── LobbyHopper.java      هوبينج (مقفول افتراضيًا)
        ├── CommandBridge.java    تنفيذ أوامر من ملف
        └── gui/
            ├── SniperGui.java    لستة التارجتس
            └── MainHUD.java      overlay
```

## الترخيص

MIT — شوف `LICENSE`.
