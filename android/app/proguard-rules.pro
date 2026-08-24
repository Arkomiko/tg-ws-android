# Правила для релизной сборки.
#
# Chaquopy добирается до Java-классов из Python через отражение, поэтому
# минификатор не должен переименовывать или выбрасывать его классы: статически
# такие обращения не видны, и R8 счёл бы их неиспользуемыми.
-keep class com.chaquo.python.** { *; }
-keep class org.jetbrains.annotations.** { *; }
-dontwarn com.chaquo.python.**

# Наши компоненты объявлены в манифесте и создаются системой по имени класса.
# AGP обычно сохраняет их сам, но список зависит от версии — фиксируем явно.
-keep class com.tgwsproxy.android.MainActivity { *; }
-keep class com.tgwsproxy.android.SettingsActivity { *; }
-keep class com.tgwsproxy.android.LogActivity { *; }
-keep class com.tgwsproxy.android.TilePreferencesActivity { *; }
-keep class com.tgwsproxy.android.ProxyService { *; }
-keep class com.tgwsproxy.android.ProxyTileService { *; }
-keep class com.tgwsproxy.android.WatchdogReceiver { *; }
-keep class com.tgwsproxy.android.BootReceiver { *; }

# Имена файлов и строк в стектрейсах — чтобы отчёт об ошибке был читаемым.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
