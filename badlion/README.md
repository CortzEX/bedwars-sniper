# Badlion — التشغيل جوّه الكلاينت من غير Forge

## الأول: الحقيقة

**مود Forge مش بيشتغل جوّه Badlion Client.** مش قصة صلاحيات — الـ core client
بيدوّر JVM من غير Forge أصلاً، فمفيش `FMLCommonHandler`، مفيش `MinecraftForge.EVENT_BUS`،
ومفيش `@Mod`. الـ jar بتاع `fx.sniper.SniperMod` جوّه Badlion = `NoClassDefFoundError`
قبل أول tick.

فاللي هنا مش "مود لـ Badlion". ده **حاقن + طبقة Java من غير Forge** بتعمل نفس الشغل
بالـ reflection على كلاسات اللعبة نفسها:

| القدرة | مصدرها | محتاجة Forge؟ |
|---|---|---|
| قراءة التاب ليست | `NetHandlerPlayClient#getPlayerInfoMap` | لأ |
| قراءة الـ server id | tab header (`mini42AB`) | لأ |
| قراءة المود + الماب | scoreboard sidebar | لأ |
| إرسال `/p join` أو `/play` | `EntityPlayerSP#sendChatMessage` | لأ |
| أوامر من الـ engine | `snipe_cmd.txt` (نفس ملف الـ Python) | لأ |
| كتابة الحالة | `state.txt` + `probe.txt` | لأ |

## الطبقة الجديدة — `blsnip-loader.jar`

`badlion/loader/` — jar صغير، **صفر dependencies**: مفيش Minecraft، مفيش Forge،
مفيش gradle. كلاسات اللعبة بتتحل وقت التشغيل.

### أسماء الكلاسات مش تخمين

الـ obfuscation بيغيّر أسماء الأعضاء كل إصدار، فالـ loader **مش بيدوّر على أسماء**.
الكلاسات بس هي اللي بالاسم، ومصدرها `CL:` في MCP 9.19 `joined.srg`:

```
Minecraft=ave            NetHandlerPlayClient=bcy     Scoreboard=auo
EntityPlayerSP=bew       NetworkPlayerInfo=bdc        ScoreObjective=auk
GuiIngame=avo            GuiPlayerTabOverlay=awh      Score=aum
GuiNewChat=avt           World=adm                    IChatComponent=eu
```

الأعضاء بتتحل بالشكل (signature)، مش بالاسم:

- الـ instance: الحقل الـ static الوحيد اللي نوعه `Minecraft`
- `thePlayer`: الحقل اللي نوعه `EntityPlayerSP`
- الاتصال: الحقل اللي نوعه `NetHandlerPlayClient`
- التاب ليست: حقل `Map` قيمته `NetworkPlayerInfo` (بيتأكد وقت القراءة، لأن map فاضية مش بتقول نوعها)
- الـ sidebar: `World` → `Scoreboard` → objective في slot 1 → score لكل سطر
- الشات: constructor `(String)` + `addChatMessage(IChatComponent)` + `sendChatMessage(String)`

`notch.properties` بيغطّي الجدول لو حبيت تبدّله، والـ jar شغّال من غيره كمان.

### الطريق الأول — `-javaagent` (ده اللي بيتعمل بيه)

```bat
set JAVA_TOOL_OPTIONS=-javaagent:C:\blsnip\blsnip-loader.jar
```

الـ JVM بيقرا المتغير ده في كل launch، بما فيهم launch بتاع Badlion، وبيشغّل
`Premain-Class` قبل أي كلاس من اللعبة. **مفيش `OpenProcess`، مفيش حقن، مفيش thread
بعيد** — مفيش حاجة anti-cheat بيشوفها كـ injection.

لو مش عايز المتغير يفضل مظبوط على الجهاز كله، حطّه في launcher profile أو في
`bat` صغير بيشغّل Badlion.

### الطريق التاني — `inject.exe`

ده للحقن في عملية شغّالة (قبل ما تدخل ماتش):

```
inject.exe                      # بيدوّر على Badlion لوحده
inject.exe --list               # كل javaw.exe، وعلم [BL] على بتاع Badlion
inject.exe --pid 24188
inject.exe --dll C:\blsnip\blagent.dll --wait 60
```

الملفات لازم تبقى جنب بعض:

