# Musicolet — mechanics, recovered from the real code

Package `in.krosbits.musicolet` · APK SHA-256 `2e696fd282711807a1340d0fb7d64ab668b9b8de63ada9471fa5bf7c55a031b0`
Target SDK 37, minimum SDK 32. Two DEX files: `classes.dex` (6,102,988 B, 4,656 classes) and
`classes2.dex` (161,408 B, 122 classes).

This file is the specification a clean rebuild should follow. Every rule below was read out of the
app's own bytecode, not guessed. Where a claim is an inference it says so.

## How these were read, and what could not be read

| Engine | What it did | Result |
|---|---|---|
| Craft Anything `android_method_show` | decodes the Dalvik bytecode inside the APK | **this is the citation source** |
| JADX 1.5.6 (`android_method_show` with `java_source: true`) | third-party Java decompiler | produced Java for `AAProvider`; interpretation only, not evidence |
| Apktool, Java, Ghidra 12.2, adb | installed and located | **not used — there is nothing for them to decompile** |

**There is no readable native program in this app.** A controlled byte search of the APK's file
list found 0 `lib/` entries and 0 `.so` files, in two text encodings, with a positive control that
did match (`ev_56568654…fef4e`). Opening the APK with the native reader fails outright:
`not_supported — "Not a readable ELF, PE or Mach-O file: file is malformed (Unknown file magic)"`.
Ghidra's decompiler takes ELF/PE/Mach-O binaries. An Android APK is a ZIP of Dalvik bytecode, so
Ghidra has no input here. The class `in.krosbits.nativex.NativeLibx` declares `native` methods but
ships no library to implement them (`ev_05f27a59…`, `ev_2b808070…`).

The 24 `assets/*` payloads are encrypted PairIP blobs, not machine code. Their first bytes are
`00 49 41 50 02 00 00 00 08 07` (the `"IAP"` marker), and a byte scan of
`assets/fhQ4m5FYWVDTYLVQ` found no ELF header. Reading them would require running the target's
decryptor; static pseudocode cannot be produced from them.

**"Addresses" used below.** A DEX file has no virtual addresses. Each rule therefore cites:
`<DEX file>` · `<method signature>` · `<code-unit offset>`. To find it again, decompile that method
and page to that offset. Code-unit offsets are hexadecimal.

---

## 1. `MusicService.onStartCommand(Intent, int, int)`

`classes.dex` · `Lin/krosbits/musicolet/MusicService;->onStartCommand(Landroid/content/Intent;II)I`
`public final` · 16 registers · **774 instructions / 3,168 code units** · 6 try blocks.
Fully read, first instruction to last.

### 1.1 How it starts

1. Logs `"MSSSC:" + intent`.
2. Reads `MyApplication;->w:I`. If that is `4` or `-1`, **or** the Intent is null, it calls
   `super.onStartCommand` and returns. Nothing else happens. These two integers are a
   "do not start" switch owned by the application object; their meanings are unknown.
3. Otherwise it hands the Intent to a queue: `PendingIntent.getService(ctx, o0.size(), intent, 0x42000000)`
   appended to `o0:Ljava/util/LinkedList`. So every command is **queued**, not acted on immediately.
4. If `MyApplication;->n` is non-null and the flag `MyApplication;->o:Z` is false, it builds
   `new Lu21(ctx)` and posts a notification with id **`0x78`** (120), then sets `o = true`.
5. It releases the wake lock `Lv82;->i:PowerManager$WakeLock` (inside a `try`).
6. It sets `v14 = 2` — this is the value returned at the end. See §1.4.

If the device is API 26 or newer it also reads a boolean extra `"EXT_NFG"`.

### 1.2 The setting-refresh guard

Eleven separate branches in this method use the same shape:

```
sget-boolean vN, Lin/krosbits/musicolet/MusicService;->H0:Z
if-nez vN, :skip            <-- note: G() is called when the flag is TRUE, not false
invoke-... ->G()V
```

A few branches use the instance field `MusicService;->x:Z` instead. Whether `x` and static `H0` are
the same flag or two flags is **not settled** (recorded as an open unknown). A rebuild should treat
them as one "settings changed, refresh the UI" signal until proven otherwise.

### 1.3 The action table — all 20 branches, in code order

`G()` = the refresh call described in §1.2. "guarded" means the branch only runs when that flag is true.

| Intent action | Guard | What it does |
|---|---|---|
| `ACTION_INIT` | none | calls `G()`; if `MusicActivity;->S0` is non-null and not finishing, calls `MusicActivity;->u0(LMusicService;)` |
| `ACTION_NEXT` | none | `R(Z,Z,Z)` with extras `EXT_DIP`, `EXT_IFOAU` (default `true`) |
| `ACTION_PREV` | none | `S(Z,Z,Z)`, same extras |
| `ACTION_PLAY_PAUSE` | none | `W([])` — plain toggle |
| `ACTION_PLAY` | guarded | if `j` is true → `W([])` |
| `ACTION_PLAY_BL` | guarded | if `m()` is true and `c50;->v(E0)` is true → set `Z=false` then `W([])`; else `Z=true` |
| `ACT_NOTCL` | guarded (inverted) | if `MyApplication;->s:I` > 0 and `j` → **re-starts itself** with `startService(sameAction)` |
| `android.media.AUDIO_BECOMING_NOISY` | none | `N()` |
| `a_atcl` | none | gated on `s`, `G0`, `F0:Lac;`, `j`; then if `x`,`y` and `J()` → `h0()`, else `MusicActivity;->l0()` + `stopSelf()` |
| `a_sltmcl` | none | if **not** (`f` && `G0` && `j`) → show Toast resource `0x7f120573` and reset `b`,`h`,`i`; else clear `f`, call `F0;->K()` and `zy;->f()Z()`, and if `g` → `r()` |
| `"AF10"` | guarded | `s0()` |
| `"AR10"` | guarded | `r0()` |
| `"musicolet.media.r.1"` | guarded | `x()->Lrn1;`; if `i()` → `aw0;->d(this, action)`, else if `!j` → `W([])` |
| `"ACT_MID"` | guarded + `mk1;->b()` | reads String extra `EXT_MID` → `aw0;->d(MusicService, String)` |
| `"ACT_S_P"` | guarded + `mk1;->b()` | reads String extra `EXT_S_Q` and Bundle extra `EXT_S_E` → `aw0;->e(MusicService, String, Bundle)` |
| `"ACT_FAV"` | guarded (inside try) | reads int extra `EXT_S_RID` (0) → looks up the song with `em0;->a(I)`; if it is already a favourite, `r71.f(song)`, else `r71.a(song)`; then `s71.j()`; if the toggled song is the current one, refreshes `p71.c1()` / `b81.F0`; jumps to the tail |
| *(the block at `0461`, no action name)* | none | see §1.5 |
| `"ACT_SFL"` | guarded | `D0 = !D0` via `y0(Z)` — a **single on/off setting** |
| `"ACT_RPTS_TGL"` | guarded | **3-way cycle**: if the UI object `g2` reports a value (`g2->w()`), it sets `g2->L(...)` to the next of the set `{0, 2, 1}`; otherwise it does a read-modify-write of SharedPreferences key **`"RSM"`**. Then `p71.i1()` if `sa0->Y()`. Then **`a0(true)`**. Jumps to the tail |
| `"ACT_REPTQ_TGL"` | guarded | **counter with wrap** (see §1.4). Then a three-check tail (`S0`, `d0`, `sa0->Y()`) → `p71.i1()`. Then **`a0(true)`**. Jumps to the tail |
| `"A_RMMS"` | guarded | **sets both settings at once** (see §1.6) |
| `"ACT_RNF"` (case-insensitive compare) | none | `MusicService;->O0()` then the tail |
| `aw0;->b` (a static String action name) | `aw0;->d:Z` must be true | reads a Bundle: int `"repeat"` ≠ -1 → `aw0;->c:Lh01;`→`h01;->m(I)`; int `"shuffle"` → `h01;->o(I)` |

