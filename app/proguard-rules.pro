# Activities are kept by the rules aapt generates from the manifest.
-allowaccessmodification
-repackageclasses ''

# Keep newer-than-minSdk API calls out of the hot classes (no inlining of Compat), so those
# classes still verify (and get compiled) cleanly on old runtimes.
-keep,allowobfuscation class io.github.plpp555.zerolauncher.Compat { *; }
