![Logo](core/assets-raw/sprites/ui/logo.png)

<!-- FORK: pybridge - this is a private fork; upstream CI badge, Discord, and Trello board are not applicable here -->

The automation tower defense RTS, written in Java.

<!-- FORK: pybridge - kept only as general technical reference for upstream game internals -->
_[Wiki](https://mindustrygame.github.io/wiki)_  
_[Javadoc](https://mindustrygame.github.io/docs/)_ 

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md) for general code style and PR guidelines.

## Building

<!-- FORK: pybridge - removed link to upstream's MindustryBuilds bleeding-edge release artifacts; not published for this fork -->
First, make sure you have [JDK 17](https://adoptium.net/temurin/releases/?os=any&arch=any&version=17) installed. **Other JDK versions will not work.** Open a terminal in the Mindustry directory and run the following commands:

### Windows

_Running:_ `gradlew desktop:run`  
_Building:_ `gradlew desktop:dist`  
_Sprite Packing:_ `gradlew tools:pack`

### Linux/Mac OS

_Running:_ `./gradlew desktop:run`  
_Building:_ `./gradlew desktop:dist`  
_Sprite Packing:_ `./gradlew tools:pack`

### Server

Server builds are bundled with each released build (in Releases). If you'd rather compile on your own, replace 'desktop' with 'server', e.g. `gradlew server:dist`.

<!-- FORK: pybridge - Android build instructions removed; mobile support is intentionally dropped in this fork, see CLAUDE.md §3 -->

### Troubleshooting

#### Permission Denied

If the terminal returns `Permission denied` or `Command not found` on Mac/Linux, run `chmod +x ./gradlew` before running `./gradlew`. *This is a one-time procedure.*

#### Where is the `mindustry.gen` package?

As the name implies, `mindustry.gen` is generated *at build time* based on other code. You will not find source code for this package in the repository, and it should not be edited by hand.

The following is a non-exhaustive list of the "source" of generated code in `mindustry.gen`:

- `Call`, `*Packet` classes: Generated from methods marked with `@Remote`.
- All entity classes (`Unit`, `EffectState`, `Posc`, etc): Generated from component classes in the `mindustry.entities.comp` package, and combined using definitions in `mindustry.content.UnitTypes`.
- `Sounds`, `Musics`, `Tex`, `Icon`, etc: Generated based on files in the respective asset folders.

---

Gradle may take up to several minutes to download files. Be patient. <br>
After building, the output .JAR file should be in `/desktop/build/libs/Mindustry.jar` for desktop builds, and in `/server/build/libs/server-release.jar` for server builds.

<!-- FORK: pybridge - removed "Feature Requests" (upstream suggestions tracker) and "Downloads" (upstream store/release badges) sections; not applicable to this private fork -->