These 20 branches are the **complete** set; the method has no other action comparisons.

Note: the two `equals` calls in this method are written in **different operand orders**
(`"literal".equals(action)` at `0x511`, the other way round elsewhere). A rebuild must not assume a
consistent order when reading this method.

### 1.4 `"ACT_REPTQ_TGL"` — the counter, exactly

Code units `0x511`–`0x534`, inside a `try` whose only handler is `NumberFormatException`.

```
v14 = 2                                     default value
n   = prefs.getInt("RQM", v14)              read
n   = n + 1                                 add
if (n == v3) n = v2                         v3 = 3, v2 = 1  → wrap
prefs.edit().putInt("RQM", n).apply()       write, asynchronous
```

Consequence: whatever the stored value, **after one press `"RQM"` is 1 or 2** — `0` is never a
reachable result of this path. So this is effectively a **two-value setting implemented as a
counting save**. Only `"A_RMMS"` (§1.6) can put another value there.

Also in this method: `v14 = 2`, `v2 = 1`, `v3 = 3`. The `const` that loads `v1` and `v4` is still
unread; `v1` behaves as 0 or a sentinel and `v4` is unused.

### 1.5 The unnamed block at code unit `0461`

```
045c: move-result v13                         result of an earlier call
045d: if-eqz    v13, :0481                    null → skip the whole thing
045f: v13 = MusicActivity;->e0:Lb81;          the "now playing" view/state object
0461: v0  = v13.b81;->F0:Z                    a boolean flag on it
0463: if-eqz    v0,  :0481                    false → skip
0465: v0  = v13.b81;->d0:I                    an int
0467: if-ne    v0, v2, :0481                  not equal to 1 → skip
0469: v13.b81;->O0()V                         do the reset/refresh
046c: v13 = MyApplication;->i  (the app object)
046e: ctx = v13.getApplicationContext()
0472: v13 = s71.c(ctx)                        ← returns an r71
0476: v13 = r71.d()Z
```

So this branch only fires when the now-playing view exists, its flag `F0` is true, and its `d0`
equals 1. It then calls `b81.O0()` and runs the `s71.c(ctx)` → `r71.d()` pair. `b81.F0` is the same
field `"ACT_FAV"` refreshes, so `F0` plausibly means "a song is showing". This whole block is wrapped
in a `try` whose handler just calls `printStackTrace()`.

### 1.6 `"A_RMMS"` — one request that writes both settings

Code units from `0x555`.

1. Reads `getIntExtra(<obfuscated key>, 0)`; the key is the static String field
   `TrkYWAZqHfxd` on the decoy class `…HQCspMiP` (see §5.4).
2. A local is coerced to the value `2`.
3. If the extra is `0`, it bails out.
4. If the cache field `MusicService;->q0:I` already equals it, it bails out.
5. Otherwise it maps the requested mode onto a **pair** of values and writes **both** `"RSM"` and
   `"RQM"`, skipping any value equal to the sentinel in `v1`.
6. It stores the requested mode in `q0`, then runs the `p71.i1()` refresh tail.

**This is the key structural fact: `"RSM"` and `"RQM"` are one setting family with a shared writer,
not two independent mechanisms.** The three UI actions (`ACT_SFL`, `ACT_RPTS_TGL`, `ACT_REPTQ_TGL`)
are three different *entry points* into it, and `A_RMMS` is a fourth that sets both together from a
single requested mode.

Reading of the three keys together (inference): `"RSM"` holds a **3-state mode** (the cycle set is
`{0, 2, 1}`), `"RQM"` holds a **2-state flag** stored as 1 or 2, and `D0` is a plain boolean. What
the letters stand for is a guess — the plausible expansions are repeat/shuffle style settings, but
the bytecode only tells us how many states each has and which key it lives under.

### 1.7 The tail, and the return value

Code units `0x61f`–`0x633`. Releases the wake lock if it is held, catches `Throwable` and calls
`printStackTrace()`, then **returns `v14` = `2`**.

`2` is `Service.START_NOT_STICKY`: Android will **not** restart the service after killing it. A
rebuild must return 2 from this method unless it wants sticky behaviour.

### 1.8 The six try blocks

| start–end (code units) | handler | catches |
|---|---|---|
| `0x5e`–`0x6d` | `0x6e` | any (`null` exception type) |
| `0x511`–`0x534` | `0x534` | `NumberFormatException` |
| `0x546`–`0x569` | `0x569` | `NumberFormatException` |
| `0x28d`–`0x2ad` | `0x2af` | any |
| `0x3fa`–`0x487` | `0x402` | any |
| `0x61f`–`0x62e` | `0x62f` | any |

The two `NumberFormatException` handlers on blocks that contain no string-to-number conversion are
**unexplained** — possibly widened by R8, possibly covering code reached from elsewhere. Do not
copy them; a rebuild should catch what it actually throws.

---

## 2. `providers.AAProvider` — the album-art content provider

`classes.dex` · `Lin/krosbits/musicolet/providers/AAProvider;`
Authority is the class name; `exported=true`, **no permission**, no intent filters.
Fully decoded, plus a JADX Java decompilation (`ev_fb170432…a8dc`).

### 2.1 It is a reflection trampoline

All six `ContentProvider` overrides are one-liners that invoke a stored `Method`:

| override | invokes |
|---|---|
| `onCreate` | `qIKpeGxYSTkS` |
| `query` | `EdlRADddwv` |
| `delete` | `bdaGImrnkL` |
| `insert` | `TBpSK.hhxRXQ` |
| `update` | `cDTP.qFkiCswog` |
| `getType` | **real code** |

The five `Method` objects live in **three different decoy classes borrowed from real libraries**:

- `org.jaudiotagger.audio.aiff.chunk.Gg.pUQfHBGokO` — 5 `public static Method` fields, 0 methods
- `com.afollestad.materialdialogs.color.zB.Yu.cDTP` — decoy import
- `com.google.android.material.search.WK.Vys.TBpSK` — decoy import

Those packages are real third-party libraries (Jaudiotagger, MaterialDialogs, Material Components),
which is what makes the decoys convincing. A search of both DEX files found **zero instructions that
write** (`sput-object`) to any of the five fields — meaning whoever fills them uses reflection,
native code, or is invisible to static search. That is an **open unknown**, not a missing feature.

