# Only BouncyCastle's lightweight API is used (no JCA provider lookups by name), so it can be shrunk freely.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontobfuscate
