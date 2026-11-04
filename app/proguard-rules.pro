# kotlinx.serialization ships R8 rules in its jar. These keep the generated serializers
# of the app's @Serializable classes (Mistral requests and answers) even when a type is
# looked up at run time rather than through the compiler intrinsic.
-if @kotlinx.serialization.Serializable class tech.dmconsulting.voxbulle.**
-keep,includedescriptorclasses class tech.dmconsulting.voxbulle.<1>$$serializer { *; }

-keepclassmembers @kotlinx.serialization.Serializable class tech.dmconsulting.voxbulle.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
