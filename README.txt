MEDIARIFT 3.17.0+26.3   (Minecraft 26.3, Fabric, Java 25)   -- project v45
=======================================================================
A web browser in a small window inside Minecraft. Listen to music (YouTube Music), watch YouTube, and watch Twitch /
Kick live streams without leaving the game. The window can be placed anywhere on the screen, pinned, resized and
controlled with hotkeys. Client-side only (nothing to install on a server).

BUILD
  Needs JDK 25 (Gradle downloads it by itself via toolchain if it is not installed). The first build downloads Gradle 9.7 (takes a bit longer).
  gradlew.bat build   ->   build/libs/mediarift-3.17.0+26.3.jar   (NOT the -sources jar)

THE mods FOLDER MUST HAVE
  - this jar
  - Fabric API 0.166.0+26.3 (or newer for 26.3)
  - Rinku (Fabric, 26.3) from https://modrinth.com/mod/rinku   -- the browser library
  - Mod Menu 21.x (optional, for the settings button; the settings also open with a hotkey)
  Remove old music_browser-*.jar files (the old name of this mod). Your old settings, start page and downloaded
  streamlink / ffmpeg are moved to the new names by themselves, once.

FIRST START
  Rinku downloads Chromium once. After that, on the title screen, MediaRift downloads streamlink + ffmpeg (Windows
  only) for live streams. If the download does not move, turn off the antivirus while it downloads, or restart the game.

HOW TO USE
  1. Open the chat (T). Click the song bar: the window slides out.
  2. Click inside the window (green frame = the keyboard goes to the page, Esc = back to the chat).
  3. Search a song and press play. The bar shows "Artist - Song".
  4. The thumbtack next to the bar: white = only in the chat, green = pinned (stays when the chat is closed),
     purple = super pin (visible on every screen, music keeps playing outside the world).
  5. T opens the same panel in every menu (pause menu, title screen, options ...).
     The hotkeys (volume, pin, play/pause, ...) also work in every menu, pinned or not (not while you type into a text box).

HOTKEYS (default = numpad; change them in the settings tab "Keys" or in Controls)
  Num 0  open / close the window        Num 5  play / pause
  Num 4  previous                       Num 6  next
  Num 8  volume +5%                     Num 2  volume -5%
  Num .  change the pin                 Num *  reload the page
  Num -  page back                      Num +  page forward
  Num /  open the settings              Num 1  player size: small -> normal -> big
  Num 7  previous Short / Reel          Num 9  next Short / Reel
  A key can be a combination with Ctrl / Shift / Alt (for example Ctrl + Num 7).

COMMANDS (type in the chat; the site you open is remembered for next time)
  /sb twitch | kick | youtube | ytmusic       open the site
  /sb live [address | stop | status]          a stream through streamlink + ffmpeg
  /sb url <address>                           any address
  /psb reset | reload | install               log out of all sites / reload the page / download streamlink + ffmpeg

SETTINGS  (Mod Menu -> MediaRift -> settings, or the hotkey; saved in config/mediarift.json)
  Look & place    size, width, height, site zoom (Small / Normal / Big), place on the screen, stream resolution
                  (720 recommended, 1080 maximum, 480 for weak PCs), stream codec (VP9 / VP8 for very weak PCs),
                  window in menus (in front of / behind the buttons), visibility of the window and of the buttons.
  Buttons & pin   every button, the song bar, the volume slider, the pin (off / green / super).
  Keys            all hotkeys.

LIVE STREAMS
  Browsers inside Minecraft cannot play H.264 / AAC, which Twitch, Kick and YouTube live use. MediaRift converts them on
  the fly:  streamlink -> ffmpeg (H.264 -> VP9, AAC -> Opus) -> a local server (127.0.0.1 only) -> the player window.
  Only live streams are converted; normal videos and music are untouched. Expect the picture a few seconds behind.
  If the player says your computer is too slow: choose a lower resolution or the VP8 codec in the settings.
  Not on Windows: install them yourself (ffmpeg, streamlink) or set ffmpegPath / streamlinkPath in config/mediarift.json.

LANGUAGES
  The texts follow the Minecraft language: Russian -> Russian, every English (US, UK, ...) and any other language -> English.
  Texts live in src/main/resources/assets/mediarift/lang (en_us.json, ru_ru.json); a new language = a new file there.

Why not Spotify? Spotify needs the "Widevine" copy protection, which the browser inside Minecraft does not have.
