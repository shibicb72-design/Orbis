# Orbis privacy policy (draft)

Last updated: November 11, 2026.

Orbis is a web browser. It has no account and shows no ads. The people who make Orbis receive no data about you
or your browsing, and there is no crash reporting. Cast talks only to a device on your local network.

## What stays on your phone
Your tabs, history, bookmarks, downloads and torrent lists, site settings, saved passwords (encrypted with a key in the Android
Keystore), preferences, and the estimated time saved from blocked YouTube ads are stored only on your phone. That
estimate records a video id and, when YouTube included one, the title, for a video whose player response scheduled
an ad break or whose ad-break request was blocked. Shorts reel ads are not counted. The dashboard calls this “ad breaks
avoided (est.)”. Private tabs keep none of these
after they close, and private tabs are not included in the estimate. Files downloaded in a private tab (file
downloads and torrents) stay inside Orbis and are deleted when private browsing ends, unless you saved them to your
Downloads first. You can delete everything in Settings > Clear
browsing data; the estimate is cleared with browsing history.

## What leaves your phone, and when
- **Websites you visit** receive what any browser sends them (your IP address, the page request, cookies they set).
  Tracking protection and, if you install it, uBlock Origin block many third-party trackers.
- **Search suggestions** (optional, on by default, never in private tabs): what you type in the address bar is sent to
  the search engine you chose, as you type.
- **Search** goes to the search engine you chose.
- **uBlock Origin** (optional): installed and updated from addons.mozilla.org (Mozilla); its filter lists are downloaded
  from their publishers.
- **Page translation**: done on your phone; the language models are downloaded from Mozilla the first time.
- **Safe Browsing**: Gecko's built-in protection checks pages against lists from Google's Safe Browsing service.
- **Voice search** uses your phone's speech recognizer (for example Google's); its privacy terms apply.
- **Updates** are delivered by Google Play.
- **Cast**: the video or audio address is sent only to the TV or speaker you pick on your local network. Orbis sends
  nothing to Google or any server for Cast. Casting YouTube opens it in the TV's YouTube app, which can show ads and
  is subject to YouTube's own terms on the TV.
- **Torrent downloads** (only after you confirm a magnet link or .torrent file): Orbis connects directly to the other
  people sharing that torrent, to its trackers, to DHT bootstrap nodes and, only if you turn local peer discovery on
  (it is off by default), to devices on your network. All of them can see your IP address, and pieces of the file are exchanged with the other people.
  Nothing else is sent: there is no account and no analytics. WebSocket (WebTorrent) trackers are never contacted.
  A torrent from a private tab uses its trackers only (no DHT, no peer exchange, no local peer discovery), is never
  written to the torrent list on disk, and leaves the list when private browsing ends. Its finished files are kept
  inside Orbis, not in your Downloads; "Save to Downloads" copies them there. Whatever was not saved is deleted when
  private browsing ends.
  Seeding (uploading pieces of a finished torrent to other people) happens only if you turn off "Stop seeding when
  finished" in Settings > Torrents; by default nothing is uploaded after a download is finished. DHT and peer
  exchange can each be turned off there too, and local peer discovery turned on. Speed limits and "Wi-Fi only" are settings on your
  phone and send nothing.

## Permissions
Camera, microphone and location are only used when a website asks and you allow it (and the camera for the QR
scanner). Notifications show download and media controls.

## Children
Orbis is a general-purpose browser and is not directed at children.

## Contact
Owner to fill in.
