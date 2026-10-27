# Only BouncyCastle's lightweight API is used (no JCA provider lookups by name), so it can be shrunk freely.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Android 2.x ships its own, older BouncyCastle under org.bouncycastle in the boot classpath, and the
# boot class loader wins over the app's copy. Renaming our copy into our own package avoids mixing the
# two versions. App classes keep their names so stack traces stay readable.
-keepnames class io.lslegacy.** { *; }
-repackageclasses 'io.lslegacy.bc'
-keepattributes SourceFile,LineNumberTable
