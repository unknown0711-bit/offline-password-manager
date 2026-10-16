# Phase 1 has no reflection-driven app code beyond Hilt/Room/SQLCipher.
# Keep this file under version control so release builds have an explicit policy.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class com.google.crypto.tink.** { *; }
