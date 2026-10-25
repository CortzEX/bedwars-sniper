# Badlion — الحقن جوّه الكلاينت

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
| قراءة الـ server id + الماب | tab header + scoreboard | لأ |
| إرسال `/p join` أو `/play` | `EntityPlayerSP#sendChatMessage` | لأ |
| قراءة الشات | `ClientChatReceivedEvent` ← **بديل**: hook على `GuiNewChat#printChatMessage` | لأ |

الطبقة دي **الجزء التاني** — لسه مش مرفوعة. الجزء اللي هنا = الحاقن نفسه، كامل.

## الطريقين — واحد منهم بس محتاج الحاقن

### ١. `-javaagent` عند التشغيل (أنضف، مفيش لمس للعملية)

```bat
set JAVA_TOOL_OPTIONS=-javaagent:C:\blsnip\blsnip-loader.jar
```

الـ JVM بيقرا المتغير ده في كل launch، بما فيهم launch بتاع Badlion، وبيشغّل
`Premain-Class` قبل أي كلاس من اللعبة. مفيش `OpenProcess`، مفيش thread بعيد،
مفيش حاجة anti-cheat بتشوفها كـ injection.

### ٢. `inject.exe` — حقن في عملية شغّالة

ده اللي هنا. بياخد `javaw.exe` بتاع Badlion ويحمّل `blagent.dll` جوّاه.

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
└── blsnip-loader.jar        ← الجزء التاني
```

`blagent.dll` بيدوّر على `blsnip-loader.jar` جنبه، أو على `%BLSNIP_JAR%` لو محدد.
اللوج: `agent.log` جنب الـ dll.

## البناء

```bat
cd badlion\native
build.bat
```

محتاج **JDK 8** (بس لـ `jni.h`) و**CMake** و**VS 2022 C++ workload**. الناتج في
`badlion\native\build\bin\`.

## حدود حقيقية

- **Badlion Anti-Cheat بيقفل الهاندل** على عملية اللعبة. `OpenProcess` هيرجع
  `ERROR_ACCESS_DENIED (5)` — الحاقن بيقولك كده صريح. الحل وقتها الطريق الأول
  (`-javaagent`)، أو حقن **قبل** ما اللعبة تدخل الجيم.
- **حقن DLL في عملية محمية = علم أحمر عند أي anti-cheat.** الطريق الأول مش
  محتاج حقن أصلاً، وده الفرق بين "أداة" و"باند".
- `%BLSNIP_JAR%` مقصود: تخلّي الـ jar في مكان والشغل في مكان تاني.

## الملفات

```
badlion/
├── native/
│   ├── CMakeLists.txt
│   ├── build.bat
│   ├── Injector.cpp     ← inject.exe (اكتشاف العملية + حقن)
│   └── Agent.cpp        ← blagent.dll (JNI attach + URLClassLoader + Bootstrap.install)
└── README.md
```
