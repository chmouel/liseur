# ML Kit finds its components through Firebase's ComponentDiscovery,
# which creates each registrar named in the manifest by reflection.
# firebase-components 16.1.0 keeps those classes but not their
# constructors, which R8 full mode then removes, so ML Kit fails with
# "Could not instantiate ...Registrar" and RemoteModelManager is null.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
