# R8 rules for the release build type.
#
# The release build enables minification (see app/build.gradle.kts) and hands
# R8 this file alongside AGP's generated rules: the manifest classes, the
# aapt rules for everything referenced by name in XML resources, and the
# consumer rules shipped inside the AndroidX/Compose/Material AARs.
#
# No app-specific rules are needed as of now, and none are added here without
# evidence, because:
#
#   * The custom views and fragments named in res/layout/*.xml - ColorButton,
#     CropOverlayView, DrawingCanvasView, PenButton, text.MarkupEditText,
#     text.TextEditorFragment, ToolbarFragment - are looked up by name when
#     the layout is inflated. AGP feeds aapt's generated keep rules to R8, so
#     those classes are retained (and not renamed) automatically.
#   * InkTool and ColorSource are resolved through a direct valueOf(name)
#     call, and proguard-android-optimize.txt already keeps enum values() and
#     valueOf().
#   * The app has no reflection, no JNI/native code and no serialization:
#     no Class.forName/getDeclared*/loadClass/ServiceLoader/getIdentifier/
#     ObjectInputStream usage exists in app/src/main/java.
#
# Add a rule here only when something actually breaks - a build error or a
# stack trace naming a stripped member - rather than pre-emptively keeping
# whole packages.
