# RandomMesh

Random 3D meshes, generated and animated on Android. OpenGL ES 2.0, no
external 3D libraries.

## What it does

- 12 mesh kinds (including Helix Coil and Möbius Strip), 75 settings
- Live morphing from a bare base shape to full complexity
- Save Mesh: `.glb` + `.obj` + `_settings.json` to Downloads/RandomMesh
- Portrait video export (1080×1920, user-set length, no cap) replaying
  the growth
- User-configurable colour palette and scrolling procedural-texture
  background, separate from the mesh
- Quality: Standard / High / Ultra, Antialiasing (default ON)

## Stack

Kotlin / Jetpack Compose / OpenGL ES 2.0.

## Build

    ./gradlew assembleDebug

APK lands in `app/build/outputs/apk/debug/`. Needs Android SDK
(`local.properties`: `sdk.dir=/home/bobby/Android/Sdk`) and Java 17.