Consumers found: `AAProvider` itself (`onCreate`, `query`, `delete`, `insert`, `update`) and
`MusicActivity` (`onStart`, `onDestroy`, via helpers `c`/`d`).

### 2.2 The security token — the exact formula

The field `AAProvider.b` is set to `UUID.randomUUID().toString()` — **a fresh random token per
process**. That is the whole access control.

- `d(dm0)` builds an art URI and embeds `k = (songId + b).hashCode()`
- `e(Uri)` recomputes that hash from the URI's `rid` and the current `b`, and compares

So: `query` is open to any app. **The image paths (`getType`, `openFile`) require a token that only
exists inside this running process.** A rebuild must reproduce this, and must reproduce the fact
that the token changes on every process start — a stale URI stops working.

### 2.3 URI vocabulary

Two path segments and a fixed set of query parameters:

| name | meaning |
|---|---|
| `raabl` | album art as a bitmap |
| `raarp` | album art from a resource |
| `fa` | fallback drawable when there is no art |
| `rid` | song / row id |
| `fl` | full-size flag |
| `k` | the token (§2.2) |
| `srids` | comma-separated song ids (for a collage) |
| `spcpx` | spacing in pixels (collage) |
| `crdpx` | corner radius in pixels (collage) |
| `ufaaf`, `unlc` | read but never used |

Methods: `a(int, int, Uri)` returns one song's art, `b(int, int, Uri)` builds a **collage**, `c(...)`
and `d(...)` build URIs, `e(...)` checks the token. Helpers: `p3.g/c/i` decode art / load a resource
/ open a stream, `tc1.n` builds the collage, `dm0.b` is a song id and `dm0.c()` its art path,
`MyApplication.j.c.a(int)` looks a song up by id. `getType` returns `application/octet`.

---

## 3. `RemoteMediaControlReceiver.onReceive` — a headphone-button receiver

`classes.dex` · 12 instructions. **It contains no media logic at all:**

```
VMRunner.invoke("fhQ4m5FYWVDTYLVQ", Object[]{ context, intent, <local> })
return
```