```
C:\blsnip\
├── inject.exe
├── blagent.dll
└── blsnip-loader.jar
```

`blagent.dll` بيدوّر على `blsnip-loader.jar` جنبه، أو على `%BLSNIP_JAR%` لو محدد.
اللوج: `agent.log` جنب الـ dll، ولوج الـ Java في `blsnip.log`.

## الملفات اللي الـ loader بيقراها/بيكتبها

مكانها = مكان الـ jar، أو `%BLSNIP_DIR%` لو محدد.

| ملف | مين بيكتبه | إيه فيه |
|---|---|---|
| `targets.txt` | إنت | يوزرنيم في كل سطر، `#` للتعليق |
| `blsnip.properties` | إنت / الـ loader أول مرة | `autoJoin`, `autoQueue`, `queueCommand`, `pollMs`, `joinGapMs`, `maxPerTarget`, `windowMinutes` |
| `snipe_cmd.txt` | `tools/snipe_engine.py` | أمر واحد بس، whitelist: `/play`, `/lobby`, `/p join`, `/swaplobby`, `/rejoin` |
| `state.txt` | الـ loader | `server=`, `mode=`, `map=`, `seen=`, `tab=` |
| `probe.txt` | الـ loader | header + footer + sidebar + التاب ليست زي ما اتقروا بالظبط |
| `blsnip.log` | الـ loader | كل حاجة، بما فيها جدول الأعضاء اللي اتحل |

`probe.txt` هو اللي بتتأكد بيه أول تشغيل: لو `server=` أو `map=` فاضيين، الملف
بيوريك السطور الحقيقية اللي اللعبة باعتاها، ومنها تظبط الـ parsing.

كل الأوامر بتتنفّذ على client thread عن طريق `Minecraft.addScheduledTask`، والـ
thread بتاع الـ loader daemon فمش بيمنع اللعبة إنها تقفل.

## البناء

```bat
cd badlion\loader
build.bat
```

محتاج **JDK 8** بس (`javac` + `jar`). الناتج `badlion\loader\build\blsnip-loader.jar`.

اختبار بدون اللعبة (بيمثّل كلاينت 1.8.9 وهمي بنفس أسماء notch):

```bat
cd badlion\loader\test
run.bat
```

## حدود حقيقية

- **Badlion Anti-Cheat بيقفل الهاندل** على عملية اللعبة. `OpenProcess` هيرجع
  `ERROR_ACCESS_DENIED (5)` — الحاقن بيقولك كده صريح. الحل وقتها الطريق الأول
  (`-javaagent`)، أو حقن **قبل** ما اللعبة تدخل الجيم.
- **حقن DLL في عملية محمية = علم أحمر عند أي anti-cheat.** الطريق الأول مش
  محتاج حقن أصلاً، وده الفرق بين "أداة" و"باند".
- الـ loader بيقرا اللعبة ويبعت أوامر — **مش بيلعب مكانك**. مفيش aim، مفيش reach،
  مفيش أي حاجة بتلمس الـ packets في اتجاه السيرفر غير الشات العادي.
- **مفيش طريقة تدخل ماتش شغّالة.** اللي متاح: `/p join` لو البارتي مفتوح، أو نفس
  الـ queue في نفس الوقت. `/rejoin` بيرجّعك لماتشك إنت، مش لماتش حد تاني.

## الملفات

```
badlion/
├── native/
│   ├── CMakeLists.txt
│   ├── build.bat
│   ├── Injector.cpp     ← inject.exe (اكتشاف العملية + حقن)
│   └── Agent.cpp        ← blagent.dll (JNI attach + URLClassLoader + Bootstrap.install)
├── loader/
│   ├── src/fx/sniper/bl/
│   │   ├── Bootstrap.java  ← premain/agentmain، الـ worker، الملفات، الجارد ريلز
│   │   ├── Mc.java         ← كل حاجة بتتقرا من اللعبة، بالـ signature
│   │   ├── Notch.java      ← جدول الكلاسات (notch + MCP)
│   │   ├── Ref.java        ← reflection آمن
│   │   └── Log.java        ← blsnip.log
│   ├── notch.properties
│   ├── manifest.txt
│   ├── build.bat / build.sh
│   └── test/            ← smoke test بكلاينت وهمي
└── README.md
```
