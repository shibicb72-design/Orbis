# Orbis privacy policy (draft)

Last updated: November 22, 2026.

Orbis is a web browser. It has no account and shows no ads. The people who make Orbis receive no data about you
or your browsing, and there is no crash reporting. Cast talks only to a device on your local network.

## What stays on your phone
Your tabs, history, bookmarks, downloads and torrent lists, site settings, saved passwords (encrypted with a key in the Android
Keystore), preferences, and the estimated time saved from blocked YouTube ads are stored only on your phone. That
estimate records a video id and, when YouTube included one, the title, for a video whose player response scheduled
an ad break or whose ad-break request was blocked. Shorts reel ads are not counted. The dashboard calls this “ad breaks
avoided (est.)”. Private tabs keep none of these
after they close, and private tabs are not included in the estimate. Files downloaded in a private tab (file
downloads and torrents) stay inside Orbis and are deleted when private browsing ends. Only a torrent you saved with
"Save to Downloads" first stays; a private file download has no such action (Share it first to keep a copy). You can delete everything in Settings > Clear
browsing data; the estimate is cleared with browsing history.

Tab groups, the order of the menu and the toolbar button you chose, the theme (including Material You, which takes
its colours from your wallpaper), search engines you add, and the secure DNS, clear on exit and private-tab lock
settings are also stored only on your phone. Clear on exit and the private-tab lock are local: nothing is sent
anywhere. Clear on exit wipes your open tabs, history, cookies, site data and cache the next time Orbis starts,
not when it closes. The private-tab lock asks for your fingerprint or screen lock when you come back to Orbis;
your fingerprint or face data stays with Android. Orbis is available in English only.

## What leaves your phone, and when
- **Websites you visit** receive what any browser sends them (your IP address, the page request, cookies they set).
  Tracking protection and, if you install it, uBlock Origin block many third-party trackers.
- **Search suggestions** (optional, on by default, never in private tabs): what you type in the address bar is sent to
  the search engine you chose, as you type.
- **Search** goes to the search engine you chose. If you add a search engine of your own, it receives what you search
  for from the address bar. Orbis does not ask an engine you added for suggestions.
- **Secure DNS** (optional, off by default): when you turn it on, Orbis sends the names of the sites you open, encrypted,
  to the provider you pick: Quad9 (dns.quad9.net), Cloudflare (cloudflare-dns.com), Mullvad (dns.mullvad.net) or an address
  you type. That provider's privacy policy applies. If the provider cannot be reached, Orbis uses your phone's DNS.
  Orbis sends nothing to its developers. When it is off, Orbis uses your phone's DNS.
- **uBlock Origin** (optional): installed and updated from addons.mozilla.org (Mozilla); its filter lists are downloaded
  from their publishers.
- **Add-ons** (optional, none installed at first): Orbis offers five add-ons: SponsorBlock, Consent-O-Matic, ClearURLs,
  Dark Reader and Violentmonkey. Each is downloaded from Mozilla's add-on store (addons.mozilla.org) when you add it,
  and updated from there. They are made by other people, not by Orbis. An add-on can see the pages it has access to;
  the Add-ons list shows that access before you add it. SponsorBlock contacts sponsor.ajay.app to look up videos.
  Other add-ons may also contact their own services. Each add-on's own privacy policy applies. An add-on's
  button or panel can open one of that add-on's own pages in a new tab; it cannot open other addresses that way.
- **Page translation**: done on your phone; the language models are downloaded from Mozilla the first time.
- **Safe Browsing**: Gecko's built-in protection checks pages against lists from Google's Safe Browsing service.
- **Engine data updates**: the Gecko engine downloads its own safety and compatibility data from Mozilla's settings service, for example lists of revoked certificates and blocked add-ons. Orbis does not send your browsing history with these requests.
- **Voice search** uses your phone's speech recognizer (for example Google's); its privacy terms apply.
- **Updates** are delivered by Google Play.
- **Cast**: the video or audio address is sent only to the TV or speaker you pick on your local network. Orbis sends
  nothing to Google or any server for Cast. Casting YouTube opens it in the TV's YouTube app, which can show ads and
  is subject to YouTube's own terms on the TV.
- **Torrent downloads** (only after you confirm a magnet link or .torrent file): Orbis connects directly to the other
  people sharing that torrent, to its trackers, to DHT bootstrap nodes and, only if you turn local peer discovery on
  (it is off by default), to devices on your network. All of them can see your IP address, and pieces of the file are exchanged with the other people.
  Nothing else is sent: there is no account and no analytics. Only http, https and udp trackers are contacted (never WebSocket/WebTorrent ones).
  A torrent from a private tab finds peers like any other torrent (its trackers, the DHT and peer exchange, if those
  are on in Settings > Torrents), so the same parties can see your IP address. Local peer discovery is never used for
  it. It is never written to the torrent list on disk, and its files are deleted when private browsing ends unless you
  save them. Its finished files are kept
  inside Orbis, not in your Downloads; "Save to Downloads" copies them there. Whatever was not saved is deleted when
  private browsing ends.
  Seeding (uploading pieces of a finished torrent to other people) happens only if you turn off "Stop seeding when
  finished" in Settings > Torrents; by default nothing is uploaded after a download is finished. DHT and peer
  exchange can each be turned off there too, and local peer discovery turned on. Speed limits and "Wi-Fi only" are settings on your
  phone and send nothing.
- Video diagnostics (off unless you turn it on in Settings): while it is on, Orbis keeps a log on your phone of how videos played: times, stops, errors, the type of connection and a few settings. The log has no page addresses or titles. It names a site only if you turn on "Include site names", and it leaves out private tabs unless you turn on "Also record private tabs". Orbis never sends this log anywhere. You can read, copy, save and delete it in Settings. Recording turns itself off after 7 days. The log is deleted 7 days after its last entry, or the next time Orbis starts after that.

## Backup
Backup is optional and only runs when you use Settings > Backup and restore. Orbis saves one file where you choose.
It holds your bookmarks (with folders), saved passwords, a fixed list of settings (including the torrent settings), site
permission answers you saved, and which add-ons are installed (not their data). Open tabs (addresses and titles) are
included if you leave "Include open tabs" ticked, and browsing history only if you tick it. The file is encrypted with a
password you type. It does not hold cookies or sign-ins, site data, downloads, private tabs, add-on data or the sites
where you turned tracking protection off. Orbis sends the file nowhere and keeps no copy of it or of the password;
if you forget the password, the file cannot be opened. If you turn on fingerprint unlock for backups, the password is
kept on your phone only, sealed with a key in the Android Keystore that needs your fingerprint. Restoring adds
bookmarks and passwords to what is on the phone, replaces the settings in the file, and never installs add-ons.

## Permissions
Camera, microphone and location are only used when a website asks and you allow it (and the camera for the QR
scanner). Notifications show download and media controls. Biometrics unlock saved passwords, backups and the private-tab lock.

## Children
Orbis is a general-purpose browser and is not directed at children.

## Contact
Owner to fill in.