That is PairIP (Google Play's code protection). The real code is not in the DEX; it is decrypted or
interpreted at run time by the `com.pairip.*` classes. **A rebuild cannot recover it statically, and
neither can this analysis.** What the key `"fhQ4m5FYWVDTYLVQ"` maps to inside `assets/` is unknown.

The **real** code is in the *other* method, `a(Context, Intent)` — `public static`, 128 instructions,
no try blocks, called from somewhere not yet located:

1. reads `"android.intent.extra.KEY_EVENT"`
2. if preference `"k_b_igbdmbev"` is true → **return immediately** (a user-facing "ignore headset
   buttons" switch)
3. logs `"mbi k=<keycode>, act=<action>"`
4. dispatches on `keyCode` via a `packed-switch`, forwarding to `MusicService` through `Lwf0;->n0`

| keyCode | result |
|---|---|
| 85 | `ACTION_PLAY_PAUSE` |
| 86, 127 | pause |
| 87 | `ACTION_NEXT` |
| 88 | `ACTION_PREV` |
| 89 | `"AR10"` |
| 90, 272 | `"AF10"` |
| 126 | `ACTION_PLAY` |
| 273 | `"AR10"` |
| `0x4f` (79) | `"ACTION_HSH"` plus `putExtra("EXTRA_KA", int)` |

**No caller check, no permission test** anywhere in `a(...)`. The receiver is exported with no
permission, so any app on the device can send it a `KEY_EVENT`. Whether the *hardware* button path
actually reaches this method is unknown.

---

## 4. `MyReceiver.onReceive` — a thin forwarder

`classes.dex` · 39 instructions, complete.

- null action → return
- `"a_thchal"` → `Liz1;->n()V` + `MyApplication.s()`, return
- otherwise: **if `MusicService.Q0` is null → return**
- matches `"android.media.AUDIO_BECOMING_NOISY"`, `"a_atcl"`, `"a_sltmcl"`; on a match it calls
  `Lv82;->a()V`, builds `new Intent(context, MusicService.class)`, copies the action, and calls
  `startService`
- the tail is wrapped in `try`/`catch (Throwable)` → `printStackTrace()`

It declares **no intent filter** in the manifest, so its four actions are broadcast from inside the
app itself (inference). It is exported with no permission regardless.

---

## 5. Five techniques used to hide the code

A rebuild does not need to copy these, but it does need to know why parts of the app cannot be read.

1. **PairIP `VMRunner.invoke` with a 16-character key.** Methods replaced by a stub:
   `RemoteMediaControlReceiver.onReceive` and possibly others. The real code lives outside the DEX.
2. **Reflective `Method` dispatch through decoy library classes.** `AAProvider`'s six overrides are
   trampolines into `Method` fields parked on three unrelated real-library packages.
3. **`android:name = com.pairip.application.Application`.** The manifest names Play's shim as the
   application; the real `MyApplication` is swapped in at startup. So "who runs first" is not
   readable from the manifest.
4. **String constants laundered through static fields.** The decoy class `…HQCspMiP` is a vault of
   **47 `public static String` fields and 0 methods** — including `TrkYWAZqHfxd`, an intent-extra
   key. Who assigns them is unknown (0 methods means there is no `<clinit>` to read, or
   `android_class_show` omitted it).
5. **Whole-class placeholder imports.** Decompiled `import` lists point at decoy classes, so imports
   in JADX output are **not** trustworthy evidence of a dependency.

---

## 6. Still unknown — do not guess these in a rebuild

- What `MyApplication;->w` values `4` and `-1` mean (the "do not start" switch).
- What the flag fields `x`, `H0`, `D0`, `j`, `g`, `y`, `f`, `s`, `b`, `h`, `i` mean, and whether
  `MusicService.x` and static `MusicService.H0` are one flag.
- The meanings of `mk1.b()`, `aw0.d/e`, `O0()`, `M0()`, `y0()`, `a0()`, `s0()`, `r0()`,
  `g2.w()/s()/L()`, `h01`, `r71`, `s71`, `qn1`, `b81`, `p71`, `sa0`.
- The 47 string values in `…HQCspMiP` and who writes them.
- Who writes the five `Method` fields (zero direct writers found).
- What `VMRunner` interprets, and what `"fhQ4m5FYWVDTYLVQ"` maps to.
- `NativeLibx`'s native methods, with no `.so` shipped.
- How `com.pairip.application.Application` hands over to `MyApplication`.
- The `const` values of `v1` and `v4` in `onStartCommand`.
- Why `NumberFormatException` handlers wrap blocks with no number parsing.
- Notification id `0x78` and the purpose of `MyApplication;->s`.
- `MediaBrowserServiceImpl.onCreate` and its 1,491-code-unit `c(...)` — not read yet.
- Two intent filters with **no action** (`LockScreenActivity`; `MusicActivity`'s
  `vnd.android.cursor.dir/*`) — malware-style reachability, purpose unexplained.
- What is inside the 24 encrypted PairIP `assets/*` payloads (`"IAP"` header).

## 7. Limits of what this analysis can claim

- **Static reading never shows that code ran.** Everything above is what the code *says*; no
  execution was observed.
- Component and permission facts come from the **manifest only**. Receivers registered at run time
  are invisible, and code can check callers regardless of the manifest.
- `android_class_users_find` sees **direct bytecode references only**. Reflection, JNI and
  string-built access are invisible, so "zero writers" means "no writer found", not "no writer".
- Exported/reachable verdicts are computed for `targetSdk 37`.
- The JADX Java source is a **third-party decompilation** — an interpretation, not evidence.
- Absence of a string or a file proves only that the search did not find it. Each search above is
  reported with its encoding and its positive control.

---

## 8. Desktop rebuild — real values read from the DEX (round 2)

The first attempt to follow the native path was made and failed honestly:

- `core_target_open` opened `base.apk` as an Android package.
- `native_strings_find` refused it: **`not_supported — "An Android app is not native machine code."`**
- The APK contains no `lib/` directory and no `.so` file. Its `assets/*` payloads start with the
  PairIP header `00 49 41 50 02 00 00 00 08 07` (the `"IAP"` marker); a byte scan of
  `assets/fhQ4m5FYWVDTYLVQ` found no ELF header. They are encrypted, not readable machine code.
- Deep mode therefore read the real program where it lives: **Dalvik bytecode in `classes.dex`**.
  The Android decompiler is the correct engine here. `native_function_pseudocode` was also
  attempted and returned the same `not_supported — "An Android app is not native machine code."`;
  the equivalent is `android_method_show` (authoritative bytecode) plus JADX 1.5.6 (readability
  only, not evidence).

**How addresses are written below.** `classes.dex` · `<method signature>` · `<code-unit offset>`.
A DEX has no virtual addresses, so a code-unit offset is the function-address equivalent. The
JADX line numbers are given only to make a rule easy to find again; they are not evidence.

The rebuild is a Rust/egui desktop program. It reuses no target code, dex output, images, fonts,
audio or copy. Everything below is implemented in `app/src`.

---

### 8.1 Queues

| Rule | Real value / rule | Target address | Rebuild |
|---|---|---|---|
| Maximum named queues | **20** | `MusicService.g(int,String,ArrayList)` 0x0050 (`const/16 0x14`); `MusicService.h(int,ArrayList)` 0x002f | `model::MAX_QUEUES = 20` |
| Overflow eviction | **remove index 0** (the first-created / bottom of the stack) | `MusicService.g` 0x005b `Stack.remove(0)` | `app.rs::create_or_replace_queue` keeps the oldest id when over 20 |
| Same-name policy | key `k_i_hwqwsnaex`, **default 0 = Replace**, 1 = Merge | `jl1.i1(Preference)` 0x0051 passes default 0; `MusicService.i(...)` 0x008d reads it; 0x0093–0x00a4 clears the old songs | `Settings.queue_conflict = Replace` |
| Queue remembers its place | per-queue `index` + position; `R_RFLP` default true resumes the next queue | `MusicService.V()` / `U(...)`; `R_RFLP` default true | `model::Queue.index/position`; jump path resumes `q.position` |
| Queue-end default | `RQM` default **2 = jump to the next queue**; `R_SFFQ` false = stop after the last; `R_RSFLQ` true = reshuffle if shuffle is on | `g2.L(int)` and `hf1` defaults; `MusicService.V()` 0x0030–0x00d8 | `Settings.jump_queues = true` |
| Name comparison | target uses exact `String.equals`, so case variants are different queues | `MusicService.i(ArrayList,int,String,boolean)` 0x0035 | rebuild now uses exact equality too (§10.8) |

The target's queue list is a `Stack`: a newly created or reused queue is pushed on top, and
overflow removes index 0. The rebuild stores a `Vec` with monotonically increasing ids and evicts
the smallest id, which produces the same "first-created leaves first" rule.

### 8.2 Playback, repeat, shuffle and statistics

| Rule | Real value / rule | Target address | Rebuild |
|---|---|---|---|
| Previous button | `PP_PBTNBH` default **"1"**; if position > **10 000 ms**, seek to 0, else previous song; value 0 = always previous | `MusicService.X()` 0x0039 `const-wide/16 0x2710`; `MusicService.onStartCommand` action table | `model::PREVIOUS_REPLAY_SECONDS = 10.0`; `app.rs::prev_track` |
| Play-count options | **10 / 30 / 50 / 90 %** (array `value_min_playback_req`) | resources `0x7f030020`; `MusicService.p()` reads `MINPBR` | `Settings.min_play_percent` |
| Play-count default | **30 %** | `MusicService.p()` 0x0021 `"30"`, 0x003f reset `0x1e` | `model::DEFAULT_PLAY_COUNT_PERCENT = 30` |
| Play-count formula | `played_ms >= floor(duration_ms * percent / 100.0) - crossfade_ms` | `MusicService.p()` 0x0049–0x0064 (multiply, divide by 100.0, subtract `k_i_cfd` default 0, compare with accumulated `C`) | `app.rs::mark_played_if_needed` |
| Speed default | **1.00×**, stored as `k_f_plyspd4 = 10000` | `h71.q()` 0x0006 | `Settings.playback_speed = 1.0` |
| Speed range / step | **0.50 … 3.00×**, step **0.01** | `h71.onClick` 0x0010 (0.50), 0x0007 (3.00), 0x0009 (double 0.01) | `app.rs` clamps 0.5–3.0; `audio.rs::set_speed`; dialog slider 0.01 |
| Speed presets | **0.50, 0.75, 0.90, 1.00, 1.25, 1.50, 2.00** | `h71.t()` 0x0031–0x0048: `0x1388, 0x1d4c, 0x2328, 0x2710, 0x30d4, 0x3a98, 0x4e20` | speed dialog chips |
| Pitch range / step | **0.50 … 2.00×**, step **0.01** | `h71.onClick` 0x004a (2.00) and shared 0.50 at 0x0010 | not independent in Rodio; the app says so instead of faking it |
| Gapless default | **true** (`k_b_glp`) | `default_preference.xml`; `dd` field init | `Settings.gapless = true` |
| Crossfade default | **0 ms** (`k_i_cfd`) | `MusicService.p()` 0x0057; `dd.<init>` | `Settings.crossfade_seconds = 0.0`; used in the play-count formula |
| Play/pause fade | `k_b_fdid` / `k_b_fdod`: default **0 ms**, range **0…4000 ms**, step **250 ms** | `default_preference.xml`; `MusicService.W` 0x003f/0x00d2; `ac.M` / `ac.e0`; `vb.run` / `ub.run` | `Settings.fade_in_ms/fade_out_ms`; `model::fade_in_level/fade_out_level`; `app.rs::update_play_fade` (§10) |
| Repeat encoding | **off = (RSM 2, RQM 1)**, **all = (RSM 2, RQM 3)**, **one = (RSM 3, RQM 1)** | `g2.L(int)` 0x0000–0x001d; `aw0.b()` 0x0004–0x0023 | `RepeatMode::{Off, All, One}` |
| Repeat button cycle | **off → all → one → off** (the `{0,2,1}` cycle) | `MusicService.onStartCommand` `ACT_RPTS_TGL` | `app.rs::cycle_repeat` |
| Shuffle scope default | **all songs in the queue** (`k_i_sflm_ob` = 0) | `option_shuffle_behaviour[0]`; `MusicService.y0` | `Settings.shuffle_scope_all = true` |

`RSM` is the "when a song ends" setting: 1 = just stop, 2 = play next song (default), 3 = repeat
the same song, 4 = load next song and pause. `RQM` is the "when the current queue ends" setting:
1 = just stop, 2 = jump to the next queue (default), 3 = repeat the same queue. The simple repeat
button writes the three pairs above, which is why only three of the twelve combinations are
reachable from that button.

### 8.3 Sleep timer

| Rule | Real value / rule | Target address | Rebuild |
|---|---|---|---|
| Time formula | `h * 0x36ee80 + m * 0xea60 + 0x3e8` ms (**+1 second**) | `zy.a(int,int)` 0x002d, 0x0032, 0x0037 | `app.rs::set_sleep_time` |
| Time history | default **1:30** and **0:15**, newest last, at most **5** | `wm1.<clinit>` 0x000f/0x0023/0x0038/0x003e; `wm1.c` | `Settings.sleep_history_hm` |
| Songs history | default **N = 10** and **N = 5**, newest last, at most **5** | `wm1.<clinit>`; `wm1.d` | `Settings.sleep_history_n` |
| Songs maximum | **99** | `zy.c(...)` 0x02e1 `const/16 0x63` | `set_sleep_songs` clamps 1–99 |
| Wait option | `k_i_slptrh_l`, **default false** | `zy.a` 0x0024 | `Settings.sleep_wait_till_end = false` |
| Fade option | `k_i_slptrh_gfls`, **default false** | `zy.a` 0x001d | `Settings.sleep_fade = false` |
| Fade rule | fade over the **remaining current song**; skip when ≤ **1000 ms** remain | `dd.l0(String)` 0x001d–0x0035 and `Lac.M(remaining_ms, 0, …)` at 0x0057 | `app.rs::begin_sleep_fade` / `update_sleep_timer` |
| Alarm action | `a_sltmcl`: pause now, or finish the song when wait is on; `g = wait && fade` | `MusicService.z0(long,boolean,boolean)`; `onStartCommand` `a_sltmcl` at 0x6a… | `update_sleep_timer`, `next_track` |
| Songs countdown | decrement at each song end; at 0 pause and toast "Stopped by sleep timer" | `MusicService.s(ac)`; `MusicService.A0(int,boolean)` | `update_playback` empty branch + `next_track` |

The desktop build implements the custom hours/minutes picker, the five-entry history, the
N-songs mode, the wait checkbox and the fade checkbox. The fade is over the remaining song, not
over a fixed ten seconds.

### 8.4 Equalizer

| Rule | Real value / rule | Target address | Rebuild |
|---|---|---|---|
| Ten-band frequencies | **31.5, 63, 125, 250, 500, 1 000, 2 000, 4 000, 8 000, 16 000 Hz** | `c01.a(int)` 0x0012 and array payload 0x0054 | `model::EQ_FREQUENCIES` |
| Band Q | **1.0** for all ten bands | `c01.d(int)` 0x0040–0x0042 (`const/high16 0x3f800000`) | `model::EQ_BAND_Q`; `audio.rs` peaking filter |
| Band gain range | fallback **−15 … +15 dB** (−1500…+1500 millibels); replaced by the device range when Android reports one | `s40.G0()` 0x0077–0x0082; `c50.l()` | sliders and faders clamp ±15 dB |
| Presets | **22** exact tables: Flat, Bass, Mid, Treble, Bass and Treble, Full Bass, Full Mid, Full Treble, Reduced Bass, Reduced Mid, Reduced Treble, No Bass, No Mid, No Treble, Classic, Dance, Live, Speaker, Pop, Rock, Soft, Techno | `c50.<clinit>` 10-band list stored at **0x03b6**; names decoded from `d50.a..v` | `model::EQ_PRESETS` + `EqProfile::apply_preset` |
| Preamp control | three modes: **Off** (`k_i_prmd_` 0, default), **Headroom** (−1, `k_i_hdrmg_` 1…30 dB, default 6), **Boost** (+1, `k_i_vbg_` 1…9 dB, default 2) | `w40.L0()` 0x0012/0x0021/0x0030 and clamps 0x36/0x4a/0x73/0x91; `w40.G0(int)` | `PreampMode`, `headroom_db`, `boost_db`; chips + slider |
| Bass boost | **0…100 %** (stored 0…1000, shown `i/10 %`) | `w40.I` 0x0015–0x0021; `BassBoost.setStrength` at 0x002b | 0…1 slider; a low shelf at 90 Hz (approximation, see below) |
| Virtualizer | **0…100 %** (stored 0…1000) | `w40.I` 0x003f–0x004b; `Virtualizer.setStrength` at 0x0055 | 0…1 slider; stereo cross-feed (approximation) |
| Balance | **0…200**, default **100** = centre; 0 = hard left, 200 = hard right | `c50.d(int)` default 100; `w40.K0(int)` text mapping | `EqProfile.balance`, applied as per-channel gain |
| Headroom suggestion | if adjusted peak > 6 dB: `min(peak − 6, 30)` dB. Bass adds strength × 15/1000 dB below 200 Hz; mid bands are reduced 2 dB, treble 4 dB | `EqualizerActivity2.o0()` 0x005e–0x012a | `EqProfile::suggested_headroom_db` + "Use" button |
| Device profiles | **five built-in rows**: built-in speakers, speakers/headphones, headset/earphones, Bluetooth, USB; saved Bluetooth/USB devices follow | `EqualizerActivity2.l0()` 0x0000–0x0080 | `DeviceProfile::ALL` (5) |

The target delegates its final sound to Android's `Equalizer`, `BassBoost` and `Virtualizer`
effects. Their exact filter shapes and dB curves are device-defined, so the desktop build uses
the target's **control values and ranges** exactly, but its own documented DSP: RBJ peaking filters
at the exact ten frequencies with Q 1.0, a 90 Hz low shelf for bass, a stereo cross-feed for the
virtualizer, and per-channel gains for balance. The headroom suggestion is the target's formula.

### 8.5 Library and appearance

| Rule | Real value / rule | Target address | Rebuild |
|---|---|---|---|
| Folder structure default | **hierarchical** (`key_folder_structure = "1"`); array order is [linear, hierarchical] | `in.krosbits.musicolet.b.a(Context)` 0x0016 | `Settings.folder_hierarchical = true` |
| Theme modes | **Light, Dark, Black, Day/Night, Follow system** (array `option_base_theme`) | resources `0x7f030003` | `ThemeMode` (5 variants) |
| Theme default | **Black** (`THMR_BT = "2"`) | `in.krosbits.musicolet.b.a(Context)` 0x001e | `ThemeMode::Black` |
| Day/Night window | **06:00 … 17:59** (`k_i_dstt` default 360 min, `k_i_dedt` default 1079 min) | `MyApplication.s()` 0x0044 and 0x0050 | `theme::local_minute_of_day` + `Settings.day_start_minutes/day_end_minutes` |
| Scan on start | **true** (`k_b_atsc`) | `default_preference.xml`; `a.onPostExecute` reads it with `true` | `Settings.scan_on_start = true` |
| Hidden / .nomedia / video | **false / false / false** (`k_b_schdfl`, `k_b_scnmfl`, `k_b_scvfl`) | `default_preference.xml`; `bm0` constructor | `Settings.scan_hidden = false`; other two are not exposed |
| Small-file exclusion | `I_E_D_SEC_SCN` default **0 s** on this platform (5 s only when `MyApplication.F.p()` is true) | `em0` constructor; `p50` | `Settings.min_duration_seconds = 0` |
| Starter library | 10 original instrumental tracks generated for this rebuild | not a target value | `app/Starter Music` |

### 8.6 What is still approximate or unsupported

These are stated so the rebuild does not claim more than it has:

- **Independent pitch.** The target stores pitch at `k_f_plyptch4` (0.50…2.00×, step 0.01) and
  applies it separately from speed. Rodio's `set_speed` changes both; the desktop build exposes
  the real speed range and says pitch follows speed.
- **Android effect curves.** BassBoost, Virtualizer and the platform Equalizer are device DSP.
  Our EQ frequencies, Q, gain range, presets, preamp modes, balance mapping and headroom formula
  are real; the low-shelf/cross-feed shapes are documented approximations.
- **ReplayGain calculation/writing, Opus decoding, notification/lockscreen/widgets/Android Auto/
  Cast/headset-button integration.** Android OS surfaces; out of scope for the desktop build.
- **Play/pause fades** are now implemented from the real `k_b_fdid` / `k_b_fdod` lengths and the
  `vb.run` / `ub.run` curves (§10); the `n0()` minimum/odd guard is not copied because its
  session flags are not understood.
- **Queue-name matching** is now exact (`String.equals`), matching the target (§10.8).
- **Playlist-name matching** is still case-insensitive; no playlist method was read that settles it.
- **Target band-count variants.** The target can show 3, 5, 10, 15 or 31 bands depending on the
  device (`c01.a`). The desktop build fixes the 10-band table, which is the table used for the
  presets above.
- **Encrypted native payloads.** `assets/*` are PairIP `"IAP"` blobs. No ELF is readable, so no
  native function pseudocode can be produced from them without running the target's decryptor.

### 8.7 Evidence for the round-2 values

| Evidence id | What it holds |
|---|---|
| `ev_7c4e4227dd790e95e5e24e928483a0c57f366041c56060f2e041b3df00fa85b2` | `MusicService.g(...)`, queue limit 20 and remove(0) |
| `ev_327b2cd9302cb32c3a27ec97ccfc4a19d47aa6ac69523503c6e187bc2c6dc381` | `MusicService.h(...)`, queue limit 20 |
| `ev_1e925fb7e52b10a577f8a636f7cd5c88764e207c1a3a4c475930d69700464bc4` | `MusicService.i(...)`, `k_i_hwqwsnaex` and replace branch |
| `ev_a036a73b716e6a0e658401521d8214577c666a568c109fa3a0b4c077ad2ab05e` | `jl1.i1(...)`, Replace/Merge labels and default 0 |
| `ev_cf1988a51fe68cc8e1199da18e1b26145bb62b256a646573945f5f26ba4fc7ed` | `MusicService.X()`, 10 000 ms previous threshold |
| `ev_2f515a402cd8c0568b0e944239b653c6618cd1a42562457897601edf608efcfe` | `MusicService.p()`, play-count default 30 and formula |
| `ev_52908a5da262dacacf9b7881da9a1e9b5365901f4fe50b288d3f3671e1a81076` | `h71.onClick`, speed/pitch ranges and 0.01 step |
| `ev_a222412427d8dfc640a01651a0ed32768da625cc9c723c297498f21283b851f0` | `h71.q()`, default speed 10000 |
| `ev_caa56e80b2f8def07f3373f3eb0a634a4e1398d38d0d9739549fcfaa8eb71947` | `h71.t()`, seven speed presets |
| `ev_4eb885644f1a24b471295ecb6ae0cc80733439a17925dfa5b4d51c381f0e2fce` | `wm1.<clinit>`, sleep defaults |
| `ev_6ee458b44a6aa009b9a7186aeaea94d013112fe81150a2be44a1134dc56910c1` | `zy.a(...)`, hours/minutes + 1 s formula |
| `ev_225299773ac6b7f4e1c219cbd40d3c262d27350f9a0c52d00b5beb5d3ffddd05` | `zy.c(...)`, 99-song clamp |
| `ev_2041800389d0b7951418754fcbd1f9b91cb7c909db7ba91636ebd421bda4b3d1` | `dd.l0(...)`, fade over remaining song |
| `ev_4ef7b85234557939a55928ba082ad56139ae53532c6ea73eb5cbf96b2ba82e3a` | `c01.a(int)`, frequency tables |
| `ev_7565354b7781f16b3a28ada7a3d40331289cb8f2515ae008154810fc8d1977e4` | `c01.d(int)`, Q per band count |
| `ev_99d6681c070633d079173710694a5c18d09afc6fd37cb68ca850cfa757918007` | `c50.<clinit>`, 22 preset tables |
| `ev_15c1b187218183a7d0e9c17f9eabbe5e285832721863eae9a8fbaf6f6c35aa7f` | `w40.L0()`, preamp modes/defaults |
| `ev_158c78bb22737f2b1e32701c1d7041b2a499b54ac20e0b9bb42be8ef0a8ffd78` | `w40.K0(int)`, balance mapping |
| `ev_bdfb5f9071b15f8293f7865fb9bb186b760a869d563edfcedb59765ebca48806` | `g2.L(int)`, RSM/RQM pairs |
| `ev_99a4b4d5080a1481c1f53de00c1d3feb331ebc4c914000e292939ebef531c84e` | `aw0.b()`, repeat mode decoding |
| `ev_5b6b6d770cb40829d9c3c5c69f92db16dedeb72863c584e75cf409bd85bc7e19` | `in.krosbits.musicolet.b.a(Context)`, folder/theme defaults |
| `ev_53742178b8c7cbce0d26b173aaa14726229b7dce5dbd215544c695871e1ebc45` | `MyApplication.s()`, Day/Night window |
| `ev_bd5cf7d89a55eab98c226915fa042d3138ebe2d513f87b1ecb8d56d86fd02dc5` | `EqualizerActivity2.l0()`, five device profiles |
| `ev_179d252fb2d9b38f397a5ef82af751ce951c4efc60329d6c906721ab53448bc7` | `EqualizerActivity2.o0()`, headroom suggestion |

---

## 9. Verification of this round

- `cargo build` succeeds.
- `cargo test` passes 9 tests, including the exact target preset tables, 10-band frequencies,
  Q 1.0 and the target defaults (Black theme, 30 % play count, Replace queue conflict, jump to
  the next queue, gapless on, sleep history 1:30/0:15 and 10/5).
- Screenshots in `app/checks/round-2/` show the five profile chips, the 22 preset chips, the
  three preamp modes, the balance control, the precise 31.5 Hz label, the Day/Night theme
  option, and the new sleep dialog with time/song modes and the two checkboxes.
- `rebuild_check` is run after this file and the build are in place.

Limits: static reading never shows that code ran. Android's DSP curves are device-defined, and
the encrypted PairIP assets cannot be decompiled without running the target's decryptor.

---

## 10. Play/pause fades and exact queue names — round 3, read from the DEX

### 10.1 What was attempted first, and why native tools cannot answer this

This round began with the native path, exactly as requested:

1. `core_target_open` on `base.apk` → `not_supported — "An Android app is not native machine code."`
2. `native_strings_find` (e.g. `"Sleep timer"`) → the same refusal.
3. `native_function_pseudocode` (`onStartCommand`) → the same refusal.
4. `core_target_triage` → recommends the `android` toolset, not the native one.

The APK contains **no `lib/` directory and no `.so` file** (all 1,452 ZIP entries checked). Its
`assets/*` payloads are encrypted PairIP blobs, so there is no ELF to give Ghidra. The program is
Dalvik bytecode. The equivalent decompiler path is therefore
`android_method_show` (authoritative bytecode) plus, for orientation only,
`core_archive_show` / `android_resources_read`. No native function address is invented here.
A DEX still has executable functions, so the citations below use
`<DEX file> · <method signature> · <code-unit offset>` — the DEX equivalent of a function address.

### 10.2 The two fade settings — exact range, step and default

`android_resources_read({xml_files:["res/xml/default_preference.xml"]})` decodes the preference
screen. The audio screen (`android:key="auido"`) contains:

```xml
<com.pavelsikun.seekbarpreference.SeekBarPreferenceCompat
    android:title="@0x7f1201db" android:key="k_b_fdid" android:defaultValue="0"
    app:msbp_dialogEnabled="false" app:msbp_interval="250"
    app:msbp_maxValue="4000" app:msbp_measurementUnit="ms" app:msbp_minValue="0"/>

<com.pavelsikun.seekbarpreference.SeekBarPreferenceCompat
    android:title="@0x7f1201dc" android:key="k_b_fdod" android:defaultValue="0"
    app:msbp_dialogEnabled="false" app:msbp_interval="250"
    app:msbp_maxValue="4000" app:msbp_measurementUnit="ms" app:msbp_minValue="0"/>
```

| Key | String resource | English text | Default | Range | Step | Unit |
|---|---|---|---|---|---|---|
| `k_b_fdid` | `0x7f1201db` | **Fade-in volume when play** | `0` | `0…4000` | `250` | ms |
| `k_b_fdod` | `0x7f1201dc` | **Fade-out volume when pause** | `0` | `0…4000` | `250` | ms |

Both settings are `SeekBarPreferenceCompat`, not a boolean: the user picks a millisecond
duration, and `0` means "no fade". The titles settle the earlier guess about the two key names:
`fdid` is used when playback starts, `fdod` when it pauses.

### 10.3 `MusicService.W` — which key is read on which edge

`classes.dex` · `Lin/krosbits/musicolet/MusicService;->W([Ljava/lang/Integer;)V` (`public final
varargs void`) · **392 code units**. This is the toggle reached by `ACTION_PLAY_PAUSE`.

Pause edge (the app is playing, `MusicService;->j` is `0`):

```
00d2: const-string v3, "k_b_fdod"
00d4: invoke-interface {prefs, v3, 0}, SharedPreferences;->getInt(String,I)I
00da: invoke-virtual {F0, duration, 2, null}, Lac;->M(IILjava/lang/Runnable;)V
```

Resume edge (`MusicService;->j` is `1`):

```
003f: const-string v0, "k_b_fdid"
0041: invoke-interface {prefs, v0, 0}, SharedPreferences;->getInt(String,I)I
0067: invoke-virtual {F0, duration, 1}, Lac;->e0(II)V
```

`0` is not special-cased in `W`; the zero-duration behaviour lives in `ac`.

One special call form skips the fade entirely: when the varargs array is non-empty and its
first element is `1`, code units `0x00b7`–`0x00cb` set the player volume to `0` and call
`L()` (pause) directly. Only the ordinary `W([])` call reaches the `k_b_fdod` path at `0x00cc`.
The rebuild has no caller that needs this stop-without-fade form.

### 10.4 `ac.M` / `ac.e0` — the two entry points

`classes.dex` · `Lac;->M(IILjava/lang/Runnable;)V` · code units `0x0000`–`0x0026`, and
`Lac;->e0(II)V` · code units `0x0000`–`0x002b`.

| Method | Meaning | Duration `<= 0` | Duration `> 0` |
|---|---|---|---|
| `M(ms, type, runnable)` | **pause with fade-out** | if current volume `<= 0` set `0`; call `L()` = pause | clear any fade-in (`k=false`), set `j=true`, start thread `vb`, which ends with `b0(0)`, `L()` and the optional runnable |
| `e0(ms, type)` | **play with fade-in** | if current volume `< 100` set `100`; call `d0()` = play | clear any fade-out (`j=false`), set `k=true`, start thread `ub`, and call `d0()` = play immediately |

The player primitives are `Lq60;->E()I` (reads ExoPlayer volume as `round(volume*100)`, 0…100)
and `Lq60;->b0(I)V` (writes `volume = value / 100.0f`). `Lq60;->L()` posts
`Lum1;->q(false)` and `Lq60;->d0()` posts `Lum1;->q(true)`; in ExoPlayer terms these are
**pause** and **play**. Both fade threads use the same integer volume scale.

### 10.5 `vb.run` — the fade-out formula

`classes.dex` · `Lvb;->run()V` · code units `0x0000`–`0x0075`.

```
steps = duration_ms / 100                     // integer division
start = E()                                    // volume at fade start, 0…100
i = 0
while i < steps and ac.j and not cancelled:
    current = E()
    ramp  = start * i / steps                  // float
    level = start - (int) pow(ramp / 10.0, 2)
    if level < current: b0(level)              // never raises the volume
    sleep(100 ms)
    if type == 2 and i == 0: i = steps / 3     // skip the first third
    i += 1
if ac.j and not cancelled:
    b0(0)
    L()                                        // pause
    runnable?.run()
```

For `type == 2` (the pause path in `W`), the first 100 ms holds the starting level, then the
table jumps to `i = steps/3 + 1`. With `start = 100` and `duration = 1000`:
`level(100 ms) = 100 − (100·4/10)² = 84`, `level(500 ms) = 36`, `level(1000 ms) = 0`.
The curve is fast at first and slow at the end — a natural fade-out.

### 10.6 `ub.run` — the fade-in formula

`classes.dex` · `Lub;->run()V` · code units `0x0000`–`0x0063`.

```
steps = duration_ms / 100
i = 0
while i < steps and ac.k and not cancelled:
    if i == 0 and duration_ms is odd: sleep(300 ms) else sleep(100 ms)
    current = E()
    level = (int) (i * 100.0 / steps)
    if type == 1 or type == 2:
        level = (int) pow(level / 10.0, 2)      // 0, 1, 4, 9 … 100
    if level > current: b0(level)               // never lowers the volume
    i += 1
if ac.k and not cancelled:
    b0(100)
```

The squared curve is the inverse shape of the fade-out: slow at first, fast at the end.

The odd-duration `300 ms` first sleep is real bytecode (`const-wide/16 0x12c` at `0x001a`),
but every value the settings screen can produce is a multiple of 250 ms and therefore even, so
the quirk cannot be reached from the UI. It can be reached by a programmatic call; the rebuild
keeps it in `model::fade_in_level`.

### 10.7 The `n0()` guard on resume

Before the fade-in call, `MusicService.W` tests `n0()`:

```
n0() = y0 != E0 && K0 != 0 && L0 != null && L0.b
```

When it is true, `W` replaces any fade shorter than **300 ms** with **101 ms** (`0x65`), then
forces the result **even** (`+1` when odd), so a stored value below 300 becomes **102 ms**;
300 ms and longer are only parity-corrected. It stores `E0` in `y0`, logs `"MS>ES"`, and sets the
player volume to `0` before `e0`. Forcing an even value also bypasses `ub.run`'s odd-duration
300 ms first sleep. The branches use `L0:Ljava/lang/Object;` (declared `Le50;`) and `K0`.
What those live playback flags mean is **not settled**; the rebuild does not copy this guard.
It only matters when a session/effect object was replaced underneath the player, and the normal
path is the 10.5/10.6 formula.

### 10.8 Exact queue names, corrected

`classes.dex` · `Lin/krosbits/musicolet/MusicService;->i(Ljava/util/ArrayList;ILjava/lang/String;Z)V` · code
unit `0x0035` compares each stored queue name (`Lrn1;->h:Ljava/lang/String;`) with the typed name via
`Ljava/lang/String;->equals(Ljava/lang/Object;)Z`. That comparison is
**case-sensitive**: `"Road"` and `"road"` are two different queues. Round 2 kept
case-insensitive uniqueness as a deliberate desktop deviation; this round removed the deviation.
The rebuild now uses exact equality in:

- `Persisted::queue_by_name` (model.rs)
- `create_or_replace_queue` (same-name reuse and the "name 2" suffix loop)
- `load_playlist_into_queue` lookup
- `rename_queue` duplicate check

Playlist names were left case-insensitive in this round because no playlist method was read that
settles the rule; they are not claimed to match the target.

### 10.9 What changed in the build

| Target rule | Address | Rebuild |
|---|---|---|
| `k_b_fdid` fade-in, default 0, 0…4000 ms, 250 ms step | `default_preference.xml`; `MusicService.W` 0x003f–0x0067 | `Settings::fade_in_ms`; two setting rows in `ui/pages.rs` with `Slider` step 250 |
| `k_b_fdod` fade-out, default 0, 0…4000 ms, 250 ms step | `default_preference.xml`; `MusicService.W` 0x00d2–0x00da | `Settings::fade_out_ms`; setting row and `app.rs::toggle_play` |
| Fade-in curve | `ub.run` 0x0000–0x0063 | `model::fade_in_level` |
| Fade-out curve (type 2) | `vb.run` 0x0000–0x0075 | `model::fade_out_level` |
| Integer volume scale 0…100 | `q60.E` 0x0000–0x000b; `q60.b0` 0x0000–0x0009 | `AudioEngine::volume`; levels multiplied by `effective_volume()` |
| Pause completes only after the ramp | `ac.M` 0x0000–0x0026 | `FadeKind::Out`; `playing = false` at once, sink paused at the end of `update_play_fade` |
| Play cancels a running fade-out | `ac.e0` 0x0000–0x002b | `toggle_play` clears `self.fade` and resumes |
| No fade on auto-advance | `MusicService.V` 0x0000–0x0102 (259 instructions, no `e0`/`M`) | `start_index` captures `was_playing`; `update_playback` keeps it true across an automatic track change |
| Case-sensitive queue names | `MusicService.i` 0x0035 `String.equals` | exact `==` in queue lookup/create/rename |

The fade code is ticked once per frame in `LiltApp::update` (`update_play_fade`) and uses the
same 100 ms step table as the target threads rather than an audio-DSP ramp. If the program is
suspended for longer than the fade, the elapsed clock finishes the fade on the next frame.

### 10.10 Verification

- `cargo build` succeeds (dev profile).
- `cargo test` passes **11 tests**, including `fade_curves_match_the_target_threads`:
  fade-in `0 → 25% → 100%`, fade-out `100% → 84% at 100 ms → 36% at 500 ms → 0`,
  the odd-duration 300 ms first step, and `queue_names_are_case_sensitive`.
- `checks/round-2/settings-fades-playback.png` shows the new **Fade in on play** /
  **Fade out on pause** sliders at `0 ms` in Settings → Playback (with
  `LILT_PAGE=settings LILT_SETTINGS_SCROLL=1050`).

### 10.11 Evidence for round 3

| Evidence id | What it holds |
|---|---|
| `ev_376b21fa7159e42bbba98e197952ccfe8b16c860e0b38c63477427afed7d45f1` | `MusicService.W`, both keys, both calls |
| `ev_17dc65dcc1ca7eda7acca27fa4b12b64ad51ab4332054343ed7489577662dc6a` | `ac.M`, fade-out entry |
| `ev_1f065cbb7f611f6a0e1fe9187b9d386b32c19a80ce1b5e01badd4609f8a56552` | `ac.e0`, fade-in entry |
| `ev_e7dbfc0a1ca27a110e844fcd4e560d2d18bf93e394d4647ba4892c9156b6e075` | `vb.run`, fade-out formula |
| `ev_adac65336ebdecc85f6fa7dcefbcd32f34a91c3af7beee4df4a7234e8cc5eb05` | `ub.run`, fade-in formula |
| `ev_f21daccc7e3903c2e6d24d88d841639e06f8791ea3d6365ed4198efe23729c62` | `q60.E`, volume read |
| `ev_0504903891e64881806b426140d145968d23a3d958352b46f6cc0de19a8d0d89` | `q60.b0`, volume write |
| `ev_5b036d15576e81ce72df4ec811a29f58791e53384d2c28c113314fd919c7b8b4` | `q60.L`, pause |
| `ev_1a0a1c1c89dd7dc3f36b17c6a78a4f58e43a9378d5915fcc9fbde55188444adf` | `q60.d0`, play |
| `ev_3a29f5728e328c4f80d48711e36857b0123ede111d74dd45fb27617d3251831a` | `default_preference.xml`, both fade rows |
| `ev_f9ce09f780c9dc8a1c5417d766494f6b7bb51c159ffd362b410ad2e5ce19ec2b` | strings `0x7f1201db` / `0x7f1201dc` |
| `ev_fcdd0a3d08c588c566d356fde0ee5b7648b4f5f73f27bb02614e0325fbb7b354` | `MusicService.i`, exact `String.equals` at 0x0035 |
| `ev_42ca61f1463998cf7de88479ce7bdf48e373289ce1cd6944f6250f01845166f2` | `MusicService.V`, auto-advance has no fade call |
| `ev_260194cd14c281ef7e6dc486dfa5eb68ab8b19783d99c12bdabfafa2c20f969d` | complete archive inventory: 1,452 entries, no `lib/` and no `.so` |
| `ev_d7cae2cd066f2898352efa1af054cf2b784b08829b8a9525587ae25bbb9c58c4` | triage: APK recommends the Android toolset, not native |

Limits of this round: static reading does not show the fades running; the `n0()` guard, the
`e50`/`K0` session flags, and playlist-name case remain open. `native_*` tools still cannot read
this APK because it ships no native program.
