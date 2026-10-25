# SQLCipher's native layer is loaded reflectively on some devices.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.sqlcipher.** { *; }

# ML Kit text recognition.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }

# Room generated implementations are referenced by name at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
