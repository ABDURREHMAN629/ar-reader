# Add project specific ProGuard rules here.

# junrar (CBR comics) references optional logging/VFS classes that aren't bundled.
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.vfs2.**
-dontwarn javax.annotation.**
-keep class com.github.junrar.** { *; }

# ML Kit (Change Background) finds its components by reflection: keep their no-arg constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { *; }
